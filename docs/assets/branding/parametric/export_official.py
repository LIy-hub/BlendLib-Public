#!/usr/bin/env python3
"""Export the adopted SVG to the existing official PNG and Fabric resource paths.

Run from a BlendLib checkout: python export_official.py [--check]
Requires the pinned packages in requirements-preview.txt. Does not modify SVGs.
"""

from __future__ import annotations

import argparse
import io
import json
import sys
from pathlib import Path

import generate

ROOT = Path(__file__).resolve().parent
REPO = ROOT.parents[3]
BRANDING = ROOT.parent
SIZE = 1254


def exports() -> dict[Path, bytes]:
    import fitz
    import PIL
    from PIL import Image

    if not (REPO / "settings.gradle.kts").is_file():
        raise ValueError("Official export must run from docs/assets/branding/parametric in a BlendLib checkout.")
    params = generate.read_parameters(ROOT / "parameters.json")
    geo = generate.construct(params)
    source = json.loads((ROOT / "reference" / "source.json").read_text(encoding="utf-8"))
    if generate.sha((ROOT / "reference" / source["reference"]).read_bytes()) != source["sha256"]:
        raise ValueError("The archived source PNG differs from its recorded checksum.")
    expected_core = generate.core_outputs(params, geo, source)
    for name, expected in expected_core.items():
        actual = ROOT / "generated" / name
        if not actual.is_file() or actual.read_bytes() != expected:
            raise ValueError(f"Stale vector source: {name}. Run generate.py before exporting official icons.")

    with fitz.open(stream=expected_core["blendlib-icon-master.svg"], filetype="svg") as document:
        page = document[0]
        scale = SIZE / page.rect.width
        pixmap = page.get_pixmap(matrix=fitz.Matrix(scale, scale), alpha=True)
        transparent = Image.open(io.BytesIO(pixmap.tobytes("png"))).convert("RGBA")
    if transparent.size != (SIZE, SIZE):
        raise ValueError(f"Unexpected raster dimensions: {transparent.size}")
    white = Image.new("RGBA", transparent.size, params["export"]["background"])
    white.alpha_composite(transparent)
    white = white.convert("RGB")

    ox, oy = geo["origin_in_canvas_u"]
    ratio = SIZE / geo["canvas_u"]
    alpha = transparent.getchannel("A")
    for counter in ("upper_counter", "lower_counter"):
        points = [geo["points_u"][key] for key in geo["loops"][counter]]
        x = round((ox + sum(v[0] for v in points) / 3) * ratio)
        y = round((oy + sum(v[1] for v in points) / 3) * ratio)
        if alpha.getpixel((x, y)) != 0:
            raise ValueError(f"The {counter} is not transparent.")

    def png(image) -> bytes:
        buffer = io.BytesIO()
        image.save(buffer, format="PNG", compress_level=9)
        return buffer.getvalue()

    white_bytes = png(white)
    output = {
        BRANDING / "blendlib-icon-transparent.png": png(transparent),
        BRANDING / "blendlib-icon-white.png": white_bytes,
        REPO / "blendlib-fabric-client/src/main/resources/assets/blendlib/icon.png": white_bytes,
    }
    official_png_names = (
        "blendlib-icon-transparent.png", "blendlib-icon-white.png",
        "blendlib-wordmark-transparent.png", "blendlib-wordmark-white.png",
    )
    checksums = []
    for name in official_png_names:
        path = BRANDING / name
        data = output[path] if path in output else path.read_bytes()
        checksums.append(f"{generate.sha(data)}  {name}\n")
    output[BRANDING / "SHA256SUMS"] = "".join(checksums).encode("utf-8")
    manifest = {
        "status": "official",
        "adopted_on": "2026-09-13",
        "scope": "Standalone folded B icon; the outlined wordmark has its own SVG master and export workflow.",
        "master": "parametric/generated/blendlib-icon-master.svg",
        "master_sha256": generate.sha(expected_core["blendlib-icon-master.svg"]),
        "core_manifest_sha256": generate.sha(expected_core["manifest.json"]),
        "exporter_sha256": generate.sha(Path(__file__).read_bytes().replace(b"\r\n", b"\n")),
        "renderer": {"PyMuPDF": fitz.VersionBind, "Pillow": PIL.__version__},
        "png_size": [SIZE, SIZE],
        "white_png_method": "Composite the transparent master render onto the configured background; encode as RGB.",
        "outputs": {
            path.relative_to(REPO).as_posix(): {"bytes": len(data), "sha256": generate.sha(data)}
            for path, data in sorted(output.items())
        },
    }
    output[BRANDING / "official-icon-manifest.json"] = generate.json_bytes(manifest)
    if any(not path.resolve().is_relative_to(REPO) for path in output):
        raise ValueError("An export path lies outside the BlendLib checkout.")
    return output


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Compare exports without writing")
    args = parser.parse_args()
    try:
        output = exports()
        if args.check:
            stale = [str(p.relative_to(REPO)) for p, data in output.items() if not p.is_file() or p.read_bytes() != data]
            if stale:
                raise ValueError("Missing or stale official exports: " + ", ".join(stale))
            print(f"PASS: {len(output)} official export files match the adopted SVG.")
        else:
            for path, data in output.items():
                path.write_bytes(data)
            print(f"Exported {SIZE} x {SIZE} official icons, Fabric resource and checksums.")
        return 0
    except (OSError, ValueError, ImportError) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
