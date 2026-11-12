#!/usr/bin/env bash
# Download public evaluation datasets into reconstruction/data/ (gitignored).
# No real user scans are ever stored here.
#   ETH3D "office"  (indoor room, DSLR undistorted images + COLMAP GT poses + GT laser scan)
#                   License: CC BY-NC-SA 4.0 — research/evaluation use only. https://www.eth3d.net
#   TUM RGB-D fr1/xyz (RGB + depth + GT trajectory + accelerometer)
#                   License: CC BY 4.0. https://cvg.cit.tum.de/data/datasets/rgbd-dataset
# Usage: scripts/fetch_test_data.sh [eth3d_office] [tum_fr1_xyz]   (default: all)
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DATA="$ROOT/data"; DL="$DATA/_downloads"; mkdir -p "$DL"

extract_7z() {  # needs 7z (p7zip) or bsdtar (libarchive)
  if command -v 7z >/dev/null; then 7z x -y -o"$2" "$1" >/dev/null
  elif command -v bsdtar >/dev/null; then bsdtar -xf "$1" -C "$2"
  else echo "need 7z or bsdtar to extract $1" >&2; exit 1; fi
}
fetch() {  # url file
  [ -s "$DL/$2" ] && return 0
  curl -fL --retry 3 -o "$DL/$2.part" "$1"
  mv "$DL/$2.part" "$DL/$2"
}

TARGETS=("$@")

if [ ${#TARGETS[@]} -eq 0 ] || [[ " ${TARGETS[*]} " == *" eth3d_office "* ]]; then
  if [ ! -d "$DATA/eth3d/office/images" ]; then
    mkdir -p "$DATA/eth3d"
    fetch https://www.eth3d.net/data/office_dslr_undistorted.7z office_dslr_undistorted.7z
    fetch https://www.eth3d.net/data/office_dslr_scan_eval.7z   office_dslr_scan_eval.7z
    extract_7z "$DL/office_dslr_undistorted.7z" "$DATA/eth3d"
    extract_7z "$DL/office_dslr_scan_eval.7z"   "$DATA/eth3d"
  fi
  echo "eth3d office: $DATA/eth3d/office"
fi

if [ ${#TARGETS[@]} -eq 0 ] || [[ " ${TARGETS[*]} " == *" tum_fr1_xyz "* ]]; then
  if [ ! -d "$DATA/tum/rgbd_dataset_freiburg1_xyz" ]; then
    mkdir -p "$DATA/tum"
    fetch https://cvg.cit.tum.de/rgbd/dataset/freiburg1/rgbd_dataset_freiburg1_xyz.tgz fr1_xyz.tgz
    tar -xzf "$DL/fr1_xyz.tgz" -C "$DATA/tum"
  fi
  echo "tum fr1_xyz: $DATA/tum/rgbd_dataset_freiburg1_xyz"
fi

# Record exactly what was fetched.
( cd "$DL" && sha256sum ./*.7z ./*.tgz 2>/dev/null ) > "$DATA/SHA256SUMS.txt" || true
[ "${KEEP_ARCHIVES:-0}" = 1 ] || rm -rf "$DL"
