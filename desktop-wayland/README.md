# Octo — native Wayland preview

The full Octo desktop player, hosted directly on Wayland with Kotlin Desktop
Toolkit 0.0.90, EGL/OpenGL ES and Skia. It reuses the real library, search,
Subsonic/Navidrome connection, Rust audio engine, full player and mini player.
No XWayland server is needed. Software renderers are rejected by default.

## Run

Download `Octo-Wayland-1.0.1677-wayland.1-linux-x64.tar.gz` from the
[verified prerelease](https://github.com/VibeProgramm/octo-player/releases/tag/preview-wayland-1.0.1677-wayland.1-run-41057075594), then:

```sh
tar -xzf Octo-Wayland-1.0.1677-wayland.1-linux-x64.tar.gz
./Octo-Wayland/octo-wayland
```

The release includes `SHA256SUMS` and `build.json`. After downloading the
checksum file beside the archive, verify it with
`sha256sum --check --ignore-missing SHA256SUMS`.

Java 27 is included. Linux x86_64, glibc 2.39+ (Ubuntu 24.04+, current Arch/CachyOS),
a Wayland session, hardware EGL/OpenGL ES, ALSA/PulseAudio/PipeWire, libxkbcommon,
libwayland-client/egl and libsecret are required. File dialogs use the desktop's
`xdg-desktop-portal` backend. `DISPLAY` is unset by the launcher.

Use **F11** for fullscreen and **Ctrl+M** for the mini player. The same server
account and audio controls as the ordinary desktop app are available. To try it
with a separate profile:

```sh
OCTO_PROFILE_DIR="$HOME/.local/share/octo-wayland-preview" ./Octo-Wayland/octo-wayland
```

Otherwise it uses the usual Octo profile and single-instance lock. Close another
Octo instance first. Updates are installed by replacing the extracted bundle;
this preview cannot auto-install upstream's AWT build.

## Preview boundaries

- Native clipboard, keyboard/Unicode input, text-input-v3 IME, portal file
  chooser and incoming audio/list-file drops are implemented.
- Mini windows are recreated when changing between the main/mini player or
  selecting a preset size; the audio engine and playback queue stay alive.
  Placement and keep-above behavior belong to the compositor. Use its window
  menu/rules. Native tray and screen-reader integration are not implemented.
- KWin/AMD Mesa is the hardware validation target. GNOME, Intel/NVIDIA,
  physical monitor hotplug and suspend/resume are not yet validated. In
  particular KDT's NVIDIA EGL compatibility is still an upstream concern.
- Compositor decorations are preferred; a client header is used where those
  are unavailable. Import-page drops work without the AWT hover overlay.

This is an experimental prerelease, not a claim of desktop-platform parity.
Native diagnostics are written to `$XDG_CACHE_HOME/octo/wayland/native.log`
(normally `~/.cache/octo/wayland/native.log`).

## Build and verification

```sh
JAVA_HOME=/path/to/jdk-27 ./gradlew :desktop-wayland:test :desktop-wayland:packagePortable
```

JDK 17 is also used by the shared desktop modules. Both FFM-based modules
(`desktop-wayland` and `wayland-probe`) compile with JDK 27 and target Java 25
bytecode. Release packages are built and tested by
`.github/workflows/wayland-preview.yml`, then published from that run's artifacts
by `.github/workflows/publish-desktop-preview.yml`. CI uses explicit test-only
software EGL (`-Docto.wayland.testSoftware=true`); this is not hardware validation.
The bundled runtime is checked for real native audio, an advancing playback
clock, HTTPS, no X11 connections, and rejection of software rendering by default.

`check/native-session.py` runs against an isolated two-output KWin compositor.
It uses a local Subsonic fixture and independent Wayland keyboard/clipboard,
input-method and GTK drag-source clients, plus MPRIS to check the audio clock.
It never stores the fixture password in the keyring. See `check/run-native.sh`.
The harness also requires ext-data-control support and its protocol XML for the delayed
clipboard owner. Set `FAKE_INPUT_XML`, `INPUT_METHOD_XML` and `DATA_CONTROL_XML`
if the distribution stores those protocol files outside the script's defaults.
Field readiness comes from the fake IME's surrounding-text feedback; clipboard
copies include a keyboard-entered marker distinct from the external seed.
The slow owner holds its response until the focus change is observed. Input uses
one persistent virtual keyboard/pointer, with commands recorded in the output
directory. Set `OCTO_CHECK_WIRE=1` to include the player's Wayland protocol trace.

To exercise an extracted CI/release bundle from the repository root:

```sh
OCTO_WAYLAND_LAUNCHER=/absolute/path/Octo-Wayland/octo-wayland \
OCTO_CHECK_OUT="$HOME/.cache/octo-player-ci/native-acceptance" \
OCTO_CHECK_WIRE=1 bash desktop-wayland/check/run-native.sh
```

### Verified prerelease (2026-10-30)

Version **1.0.1677-wayland.1** was built from
[`1a10879d035be566b4b15c775e038b471d8fd70d`](https://github.com/VibeProgramm/octo-player/commit/1a10879d035be566b4b15c775e038b471d8fd70d).
[Native CI 41057075594](https://github.com/VibeProgramm/octo-player/actions/runs/41057075594)
passed the 37 native-adapter tests, packaged Wayland/audio/HTTPS smoke and full
Ubuntu desktop suite.
[Publisher 41061088272](https://github.com/VibeProgramm/octo-player/actions/runs/41061088272)
published those exact artifacts. The public downloads were compared byte-for-byte
with the tested CI files; tag, commit, provenance and asset digests matched.
Archive SHA-256:

```text
ae84384efdf3b6a214621f5cab93db7830ac5c2937009a1374a62488ee9cef9b
```

The release and this verification record refer to that fixed commit. Subsequent
documentation updates do not change the published bundle.

The downloaded bundle was checked on AMD Radeon Vega 8, Mesa 26.2.5 and KWin
6.8.2, with bundled Temurin **27.0.1.0.2+1**. The GUI harness used two isolated
**virtual** outputs at 100% and 150% on the real GPU:

- Real Compose fields: typing/backspace/selection, queued-key bursts, Unicode
  clipboard, and delayed Ctrl+V/Shift+Insert across both Tab and click focus changes.
- text-input-v3 preedit, commit, UTF-8 deletion and window-focus lifecycle checks.
- Sign-in, library/search, real HTTP audio streaming with an advancing MPRIS
  clock, pause/resume and seeking.
- Full/mini-player switching while playing, mini-window movement followed by
  transport/volume input, mixed-DPI movement and fullscreen.
- External GTK audio-file drop, MPRIS Raise from a backgrounded/minimized mini
  player, restart with saved normal dimensions/maximization, and clean shutdown.
- Real KDE portal Save/Open roundtrip with a Cyrillic filename.

A separate packaged hardware smoke confirmed the Wayland socket and DRM render
node through `strace`, no X11 connections, working audio/HTTPS and exit 0.
Default software-renderer rejection returned exit 1.

**GUI qualification:** the complete scenario passed on the second attempt with
the same source, bundle and harness. The first attempt received an external
paste-key repeat, producing `NativeNative` in search and failing the streaming
assertion. The trace measured 785 ms between press/release despite a requested
20 ms; the source of that delay remains unresolved. Both attempts were retained
and independently reviewed; the final review verdict remained **OK**.

IME coverage uses a dedicated protocol client. Live keyboard/clipboard retention
checks explicitly clear KWin's cached preedit; exact sequences with no further
IME event are covered by actual-Compose regression tests. Complete CJK candidate
placement/reconversion and individual input-method implementations are not
qualified by these checks. Historical probe timings are recorded separately in
[`wayland-probe/RESULTS.md`](../wayland-probe/RESULTS.md).

### Compose dispatcher compatibility adapter

Compose 1.12.2 hardwires `PostDelayedDispatcher` to Skiko's Swing dispatcher.
That makes `RectManager` defragment its layout data on a different thread from
the native host, causing intermittent corruption. The host replaces the single
getter in `androidx.compose.ui.Actuals_desktopKt` with `Dispatchers.Main`, which
is bound to the Wayland event loop. The host jar **must be first** on the
classpath. Tests check the original ABI and actual delayed-callback dispatch.
Review/remove this small adapter when upstream exposes a host dispatcher here.

Keyboard/IME edit publication also uses Compose's internal `FrameRecomposer`
APIs to flush pending state before the next input event. Recheck both adapters
and the actual-Compose regression tests when changing Compose/Skiko versions.

### Native application lifetime

KDT 0.0.90's `Application.close()` drops the Wayland event loop before its EGL
display; Ubuntu's Mesa 25 crashes in `libwayland-client` during that teardown.
The player and standalone probe therefore retain their single native
`Application` until process exit.
Each stops its event loop and closes windows and rendering resources; the player
also flushes settings and disposes its audio/player resources. The OS reclaims
the process-level display and runtime. Review this workaround when updating KDT.
