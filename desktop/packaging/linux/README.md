# Linux release packages

`desktop-installers.yml` builds DEB, RPM, portable ZIP, AppImage, Flatpak and
pacman packages for x86_64. All six use the same `createDistributable` app image,
including its trimmed Java 17 runtime and Rust audio engine. Java does not need
to be installed on the listener's computer.

## Install and update

Download a package from the [desktop releases](https://github.com/winters27/octo/releases?q=desktop-v&expanded=true).
The release's `SHA256SUMS` and signed `update.json` cover every package.

**AppImage** (Ubuntu 24.04 or newer, or a distribution with glibc 2.39 or newer):

```sh
chmod +x Octo-<version>-linux-x64.AppImage
./Octo-<version>-linux-x64.AppImage
# Without FUSE:
./Octo-<version>-linux-x64.AppImage --appimage-extract-and-run
```

It uses the desktop's X11/XWayland, OpenGL, fontconfig and ALSA libraries;
`libsecret` and a Secret Service such as GNOME Keyring or KWallet let it remember
passwords. Keep the AppImage in a permanent folder: the `octo://` handler points
there. Close Octo and replace that file to update it. Settings remain in the
usual XDG folders, outside the image.

**Flatpak**:

```sh
flatpak install --user ./Octo-<version>-linux-x64.flatpak
flatpak run app.winters.octo
```

The bundle names Flathub as its runtime source, so Flatpak can install
`org.freedesktop.Platform` 26.08 when needed. Install the new bundle the same way
to update; GitHub bundles do not provide a remote for `flatpak update` to find
new Octo versions. Prereleases use the `beta` branch; stable releases use `stable`.

Settings and cache live under `~/.var/app/app.winters.octo/`. The sandbox allows
network streaming, X11 (also through XWayland), graphics acceleration, audio
through PulseAudio/PipeWire, MPRIS, notifications and Secret Service. It can read
Downloads for imports and read/write Music for local and offline songs. To use
another music directory, grant that directory with Flatseal or:

```sh
flatpak override --user --filesystem=/path/to/music app.winters.octo
```

**Arch Linux / pacman**:

```sh
sudo pacman -U ./Octo-<version>-linux-x64.pkg.tar.zst
octo
```

The package is named `octo-player`, installs under `/opt/octo-player`, and
includes `/usr/bin/octo`, its icon and desktop entry. Install a newer release's
package with the same command to update. Settings > About also offers the correct
pacman package. Prereleases use an Arch version such as `1.3.0pre.beta.1`, which
sorts below `1.3.0` while the app and release keep `1.3.0-beta.1`.

## Build

In addition to the normal desktop build prerequisites, install Python 3.11+,
curl, `flatpak-builder`, Flatpak and Docker (or `makepkg` on Arch). Set up the
Flatpak build runtime once:

```sh
flatpak remote-add --user --if-not-exists flathub https://flathub.org/repo/flathub.flatpakrepo
flatpak install --user flathub org.freedesktop.Platform//26.08 org.freedesktop.Sdk//26.08
./gradlew :desktop:packageAppImage :desktop:packageFlatpak :desktop:packagePacman \
  -PoctoBuild="$(git rev-list --count HEAD)" -PoctoDesktopVersion=1.2.0
```

Packages land in `desktop/build/compose/binaries/main/{appimage,flatpak,pacman}`.
`package-linux.py` validates the version against the image's launcher configuration.
It downloads appimagetool 1.9.1 with a pinned SHA-256 and reuses that AppImage's
runtime, so packaging needs neither FUSE nor an unpinned runtime download.
The Flatpak manifest packages the same prebuilt image against the newer runtime;
the release build's Ubuntu 24.04 baseline must not exceed that runtime's ABI.
The pacman task uses `makepkg` locally or a disposable `archlinux:base-devel`
container, where the finished package is also installed with its dependencies.

## Release checks

PRs and manual workflow runs test and build the same installers as `desktop-v*`
tags, then download **both platforms' artifacts** and rehearse the real release
staging, manifest and checksum generation. Every Linux format is required;
missing or duplicate packages fail staging. Only upstream tag runs call
`publish-player-release.yml`, after these checks. That existing publisher signs
the manifest, verifies its signature and publishes a complete draft on
`winters27/octo` with `--latest=false`.

`smoke-test.sh` uses Xvfb and an isolated PulseAudio null sink to launch all three
new packages, play a silent tone through the real audio engine, check the version
and save settings. It extracts AppImage without FUSE and installs the Flatpak
bundle with its actual sandbox permissions. Run it on a disposable CI user:

```sh
bash desktop/packaging/linux/smoke-test.sh desktop/build/compose/binaries/main 1.2.0
python3 scripts/release/release_assets.py self-test
```
