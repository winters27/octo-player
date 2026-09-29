"""Smooth gradient noise, fractal sums and blurs on float32 numpy grids."""
import numpy as np


class Noise:
    """2D gradient noise with a quintic fade, so the field and its slope are
    continuous (no creases for the lighting to catch)."""

    def __init__(self, seed: int):
        rng = np.random.default_rng(seed)
        perm = rng.permutation(256)
        self.perm = np.concatenate([perm, perm]).astype(np.int32)
        angles = rng.uniform(0, 2 * np.pi, 256)
        self.gx = np.cos(angles).astype(np.float32)
        self.gy = np.sin(angles).astype(np.float32)

    def __call__(self, x: np.ndarray, y: np.ndarray) -> np.ndarray:
        x0f = np.floor(x)
        y0f = np.floor(y)
        fx = (x - x0f).astype(np.float32)
        fy = (y - y0f).astype(np.float32)
        xi = x0f.astype(np.int64) & 255
        yi = y0f.astype(np.int64) & 255
        p = self.perm
        a = p[xi] + yi
        b = p[xi + 1] + yi
        h00, h01 = p[a], p[a + 1]
        h10, h11 = p[b], p[b + 1]

        def dot(h, dx, dy):
            return self.gx[h] * dx + self.gy[h] * dy

        n00 = dot(h00, fx, fy)
        n10 = dot(h10, fx - 1, fy)
        n01 = dot(h01, fx, fy - 1)
        n11 = dot(h11, fx - 1, fy - 1)
        u = fx * fx * fx * (fx * (fx * 6 - 15) + 10)
        v = fy * fy * fy * (fy * (fy * 6 - 15) + 10)
        nx0 = n00 + u * (n10 - n00)
        nx1 = n01 + u * (n11 - n01)
        return (nx0 + v * (nx1 - nx0)) * np.float32(1.4)


_ROT = np.array([[0.8, 0.6], [-0.6, 0.8]], dtype=np.float32)


def fbm(noise: Noise, x, y, octaves=4, gain=0.42):
    """Fractal sum with each octave rotated, so no grid direction shows."""
    total = np.zeros_like(x, dtype=np.float32)
    amp = np.float32(0.5)
    norm = 0.0
    for _ in range(octaves):
        total += amp * noise(x, y)
        norm += amp
        x, y = (_ROT[0, 0] * x + _ROT[0, 1] * y) * 2.02, (_ROT[1, 0] * x + _ROT[1, 1] * y) * 2.02
        amp *= gain
    return total / np.float32(norm)


def _box(a: np.ndarray, r: int, axis: int) -> np.ndarray:
    if r < 1:
        return a
    pad = [(0, 0)] * a.ndim
    pad[axis] = (r + 1, r)
    padded = np.pad(a, pad, mode="reflect")
    c = np.cumsum(padded, axis=axis, dtype=np.float64)
    n = a.shape[axis]
    hi = np.take(c, np.arange(2 * r + 1, 2 * r + 1 + n), axis=axis)
    lo = np.take(c, np.arange(0, n), axis=axis)
    return ((hi - lo) / (2 * r + 1)).astype(np.float32)


def blur(a: np.ndarray, sigma: float) -> np.ndarray:
    """Gaussian blur approximated by three box passes per axis."""
    if sigma < 0.5:
        return a
    r = int(round((np.sqrt(12 * sigma * sigma / 3 + 1) - 1) / 2))
    for axis in (0, 1):
        for _ in range(3):
            a = _box(a, r, axis)
    return a
