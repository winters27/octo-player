"""Prepares a player release for the public Octo repository: names the
built files, writes the release notes from the commits, and writes the
signed manifest (update.json) and SHA256SUMS the apps and people check.

Used by .github/workflows/desktop-installers.yml and android-release.yml;
it runs locally too (Python 3.11 or newer, nothing to install).

  release_assets.py stage-desktop --version 1.2.0 --artifacts DIR --out DIR [--require-platform macos-arm64 ...]
  release_assets.py stage-android --version 1.2.0 --apk FILE --out DIR
  release_assets.py stage-ios --version 1.11.0 --ipa FILE --out DIR
  release_assets.py verify-carrier --tag ios-v1.11.0 --ipa FILE
  release_assets.py verify-release --tag ios-v1.11.0 --dir DIR --swift-keys FILE --sha256 HEX
  release_assets.py notes --app desktop --tag desktop-v1.2.0 --out FILE
  release_assets.py manifest --app desktop --tag desktop-v1.2.0 --notes FILE --dir DIR
  release_assets.py public-keys --keys FILE --out DIR
  release_assets.py retry --what "the DEB" [--tries 2] [--wait 20] -- COMMAND...
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
import plistlib
import re
import shutil
import subprocess
import sys
import tempfile
import time
import zipfile
from pathlib import Path

APPS = {
    "desktop": {
        "prefix": "desktop-v",
        "title": "Octo for Windows, macOS and Linux",
        # The parts of the repository the desktop app is built from.
        "paths": ["desktop", "desktop-design", "design", "shared", "subsonic", "audio-engine"],
    },
    "android": {
        "prefix": "android-v",
        "title": "Octo for Android",
        "paths": ["app", "design", "shared", "subsonic"],
    },
    "ios": {
        "prefix": "ios-v",
        "title": "Octo for iPhone",
        "paths": ["ios"],
    },
}
VERSION = re.compile(r"(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(-[0-9A-Za-z-]+(\.[0-9A-Za-z-]+)*)?")
# Commits about the build and its tools are not news for listeners.
QUIET = re.compile(r"^(ci|tools|build|scripts|test|tests|docs)(\([^)]*\))?:", re.IGNORECASE)
# "desktop: ", "player and nav: ", "audio-engine: ": the part of the app a
# commit touched, which release notes leave out.
AREA = re.compile(r"^[a-z][a-z0-9 /-]{0,30}: ")
# Files the manifest describes, by name: Octo-<version>-<system>-<chip>[-portable].<type>
DESKTOP_NAME = re.compile(r"^Octo-(?P<version>.+)-(?P<os>windows|macos|linux)-(?P<arch>x64|arm64)(?P<portable>-portable)?\.(?P<kind>msi|dmg|deb|rpm|flatpak|AppImage|pkg\.tar\.zst|zip)$")
# The files each system's build holds, as their names end. The manifest
# gives each kind in lowercase (appimage), as the apps look for it.
DESKTOP_KINDS = {"windows": ["msi", "zip"], "macos": ["dmg"], "linux": ["deb", "rpm", "flatpak", "AppImage", "pkg.tar.zst", "zip"]}
ANDROID_NAME = re.compile(r"^Octo-(?P<version>.+)-android\.apk$")
IOS_NAME = re.compile(r"^Octo-(?P<version>.+)-iPhone\.ipa$")
# Lines an iPhone release tag carries for the release workflow, never for people to read.
TAG_LINES = re.compile(r"^ipa-(?:sha256|commit): .*$\n?", re.M)
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
# Every file its system makes must be there, once; `required` names the
# systems (macos-arm64) a release can't go out without.
def stage_desktop(version: str, artifacts: Path, out: Path, required: list[str] = ()) -> list[Path]:
    check_version(version)
    out.mkdir(parents=True, exist_ok=True)
    systems = {"windows": "windows", "macos": "macos", "linux": "linux"}
    chips = {"x64": "x64", "arm64": "arm64"}
    staged = []
    platforms = set()
    folders = sorted(p for p in artifacts.iterdir() if p.is_dir() and p.name.startswith("octo-desktop-"))
    if not folders:
        fail(f"no octo-desktop-* folders in {artifacts}")
    for folder in folders:
        _, _, os_name, chip = folder.name.lower().split("-", 3)
        system, arch = systems.get(os_name), chips.get(chip)
        if system is None or arch is None:
            fail(f"unexpected artifact folder {folder.name}")
        platform = f"{system}-{arch}"
        if platform in platforms:
            fail(f"more than one artifact folder for {platform}")
        platforms.add(platform)
        for kind in DESKTOP_KINDS[system]:
            # By the name's ending, in its own case: Windows would match
            # .appimage with *.AppImage, Linux would not.
            found = [p for p in folder.rglob("*") if p.is_file() and p.name.endswith(f".{kind}")]
            if len(found) != 1:
                fail(f"{folder.name} should hold one .{kind}, found {len(found)}")
            suffix = "-portable" if kind == "zip" else ""
            target = out / f"Octo-{version}-{system}-{arch}{suffix}.{kind}"
            shutil.copyfile(found[0], target)
            staged.append(target)
    missing = sorted(set(required) - platforms)
    if missing:
        fail(f"the build for {', '.join(missing)} is missing, so this release would leave it out")
    return staged


def stage_android(version: str, apk: Path, out: Path) -> Path:
    check_version(version)
    out.mkdir(parents=True, exist_ok=True)
    target = out / f"Octo-{version}-android.apk"
    shutil.copyfile(apk, target)
    return target


def stage_ios(version: str, ipa: Path, out: Path) -> Path:
    check_version(version)
    out.mkdir(parents=True, exist_ok=True)
    target = out / f"Octo-{version}-iPhone.ipa"
    shutil.copyfile(ipa, target)
    return target


def describe(name: str, app: str, version: str) -> dict:
    if app == "desktop":
        match = DESKTOP_NAME.match(name)
        if not match or match["version"] != version:
            fail(f"{name} is not named like Octo-{version}-<system>-<chip>.<type>")
        if match["kind"] not in DESKTOP_KINDS[match["os"]] or bool(match["portable"]) != (match["kind"] == "zip"):
            fail(f"{name} is not a file Octo makes for {match['os']}")
        return {"os": match["os"], "arch": match["arch"], "kind": match["kind"].lower()}
    if app == "ios":
        match = IOS_NAME.match(name)
        if not match or match["version"] != version:
            fail(f"{name} is not named like Octo-{version}-iPhone.ipa")
        return {"os": "ios", "arch": "arm64", "kind": "ipa"}
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
        message = re.sub(r"-----BEGIN [A-Z ]*SIGNATURE-----.*", "", message, flags=re.S)
        message = TAG_LINES.sub("", message).strip()
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


# Runs one package's build, and once more if it fails: a mirror or download
# that drops out now and then must not stop a release. A build that fails
# every try still stops it, so the update list never names a file the
# release lacks. Gives the last try's exit code.
def retry(command: list[str], what: str, tries: int = 2, wait: float = 20, run=subprocess.call, sleep=time.sleep) -> int:
    if tries < 1:
        fail("--tries must be 1 or more")
    code = 1
    for attempt in range(1, tries + 1):
        code = run(command)
        if code == 0:
            if attempt > 1:
                print(f"{what} built on try {attempt}.", flush=True)
            return 0
        if attempt < tries:
            print(f"::warning::{what} failed (exit {code}), so it is built once more.", flush=True)
            sleep(wait)
    print(f"::error::{what} failed {tries} times, so the release stops here.", flush=True)
    return code


def tag_lines(message: str) -> dict:
    """The ipa-sha256: and ipa-commit: values an iPhone release tag carries."""
    return {key: value.strip() for key, value in re.findall(r"^ipa-(sha256|commit): (.+)$", message, re.M)}


def ipa_version(ipa: Path) -> str:
    """The version the app in an IPA reads, from Payload/<app>.app/Info.plist."""
    try:
        with zipfile.ZipFile(ipa) as archive:
            names = [n for n in archive.namelist() if re.fullmatch(r"Payload/[^/]+\.app/Info\.plist", n)]
            info = plistlib.loads(archive.read(names[0])) if len(names) == 1 else None
    except Exception:  # a damaged zip, plist or deflate stream: all mean it is no IPA we made
        info = None
    if not isinstance(info, dict):
        fail(f"{ipa.name} is not an IPA with one app")
    return str(info.get("CFBundleShortVersionString", ""))


# The carrier IPA is the one the tag names, built from the commit the tag is on, and reads the
# tag's version, so the updater never offers a release whose app calls itself older.
def verify_carrier(tag: str, ipa: Path) -> None:
    version = version_of(tag, "ios")
    lines = tag_lines(git("tag", "-l", "--format=%(contents)", tag))
    if set(lines) != {"sha256", "commit"}:
        fail(f"{tag} lacks its ipa-sha256: and ipa-commit: lines (an annotated tag carries them)")
    if sha256(ipa) != lines["sha256"]:
        fail(f"the carrier's IPA is not the one {tag} names")
    commit = git("rev-parse", f"{tag}^{{commit}}").strip()
    if commit != lines["commit"]:
        fail(f"{tag} is on {commit}, but its IPA was built from {lines['commit']}")
    if ipa_version(ipa) != version:
        fail(f"the carrier's app reads version {ipa_version(ipa)}, but the tag is {tag}")


def swift_keys(path: Path) -> str:
    """The keys the iPhone app trusts, read from its own copy (trustedKeysText in PlayerUpdates.swift)."""
    match = re.search(r'trustedKeysText = """\n(.*?)\n\s*"""', path.read_text(encoding="utf-8"), re.S)
    if not match:
        fail(f"no trustedKeysText in {path}")
    return "\n".join(line.strip() for line in match.group(1).splitlines()) + "\n"


def key_lines(text: str) -> list[str]:
    return [line.split("#", 1)[0].strip() for line in text.splitlines() if line.split("#", 1)[0].strip()]


# A published iPhone release the app will take: signed by a key the app trusts, for ios at
# this tag, and listing the IPA that was uploaded to TestFlight.
def verify_release(tag: str, folder: Path, keys_swift: Path, want_sha256: str) -> None:
    version = version_of(tag, "ios")
    manifest_bytes = (folder / MANIFEST).read_bytes()
    signature = base64.b64decode((folder / SIGNATURE).read_text().strip())
    with tempfile.TemporaryDirectory() as temp:
        keys = Path(temp) / "keys.txt"
        keys.write_text(swift_keys(keys_swift), encoding="utf-8")
        sig = Path(temp) / "sig"
        sig.write_bytes(signature)
        body = Path(temp) / "update.json"
        body.write_bytes(manifest_bytes)
        if not any(subprocess.run(["openssl", "pkeyutl", "-verify", "-pubin", "-inkey", str(pem), "-rawin",
                                   "-in", str(body), "-sigfile", str(sig)], capture_output=True).returncode == 0
                   for pem in public_keys(keys, Path(temp) / "pem")):
            fail(f"{tag}'s update.json is not signed by a key the iPhone app trusts")
    manifest = json.loads(manifest_bytes)
    if (manifest.get("product"), manifest.get("tag"), manifest.get("version")) != ("ios", tag, version):
        fail(f"{tag}'s manifest is for {manifest.get('product')} {manifest.get('tag')}")
    ipas = [a for a in manifest.get("assets", []) if a.get("os") == "ios" and a.get("kind") == "ipa"]
    if len(ipas) != 1 or ipas[0]["sha256"] != want_sha256:
        fail(f"{tag} does not list the uploaded build's IPA")
    path = folder / ipas[0]["name"]
    if not path.exists() or path.stat().st_size != ipas[0]["size"] or sha256(path) != want_sha256:
        fail(f"{tag}'s {ipas[0]['name']} is not the uploaded build's IPA")
    print(f"{tag} verified: signed for the iPhone app, IPA {want_sha256[:12]}")


# Whether a step stops the script (fail), keeping its message quiet.
def refuses(step) -> bool:
    with contextlib.redirect_stderr(io.StringIO()):
        try:
            step()
        except SystemExit:
            return True
    return False


# A build that passes runs once; one that fails once runs again and
# passes; one that fails every try is tried twice and stops the release.
def retry_self_test(root: Path) -> None:
    def runs(codes: list[int]):
        calls = []
        def run(command):
            calls.append(command)
            return codes[len(calls) - 1]
        return calls, run
    quiet = io.StringIO()
    with contextlib.redirect_stdout(quiet):
        calls, run = runs([0])
        assert retry(["build"], "the DEB", run=run, sleep=lambda _: None) == 0 and len(calls) == 1
        calls, run = runs([1, 0])
        assert retry(["build"], "the DEB", run=run, sleep=lambda _: None) == 0 and len(calls) == 2
        calls, run = runs([3, 4])
        assert retry(["build"], "the DEB", run=run, sleep=lambda _: None) == 4 and len(calls) == 2
        calls, run = runs([1])
        assert retry(["build"], "the DMG", tries=1, run=run, sleep=lambda _: None) == 1 and len(calls) == 1
    said = quiet.getvalue()
    assert "::warning::the DEB failed (exit 3), so it is built once more." in said
    assert "::error::the DEB failed 2 times, so the release stops here." in said
    assert refuses(lambda: retry(["build"], "x", tries=0))
    # The command line, with a real program that fails its first run only.
    count = root / "runs.txt"
    flaky = f"import pathlib,sys; p=pathlib.Path({str(count)!r}); n=int(p.read_text()) if p.exists() else 0; p.write_text(str(n+1)); sys.exit(0 if n else 5)"
    script = str(Path(__file__).resolve())
    command = [sys.executable, script, "retry", "--what", "the AppImage", "--wait", "0", "--", sys.executable, "-c", flaky]
    done = subprocess.run(command, capture_output=True, text=True)
    assert done.returncode == 0 and count.read_text() == "2", (done.returncode, done.stdout, done.stderr)
    count.unlink()
    command = [sys.executable, script, "retry", "--what", "the AppImage", "--tries", "1", "--", sys.executable, "-c", flaky]
    assert subprocess.run(command, capture_output=True).returncode == 5 and count.read_text() == "1"


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
            "octo-desktop-macOS-X64": ["dmg/Octo-1.0.0.dmg"],
            "octo-desktop-Linux-X64": ["deb/octo_1.0.0-1_amd64.deb", "rpm/octo-1.0.0-1.x86_64.rpm", "flatpak/Octo-1.0.0-linux-x64.flatpak",
                                       "appimage/Octo-1.0.0-linux-x64.AppImage", "pacman/Octo-1.0.0-linux-x64.pkg.tar.zst",
                                       "zip/Octo-1.0.0-linux-x86-64.zip"],
        }.items():
            for name in names:
                path = artifacts / folder / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(name.encode())
        out = root / "out"
        every = ["windows-x64", "macos-arm64", "macos-x64", "linux-x64"]
        staged = stage_desktop("1.2.0", artifacts, out, every)
        assert sorted(p.name for p in staged) == sorted([
            "Octo-1.2.0-windows-x64.msi", "Octo-1.2.0-windows-x64-portable.zip", "Octo-1.2.0-macos-arm64.dmg", "Octo-1.2.0-macos-x64.dmg",
            "Octo-1.2.0-linux-x64.deb", "Octo-1.2.0-linux-x64.rpm", "Octo-1.2.0-linux-x64.flatpak",
            "Octo-1.2.0-linux-x64.AppImage", "Octo-1.2.0-linux-x64.pkg.tar.zst", "Octo-1.2.0-linux-x64-portable.zip",
        ]), staged
        manifest = json.loads(write_manifest("desktop", "desktop-v1.2.0", "- Hello.", out).read_bytes())
        assert manifest["version"] == "1.2.0" and manifest["product"] == "desktop" and len(manifest["assets"]) == 10
        flatpak = next(a for a in manifest["assets"] if a["kind"] == "flatpak")
        assert (flatpak["os"], flatpak["arch"], flatpak["name"]) == ("linux", "x64", "Octo-1.2.0-linux-x64.flatpak")
        appimage = next(a for a in manifest["assets"] if a["kind"] == "appimage")
        assert (appimage["os"], appimage["arch"], appimage["name"]) == ("linux", "x64", "Octo-1.2.0-linux-x64.AppImage")
        pacman = next(a for a in manifest["assets"] if a["kind"] == "pkg.tar.zst")
        assert (pacman["os"], pacman["arch"], pacman["name"]) == ("linux", "x64", "Octo-1.2.0-linux-x64.pkg.tar.zst")
        dmgs = sorted((a["os"], a["arch"], a["name"]) for a in manifest["assets"] if a["kind"] == "dmg")
        assert dmgs == [("macos", "arm64", "Octo-1.2.0-macos-arm64.dmg"), ("macos", "x64", "Octo-1.2.0-macos-x64.dmg")], dmgs
        msi = next(a for a in manifest["assets"] if a["kind"] == "msi")
        assert msi == {"name": "Octo-1.2.0-windows-x64.msi", "sha256": hashlib.sha256(b"msi/Octo-1.0.0.msi").hexdigest(), "size": 18, "os": "windows", "arch": "x64", "kind": "msi"}
        sums = (out / SUMS).read_text()
        assert sums.count("\n") == 10 and "Octo-1.2.0-macos-x64.dmg" in sums and "Octo-1.2.0-linux-x64.AppImage" in sums
        # A file of the wrong system, or a portable one that isn't the zip, is never described.
        assert refuses(lambda: describe("Octo-1.2.0-windows-x64.AppImage", "desktop", "1.2.0"))
        assert refuses(lambda: describe("Octo-1.2.0-macos-arm64.msi", "desktop", "1.2.0"))
        assert refuses(lambda: describe("Octo-1.2.0-linux-x64-portable.AppImage", "desktop", "1.2.0"))
        assert refuses(lambda: describe("Octo-1.2.0-linux-x64.appimage", "desktop", "1.2.0"))
        # A release missing a system's build, or a Linux build missing one
        # of its files or holding two, stops before anything is named.
        for chip in ["ARM64", "X64"]:
            mac = artifacts / f"octo-desktop-macOS-{chip}"
            shutil.move(mac, root / "mac-aside")
            assert refuses(lambda: stage_desktop("1.2.0", artifacts, root / f"no-mac-{chip}", every)), chip
            assert len(stage_desktop("1.2.0", artifacts, root / f"by-hand-{chip}")) == 9, "a build by hand needs no system"
            shutil.move(root / "mac-aside", mac)
        linux = artifacts / "octo-desktop-Linux-X64"
        image = linux / "appimage/Octo-1.0.0-linux-x64.AppImage"
        image.rename(root / "aside.AppImage")
        assert refuses(lambda: stage_desktop("1.2.0", artifacts, root / "no-appimage"))
        (root / "aside.AppImage").rename(image)
        (linux / "pacman/second.pkg.tar.zst").write_bytes(b"again")
        assert refuses(lambda: stage_desktop("1.2.0", artifacts, root / "two-packages"))
        (linux / "pacman/second.pkg.tar.zst").unlink()
        twice = artifacts / "octo-desktop-linux-x64"
        # Only where names differ by case alone (Linux): Windows sees one folder.
        if not twice.exists():
            twice.mkdir()
            assert refuses(lambda: stage_desktop("1.2.0", artifacts, root / "twice"))
            twice.rmdir()
        retry_self_test(root)
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
    desktop.add_argument("--require-platform", action="append", default=[], metavar="SYSTEM-CHIP",
                         help="stop unless this system's build is there, such as macos-arm64 (repeatable)")
    android = commands.add_parser("stage-android")
    android.add_argument("--version", required=True)
    android.add_argument("--apk", type=Path, required=True)
    android.add_argument("--out", type=Path, required=True)
    ios = commands.add_parser("stage-ios")
    ios.add_argument("--version", required=True)
    ios.add_argument("--ipa", type=Path, required=True)
    ios.add_argument("--out", type=Path, required=True)
    carrier = commands.add_parser("verify-carrier")
    carrier.add_argument("--tag", required=True)
    carrier.add_argument("--ipa", type=Path, required=True)
    published = commands.add_parser("verify-release")
    published.add_argument("--tag", required=True)
    published.add_argument("--dir", type=Path, required=True)
    published.add_argument("--swift-keys", type=Path, required=True)
    published.add_argument("--sha256", required=True)
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
    again = commands.add_parser("retry", help="run a package build, and once more if it fails")
    again.add_argument("--what", required=True, help="the package, for the log")
    again.add_argument("--tries", type=int, default=2)
    again.add_argument("--wait", type=float, default=20, help="seconds between tries")
    again.add_argument("build", nargs=argparse.REMAINDER, metavar="-- COMMAND", help="the build to run")
    commands.add_parser("self-test")
    args = parser.parse_args()
    if args.rehearsal:
        if os.environ.get("GITHUB_ACTIONS") == "true":
            fail("--rehearsal is for one PC, never for a release built on GitHub")
        global REHEARSAL
        REHEARSAL = True

    if args.command == "stage-desktop":
        for path in stage_desktop(args.version, args.artifacts, args.out, args.require_platform):
            print(path.name)
    elif args.command == "retry":
        build = args.build[1:] if args.build[:1] == ["--"] else args.build
        if not build:
            fail("retry needs the build to run after --")
        sys.exit(retry(build, args.what, args.tries, args.wait))
    elif args.command == "stage-android":
        print(stage_android(args.version, args.apk, args.out).name)
    elif args.command == "stage-ios":
        print(stage_ios(args.version, args.ipa, args.out).name)
    elif args.command == "verify-carrier":
        verify_carrier(args.tag, args.ipa)
        print(f"{args.tag}: the carrier's IPA is the one the tag names")
    elif args.command == "verify-release":
        verify_release(args.tag, args.dir, args.swift_keys, args.sha256)
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
