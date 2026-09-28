# octo-audio

The audio engine for the Octo desktop app (Windows, macOS, Linux). A Rust
library driven from Kotlin on the JVM through generated UniFFI bindings. It
plays local files and server streams with gapless joins, crossfades, the
same sound shaping as the Android app, and a sample-accurate clock.

## Layout

<!-- markdownlint-disable MD013 -->

| Path | What it holds |
| --- | --- |
| `src/api.rs` | The `Engine` object, records, events and listener |
| `src/player.rs` | The player thread: queue, device, events |
| `src/mixer.rs` | Lanes, crossfade, speed, shaping, clock markers |
| `src/lane.rs` | Gapless chains of songs through one resampler |
| `src/deck.rs` | One song decoding ahead on its own thread |
| `src/decode.rs` | symphonia decoding, gapless trims, seeking, tags |
| `src/opus.rs` | Opus through a pure Rust decoder |
| `src/source/` | Files, and HTTP(S) streams with read-ahead and pinned certificates |
| `src/sound/` | EQ, presets, limiter, balance, mono, ReplayGain |
| `src/pace.rs` | Speed (pitch kept) and pitch shift |
| `src/output/` | Device callback, audio clock, cpal and silent drivers |
| `src/timeline.rs` | Which song and second each ring frame holds |
| `bindings/kotlin/` | Generated Kotlin bindings |
| `examples/` | `play` (manual testing) and `bench` (CPU) |
| `tools/make-fixtures/` | Makes the small mp3 and opus test files |

<!-- markdownlint-enable MD013 -->

## Architecture

```text
 deck thread (per song)   player thread                  device callback
 source -> decode ------> lane: RG gain -> resample      (cpal thread)
 (file or HTTP fetch        mix (crossfade, per sample)  ring -> volume,
  thread with               speed -> EQ -> limiter  ---> pause/seek fades
  read-ahead)               markers -> timeline    ring  -> device
                                                          clock atomics
 event thread: calls the app's listener, never on the audio path
```

- **Threads.** The app's calls send commands and return at once. The player
  thread owns the queue, mixer and device. Each song decodes on its own
  thread into a 0.75 s buffer, so opening and network waits never stall
  mixing. HTTP streams fetch on another thread into a 4 MB window.
- **Ring.** The player keeps about 120 ms in a lock-free SPSC ring (`rtrb`).
  The callback never locks, allocates or waits: it copies, applies volume
  and the 10 ms fades for pause, resume and jumps, and records timing.
- **Gapless.** A lane runs consecutive songs through one resampler, so a
  join at the same source rate is exact to the sample, even when the device
  rate differs. Encoder delay and padding are trimmed where symphonia
  exposes them: the LAME header for MP3, pre-skip and granule end for Opus.
  AAC in MP4 (`iTunSMPB`) is not trimmed by symphonia 0.6.
- **Clock.** The callback notes which ring frame it played last and when
  that buffer is heard (cpal's playback timestamp, which includes device
  latency). The mixer tags ring frames with song and second. Position is
  interpolated between callbacks and never runs backwards within a song.

## Coverage against Android

<!-- markdownlint-disable MD013 -->

| Feature | Status |
| --- | --- |
| Gapless | Sample-exact, pre-decoded next song, delay/padding trimmed |
| Crossfade 0 to 12 s | Equal power, per sample; Android's rules, albums in order stay gapless |
| Volume | Linear and dB, mute, click-free ramps |
| ReplayGain | Off, track, album, smart; preamp, fallback, clipping prevention, R128 tags, stored server values |
| EQ | Graphic 10 bands and presets, parametric peak and shelves, headphone correction, auto preamp, 30 ms glides |
| Balance, mono, limiter | Same numbers (-1 dB ceiling, 1.5 ms look-ahead, 100 ms release) |
| Speed 0.5 to 2x | Pitch kept (overlap-add, as Android's player), pitch shift in semitones |
| Seek | Sample-accurate, with a fade |
| Pause and resume | 10 ms fade |
| Per-output profiles | The app keeps them: it gets `DeviceChanged` and calls `set_eq` and `set_dsp` |
| Trusted self-signed certificates | `set_trusted_certificates`: the system's check first, then a SHA-256 pin for that host only |
| Skip silence | Not ported |

<!-- markdownlint-enable MD013 -->

## Crates

All newest stable, checked with `python scripts/check-deps.py`.

<!-- markdownlint-disable MD013 -->

| Crate | Version | Why |
| --- | --- | --- |
| symphonia | 0.6.1 | FLAC, MP3, AAC/M4A, ALAC, Vorbis, WAV, AIFF; gapless trims |
| opus-decoder | 0.1.1 | Pure Rust Opus, passes all 12 RFC 8251 vectors |
| cpal | 0.18.2 | WASAPI, Core Audio, ALSA; default-device events |
| rubato | 5.0.0 | FFT resampler to the device rate |
| rtrb | 0.4.0 | Wait-free ring to the callback |
| ureq | 3.4.2 | Small blocking HTTP client, rustls with the OS trust store |
| rustls, rustls-platform-verifier, ring | 0.23.45, 0.7.1, 0.17.14 | ureq's own TLS, named to add pinned certificates to the OS check |
| uniffi | 0.32.2 | Kotlin bindings |
| crossbeam-channel, thiserror, log | 0.5.17, 2.0.21, 0.4.34 | Commands, errors, logs |
| flacenc (tests only) | 0.5.1 | FLAC files made while testing |
| rcgen (tests only) | 0.14.10 | Self-signed certificates for the HTTPS test server |

<!-- markdownlint-enable MD013 -->

**Opus.** symphonia 0.6 has no Opus decoder. The libopus bindings
(`opusic-sys`, `audiopus_sys`, `libopus_sys`) all build libopus with CMake,
and some need libclang, so they fail on a plain machine. `opus-decoder` is
safe pure Rust and conformance-tested. It is slower than libopus, which is
still well inside budget for music. `opus-rs` is faster but has no
conformance vectors in its tests.

**Speed.** No mature pure Rust time-stretch crate exists. `timestretch`
(0.15) is capable but young, single-author and aimed at DJ decks;
`signalsmith-stretch` needs libclang; the WSOLA crates are weeks old. So
speed uses pitch-synchronous overlap-add written here, the same family as
the Sonic method the Android player uses, with a cubic rate change for
pitch shifts.

## Building

Rust 1.88 or newer (tested with 1.91.1), no toolchain file needed.

- **Windows:** MSVC build tools.
- **macOS:** Xcode command line tools.
- **Linux:** `sudo apt install libasound2-dev pkg-config` (Fedora:
  `alsa-lib-devel`). Output goes through ALSA; with `pipewire-alsa` or the
  PulseAudio ALSA plugin installed, the ALSA `default` device plays through
  PipeWire or PulseAudio and follows its default sink.

```sh
cargo build --release
cargo test
cargo clippy --all-targets -- -D warnings
```

`.github-workflow-example.yml` is a matrix build for all three systems, to
copy into `.github/workflows/` later.

## Kotlin bindings

```sh
sh scripts/gen-kotlin.sh        # or scripts/gen-kotlin.ps1 on Windows
```

This builds the release library and writes
`bindings/kotlin/app/winters/octo/audio/octo_audio.kt`. The JVM side needs
JNA 5.12 or newer (tested with 5.17) and the native library on
`jna.library.path` or bundled as a JNA resource.

```kotlin
val engine = Engine()                  // Engine.newSilent() for tests
engine.setListener(object : EngineListener {
    override fun onEvent(event: EngineEvent) { /* on the engine's thread */ }
})
engine.load(listOf(QueueItem(id = "q:1", source = url)), 0u, 0uL, true)
engine.setCrossfade(6000u)
engine.position().positionMs          // audio clock, fractional ms
```

- **Queue:** `load`, `playNext`, `enqueue`, `replaceUpcoming`,
  `replaceQueue`, `skipTo`, `skipNext`, `setRepeat`, `setStopAfterCurrent`,
  `queue`.
- **Transport:** `play`, `pause`, `stop`, `seek`.
- **Sound:** `setVolume`, `setVolumeDb`, `setMuted`, `setCrossfade`,
  `setEq`, `setReplaygain`, `setDsp`, `setSpeed(speed, pitch)`.
- **Devices:** `devices`, `currentDevice`, `setOutputDevice(id or null)`,
  `outputFormat` (the rate, channels and sample format the device's stream
  was opened with).
- **State:** `position`, `state`, `nowPlaying`, `underruns`,
  `setPositionInterval`, `shutdown`.
- **Also:** `equalizerPresets()`, `graphicBands()`.
- **Events:** `TrackStarted`, `TrackEnded` (with a reason),
  `GaplessTransition`, `CrossfadeStarted`, `Buffering`, `Ready`,
  `StateChanged`, `QueueEnded`, `Error` (with a kind), `DeviceChanged`
  (with the device's format), `Position`. `TrackStarted` carries the song's
  own format as decoded (codec, lossless, rate, channels, bits).

Server streams arrive as signed addresses from the app, as on Android, with
optional extra headers per item. The engine reuses the address for range
requests and reconnects.

## Tests

`cargo test` needs no sound device: engine tests play through the silent
driver. WAV and FLAC files are made while the tests run; the MP3 and Opus
fixtures are tones made by `tools/make-fixtures`.

## Performance

`cargo run --release --example bench`, on this machine (Windows 11):

<!-- markdownlint-disable MD013 -->

| Stage, 44.1 kHz 16-bit FLAC to a 48 kHz device | Per second of audio |
| --- | --- |
| FLAC decode | 0.78 ms |
| EQ (10 bands) + preamp + limiter | 2.2 ms |
| Whole chain (decode, resample, EQ, limiter) | about 3 ms |

<!-- markdownlint-enable MD013 -->

Real-time playback on WASAPI with the Rock preset and the limiter, measured
from outside the process: 0.63 % of one core and a 13.5 MB working set
(3.3 MB private), no underruns.

## Needs real hardware

- Clock accuracy against what is heard (latency figures from each backend).
- Default-device following: unplugging USB and Bluetooth, and Windows,
  macOS and PipeWire default changes.
- Long pauses stopping the stream, and resuming after sleep.
- macOS and Linux builds and playback.
- Long runs over flaky networks.
