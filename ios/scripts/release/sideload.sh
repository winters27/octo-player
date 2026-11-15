#!/usr/bin/env bash
# Builds the Octo iPhone IPA for sideloading from one commit, on Linux, with no TestFlight: a
# release build with xtool, then the sideload IPA staged, signed and validated as testflight.sh
# does. Its build number is the commit's count, so every commit's number is its own and only grows.
# It never uploads or publishes: the carrier draft and the ios-v tag do that, at their own gate.
#
#   build-slot -Kind wsl-xtool ... -Command 'bash <this script> --ref <full sha>'
#
# Output: /root/octo-sideload/dist/Octo-<version>-iPhone.ipa, and its SHA-256 and commit as the
# ipa-sha256: and ipa-commit: lines the ios-v tag carries.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
# shellcheck source=release-lib.sh
source "$here/release-lib.sh"
repo=/mnt/c/OctoPlayer
work=/root/octo-testflight
dist=/root/octo-sideload/dist
die() { echo "sideload: $*" >&2; exit 1; }

ref=""
while [ $# -gt 0 ]; do
  case "$1" in
    --ref) ref=${2:-}; shift 2 ;;
    *) die "unknown option $1" ;;
  esac
done
[[ $ref =~ ^[0-9a-f]{40}$ ]] || die "--ref takes a full 40-character commit id"
in_build_slot /root/.lanes-wsl.lock || die "run me through build-slot (kind wsl-xtool)"
[ -f /root/.octo-asc/reserved.json ] && die "a TestFlight reservation is open; build it with testflight.sh instead"

src=$work/src/$ref
mkdir -p "$work/logs" "$work/src" "$dist"
find "$work/src" -mindepth 1 -maxdepth 1 -exec rm -rf {} +
export_tree "$repo" "$ref" "$src"
bad=$(cr_files "$src")
[ -z "$bad" ] || die "carriage returns in: $bad"
[ -f "$src/ios/Resources/StoreAssets/Icon/Assets.car" ] || die "$ref has no store assets; commit them from the iOS store assets workflow first"
tree=$(git -c safe.directory='*' -C "$repo" rev-parse "$ref:ios")
log=$work/logs/xtool-${ref:0:8}.log
echo "building $ref (log $log)"
rm -f "$src/ios/xtool/Octo.ipa"
(cd "$src/ios" && flock -w "${OCTO_SWIFT_LOCK_SECONDS:-900}" /root/sn-ios-core/build.lock xtool dev build -c release --ipa < /dev/null > "$log" 2>&1) \
  || { tail -40 "$log"; die "the release build failed (or StreamNook's build lock stayed busy)"; }
[ -f "$src/ios/xtool/Octo.ipa" ] || die "xtool wrote no Octo.ipa"
cp "$src/ios/xtool/Octo.ipa" "$work/unsigned.ipa"
echo "$ref $tree" > "$work/unsigned.from"

asc="python3 -I $src/ios/scripts/release/octo_asc.py"
build=$(git -c safe.directory='*' -C "$repo" rev-list --count "$ref")
version=$(python3 -I -c 'import plistlib, sys; print(plistlib.load(open(sys.argv[1], "rb"))["CFBundleShortVersionString"])' \
  "$src/ios/Info.plist")
side=$dist/Octo-$version-iPhone.ipa
find "$dist" -mindepth 1 -maxdepth 1 -exec rm -rf {} +
$asc sideload --tree "$src" --unsigned "$work/unsigned.ipa" --out "$side" --build "$build"
$asc validate --tree "$src" --ipa "$side" --sideload
echo "ready: $version build $build of $ref"
echo "  $side"
echo "ipa-sha256: $(sha256sum "$side" | cut -d' ' -f1)"
echo "ipa-commit: $ref"
