#!/bin/sh
# The Rust core for Android: the phone's library, this computer's library (for the unit tests) and the Kotlin bindings.
# Never while Gradle runs (the ThinkPad manages one big build at a time).
set -e
cd "$(dirname "$0")/../core"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$HOME/Android/Sdk/ndk/28.2.13676358}"
nice cargo ndk -t arm64-v8a -P 26 build -j 3 --release --features mobile
nice cargo build -j 3 --release --features mobile
mkdir -p ../android/app/src/main/jniLibs/arm64-v8a
cp target/aarch64-linux-android/release/liblichat_core.so ../android/app/src/main/jniLibs/arm64-v8a/
nice cargo run -j 3 --release --features bindgen --bin uniffi-bindgen -- generate --library target/release/liblichat_core.so \
    --language kotlin --no-format --out-dir ../android/app/src/main/java
echo "Kern für Android fertig"
