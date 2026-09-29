"""Times the packaged desktop runtime from process start to the first frame,
without starting Octo: StartupProbe (desktop tests) builds the app's state
and draws the signed-out window once, off screen, then ends. It runs on the
app image's own runtime and jars, with a java launcher lent from the JDK
that built it (the trimmed runtime has none), so the numbers show what the
runtime and class loading cost, for example with and without a CDS archive.

    ./gradlew :desktop:createDistributable :desktop:testClasses
    PYTHONUTF8=1 python scripts/startup-probe.py
    PYTHONUTF8=1 python scripts/startup-probe.py --runs 10 "cds=-Xshare:auto" "no cds=-Xshare:off"

Each variant is a name and the JVM options it adds; the default compares
the runtime's CDS archive on and off. Runs alternate between variants and
the median is printed.

--steps runs StartupStepsProbe instead, which times each step the app takes
before and around its window one after another (the settings, the one-Octo
lock, the HTTP client, the password store, the audio engine, the app state,
the first frame), and prints each step's median per variant. --settings
starts it from a copy of a profile's settings file.

    PYTHONUTF8=1 python scripts/startup-probe.py --steps --runs 6 "now=" "trial=-Dsome.option=1"

--probe LibraryProbeKt --steps --settings <a signed-in profile's settings.json>
times signing in to that server and reading and indexing its whole library
(the password comes from the system's store, read only; nothing on the
server changes).
"""
import argparse
import os
import re
import shutil
import statistics
import subprocess
import sys
import tempfile
import zipfile

root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
parser = argparse.ArgumentParser()
parser.add_argument("--app", default=os.path.join(root, "desktop", "build", "compose", "binaries", "main", "app", "Octo"))
parser.add_argument("--jdk", default=os.environ.get("JAVA_HOME", ""), help="the JDK that built the runtime (lends its java launcher)")
parser.add_argument("--runs", type=int, default=8)
parser.add_argument("--steps", action="store_true", help="time each startup step (StartupStepsProbe)")
parser.add_argument("--settings", default="", help="a settings file for --steps to start from")
parser.add_argument("--probe", default="", help="another step probe for --steps, such as LibraryProbeKt")
parser.add_argument("variants", nargs="*", default=["cds=-Xshare:auto", "no cds=-Xshare:off"])
args = parser.parse_args()
probe_class = args.probe or ("StartupStepsProbeKt" if args.steps else "StartupProbeKt")

app = os.path.join(args.app, "app")
cfg = open(os.path.join(app, "Octo.cfg"), encoding="utf-8").read().splitlines()
jars = [line.split("=", 1)[1].replace("$APPDIR", app) for line in cfg if line.startswith("app.classpath=")]
options = [line.split("=", 1)[1].replace("$APPDIR", app) for line in cfg if line.startswith("java-options=")]
exe = "java.exe" if os.name == "nt" else "java"
launcher = os.path.join(args.jdk, "bin", exe) if args.jdk else shutil.which("java")
if not launcher or not os.path.exists(launcher):
    sys.exit("No JDK launcher: pass --jdk <the JDK 17 the runtime was built from>")

work = tempfile.mkdtemp(prefix="octo-probe-")
try:
    # The runtime, with the launcher lent to it.
    runtime = os.path.join(work, "runtime")
    shutil.copytree(os.path.join(args.app, "runtime"), runtime)
    shutil.copy(launcher, os.path.join(runtime, "bin", exe))
    # The probe as a jar (CDS takes only jars on the class path).
    classes = os.path.join(root, "desktop", "build", "classes", "kotlin", "test")
    probe = os.path.join(work, "probe.jar")
    with zipfile.ZipFile(probe, "w") as out:
        folder = os.path.join(classes, "app", "winters", "octo", "desktop", "perf")
        for name in os.listdir(folder):
            if name.startswith(probe_class):
                out.write(os.path.join(folder, name), "app/winters/octo/desktop/perf/" + name)
    path = os.pathsep.join(jars + [probe])

    settings = ["-Docto.probe.settings=" + os.path.abspath(args.settings)] if args.settings else []

    def run(extra):
        done = subprocess.run([os.path.join(runtime, "bin", exe)] + extra + options + settings + ["-cp", path, "app.winters.octo.desktop.perf." + probe_class], capture_output=True, text=True, timeout=300)
        if args.steps:
            steps = {m.group(1).strip(): int(m.group(2)) for m in re.finditer(r"^step (.+?)\s+(\d+) ms", done.stdout, re.M)}
            if not steps:
                sys.exit("The probe failed:\n" + done.stdout[-2000:] + done.stderr[-2000:])
            return steps
        found = re.search(r"app state (\d+) ms, first frame (\d+) ms", done.stdout)
        if not found:
            sys.exit("The probe failed:\n" + done.stdout[-2000:] + done.stderr[-2000:])
        return int(found.group(1)), int(found.group(2))

    variants = [v.split("=", 1) for v in args.variants]
    results = {name: [] for name, _ in variants}
    for _ in range(args.runs):
        for name, extra in variants:
            results[name].append(run([o for o in extra.split(" ") if o]))
    print(f"{len(jars)} jars, JVM options from Octo.cfg: {' '.join(o for o in options if not o.startswith('-D'))}")
    if args.steps:
        names = list(results)
        print(f"{'step (median ms)':<34}" + "".join(f"{n:>14}" for n in names))
        for step in results[names[0]][0]:
            print(f"{step:<34}" + "".join(f"{statistics.median(r[step] for r in results[n]):>14.0f}" for n in names))
        sys.exit(0)
    for name, times in results.items():
        print(f"{name:>12}: first frame {statistics.median(t[1] for t in times):.0f} ms, app state {statistics.median(t[0] for t in times):.0f} ms after the process started (median of {len(times)}; first frames {[t[1] for t in times]})")
finally:
    shutil.rmtree(work, ignore_errors=True)
