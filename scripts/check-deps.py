"""Checks every version in gradle/libs.versions.toml against the newest
stable release on Google's Maven and Maven Central, plus the Gradle wrapper.

Run from the repo root: python scripts/check-deps.py
Exit code 1 when anything is behind.
"""
import json
import re
import sys
import tomllib
import urllib.request

REPOS = ["https://dl.google.com/android/maven2", "https://repo1.maven.org/maven2"]
UNSTABLE = re.compile(r"(?i)alpha|beta|rc|dev|snapshot|eap|preview|-m\d")
PLUGIN_ARTIFACTS = {
    "com.android.application": ("com.android.tools.build", "gradle"),
    "com.android.library": ("com.android.tools.build", "gradle"),
}


def numeric(version: str) -> tuple:
    return tuple(int(n) for n in re.findall(r"\d+", version))


def newest(group: str, name: str) -> str | None:
    for repo in REPOS:
        url = f"{repo}/{group.replace('.', '/')}/{name}/maven-metadata.xml"
        try:
            xml = urllib.request.urlopen(url, timeout=20).read().decode()
        except Exception:
            continue
        stable = [v for v in re.findall(r"<version>([^<]+)</version>", xml) if not UNSTABLE.search(v)]
        if stable:
            return max(stable, key=numeric)
    return None


def main() -> int:
    catalog = tomllib.load(open("gradle/libs.versions.toml", "rb"))
    versions = catalog["versions"]
    checked: dict[str, tuple[str, str]] = {}
    for lib in catalog["libraries"].values():
        ref = (lib.get("version") or {}).get("ref") if isinstance(lib.get("version"), dict) else lib.get("version.ref")
        if ref and ref not in checked:
            checked[ref] = (lib["group"], lib["name"])
    for plugin in catalog["plugins"].values():
        ref = (plugin.get("version") or {}).get("ref") if isinstance(plugin.get("version"), dict) else plugin.get("version.ref")
        if ref and ref not in checked:
            pid = plugin["id"]
            checked[ref] = PLUGIN_ARTIFACTS.get(pid, (pid, f"{pid}.gradle.plugin"))

    behind = 0
    for ref, (group, name) in sorted(checked.items()):
        have, latest = versions[ref], newest(group, name)
        mark = "" if latest is None or numeric(latest) <= numeric(have) else f"  <-- newer: {latest}"
        behind += bool(mark)
        print(f"{ref:16} {have:12} {group}:{name}{mark}")

    wrapper = re.search(r"gradle-([\d.]+)-", open("gradle/wrapper/gradle-wrapper.properties").read()).group(1)
    current = json.load(urllib.request.urlopen("https://services.gradle.org/versions/current", timeout=20))["version"]
    mark = "" if numeric(current) <= numeric(wrapper) else f"  <-- newer: {current}"
    behind += bool(mark)
    print(f"{'gradle':16} {wrapper:12} wrapper{mark}")
    return 1 if behind else 0


if __name__ == "__main__":
    sys.exit(main())
