"""Octo's icons: Phosphor Icons, turned into the Android vector drawables
both apps draw (design/src/main/res/drawable/sym_*.xml).

  python make_icons.py                 writes every icon icons.json names
  python make_icons.py --weight bold --out DIR
                                       every icon in one weight, for comparing
  python make_icons.py measure DIR     each icon's box and ink, in 24ths of
                                       its square, for checking optical size

icons.json holds the Phosphor release (fetched from the npm registry and
checked against its published sha512), the scale every icon is drawn at,
and each file's icon, weight and, where it needs one, its own scale (the
filled transport glyphs fill more of the square than the line icons do). Phosphor draws on a 256 square with its
strokes already outlined, so its paths are filled as they are; this only
rewrites them in absolute coordinates, scaled about the middle, with every
number and arc flag spelled out so any vector parser reads them. The
licence is copied to design/licenses/Phosphor-MIT.txt.

Standard library only. Run with PYTHONUTF8=1.
"""
import argparse
import base64
import hashlib
import io
import json
import math
import re
import sys
import tarfile
import tempfile
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
DRAWABLES = ROOT / "design/src/main/res/drawable"
LICENCE = ROOT / "design/licenses/Phosphor-MIT.txt"
GRID = 256.0


# The release, unpacked once into the temp folder and reused after.
def phosphor(spec: dict) -> Path:
    where = Path(tempfile.gettempdir()) / f"octo-phosphor-{spec['version']}"
    if (where / "package/assets/regular").is_dir():
        return where / "package"
    data = urllib.request.urlopen(spec["tarball"], timeout=60).read()
    algo, want = spec["integrity"].split("-", 1)
    got = base64.b64encode(hashlib.new(algo, data).digest()).decode()
    if got != want:
        sys.exit(f"the download does not match its {algo}: {got}")
    with tarfile.open(fileobj=io.BytesIO(data), mode="r:gz") as tar:
        tar.extractall(where, filter="data")
    return where / "package"


def svg_file(package: Path, icon: str, weight: str) -> Path:
    name = icon if weight == "regular" else f"{icon}-{weight}"
    path = package / "assets" / weight / f"{name}.svg"
    if not path.is_file():
        sys.exit(f"Phosphor has no {icon} in {weight}")
    return path


# The path data of a Phosphor file. Anything but plain filled paths is
# refused, since it would be lost.
def svg_paths(path: Path) -> list[str]:
    text = path.read_text()
    if re.search(r"<(circle|rect|line|polyline|polygon|ellipse|g)\b", text) or "opacity" in text or "stroke" in text:
        sys.exit(f"{path.name} has more than filled paths")
    if 'viewBox="0 0 256 256"' not in text:
        sys.exit(f"{path.name} is not on the 256 grid")
    return re.findall(r'<path[^>]*\sd="([^"]+)"', text)


NUMBER = re.compile(r"[-+]?(?:\d*\.\d+|\d+\.?)(?:[eE][-+]?\d+)?")
COUNTS = {"M": 2, "L": 2, "H": 1, "V": 1, "C": 6, "S": 4, "Q": 4, "T": 2, "A": 7, "Z": 0}


# Splits path data into (command, numbers) with every command absolute.
# Arc flags are read one digit each, as SVG allows them packed together.
def absolute(data: str) -> list[tuple[str, list[float]]]:
    out = []
    i, n = 0, len(data)
    x = y = sx = sy = 0.0
    command = None

    def skip(i):
        while i < n and data[i] in " ,\t\r\n":
            i += 1
        return i

    def number(i):
        i = skip(i)
        m = NUMBER.match(data, i)
        if not m:
            raise ValueError(f"no number at {i}: {data[i:i + 20]!r}")
        return float(m.group()), m.end()

    def flag(i):
        i = skip(i)
        if data[i] not in "01":
            raise ValueError(f"no arc flag at {i}")
        return float(data[i]), i + 1

    while True:
        i = skip(i)
        if i >= n:
            break
        if data[i].isalpha():
            command = data[i]
            i += 1
        elif command is None:
            raise ValueError("path data starts without a command")
        elif command in "Mm":
            command = "L" if command == "M" else "l"
        upper = command.upper()
        relative = command != upper
        if upper == "Z":
            out.append(("Z", []))
            x, y = sx, sy
            continue
        values = []
        for k in range(COUNTS[upper]):
            if upper == "A" and k in (3, 4):
                v, i = flag(i)
            else:
                v, i = number(i)
            values.append(v)
        if relative:
            if upper == "H":
                values[0] += x
            elif upper == "V":
                values[0] += y
            elif upper == "A":
                values[5] += x
                values[6] += y
            else:
                for k in range(0, len(values), 2):
                    values[k] += x
                    values[k + 1] += y
        if upper == "H":
            x = values[0]
        elif upper == "V":
            y = values[0]
        else:
            x, y = values[-2], values[-1]
        if upper == "M":
            sx, sy = x, y
        out.append((upper, values))
    return out


def fmt(v: float) -> str:
    text = f"{v:.2f}".rstrip("0").rstrip(".")
    return "0" if text in ("-0", "") else text


# The path, absolute, scaled by `scale` about the middle of the square.
def rewrite(data: str, scale: float) -> str:
    c = GRID / 2

    def t(v):
        return (v - c) * scale + c

    parts = []
    for command, values in absolute(data):
        if command == "A":
            rx, ry, rot, large, sweep, x, y = values
            numbers = [fmt(rx * scale), fmt(ry * scale), fmt(rot), str(int(large)), str(int(sweep)), fmt(t(x)), fmt(t(y))]
        else:
            numbers = [fmt(t(v)) for v in values]
        parts.append(command + ",".join(numbers))
    return "".join(parts)


def vector_xml(paths: list[str], note: str, size: float) -> str:
    body = "".join(
        f'  <path\n      android:fillColor="@android:color/white"\n      android:pathData="{p}"/>\n' for p in paths
    )
    return (
        f"<!-- {note} -->\n"
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        f'    android:width="{fmt(size)}dp"\n'
        f'    android:height="{fmt(size)}dp"\n'
        f'    android:viewportWidth="{fmt(GRID)}"\n'
        f'    android:viewportHeight="{fmt(GRID)}">\n'
        f"{body}"
        "</vector>\n"
    )


def make(args, spec: dict) -> None:
    package = phosphor(spec["phosphor"])
    out = Path(args.out) if args.out else DRAWABLES
    out.mkdir(parents=True, exist_ok=True)
    scale = spec["scale"]
    version = spec["phosphor"]["version"]
    for file, entry in spec["icons"].items():
        weight = args.weight or entry["weight"]
        icon = entry["icon"]
        name = icon if weight == "regular" else f"{icon}-{weight}"
        if args.weight and not (package / "assets" / weight / f"{name}.svg").is_file():
            continue
        drawn = scale * entry.get("scale", 1.0)
        paths = [rewrite(p, drawn) for p in svg_paths(svg_file(package, icon, weight))]
        at = f" at {fmt(drawn)}" if drawn != 1 else ""
        note = f"Phosphor Icons {version}, {icon} ({weight}{at}), MIT. Made by tools/icons/make_icons.py."
        (out / f"{file}.xml").write_text(vector_xml(paths, note, spec["size"]), newline="\n")
    if not args.out:
        LICENCE.write_text((package / "LICENSE").read_text(), newline="\n")
        stray = sorted(p.stem for p in out.glob("sym_*.xml") if p.stem not in spec["icons"])
        if stray:
            sys.exit("icon files icons.json does not name: " + ", ".join(stray))
    print(f"wrote {len(spec['icons'])} icons from Phosphor {version} at scale {scale}")


# --- Measuring -------------------------------------------------------------

# Points along a path's outlines, one list per closed shape, for drawing
# and measuring. Curves and arcs are cut into short straight steps.
def outlines(commands: list[tuple[str, list[float]]], steps: int = 24) -> list[list[tuple[float, float]]]:
    shapes, shape = [], []
    x = y = 0.0
    last_control = None
    for command, v in commands:
        if command == "M":
            if len(shape) > 1:
                shapes.append(shape)
            x, y = v
            shape = [(x, y)]
            last_control = None
            continue
        if command == "Z":
            if len(shape) > 1:
                shapes.append(shape)
            shape = [shape[0]] if shape else []
            if shape:
                x, y = shape[0]
            last_control = None
            continue
        if command == "L":
            x, y = v
            shape.append((x, y))
            last_control = None
        elif command == "H":
            x = v[0]
            shape.append((x, y))
            last_control = None
        elif command == "V":
            y = v[0]
            shape.append((x, y))
            last_control = None
        elif command in "CS":
            if command == "C":
                c1 = (v[0], v[1])
                c2, end = (v[2], v[3]), (v[4], v[5])
            else:
                c1 = (2 * x - last_control[0], 2 * y - last_control[1]) if last_control else (x, y)
                c2, end = (v[0], v[1]), (v[2], v[3])
            for k in range(1, steps + 1):
                s = k / steps
                a, b, c, d = (1 - s) ** 3, 3 * (1 - s) ** 2 * s, 3 * (1 - s) * s * s, s ** 3
                shape.append((a * x + b * c1[0] + c * c2[0] + d * end[0], a * y + b * c1[1] + c * c2[1] + d * end[1]))
            last_control = c2
            x, y = end
        elif command in "QT":
            if command == "Q":
                c1, end = (v[0], v[1]), (v[2], v[3])
            else:
                c1 = (2 * x - last_control[0], 2 * y - last_control[1]) if last_control else (x, y)
                end = (v[0], v[1])
            for k in range(1, steps + 1):
                s = k / steps
                shape.append(((1 - s) ** 2 * x + 2 * (1 - s) * s * c1[0] + s * s * end[0],
                              (1 - s) ** 2 * y + 2 * (1 - s) * s * c1[1] + s * s * end[1]))
            last_control = c1
            x, y = end
        elif command == "A":
            shape.extend(arc_points(x, y, *v, steps))
            x, y = v[5], v[6]
            last_control = None
    if len(shape) > 1:
        shapes.append(shape)
    return shapes


# An SVG arc's points, from its end point form (SVG 1.1, F.6.5).
def arc_points(x1, y1, rx, ry, rotation, large, sweep, x2, y2, steps):
    if rx == 0 or ry == 0 or (x1 == x2 and y1 == y2):
        return [(x2, y2)]
    phi = math.radians(rotation)
    cp, sp = math.cos(phi), math.sin(phi)
    dx, dy = (x1 - x2) / 2, (y1 - y2) / 2
    xp, yp = cp * dx + sp * dy, -sp * dx + cp * dy
    rx, ry = abs(rx), abs(ry)
    grow = xp * xp / (rx * rx) + yp * yp / (ry * ry)
    if grow > 1:
        rx, ry = rx * math.sqrt(grow), ry * math.sqrt(grow)
    top = rx * rx * ry * ry - rx * rx * yp * yp - ry * ry * xp * xp
    root = math.sqrt(max(0.0, top / (rx * rx * yp * yp + ry * ry * xp * xp)))
    if large == sweep:
        root = -root
    cxp, cyp = root * rx * yp / ry, -root * ry * xp / rx
    cx = cp * cxp - sp * cyp + (x1 + x2) / 2
    cy = sp * cxp + cp * cyp + (y1 + y2) / 2

    def angle(ux, uy, vx, vy):
        a = math.atan2(ux * vy - uy * vx, ux * vx + uy * vy)
        return a

    start = angle(1, 0, (xp - cxp) / rx, (yp - cyp) / ry)
    delta = angle((xp - cxp) / rx, (yp - cyp) / ry, (-xp - cxp) / rx, (-yp - cyp) / ry)
    if not sweep and delta > 0:
        delta -= 2 * math.pi
    elif sweep and delta < 0:
        delta += 2 * math.pi
    points = []
    for k in range(1, steps + 1):
        a = start + delta * k / steps
        ex, ey = rx * math.cos(a), ry * math.sin(a)
        points.append((cp * ex - sp * ey + cx, sp * ex + cp * ey + cy))
    return points


# An icon file's box and ink, as fractions of its square: drawn at 480
# pixels a side with the even-odd rule (Phosphor's and the Material files'
# holes are wound against their outlines, so it matches non-zero).
def measure_file(path: Path) -> dict:
    from PIL import Image, ImageChops, ImageDraw
    text = path.read_text()
    if path.suffix == ".svg":
        grid, datas = GRID, re.findall(r'<path[^>]*\sd="([^"]+)"', text)
    else:
        grid = float(re.search(r'android:viewportWidth="([\d.]+)"', text).group(1))
        datas = re.findall(r'android:pathData="([^"]+)"', text)
    side = 480
    ink = Image.new("1", (side, side), 0)
    for data in datas:
        for shape in outlines(absolute(data)):
            layer = Image.new("1", (side, side), 0)
            ImageDraw.Draw(layer).polygon([(px * side / grid, py * side / grid) for px, py in shape], fill=1)
            ink = ImageChops.logical_xor(ink, layer)
    box = ink.getbbox()
    if not box:
        return {"w": 0, "h": 0, "ink": 0}
    count = sum(ink.getdata()) / 255 if ink.mode != "1" else sum(1 for p in ink.getdata() if p)
    return {"w": (box[2] - box[0]) / side * 24, "h": (box[3] - box[1]) / side * 24, "ink": count / (side * side) * 576}


def measure(args) -> None:
    folder = Path(args.dir)
    print(f"{'file':34} {'width':>6} {'height':>6} {'ink':>7}   (24ths of the square; ink in square 24ths)")
    for path in sorted(folder.glob("sym_*.xml")):
        m = measure_file(path)
        print(f"{path.stem:34} {m['w']:6.2f} {m['h']:6.2f} {m['ink']:7.2f}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("action", nargs="?", default="make", choices=["make", "measure"])
    parser.add_argument("dir", nargs="?", help="measure: the folder of sym_*.xml files")
    parser.add_argument("--out", help="write here instead of the design module")
    parser.add_argument("--weight", help="draw every icon in this weight, where Phosphor has it")
    args = parser.parse_args()
    if args.action == "measure":
        measure(args)
        return
    make(args, json.loads((HERE / "icons.json").read_text()))


if __name__ == "__main__":
    main()
