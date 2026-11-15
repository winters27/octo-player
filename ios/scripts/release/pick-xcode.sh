#!/usr/bin/env bash
# Selects the installed Xcode whose build is $1 (the build of the iOS SDK the Linux store build
# stamps), or fails naming every build this Mac has.
set -euo pipefail
want=${1:?usage: pick-xcode.sh XCODE_BUILD}
if ! [[ $want =~ ^[0-9]{2}[A-Z][0-9]+[a-z]?$ ]]; then
  echo "::error::'$want' is not an Xcode build like 27A9275" >&2
  exit 1
fi
have=()
for app in /Applications/Xcode*.app; do
  [ -f "$app/Contents/version.plist" ] || continue
  build=$(/usr/libexec/PlistBuddy -c "Print :ProductBuildVersion" "$app/Contents/version.plist")
  have+=("$(basename "$app") ($build)")
  if [ "$build" = "$want" ]; then
    sudo xcode-select -s "$app"
    xcodebuild -version
    exit 0
  fi
done
echo "::error::No Xcode with build $want on this runner. It has: ${have[*]:-none}" >&2
exit 1
