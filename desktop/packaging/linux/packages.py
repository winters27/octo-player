"""Octo for Linux as an AppImage and as an Arch Linux (pacman) package, from
the same app image as the DEB, RPM and Flatpak (createDistributable's Octo
folder, with its own Java runtime and the audio engine).

  packages.py appimage --image DIR --version 1.13.0 --out DIR [--appimagetool FILE]
      Builds Octo-<version>-linux-x64.AppImage (run through
      ./gradlew :desktop:packageAppImage). Needs curl and Linux x64: it
      fetches appimagetool at a pinned version (or takes FILE, fetched
      beforehand), checks its SHA-256, and
      runs it unpacked, so the build needs no FUSE. The AppImage's runtime
      is the one inside that same appimagetool, so nothing else is fetched.

  packages.py pacman --image DIR --version 1.13.0 --out DIR
      Builds Octo-<version>-linux-x64.pkg.tar.zst (./gradlew
      :desktop:packagePacman) with makepkg on Arch, or elsewhere in a
      throwaway archlinux:base-devel container (Docker), which also
      installs the package and checks its files.

  packages.py self-test
      Checks the layouts, names and versions, with no tools.

The AppImage replaces itself when Octo updates (update/AppImageUpdate.kt):
the app's updater takes Octo-<version>-linux-x64.AppImage from the release.
A pacman install updates with sudo pacman -U, as Settings > About says.
"""
import argparse
import hashlib
import os
import platform
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
APP_ID = "app.winters.octo"
# The entry name the deb and rpm install and the app writes for itself
# (system/LinkRegistration.kt); the media controls name it too.
ENTRY = "octo-Octo.desktop"
FLATPAK_ENTRY = HERE / "flatpak" / f"{APP_ID}.desktop"
APPRUN = HERE / "appimage" / "AppRun"
PKGBUILD = HERE / "pacman" / "PKGBUILD.in"
CONTAINER = HERE / "pacman" / "build-in-container.sh"
ICON = ROOT / "desktop" / "icons" / "octo.png"
LICENSE = ROOT / "LICENSE"
APPIMAGETOOL_VERSION = "1.9.1"
APPIMAGETOOL_SHA256 = "ed4ce84f0d9caff66f50bcca6ff6f35aae54ce8135408b3fa33abfc3cb384eb0"
APPIMAGETOOL_URL = f"https://github.com/AppImage/appimagetool/releases/download/{APPIMAGETOOL_VERSION}/appimagetool-x86_64.AppImage"
TOOLS = ROOT / "desktop" / "build" / "linux-tools"
VERSION = re.compile(r"(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(-[0-9A-Za-z-]+(\.[0-9A-Za-z-]+)*)?")


def fail(message: str) -> None:
    print(f"packages: {message}", file=sys.stderr)
    sys.exit(1)


def run(*args, cwd=None, env=None) -> None:
    subprocess.run([str(arg) for arg in args], check=True, cwd=cwd, env=env)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


# The image must be createDistributable's, made for this very version: the
# launcher's settings carry the version the app shows and compares.
def check_image(image: Path, version: str) -> None:
    if not (image / "bin" / "Octo").is_file():
        fail(f"{image} is not createDistributable's Octo folder (no bin/Octo)")
    config = image / "lib" / "app" / "Octo.cfg"
    if not config.is_file() or f"java-options=-Docto.version={version}" not in config.read_text(encoding="utf-8").splitlines():
        fail(f"{image} was not built as version {version}")


# The Flatpak's desktop entry, made for another package: the program it
# starts and the icon it names.
def entry_for(program: str, icon: str, extra: list[str] = ()) -> str:
    lines = []
    for line in FLATPAK_ENTRY.read_text(encoding="utf-8").splitlines():
        if line.startswith("Exec="):
            line = f"Exec={program} %U"
        elif line.startswith("Icon="):
            line = f"Icon={icon}"
        lines.append(line)
    return "\n".join(lines + list(extra)) + "\n"


# Arch versions can't hold a dash, and a release must sort above its early
# versions: 1.3.0-beta.1 becomes 1.3.0pre.beta.1, which pacman puts before
# 1.3.0 even when the early part starts with a number.
def pkgver(version: str) -> str:
    base, dash, early = version.partition("-")
    return base + ("pre." + early.replace("-", ".") if dash else "")


# The AppImage's folder: the app under usr/, the start script, and the entry
# and icon an AppImage keeps at its top.
def appdir(image: Path, work: Path, version: str) -> Path:
    folder = work / "Octo.AppDir"
    shutil.copytree(image, folder / "usr", symlinks=True)
    # The image comes from createDistributable, never the portable zip, but
    # an AppImage updates itself, so it must never carry the portable mark.
    (folder / "usr" / "lib" / "app" / "octo-portable").unlink(missing_ok=True)
    shutil.copyfile(APPRUN, folder / "AppRun")
    (folder / "AppRun").chmod(0o755)
    (folder / ENTRY).write_text(entry_for("Octo", "octo", [f"X-AppImage-Version={version}"]), encoding="utf-8", newline="\n")
    shutil.copyfile(ICON, folder / "octo.png")
    shutil.copyfile(ICON, folder / ".DirIcon")
    return folder


# The pinned appimagetool: `given` (one fetched beforehand, checked all the
# same), or else fetched into desktop/build/linux-tools.
def appimagetool(given: Path | None = None) -> Path:
    if given is not None:
        if not given.is_file() or sha256(given) != APPIMAGETOOL_SHA256:
            fail(f"{given} is not appimagetool {APPIMAGETOOL_VERSION} (SHA-256 differs), so it was not run")
        # A download keeps no execute bit.
        given.chmod(0o755)
        return given
    TOOLS.mkdir(parents=True, exist_ok=True)
    tool = TOOLS / f"appimagetool-{APPIMAGETOOL_VERSION}.AppImage"
    if not tool.is_file() or sha256(tool) != APPIMAGETOOL_SHA256:
        run("curl", "--fail", "--location", "--silent", "--show-error", "--retry", "3", "--output", tool, APPIMAGETOOL_URL)
    if sha256(tool) != APPIMAGETOOL_SHA256:
        tool.unlink()
        fail(f"appimagetool {APPIMAGETOOL_VERSION} is not the file it should be (SHA-256 differs), so it was not run")
    tool.chmod(0o755)
    return tool


def appimage(image: Path, version: str, out: Path, tool: Path | None = None) -> Path:
    check_image(image, version)
    out.mkdir(parents=True, exist_ok=True)
    target = out / f"Octo-{version}-linux-x64.AppImage"
    tool = appimagetool(tool)
    with tempfile.TemporaryDirectory(prefix="appimage-", dir=out.parent) as temp:
        work = Path(temp)
        folder = appdir(image, work, version)
        # The pinned tool's own runtime (the part before its file system),
        # rather than a runtime fetched fresh at each build.
        offset = int(subprocess.check_output([str(tool), "--appimage-offset"], text=True).strip())
        runtime = work / "runtime"
        with tool.open("rb") as stream:
            runtime.write_bytes(stream.read(offset))
        env = {**os.environ, "ARCH": "x86_64"}
        run(tool, "--appimage-extract-and-run", "--no-appstream", "--runtime-file", runtime, folder, target, cwd=work, env=env)
    target.chmod(0o755)
    return target


# The files the PKGBUILD packs: the app, its entry and icon, and the license.
def pacman_sources(image: Path, work: Path) -> Path:
    archive = work / "octo.tar.gz"
    (work / ENTRY).write_text(entry_for("octo", APP_ID), encoding="utf-8", newline="\n")
    shutil.copyfile(ICON, work / f"{APP_ID}.png")
    shutil.copyfile(LICENSE, work / "LICENSE")
    with tarfile.open(archive, "w:gz") as tar:
        tar.add(image, arcname="Octo")
        for name in [ENTRY, f"{APP_ID}.png", "LICENSE"]:
            tar.add(work / name, arcname=name)
    return archive


def pkgbuild_for(version: str, checksum: str) -> str:
    return PKGBUILD.read_text(encoding="utf-8").replace("@PKGVER@", pkgver(version)).replace("@SHA256@", checksum)


def pacman(image: Path, version: str, out: Path) -> Path:
    check_image(image, version)
    out.mkdir(parents=True, exist_ok=True)
    target = out / f"Octo-{version}-linux-x64.pkg.tar.zst"
    with tempfile.TemporaryDirectory(prefix="pacman-", dir=out.parent) as temp:
        work = Path(temp)
        archive = pacman_sources(image, work)
        (work / "PKGBUILD").write_text(pkgbuild_for(version, sha256(archive)), encoding="utf-8", newline="\n")
        if shutil.which("makepkg"):
            run("makepkg", "--nodeps", "--noconfirm", cwd=work)
        else:
            base = version.partition("-")[0]
            run("docker", "run", "--rm",
                "--env", f"BUILD_UID={os.getuid()}",
                "--env", f"EARLY_PKGVER={pkgver(base + '-beta.1')}", "--env", f"STABLE_PKGVER={pkgver(base)}",
                "--volume", f"{work}:/work", "--volume", f"{CONTAINER}:/build.sh:ro",
                "archlinux:base-devel", "bash", "/build.sh")
        built = sorted(work.glob("*.pkg.tar.zst"))
        if len(built) != 1:
            fail(f"makepkg should make one package, made {len(built)}")
        shutil.copyfile(built[0], target)
    return target


# A made-up app image, as createDistributable lays one out.
def fake_image(root: Path, version: str) -> Path:
    image = root / "Octo"
    (image / "bin").mkdir(parents=True)
    (image / "bin" / "Octo").write_text("#!/bin/sh\n", encoding="utf-8")
    (image / "lib" / "app").mkdir(parents=True)
    (image / "lib" / "app" / "Octo.cfg").write_text(f"[JavaOptions]\njava-options=-Docto.version={version}\n", encoding="utf-8")
    (image / "lib" / "app" / "octo-portable").write_text("", encoding="utf-8")
    (image / "lib" / "Octo.png").write_bytes(b"png")
    return image


def refuses(step) -> bool:
    import contextlib
    import io
    with contextlib.redirect_stderr(io.StringIO()):
        try:
            step()
        except SystemExit:
            return True
    return False


def self_test() -> None:
    assert pkgver("1.13.0") == "1.13.0"
    assert pkgver("1.13.0-beta.1") == "1.13.0pre.beta.1"
    assert pkgver("1.13.0-rc-2") == "1.13.0pre.rc.2"
    assert "-" not in pkgver("2.0.0-x-y.3")
    assert re.fullmatch(r"[0-9a-f]{64}", APPIMAGETOOL_SHA256) and APPIMAGETOOL_VERSION in APPIMAGETOOL_URL
    # The entries start each package's own program and name its own icon,
    # with every other line (types, window class) as the Flatpak's.
    flatpak = FLATPAK_ENTRY.read_text(encoding="utf-8").splitlines()
    for program, icon in [("Octo", "octo"), ("octo", APP_ID)]:
        lines = entry_for(program, icon).splitlines()
        assert f"Exec={program} %U" in lines and f"Icon={icon}" in lines, lines
        assert [l for l in lines if not l.startswith(("Exec=", "Icon="))] == [l for l in flatpak if not l.startswith(("Exec=", "Icon="))]
    assert entry_for("octo", APP_ID) == FLATPAK_ENTRY.read_text(encoding="utf-8").replace("\r\n", "\n")
    text = pkgbuild_for("1.13.0-beta.2", "ab" * 32)
    assert "pkgver=1.13.0pre.beta.2\n" in text and "sha256sums=('" + "ab" * 32 + "')" in text and "@" not in text
    assert f"/usr/share/applications/{ENTRY}" in text and f"{APP_ID}.png" in text
    container = CONTAINER.read_text(encoding="utf-8")
    assert f"/usr/share/applications/{ENTRY}" in container and "EARLY_PKGVER" in container
    assert 'if [ "$builder_uid" = 0 ]' in container, "a build started by root still needs a builder makepkg accepts"
    assert 'exec "$here/usr/bin/Octo" "$@"' in APPRUN.read_text(encoding="utf-8")
    with tempfile.TemporaryDirectory() as temp:
        root = Path(temp)
        image = fake_image(root, "1.13.0")
        check_image(image, "1.13.0")
        assert refuses(lambda: check_image(image, "1.13.1")), "an image of another version"
        assert refuses(lambda: check_image(root / "nothing", "1.13.0"))
        impostor = root / "appimagetool.AppImage"
        impostor.write_bytes(b"not the tool")
        assert refuses(lambda: appimagetool(impostor)), "a tool that isn't the pinned one never runs"
        (root / "work-appimage").mkdir()
        folder = appdir(image, root / "work-appimage", "1.13.0")
        for name in ["AppRun", ENTRY, "octo.png", ".DirIcon", "usr/bin/Octo", "usr/lib/Octo.png"]:
            assert (folder / name).is_file(), name
        assert not (folder / "usr/lib/app/octo-portable").exists(), "an AppImage must never look portable"
        entry = (folder / ENTRY).read_text(encoding="utf-8")
        assert "Exec=Octo %U" in entry and "Icon=octo\n" in entry and "X-AppImage-Version=1.13.0" in entry
        work = root / "work-pacman"
        work.mkdir()
        with tarfile.open(pacman_sources(image, work)) as tar:
            names = set(tar.getnames())
        assert {"Octo/bin/Octo", "Octo/lib/app/Octo.cfg", ENTRY, f"{APP_ID}.png", "LICENSE"} <= names, names
    for path in [APPRUN, PKGBUILD, CONTAINER, Path(__file__)]:
        text = path.read_text(encoding="utf-8")
        assert chr(0x2014) not in text and chr(0x2013) not in text, f"{path.name} has a long dash"
    print("packages self-test passed")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest="command", required=True)
    for kind in ["appimage", "pacman"]:
        make = commands.add_parser(kind)
        make.add_argument("--image", type=Path, required=True)
        make.add_argument("--version", required=True)
        make.add_argument("--out", type=Path, required=True)
        if kind == "appimage":
            make.add_argument("--appimagetool", type=Path, help="appimagetool fetched beforehand (its SHA-256 is still checked)")
    commands.add_parser("self-test")
    args = parser.parse_args()
    if args.command == "self-test":
        self_test()
        return
    if not VERSION.fullmatch(args.version):
        fail(f"{args.version!r} is not a version like 1.13.0 or 1.14.0-beta.1")
    if platform.system() != "Linux" or platform.machine() not in ("x86_64", "AMD64"):
        fail("the AppImage and the pacman package are built on Linux x64")
    if args.command == "appimage":
        tool = args.appimagetool.resolve() if args.appimagetool else None
        print(appimage(args.image.resolve(), args.version, args.out.resolve(), tool))
    else:
        print(pacman(args.image.resolve(), args.version, args.out.resolve()))


if __name__ == "__main__":
    main()
