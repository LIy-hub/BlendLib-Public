#!/usr/bin/env python3
"""Reproduce the two original 8x8, opaque PNG pixel patterns (Python standard library only)."""
from pathlib import Path
import struct
import zlib

ROOT = Path(__file__).resolve().parents[1]
DEST = ROOT / "src/main/resources/assets/blendlib_runnable_examples/textures/skins"
# Hand-authored tiles: bright cross highlights over alternating colored bands.
ROWS = ("00111000", "01121100", "11222110", "12222211",
        "11222110", "01121100", "00111000", "00000000")
PALETTES = {
    "ember": ((104, 34, 13), (231, 101, 28), (255, 227, 137)),
    "frost": ((15, 55, 101), (47, 172, 210), (215, 255, 255)),
}


def chunk(kind, data):
    return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))


def png(palette):
    pixels = b"".join(b"\x00" + b"".join(bytes((*palette[int(c)], 255)) for c in row) for row in ROWS)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">2I5B", 8, 8, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(pixels, 9)) + chunk(b"IEND", b""))


if __name__ == "__main__":
    DEST.mkdir(parents=True, exist_ok=True)
    for name, palette in PALETTES.items():
        (DEST / f"{name}.png").write_bytes(png(palette))
