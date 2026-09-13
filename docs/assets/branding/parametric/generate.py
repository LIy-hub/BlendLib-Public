#!/usr/bin/env python3
"""Deterministic BlendLib folded-B construction. Core output: Python 3.10+ only.

python generate.py                 # SVGs, coordinates and manifest
python generate.py --check         # compare every core output without writing
python generate.py --render        # also render proofs; requires PyMuPDF + Pillow
python generate.py --params custom.json --out /path/to/exports
"""

from __future__ import annotations

import argparse
import hashlib
import html
import io
import json
import math
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
SVG_NS = "http://www.w3.org/2000/svg"
INK = "#23272B"
GUIDE = "#087D89"
PAPER = "#FAFAF7"
FONT = "Arial, Helvetica, sans-serif"


def n(value: float) -> str:
    value = round(value, 6)
    return "0" if value == 0 else f"{value:.6f}".rstrip("0").rstrip(".")


def json_bytes(value: object) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + "\n").encode("utf-8")


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def read_parameters(path: Path) -> dict:
    p = json.loads(path.read_text(encoding="utf-8-sig"))
    expected = {
        "geometry": {"stem_u", "counter_run_u", "return_u", "slope_rise", "slope_run", "counter_gap_u"},
        "composition": {"canvas_u", "offset_x_u", "offset_y_u", "clear_space_u"},
        "export": {"unit_px", "graphite", "fold", "background"},
    }
    if not isinstance(p, dict) or set(p) != {"schema_version", *expected} or p["schema_version"] != 1:
        raise ValueError("Expected schema_version 1 and geometry/composition/export sections.")
    for section, keys in expected.items():
        if not isinstance(p[section], dict) or set(p[section]) != keys:
            raise ValueError(f"{section}: required keys are {sorted(keys)}")
        for key, value in p[section].items():
            if key in {"graphite", "fold", "background"}:
                if not isinstance(value, str) or not re.fullmatch(r"#[0-9a-fA-F]{6}", value):
                    raise ValueError(f"{key}: use a six-digit hexadecimal color.")
                p[section][key] = value.upper()
                continue
            if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value):
                raise ValueError(f"{key}: expected a finite number.")
            if not key.startswith("offset_") and value <= 0:
                raise ValueError(f"{key}: must be positive.")
    if len({p["export"][k] for k in ("graphite", "fold", "background")}) != 3:
        raise ValueError("Graphite, fold and background must be distinct colors.")
    return p


def signed_area(points: list[tuple[float, float]]) -> float:
    return sum(a[0] * b[1] - b[0] * a[1] for a, b in zip(points, points[1:] + points[:1])) / 2


def construct(p: dict) -> dict:
    g, c = p["geometry"], p["composition"]
    s, r, d, gap = (g[k] for k in ("stem_u", "counter_run_u", "return_u", "counter_gap_u"))
    m = g["slope_rise"] / g["slope_run"]
    t, h = 2 * m * d, 2 * m * r
    pitch, w, height = h + gap, s + r + d, 2 * t + 2 * h + gap
    a = m * (r + d)
    seam_x = w - pitch / (2 * m)
    notch_x = seam_x + d
    if m * s >= height / 2:
        raise ValueError("Stem projection requires m*s < H/2 so the two left corners remain ordered.")
    if not s < seam_x < s + r < notch_x < w:
        raise ValueError("Fold topology requires s < W-p/(2m) < s+r < W-p/(2m)+d < W. Reduce the gap or adjust the two runs.")
    points = {
        "A": (s, 0), "B": (0, m * s), "C": (0, height - m * s),
        "D": (s, height), "E": (w, height - a), "F": (w, a + pitch),
        "G": (notch_x, a + pitch / 2 + m * d), "H": (w, a + t),
        "I": (w, a), "J": (s, t), "K": (s, t + h), "L": (s + r, t + h / 2),
        "M": (seam_x, a + pitch / 2), "N": (s, t + pitch),
        "O": (s, t + pitch + h), "P": (s + r, t + pitch + h / 2),
        "Q": (s + r, height - m * r),
    }
    loops = {
        "outline": list("AIHGFEDCB"), "upper_counter": list("JLK"),
        "lower_counter": list("NPO"), "upper_fold": list("MIHG"),
        "lower_fold": list("PFEQ"),
    }
    # Verify the edge family independently of SVG output and rounding.
    for name, ids in loops.items():
        poly = [points[i] for i in ids]
        if abs(signed_area(poly)) <= 1e-9:
            raise ValueError(f"Degenerate polygon: {name}")
        for (x1, y1), (x2, y2) in zip(poly, poly[1:] + poly[:1]):
            dx, dy = x2 - x1, y2 - y1
            if abs(dx) > 1e-9 and not math.isclose(abs(dy / dx), m, abs_tol=1e-9):
                raise ValueError(f"Nonconforming edge in {name}")
    canvas = c["canvas_u"]
    ox = (canvas - w) / 2 + c["offset_x_u"]
    oy = (canvas - height) / 2 + c["offset_y_u"]
    margins = {"left": ox, "top": oy, "right": canvas - ox - w, "bottom": canvas - oy - height}
    if min(margins.values()) + 1e-9 < c["clear_space_u"]:
        raise ValueError(f"Canvas violates the {n(c['clear_space_u'])}u clear space: {margins}")
    if any(y < 0 or y > height for x, y in points.values()):
        raise ValueError("Stem/projection combination places a vertex outside the symbol frame.")
    return {
        "units": "u; x increases right, y increases down; local origin at ink bounds upper left",
        "slope": m, "angle_degrees": math.degrees(math.atan(m)),
        "width_u": w, "height_u": height, "band_u": t, "counter_height_u": h,
        "pitch_u": pitch, "canvas_u": canvas, "origin_in_canvas_u": [ox, oy],
        "margins_u": margins, "points_u": points, "loops": loops,
        "counter_area_u2": r * h / 2,
        "ink_area_u2": abs(signed_area([points[i] for i in loops["outline"]])) - r * h,
    }


def path_data(ids: list[str], geo: dict) -> str:
    return "M " + " L ".join(f"{n(geo['points_u'][i][0])} {n(geo['points_u'][i][1])}" for i in ids) + " Z"


def mark(p: dict, geo: dict, prefix: str = "") -> str:
    loops, colors = geo["loops"], p["export"]
    outline = " ".join(path_data(loops[key], geo) for key in ("outline", "upper_counter", "lower_counter"))
    # A continuous dark underlay prevents white seams at the two shared fold edges.
    # Both counters are genuine holes in this compound path, with no white paint.
    return "\n".join([
        f'<path id="{prefix}body" fill="{colors["graphite"]}" fill-rule="evenodd" d="{outline}"/>',
        f'<path id="{prefix}upper-fold" fill="{colors["fold"]}" d="{path_data(loops["upper_fold"], geo)}"/>',
        f'<path id="{prefix}lower-fold" fill="{colors["fold"]}" d="{path_data(loops["lower_fold"], geo)}"/>',
    ])


def svg_head(width: float, height: float, viewbox: str, title: str, desc: str) -> str:
    return (f'<?xml version="1.0" encoding="UTF-8"?>\n'
            f'<svg xmlns="{SVG_NS}" width="{n(width)}" height="{n(height)}" viewBox="{viewbox}" '
            f'role="img" aria-labelledby="title description">\n'
            f'<title id="title">{html.escape(title)}</title>\n'
            f'<desc id="description">{html.escape(desc)}</desc>\n')


def master_svg(p: dict, geo: dict, *, white: bool = False, tight: bool = False) -> str:
    w = geo["width_u"] if tight else geo["canvas_u"]
    h = geo["height_u"] if tight else geo["canvas_u"]
    px = p["export"]["unit_px"]
    start = svg_head(w * px, h * px, f"0 0 {n(w)} {n(h)}", "BlendLib folded B",
                     "Parametric geometric reconstruction of the existing folded B. Two triangular counters and two gray return faces. Filled vector paths; no raster, fonts or filters.")
    if white:
        start += f'<rect width="{n(w)}" height="{n(h)}" fill="{p["export"]["background"]}"/>\n'
    ox, oy = (0, 0) if tight else geo["origin_in_canvas_u"]
    return start + f'<g transform="translate({n(ox)} {n(oy)})">\n' + mark(p, geo) + "\n</g>\n</svg>\n"


def construction_svg(p: dict, geo: dict) -> str:
    items = [svg_head(1600, 1100, "0 0 1600 1100", "BlendLib geometric construction",
                      "Dimensions and vertices derive from the same parameters as the SVG master. Teal construction guides are not part of the logo.")]

    def add(s: str) -> None:
        items.append(s)

    def text(x, y, content, size=18, color=INK, weight=400, anchor="start"):
        add(f'<text x="{n(x)}" y="{n(y)}" fill="{color}" font-family="{FONT}" font-size="{n(size)}" font-weight="{weight}" text-anchor="{anchor}">{html.escape(content)}</text>')

    def line(x1, y1, x2, y2, color=GUIDE, width=1, dash=""):
        extra = f' stroke-dasharray="{dash}"' if dash else ""
        add(f'<line x1="{n(x1)}" y1="{n(y1)}" x2="{n(x2)}" y2="{n(y2)}" stroke="{color}" stroke-width="{n(width)}"{extra}/>')

    def dim_h(x1, x2, y, label, extension_y):
        for x in (x1, x2):
            line(x, y - 5, x, extension_y, "#88B2B7", .75)
            line(x - 4, y + 5, x + 4, y - 5)
        line(x1, y, x2, y)
        text((x1 + x2) / 2, y - 10, label, 17, GUIDE, anchor="middle")

    def dim_v(x, y1, y2, label, extension_x, *, right=False):
        for y in (y1, y2):
            line(x - 5, y, extension_x, y, "#88B2B7", .75)
            line(x - 5, y + 4, x + 5, y - 4)
        line(x, y1, x, y2)
        text(x + (10 if right else -10), (y1 + y2) / 2 + 6, label, 17, GUIDE, anchor="start" if right else "end")

    add(f'<rect width="1600" height="1100" fill="{PAPER}"/>')
    text(64, 54, "BLENDLIB  /  IDENTITY ENGINEERING", 16, GUIDE, 600)
    text(64, 111, "BlendLib / geometric construction", 42, INK, 600)
    text(64, 149, "Shared vertices. Equal counters. One family of diagonal edges.", 19, "#687176")
    text(1536, 54, "GEOMETRIC MASTER  /  01", 15, "#687176", anchor="end")
    line(64, 177, 1536, 177, "#CCD2D2")
    line(1008, 212, 1008, 974, "#D8DDDB")

    w, h, m = geo["width_u"], geo["height_u"], geo["slope"]
    scale = min(28, 588 / h, 432 / w)
    ox, oy = 518 - w * scale / 2, 280
    sx = lambda value: ox + value * scale
    sy = lambda value: oy + value * scale
    points = geo["points_u"]
    # Clip the grid mathematically; no reliance on renderer-specific clipPath support.
    def diagonal_grid(y0, slope):
        left, right, bottom, top = -2, w + 2, -1, h + 1
        if slope > 0:
            lo = max(left, left + (bottom - y0) / slope)
            hi = min(right, left + (top - y0) / slope)
        else:
            lo = max(left, left + (top - y0) / slope)
            hi = min(right, left + (bottom - y0) / slope)
        if lo < hi:
            line(sx(lo), sy(y0 + slope * (lo-left)), sx(hi), sy(y0 + slope * (hi-left)), "#CDDFDC", .55)
    for i in range(-4, math.ceil((w + 2) * 2) + 1):
        x = i / 2
        if x <= w + 2:
            line(sx(x), sy(-1), sx(x), sy(h + 1), "#DDE9E6", .7 if i % 2 == 0 else .35)
    for i in range(-2, math.ceil((h + 1) * 2) + 1):
        y = i / 2
        if y <= h + 1:
            line(sx(-2), sy(y), sx(w + 2), sy(y), "#DDE9E6", .7 if i % 2 == 0 else .35)
    for i in range(-math.ceil(m * (w + 4)) - 2, math.ceil(h + m * (w + 4)) + 3, 2):
        diagonal_grid(i, m)
        diagonal_grid(i, -m)
    add(f'<g transform="translate({n(ox)} {n(oy)}) scale({n(scale)})">{mark(p, geo, "diagram-")}</g>')
    add(f'<rect x="{n(ox)}" y="{n(oy)}" width="{n(w*scale)}" height="{n(h*scale)}" fill="none" stroke="#88B2B7" stroke-dasharray="4 5" stroke-width=".8"/>')
    s, r, d = (p["geometry"][key] for key in ("stem_u", "counter_run_u", "return_u"))
    dim_h(sx(0), sx(w), 222, f"W = {n(w)}u", oy)
    for left, right, label in ((0, s, f"s = {n(s)}u"), (s, s + r, f"r = {n(r)}u"), (s + r, w, f"d = {n(d)}u")):
        dim_h(sx(left), sx(right), 255, label, oy)
    dim_v(sx(-3), sy(0), sy(h), f"H = {n(h)}u", sx(0))
    for a, b in (("J", "K"), ("N", "O")):
        dim_v(sx(w + 3.1), sy(points[a][1]), sy(points[b][1]), f"h = {n(geo['counter_height_u'])}u", sx(w + .9), right=True)
    dim_v(sx(w + 5), sy(points["K"][1]), sy(points["N"][1]), f"{n(p['geometry']['counter_gap_u'])}u", sx(w + .9), right=True)
    offsets = {"A": (0, -12), "B": (-17, 0), "C": (-17, 6), "D": (0, 25),
               "E": (17, 6), "F": (17, -4), "G": (16, 5), "H": (17, 6),
               "I": (17, -6), "J": (-15, -3), "K": (-15, 4), "L": (0, -13),
               "M": (-12, -12), "N": (-15, 3), "O": (-15, 5), "P": (-11, -10), "Q": (-13, 13)}
    for key, (x, y) in points.items():
        px, py = sx(x), sy(y)
        add(f'<circle cx="{n(px)}" cy="{n(py)}" r="3.2" fill="{PAPER}" stroke="{GUIDE}" stroke-width="1.25"/>')
        dx, dy = offsets[key]
        text(px + dx, py + dy, key, 15, GUIDE, 600, "middle")
    text(ox, 935, f"Major grid = 1u   /   subdivisions = 0.5u   /   m = {n(m)}", 16, "#687176")
    text(ox, 961, "Construction guides are excluded from the master.", 16, "#687176")

    x = 1056
    text(x, 240, "01  /  UNIT & PROPORTIONS", 15, GUIDE, 600)
    text(x, 288, f"u = {n(p['export']['unit_px'])} px", 35, INK, 600)
    text(x, 317, "Export scale; the vector geometry is unit based.", 16, "#687176")
    rows = [("Symbol frame", f"{n(w)}u x {n(h)}u"),
            ("Stem / depth run", f"{n(s)}u / {n(d)}u"),
            ("Counter / gap", f"{n(r)}u x {n(geo['counter_height_u'])}u / {n(p['geometry']['counter_gap_u'])}u"),
            ("Repeated pitch", f"{n(geo['pitch_u'])}u"),
            ("Edge directions", f"+/- {geo['angle_degrees']:.3f} deg / 90 deg")]
    for i, (key, value) in enumerate(rows):
        y = 365 + i * 36
        text(x, y, key, 16, "#687176")
        text(1532, y, value, 17, INK, 600, "end")
    line(x, 536, 1536, 536, "#CCD2D2")
    text(x, 570, "02  /  DEPENDENT DIMENSIONS", 15, GUIDE, 600)
    for i, formula in enumerate((f"t = 2md = {n(geo['band_u'])}u", f"h = 2mr = {n(geo['counter_height_u'])}u",
                                  f"p = h + g = {n(geo['pitch_u'])}u", f"W = s + r + d = {n(w)}u",
                                  f"H = 2t + 2h + g = {n(h)}u")):
        text(x, 606 + i * 29, formula, 19, INK)
    text(x, 752, "03  /  CANVAS & PALETTE", 15, GUIDE, 600)
    canvas, thumb = geo["canvas_u"], 150
    tx, ty, ts = x, 778, thumb / geo["canvas_u"]
    add(f'<rect x="{tx}" y="{ty}" width="{thumb}" height="{thumb}" fill="#FFFFFF" stroke="#B4C5C5"/>')
    cx, cy = geo["origin_in_canvas_u"]
    add(f'<g transform="translate({n(tx+cx*ts)} {n(ty+cy*ts)}) scale({n(ts)})">{mark(p, geo, "frame-")}</g>')
    pad = p["composition"]["clear_space_u"]
    add(f'<rect x="{n(tx+(cx-pad)*ts)}" y="{n(ty+(cy-pad)*ts)}" width="{n((w+2*pad)*ts)}" height="{n((h+2*pad)*ts)}" fill="none" stroke="{GUIDE}" stroke-dasharray="3 3" stroke-width=".8"/>')
    for i, label in enumerate((f"Canvas: {n(canvas)}u x {n(canvas)}u", f"Clear space: {n(pad)}u minimum",
                               f"Offset: ({n(p['composition']['offset_x_u'])}, {n(p['composition']['offset_y_u'])})u",
                               "Relative to centered ink bounds.")):
        text(x + 174, 802 + i * 27, label, 16, "#687176" if i == 3 else INK)
    for i, key in enumerate(("graphite", "fold")):
        color = p["export"][key]
        dx = x + i * 235
        add(f'<rect x="{dx}" y="949" width="22" height="22" fill="{color}"/>')
        text(dx + 32, 966, color + " / " + key, 16)
    line(64, 1001, 1536, 1001, "#CCD2D2")
    text(64, 1040, "Reference: existing BlendLib white icon  /  normalized reconstruction, not a pixel-identical trace.", 16, "#687176")
    text(1536, 1040, "PARAMETERS.JSON  ->  GENERATE.PY", 15, GUIDE, 600, "end")
    add('</svg>\n')
    return "\n".join(items)


def core_outputs(p: dict, geo: dict, source: dict) -> dict[str, bytes]:
    outputs = {
        "blendlib-icon-master.svg": master_svg(p, geo).encode(),
        "blendlib-icon-white.svg": master_svg(p, geo, white=True).encode(),
        "blendlib-symbol.svg": master_svg(p, geo, tight=True).encode(),
        "construction.svg": construction_svg(p, geo).encode(),
        "geometry.json": json_bytes(geo),
    }
    manifest = {
        "schema_version": 1,
        "source": source,
        "parameters_sha256": sha(json_bytes(p)),
        "generator_sha256": sha(Path(__file__).read_bytes().replace(b"\r\n", b"\n")),
        "source_of_truth": "parameters.json + generate.py; generated SVGs are not independent inputs",
        "geometry_checks": {"edge_family": "PASS", "fold_topology": "PASS", "clear_space": "PASS", "counter_count": 2},
        "outputs": {name: {"bytes": len(data), "sha256": sha(data)} for name, data in sorted(outputs.items())},
    }
    outputs["manifest.json"] = json_bytes(manifest)
    outputs["SHA256SUMS"] = "".join(f"{sha(data)}  {name}\n" for name, data in sorted(outputs.items())).encode()
    return outputs


def render_proofs(out: Path, p: dict, geo: dict, source_path: Path) -> None:
    try:
        import fitz
        import PIL
        from PIL import Image, ImageDraw, ImageFont
    except ImportError as exc:
        raise ValueError("Optional proof rendering requires PyMuPDF and Pillow. Core SVG generation has no dependencies.") from exc

    def raster(svg: bytes, width: int, alpha: bool = False):
        with fitz.open(stream=svg, filetype="svg") as doc:
            page = doc[0]
            pix = page.get_pixmap(matrix=fitz.Matrix(width / page.rect.width, width / page.rect.width), alpha=alpha)
            return Image.open(io.BytesIO(pix.tobytes("png"))).convert("RGBA" if alpha else "RGB")

    # Proof text uses Pillow's bundled font, avoiding an OS-dependent font path.
    def font(size):
        return ImageFont.load_default(size=size)

    preview = raster((out / "blendlib-icon-white.svg").read_bytes(), 768)
    preview.save(out / "preview.png")
    transparent = raster((out / "blendlib-icon-master.svg").read_bytes(), 768, True)
    transparent.save(out / "preview-transparent.png")
    raster((out / "construction.svg").read_bytes(), 1600).save(out / "construction.png")
    sheet = Image.new("RGB", (1600, 1140), PAPER)
    draw = ImageDraw.Draw(sheet)
    draw.text((64, 40), "BLENDLIB / GEOMETRIC RECONSTRUCTION", fill=GUIDE, font=font(18))
    draw.text((64, 80), "Folded B / source and reconstruction", fill=INK, font=font(37))
    draw.line((64, 146, 1536, 146), fill="#CCD2D2", width=1)
    source = Image.open(source_path).convert("RGB")
    for x, title, description, bitmap in ((64, "01 / EXISTING ICON", "Original white PNG / original framing", source),
                                           (840, "02 / SVG MASTER", "Shared geometry / flat graphite and gray", preview)):
        draw.text((x, 173), title, fill=GUIDE, font=font(18))
        draw.rectangle((x, 211, x + 696, 843), fill="#FFFFFF")
        tile = bitmap.resize((608, 608), Image.Resampling.LANCZOS)
        sheet.paste(tile, (x + 44, 223))
        draw.text((x, 860), description, fill="#687176", font=font(17))
    draw.line((64, 905, 1536, 905), fill="#CCD2D2", width=1)
    draw.text((64, 930), "NATIVE PIXEL SIZES / SAME MASTER", fill=GUIDE, font=font(17))
    raw = (out / "blendlib-icon-master.svg").read_bytes()
    left = 64
    for size in (16, 24, 32, 48, 64, 128):
        icon = raster(raw, size, True)
        draw.rectangle((left, 963, left + 152, 1110), fill="#FFFFFF")
        sheet.paste(icon, (left + (152 - size) // 2, 968 + (128 - size) // 2), icon)
        draw.text((left + 7, 1117), f"{size}px", fill="#687176", font=font(14))
        left += 174
    bg = Image.new("RGBA", (144, 144), "#E7EEF0")
    bg.alpha_composite(raster(raw, 144, True))
    sheet.paste(bg.convert("RGB"), (1232, 964))
    draw.text((1395, 990), "True alpha", fill=INK, font=font(17))
    draw.text((1395, 1016), "in both", fill="#687176", font=font(17))
    draw.text((1395, 1042), "counters", fill="#687176", font=font(17))
    sheet.save(out / "comparison.png")

    # Inspect real SVG rasterization, not a separately redrawn approximation.
    alpha = transparent.getchannel("A")
    ratio = 768 / geo["canvas_u"]
    ox, oy = geo["origin_in_canvas_u"]
    samples = {}
    for name in ("upper_counter", "lower_counter"):
        vertices = [geo["points_u"][key] for key in geo["loops"][name]]
        x = ox + sum(v[0] for v in vertices) / 3
        y = oy + sum(v[1] for v in vertices) / 3
        samples[name] = alpha.getpixel((round(x * ratio), round(y * ratio)))
    if samples != {"upper_counter": 0, "lower_counter": 0}:
        raise ValueError(f"Rasterized counters are not transparent: {samples}")
    background = Image.new("RGBA", transparent.size, p["export"]["background"])
    background.alpha_composite(transparent)
    from PIL import ImageChops
    diff = ImageChops.difference(background.convert("RGB"), preview)
    extrema = [pair[1] for pair in diff.getextrema()]
    if max(extrema) > 2:
        raise ValueError(f"White/transparent render mismatch exceeds two 8-bit levels: {extrema}")
    proofs = ("preview.png", "preview-transparent.png", "construction.png", "comparison.png")
    report = {
        "renderer": {"PyMuPDF": fitz.VersionBind, "Pillow": PIL.__version__},
        "source_sha256": sha(source_path.read_bytes()),
        "core_manifest_sha256": sha((out / "manifest.json").read_bytes()),
        "counter_alpha_at_centroids": samples,
        "white_vs_composited_alpha_max_channel_difference": extrema,
        "pixel_sizes_rendered": [16, 24, 32, 48, 64, 128],
        "scope": "SVG raster and alpha checks; no application integration or owner approval implied",
        "outputs": {name: sha((out / name).read_bytes()) for name in proofs},
    }
    (out / "proof-report.json").write_bytes(json_bytes(report))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--params", type=Path, default=ROOT / "parameters.json")
    parser.add_argument("--out", type=Path, default=ROOT / "generated")
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--check", action="store_true", help="Read-only comparison of all deterministic core outputs")
    mode.add_argument("--render", action="store_true", help="Also render PNG proofs using optional PyMuPDF and Pillow")
    args = parser.parse_args()
    try:
        p = read_parameters(args.params)
        geo = construct(p)
        reference = json.loads((ROOT / "reference" / "source.json").read_text(encoding="utf-8"))
        source_path = ROOT / "reference" / "blendlib-icon-white.png"
        if sha(source_path.read_bytes()) != reference["sha256"]:
            raise ValueError("Reference PNG SHA-256 differs from the recorded source.")
        outputs = core_outputs(p, geo, reference)
        if args.check:
            failures = [name for name, data in outputs.items() if not (args.out / name).is_file() or (args.out / name).read_bytes() != data]
            if failures:
                raise ValueError("Missing or stale output(s): " + ", ".join(failures))
            print(f"PASS: {len(outputs)} deterministic core outputs; geometry and reference checks passed.")
            return 0
        # Only the generator's named files are written; output directories are never cleared.
        args.out.mkdir(parents=True, exist_ok=True)
        for name, data in outputs.items():
            (args.out / name).write_bytes(data)
        if args.render:
            render_proofs(args.out, p, geo, source_path)
        print(f"Generated {len(outputs)} core outputs" + (" and 5 proof files" if args.render else "") + f" in {args.out.resolve()}")
        return 0
    except (OSError, ValueError, TypeError, KeyError) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
