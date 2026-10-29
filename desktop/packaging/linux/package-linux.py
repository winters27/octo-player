"""Package createDistributable's Linux app image, including its Java runtime.

Run through :desktop:packageAppImage, :desktop:packageFlatpak or
:desktop:packagePacman. See README.md in this folder for the build tools.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import tarfile
import tempfile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
APP_ID = "app.winters.octo"
APPIMAGETOOL_VERSION = "1.9.1"
APPIMAGETOOL_SHA256 = "ed4ce84f0d9caff66f50bcca6ff6f35aae54ce8135408b3fa33abfc3cb384eb0"


def run(*args, **kwargs):
    subprocess.run([str(arg) for arg in args], check=True, **kwargs)


def checksum(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def payload(image, dest):
    shutil.copytree(image, dest / "Octo", symlinks=True)
    licenses = dest / "Octo/share/licenses/octo"
    licenses.mkdir(parents=True)
    shutil.copy2(ROOT / "LICENSE", licenses / "LICENSE")
    shutil.copytree(ROOT / "design/licenses", licenses / "design")
    shutil.copy2(ROOT / "desktop/icons/octo.png", dest / "octo.png")
    shutil.copy2(HERE / "octo.desktop", dest / "octo.desktop")
    shutil.copy2(HERE / f"{APP_ID}.metainfo.xml", dest / f"{APP_ID}.metainfo.xml")


def appimage(work, out, version):
    appdir = work / "Octo.AppDir"
    appdir.mkdir()
    shutil.move(work / "Octo", appdir / "usr")
    shutil.copy2(work / "octo.png", appdir / "octo.png")
    desktop = (work / "octo.desktop").read_text().replace("Exec=octo ", "Exec=Octo ").replace(f"Icon={APP_ID}", "Icon=octo")
    (appdir / "octo-Octo.desktop").write_text(desktop)
    shutil.copy2(HERE / "AppRun", appdir / "AppRun")
    (appdir / "AppRun").chmod(0o755)
    # Also identifies an extracted AppImage, which has no APPIMAGE variable.
    (appdir / "usr/lib/app/octo-portable").touch()
    tools = ROOT / "desktop/build/linux-tools"
    tools.mkdir(parents=True, exist_ok=True)
    tool = tools / f"appimagetool-{APPIMAGETOOL_VERSION}.AppImage"
    if not tool.exists() or checksum(tool) != APPIMAGETOOL_SHA256:
        run("curl", "--fail", "--location", "--retry", "3", "--output", tool,
            f"https://github.com/AppImage/appimagetool/releases/download/{APPIMAGETOOL_VERSION}/appimagetool-x86_64.AppImage")
    if checksum(tool) != APPIMAGETOOL_SHA256:
        raise ValueError("appimagetool's SHA-256 does not match")
    tool.chmod(0o755)
    # Reuse the pinned tool's own type-2 runtime rather than letting it fetch
    # a moving 'continuous' runtime during every build. No FUSE is needed.
    offset = int(subprocess.check_output([str(tool), "--appimage-offset"]))
    runtime = work / "runtime"
    with tool.open("rb") as stream:
        runtime.write_bytes(stream.read(offset))
    run(tool, "--appimage-extract-and-run", "--runtime-file", runtime, appdir,
        out / f"Octo-{version}-linux-x64.AppImage", env={**os.environ, "ARCH": "x86_64"})


def flatpak(work, out, version):
    manifest = json.loads((HERE / f"flatpak/{APP_ID}.json").read_text())
    # Early versions install on a separate branch, just as the release is marked early.
    branch = "beta" if "-" in version else "stable"
    manifest["branch"] = branch
    source = work / "payload"
    source.mkdir()
    for path in list(work.iterdir()):
        if path != source:
            shutil.move(path, source / path.name)
    for name in ("octo", "asound.conf"):
        shutil.copy2(HERE / "flatpak" / name, source / name)
    config = work / f"{APP_ID}.json"
    config.write_text(json.dumps(manifest, indent=2) + "\n")
    repo = work / "repo"
    run("flatpak-builder", "--user", "--force-clean", "--disable-rofiles-fuse",
        f"--repo={repo}", work / "build", config, cwd=work)
    run("flatpak", "build-bundle", "--arch=x86_64",
        "--runtime-repo=https://flathub.org/repo/flathub.flatpakrepo",
        repo, out / f"Octo-{version}-linux-x64.flatpak", APP_ID, branch)


def pacman(work, out, version):
    # Arch forbids '-' in pkgver. 'pre' sorts before the matching stable
    # version even when SemVer's prerelease starts with a number.
    base, dash, early = version.partition("-")
    pkgver = base + ("pre." + early.replace("-", ".") if dash else "")
    archive = work / "octo.tar.gz"
    with tarfile.open(archive, "w:gz") as tar:
        for name in ("Octo", "octo.desktop", "octo.png"):
            tar.add(work / name, arcname=name)
    template = (HERE / "pacman/PKGBUILD.in").read_text()
    (work / "PKGBUILD").write_text(template.replace("@VERSION@", pkgver).replace("@SHA256@", checksum(archive)))
    if shutil.which("makepkg"):
        run("makepkg", "--nodeps", "--noconfirm", cwd=work)
    else:
        run("docker", "run", "--rm", "--env", f"BUILD_UID={os.getuid()}",
            "--volume", f"{work}:/work", "--volume", f"{HERE / 'pacman/build-container.sh'}:/build.sh:ro",
            "archlinux:base-devel", "bash", "/build.sh")
    packages = list(work.glob("*.pkg.tar.zst"))
    if len(packages) != 1:
        raise ValueError(f"expected one pacman package, found {len(packages)}")
    shutil.copy2(packages[0], out / f"Octo-{version}-linux-x64.pkg.tar.zst")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("kind", choices=("appimage", "flatpak", "pacman"))
    parser.add_argument("--image", required=True, type=Path)
    parser.add_argument("--out", required=True, type=Path)
    parser.add_argument("--version", required=True)
    args = parser.parse_args()
    if platform.system() != "Linux" or platform.machine() != "x86_64":
        parser.error("these packages are built on Linux x86_64")
    if not re.fullmatch(r"(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(-[0-9A-Za-z-]+(\.[0-9A-Za-z-]+)*)?", args.version):
        parser.error("--version must be a SemVer version, such as 1.2.0 or 1.3.0-beta.1")
    image = args.image.resolve()
    if not (image / "bin/Octo").is_file() or not os.access(image / "bin/Octo", os.X_OK):
        parser.error("--image must be createDistributable's Octo folder, with an executable bin/Octo")
    config = (image / "lib/app/Octo.cfg").read_text()
    if f"java-options=-Docto.version={args.version}" not in config.splitlines():
        parser.error("the app image's version does not match --version")
    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=f"{args.kind}-", dir=out.parent) as temp:
        work = Path(temp)
        payload(image, work)
        {"appimage": appimage, "flatpak": flatpak, "pacman": pacman}[args.kind](work, out, args.version)


if __name__ == "__main__":
    main()
