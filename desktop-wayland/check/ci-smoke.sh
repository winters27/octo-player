#!/bin/bash
# CI lacks a physical GPU. Allow Mesa software EGL explicitly for this test;
# release launches still reject it. Hardware acceptance is run on the CI bundle.
set -euo pipefail
root=$(pwd)
out="$root/desktop-wayland/build/ci-check"
mkdir -p "$out/runtime" "$out/unpacked"
chmod 700 "$out/runtime"
tar -xzf "desktop-wayland/build/distributions/Octo-Wayland-$VERSION-linux-x64.tar.gz" -C "$out/unpacked"
launcher="$out/unpacked/Octo-Wayland/octo-wayland"
test -x "$launcher"
test -x "$out/unpacked/Octo-Wayland/runtime/bin/java"
test -x "$out/unpacked/Octo-Wayland/runtime/lib/jspawnhelper"
export XDG_RUNTIME_DIR="$out/runtime"
export WAYLAND_DISPLAY=wayland-octo-ci
export OCTO_PROFILE_DIR="$out/profile"
unset DISPLAY JAVA_HOME
export LIBGL_ALWAYS_SOFTWARE=1
weston --backend=headless-backend.so --renderer=gl --width=1280 --height=900 \
    --socket="$WAYLAND_DISPLAY" --idle-time=0 --log="$out/weston.log" &
weston_pid=$!
pulseaudio -n --daemonize=no --exit-idle-time=-1 --use-pid-file=no \
    --load="module-native-protocol-unix socket=$out/runtime/pulse-native" \
    --load="module-null-sink sink_name=octo_test" >"$out/pulse.log" 2>&1 &
pulse_pid=$!
trap 'kill "$weston_pid" "$pulse_pid" 2>/dev/null || true' EXIT
export PULSE_SERVER="unix:$out/runtime/pulse-native"
export ALSA_CONFIG_PATH="$root/desktop/packaging/linux/flatpak/asound.conf"
for ((i=0; i<30; i++)); do
    if [[ -S "$out/runtime/$WAYLAND_DISPLAY" ]] && pactl info >/dev/null 2>&1; then break; fi
    sleep 1
done
test -S "$out/runtime/$WAYLAND_DISPLAY"
pactl info
export JAVA_TOOL_OPTIONS="-Docto.wayland.testSoftware=true -Docto.checkPlay=$out/tone.wav -Docto.wayland.capture=$out/frame.png"
if ! timeout 90 dbus-run-session -- strace -f -e trace=connect,openat -o "$out/trace.log" "$launcher" >"$out/player.log" 2>&1; then
    cat "$out/player.log"
    exit 1
fi
python3 - "$out" "$VERSION" <<'PY'
import pathlib, sys
out = pathlib.Path(sys.argv[1])
log = (out / 'player.log').read_text()
print(log)
for expected in ('wayland: clean-exit', 'check: java 27', 'check: https works',
                 'check: sound is playing, the clock moved', f'check: version {sys.argv[2]}'):
    assert expected in log, expected
assert (out / 'frame.png').stat().st_size > 1000
trace = (out / 'trace.log').read_text()
assert 'wayland-octo-ci' in trace
assert not any('connect(' in line and ('X11-unix' in line or 'htons(6000)' in line) for line in trace.splitlines())
assert (out / 'profile/config/settings.json').is_file()
print('PASS packaged native Wayland, bundled Java 27, real audio clock, HTTPS and no X11 connection')
PY
# The production default must fail rather than silently fall back to software.
export JAVA_TOOL_OPTIONS='-Docto.wayland.seconds=10'
if timeout 25 dbus-run-session -- "$launcher" >"$out/software-rejection.log" 2>&1; then
    echo 'Production launcher unexpectedly accepted software rendering' >&2
    exit 1
fi
python3 - "$out/software-rejection.log" <<'PY'
import pathlib, sys
log = pathlib.Path(sys.argv[1]).read_text()
assert 'Hardware GPU required; got' in log, log
PY
