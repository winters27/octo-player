#!/bin/bash
# Builds the pacman package inside a throwaway Arch Linux container (never
# on the build machine itself), then installs it there with its
# dependencies and checks every file landed.
set -euo pipefail
pacman -Syu --noconfirm --needed base-devel
useradd --uid "$BUILD_UID" --create-home builder
cd /work
runuser -u builder -- makepkg --nodeps --noconfirm
pacman -U --noconfirm ./*.pkg.tar.zst
pacman -Qkk octo-player
test -x /usr/bin/octo
test -f /usr/share/applications/octo-Octo.desktop
test -f /usr/share/icons/hicolor/512x512/apps/app.winters.octo.png
# A release must upgrade its own early versions, numbered or named.
test "$(vercmp "$EARLY_PKGVER" "$STABLE_PKGVER")" -lt 0
test "$(vercmp 1.2.0pre.beta.1 1.2.0)" -lt 0
test "$(vercmp 1.2.0pre.1 1.2.0)" -lt 0
test "$(vercmp 1.2.0pre.beta.1 1.2.0pre.beta.2)" -lt 0
