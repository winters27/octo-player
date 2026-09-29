"""The reference for Octo's designed covers: the words' layout and the
colour-keeping veil under them, exactly as cover-design.json describes, so
the phone, desktop and server ports can be checked against it.

  python reference.py golden        writes golden/ (6 covers and samples.json)
  python reference.py mock OUT.png  a sheet of 12 finished covers

Run with PYTHONUTF8=1 from this folder, with requirements.txt installed.

What must match across ports is the layout (sizes and boxes) and the veil
(every background pixel). Glyph pixels come from each platform's own text
rasteriser and are compared by eye, not by value.
"""
import json
import re
import sys
from dataclasses import dataclass, field
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont

from colour import gamut_clip, linear_to_oklab, oklab_to_linear, srgb_to_linear, linear_to_srgb

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
COVERS = REPO / "shared/core/src/commonMain/resources/app/winters/octo/covers"
FONTS = REPO / "design/src/main/res/font"
GOLDEN = HERE / "golden"
BOOK = json.loads((COVERS / "cover-design.json").read_text(encoding="utf-8"))
LAYOUT = BOOK["layout"]
VEIL = BOOK["veil"]
LUMA = np.array([0.2126, 0.7152, 0.0722])

_WIDE = re.compile(r"[ᄀ-ᇿ⺀-鿿가-힯豈-﫿＀-￯]")


# Backgrounds ----------------------------------------------------------------

def background(file: str, side: int) -> np.ndarray:
    """The library file as 8-bit sRGB at `side`. The reference defines 1200
    (as stored) and 600 (each 2x2 block averaged: (a + b + c + d + 2) // 4)."""
    img = np.asarray(Image.open(COVERS / "backgrounds" / file).convert("RGB"), dtype=np.int32)
    if side == img.shape[0]:
        return img.astype(np.uint8)
    if side * 2 == img.shape[0]:
        s = img[0::2, 0::2] + img[1::2, 0::2] + img[0::2, 1::2] + img[1::2, 1::2]
        return ((s + 2) // 4).astype(np.uint8)
    raise ValueError("the reference defines covers at 1200 and 600 px")


# Words ----------------------------------------------------------------------

def font_file(role: str) -> Path:
    # "InterDisplay-SemiBold.ttf" is bundled as inter_display_semibold.ttf.
    weight = BOOK["fonts"][role].split("-")[1].split(".")[0].lower()
    return FONTS / f"inter_display_{weight}.ttf"


class Setter:
    """The text engine: advance widths from the font, lines broken greedily
    at spaces (and between any two wide, CJK-style, letters)."""

    def __init__(self):
        self.cache = {}

    def font(self, role, size):
        key = (role, size)
        if key not in self.cache:
            self.cache[key] = ImageFont.truetype(str(font_file(role)), size)
        return self.cache[key]

    def width(self, text, role, size):
        return self.font(role, size).getlength(text)

    def lines(self, text, role, size, width):
        runs = runs_of(text)
        out, cur = [], ""
        for run, spaced in runs:
            trial = cur + (" " if cur and spaced else "") + run if cur else run
            if not cur or self.width(trial, role, size) <= width:
                cur = trial
            else:
                out.append(cur)
                cur = run
        if cur:
            out.append(cur)
        return out


def runs_of(text):
    """Unbreakable runs, as CoverText.unbreakableRuns: words split at spaces,
    each wide letter a run of its own. The flag says whether a space came
    before the run (only then is one set between it and the run before)."""
    runs, word, word_spaced, spaced = [], "", False, False
    for ch in text:
        if ch.isspace():
            if word:
                runs.append((word, word_spaced))
                word = ""
            spaced = True
        elif _WIDE.match(ch):
            if word:
                runs.append((word, word_spaced))
                word = ""
            runs.append((ch, spaced))
            spaced = False
        else:
            if not word:
                word_spaced = spaced
                spaced = False
            word += ch
    if word:
        runs.append((word, word_spaced))
    return runs


def line_height(text, wanted):
    """CoverText.coverLineHeight: wide scripts need at least 1.15."""
    return max(wanted, 1.15) if _WIDE.search(text) else wanted


@dataclass
class Fitted:
    size: int
    lines: list
    height: float


def fit(setter, text, role, width, height, max_lines, least, most, lh) -> Fitted | None:
    """CoverText.fitOrNull: the largest whole-pixel size from `least` to
    `most` at which the text fits, found by halving."""
    runs = [r for r, _ in runs_of(text)]

    def fits(size):
        size = int(size)
        if any(setter.width(r, role, size) > width for r in runs):
            return None
        lines = setter.lines(text, role, size, width)
        h = len(lines) * size * lh
        return Fitted(size, lines, h) if len(lines) <= max_lines and h <= height else None

    low = max(np.floor(least), 1)
    high = max(np.floor(most), low)
    got = fits(high)
    if got:
        return got
    best = fits(low)
    if best is None:
        return None
    while high - low > 1:
        mid = np.floor((low + high) / 2)
        m = fits(mid)
        if m:
            low, best = mid, m
        else:
            high = mid
    return best


def fit_or_cut(setter, text, role, width, height, max_lines, least, most, lh) -> Fitted:
    got = fit(setter, text, role, width, height, max_lines, least, most, lh)
    if got:
        return got
    size = int(max(np.floor(least), 1))
    rows = max(1, min(max_lines, int(height / (size * lh))))
    lines = setter.lines(text, role, size, width)[:rows]
    return Fitted(size, lines, len(lines) * size * lh)


KIND_WORDS = {"mix", "mixes", "radio", "radios", "station", "stations", "playlist", "playlists"}


def says_what_it_is(name):
    words = [w for w in re.split(r"[^\w]+", name.strip()) if w]
    return bool(words) and words[-1].lower() in KIND_WORDS


@dataclass
class Words:
    role: str
    text: str
    size: int
    lines: list
    lh: float
    x: float
    top: float
    height: float
    opacity: float
    widths: list = field(default_factory=list)

    @property
    def right(self):
        return self.x + max(self.widths)


def cover_words(setter, side, name, line=None, footer=None) -> list[Words]:
    """CoverDesign.coverWords. Below tinyBelowPx the cover is artwork only."""
    s = float(side)
    if side < LAYOUT["tinyBelowPx"]:
        return []
    margin = round(s * LAYOUT["margin"])
    width = s - 2 * margin
    out = []
    floor = s - margin
    foot = None
    if footer and footer.strip() and side >= LAYOUT["footer"]["showFromPx"]:
        F = LAYOUT["footer"]
        lh = line_height(footer, F["lineHeight"])
        size = max(F["minPx"], s * F["size"])
        f = fit_or_cut(setter, footer, "footer", width, s * 0.2, 1, F["minPx"], size, lh)
        top = s - s * F["bottom"] - f.height
        foot = Words("footer", footer, f.size, f.lines, lh, margin, top, f.height, F["opacity"])
        floor = top - s * 0.04
    name = name.strip()
    if not name:
        return [foot] if foot else []
    T = LAYOUT["title"]
    top = s * T["top"]
    show_line = line and line.strip() and side >= LAYOUT["line"]["showFromPx"] and not says_what_it_is(name)
    line_room = max(s * T["wrapSize"], T["wrapMinPx"]) * LAYOUT["line"]["shareOfTitle"] * LAYOUT["line"]["lineHeight"] if show_line else 0
    room = floor - top - line_room
    lh = line_height(name, T["lineHeight"])
    min_px = max(T["minPx"], s * T["minSize"])
    one_least = max(min_px, max(s * T["oneLineDownTo"], T["oneLineMinPx"]))
    wrap_most = max(min_px, max(s * T["wrapSize"], min(T["wrapMinPx"], s * T["size"])))
    title = fit(setter, name, "title", width, room, 1, one_least, s * T["size"], lh) if one_least <= s * T["size"] else None
    title = title or fit_or_cut(setter, name, "title", width, room, T["maxLines"], min_px, wrap_most, lh)
    out.append(Words("title", name, title.size, title.lines, lh, margin, top, title.height, 1.0))
    if show_line:
        Ln = LAYOUT["line"]
        line_top = top + title.height
        most = max(Ln["minPx"], title.size * Ln["shareOfTitle"])
        llh = line_height(line, Ln["lineHeight"])
        f = fit_or_cut(setter, line, "line", width, s, 1, min(Ln["minPx"], most), most, llh)
        if line_top + f.height <= floor:
            out.append(Words("line", line, f.size, f.lines, llh, margin, line_top, f.height, 1.0))
    if foot:
        out.append(foot)
    for w in out:
        w.widths = [setter.width(l, w.role, w.size) for l in w.lines]
    return out


# The veil -------------------------------------------------------------------

def smoothstep(e0, e1, x):
    t = np.clip((x - e0) / (e1 - e0), 0.0, 1.0)
    return t * t * (3 - 2 * t)


def region_weight(side, box, falloff):
    """1 inside the box, easing to 0 outside it: the distance from the box,
    across and down each measured in its own falloff (shares of the side),
    at pixel centres."""
    c = (np.arange(side) + 0.5)
    x0, y0, x1, y1 = box
    dx = (np.maximum(np.maximum(x0 - c, c - x1), 0) / (falloff[0] * side))[None, :]
    dy = (np.maximum(np.maximum(y0 - c, c - y1), 0) / (falloff[1] * side))[:, None]
    return 1 - smoothstep(0.0, 1.0, np.sqrt(dx * dx + dy * dy))


def limit_for(contrast, ink_opacity=1.0):
    """The most a background's luminance may be for white words at
    `ink_opacity` (laid over it in sRGB, as the apps draw) to reach
    `contrast`, taken on a grey background."""
    if ink_opacity >= 1:
        return 1.05 / contrast - 0.05
    lo, hi = 0.0, 1.0
    for _ in range(40):
        mid = (lo + hi) / 2
        bg = float(linear_to_srgb(np.array([mid]))[0])
        ink = bg + (1 - bg) * ink_opacity
        y_ink = float(srgb_to_linear(np.array([ink]))[0])
        if (y_ink + 0.05) / (mid + 0.05) >= contrast:
            lo = mid
        else:
            hi = mid
    return lo


def veil_regions(side, words):
    """Each region's box (pixels) and rule, from the laid-out words."""
    s = float(side)
    regions = []
    block = [w for w in words if w.role in ("title", "line")]
    if block:
        V = VEIL["title"]
        pad = V["pad"] * s
        box = (0.0, 0.0, max(w.right for w in block) + pad, max(w.top + w.height for w in block) + pad)
        aim = limit_for(V["aimContrast"]) * (1 - VEIL["margin"])
        floor = limit_for(V["minContrast"]) * (1 - VEIL["margin"])
        regions.append(dict(box=box, falloff=V["falloff"], aim=aim, least=floor, max_drop=V["maxDrop"]))
    foot = [w for w in words if w.role == "footer"]
    if foot:
        V = VEIL["footer"]
        w = foot[0]
        pad = V["pad"] * s
        box = (0.0, w.top - pad, w.right + pad, s)
        need = limit_for(V["contrast"], LAYOUT["footer"]["opacity"]) * (1 - VEIL["margin"])
        regions.append(dict(box=box, falloff=V["falloff"], aim=need, least=need, max_drop=1.0))
    return regions


def apply_veil(rgb8: np.ndarray, regions) -> np.ndarray:
    """The colour-keeping veil: only what is too bright for white words is
    darkened, by OKLab lightness with hue and chroma kept (yellows turned
    toward amber), and only as far as each region's rule asks."""
    side = rgb8.shape[0]
    if not regions:
        return rgb8
    lin = srgb_to_linear(rgb8.astype(np.float64) / 255.0)
    y = lin @ LUMA
    # Each region keeps the share `keep` of a pixel's luminance; shares
    # multiply, so where two regions meet the veil stays smooth.
    keep = np.ones_like(y)
    reach = np.zeros_like(y)
    for r in regions:
        w = region_weight(side, r["box"], r["falloff"])
        # Aim for the region's target, but take at most max_drop of the
        # pixel's luminance for it; never stop short of `least`.
        limit = np.minimum(np.maximum(r["aim"], y * (1 - r["max_drop"])), r["least"])
        keep *= 1 - w * (1 - np.minimum(1.0, limit / np.maximum(y, 1e-9)))
        reach = np.maximum(reach, w)
    target = y * keep
    todo = target < y - 1e-9
    if not todo.any():
        return rgb8
    full = linear_to_oklab(lin)
    Y = VEIL["yellow"]
    lo, full_lo, full_hi, hi = Y["hues"]

    def yellowness(lab):
        c = np.hypot(lab[..., 1], lab[..., 2])
        h = np.degrees(np.arctan2(lab[..., 2], lab[..., 1])) % 360
        return h, c, smoothstep(lo, full_lo, h) * (1 - smoothstep(full_hi, hi, h)) * np.clip(c / Y["chromaFrom"], 0, 1)

    # One direction per cover: where the yellows under the veil lean
    # yellow they deepen to amber, where they lean lime, to green.
    h_all, c_all, wy_all = yellowness(full)
    mass = wy_all * c_all * reach
    lean = float((h_all * mass).sum() / mass.sum()) if mass.sum() > 0 else 0.0
    towards = Y["towards"][0] if lean <= Y["split"] else Y["towards"][1]
    lab = full[todo]
    y0, t = y[todo], target[todo]
    drop = 1 - t / y0
    L = lab[:, 0]
    h, c, wy = yellowness(lab)
    share = np.clip(Y["turnPerDrop"] * drop, 0, 1) * wy
    h2 = h + (towards - h) * share
    c2 = c * (1 + Y["chromaLift"] * drop * wy)
    a2 = c2 * np.cos(np.radians(h2))
    b2 = c2 * np.sin(np.radians(h2))

    def settle(Lx):
        lab2 = np.stack([Lx, a2, b2], axis=-1).astype(np.float32)
        return np.clip(gamut_clip(oklab_to_linear(lab2), lab2), 0, 1).astype(np.float64)

    # Lightness for the target luminance: start from L * cbrt(t / y) (exact
    # for greys), then refine the same way against what the colour settles
    # to after gamut mapping.
    L2 = L * np.cbrt(t / y0)
    for _ in range(VEIL["refine"]):
        got = settle(L2) @ LUMA
        L2 = L2 * np.cbrt(t / np.maximum(got, 1e-6))
    out = lin.copy()
    out[todo] = settle(L2)
    return np.clip(np.round(linear_to_srgb(out) * 255), 0, 255).astype(np.uint8)


# Drawing --------------------------------------------------------------------

def draw_words(img: Image.Image, setter, words):
    """White words over the veiled background, alpha laid over in sRGB. Each
    line's glyph box (the font's ascender to descender) is centred in its
    line box."""
    layer = Image.new("RGBA", img.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    for w in words:
        f = setter.font(w.role, w.size)
        asc, desc = f.getmetrics()
        box = w.size * w.lh
        for i, line in enumerate(w.lines):
            base = w.top + i * box + (box - (asc + desc)) / 2 + asc
            d.text((w.x, base), line, font=f, fill=(255, 255, 255, round(255 * w.opacity)), anchor="ls")
    return Image.alpha_composite(img.convert("RGBA"), layer).convert("RGB"), np.asarray(layer)[..., 3]


def render(bg_file, side, name, line=None, footer=None):
    setter = Setter()
    words = cover_words(setter, side, name, line, footer)
    veiled = apply_veil(background(bg_file, side), veil_regions(side, words))
    img, ink = draw_words(Image.fromarray(veiled), setter, words)
    return img, veiled, words, ink


def contrast_under(veiled, ink, words, side):
    """The worst white contrast under each word block's ink (95th
    percentile of the background luminance there)."""
    y = srgb_to_linear(veiled.astype(np.float64) / 255.0) @ LUMA
    out = {}
    for w in words:
        y0, y1 = int(w.top), int(np.ceil(w.top + w.height))
        x0, x1 = int(w.x), int(np.ceil(w.right))
        mask = np.zeros_like(ink, dtype=bool)
        mask[y0:y1, x0:x1] = ink[y0:y1, x0:x1] > 128
        if not mask.any():
            continue
        yb = float(np.percentile(y[mask], 95))
        if w.opacity >= 1:
            ratio = 1.05 / (yb + 0.05)
        else:
            bg = float(linear_to_srgb(np.array([yb]))[0])
            yi = float(srgb_to_linear(np.array([bg + (1 - bg) * w.opacity]))[0])
            ratio = (yi + 0.05) / (yb + 0.05)
        out[w.role] = round(ratio, 2)
    return out


# Commands -------------------------------------------------------------------

GOLDEN_COVERS = [
    ("gym", "lemonade.webp", 600, "Gym", "Playlist", "By Noah"),
    ("everything", "peach.webp", 1200, "Everything I have ever loved", "Playlist", "1,204 songs"),
    ("late-night", "night-swim.webp", 600, "Late night", "Playlist", "86 songs"),
    ("sunday", "clear-sky.webp", 1200, "Sunday morning", "Playlist", "18 songs"),
    ("daft-punk", "chiffon.webp", 600, "Daft Punk", "Station", None),
    ("rock", "magenta.webp", 1200, "Rock", "Mix", "50 songs"),
]


def cmd_golden():
    GOLDEN.mkdir(exist_ok=True)
    report = dict(
        about=("Golden covers from reference.py. Ports must match `words` (sizes and boxes, to a pixel) "
               "and `samples` (the veiled background before any words, 8-bit sRGB, to within 2 per "
               "channel). The images show the words as Pillow sets them, for comparing by eye."),
        covers=[],
    )
    for key, file, side, name, line, footer in GOLDEN_COVERS:
        img, veiled, words, ink = render(file, side, name, line, footer)
        path = GOLDEN / f"{key}_{side}.webp"
        img.save(path, "WEBP", lossless=True, method=6)
        pts = [(int(side * u), int(side * v)) for v in (0.02, 0.15, 0.3, 0.5, 0.7, 0.88, 0.97)
               for u in (0.02, 0.2, 0.45, 0.7, 0.97)]
        report["covers"].append(dict(
            file=path.name, background=file, side=side, name=name, line=line, footer=footer,
            words=[dict(role=w.role, size=w.size, lines=w.lines, x=w.x, top=round(w.top, 2),
                        height=round(w.height, 2), right=round(w.right, 2)) for w in words],
            contrast=contrast_under(veiled, ink, words, side),
            samples=[dict(x=x, y=y, rgb=[int(c) for c in veiled[y, x]]) for x, y in pts],
        ))
        print(path.name, report["covers"][-1]["contrast"])
    (GOLDEN / "samples.json").write_text(json.dumps(report, indent=1, ensure_ascii=False) + "\n", encoding="utf-8")


MOCKS = [
    ("night-swim.webp", "Late night", "Playlist", "86 songs"),
    ("sunset.webp", "Running", "Playlist", "42 songs"),
    ("peach.webp", "Everything I have ever loved", "Playlist", "1,204 songs"),
    ("tide.webp", "Night drive", "Playlist", "31 songs"),
    ("magenta.webp", "Daft Punk", "Station", None),
    ("crimson.webp", "Rock", "Mix", "50 songs"),
    ("lemonade.webp", "Gym", "Playlist", "By Noah"),
    ("clear-sky.webp", "Sunday morning", "Playlist", "18 songs"),
    ("mercury.webp", "Focus", "Live list", "240 songs"),
    ("amber-night.webp", "Road trip", "Playlist", "64 songs"),
    ("chiffon.webp", "Summer", "Playlist", "112 songs"),
    ("orchid.webp", "Deep cuts", "Mix", "50 songs"),
]


def cmd_mock(out, mocks=MOCKS, tile=400):
    gap = 16
    cols = 4
    rows = (len(mocks) + cols - 1) // cols
    sheet = Image.new("RGB", (cols * tile + (cols + 1) * gap, rows * tile + (rows + 1) * gap), (14, 14, 16))
    for i, (file, name, line, footer) in enumerate(mocks):
        img, veiled, words, ink = render(file, 600, name, line, footer)
        print(f"{name:32} {contrast_under(veiled, ink, words, 600)}")
        img = img.resize((tile, tile), Image.Resampling.LANCZOS)
        sheet.paste(img, (gap + (i % cols) * (tile + gap), gap + (i // cols) * (tile + gap)))
    sheet.save(out)
    print(out)


if __name__ == "__main__":
    if len(sys.argv) >= 2 and sys.argv[1] == "golden":
        cmd_golden()
    elif len(sys.argv) >= 3 and sys.argv[1] == "mock":
        cmd_mock(sys.argv[2])
    else:
        print(__doc__)
        sys.exit(1)
