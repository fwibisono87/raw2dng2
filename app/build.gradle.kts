import java.util.Properties

plugins {
    id("com.android.application")
}

val rustAbis = mapOf(
    "arm64-v8a" to Triple("aarch64-linux-android", "aarch64-linux-android", "aarch64-linux-android"),
    "armeabi-v7a" to Triple("armv7-linux-androideabi", "armv7a-linux-androideabi", "armv7-linux-androideabi"),
    "x86_64" to Triple("x86_64-linux-android", "x86_64-linux-android", "x86_64-linux-android"),
)
val selectedRustAbis = providers.gradleProperty("rust.abis")
    .orElse("arm64-v8a,armeabi-v7a,x86_64")
    .get()
    .split(',')
    .map(String::trim)
    .filter(String::isNotEmpty)
    .also { requested -> require(requested.all(rustAbis::containsKey)) { "Unsupported Rust ABI in rust.abis=$requested" } }
val generatedDebugJni = layout.buildDirectory.dir("generated/rustJniLibs/debug")
val generatedReleaseJni = layout.buildDirectory.dir("generated/rustJniLibs/release")
val rustInputs = files(fileTree("../native/src"), rootProject.file("native/Cargo.toml"), rootProject.file("native/Cargo.lock"))
val rustAbiSetting = providers.gradleProperty("rust.abis").orElse("arm64-v8a,armeabi-v7a,x86_64")
val releaseStoreFile = providers.gradleProperty("release.storeFile")
val releaseStorePassword = providers.gradleProperty("release.storePassword")
val releaseKeyAlias = providers.gradleProperty("release.keyAlias")
val releaseKeyPassword = providers.gradleProperty("release.keyPassword")
val release = Properties().apply { file("version.properties").inputStream().use { load(it) } }
val appVersionName: String = release.getProperty("versionName")
val appVersionCode = appVersionName.split('.').map(String::toInt)
    .also { require(it.size == 3) { "versionName must look like 1.2.3, got $appVersionName" } }
    .let { (major, minor, patch) -> major * 10000 + minor * 100 + patch }

val sdkPath = providers.provider {
    val properties = Properties()
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use { properties.load(it) }
    properties.getProperty("sdk.dir")
        ?: System.getenv("ANDROID_HOME")
        ?: System.getenv("ANDROID_SDK_ROOT")
        ?: error("Set sdk.dir in local.properties or ANDROID_HOME")
}
val cargo = providers.environmentVariable("CARGO").orElse("cargo")
val ndkVersionForRust = "28.2.13676358"
val readyMarker = layout.buildDirectory.file("intermediates/rustJniReady/ready")

val prepareRustJniLibs = tasks.register("prepareRustJniLibs") {
    inputs.property("rust.abis", rustAbiSetting)
    outputs.file(readyMarker)
    doLast {
        generatedDebugJni.get().asFile.deleteRecursively()
        generatedReleaseJni.get().asFile.deleteRecursively()
        generatedDebugJni.get().asFile.mkdirs()
        generatedReleaseJni.get().asFile.mkdirs()
        readyMarker.get().asFile.apply { parentFile.mkdirs(); writeText("${selectedRustAbis.joinToString()}\n") }
    }
}

val rustBuildTasks = selectedRustAbis.map { abi ->
    val (triple, clangTriple, ccTarget) = rustAbis.getValue(abi)
    val debugOutput = generatedDebugJni.get().file("$abi/libraw2dng.so")
    val releaseOutput = generatedReleaseJni.get().file("$abi/libraw2dng.so")
    tasks.register<Exec>("buildRust${abi.split('-', '_').joinToString("") { part -> part.replaceFirstChar(Char::uppercase) }}") {
        group = "build"
        description = "Builds raw2dng for $abi."
        dependsOn(prepareRustJniLibs)
        inputs.files(rustInputs)
        inputs.property("target", triple)
        inputs.property("ndkVersion", ndkVersionForRust)
        outputs.file(debugOutput)
        if (abi != "x86_64") outputs.file(releaseOutput)
        workingDir(rootProject.projectDir)
        val ndk = sdkPath.get() + "/ndk/$ndkVersionForRust"
        val prebuilt = "$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin"
        val linkerName = "${clangTriple}21-clang"
        commandLine(cargo.get(), "build", "--manifest-path", "native/Cargo.toml", "--release", "--target", triple)
        environment("CARGO_TARGET_${triple.uppercase().replace('-', '_')}_LINKER", "$prebuilt/$linkerName")
        environment("CC_$ccTarget", "$prebuilt/$linkerName")
        environment("AR_$ccTarget", "$prebuilt/llvm-ar")
        environment("CC_${ccTarget.replace('-', '_')}", "$prebuilt/$linkerName")
        environment("AR_${ccTarget.replace('-', '_')}", "$prebuilt/llvm-ar")
        environment("RUSTFLAGS", "-C link-arg=-Wl,-z,max-page-size=16384")
        environment("CARGO_HOME", rootProject.file(".cargo-home").absolutePath)
        doLast {
            val nativeLibrary = rootProject.file("native/target/$triple/release/libraw2dng.so")
            require(nativeLibrary.isFile) { "Rust build did not produce $nativeLibrary" }
            debugOutput.asFile.apply { parentFile.mkdirs(); nativeLibrary.copyTo(this, overwrite = true) }
            if (abi != "x86_64") {
                releaseOutput.asFile.apply { parentFile.mkdirs(); nativeLibrary.copyTo(this, overwrite = true) }
            }
        }
    }
}

val buildRustNative = tasks.register("buildRustNative") {
    group = "build"
    description = "Builds rawler for the selected Android ABIs."
    inputs.files(rustInputs)
    inputs.property("rust.abis", rustAbiSetting)
    inputs.property("ndkVersion", ndkVersionForRust)
    outputs.dir(generatedDebugJni)
    outputs.dir(generatedReleaseJni)
    dependsOn(rustBuildTasks)
}

android {
    namespace = "com.raw2dng"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    ndkVersion = ndkVersionForRust

    defaultConfig {
        applicationId = "com.franciswibisono.raw2dng2"
        testApplicationId = "com.franciswibisono.raw2dng2.test"
        minSdk = 21
        targetSdk = 36
        testInstrumentationRunner = "android.test.InstrumentationTestRunner"
        versionCode = appVersionCode
        versionName = appVersionName
        resValue("string", "release_codename", release.getProperty("codename"))
        resValue("string", "release_codename_url", release.getProperty("codenameUrl").orEmpty())
        ndk { abiFilters += setOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }

    if (releaseStoreFile.isPresent) {
        signingConfigs.create("providedRelease") {
            storeFile = rootProject.file(releaseStoreFile.get())
            storePassword = releaseStorePassword.get()
            keyAlias = releaseKeyAlias.get()
            keyPassword = releaseKeyPassword.get()
        }
    }

    sourceSets.getByName("debug").jniLibs.directories.add(generatedDebugJni.get().asFile.absolutePath)
    sourceSets.getByName("release").jniLibs.directories.add(generatedReleaseJni.get().asFile.absolutePath)

    buildTypes {
        debug { isMinifyEnabled = false }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = if (releaseStoreFile.isPresent) {
                signingConfigs.getByName("providedRelease")
            } else {
                signingConfigs.getByName("debug")
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures { resValues = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging { jniLibs { useLegacyPackaging = false } }
}

dependencies {
    androidTestCompileOnly(files(
        "${sdkPath.get()}/platforms/android-36/optional/android.test.base.jar",
        "${sdkPath.get()}/platforms/android-36/optional/android.test.runner.jar"
    ))
}

tasks.named("preBuild").configure { dependsOn(buildRustNative) }
