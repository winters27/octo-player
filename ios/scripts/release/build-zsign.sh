#!/usr/bin/env bash
# Builds zsign-octo: the zsign the team already pins (614caa8d, kept in /root/sn-tools-src/zsign)
# with zsign-entitlements.patch, so every bundle is signed with its own entitlements and every
# load failure stops the signing. Nothing is fetched from the network.
#   build-slot -Kind wsl-xtool ... -Command 'bash ios/scripts/release/build-zsign.sh'
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
src=/root/sn-tools-src/zsign
pin=614caa8d1ca949e260e5746144aa52d27a4b08d6
out=/root/octo-tools/bin/zsign-octo
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
git -c safe.directory='*' -C "$src" cat-file -e "$pin^{commit}"
git -c safe.directory='*' -C "$src" archive "$pin" | tar -x -C "$work"
(cd "$work" && git apply --check "$here/zsign-entitlements.patch" && git apply "$here/zsign-entitlements.patch")
make -C "$work/build/linux" -j2 VERSION=1.1.2-octo > "$work/make.log" 2>&1 || { tail -30 "$work/make.log"; exit 1; }
mkdir -p "$(dirname "$out")"
install -m 755 "$work/bin/zsign" "$out"
"$out" -v
