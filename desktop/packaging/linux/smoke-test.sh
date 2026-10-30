#!/bin/bash
# Opens the actual packaged GUI and plays a silent tone through the Rust
# engine. Run on the disposable CI runner with Xvfb and PulseAudio installed.
set -euo pipefail
packages=$(realpath "${1:?package directory}")
version=${2:?version}
here=$(cd -- "$(dirname -- "$0")" && pwd)
work=$(mktemp -d)
branch=stable
[[ $version != *-* ]] || branch=beta
ref="app.winters.octo//$branch"
installed=false
pulse_pid=
cleanup() {
    if $installed; then flatpak uninstall --user --noninteractive "$ref" || true; fi
    if [[ -n $pulse_pid ]]; then kill "$pulse_pid" 2>/dev/null || true; fi
    rm -rf "$work"
}
trap cleanup EXIT
if flatpak info --user "$ref" >/dev/null 2>&1; then
    echo "Use a clean user for this test: $ref is already installed." >&2
    exit 1
fi

desktop-file-validate "$here/octo.desktop"
appstreamcli validate --no-net "$here/app.winters.octo.metainfo.xml"
export XDG_RUNTIME_DIR="$work/runtime"
mkdir -m700 "$XDG_RUNTIME_DIR"
mkdir "$XDG_RUNTIME_DIR/pulse"
pulseaudio -n --daemonize=no --exit-idle-time=-1 --use-pid-file=no \
    --load="module-native-protocol-unix socket=$XDG_RUNTIME_DIR/pulse/native" \
    --load="module-null-sink sink_name=octo_test" >"$work/pulse.log" 2>&1 &
pulse_pid=$!
export PULSE_SERVER="unix:$XDG_RUNTIME_DIR/pulse/native"
for ((i=0; i<30; i++)); do
    if pactl info >/dev/null 2>&1; then break; fi
    sleep 1
done
pactl info
export ALSA_CONFIG_PATH="$here/flatpak/asound.conf"

check() {
    local kind=$1
    shift
    local profile="$work/$kind"
    mkdir "$profile"
    export OCTO_PROFILE_DIR="$profile"
    export JAVA_TOOL_OPTIONS="-Docto.checkPlay=$profile/tone.wav -Dskiko.renderApi=SOFTWARE"
    timeout 120 xvfb-run -a dbus-run-session -- "$@" 2>&1 | tee "$work/$kind.log"
    # The self-check closes the window even on failure: its exit code alone
    # is not proof that the native audio library loaded and played.
    grep -q 'check: sound is playing, the clock moved' "$work/$kind.log"
    grep -q "check: version $version$" "$work/$kind.log"
    grep -q 'check: java 27$' "$work/$kind.log"
    grep -q 'check: https works' "$work/$kind.log"
    test -f "$profile/config/settings.json"
}

# Extract without repairing permissions: the ZIP must run as downloaded.
mkdir "$work/zip-image"
unzip -q "$packages/zip/Octo-$version-linux-x86-64.zip" -d "$work/zip-image"
test -x "$work/zip-image/Octo/bin/Octo"
test -x "$work/zip-image/Octo/lib/runtime/lib/jspawnhelper"
test ! -x "$work/zip-image/Octo/lib/runtime/lib/modules"
check zip "$work/zip-image/Octo/bin/Octo"

appimage="$packages/appimage/Octo-$version-linux-x64.AppImage"
chmod +x "$appimage"
check appimage "$appimage" --appimage-extract-and-run

mkdir "$work/arch"
tar --zstd -xf "$packages/pacman/Octo-$version-linux-x64.pkg.tar.zst" -C "$work/arch"
check pacman "$work/arch/opt/octo-player/bin/Octo"

unset JAVA_TOOL_OPTIONS OCTO_PROFILE_DIR
flatpak install --user --noninteractive "$packages/flatpak/Octo-$version-linux-x64.flatpak"
installed=true
mkdir "$work/flatpak"
# Pass only this test's files into the sandbox. All other permissions come
# from the real package, including audio and graphics.
timeout 120 xvfb-run -a dbus-run-session -- flatpak run \
    --filesystem="$work" \
    --env="OCTO_PROFILE_DIR=$work/flatpak" \
    --env="JAVA_TOOL_OPTIONS=-Docto.checkPlay=$work/flatpak/tone.wav -Dskiko.renderApi=SOFTWARE" \
    "$ref" 2>&1 | tee "$work/flatpak.log"
grep -q 'check: sound is playing, the clock moved' "$work/flatpak.log"
grep -q "check: version $version$" "$work/flatpak.log"
grep -q 'check: java 27$' "$work/flatpak.log"
grep -q 'check: https works' "$work/flatpak.log"
test -f "$work/flatpak/config/settings.json"
echo 'Portable ZIP, AppImage, pacman and Flatpak: GUI, native audio, version and settings checks passed.'
