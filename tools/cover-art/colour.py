"""Colour maths: sRGB <-> linear <-> OKLab / OKLCH, all on float32 arrays."""
import numpy as np


def hex_to_srgb(code: str) -> np.ndarray:
    code = code.lstrip("#")
    return np.array([int(code[i:i + 2], 16) / 255.0 for i in (0, 2, 4)], dtype=np.float32)


def srgb_to_linear(c: np.ndarray) -> np.ndarray:
    return np.where(c <= 0.04045, c / 12.92, ((c + 0.055) / 1.055) ** 2.4).astype(np.float32)


def linear_to_srgb(c: np.ndarray) -> np.ndarray:
    c = np.clip(c, 0.0, 1.0)
    return np.where(c <= 0.0031308, c * 12.92, 1.055 * np.power(c, 1 / 2.4) - 0.055).astype(np.float32)


_M1 = np.array([[0.4122214708, 0.5363325363, 0.0514459929],
                [0.2119034982, 0.6806995451, 0.1073969566],
                [0.0883024619, 0.2817188376, 0.6299787005]], dtype=np.float32)
_M2 = np.array([[0.2104542553, 0.7936177850, -0.0040720468],
                [1.9779984951, -2.4285922050, 0.4505937099],
                [0.0259040371, 0.7827717662, -0.8086757660]], dtype=np.float32)
_M2_INV = np.linalg.inv(_M2.astype(np.float64)).astype(np.float32)
_M1_INV = np.linalg.inv(_M1.astype(np.float64)).astype(np.float32)


def linear_to_oklab(c: np.ndarray) -> np.ndarray:
    lms = c @ _M1.T
    return np.cbrt(lms) @ _M2.T


def oklab_to_linear(lab: np.ndarray) -> np.ndarray:
    lms = lab @ _M2_INV.T
    return (lms ** 3) @ _M1_INV.T


def hex_to_oklab(code: str) -> np.ndarray:
    return linear_to_oklab(srgb_to_linear(hex_to_srgb(code)))


def oklab_to_srgb(lab: np.ndarray) -> np.ndarray:
    return linear_to_srgb(gamut_clip(oklab_to_linear(lab), lab))


def gamut_clip(lin: np.ndarray, lab: np.ndarray) -> np.ndarray:
    """Pulls out-of-gamut colours toward grey of the same lightness instead of
    clipping each channel, so vivid edges do not shift hue."""
    over = (lin.min(axis=-1) < 0) | (lin.max(axis=-1) > 1)
    if not over.any():
        return lin
    lab_o = lab[over]
    lo, hi = np.zeros(len(lab_o), np.float32), np.ones(len(lab_o), np.float32)
    for _ in range(12):
        mid = (lo + hi) / 2
        test = lab_o.copy()
        test[:, 1:] *= mid[:, None]
        rgb = oklab_to_linear(test)
        ok = (rgb.min(axis=-1) >= -1e-4) & (rgb.max(axis=-1) <= 1 + 1e-4)
        lo = np.where(ok, mid, lo)
        hi = np.where(ok, hi, mid)
    fixed = lab_o.copy()
    fixed[:, 1:] *= lo[:, None]
    lin = lin.copy()
    lin[over] = oklab_to_linear(fixed)
    return lin


def oklch(lab: np.ndarray) -> np.ndarray:
    c = np.hypot(lab[..., 1], lab[..., 2])
    h = np.degrees(np.arctan2(lab[..., 2], lab[..., 1])) % 360
    return np.stack([lab[..., 0], c, h], axis=-1)


def relative_luminance(srgb: np.ndarray) -> np.ndarray:
    lin = srgb_to_linear(srgb)
    return lin @ np.array([0.2126, 0.7152, 0.0722], dtype=np.float32)
