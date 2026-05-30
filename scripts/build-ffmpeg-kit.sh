#!/usr/bin/env bash
# build-ffmpeg-kit.sh — Build the InfinityLoop1308/ffmpeg-kit LTS AAR for local development.
#
# Usage:
#   ./scripts/build-ffmpeg-kit.sh
#
# Requirements (macOS):
#   brew install autoconf automake libtool nasm cmake pkg-config gmp gnutls
#   Android NDK 22+ installed via Android Studio SDK Manager
#   ANDROID_SDK_ROOT set, or ~/Library/Android/sdk present

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FFMPEG_KIT_COMMIT="efd648489598efdc65e2e2f06ae69e065e257077"
FFMPEG_KIT_REPO="https://github.com/InfinityLoop1308/ffmpeg-kit.git"
BUILD_DIR="/tmp/ffmpeg-kit-build-$$"
TARGET_AAR="${REPO_ROOT}/app/libs/ffmpeg-kit.aar"

# Resolve Android SDK / NDK
if [[ -z "${ANDROID_SDK_ROOT:-}" ]]; then
  if [[ -d "${HOME}/Library/Android/sdk" ]]; then
    export ANDROID_SDK_ROOT="${HOME}/Library/Android/sdk"
  else
    echo "ERROR: ANDROID_SDK_ROOT not set and ~/Library/Android/sdk not found." >&2
    exit 1
  fi
fi

if [[ -z "${ANDROID_NDK_ROOT:-}" ]]; then
  NDK_DIR=$(ls -d "${ANDROID_SDK_ROOT}/ndk/"*/ 2>/dev/null | sort -V | tail -1)
  if [[ -z "$NDK_DIR" ]]; then
    echo "ERROR: No NDK found in ${ANDROID_SDK_ROOT}/ndk/. Install via SDK Manager." >&2
    exit 1
  fi
  export ANDROID_NDK_ROOT="${NDK_DIR%/}"
fi

echo "==> Android SDK : ${ANDROID_SDK_ROOT}"
echo "==> Android NDK : ${ANDROID_NDK_ROOT}"
echo "==> Commit      : ${FFMPEG_KIT_COMMIT}"
echo "==> Output      : ${TARGET_AAR}"
echo ""

# Clone
echo "==> Cloning InfinityLoop1308/ffmpeg-kit..."
git clone --no-checkout "${FFMPEG_KIT_REPO}" "${BUILD_DIR}"
cd "${BUILD_DIR}"
git checkout "${FFMPEG_KIT_COMMIT}"

# Build (arm64-v8a + x86_64 LTS, same flags as PipePipe CI)
echo "==> Building (this takes 30-60 minutes)..."
./android.sh \
  --lts \
  --enable-gmp \
  --enable-openssl \
  --enable-android-media-codec \
  --enable-android-zlib \
  --disable-x86 \
  --disable-arm-v7a \
  --disable-arm-v7a-neon

# Find output AAR
AAR=$(find "${BUILD_DIR}/bundle-android-aar-lts" -name "*.aar" | head -1)
if [[ -z "${AAR}" ]]; then
  echo "ERROR: AAR not found after build. Check /tmp/ffmpegkit-*.log" >&2
  exit 1
fi

mkdir -p "${REPO_ROOT}/app/libs"
cp "${AAR}" "${TARGET_AAR}"
echo ""
echo "✅ Done — ffmpeg-kit.aar installed to app/libs/"
echo "   Size: $(du -sh "${TARGET_AAR}" | cut -f1)"
echo ""
echo "Now run: ./gradlew assembleDebug"

# Cleanup
rm -rf "${BUILD_DIR}"

