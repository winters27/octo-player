"""Octo's app icon: the octopus from Octo's logo, exactly as painted, on a
deep violet tile.

  python make_app_icon.py              writes the phone's adaptive icon layers
                                       and the square store icon the desktop
                                       icons are cut from
  python make_app_icon.py take LOGO    copies the octopus out of the logo's PNG
                                       (C:\\Octo\\octo\\Assets\\octo_logo.png)
                                       into octopus.png; run the line above after
  python make_app_icon.py preview DIR  writes the icons, plus previews in DIR

The logo's pixels are used as they are: its alpha is the octopus and its
colour is the painted octopus (the grey glow around it is fully clear).
Only the tile behind it is drawn here, and the same tile is written as the
adaptive icon's vector background.

After a change, run ./gradlew :desktop:makeIcons to remake desktop/icons.

Needs numpy and Pillow. Run with PYTHONUTF8=1.
"""
import sys
from pathlib import Path

import numpy as np
from PIL import Image

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
OCTOPUS = HERE / "octopus.png"
RES = ROOT / "app/src/main/res"
STORE = ROOT / "app/src/main/ic_launcher-playstore.png"

# The tile: a deep violet, lighter at the top left, with a faint light of
# the octopus's own violet behind it, as around the logo.
SKY_TOP = "#1D1250"
SKY_BOTTOM = "#0B0720"
GLOW = "#6C4AF6"
GLOW_ALPHA = 0.28
GLOW_RADIUS = 0.50   # of the side, from the centre

# How far the octopus reaches from the tile's centre, as a share of the side.
# Launchers always show the middle 66 dp circle of the adaptive icon's 108.
ADAPTIVE_REACH = 29 / 108
SQUARE_REACH = 0.44

# The adaptive icon's layers, 108 dp at each density.
DENSITIES = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}


def take(logo_path):
    """The octopus, cropped out of the logo. Alpha under 4 of 255 is stray
    specks and the trace of the glow, so it is cleared."""
    image = Image.open(logo_path).convert("RGBA")
    pixels = np.asarray(image).copy()
    pixels[pixels[..., 3] < 4] = 0
    ys, xs = np.nonzero(pixels[..., 3])
    pad = 2
    crop = pixels[ys.min() - pad:ys.max() + pad + 1, xs.min() - pad:xs.max() + pad + 1]
    Image.fromarray(crop).save(OCTOPUS, optimize=True)
    print("Took the octopus from", logo_path, "into", OCTOPUS.relative_to(ROOT), f"({crop.shape[1]}x{crop.shape[0]})")


def octopus():
    return Image.open(OCTOPUS).convert("RGBA")


def reach(art):
    """How far the octopus's ink goes from its box's centre, in its pixels."""
    alpha = np.asarray(art)[..., 3]
    ys, xs = np.nonzero(alpha > 127)
    cx, cy = (art.width - 1) / 2, (art.height - 1) / 2
    return float(np.sqrt((xs - cx) ** 2 + (ys - cy) ** 2).max())


def placed(art, size, share):
    """The octopus scaled and centred on a clear square `size` wide, its ink
    reaching `share` of the side from the centre."""
    scale = size * share / reach(art)
    w, h = round(art.width * scale), round(art.height * scale)
    # Scaled with premultiplied alpha, so no dark or grey fringe.
    small = art.convert("RGBa").resize((w, h), Image.LANCZOS).convert("RGBA")
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    out.alpha_composite(small, ((size - w) // 2, (size - h) // 2))
    return out


def rgb(hex_colour):
    h = hex_colour.lstrip("#")
    return np.array([int(h[i:i + 2], 16) for i in (0, 2, 4)], float)


def tile(size):
    """The tile, as the vector background draws it: a diagonal gradient,
    then the glow over it."""
    ys, xs = np.mgrid[0:size, 0:size].astype(float) + 0.5
    t = np.clip((xs + ys) / (2 * size), 0, 1)[..., None]
    sky = rgb(SKY_TOP) * (1 - t) + rgb(SKY_BOTTOM) * t
    d = np.sqrt((xs - size / 2) ** 2 + (ys - size / 2) ** 2) / (size * GLOW_RADIUS)
    glow = (GLOW_ALPHA * np.clip(1 - d, 0, 1))[..., None]
    out = sky * (1 - glow) + rgb(GLOW) * glow
    alpha = np.full((size, size, 1), 255.0)
    return Image.fromarray(np.concatenate([out, alpha], -1).round().astype(np.uint8), "RGBA")


def argb(hex_colour, alpha=1.0):
    return f"#{round(alpha * 255):02X}{hex_colour.lstrip('#').upper()}"


def background_vector():
    def layer(gradient):
        return ('    <path\n        android:pathData="M0,0H108V108H0Z">\n'
                '        <aapt:attr name="android:fillColor">\n'
                f"            <gradient {gradient} />\n"
                "        </aapt:attr>\n    </path>\n")

    sky = ('android:type="linear" android:startX="0" android:startY="0" android:endX="108" android:endY="108" '
           f'android:startColor="{argb(SKY_TOP)}" android:endColor="{argb(SKY_BOTTOM)}"')
    glow = (f'android:type="radial" android:centerX="54" android:centerY="54" '
            f'android:gradientRadius="{108 * GLOW_RADIUS:g}" '
            f'android:startColor="{argb(GLOW, GLOW_ALPHA)}" android:endColor="{argb(GLOW, 0)}"')
    return ('<?xml version="1.0" encoding="utf-8"?>\n'
            "<!-- Made by tools/appicon/make_app_icon.py; change that, not this. -->\n"
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    xmlns:aapt="http://schemas.android.com/aapt"\n'
            '    android:width="108dp"\n    android:height="108dp"\n'
            '    android:viewportWidth="108"\n    android:viewportHeight="108">\n'
            f"{layer(sky)}{layer(glow)}</vector>\n")


def square(art, size):
    out = tile(size)
    out.alpha_composite(placed(art, size, SQUARE_REACH))
    return out


def write():
    art = octopus()
    for name, density in DENSITIES.items():
        size = round(108 * density)
        folder = RES / f"mipmap-{name}"
        folder.mkdir(parents=True, exist_ok=True)
        front = placed(art, size, ADAPTIVE_REACH)
        front.save(folder / "ic_launcher_foreground.png", optimize=True)
        # Themed icons: the silhouette alone; the launcher tints it.
        white = Image.new("RGBA", front.size, (255, 255, 255, 0))
        white.putalpha(front.getchannel("A"))
        white.save(folder / "ic_launcher_monochrome.png", optimize=True)
    (RES / "drawable/ic_launcher_background.xml").write_text(background_vector(), encoding="utf-8")
    square(art, 512).convert("RGB").save(STORE, optimize=True)
    print("Wrote the adaptive icon layers and", STORE.relative_to(ROOT))


def preview(out):
    out.mkdir(parents=True, exist_ok=True)
    art = octopus()
    square(art, 1024).save(out / "square.png")
    adaptive = tile(432)
    adaptive.alpha_composite(placed(art, 432, ADAPTIVE_REACH))
    adaptive.save(out / "adaptive.png")
    print("Previews in", out)


def main():
    args = sys.argv[1:]
    if args[:1] == ["take"]:
        take(args[1])
        return
    write()
    if args[:1] == ["preview"]:
        preview(Path(args[1]))


if __name__ == "__main__":
    main()
