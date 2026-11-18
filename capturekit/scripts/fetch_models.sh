#!/usr/bin/env bash
# Download the Phase 9 object-detector model into the Android library assets (gitignored, never committed; ADR 0017).
# Pinned to one Hugging Face commit and one sha256: a changed file is rejected and deleted. Re-running is a no-op when
# the file is already present and matches. Without the file the app builds and the detector stays off.
set -euo pipefail

MODEL="efficientdet_lite0_detection.tflite"
HF_COMMIT="360eea05e7de8f0d0f5602b548b5bbee6f7b65c7"   # litert-community/efficientdet, license: apache-2.0
SHA256="33a3b622c7cac0762f96089353cd61495f3e993968d133af7871bfc2d5396704"
URL="https://huggingface.co/litert-community/efficientdet/resolve/${HF_COMMIT}/${MODEL}"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST_DIR="$ROOT/android/capturekit/src/main/assets"
DEST="$DEST_DIR/$MODEL"

sha_of() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi
}

if [ -f "$DEST" ] && [ "$(sha_of "$DEST")" = "$SHA256" ]; then
  echo "models: $MODEL already present and verified"
  exit 0
fi

mkdir -p "$DEST_DIR"
tmp="$(mktemp)"
trap 'rm -f "$tmp"' EXIT
echo "models: downloading $MODEL"
curl -fsSL --retry 3 -o "$tmp" "$URL"
got="$(sha_of "$tmp")"
if [ "$got" != "$SHA256" ]; then
  echo "models: sha256 mismatch for $MODEL (expected $SHA256, got $got); not installed" >&2
  exit 1
fi
mv "$tmp" "$DEST"
trap - EXIT
echo "models: ready in $DEST"
