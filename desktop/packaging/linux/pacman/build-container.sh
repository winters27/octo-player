#!/bin/bash
# Run inside the disposable Arch container, never on the build host.
set -euo pipefail
pacman -Syu --noconfirm --needed base-devel
useradd --uid "$BUILD_UID" --create-home builder
cd /work
runuser -u builder -- makepkg --nodeps --noconfirm
# Install the result with its declared dependencies in this clean system.
pacman -U --noconfirm ./*.pkg.tar.zst
pacman -Qkk octo-player
test -x /usr/bin/octo
test -f /usr/share/applications/octo-Octo.desktop
# Stable must upgrade a prerelease, including a numeric prerelease label.
test "$(vercmp 1.2.0pre.beta.1 1.2.0)" -lt 0
test "$(vercmp 1.2.0pre.1 1.2.0)" -lt 0
