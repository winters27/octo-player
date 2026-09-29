"""Writes desktop/src/main/resources/startup-classes.txt, the classes a start
of Octo loads in the order it loads them, which the app reads ahead on a
thread of its own while it starts (system/ClassPreload.kt). Made from the
class loading log of a real start of the packaged app, signed in:

    powershell -File scripts/measure-desktop.ps1 -WithLibrary -DryRun -ProfileDir <dir>
    set JAVA_TOOL_OPTIONS=-Xlog:class+load=info:file=<log>:uptime
    set OCTO_PROFILE_DIR=<dir>   (and APPDATA and LOCALAPPDATA inside it)
    start the app image's Octo.exe, wait for Home, close it
    PYTHONUTF8=1 python scripts/startup-classes.py <log>

Classes the runtime's CDS archive already holds, and generated ones
(lambdas, method handles, proxies), are left out: they cannot be looked up
by name, or are loaded already. A class the list names that a later build
no longer has is simply skipped at run time, but make the list again after
a dependency changes; ClassPreloadTest says when too many are missing.
"""
import argparse
import os
import re

root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
parser = argparse.ArgumentParser()
parser.add_argument("log", help="a -Xlog:class+load=info:file=<log>:uptime log of one start")
parser.add_argument("--until", type=float, default=10.0, help="seconds into the start to stop at")
parser.add_argument("--out", default=os.path.join(root, "desktop", "src", "main", "resources", "startup-classes.txt"))
args = parser.parse_args()

line = re.compile(r"^\[(\d+\.\d+)s\].*?\s(\S+) source: (.*)$")
generated = re.compile(r"\$\$Lambda|LambdaForm\$|/0x|\$Proxy|^jdk\.internal\.reflect\.Generated|^com\.sun\.proxy\.")
seen = set()
names = []
for text in open(args.log, encoding="utf-8", errors="replace"):
    found = line.match(text.strip())
    if not found:
        continue
    seconds, name, source = float(found.group(1)), found.group(2), found.group(3)
    if seconds > args.until:
        break
    if source.startswith("shared objects file") or generated.search(name) or name in seen:
        continue
    seen.add(name)
    names.append(name)

with open(args.out, "w", encoding="utf-8", newline="\n") as out:
    out.write("\n".join(names) + "\n")
print(f"{len(names)} classes, the first {args.until:g} s of the start, written to {args.out}")
