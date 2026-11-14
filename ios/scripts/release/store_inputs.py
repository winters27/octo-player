#!/usr/bin/env python3
"""The files Apple's Mac tools turn into the iPhone app's store assets, and one hash of them.

The "iOS store assets" workflow records these hashes beside what it makes, and
octo_asc.py validate works them out again from the tree it ships, so a build
whose icon or App Intents changed since the assets were made is refused.

  store_inputs.py hash ROOT KIND                       print the hash (KIND: icon or intents)
  store_inputs.py record ROOT KIND OUT XCODE_BUILD     write OUT (inputs.json)
  store_inputs.py collect ROOT APP OUT XCODE_BUILD     copy every Metadata.appintents of APP
                                                       into OUT/<executable>/, refusing a
                                                       missing or unexpected one, and record

ROOT is the repository root (the folder holding ios/ and .github/).
"""
import hashlib
import json
import shutil
import sys
from pathlib import Path

KINDS = ("icon", "intents")
WORKFLOW = ".github/workflows/ios-store-assets.yml"
# Where each bundle that must carry App Intents metadata sits inside the .app, by executable.
EXPECTED_METADATA = {"Octo": ".", "OctoWidgets": "PlugIns/OctoWidgets.appex"}
METADATA = "Metadata.appintents"


def fail(message: str) -> None:
    print(f"store_inputs: {message}", file=sys.stderr)
    sys.exit(1)


def files(root: Path, kind: str) -> list[str]:
    """The inputs of one kind, as sorted paths relative to root."""
    ios = root / "ios"
    if kind == "icon":
        picked = [p for folder in ("AppIcon.icon", "Launch.xcassets")
                  for p in (ios / folder).rglob("*") if p.is_file() and p.name != ".DS_Store"]
        for folder in ("AppIcon.icon", "Launch.xcassets"):
            if not any(p.is_relative_to(ios / folder) for p in picked):
                fail(f"ios/{folder} has no files")
    elif kind == "intents":
        picked = [ios / name for name in ("Package.swift", "project.yml", "xtool.yml")]
        # Every folder of Swift the Xcode project or the package compiles.
        picked += [p for folder in ("Sources", "XcodeSupport") for p in (ios / folder).rglob("*.swift")
                   if b"AppIntents" in p.read_bytes()]
    else:
        fail(f"unknown kind {kind!r}; use icon or intents")
    picked.append(root / WORKFLOW)
    names = sorted({p.relative_to(root).as_posix() for p in picked})
    for name in names:
        if not (root / name).is_file():
            fail(f"{name} is missing")
    return names


def digest(root: Path, names: list[str]) -> str:
    """SHA-256 over each file's path and bytes, each length-prefixed, in sorted path order."""
    h = hashlib.sha256()
    for name in sorted(names):
        path = name.encode("utf-8")
        data = (root / name).read_bytes()
        h.update(len(path).to_bytes(8, "big"))
        h.update(path)
        h.update(len(data).to_bytes(8, "big"))
        h.update(data)
    return h.hexdigest()


def record(root: Path, kind: str, out: Path, xcode_build: str) -> dict:
    names = files(root, kind)
    entry = {"kind": kind, "sha256": digest(root, names), "files": names, "xcode_build": xcode_build}
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(entry, indent=2, sort_keys=True) + "\n", encoding="utf-8", newline="\n")
    return entry


def collect(root: Path, app: Path, out: Path, xcode_build: str) -> dict:
    app = app.resolve()
    found = sorted(p.resolve() for p in app.rglob(METADATA) if p.is_dir())
    expected = {exe: (app / where / METADATA).resolve() for exe, where in EXPECTED_METADATA.items()}
    extra = [p for p in found if p not in expected.values()]
    if extra:
        fail("unexpected App Intents metadata: " + ", ".join(p.relative_to(app).as_posix() for p in extra))
    for exe, path in expected.items():
        if not path.is_dir():
            fail(f"no {METADATA} for {exe} at {path.relative_to(app).as_posix()}")
        target = out / exe / METADATA
        if target.exists():
            shutil.rmtree(target)
        shutil.copytree(path, target)
    return record(root, "intents", out / "inputs.json", xcode_build)


def main() -> None:
    args = sys.argv[1:]
    if len(args) == 3 and args[0] == "hash" and args[2] in KINDS:
        print(digest(Path(args[1]), files(Path(args[1]), args[2])))
    elif len(args) == 5 and args[0] == "record" and args[2] in KINDS:
        print(json.dumps(record(Path(args[1]), args[2], Path(args[3]), args[4]), sort_keys=True))
    elif len(args) == 5 and args[0] == "collect":
        print(json.dumps(collect(Path(args[1]), Path(args[2]), Path(args[3]), args[4]), sort_keys=True))
    else:
        fail(__doc__.strip())


if __name__ == "__main__":
    main()
