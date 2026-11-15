#!/usr/bin/env bash
# Builds the Octo iPhone app for TestFlight and for sideloading from one commit, on Linux: a
# release build with xtool, then the signed App Store IPA and the sideload IPA, both validated.
# It never uploads: `octo_asc.py upload` does that, at its own gate.
#
#   python3 -I ios/scripts/release/octo_asc.py reserve --ref <full sha>      (outside the slot)
#   build-slot -Kind wsl-xtool ... -Command 'bash <this script> --ref <full sha> [--no-build]'
#
# The tools it runs after the export are the commit's own copies, so they always match the code.
# Output: /root/octo-testflight/dist/Octo-<version>-<build>-<sha8>.ipa and ...-iPhone.ipa.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
# shellcheck source=release-lib.sh
source "$here/release-lib.sh"
repo=/mnt/c/OctoPlayer
work=/root/octo-testflight
home=/root/.octo-asc
die() { echo "testflight: $*" >&2; exit 1; }

ref="" nobuild=0
while [ $# -gt 0 ]; do
  case "$1" in
    --ref) ref=${2:-}; shift 2 ;;
    --no-build) nobuild=1; shift ;;
    *) die "unknown option $1" ;;
  esac
done
[[ $ref =~ ^[0-9a-f]{40}$ ]] || die "--ref takes a full 40-character commit id"
in_build_slot /root/.lanes-wsl.lock || die "run me through build-slot (kind wsl-xtool)"
[ "$(reservation_field "$home" commit)" = "$ref" ] || die "no open reservation for $ref; run octo_asc.py reserve --ref $ref first"
[ "$(reservation_field "$home" state)" = open ] || die "build $(reservation_field "$home" build) is being uploaded; wait, then reserve again"
# The build is tied to this reservation: if another reserve replaces it meanwhile, record-built refuses.
nonce=$(reservation_field "$home" nonce)

src=$work/src/$ref
mkdir -p "$work/dist" "$work/logs" "$work/src"
if [ "$nobuild" = 0 ]; then
  find "$work/src" -mindepth 1 -maxdepth 1 -exec rm -rf {} +
  export_tree "$repo" "$ref" "$src"
  bad=$(cr_files "$src")
  [ -z "$bad" ] || die "carriage returns in: $bad"
  tree=$(git -c safe.directory='*' -C "$repo" rev-parse "$ref:ios")
  log=$work/logs/xtool-${ref:0:8}.log
  echo "building $ref (log $log)"
  rm -f "$src/ios/xtool/Octo.ipa"
  (cd "$src/ios" && flock -w "${OCTO_SWIFT_LOCK_SECONDS:-900}" /root/sn-ios-core/build.lock xtool dev build -c release --ipa < /dev/null > "$log" 2>&1) \
    || { tail -40 "$log"; die "the release build failed (or StreamNook's build lock stayed busy)"; }
  [ -f "$src/ios/xtool/Octo.ipa" ] || die "xtool wrote no Octo.ipa"
  cp "$src/ios/xtool/Octo.ipa" "$work/unsigned.ipa"
  echo "$ref $tree" > "$work/unsigned.from"
else
  [ "$(unsigned_commit "$work")" = "$ref" ] || die "--no-build re-signs only the kept build of $(unsigned_commit "$work")"
  [ -d "$src/ios" ] || die "the tree of $ref is gone; build again without --no-build"
fi

asc="python3 -I $src/ios/scripts/release/octo_asc.py"
build=$(reservation_field "$home" build)
version=$(python3 -I -c 'import plistlib, sys; print(plistlib.load(open(sys.argv[1], "rb"))["CFBundleShortVersionString"])' \
  "$src/ios/Info.plist")
signed=$work/dist/Octo-$version-$build-${ref:0:8}.ipa
side=$work/dist/Octo-$version-$build-${ref:0:8}-iPhone.ipa
# dist holds only the open reservation's build.
find "$work/dist" -mindepth 1 -maxdepth 1 -exec rm -rf {} +
symbols=$work/dist/symbols-$build
mkdir -p "$symbols"
unzip -q -o -j "$work/unsigned.ipa" 'Payload/Octo.app/Octo' 'Payload/Octo.app/PlugIns/*.appex/Octo*' -x '*.plist' -d "$symbols"

$asc prepare --tree "$src" --unsigned "$work/unsigned.ipa" --out "$signed"
$asc sideload --tree "$src" --unsigned "$work/unsigned.ipa" --out "$side"
$asc validate --tree "$src" --ipa "$signed"
$asc validate --tree "$src" --ipa "$side" --sideload
$asc record-built --signed "$signed" --sideload "$side" --nonce "$nonce"
echo "ready: build $build of $ref"
echo "  $signed"
echo "  $side"
echo "upload (its own gate): python3 -I $src/ios/scripts/release/octo_asc.py upload $signed --wait"
