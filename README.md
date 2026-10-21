# Raw2DNG

Raw2DNG is a small Android app for converting camera RAW files to DNG or JPEG. It uses [rawler](https://github.com/dnglab/dnglab), the pure Rust engine used by dnglab. Android file picker and document provider URIs are read directly, including cloud-backed files.

The app supports the camera formats decoded by rawler. See [dnglab's supported cameras list](https://github.com/dnglab/dnglab/blob/main/rawler/SUPPORTED_CAMERAS.md); support depends on the camera model and rawler version. Raw2DNG currently pins rawler 0.8.0.

Screenshots: to be added after device review.

## Build prerequisites

- JDK 21
- Android SDK Platform 36 and Build Tools 36.0.0
- Android NDK 28.2.13676358
- Stable Rust with Android targets installed:

  ```sh
  rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
  ```

Set `ANDROID_HOME` or `ANDROID_SDK_ROOT`, or place `sdk.dir=/path/to/Android/Sdk` in `local.properties`. Gradle builds the native library for `arm64-v8a`, `armeabi-v7a`, and `x86_64`; narrow a local build with `-Prust.abis=arm64-v8a`.

```sh
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
./gradlew :app:lintDebug
```

The debug and default release variants use a local Android debug key under the ignored app build directory. Provide `release.storeFile`, `release.storePassword`, `release.keyAlias`, and `release.keyPassword` Gradle properties to sign release builds with a separate key before publishing.

The Rust core can also be developed and tested on the host:

```sh
cd native
cargo test --release
RAW2DNG_SAMPLE=/path/to/sample.CR2 cargo test --release
cargo clippy --release
```

## License

The Raw2DNG app code is MIT licensed; see [LICENSE](LICENSE). The native conversion engine uses rawler, distributed under LGPL-2.1. The Rust integration and pinned dependency are in this repository, and rawler's upstream source is available at [dnglab/dnglab](https://github.com/dnglab/dnglab). See [NOTICE](NOTICE) for attribution.
