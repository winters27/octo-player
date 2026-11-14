#!/usr/bin/env bash
# Tests for release-lib.sh, the checks testflight.sh makes before it builds. Uses short systemd
# units of its own (a few seconds), and its own lock file, never build-slot's.
#   bash ios/scripts/release/test_testflight.sh
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
lib=$here/release-lib.sh
# shellcheck source=release-lib.sh
source "$lib"
work=$(mktemp -d)
tag=octo-tf-test-$$
trap 'systemctl stop "other-$tag-hold.service" 2>/dev/null || true; rm -rf "$work"' EXIT
passed=0
ok() { echo "ok: $*"; passed=$((passed + 1)); }
fail() { echo "FAIL: $*" >&2; exit 1; }
lock=$work/lanes.lock
touch "$lock"
unit() { # NAME COMMAND...: runs COMMAND in a systemd unit of that name, returns its exit code
  local name=$1; shift
  systemd-run --wait --quiet --collect --unit="$name" "$@" > /dev/null 2>&1
}
check="source '$lib'; in_build_slot '$lock'"

# This shell is in no lane unit.
if in_build_slot "$lock"; then fail "a plain shell counted as build-slot"; fi
ok "a process outside a lane unit is refused"

# A lane unit, but nobody holds the lock.
if unit "lane-$tag-free" bash -c "$check"; then fail "a free lock counted as build-slot"; fi
ok "a lane unit without the held lock is refused"

# A lane unit that holds the lock itself: build-slot's own case.
unit "lane-$tag-own" flock "$lock" bash -c "$check" || fail "a real build-slot job was refused"
ok "a lane unit holding the lock passes"

# Another kind of unit holding the lock.
if unit "other-$tag-own" flock "$lock" bash -c "$check"; then fail "a non-lane unit counted as build-slot"; fi
ok "a unit that is not a lane is refused"

# Someone else's unit holds the lock while a lane unit asks.
systemd-run --quiet --collect --unit="other-$tag-hold" flock "$lock" sleep 5 > /dev/null 2>&1
for _ in 1 2 3 4 5 6 7 8 9 10; do grep -q FLOCK /proc/locks && [ -n "$(awk -v i="$(stat -c %i "$lock")" '$2=="FLOCK"{n=split($6,f,":"); if (f[n]==i) print $5}' /proc/locks)" ] && break; sleep 0.2; done
if unit "lane-$tag-asks" bash -c "$check"; then fail "another unit's lock counted for this lane"; fi
systemctl stop "other-$tag-hold.service" 2>/dev/null || true
ok "a lock held by another unit is not this job's"

# The reservation and the kept build are read back.
mkdir -p "$work/home" "$work/w"
echo '{"commit": "abc", "build": 3, "state": "uploading", "nonce": "n1"}' > "$work/home/reserved.json"
[ "$(reservation_field "$work/home" commit)" = abc ] || fail "commit"
[ "$(reservation_field "$work/home" state)" = uploading ] || fail "state"
[ "$(reservation_field "$work/home" nonce)" = n1 ] || fail "nonce"
[ -z "$(reservation_field "$work/nowhere" commit)" ] || fail "no reservation"
echo "def 1234" > "$work/w/unsigned.from"
[ "$(unsigned_commit "$work/w")" = def ] || fail "unsigned_commit"
ok "the reservation and the kept build are read back"

# Carriage returns are found in text files, never in the store assets.
mkdir -p "$work/t/ios/Sources" "$work/t/ios/Resources/StoreAssets/Intents"
printf 'let a = 1\r\n' > "$work/t/ios/Sources/A.swift"
printf 'let b = 2\n' > "$work/t/ios/Sources/B.swift"
printf '{}\r\n' > "$work/t/ios/Resources/StoreAssets/Intents/inputs.json"
found=$(cr_files "$work/t")
[ "$found" = "$work/t/ios/Sources/A.swift" ] || fail "cr_files found: $found"
ok "carriage returns are found, outside the store assets only"

# The export takes ios and the workflow file, with Linux line endings.
git init -q "$work/repo"
mkdir -p "$work/repo/ios" "$work/repo/.github/workflows" "$work/repo/desktop"
printf 'line\n' > "$work/repo/ios/a.swift"
printf 'name: iOS store assets\n' > "$work/repo/.github/workflows/ios-store-assets.yml"
printf 'other\n' > "$work/repo/desktop/b.kt"
git -C "$work/repo" add -A
git -C "$work/repo" -c user.name=t -c user.email=t@t commit -q -m t
sha=$(git -C "$work/repo" rev-parse HEAD)
export_tree "$work/repo" "$sha" "$work/out"
[ -f "$work/out/ios/a.swift" ] && [ -f "$work/out/.github/workflows/ios-store-assets.yml" ] || fail "export missed a file"
[ ! -e "$work/out/desktop" ] || fail "export took more than ios and the workflow"
[ -z "$(cr_files "$work/out")" ] || fail "export wrote carriage returns"
ok "the export takes ios and the workflow file, with Linux line endings"

[ "$passed" = 8 ] || fail "only $passed of 8 checks passed"
echo "release-lib self-test passed (8 checks)"
