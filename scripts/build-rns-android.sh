#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUST_CRATE="$REPO_ROOT/rust/protocolx-rns"
JNI_OUT="$REPO_ROOT/android/app/src/main/jniLibs"
API_LEVEL="${ANDROID_API_LEVEL:-26}"

if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
  SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/opt/homebrew/share/android-commandlinetools}}"
  if [[ -d "$SDK_ROOT/ndk" ]]; then
    ANDROID_NDK_HOME="$(find "$SDK_ROOT/ndk" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n 1)"
  fi
fi

if [[ -z "${ANDROID_NDK_HOME:-}" || ! -d "$ANDROID_NDK_HOME" ]]; then
  echo "error: ANDROID_NDK_HOME is not set and no NDK was found under \$ANDROID_HOME/ndk" >&2
  exit 1
fi
export ANDROID_NDK_HOME

if ! command -v cargo-ndk >/dev/null 2>&1; then
  echo "error: cargo-ndk is not installed (run: RUSTC_WRAPPER=\"\" cargo install cargo-ndk --locked)" >&2
  exit 1
fi

export RUSTC_WRAPPER=""

mkdir -p "$JNI_OUT"
cd "$RUST_CRATE"

cargo ndk \
  -t arm64-v8a \
  -t x86_64 \
  --platform "$API_LEVEL" \
  -o "$JNI_OUT" \
  build --release

ls -lh "$JNI_OUT"/arm64-v8a/libprotocolx_rns.so "$JNI_OUT"/x86_64/libprotocolx_rns.so
