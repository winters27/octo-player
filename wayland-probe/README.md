# Java 27 / native Wayland GPU probe

A standalone Linux x86_64 diagnostic using Kotlin Desktop Toolkit 0.0.90,
Compose Multiplatform 1.12.2 and its matched Skiko. It compiles the **actual**
`desktop/.../wash/WashRenderer.kt` and uses the shared shaders/motion, with
Compose text and pointer targets above it. The small second window exercises
the mechanics needed by a mini-player, not the production mini-player UI.

The full player is implemented in [`desktop-wayland`](../desktop-wayland/README.md)
and has a published prerelease. This module remains useful for isolated renderer
and protocol checks. See [historical measurements and current scope](RESULTS.md).

## Build and run

Install JDK 17 and 27, and the normal Linux desktop build dependencies. Run in
a Wayland session with working EGL/OpenGL drivers. KDT's native library also
needs the usual Wayland, xkbcommon and D-Bus libraries.

```sh
./gradlew :wayland-probe:installDist
JAVA_HOME=/path/to/jdk-27 bash wayland-probe/check-gpu.sh /path/to/results

# Interactive protocol inspector:
env -u DISPLAY ./gradlew :wayland-probe:run
```

The acceptance script requires `strace`, `timeout` and Python 3. It runs for
22 seconds, switches fullscreen on/off, opens/closes/reopens the second window,
reads back a rendered frame and checks actual Wayland/DRM syscalls and the
absence of X11 connections. Run it with the window visible. `GL_RENDERER` values
identifying llvmpipe, softpipe, swrast or SwiftShader fail; there is no raster
window fallback. Creating the small synthetic cover on the CPU is texture input,
not window rendering. `dependency-checks.yml` only **compiles** this probe;
the full player's CI and hardware checks are documented in its guide.

The standalone launcher accepts `--seconds=N`, `--exercise` (at least 20 seconds),
`--fullscreen`, and `--snapshot=/absolute/file.png`. Set
`-Dkdt.library.folder.path=<installDist>/lib/kdt` and `-Dkdt.native.log.path=...`
in `JAVA_OPTS` when running it directly. Unset `DISPLAY`.

## Interactive checks

- Resize, minimize/restore and close the decorated window. F11 toggles fullscreen.
- Ctrl+N or the Compose link toggles the mini window. The click counter checks
  native pointer coordinates through Compose, including fractional scaling.
- Type, backspace and switch keyboard layouts. The append-only text inspector
  handles native key text and Wayland preedit/commit/delete-surrounding events.
  Its fixed cursor rectangle and whole-text clipboard are diagnostic, not a
  complete `BasicTextField`/Compose IME bridge.
- Ctrl+C copies the inspector's text; Ctrl+V pastes. Try both directions with
  another native client. Drop text or files on the window; MIME type and receipt
  are logged. The probe does not open or modify dropped files.
- Move between monitors with different scales. Check `WindowScreenChange`,
  `WindowScaleChanged`, physical framebuffer size and pointer alignment.

The log records callback intervals (p50/p95, after 60 warm-up frames) and CPU
time spent drawing/submitting. These are **not** GPU timer-query measurements
or presentation timestamps. KDT paces drawing through Wayland frame callbacks.

## Relationship to the native player

The native player reuses this module's EGL/Skia bridge and adds the real Octo
UI/audio engine, Compose keyboard/focus/IME/clipboard adapters, incoming file
drops, portal dialogs, decorations and main/mini-window lifecycle. Its
[verification and preview boundaries](../desktop-wayland/README.md) describe the
full-player checks. The ordinary desktop installers continue to use Compose/AWT.

The probe's append-only inspector and synthetic mini window intentionally remain
small diagnostic surfaces. Tray, screen-reader integration, broader GPU/compositor
coverage, physical hotplug and suspend/resume remain open for the native preview;
mini-player placement and keep-above are compositor-managed.

Both executables retain one KDT `Application` until process exit to avoid KDT
0.0.90's Wayland-before-EGL teardown crash on Mesa 25. Windows and rendering
resources are closed before exiting. Revisit this workaround when updating KDT.
