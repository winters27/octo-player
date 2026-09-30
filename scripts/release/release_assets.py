"""Prepares a player release for the public Octo repository: names the
built files, writes the release notes from the commits, and writes the
signed manifest (update.json) and SHA256SUMS the apps and people check.

Used by .github/workflows/desktop-installers.yml and android-release.yml;
it runs locally too (Python 3.11 or newer, nothing to install).

  release_assets.py stage-desktop --version 1.2.0 --artifacts DIR --out DIR
  release_assets.py stage-android --version 1.2.0 --apk FILE --out DIR
  release_assets.py notes --app desktop --tag desktop-v1.2.0 --out FILE
  release_assets.py manifest --app desktop --tag desktop-v1.2.0 --notes FILE --dir DIR
  release_assets.py public-keys --keys FILE --out DIR
  release_assets.py self-test

A rehearsal version (1.1.0-rehearsal.1) is refused: its build trusts a
throwaway key, so it is never published. --rehearsal, given before the
command, stages only rehearsal versions, for scripts/rehearse-update.ps1
on one PC; it refuses to run on GitHub.

  release_assets.py --rehearsal stage-desktop --version 1.1.0-rehearsal.2 ...
"""
import argparse
import base64
import contextlib
import hashlib
import io
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

APPS = {
    "desktop": {
        "prefix": "desktop-v",
        "title": "Octo for Windows and Linux",
        # The parts of the repository the desktop app is built from.
        "paths": ["desktop", "desktop-design", "design", "shared", "subsonic", "audio-engine"],
    },
    "android": {
        "prefix": "android-v",
        "title": "Octo for Android",
        "paths": ["app", "design", "shared", "subsonic"],
    },
}
VERSION = re.compile(r"(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(-[0-9A-Za-z-]+(\.[0-9A-Za-z-]+)*)?")
# Commits about the build and its tools are not news for listeners.
QUIET = re.compile(r"^(ci|tools|build|scripts|test|tests|docs)(\([^)]*\))?:", re.IGNORECASE)
# "desktop: ", "player and nav: ", "audio-engine: ": the part of the app a
# commit touched, which release notes leave out.
AREA = re.compile(r"^[a-z][a-z0-9 /-]{0,30}: ")
# Files the manifest describes, by name: Octo-<version>-<system>-<chip>[-portable].<type>
DESKTOP_NAME = re.compile(r"^Octo-(?P<version>.+)-(?P<os>windows|macos|linux)-(?P<arch>x64|arm64)(?P<portable>-portable)?\.(?P<kind>msi|dmg|deb|rpm|zip)$")
ANDROID_NAME = re.compile(r"^Octo-(?P<version>.+)-android\.apk$")
MANIFEST = "update.json"
SIGNATURE = "update.json.sig"
SUMS = "SHA256SUMS"
# The DER prefix of an Ed25519 public key (SubjectPublicKeyInfo), before its 32 bytes.
ED25519_SPKI = bytes.fromhex("302a300506032b6570032100")
# Set by --rehearsal: only rehearsal versions, never anything publishable.
REHEARSAL = False


def fail(message: str) -> None:
    print(f"release: {message}", file=sys.stderr)
    sys.exit(1)


def check_version(version: str) -> None:
    if not VERSION.fullmatch(version):
        fail(f"{version!r} is not a version like 1.2.0 or 1.3.0-beta.1")
    rehearsal = "rehearsal" in version.lower()
    if rehearsal and not REHEARSAL:
        fail(f"{version} is a rehearsal version: its build trusts a throwaway key, so it is never published")
    if REHEARSAL and not rehearsal:
        fail(f"--rehearsal only stages rehearsal versions (1.1.0-rehearsal.1), not {version}")


def version_of(tag: str, app: str) -> str:
    prefix = APPS[app]["prefix"]
    if not tag.startswith(prefix):
        fail(f"{tag} is not a {app} tag ({prefix}<version>)")
    version = tag[len(prefix):]
    check_version(version)
    return version


def git(*args: str) -> str:
    return subprocess.run(["git", *args], check=True, capture_output=True, text=True, encoding="utf-8").stdout


# The built files, under the names the release shows. Each artifact folder
# is named octo-desktop-<runner os>-<runner arch>, as the workflow uploads them.
def stage_desktop(version: str, artifacts: Path, out: Path) -> list[Path]:
    check_version(version)
    out.mkdir(parents=True, exist_ok=True)
    systems = {"windows": "windows", "macos": "macos", "linux": "linux"}
    chips = {"x64": "x64", "arm64": "arm64"}
    wanted = {"windows": ["msi", "zip"], "macos": ["dmg"], "linux": ["deb", "rpm", "zip"]}
    staged = []
    folders = sorted(p for p in artifacts.iterdir() if p.is_dir() and p.name.startswith("octo-desktop-"))
    if not folders:
        fail(f"no octo-desktop-* folders in {artifacts}")
    for folder in folders:
        _, _, os_name, chip = folder.name.lower().split("-", 3)
        system, arch = systems.get(os_name), chips.get(chip)
        if system is None or arch is None:
            fail(f"unexpected artifact folder {folder.name}")
        for kind in wanted[system]:
            found = [p for p in folder.rglob(f"*.{kind}") if p.is_file()]
            if len(found) != 1:
                fail(f"{folder.name} should hold one .{kind}, found {len(found)}")
            suffix = "-portable" if kind == "zip" else ""
            target = out / f"Octo-{version}-{system}-{arch}{suffix}.{kind}"
            shutil.copyfile(found[0], target)
            staged.append(target)
    return staged


def stage_android(version: str, apk: Path, out: Path) -> Path:
    check_version(version)
    out.mkdir(parents=True, exist_ok=True)
    target = out / f"Octo-{version}-android.apk"
    shutil.copyfile(apk, target)
    return target


def describe(name: str, app: str, version: str) -> dict:
    if app == "desktop":
        match = DESKTOP_NAME.match(name)
        if not match or match["version"] != version:
            fail(f"{name} is not named like Octo-{version}-<system>-<chip>.<type>")
        return {"os": match["os"], "arch": match["arch"], "kind": match["kind"]}
    match = ANDROID_NAME.match(name)
    if not match or match["version"] != version:
        fail(f"{name} is not named like Octo-{version}-android.apk")
    return {"os": "android", "arch": "any", "kind": "apk"}


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


# update.json: the version, the notes, and each file with its SHA-256 and
# size, as compact JSON with sorted keys. The workflow signs these exact
# bytes, and the apps check the signature before reading a word of it.
def write_manifest(app: str, tag: str, notes: str, folder: Path) -> Path:
    version = version_of(tag, app)
    files = sorted(p for p in folder.iterdir() if p.is_file() and p.name not in (MANIFEST, SIGNATURE, SUMS))
    if not files:
        fail(f"nothing to describe in {folder}")
    assets = []
    for path in files:
        assets.append({"name": path.name, "sha256": sha256(path), "size": path.stat().st_size, **describe(path.name, app, version)})
    manifest = {"format": 1, "product": app, "version": version, "tag": tag, "notes": notes, "assets": assets}
    target = folder / MANIFEST
    target.write_bytes(json.dumps(manifest, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode("utf-8"))
    sums = "".join(f"{asset['sha256']}  {asset['name']}\n" for asset in assets)
    (folder / SUMS).write_text(sums, encoding="utf-8", newline="\n")
    return target


def sentence(subject: str) -> str:
    text = AREA.sub("", subject.strip(), count=1).strip()
    if not text:
        return ""
    text = text[0].upper() + text[1:]
    return text if text[-1] in ".!?" else text + "."


# The notes: the tag's own message when it was written with one (an
# annotated tag), or else the commits since this app's last release that
# touched the app, one plain line each.
def release_notes(app: str, tag: str) -> str:
    info = APPS[app]
    kind = git("cat-file", "-t", tag).strip()
    if kind == "tag":
        message = git("tag", "-l", "--format=%(contents)", tag).strip()
        message = re.sub(r"-----BEGIN [A-Z ]*SIGNATURE-----.*", "", message, flags=re.S).strip()
        if message and message != tag:
            return message
    try:
        previous = git("describe", "--tags", "--abbrev=0", "--match", f"{info['prefix']}*", f"{tag}^").strip()
    except subprocess.CalledProcessError:
        previous = ""
    if not previous:
        return f"The first {info['title']} published here."
    subjects = git("log", "--no-merges", "--format=%s", f"{previous}..{tag}", "--", *info["paths"]).splitlines()
    lines = []
    for subject in subjects:
        if QUIET.match(subject):
            continue
        line = sentence(subject)
        if line and line not in lines:
            lines.append(line)
    if not lines:
        return f"Small fixes since {previous.removeprefix(info['prefix'])}."
    return "\n".join(f"- {line}" for line in lines)


# The trusted keys, one base64 line each, as PEM files OpenSSL can check
# a signature with.
def public_keys(keys: Path, out: Path) -> list[Path]:
    out.mkdir(parents=True, exist_ok=True)
    written = []
    for line in keys.read_text(encoding="utf-8").splitlines():
        line = line.split("#", 1)[0].strip()
        if not line:
            continue
        raw = base64.b64decode(line, validate=True)
        if len(raw) != 32:
            fail(f"not an Ed25519 public key: {line}")
        pem = "-----BEGIN PUBLIC KEY-----\n" + base64.b64encode(ED25519_SPKI + raw).decode() + "\n-----END PUBLIC KEY-----\n"
        target = out / f"key-{len(written) + 1}.pem"
        target.write_text(pem, encoding="ascii", newline="\n")
        written.append(target)
    if not written:
        fail(f"{keys} has no public key: add the release key's public half before publishing")
    return written


# Whether a step stops the script (fail), keeping its message quiet.
def refuses(step) -> bool:
    with contextlib.redirect_stderr(io.StringIO()):
        try:
            step()
        except SystemExit:
            return True
    return False


def self_test() -> None:
    assert sentence("desktop: a sheet of the server's 24 lists") == "A sheet of the server's 24 lists."
    assert sentence("player and nav: glowing buttons when on") == "Glowing buttons when on."
    assert sentence("Remove the NOTICE file") == "Remove the NOTICE file."
    assert sentence("core: done!") == "Done!"
    assert QUIET.match("ci: publish releases") and QUIET.match("tools: x") and not QUIET.match("desktop: x")
    for good in ["1.2.0", "1.3.0-beta.1", "10.0.0"]:
        assert VERSION.fullmatch(good), good
    for bad in ["1.2", "01.2.0", "1.2.0-", "v1.2.0", "2026.09.23.1"]:
        assert not VERSION.fullmatch(bad), bad
    global REHEARSAL
    for rehearsal, version, refused in [
        (False, "1.1.0-rehearsal.1", True),
        (False, "1.1.0-Rehearsal.1", True),
        (False, "1.1.0-beta.rehearsal", True),
        (False, "1.1.0-beta.1", False),
        (True, "1.1.0-rehearsal.1", False),
        (True, "1.1.0", True),
    ]:
        REHEARSAL = rehearsal
        assert refuses(lambda: check_version(version)) == refused, (version, rehearsal)
    REHEARSAL = False
    with tempfile.TemporaryDirectory() as temp:
        root = Path(temp)
        artifacts = root / "artifacts"
        for folder, names in {
            "octo-desktop-Windows-X64": ["msi/Octo-1.0.0.msi", "zip/Octo-1.0.0-win32-x86-64.zip"],
            "octo-desktop-macOS-ARM64": ["dmg/Octo-1.0.0.dmg"],
            "octo-desktop-Linux-X64": ["deb/octo_1.0.0-1_amd64.deb", "rpm/octo-1.0.0-1.x86_64.rpm", "zip/Octo-1.0.0-linux-x86-64.zip"],
        }.items():
            for name in names:
                path = artifacts / folder / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(name.encode())
        out = root / "out"
        staged = stage_desktop("1.2.0", artifacts, out)
        assert sorted(p.name for p in staged) == sorted([
            "Octo-1.2.0-windows-x64.msi", "Octo-1.2.0-windows-x64-portable.zip", "Octo-1.2.0-macos-arm64.dmg",
            "Octo-1.2.0-linux-x64.deb", "Octo-1.2.0-linux-x64.rpm", "Octo-1.2.0-linux-x64-portable.zip",
        ]), staged
        manifest = json.loads(write_manifest("desktop", "desktop-v1.2.0", "- Hello.", out).read_bytes())
        assert manifest["version"] == "1.2.0" and manifest["product"] == "desktop" and len(manifest["assets"]) == 6
        msi = next(a for a in manifest["assets"] if a["kind"] == "msi")
        assert msi == {"name": "Octo-1.2.0-windows-x64.msi", "sha256": hashlib.sha256(b"msi/Octo-1.0.0.msi").hexdigest(), "size": 18, "os": "windows", "arch": "x64", "kind": "msi"}
        assert (out / SUMS).read_text().count("\n") == 6
        # A rehearsal build is never staged or described for a release.
        assert refuses(lambda: stage_desktop("1.1.0-rehearsal.1", artifacts, root / "never"))
        assert refuses(lambda: stage_android("1.1.0-rehearsal.1", root / "app-release.apk", root / "never"))
        assert refuses(lambda: write_manifest("desktop", "desktop-v1.1.0-rehearsal.1", "", out))
        assert refuses(lambda: version_of("desktop-v1.1.0-rehearsal.1", "desktop"))
        assert not (root / "never").exists()
        apk = root / "app-release.apk"
        apk.write_bytes(b"apk")
        phone = root / "phone"
        stage_android("0.3.0", apk, phone)
        assert json.loads(write_manifest("android", "android-v0.3.0", "", phone).read_bytes())["assets"][0]["kind"] == "apk"
        keys = root / "keys.txt"
        keys.write_text("# comment\n" + base64.b64encode(bytes(range(32))).decode() + "  # a key\n")
        pem = public_keys(keys, root / "pem")[0].read_text()
        assert base64.b64decode(pem.splitlines()[1])[-32:] == bytes(range(32))
    print("release_assets self-test passed")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--rehearsal", action="store_true", help="stage a rehearsal version, on this PC only")
    commands = parser.add_subparsers(dest="command", required=True)
    desktop = commands.add_parser("stage-desktop")
    desktop.add_argument("--version", required=True)
    desktop.add_argument("--artifacts", type=Path, required=True)
    desktop.add_argument("--out", type=Path, required=True)
    android = commands.add_parser("stage-android")
    android.add_argument("--version", required=True)
    android.add_argument("--apk", type=Path, required=True)
    android.add_argument("--out", type=Path, required=True)
    notes = commands.add_parser("notes")
    notes.add_argument("--app", choices=APPS, required=True)
    notes.add_argument("--tag", required=True)
    notes.add_argument("--out", type=Path)
    manifest = commands.add_parser("manifest")
    manifest.add_argument("--app", choices=APPS, required=True)
    manifest.add_argument("--tag", required=True)
    manifest.add_argument("--notes", type=Path, required=True)
    manifest.add_argument("--dir", type=Path, required=True)
    keys = commands.add_parser("public-keys")
    keys.add_argument("--keys", type=Path, required=True)
    keys.add_argument("--out", type=Path, required=True)
    commands.add_parser("self-test")
    args = parser.parse_args()
    if args.rehearsal:
        if os.environ.get("GITHUB_ACTIONS") == "true":
            fail("--rehearsal is for one PC, never for a release built on GitHub")
        global REHEARSAL
        REHEARSAL = True

    if args.command == "stage-desktop":
        for path in stage_desktop(args.version, args.artifacts, args.out):
            print(path.name)
    elif args.command == "stage-android":
        print(stage_android(args.version, args.apk, args.out).name)
    elif args.command == "notes":
        version_of(args.tag, args.app)
        text = release_notes(args.app, args.tag)
        if args.out:
            args.out.write_text(text + "\n", encoding="utf-8", newline="\n")
        else:
            print(text)
    elif args.command == "manifest":
        print(write_manifest(args.app, args.tag, args.notes.read_text(encoding="utf-8").strip(), args.dir))
    elif args.command == "public-keys":
        for path in public_keys(args.keys, args.out):
            print(path)
    else:
        self_test()


if __name__ == "__main__":
    main()
