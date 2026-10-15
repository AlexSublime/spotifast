# Android build

The Android target uses `NativeActivity`, Android's document picker, a system
media session, and a `mediaPlayback` foreground service. Desktop dependencies
and desktop entry points remain selected on their existing target platforms.

## Requirements

- Android SDK platform 35 and build-tools 35.0.0
- NDK 27.2.12479018
- JDK with `javac`, `java`, and `keytool`
- Rust toolchain pinned by `rust-toolchain.toml`, with
  `aarch64-linux-android`, `armv7-linux-androideabi` and
  `x86_64-linux-android` installed
- `cargo-apk` 0.10.0

The local build script reads tool paths from `ANDROID_HOME`,
`ANDROID_NDK_HOME`, `CARGO_HOME`, and `RUSTUP_HOME`. By default it expects the
isolated tools under `.cache/android`.

## Build

```sh
scripts/build-android.sh
```

The script compiles the native library without the desktop MilkDrop child
window for ARM64, ARMv7 and x86_64, compiles the Android activity and playback
service, assembles the manifest and native libraries, aligns the APK, signs it,
and checks the package name, service declaration, minimum SDK and ABIs. Output:

```text
target/android/spotifast-universal.apk
```

The default key is a local debug key at `.cache/android/debug.keystore`, with
the standard debug password. It is suitable for local installs only. For a
release key, set `SPOTIFAST_ANDROID_KEYSTORE`,
`SPOTIFAST_ANDROID_KEYSTORE_PASSWORD`, and `SPOTIFAST_ANDROID_KEY_ALIAS`; keep
the key and password outside Git. The password is passed to the build tools via
environment and standard input, not command-line arguments.

## Compatibility

The APK declares API 25 as its minimum and includes ARM64, ARMv7 and x86_64.
That ABI/API combination matches the MXQ Pro RK3228A's ARMv7 and Android
7.1/API 25. Android uses CPAL 0.15.3 with Oboe; desktop remains on CPAL
0.18.1. Desktop MilkDrop remains available on desktop builds; its separate
child window is omitted from Android builds.

The APK must still be installed and exercised on a physical device to validate
OAuth, background playback, notification controls, and SAF picker behavior.
