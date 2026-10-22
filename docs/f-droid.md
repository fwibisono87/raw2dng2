# F-Droid preparation

The listing metadata in [`fastlane/metadata/android/en-US/`](../fastlane/metadata/android/en-US/) follows F-Droid's Fastlane-compatible source layout. The release in `app/version.properties` is `1.0.0`; `app/build.gradle.kts` derives version code `10000`, which is the changelog filename. The phone screenshots are reused from the app's existing documentation. The tablet screenshots are from a 10.5-inch tablet and are filed under `tenInchScreenshots`.

F-Droid's package build recipe belongs in its separate [`fdroiddata`](https://gitlab.com/fdroid/fdroiddata) repository. The following is only a handoff sketch of a few known values; it is not a complete or executable fdroiddata metadata file:

```yaml
# Incomplete outline only. Do not submit as-is.
RepoType: git
Repo: https://github.com/fwibisono87/raw2dng2
Builds:
  - versionName: 1.0.0
    versionCode: 10000
    commit: v1.0.0
    ndk: 28.2.13676358
    gradle: yes
```

Leave the recipe at the repository root: `settings.gradle.kts` includes `:app`, while the Rust task uses root-relative `native/Cargo.toml` and `native/target` paths. The app's Gradle task can compile `arm64-v8a`, `armeabi-v7a`, and `x86_64` for debug, but release staging currently copies only the two ARM libraries. The task compiles `native/` with Cargo and uses NDK `28.2.13676358` with the NDK's API 21 clang driver. Android also requires compile SDK 36 and Build Tools 36.0.0. This is not yet an F-Droid build recipe: it still needs the builder's Rust toolchain and Android targets, an offline Cargo source configuration, and verification of the buildserver command and output. This repository currently says “stable” in its build instructions and does not pin a Rust compiler version; F-Droid's reproducible-build guidance says Rust should be pinned to the same version as upstream.

The Cargo lockfile fixes crate versions and checksums but the repository does not contain a Cargo vendor directory. With a populated Cargo registry, `cargo vendor --offline --locked --manifest-path native/Cargo.toml /tmp/raw2dng2-fdroid-vendor` succeeds. A clean F-Droid build has not been run, so this does not verify dependency availability or the build in F-Droid's isolated environment. The generated vendor tree is about 203 MiB; it is not checked in here.

## Review items before submission

- The project license and notice identify the app code as MIT and `rawler` 0.8.0 as LGPL-2.1. However, `app/src/main/cpp/dng_sdk/` also contains 161 legacy Adobe DNG SDK files. Their headers refer to an accompanying Adobe license agreement, but that agreement is not present in this repository. The current Gradle build has no CMake or `externalNativeBuild` configuration, so these files are outside the configured build path; their terms still need to be established for source-license review. This PR does not claim F-Droid eligibility.
- No F-Droid buildserver run has checked source scanning, the release APK's ABI contents, or a clean unsigned release build.
- Reproducibility has not been tested against the signed GitHub release APK. F-Droid documents copying an upstream signature only after its build matches; matching signatures and enabling same-key distribution remain unverified.

F-Droid's current guidance: [inclusion policy](https://f-droid.org/docs/Inclusion_Policy/), [Fastlane metadata layout](https://f-droid.org/docs/All_About_Descriptions_Graphics_and_Screenshots/), [build metadata reference](https://f-droid.org/docs/Build_Metadata_Reference/), and [reproducible builds](https://f-droid.org/docs/Reproducible_Builds/).
