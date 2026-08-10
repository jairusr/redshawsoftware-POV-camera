#!/usr/bin/env bash
set -euo pipefail

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
repo_dir=$(CDPATH= cd -- "$script_dir/.." && pwd)
default_archive="$repo_dir/../VITURE_XR_Glasses_SDK_for_Android.zip"
sdk_archive=${1:-$default_archive}
destination="$repo_dir/app/src/main/jniLibs/arm64-v8a"

if [[ ! -f "$sdk_archive" ]]; then
    echo "VITURE Android SDK archive not found: $sdk_archive" >&2
    echo "Apply for the Android Glasses SDK from VITURE, then run:" >&2
    echo "  $0 /path/to/VITURE_XR_Glasses_SDK_for_Android.zip" >&2
    exit 1
fi

matching_library=$(unzip -Z1 "$sdk_archive" | grep -Fxc 'android/arm64-v8a/libglasses.so' || true)
if [[ "$matching_library" -ne 1 ]]; then
    echo "The archive does not contain android/arm64-v8a/libglasses.so" >&2
    exit 1
fi

mkdir -p "$destination"
temporary_dir=$(mktemp -d "${TMPDIR:-/tmp}/redshawsoftware-pov-camera.XXXXXX")
trap 'rm -rf "$temporary_dir"' EXIT
unzip -q "$sdk_archive" 'android/arm64-v8a/libglasses.so' -d "$temporary_dir"
install -m 0644 "$temporary_dir/android/arm64-v8a/libglasses.so" "$destination/libglasses.so"

echo "Installed the local VITURE SDK binary at:"
echo "  $destination/libglasses.so"
echo "This proprietary file is ignored by Git and must not be committed."
