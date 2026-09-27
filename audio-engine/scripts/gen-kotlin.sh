#!/usr/bin/env sh
# Builds the engine and writes its Kotlin bindings to bindings/kotlin.
# Run from anywhere; works on Linux, macOS and Git Bash on Windows.
set -eu
cd "$(dirname "$0")/.."

cargo build --release --lib

case "$(uname -s)" in
    Darwin) lib=target/release/libocto_audio.dylib ;;
    MINGW* | MSYS* | CYGWIN*) lib=target/release/octo_audio.dll ;;
    *) lib=target/release/libocto_audio.so ;;
esac

cargo run --release --features bindgen --bin uniffi-bindgen -- \
    generate --library "$lib" --language kotlin --out-dir bindings/kotlin --no-format

# The generator's own comments use long dashes; the repo style has none.
find bindings/kotlin -name '*.kt' -exec perl -CSD -pi -e 's/ \x{2014} /, /g' {} +

echo "Kotlin bindings written to bindings/kotlin; native library at $lib"
