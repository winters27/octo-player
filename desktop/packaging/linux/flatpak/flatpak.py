"""Octo for Linux as a Flatpak, from the same app image as the DEB and RPM.

  flatpak.py bundle --image DIR --version 1.12.0 --out DIR
      Builds Octo-<version>-linux-x64.flatpak from createDistributable's
      Octo folder (run through ./gradlew :desktop:packageFlatpak). Needs
      flatpak and flatpak-builder, and the Flathub remote for the runtime:
        flatpak remote-add --user --if-not-exists flathub https://dl.flathub.org/repo/flathub.flatpakrepo
        flatpak install --user -y flathub org.freedesktop.Platform//25.08 org.freedesktop.Sdk//25.08

  flatpak.py flathub --version 1.12.0 --zip Octo-1.12.0-linux-x64-portable.zip --out DIR
      Writes the folder a Flathub submission is: app.winters.octo.json with
      the released portable zip's address and SHA-256, and the files beside
      it. Flathub builds from that zip; after the first submission its
      x-checker-data lets Flathub's checker bring each new desktop release in.

  flatpak.py self-test
      Checks the manifest, desktop entry and metainfo agree, with no tools.

The sandbox (finish-args in app.winters.octo.json):
  - network, ipc; x11 for the window (Java's toolkit draws through X11, by
    XWayland on a Wayland desktop) and wayland beside it; dri for drawing;
  - pulseaudio, which PipeWire answers too: the audio engine plays through
    ALSA, and the runtime routes ALSA's default device there;
  - home: the file dialogs (Java's own, not the portal's) open and save
    lists, presets and the offline folder anywhere in it; /media, /run/media
    and /mnt read only, for music on other drives;
  - the media controls' bus name (org.mpris.MediaPlayer2.octo), the desktop's
    notices, the password store (libsecret), logind's sleep and wake, and
    Discord's sockets for the listening status.
Inside the Flatpak the app's own updater is off (Flatpak updates it), and it
writes no desktop entry of its own.
"""
import argparse
import copy
import datetime
import hashlib
import json
import re
import shutil
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
APP_ID = "app.winters.octo"
MANIFEST = HERE / f"{APP_ID}.json"
DESKTOP = HERE / f"{APP_ID}.desktop"
METAINFO = HERE / f"{APP_ID}.metainfo.xml"
LAUNCHER = HERE / "octo-launcher"
ICON = ROOT / "desktop/icons/octo.png"
FLATHUB_REPO = "https://dl.flathub.org/repo/flathub.flatpakrepo"
RELEASES = "https://github.com/winters27/octo/releases/download"
VERSION = re.compile(r"(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(-[0-9A-Za-z-]+(\.[0-9A-Za-z-]+)*)?")


def fail(message: str) -> None:
    print(f"flatpak: {message}", file=sys.stderr)
    sys.exit(1)


def run(*args, cwd=None) -> None:
    subprocess.run([str(arg) for arg in args], check=True, cwd=cwd)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def manifest() -> dict:
    return json.loads(MANIFEST.read_text(encoding="utf-8"))


# The metainfo with this version as its newest release, dated today, and any
# older entry for the same version dropped.
def metainfo_for(version: str, today: datetime.date) -> str:
    text = METAINFO.read_text(encoding="utf-8")
    entry = f'<release version="{version}" date="{today.isoformat()}"/>'
    text = re.sub(rf'\s*<release version="{re.escape(version)}"[^>]*/>', "", text)
    return text.replace("<releases>", f"<releases>\n    {entry}", 1)


# The files that go beside the manifest: the launcher, the desktop entry, the
# metainfo for this version and the icon (drawn from the vector octopus by
# :desktop:makeIcons).
def write_beside(folder: Path, version: str) -> None:
    shutil.copyfile(LAUNCHER, folder / LAUNCHER.name)
    (folder / LAUNCHER.name).chmod(0o755)
    shutil.copyfile(DESKTOP, folder / DESKTOP.name)
    (folder / METAINFO.name).write_text(metainfo_for(version, datetime.date.today()), encoding="utf-8", newline="\n")
    shutil.copyfile(ICON, folder / f"{APP_ID}.png")


# The manifest with the app's files from `source` in place of the released zip.
def with_source(base: dict, source: dict, branch: str | None = None) -> dict:
    out = copy.deepcopy(base)
    module = out["modules"][0]
    module["sources"][0] = source
    if branch:
        out["branch"] = branch
    return out


def bundle(image: Path, version: str, out: Path) -> Path:
    if not (image / "bin/Octo").is_file():
        fail(f"{image} is not createDistributable's Octo folder (no bin/Octo)")
    out.mkdir(parents=True, exist_ok=True)
    # An early version installs on its own branch, as the release is marked early.
    branch = "beta" if "-" in version else "stable"
    target = out / f"Octo-{version}-linux-x64.flatpak"
    with tempfile.TemporaryDirectory(prefix="flatpak-", dir=out.parent) as temp:
        work = Path(temp)
        shutil.copytree(image, work / "octo", symlinks=True)
        write_beside(work, version)
        config = work / f"{APP_ID}.json"
        config.write_text(json.dumps(with_source(manifest(), {"type": "dir", "path": "octo", "dest": "octo"}, branch), indent=2) + "\n", encoding="utf-8")
        repo = work / "repo"
        run("flatpak-builder", "--user", "--install-deps-from=flathub", "--force-clean", "--disable-rofiles-fuse",
            f"--repo={repo}", work / "build", config, cwd=work)
        run("flatpak", "build-bundle", "--arch=x86_64", f"--runtime-repo={FLATHUB_REPO}", repo, target, APP_ID, branch)
    return target


def flathub(version: str, zip_file: Path, out: Path) -> Path:
    out.mkdir(parents=True, exist_ok=True)
    source = copy.deepcopy(manifest()["modules"][0]["sources"][0])
    source["url"] = f"{RELEASES}/desktop-v{version}/Octo-{version}-linux-x64-portable.zip"
    source["sha256"] = sha256(zip_file)
    target = out / f"{APP_ID}.json"
    target.write_text(json.dumps(with_source(manifest(), source), indent=2) + "\n", encoding="utf-8")
    write_beside(out, version)
    return target


# What the app's code names, read from it, so the sandbox and entry follow it.
def code_names() -> dict:
    payloads = (ROOT / "desktop/src/main/kotlin/app/winters/octo/desktop/system/MediaPayloads.kt").read_text(encoding="utf-8")
    links = (ROOT / "desktop/src/main/kotlin/app/winters/octo/desktop/system/LinkRegistration.kt").read_text(encoding="utf-8")
    bus = re.search(r'BUS_NAME = "([^"]+)"', payloads).group(1)
    types = re.findall(r'"(audio/[a-z0-9.+-]+)"', re.search(r"AUDIO_TYPES = listOf\(([^)]*)\)", links).group(1))
    return {"bus": bus, "types": types}


def problems() -> list[str]:
    found = []
    data = manifest()
    if data.get("app-id") != APP_ID:
        found.append("the manifest's app-id is not " + APP_ID)
    if data.get("command") != "octo":
        found.append("the manifest's command is not octo")
    args = data.get("finish-args", [])
    names = code_names()
    for needed in ["--share=network", "--share=ipc", "--socket=x11", "--socket=wayland", "--socket=pulseaudio", "--device=dri",
                   "--filesystem=home", f"--own-name={names['bus']}", "--talk-name=org.freedesktop.Notifications",
                   "--talk-name=org.freedesktop.secrets", "--system-talk-name=org.freedesktop.login1"]:
        if needed not in args:
            found.append(f"finish-args lacks {needed}")
    if len(args) != len(set(args)):
        found.append("finish-args repeats one")
    module = data["modules"][0]
    sources = module["sources"]
    if sources[0].get("type") != "archive" or "x-checker-data" not in sources[0]:
        found.append("the first source is not the released zip with its checker")
    beside = {s["path"] for s in sources[1:] if s.get("type") == "file"}
    for name in [LAUNCHER.name, DESKTOP.name, METAINFO.name, f"{APP_ID}.png"]:
        if name not in beside:
            found.append(f"{name} is not among the sources")
        if name != f"{APP_ID}.png" and not (HERE / name).is_file():
            found.append(f"{name} is missing beside the manifest")
    commands = " ".join(module["build-commands"])
    for installed in ["/app/bin/octo", f"/app/share/applications/{APP_ID}.desktop", f"/app/share/metainfo/{APP_ID}.metainfo.xml",
                      f"/app/share/icons/hicolor/512x512/apps/{APP_ID}.png", "octo-portable"]:
        if installed not in commands:
            found.append(f"the build commands never name {installed}")
    entry = dict(line.split("=", 1) for line in DESKTOP.read_text(encoding="utf-8").splitlines() if "=" in line)
    if entry.get("Icon") != APP_ID:
        found.append("the desktop entry's icon is not the app id")
    if not entry.get("Exec", "").startswith("octo "):
        found.append("the desktop entry does not start octo")
    mimes = [m for m in entry.get("MimeType", "").split(";") if m]
    if mimes != names["types"] + ["x-scheme-handler/octo"]:
        found.append("the desktop entry's types differ from the app's own (LinkRegistration.kt)")
    if entry.get("StartupWMClass") != "app-winters-octo-desktop-MainKt":
        found.append("the desktop entry's window class is not the app's main class")
    info = ET.parse(METAINFO).getroot()
    if info.findtext("id") != APP_ID or info.findtext("launchable") != f"{APP_ID}.desktop":
        found.append("the metainfo's id or launchable is wrong")
    if not info.findall("screenshots/screenshot/image") or info.find("releases/release") is None:
        found.append("the metainfo lacks screenshots or a release")
    for image in info.findall("screenshots/screenshot/image"):
        local = HERE / "screenshots" / image.text.rsplit("/", 1)[-1]
        if not local.is_file():
            found.append(f"screenshot {local.name} is not in screenshots/")
    for path in [DESKTOP, METAINFO, LAUNCHER, MANIFEST]:
        text = path.read_text(encoding="utf-8")
        if "—" in text or "–" in text:
            found.append(f"{path.name} has a long dash")
    return found


def self_test() -> None:
    found = problems()
    if found:
        fail("\n  ".join(["the Flatpak files disagree:"] + found))
    text = metainfo_for("9.9.9", datetime.date(2030, 1, 2))
    assert '<release version="9.9.9" date="2030-01-02"/>' in text
    assert text.count('version="9.9.9"') == 1
    assert metainfo_for("1.12.0", datetime.date(2030, 1, 2)).count('version="1.12.0"') == 1
    local = with_source(manifest(), {"type": "dir", "path": "octo", "dest": "octo"}, "beta")
    assert local["branch"] == "beta" and local["modules"][0]["sources"][0]["type"] == "dir"
    assert manifest()["modules"][0]["sources"][0]["type"] == "archive", "with_source changed the original"
    print("flatpak self-test passed")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest="command", required=True)
    make = commands.add_parser("bundle")
    make.add_argument("--image", type=Path, required=True)
    make.add_argument("--version", required=True)
    make.add_argument("--out", type=Path, required=True)
    hub = commands.add_parser("flathub")
    hub.add_argument("--version", required=True)
    hub.add_argument("--zip", type=Path, required=True)
    hub.add_argument("--out", type=Path, required=True)
    commands.add_parser("self-test")
    args = parser.parse_args()
    if args.command == "self-test":
        self_test()
        return
    if not VERSION.fullmatch(args.version):
        fail(f"{args.version!r} is not a version like 1.12.0 or 1.13.0-beta.1")
    if args.command == "bundle":
        print(bundle(args.image.resolve(), args.version, args.out.resolve()))
    else:
        print(flathub(args.version, args.zip.resolve(), args.out.resolve()))


if __name__ == "__main__":
    main()
