"""The two background families. Each render is fully decided by
(family, palette, seed): every shape parameter is drawn from the seed."""
import numpy as np

from colour import hex_to_oklab
from noise import Noise, blur, fbm
from palettes import PALETTES


def stops_lab(palette: str) -> np.ndarray:
    return np.stack([hex_to_oklab(s) for s in PALETTES[palette]["stops"]]).astype(np.float32)


def ramp(stops: np.ndarray, t: np.ndarray, sharp: float) -> np.ndarray:
    """Colour along the stops. Each blend keeps the chroma of its ends (plain
    OKLab lerps sag toward grey mid-way), and `sharp` holds each colour a
    little longer before it gives way to the next."""
    n = len(stops)
    u = np.clip(t, 0, 1) * (n - 1)
    i = np.minimum(np.floor(u).astype(np.int32), n - 2)
    f = u - i
    if sharp > 0:
        k = np.float32(sharp)
        s = 1 / (1 + np.exp(-k * (f - 0.5)))
        lo, hi = 1 / (1 + np.exp(k * 0.5)), 1 / (1 + np.exp(-k * 0.5))
        f = (s - lo) / (hi - lo)
    a, b = stops[i], stops[i + 1]
    f = f[..., None].astype(np.float32)
    lab = a + (b - a) * f
    ca = np.hypot(a[..., 1], a[..., 2])
    cb = np.hypot(b[..., 1], b[..., 2])
    want = ca + (cb - ca) * f[..., 0]
    have = np.hypot(lab[..., 1], lab[..., 2])
    scale = np.where(have > 1e-4, want / np.maximum(have, 1e-4), 1.0)
    lab[..., 1] *= scale
    lab[..., 2] *= scale
    return lab


def equalise(f: np.ndarray, amount: float, rng) -> np.ndarray:
    """Maps the field to 0..1, part linear and part by rank, so every stop
    gets a real share of the cover."""
    sample = np.sort(rng.choice(f.ravel(), 20000))
    lo, hi = sample[200], sample[-200]
    lin = np.clip((f - lo) / (hi - lo), 0, 1)
    cdf = np.interp(f, sample, np.linspace(0, 1, len(sample))).astype(np.float32)
    return (lin * (1 - amount) + cdf * amount).astype(np.float32)


def grid(size: int):
    c = (np.arange(size, dtype=np.float32) + 0.5) / size
    return np.meshgrid(c, c)


def liquid_params(seed: int) -> dict:
    rng = np.random.default_rng(seed * 7919 + 17)
    return dict(
        scale=float(rng.uniform(0.45, 0.75)),
        warp=float(rng.uniform(1.2, 2.2)),
        octaves=int(rng.choice([2, 3])),
        sharp=float(rng.uniform(6.0, 10.0)),
        equal=float(rng.uniform(0.4, 0.8)),
        angle=float(rng.uniform(0, 2 * np.pi)),
        flip=bool(rng.random() < 0.5),
        fold=float(rng.choice([0.0, 0.0, 0.0, 1.0])),
        depth=float(rng.uniform(1.0, 2.0)),
        fold_level=float(rng.uniform(0.35, 0.65)),
        soft=float(rng.uniform(0.006, 0.012)),
        stretch=float(rng.uniform(1.0, 1.8)),
        fold_turn=float(rng.uniform(-0.8, 0.8)),
    )


def render_liquid(palette: str, seed: int, size: int, overrides: dict | None = None) -> np.ndarray:
    p = liquid_params(seed)
    p.update(overrides or {})
    noise = Noise(seed)
    rng = np.random.default_rng(seed)
    gx, gy = grid(size)
    ca, sa = np.cos(p["angle"]), np.sin(p["angle"])
    # Stretching one axis before the warp makes the forms stream in one
    # direction, like poured paint, instead of sitting as round blobs.
    x = ((gx - 0.5) * ca - (gy - 0.5) * sa) * p["scale"] / p["stretch"] + 10.0
    y = ((gx - 0.5) * sa + (gy - 0.5) * ca) * p["scale"] * p["stretch"] + 10.0
    o = p["octaves"]
    k = p["warp"]
    qx = fbm(noise, x, y, o)
    qy = fbm(noise, x + 5.2, y + 1.3, o)
    rx = fbm(noise, x + k * qx + 1.7, y + k * qy + 9.2, o)
    ry = fbm(noise, x + k * qx + 8.3, y + k * qy + 2.8, o)
    f = fbm(noise, x + k * rx, y + k * ry, o)
    f = blur(f, p["soft"] * size)
    t = equalise(f, p["equal"], rng)
    stops = stops_lab(palette)
    if p["flip"]:
        stops = stops[::-1].copy()
    lab = ramp(stops, t, p["sharp"])
    if p["depth"] > 0:
        lab = add_depth(lab, t, size, p["depth"])
    if p["fold"] > 0:
        # One long curve across the cover: a straight line bent by the warp.
        fa = p["angle"] + p["fold_turn"]
        line = (gx - 0.5) * np.sin(fa) - (gy - 0.5) * np.cos(fa)
        crease = line + 0.3 * blur(qx, size * 0.03) - (p["fold_level"] - 0.5) * 0.6
        under = ramp(stops, 1 - t, p["sharp"])
        if p["depth"] > 0:
            under = add_depth(under, 1 - t, size, p["depth"])
        lab = add_fold(lab, under, crease, size, p["fold"])
    return lab


def shade(lab: np.ndarray, delta: np.ndarray) -> np.ndarray:
    """Changes lightness by `delta`. Where it darkens a yellow, the hue turns
    toward amber as it does in paint, instead of sinking to olive."""
    out = lab.copy()
    out[..., 0] += delta
    dark = np.maximum(-delta, 0)
    if dark.any():
        h = np.degrees(np.arctan2(lab[..., 2], lab[..., 1])) % 360
        c = np.hypot(lab[..., 1], lab[..., 2])
        yellow = np.exp(-(((h - 100) / 30) ** 2)) * np.clip(c / 0.08, 0, 1)
        h = np.radians(h - 220 * dark * yellow)
        c = c * (1 + 1.5 * dark * yellow)
        out[..., 1] = c * np.cos(h)
        out[..., 2] = c * np.sin(h)
    return out


def add_depth(lab: np.ndarray, field: np.ndarray, size: int, amount: float) -> np.ndarray:
    """Broad diffuse light over the field as a surface, lit from the top
    left: each colour body swells a little instead of lying flat."""
    h = blur(field, size * 0.03)
    gy, gx = np.gradient(h)
    gx, gy = gx * size * 0.25, gy * size * 0.25
    nz = 1 / np.sqrt(gx * gx + gy * gy + 1)
    light = np.array([-0.5, -0.6, 0.62], dtype=np.float32)
    light /= np.linalg.norm(light)
    diffuse = (-gx * light[0] - gy * light[1] + light[2]) * nz - light[2]
    lift = amount * 0.12 * diffuse
    # Highlights ease off toward white instead of blowing out.
    room = np.maximum(0.97 - lab[..., 0], 0)
    return shade(lab, np.where(lift > 0, room * np.tanh(lift / np.maximum(room, 1e-3)), lift))


def add_fold(top: np.ndarray, under: np.ndarray, crease: np.ndarray, size: int, strength: float) -> np.ndarray:
    """A sheet of the liquid turned over along the crease: the top sheet on
    one side with a light rim at its edge, the underside on the other with
    a soft shadow falling away from the edge."""
    gy, gx = np.gradient(crease)
    slope = np.sqrt(gx * gx + gy * gy) * size + 1e-6
    d = crease / slope
    over = np.maximum(d, 0)
    top = top.copy()
    top[..., 0] += strength * (0.08 * np.exp(-over / 0.006) + 0.05 * np.exp(-over / 0.06))
    under = shade(under, -strength * 0.16 * np.exp(np.minimum(d, 0) / 0.08))
    aa = 1.5 / size
    alpha = np.clip((d + aa) / (2 * aa), 0, 1)[..., None]
    return under * (1 - alpha) + top * alpha


def silk_params(seed: int) -> dict:
    rng = np.random.default_rng(seed * 104729 + 3)
    return dict(
        angle=float(rng.uniform(-0.9, 0.9) + (np.pi if rng.random() < 0.5 else 0)),
        layers=int(rng.choice([3, 4])),
        freq=float(rng.uniform(0.35, 0.8)),
        amp=float(rng.uniform(0.08, 0.2)),
        spread=float(rng.uniform(0.18, 0.28)),
        shadow=float(rng.uniform(0.10, 0.18)),
        shadow_width=float(rng.uniform(0.05, 0.12)),
        rim=float(rng.uniform(0.05, 0.10)),
        rim_width=float(rng.uniform(0.006, 0.014)),
        soft=float(rng.uniform(0.002, 0.004)),
        curl=float(rng.uniform(0.04, 0.10)),
        sheen=float(rng.uniform(0.02, 0.06)),
        sheen_at=float(rng.uniform(0.08, 0.16)),
    )


def render_silk(palette: str, seed: int, size: int, overrides: dict | None = None) -> np.ndarray:
    p = silk_params(seed)
    p.update(overrides or {})
    rng = np.random.default_rng(seed)
    stops = stops_lab(palette)
    gx, gy = grid(size)
    ca, sa = np.cos(p["angle"]), np.sin(p["angle"])
    u = (gx - 0.5) * ca + (gy - 0.5) * sa
    v = -(gx - 0.5) * sa + (gy - 0.5) * ca
    n = min(p["layers"], len(stops))
    colours = stops[-n:] if n < len(stops) else stops

    # Back layer: its own colour lit gently across the fold direction.
    lab = np.broadcast_to(colours[0], u.shape + (3,)).copy()
    lab[..., 0] += 0.06 * np.tanh(v * 2)

    start = -p["spread"] * (n - 2) / 2 - 0.05
    for i in range(1, n):
        phase = rng.uniform(0, 2 * np.pi)
        phase2 = rng.uniform(0, 2 * np.pi)
        w = 2 * np.pi * p["freq"] * rng.uniform(0.8, 1.2)
        amp = p["amp"] * rng.uniform(0.7, 1.2)
        c = start + p["spread"] * (i - 1)
        b = c + amp * np.sin(w * u + phase) + amp * 0.3 * np.sin(2.1 * w * u + phase2)
        db = amp * w * np.cos(w * u + phase) + amp * 0.3 * 2.1 * w * np.cos(2.1 * w * u + phase2)
        d = (v - b) / np.sqrt(1 + db * db)
        # Shadow the layer below, strongest at the crease.
        below = d < 0
        fall = np.where(below, np.exp(d / p["shadow_width"]), 0).astype(np.float32)
        lab = shade(lab, -p["shadow"] * fall)
        # This layer: its colour, a light rim at the crease, and a slow
        # rounding into the flat of the cloth.
        fill = np.broadcast_to(colours[i], u.shape + (3,)).copy()
        # The cloth turns away from the light as it leaves the crease, and
        # drifts a little toward the next colour along its length.
        dd = np.maximum(d, 0)
        sheen = np.exp(-(((dd - p["sheen_at"]) / 0.07) ** 2))
        fill[..., 0] += (0.05 * np.tanh(u * 2) - p["curl"] * (1 - np.exp(-dd / 0.22))
                         + 0.03 * np.exp(-dd / 0.05) + p["sheen"] * sheen)
        nxt = colours[min(i + 1, n - 1)] if i + 1 < n else colours[i - 1]
        drift = (0.18 * (0.5 + 0.5 * np.tanh(u * 2.5)))[..., None]
        fill = fill * (1 - drift) + (nxt + (fill - colours[i])) * drift
        rim = np.exp(-np.maximum(d, 0) / p["rim_width"])
        fill[..., 0] += p["rim"] * rim
        aa = 1.2 / size
        alpha = np.clip((d + aa) / (2 * aa), 0, 1)[..., None]
        lab = lab * (1 - alpha) + fill * alpha
    lab = np.stack([blur(lab[..., c], p["soft"] * size) for c in range(3)], axis=-1)
    return lab


FAMILIES = {"liquid": render_liquid, "silk": render_silk}
