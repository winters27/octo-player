"""Checks every dependency in audio-engine/Cargo.toml (and the fixture
maker's) against the newest stable release on crates.io.

Run from anywhere: python audio-engine/scripts/check-deps.py
Exit code 1 when anything is behind.
"""
import json
import pathlib
import sys
import tomllib
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent.parent
MANIFESTS = [ROOT / "Cargo.toml", ROOT / "tools" / "make-fixtures" / "Cargo.toml"]
SECTIONS = ["dependencies", "dev-dependencies", "build-dependencies"]


def newest(name: str) -> str | None:
    request = urllib.request.Request(
        f"https://crates.io/api/v1/crates/{name}",
        headers={"User-Agent": "octo-audio-check-deps"},
    )
    with urllib.request.urlopen(request, timeout=20) as response:
        crate = json.load(response)["crate"]
    return crate.get("max_stable_version") or crate.get("newest_version")


def numeric(version: str) -> tuple:
    return tuple(int(part) for part in version.split("-")[0].split("."))


def main() -> int:
    behind = 0
    for manifest in MANIFESTS:
        data = tomllib.loads(manifest.read_text(encoding="utf-8"))
        print(f"{manifest.relative_to(ROOT.parent)}")
        for section in SECTIONS:
            for name, spec in data.get(section, {}).items():
                wanted = spec if isinstance(spec, str) else spec.get("version")
                if wanted is None:
                    continue
                latest = newest(name)
                ok = latest is not None and numeric(wanted.lstrip("=^~")) >= numeric(latest)
                behind += 0 if ok else 1
                mark = "ok" if ok else "BEHIND"
                print(f"  {mark:6} {name:24} {wanted:10} newest {latest}")
    return 1 if behind else 0


if __name__ == "__main__":
    sys.exit(main())
