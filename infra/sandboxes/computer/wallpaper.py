#!/usr/bin/env python3
"""Renders the computer's wallpapers: a soft fabric fold over a quiet
gradient, in three lights (day, noon, night). Standard library only, so it
runs inside the image build.

    wallpaper.py OUT_DIR [WIDTH HEIGHT]
"""
import math
import os
import random
import struct
import sys
import zlib

# lo → hi tone ramps per time of day.
PALETTES = {
    "day": ((196, 197, 201), (250, 250, 251)),
    "noon": ((122, 123, 128), (226, 227, 230)),
    "night": ((8, 8, 10), (112, 112, 118)),
}


def smoothstep(x):
    x = min(max(x, 0.0), 1.0)
    return x * x * (3 - 2 * x)


def shade(u, v, aspect):
    """Brightness in [0, 1] at (u, v), both in [0, 1]."""
    # The fold's ridge sweeps from the top left down towards the bottom middle.
    ridge = 0.16 + 0.40 * smoothstep(v * 0.95 + 0.05) - 0.05 * math.sin(v * math.pi)
    d = (u - ridge) * aspect
    if d < 0:  # the lit face rises towards the ridge
        fold = 0.22 * math.exp(d / 0.22) + 0.30 * math.exp(-(d / 0.018) ** 2)
    else:  # the shadow falls away behind it, then the cloth lifts again
        fold = -0.30 * math.exp(-d / 0.10) + 0.10 * (1 - math.exp(-d / 0.5)) + 0.30 * math.exp(-(d / 0.010) ** 2)
    # A second, gentler fold further right.
    ridge2 = 0.70 + 0.22 * smoothstep(v) - 0.08 * math.sin(v * math.pi * 0.8)
    d2 = (u - ridge2) * aspect
    fold += 0.07 * math.exp(-(d2 / 0.08) ** 2) - 0.05 * math.exp(-((d2 - 0.12) / 0.10) ** 2)
    # Light from the lower right, falling off to the corners.
    glow = 0.35 * math.exp(-(((u - 0.86) * aspect) ** 2 + (v - 0.80) ** 2) / 0.45)
    vignette = -0.12 * ((u - 0.5) ** 2 * 2 + (v - 0.5) ** 2 * 2)
    return min(max(0.26 + fold + glow + vignette, 0.0), 1.0)


def render(width, height, palette, seed=7):
    rnd = random.Random(seed)
    (lr, lg, lb), (hr, hg, hb) = palette
    aspect = width / height
    rows = []
    for y in range(height):
        v = y / (height - 1)
        row = bytearray(b"\x00")  # PNG filter: none
        for x in range(width):
            t = shade(x / (width - 1), v, aspect)
            n = rnd.random() - 0.5  # dither so the gradient doesn't band
            row += bytes((
                min(255, max(0, int(lr + (hr - lr) * t + n))),
                min(255, max(0, int(lg + (hg - lg) * t + n))),
                min(255, max(0, int(lb + (hb - lb) * t + n))),
            ))
        rows.append(bytes(row))
    return b"".join(rows)


def png(width, height, raw):
    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)
    return (b"\x89PNG\r\n\x1a\n"
            + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw, 9))
            + chunk(b"IEND", b""))


def main():
    out = sys.argv[1]
    width, height = (int(sys.argv[2]), int(sys.argv[3])) if len(sys.argv) > 3 else (1280, 800)
    os.makedirs(out, exist_ok=True)
    for name, palette in PALETTES.items():
        with open(os.path.join(out, f"wallpaper-{name}.png"), "wb") as f:
            f.write(png(width, height, render(width, height, palette)))


if __name__ == "__main__":
    main()
