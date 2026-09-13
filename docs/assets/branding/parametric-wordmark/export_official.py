#!/usr/bin/env python3
"""Synchronize the adopted wordmark with the official repository asset paths.

Run this after generate.py, then run ../parametric/export_official.py to refresh
the shared four-PNG checksum list. --check compares without writing any files.
Requires requirements.txt. Geometry and type always come from the SVG master.
"""

from __future__ import annotations

import argparse
import io
import json
import math
import sys
import xml.etree.ElementTree as ET
from fractions import Fraction
from pathlib import Path

from PIL import Image

import generate

ROOT = Path(__file__).resolve().parent
BRANDING = ROOT.parent
REPO = ROOT.parents[3]
SOCIAL_SIZE = (1774, 887)
SOCIAL_PIXELS_PER_U = 14
NS = "http://www.w3.org/2000/svg"


def q(value) -> Fraction:
    return Fraction(str(value))


def social_render(master: bytes, params: dict, layout: dict):
    """Place the unchanged SVG at 14 px/u; snap the type origin and baseline."""
    scale = q(SOCIAL_PIXELS_PER_U) / q(params["export"]["pixels_per_u"])
    width, height = (q(value) * scale for value in layout["canvas_px"])
    center_x = (SOCIAL_SIZE[0] - width) / 2
    center_y = (SOCIAL_SIZE[1] - height) / 2
    ox, oy = map(q, layout["origin_u"])
    first_left = (ox + q(layout["glyphs"][0]["ink_bounds_local_u"][0])) * SOCIAL_PIXELS_PER_U
    baseline = (oy + q(layout["baseline_local_u"])) * SOCIAL_PIXELS_PER_U
    # Round ties toward +infinity. Default corrections are +0.5 px on both axes.
    x = q(math.floor(center_x + first_left + q("0.5"))) - first_left
    y = q(math.floor(center_y + baseline + q("0.5"))) - baseline
    if min(x, y) < 0 or x + width > SOCIAL_SIZE[0] or y + height > SOCIAL_SIZE[1]:
        raise ValueError("The complete wordmark canvas must fit inside the social preview.")

    ET.register_namespace("", NS)
    root = ET.Element(f"{{{NS}}}svg", {
        "width": str(SOCIAL_SIZE[0]), "height": str(SOCIAL_SIZE[1]),
        "viewBox": f"0 0 {SOCIAL_SIZE[0]} {SOCIAL_SIZE[1]}",
    })
    # Use an explicit transform: this renderer ignores x/y on nested SVG viewports.
    placed = ET.SubElement(root, f"{{{NS}}}g", {
        "transform": f"translate({float(x)} {float(y)}) scale({float(scale)})",
    })
    placed.extend(list(ET.fromstring(master)))
    transparent = generate.raster(ET.tostring(root, encoding="unicode"))
    if transparent.size != SOCIAL_SIZE:
        raise ValueError("Unexpected social preview dimensions.")
    bounds = [g["ink_bounds_local_u"] for g in layout["glyphs"]]
    type_box = (round(x + first_left),
                math.floor(y + (oy + min(q(b[1]) for b in bounds)) * SOCIAL_PIXELS_PER_U),
                math.ceil(x + (ox + max(q(b[2]) for b in bounds)) * SOCIAL_PIXELS_PER_U),
                round(y + baseline))
    alpha = transparent.getchannel("A").crop(type_box)
    levels = [i for i, count in enumerate(alpha.histogram()) if count]
    if levels != [0, 255]:
        raise ValueError("Social preview type is not on the pixel grid; adjust export parameters.")
    return generate.png_bytes(generate.on_white(transparent)), {
        "size_px": list(SOCIAL_SIZE), "pixels_per_u": SOCIAL_PIXELS_PER_U,
        "master_scale": str(scale), "master_canvas_size_px": [float(width), float(height)],
        "master_canvas_origin_px": [float(x), float(y)],
        "correction_from_exact_center_px": [float(x - center_x), float(y - center_y)],
        "correction_reason": "Round the first glyph ink edge and common baseline to integer pixels, ties upward.",
        "type_alpha_levels_before_white_composite": levels,
        "method": "Render the unchanged SVG inside a 2:1 canvas, then composite onto pure white; no stretching or cropping.",
    }


def exports() -> dict[Path, bytes]:
    if not (REPO / "settings.gradle.kts").is_file():
        raise ValueError("Official export requires a BlendLib checkout at docs/assets/branding/parametric-wordmark.")
    params = generate.parameters(ROOT / "parameters.json")
    expected = generate.generate_all(params)
    stale = [name for name, data in expected.items()
             if not (ROOT / "generated" / name).is_file()
             or (ROOT / "generated" / name).read_bytes() != data]
    if stale:
        raise ValueError("Stale master files; run generate.py first: " + ", ".join(stale))
    master = expected["blendlib-wordmark-master.svg"]
    layout = json.loads(expected["layout.json"])
    transparent_bytes = expected["blendlib-wordmark.png"]
    with Image.open(io.BytesIO(transparent_bytes)) as bitmap:
        if bitmap.mode != "RGBA" or list(bitmap.size) != layout["canvas_px"]:
            raise ValueError("The adopted PNG must be the native transparent SVG render.")
        white_bytes = generate.png_bytes(generate.on_white(bitmap))
    social_bytes, social = social_render(master, params, layout)
    output = {
        BRANDING / "blendlib-wordmark-transparent.png": transparent_bytes,
        BRANDING / "blendlib-wordmark-white.png": white_bytes,
        REPO / "docs/assets/blendlib-logo.png": white_bytes,
        BRANDING / "blendlib-social-preview.png": social_bytes,
    }
    manifest = {
        "status": "official", "adopted_on": "2026-09-13",
        "scope": "Folded B + lendLib wordmark for documentation and social preview.",
        "logical_text": "BlendLib", "outlined_text": "lendLib",
        "master": "parametric-wordmark/generated/blendlib-wordmark-master.svg",
        "master_sha256": generate.sha(master),
        "core_manifest_sha256": generate.sha(expected["manifest.json"]),
        "exporter_sha256": generate.sha(Path(__file__).read_bytes().replace(b"\r\n", b"\n")),
        "engines": json.loads(expected["manifest.json"])["engines"],
        "native_png_size": layout["canvas_px"],
        "transparent_png_method": "Exact byte copy of the transparent master PNG.",
        "white_png_method": "Composite the transparent PNG onto pure white and encode as RGB; exact copy for the documentation alias.",
        "social_preview": social,
        "outputs": {path.relative_to(REPO).as_posix(): {"bytes": len(data), "sha256": generate.sha(data)}
                    for path, data in sorted(output.items())},
    }
    output[BRANDING / "official-wordmark-manifest.json"] = generate.jb(manifest)
    if any(not path.resolve().is_relative_to(REPO) for path in output):
        raise ValueError("An official export path lies outside the BlendLib checkout.")
    return output


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Compare exports without writing")
    args = parser.parse_args()
    try:
        output = exports()
        if args.check:
            stale = [str(path.relative_to(REPO)) for path, data in output.items()
                     if not path.is_file() or path.read_bytes() != data]
            if stale:
                raise ValueError("Missing or stale official wordmark exports: " + ", ".join(stale))
            print(f"PASS: {len(output)} official wordmark files match the adopted SVG; pixel type remains sharp.")
        else:
            for path, data in output.items():
                path.write_bytes(data)
            print(f"Exported {len(output)} official wordmark files. Refresh shared checksums with ../parametric/export_official.py.")
        return 0
    except (OSError, ValueError, KeyError, TypeError) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
