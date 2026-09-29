"""Octo's cover background library: renders the liquid and silk families,
contact sheets for choosing, and the finished set with its index.

  python cover_art.py candidates --family liquid --count 120 --out DIR [--blur 0.09]
  python cover_art.py sheet DIR --out SHEET_PREFIX
  python cover_art.py finals            (reads picks.json, writes the library)

Every render ends with a wide Gaussian blur (sigma = blur x side, 0.09 by
default; picks.json sets it, per pick if need be), so a cover reads as soft
colour masses, not as the shapes that made them.

Run with PYTHONUTF8=1 from this folder, with requirements.txt installed.
"""
import argparse
import json
import sys
from concurrent.futures import ProcessPoolExecutor
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont

from colour import linear_to_oklab, oklab_to_srgb, oklch, relative_luminance, srgb_to_linear
from palettes import PALETTES
from render import FAMILIES, soften

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
LIBRARY = REPO / "shared/core/src/commonMain/resources/app/winters/octo/covers/backgrounds"
RENDER_SIZE = 1600
FINAL_SIZE = 1200
# Where the title sits (cover-design.json: margin 0.08, title top 0.1, up to
# three wrapped lines), as shares of the side: x0, y0, x1, y1.
TEXT_BOX = (0.04, 0.05, 0.80, 0.50)


def to_srgb(lab: np.ndarray) -> np.ndarray:
    h, w, _ = lab.shape
    return oklab_to_srgb(lab.reshape(-1, 3)).reshape(h, w, 3)


def resize(rgb: np.ndarray, size: int) -> np.ndarray:
    if rgb.shape[0] == size:
        return rgb
    chans = [np.asarray(Image.fromarray(rgb[..., c]).resize((size, size), Image.Resampling.LANCZOS)) for c in range(3)]
    return np.clip(np.stack(chans, axis=-1), 0, 1)


def quantise(rgb: np.ndarray, seed: int) -> Image.Image:
    """8-bit with a triangular dither of one step, so slow blends never band."""
    rng = np.random.default_rng(seed + 99)
    tri = (rng.random(rgb.shape, dtype=np.float32) - rng.random(rgb.shape, dtype=np.float32))
    out = np.clip(np.round(rgb * 255 + tri), 0, 255).astype(np.uint8)
    return Image.fromarray(out, "RGB")


DEFAULT_BLUR = 0.09


def render(family: str, palette: str, seed: int, size: int, overrides=None, blur_share=DEFAULT_BLUR) -> np.ndarray:
    lab = FAMILIES[family](palette, seed, size, overrides)
    lab = soften(lab, size, blur_share)
    return to_srgb(lab.astype(np.float32))


def _candidate(job):
    family, palette, seed, size, blur_share, out = job
    rgb = render(family, palette, seed, size, blur_share=blur_share)
    quantise(rgb, seed).save(Path(out) / f"{family}_{palette}_{seed}.png")
    return f"{family}_{palette}_{seed}"


def cmd_candidates(args):
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    names = [n for n, p in PALETTES.items() if args.family in p["families"]]
    if args.palettes:
        names = [n for n in args.palettes.split(",")]
    jobs = []
    for i in range(args.count):
        palette = names[i % len(names)]
        seed = args.seed + i
        jobs.append((args.family, palette, seed, args.size, args.blur, str(out)))
    with ProcessPoolExecutor(max_workers=args.workers) as pool:
        for name in pool.map(_candidate, jobs):
            print(name, flush=True)


def contact_sheet(images: list[tuple[str, Image.Image]], cols=6, rows=5, tile=300, gap=8, label=True) -> list[Image.Image]:
    font = ImageFont.load_default(size=13)
    label_h = 20 if label else 0
    sheets = []
    per = cols * rows
    for start in range(0, len(images), per):
        chunk = images[start:start + per]
        r = (len(chunk) + cols - 1) // cols
        sheet = Image.new("RGB", (cols * (tile + gap) + gap, r * (tile + gap + label_h) + gap), (14, 14, 16))
        draw = ImageDraw.Draw(sheet)
        for j, (name, img) in enumerate(chunk):
            cx, cy = j % cols, j // cols
            x = gap + cx * (tile + gap)
            y = gap + cy * (tile + gap + label_h)
            sheet.paste(img.resize((tile, tile), Image.Resampling.LANCZOS), (x, y))
            if label:
                draw.text((x + 2, y + tile + 3), name, fill=(200, 200, 205), font=font)
        sheets.append(sheet)
    return sheets


def cmd_sheet(args):
    files = sorted(Path(args.dir).glob("*.png")) + sorted(Path(args.dir).glob("*.webp"))
    images = [(f.stem, Image.open(f).convert("RGB")) for f in files]
    for i, sheet in enumerate(contact_sheet(images)):
        path = f"{args.out}_{i + 1:02d}.png"
        sheet.save(path)
        print(path)


def measure(rgb: np.ndarray) -> dict:
    """The index entry's numbers, from the finished pixels."""
    small = np.asarray(Image.fromarray((rgb * 255).astype(np.uint8)).resize((200, 200), Image.Resampling.BOX)) / 255.0
    small = small.astype(np.float32)
    lab = linear_to_oklab(srgb_to_linear(small))
    lch = oklch(lab)
    L, C, H = lch[..., 0], lch[..., 1], lch[..., 2]

    # Dominant hues: a chroma-weighted hue histogram, smoothed round the wheel.
    weights = np.where(C > 0.04, C, 0).ravel()
    hist, _ = np.histogram(H.ravel(), bins=72, range=(0, 360), weights=weights)
    k = np.exp(-0.5 * (np.arange(-6, 7) / 2.0) ** 2)
    smooth = np.convolve(np.concatenate([hist[-6:], hist, hist[:6]]), k, mode="same")[6:-6]
    peaks = [i for i in range(72) if smooth[i] >= smooth[i - 1] and smooth[i] >= smooth[(i + 1) % 72] and smooth[i] > 0]
    peaks.sort(key=lambda i: -smooth[i])
    hues = []
    total = smooth.sum() or 1
    for i in peaks[:3]:
        share = float(smooth[i] / total * 12)
        if hues and share < 0.25 * hues[0]["weight"]:
            continue
        centre = i * 5 + 2.5
        sel = (np.abs(((H - centre + 180) % 360) - 180) < 20) & (C > 0.04)
        if not sel.any():
            continue
        hues.append(dict(
            l=round(float(L[sel].mean()), 3),
            c=round(float(C[sel].mean()), 3),
            h=round(float(centre), 1),
            weight=round(share, 3),
        ))
    for hue in hues:
        del hue["weight"]

    x0, y0, x1, y1 = (int(v * 200) for v in TEXT_BOX)
    box = small[y0:y1, x0:x1]
    lum = relative_luminance(box)
    p50 = float(np.percentile(lum, 50))
    p95 = float(np.percentile(lum, 95))

    def veil(ratio):
        # Black overlay opacity that lifts white text to `ratio` against p95.
        need = 1.05 / ratio - 0.05
        return 0.0 if p95 <= need else round(1 - need / p95, 3)

    return dict(
        hues=hues,
        meanLightness=round(float(L.mean()), 3),
        textArea=dict(
            box=list(TEXT_BOX),
            meanLightness=round(float(lab[y0:y1, x0:x1, 0].mean()), 3),
            darkness=round(1 - float(lab[y0:y1, x0:x1, 0].mean()), 3),
            p50Luminance=round(p50, 4),
            p95Luminance=round(p95, 4),
            whiteContrastP50=round(1.05 / (p50 + 0.05), 2),
            whiteContrastP95=round(1.05 / (p95 + 0.05), 2),
            veilFor3=veil(3.0),
            veilFor4_5=veil(4.5),
        ),
    )


def _final(job):
    pick, size, final, quality, out = job
    rgb = render(pick["family"], pick["palette"], pick["seed"], size, pick.get("overrides"), pick["blur"])
    rgb = resize(rgb, final)
    img = quantise(rgb, pick["seed"])
    path = Path(out) / pick["file"]
    img.save(path, "WEBP", quality=quality, method=6)
    back = np.asarray(Image.open(path).convert("RGB"), dtype=np.float32) / 255.0
    return pick, measure(back), path.stat().st_size


def cmd_finals(args):
    chosen = json.loads((HERE / "picks.json").read_text(encoding="utf-8"))
    picks = chosen["picks"]
    for pick in picks:
        pick.setdefault("blur", chosen.get("blur", DEFAULT_BLUR))
    out = Path(args.out) if args.out else LIBRARY
    out.mkdir(parents=True, exist_ok=True)
    for pick in picks:
        pick["file"] = f"{pick['id']}.webp"
    # A background dropped from the picks leaves the library with it.
    keep = {p["file"] for p in picks}
    for old in out.glob("*.webp"):
        if old.name not in keep:
            old.unlink()
    jobs = [(p, args.size, FINAL_SIZE, args.quality, str(out)) for p in picks]
    entries, total = [], 0
    with ProcessPoolExecutor(max_workers=args.workers) as pool:
        for pick, m, size in pool.map(_final, jobs):
            total += size
            entries.append(dict(file=pick["file"], name=pick["name"], family=pick["family"],
                                palette=pick["palette"], seed=pick["seed"], blur=pick["blur"], **m))
            print(f"{pick['file']}  {size // 1024} KB", flush=True)
    index = dict(
        version=1,
        about=("Octo's cover backgrounds, made by tools/cover-art (python cover_art.py finals). "
               f"Each is {FINAL_SIZE}x{FINAL_SIZE} WebP. Hues are OKLCH (l 0..1, c, h degrees), strongest first. "
               "textArea measures the box (shares of the side: x0, y0, x1, y1) where the title sits: "
               "darkness is 1 minus its mean OKLab lightness; whiteContrastP50 and P95 are white text against the "
               "box's median and 95th percentile luminance; veilFor3 and veilFor4_5 are the black overlay "
               "opacities that bring the P95 contrast to 3:1 (large text) and 4.5:1."),
        size=FINAL_SIZE,
        backgrounds=entries,
    )
    (out / "backgrounds.json").write_text(json.dumps(index, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"total {total / 1024 / 1024:.2f} MB in {len(entries)} files")
    if args.sheet:
        # Family first, then round the colour wheel, so near neighbours sit together.
        order = sorted(entries, key=lambda e: (e["family"] != "liquid", (e["hues"][0]["h"] + 30) % 360 if e["hues"] else 0))
        images = [(e["name"], Image.open(out / e["file"]).convert("RGB")) for e in order]
        for i, sheet in enumerate(contact_sheet(images, cols=8, rows=6, tile=300)):
            sheet.save(f"{args.sheet}_{i + 1:02d}.png")
            print(f"{args.sheet}_{i + 1:02d}.png")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    c = sub.add_parser("candidates")
    c.add_argument("--family", choices=list(FAMILIES), required=True)
    c.add_argument("--count", type=int, default=60)
    c.add_argument("--seed", type=int, default=1)
    c.add_argument("--size", type=int, default=600)
    c.add_argument("--blur", type=float, default=DEFAULT_BLUR)
    c.add_argument("--palettes", default="")
    c.add_argument("--workers", type=int, default=8)
    c.add_argument("--out", required=True)
    c.set_defaults(fn=cmd_candidates)
    s = sub.add_parser("sheet")
    s.add_argument("dir")
    s.add_argument("--out", required=True)
    s.set_defaults(fn=cmd_sheet)
    f = sub.add_parser("finals")
    f.add_argument("--size", type=int, default=RENDER_SIZE)
    f.add_argument("--quality", type=int, default=95)
    f.add_argument("--workers", type=int, default=6)
    f.add_argument("--out", default="")
    f.add_argument("--sheet", default="")
    f.set_defaults(fn=cmd_finals)
    a = ap.parse_args()
    a.fn(a)


if __name__ == "__main__":
    sys.exit(main())
