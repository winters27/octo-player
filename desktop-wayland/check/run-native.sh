#!/bin/bash
# Run from the repository root. Requires KWin 6, kscreen, qdbus6, wl-clipboard,
# GTK4/PyGObject, wayland-scanner, a C compiler and the protocol XML packages
# (including ext-data-control for the deliberately slow clipboard owner).
set -euo pipefail
root=$(cd -- "$(dirname -- "$0")/../.." && pwd)
out=${OCTO_CHECK_OUT:-/tmp/opencode/octo-native-player-check}
mkdir -p "$out/tools" "$out/compositor-config"
fake=${FAKE_INPUT_XML:-/usr/share/plasma-wayland-protocols/fake-input.xml}
im=${INPUT_METHOD_XML:-/usr/share/wayland-protocols/unstable/input-method/input-method-unstable-v1.xml}
dc=${DATA_CONTROL_XML:-/usr/share/wayland-protocols/staging/ext-data-control/ext-data-control-v1.xml}
test -f "$fake" || { echo 'Set FAKE_INPUT_XML to plasma-wayland-protocols/fake-input.xml' >&2; exit 1; }
test -f "$im" || { echo 'Set INPUT_METHOD_XML to input-method-unstable-v1.xml' >&2; exit 1; }
test -f "$dc" || { echo 'Set DATA_CONTROL_XML to ext-data-control-v1.xml' >&2; exit 1; }
wayland-scanner client-header "$fake" "$out/tools/octo-fake-input.h"
wayland-scanner private-code "$fake" "$out/tools/octo-fake-input.c"
cc -O2 -I"$out/tools" "$root/desktop-wayland/check/native-input.c" "$out/tools/octo-fake-input.c" -lwayland-client -o "$out/tools/input"
wayland-scanner client-header "$im" "$out/tools/octo-input-method.h"
wayland-scanner private-code "$im" "$out/tools/octo-input-method.c"
cc -O2 -I"$out/tools" "$root/desktop-wayland/check/native-ime.c" "$out/tools/octo-input-method.c" -lwayland-client -o "$out/tools/ime"
wayland-scanner client-header "$dc" "$out/tools/octo-data-control.h"
wayland-scanner private-code "$dc" "$out/tools/octo-data-control.c"
cc -O2 -I"$out/tools" "$root/desktop-wayland/check/delayed-clipboard.c" "$out/tools/octo-data-control.c" -lwayland-client -o "$out/tools/delayed-clipboard"
export OCTO_DELAYED_CLIPBOARD="$out/tools/delayed-clipboard"
export OCTO_CHECK_OUT="$out" OCTO_CHECK_INPUT="$out/tools/input" OCTO_IME_CONTROL="$out/ime-control" OCTO_IME_LOG="$out/ime.log"
export OCTO_CHECK_PORTAL=1
export OCTO_WAYLAND_LAUNCHER=${OCTO_WAYLAND_LAUNCHER:-$root/desktop-wayland/build/portable/Octo-Wayland/octo-wayland}
status=0
# D-Bus activation must inherit the isolated display too, otherwise portal
# backends can open a dialog on the real desktop even though Octo is nested.
timeout 420s env -u DISPLAY WAYLAND_DISPLAY=wayland-octo-player-test XDG_CONFIG_HOME="$out/compositor-config" \
    QT_QPA_PLATFORM=wayland QT_FORCE_STDERR_LOGGING=1 GTK_A11Y=none NO_AT_BRIDGE=1 dbus-run-session -- \
    kwin_wayland --virtual --width 1280 --height 900 --output-count 2 --socket wayland-octo-player-test \
    --inputmethod "$out/tools/ime" --no-lockscreen --no-global-shortcuts --no-kactivities \
    --exit-with-session "$root/desktop-wayland/check/native-session.py" >"$out/compositor.log" 2>&1 || status=$?
cat "$out/compositor.log"
if ! rg -q '^PASS native acceptance complete$' "$out/compositor.log"; then status=1; fi
exit "$status"
