# Build KzDesk Remote

This guide describes the current Android project configuration. The source tree includes the FreeRDP native client, so the first complete APK build may take substantially longer than a Kotlin-only check.

## Requirements

| Component | Version / package |
|---|---|
| JDK | 17 or newer; CI uses Temurin 21 |
| Gradle | Wrapper in this repository (9.6.1) |
| Android Gradle Plugin | 9.2.1 |
| Android SDK platform | `android-37.2` |
| Android Build Tools | `37.0.0` |
| Android NDK | `29.0.13113456` |
| CMake | `4.1.2` |

The ABI splits and other Android build settings are in `client/Android/Studio/release.properties`. The app targets API 37 and is configured with minimum API 24 (Android 7.0+), which includes Android 16 (API 36). It builds separate APKs for `armeabi-v7a`, `arm64-v8a`, and `x86_64`; the 32-bit ARM split is needed for older devices. This is build configuration, not runtime proof: API 24 and API 36 device/emulator smoke tests are still pending.

## Local build

1. Install the components listed above with Android Studio's SDK Manager.
2. Open `client/Android/Studio` in Android Studio, or run Gradle from that directory.
3. Run the JVM tests and Kotlin compilation:

   ```sh
   bash gradlew --no-daemon :core-rdp:testDebugUnitTest
   bash gradlew --no-daemon :app:compileDebugKotlin
   ```

4. Build the debug APKs (including FreeRDP native libraries):

   ```sh
   bash gradlew --no-daemon assembleDebug
   ```

Outputs are under `app/build/outputs/apk/debug/`. Release outputs are under `app/build/outputs/apk/release/`.

## Release signing

A release build requires a dedicated release keystore and all four settings below. Supply them as environment variables to Gradle; do not commit the keystore, passwords, or signing properties to the repository.

- `RELEASE_STORE_FILE` — absolute path to the JKS keystore.
- `RELEASE_STORE_PASSWORD`
- `RELEASE_KEY_ALIAS`
- `RELEASE_KEY_PASSWORD`

The build no longer falls back to Android's debug keystore for release signing. A missing or invalid signing setup must fail rather than produce a debug-signed release. Keep the keystore in a trusted location and make an encrypted backup before using it for distribution. Replacing the signing key can prevent existing installs from accepting an in-place update.

GitHub Actions obtains release signing material from repository secrets, verifies the signatures, and removes the temporary keystore from the runner. The build job has read-only repository permission; a separate job publishes tagged releases. Temporary workflow artifacts expire after a short retention period.

## GitHub Actions

Workflow: `.github/workflows/build-apk.yml`.

- Pushes to `main` run JVM tests, compile Kotlin, and build debug APKs.
- A `v*` tag pointing to a commit reachable from `main` runs the release build and publishes signed APKs as a GitHub Release.
- Manual runs can select a debug or release build. A manual release build is accepted only on `main`, produces a short-lived workflow artifact, and does not create a GitHub Release.
- Repeated runs for the same ref cancel the older run. Debug and signed-release artifacts have explicit short retention periods.

## Troubleshooting

| Symptom | Check |
|---|---|
| Gradle reports an unsupported Java version | Use JDK 17 or newer; JDK 21 matches CI. |
| Android SDK or NDK not found | Install the exact SDK, NDK, Build Tools, and CMake package versions above; check `ANDROID_HOME` / `ANDROID_SDK_ROOT`. |
| Native build fails while fetching dependencies | Check network access, proxy settings, and TLS inspection on the build host. |
| Release signing fails | Confirm the keystore path exists and all four signing environment variables match that keystore. Never substitute the debug keystore for a release key. |
| Old native outputs cause a local build failure | Clean the affected Gradle/native build outputs and rebuild; do not commit generated `.cxx`, `build`, or `jniLibs` outputs. |
