# Dependency refresh and Java 27

Stable dependency review: 2026-10-30. Versions live in
`gradle/libs.versions.toml`, the Gradle wrapper, Cargo manifests/lockfiles and
`tools/cover-art/requirements.txt`.

| Component | Selected version |
| --- | --- |
| Desktop runtime, tests, jlink, jpackage | Java 27 |
| Shared/Android and ordinary desktop bytecode | Java 17 |
| Native player/probe FFM bytecode (JDK 27 toolchain) | Java 25 |
| Gradle | 9.8.2, distribution SHA-256 checked |
| Kotlin and Compose compiler | 2.4.21 |
| Android Gradle Plugin | 9.5.0 |
| Compose Multiplatform | 1.12.2 |
| Skiko | Compose's matched 0.150.2 |
| Haze | 2.0.1 |
| OkHttp | 5.5.1 |
| kotlinx.serialization | 1.12.0 |
| DataStore / WorkManager | 1.3.0 / 2.12.0 |
| CameraX / Cast framework | 1.6.2 / 23.0.0 |
| ZXing / Palette / Robolectric | 3.5.4 / 1.1.0 / 4.17 |
| rubato / rtrb / ureq / rustls | 5.0.1 / 0.4.1 / 3.5.0 / 0.23.46 |
| objc2 / serde_json / fixture opus-rs | 0.6.5 / 1.0.154 / 0.1.40 |
| NumPy / Pillow | 2.5.4 / 13.0.0 |
| Kotlin Desktop Toolkit (native player and probe) | 0.0.90 |

## Compatibility choices

- Keep coherent stable library families and resolved transitive dependencies.
  A newer standalone Skiko is not a drop-in guarantee for Compose or Octo's
  reflection-based GPU cache management. In particular, do not force Skiko
  0.156 onto this Compose version.
- Keep the locally patched CPAL and Rust 1.88 MSRV. All three Cargo lockfiles
  are updated; `ureq`'s unversioned transport adapter now receives its clock,
  and the idle limiter preserves the new per-read/per-write timeout fields.
- Keep the existing JUnit 4 suite and its latest 4.x release. Jupiter is a test
  framework migration, not a compatible version-catalog bump.
- Gradle 9.8 supports running on Java 27. Kotlin 2.4.21's published fully
  supported integration range ends at Gradle 9.7 / AGP 9.3.1; this newer
  combination therefore needs the actual Android and desktop CI checks.
- Kotlin 2.4.21 does not emit Java 27 bytecode. Shared/Android and ordinary
  desktop code stay at target 17; both FFM-based modules (`desktop-wayland`
  and `wayland-probe`) target 25 while compiling and running with JDK 27.
  Bytecode target and installed runtime are separate choices.
- Standard OpenJDK 27 does not provide JBR's WLToolkit. Switching the runtime
  alone cannot make Compose/AWT a native Wayland GPU client. The
  [native player](../desktop-wayland/README.md) hosts the real Octo UI through
  Wayland/EGL/Skia; the [probe](../wayland-probe/README.md) shares its GPU bridge.
- Compose's delayed dispatcher and text-state publication need native-host
  compatibility adapters. KDT's process-level `Application` is retained until
  exit to avoid its Mesa 25 teardown crash. The native guide documents these
  version-sensitive choices and their regression tests.

## Reproducible checks

```sh
./gradlew :desktop:test :desktop-design:test :shared:core:desktopTest
./gradlew :desktop-wayland:test
./gradlew :shared:core:allTests :app:testDebugUnitTest :app:assembleDebug
cargo test --manifest-path audio-engine/Cargo.toml --locked
cargo check --manifest-path desktop/system-shim/Cargo.toml --locked
cargo check --manifest-path audio-engine/tools/make-fixtures/Cargo.toml --locked
./gradlew :wayland-probe:installDist
JAVA_HOME=/path/to/jdk-27 bash wayland-probe/check-gpu.sh /path/to/results
```

`desktop-installers.yml` installs JDK 17 and 27 and builds packages in Actions.
Linux package smoke tests require both `check: java 27` and successful HTTPS
from the actual packaged app. Those Xvfb/software smoke tests check packaging
and audio, including the extracted portable ZIP's executable permissions.
`wayland-preview.yml` tests and packages the native player, with explicit test-only
software EGL. The downloaded native bundle receives separate real-GPU and
[GUI acceptance](../desktop-wayland/README.md#verified-prerelease-2026-10-30).

`dependency-checks.yml` builds/tests Android with only JDK 17, runs Rust tests,
compiles the Wayland probe on Java 27, and renders small covers with the new
NumPy/Pillow. A Linux system-shim check does not test macOS or Windows code;
Windows packaging exercises that platform in the installer workflow.

## Final verification and publication

The reviewed release snapshot is
[`1a10879d035be566b4b15c775e038b471d8fd70d`](https://github.com/VibeProgramm/octo-player/commit/1a10879d035be566b4b15c775e038b471d8fd70d).
The reviewer/fixer/independent-verifier cycle finished with **OK** for that
100-file baseline-to-release change set.

- [Native player, run 41057075594](https://github.com/VibeProgramm/octo-player/actions/runs/41057075594):
  **passed** native-adapter tests (37 cases), portable Java 27 packaging,
  Wayland/audio/HTTPS smoke, default software-renderer rejection and the full
  Ubuntu desktop suite at the release commit.
- [Desktop installers, run 41057075887](https://github.com/VibeProgramm/octo-player/actions/runs/41057075887):
  **passed** Linux and Windows desktop tests; built MSI, Windows/Linux portable
  ZIPs, DEB, RPM, AppImage, Flatpak and pacman packages; Linux package smoke and
  cross-platform release manifest/checksum rehearsal passed at the same commit.
  Downloaded Windows XML totals: **1,276 tests, 0 failures/errors, 45 skipped**.
  XML/HTML diagnostics are retained even when an installer test job fails.
- [Dependency checks, run 41041711826](https://github.com/VibeProgramm/octo-player/actions/runs/41041711826):
  **passed** shared `allTests`, Android unit tests and debug APK with only JDK 17;
  Rust's 209 audio tests; Java 27 probe compilation; NumPy/Pillow checks and both
  cover renderers. This run used `69c03e6b`; the dependency/Android/shared/Rust/
  probe inputs are unchanged at the release commit. Android's API 37 base SDK
  package is named `platforms;android-37.0`.
- [Publisher, run 41061088272](https://github.com/VibeProgramm/octo-player/actions/runs/41061088272):
  **published** [1.0.1677-wayland.1](https://github.com/VibeProgramm/octo-player/releases/tag/preview-wayland-1.0.1677-wayland.1-run-41057075594)
  directly from the successful native CI artifacts. Public assets were downloaded,
  checked against SHA-256/tag/commit/provenance and compared byte-for-byte with
  the tested CI files.

The exact native bundle also passed AMD/KWin hardware/audio/HTTPS/no-X11 smoke
and the full GUI scenario on an unchanged second attempt. The
[native guide](../desktop-wayland/README.md#verified-prerelease-2026-10-30)
records the first attempt's external paste repeat, unresolved input delay,
IME-test qualifications and remaining platform coverage. Historical probe
timings are kept in its [results](../wayland-probe/RESULTS.md).

Review fixes cover delayed-paste targeting, clipboard generations, logical
shortcuts, pointer cancellation, IME lifecycle and queued-edit publication,
window-state restoration, portable executable permissions and probe teardown.
Windows animation/decoder tests now use child-JVM ownership checks, EDT-confined
scenes, actual readiness and awaited cleanup, retaining strict color/hold
assertions. The final Windows run validates those checks; the historical
black-frame cause and identity of an earlier disappearing thread remain unknown.

Earlier local exploration passed 1,159 shared desktop and 50 design tests,
Rust system-shim/fixture checks, and Java 27 launch/audio/HTTPS checks. Its full
offscreen desktop run had two visual failures (`ContrastTest` yellow wash and
`TitleRowTest` heart alignment), both reproduced on the pre-update baseline.
Those historical local results are distinct from the final successful CI suites.

Release packages were built and published through GitHub Actions. Subsequent
documentation-only edits describe the fixed release snapshot; they are not
included in that binary.
