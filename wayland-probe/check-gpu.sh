#!/bin/bash
# Acceptance test for a real Linux x86_64 GPU and a running Wayland compositor.
# Build first: ./gradlew :wayland-probe:installDist
set -euo pipefail
here=$(cd -- "$(dirname -- "$0")" && pwd)
out=$(realpath -m "${1:?usage: bash wayland-probe/check-gpu.sh OUTPUT_DIRECTORY}")
mkdir -p "$out"
app="$here/build/install/wayland-probe"
unset DISPLAY JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS _JAVA_OPTIONS
export JAVA_OPTS="-Dkdt.library.folder.path=$app/lib/kdt -Dkdt.native.log.path=$out/kdt.log"
timeout 45s strace -f -e trace=connect,openat -o "$out/syscalls.log" \
    "$app/bin/wayland-probe" --exercise --seconds=22 "--snapshot=$out/frame.png" > "$out/probe.log" 2>&1
python3 - "$out" <<'PY'
from pathlib import Path
import re
import sys

out = Path(sys.argv[1])
log = (out / "probe.log").read_text()
trace = (out / "syscalls.log").read_text()
assert "probe: PASS java=27 native-wayland hardware-gpu" in log, log
assert re.search(r'connect\(.*(?:wayland|WAYLAND)', trace), "No Wayland socket connection in trace"
assert re.search(r'openat\(.*"/dev/dri/renderD\d+".*= \d+', trace), "No successful DRM render-node open"
assert not re.search(r'connect\(.*(?:\.X11-unix|sin_port=htons\(60\d\d\))', trace), "X11 connection attempted"
assert re.search(r'configure .*fullscreen=true', log), "No acknowledged fullscreen configure"
windows = re.findall(r'probe: window=(\d+) frames=(\d+)', log)
assert len(windows) == 3 and all(int(n) >= 60 for _, n in windows), windows
assert len(re.findall(r'probe: mini-open', log)) == 2, "Mini reopen missing"
assert (out / "frame.png").is_file(), "No GPU readback"
print("PASS: Java 27, native Wayland socket, DRM hardware GPU, no X11 connections,")
print("fullscreen/restore, three rendered windows including mini close/reopen, GPU readback.")
print("Input/IME/clipboard/DnD and mixed-monitor movement require separate checks.")
for line in log.splitlines():
    if line.startswith(("probe: gpu=", "probe: timing", "probe: scale")):
        print(line)
PY
