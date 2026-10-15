#!/usr/bin/env bash
set -Eeuo pipefail

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd -- "$ROOT"
TOOLS="${SPOTIFAST_ANDROID_TOOLS:-$ROOT/.cache/android}"
SDK="${ANDROID_HOME:-$TOOLS/sdk}"
NDK="${ANDROID_NDK_HOME:-$SDK/ndk/27.2.12479018}"
BUILD_TOOLS_VERSION="${SPOTIFAST_ANDROID_BUILD_TOOLS_VERSION:-35.0.0}"
BUILD_TOOLS="$SDK/build-tools/$BUILD_TOOLS_VERSION"
ANDROID_JAR="$SDK/platforms/android-35/android.jar"
CARGO_HOME="${CARGO_HOME:-$TOOLS/cargo}"
RUSTUP_HOME="${RUSTUP_HOME:-$TOOLS/rustup}"
KEYSTORE="${SPOTIFAST_ANDROID_KEYSTORE:-$TOOLS/debug.keystore}"
KEYSTORE_PASSWORD="${SPOTIFAST_ANDROID_KEYSTORE_PASSWORD:-android}"
KEY_ALIAS="${SPOTIFAST_ANDROID_KEY_ALIAS:-androiddebugkey}"
TIMEOUT_SECONDS="${SPOTIFAST_ANDROID_BUILD_TIMEOUT_SECONDS:-1800}"
ANDROID_TARGETS=(
    aarch64-linux-android:arm64-v8a:aarch64-linux-android
    armv7-linux-androideabi:armeabi-v7a:arm-linux-androideabi
    x86_64-linux-android:x86_64:x86_64-linux-android
)
OUTPUT="$ROOT/target/android/spotifast-universal.apk"

fail() {
    printf 'Android build: %s\n' "$*" >&2
    exit 1
}

need_command() {
    command -v "$1" >/dev/null 2>&1 || fail "required command not found: $1"
}

need_file() {
    [[ -f "$1" ]] || fail "required file not found: $1"
}

for tool in aapt apksigner d8 zipalign; do
    [[ -x "$BUILD_TOOLS/$tool" ]] || fail "Android build tool not found: $BUILD_TOOLS/$tool"
done
need_file "$NDK/source.properties"
need_file "$ANDROID_JAR"
[[ "$TIMEOUT_SECONDS" =~ ^[1-9][0-9]*$ ]] || fail "timeout must be a positive integer"

export CARGO_HOME RUSTUP_HOME ANDROID_HOME="$SDK" ANDROID_NDK_HOME="$NDK"
export ANDROID_NDK_ROOT="$NDK" CC="${CC:-/usr/bin/gcc}"
export CARGO_APK_RELEASE_KEYSTORE="$KEYSTORE"
export CARGO_APK_RELEASE_KEYSTORE_PASSWORD="$KEYSTORE_PASSWORD"
export PATH="$CARGO_HOME/bin:$TOOLS/cargo-apk/bin:$PATH"

for command in cargo cargo-apk javac keytool timeout flock find; do
    need_command "$command"
done

PACKAGE_DIR="$ROOT/target/android"
mkdir -p -- "$PACKAGE_DIR"
exec 9>"$PACKAGE_DIR/.build.lock"
flock -n 9 || fail "another Android package build is using $PACKAGE_DIR"
STAGE="$(mktemp -d "$PACKAGE_DIR/.stage.XXXXXX")"
trap 'rm -rf -- "$STAGE"' EXIT

if [[ ! -f "$KEYSTORE" ]]; then
    [[ "$KEYSTORE" == "$TOOLS/debug.keystore" ]] || fail "configured signing keystore not found: $KEYSTORE"
    mkdir -p -- "$(dirname -- "$KEYSTORE")"
    keytool -genkey -v -keystore "$KEYSTORE" -storepass android \
        -alias "$KEY_ALIAS" -keypass android \
        -dname 'CN=Android Debug,O=Android,C=US' -keyalg RSA -keysize 2048 -validity 10000
fi

TARGET_DIR="${CARGO_TARGET_DIR:-$ROOT/target}"
BUILD_STARTED=$SECONDS
mkdir -p -- "$STAGE/lib"
for target_and_abi in "${ANDROID_TARGETS[@]}"; do
    target="${target_and_abi%%:*}"
    abi_and_linker_abi="${target_and_abi#*:}"
    abi="${abi_and_linker_abi%%:*}"
    linker_abi="${abi_and_linker_abi#*:}"
    remaining=$((TIMEOUT_SECONDS - (SECONDS - BUILD_STARTED)))
    ((remaining > 0)) || fail "Android native build exceeded ${TIMEOUT_SECONDS}s deadline"
    timeout --kill-after=2s "$remaining" cargo apk build --release --lib \
        --no-default-features --target "$target" --package spotifast
    native_lib="$TARGET_DIR/release/apk/lib/$abi/libspotifast.so"
    need_file "$native_lib"
    mkdir -p -- "$STAGE/lib/$abi"
    cp -- "$native_lib" "$STAGE/lib/$abi/libspotifast.so"
    cxx_runtime="$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/$linker_abi/libc++_shared.so"
    need_file "$cxx_runtime"
    cp -- "$cxx_runtime" "$STAGE/lib/$abi/libc++_shared.so"
done

mapfile -d '' JAVA_SOURCES < <(find "$ROOT/android/src/main/java" -type f -name '*.java' -print0)
((${#JAVA_SOURCES[@]} > 0)) || fail "no Android Java sources found"
mkdir -p -- "$STAGE/classes" "$STAGE/dex"
javac -source 8 -target 8 -bootclasspath "$ANDROID_JAR" \
    -d "$STAGE/classes" "${JAVA_SOURCES[@]}"
mapfile -d '' JAVA_CLASSES < <(find "$STAGE/classes" -type f -name '*.class' -print0)
((${#JAVA_CLASSES[@]} > 0)) || fail "javac produced no class files"
"$BUILD_TOOLS/d8" --lib "$ANDROID_JAR" --min-api 25 \
    --output "$STAGE/dex" "${JAVA_CLASSES[@]}"
need_file "$STAGE/dex/classes.dex"

UNSIGNED="$STAGE/unsigned.apk"
ALIGNED="$STAGE/aligned.apk"
SIGNED="$STAGE/spotifast-universal.apk"
"$BUILD_TOOLS/aapt" package -f -M "$ROOT/android/AndroidManifest.xml" \
    -S "$ROOT/android/res" -I "$ANDROID_JAR" -F "$UNSIGNED"
cp -- "$STAGE/dex/classes.dex" "$STAGE/classes.dex"
mapfile -d '' APK_LIBS < <(find "$STAGE/lib" -type f -name '*.so' -printf '%P\0')
((${#APK_LIBS[@]} == 2 * ${#ANDROID_TARGETS[@]})) || fail "not all Android native libraries were packaged"
(
    cd -- "$STAGE"
    "$BUILD_TOOLS/aapt" add "$UNSIGNED" classes.dex "${APK_LIBS[@]/#/lib/}"
)
"$BUILD_TOOLS/zipalign" -f 4 "$UNSIGNED" "$ALIGNED"
printf '%s\n%s\n' "$KEYSTORE_PASSWORD" "$KEYSTORE_PASSWORD" |
    "$BUILD_TOOLS/apksigner" sign --ks "$KEYSTORE" --ks-key-alias "$KEY_ALIAS" \
        --ks-pass stdin --key-pass stdin --out "$SIGNED" "$ALIGNED"
"$BUILD_TOOLS/apksigner" verify --verbose "$SIGNED" >/dev/null

MANIFEST_TREE="$("$BUILD_TOOLS/aapt" dump xmltree "$SIGNED" AndroidManifest.xml)"
for expected in 'rocks.spotifast' 'Spotifast' 'SpotifastActivity' \
    'PlaybackService' 'FOREGROUND_SERVICE_MEDIA_PLAYBACK' 'minSdkVersion' 'targetSdkVersion'; do
    [[ "$MANIFEST_TREE" == *"$expected"* ]] || fail "APK manifest is missing: $expected"
done
BADGING="$("$BUILD_TOOLS/aapt" dump badging "$SIGNED")"
NATIVE_CODE="$(sed -n '/^native-code:/p' <<<"$BADGING")"
for target_and_abi in "${ANDROID_TARGETS[@]}"; do
    abi_and_linker_abi="${target_and_abi#*:}"
    abi="${abi_and_linker_abi%%:*}"
    [[ "$NATIVE_CODE" == *"$abi"* ]] || fail "APK does not contain $abi native code"
done
mkdir -p -- "$(dirname -- "$OUTPUT")"
mv -- "$SIGNED" "$OUTPUT"
printf 'Built and verified: %s\n' "$OUTPUT"
