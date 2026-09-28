#!/usr/bin/env bash
# Builds the debug app and publishes it as a release on the private test
# repo, where Obtainium on the phone picks it up. Each build is numbered by
# the commit count, so every one installs as an update over the last, keeping
# the app's data (all are signed with this machine's debug key).
#
# Usage: scripts/publish-test-build.sh "What changed, in a line or two"
set -euo pipefail

REPO="winters27/octo-android-builds"
cd "$(dirname "$0")/.."

if [ -n "$(git status --porcelain --untracked-files=no)" ]; then
  echo "Commit your changes first, so the build matches a commit." >&2
  exit 1
fi

build=$(git rev-list --count HEAD)
version="0.2.0.${build}-debug"
commit=$(git rev-parse --short HEAD)
notes="${1:-Test build from ${commit}.}"

./gradlew :app:assembleDebug -PoctoBuild="$build" -q

apk="app/build/outputs/apk/debug/app-debug.apk"
named="app/build/outputs/apk/debug/octo-${version}.apk"
cp "$apk" "$named"

gh release create "$version" "$named" \
  --repo "$REPO" \
  --title "Octo ${version}" \
  --notes "${notes}

Built from ${commit}."

echo "Published ${version} to ${REPO}"
