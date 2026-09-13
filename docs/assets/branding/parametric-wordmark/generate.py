#!/usr/bin/env python3
"""Shape the provided font, outline it, and generate BlendLib SVG/PNG specifications.

python generate.py
python generate.py --check
python generate.py --params custom.json --out custom-output
"""

from __future__ import annotations

import argparse
import csv
from fractions import Fraction as F
import hashlib
import html
import io
import json
import math
from pathlib import Path
import re
import sys

import fitz
import fontTools
from fontTools.ttLib import TTFont
from fontTools.pens.boundsPen import BoundsPen
from fontTools.pens.recordingPen import RecordingPen
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
import PIL
from PIL import Image, ImageDraw, ImageFont, ImageChops
import uharfbuzz as hb

import geometry
from geometry import number as q, fmt as n

ROOT = Path(__file__).resolve().parent
ASSETS = ROOT / "assets"
NS = "http://www.w3.org/2000/svg"
PAPER, INK, MUTED, GUIDE = "#FAFAF7", "#292D31", "#657075", "#087D89"


def clean(value):
    if isinstance(value, F):
        return value.numerator if value.denominator == 1 else round(float(value), 10)
    if isinstance(value, dict):
        return {key: clean(v) for key, v in value.items()}
    if isinstance(value, (tuple, list)):
        return [clean(v) for v in value]
    return value


def jb(value):
    return (json.dumps(clean(value), indent=2, ensure_ascii=False, allow_nan=False)+"\n").encode("utf-8")


def sha(data):
    return hashlib.sha256(data).hexdigest()


def parameters(path):
    p = json.loads(path.read_text(encoding="utf-8-sig"))
    fields = {
        "identity": {"logical_text", "outlined_text", "initial"},
        "emblem": {"stem_u", "counter_run_u", "return_u", "slope_rise", "slope_run", "counter_gap_u"},
        "typography": {"cap_height_u", "visible_letter_gap_u", "emblem_gap_u", "baseline_optical_shift_u", "features"},
        "composition": {"horizontal_padding_u", "canvas_ratio", "offset_x_u", "offset_y_u", "minimum_clearance_u"},
        "palette": {"graphite", "fold"}, "export": {"pixels_per_u"},
    }
    if set(p) != {"schema_version", *fields} or p["schema_version"] != 1:
        raise ValueError("Expected parameter schema_version 1.")
    for section, keys in fields.items():
        if not isinstance(p[section], dict) or set(p[section]) != keys:
            raise ValueError(f"{section}: expected keys {sorted(keys)}")
    if p["identity"] != {"logical_text": "BlendLib", "outlined_text": "lendLib", "initial": "folded_B"}:
        raise ValueError("Identity is fixed: folded B + the exact case-sensitive text lendLib = BlendLib.")
    if p["typography"]["features"] != {"kern": True, "liga": False, "clig": False}:
        raise ValueError("This profile uses kern=true, liga=false, clig=false.")
    for section in ("emblem", "typography", "composition", "export"):
        for key, value in p[section].items():
            if key == "features":
                continue
            if isinstance(value, bool) or not isinstance(value, (float, int)) or not math.isfinite(value):
                raise ValueError(f"{section}.{key}: expected a finite number.")
            if key not in ("baseline_optical_shift_u", "offset_x_u", "offset_y_u") and value <= 0:
                raise ValueError(f"{section}.{key}: must be positive.")
    for key, value in p["palette"].items():
        if not isinstance(value, str) or not re.fullmatch(r"#[0-9A-Fa-f]{6}", value):
            raise ValueError(f"{key}: expected a six-digit hex color.")
        p["palette"][key] = value.upper()
    if p["palette"]["graphite"] == p["palette"]["fold"]:
        raise ValueError("The two plane colors must remain distinct.")
    return p


def shape_and_outline(p, geo):
    source = json.loads((ASSETS / "provenance.json").read_text(encoding="utf-8"))
    for key in ("font", "reference"):
        asset = ASSETS / source[key]["file"]
        if sha(asset.read_bytes()) != source[key]["sha256"]:
            raise ValueError(f"Source hash mismatch: {asset.name}")
    font_bytes = (ASSETS / "Minecraft.otf").read_bytes()
    tt = TTFont(io.BytesIO(font_bytes))
    gs, cmap = tt.getGlyphSet(), tt.getBestCmap()
    text = p["identity"]["outlined_text"]
    face = hb.Face(font_bytes)
    font = hb.Font(face)
    font.scale = (face.upem, face.upem)
    buffer = hb.Buffer()
    buffer.add_str(text)
    buffer.direction, buffer.script, buffer.language = "ltr", "Latn", "en"
    hb.shape(font, buffer, p["typography"]["features"])
    infos, positions = buffer.glyph_infos, buffer.glyph_positions
    if len(infos) != len(text) or [i.cluster for i in infos] != list(range(len(text))):
        raise ValueError("Unexpected glyph count or cluster order for lendLib.")
    cap = tt["OS/2"].sCapHeight
    target_cap = q(p["typography"]["cap_height_u"])
    factor = target_cap / cap
    baseline = (geo["height_u"]+target_cap)/2 + q(p["typography"]["baseline_optical_shift_u"])
    wanted_gap = q(p["typography"]["visible_letter_gap_u"])
    records, cursor, tracking = [], 0, F(0)
    previous_right = None
    step_lengths = []
    for index, (char, info, pos) in enumerate(zip(text, infos, positions)):
        name = tt.getGlyphName(info.codepoint)
        if info.codepoint == 0 or name != cmap.get(ord(char)) or pos.y_offset or pos.y_advance:
            raise ValueError(f"Unexpected or missing glyph: {char}")
        bounds_pen = BoundsPen(gs)
        gs[name].draw(bounds_pen)
        xmin, ymin, xmax, ymax = map(q, bounds_pen.bounds)
        raw_origin = q(cursor+pos.x_offset) * factor
        raw_left, raw_right = raw_origin+xmin*factor, raw_origin+xmax*factor
        extra = F(0) if previous_right is None else wanted_gap-(raw_left-previous_right)
        tracking += extra
        record_pen = RecordingPen()
        gs[name].draw(record_pen)
        last = None
        for operation, points in record_pen.value:
            if operation not in ("moveTo", "lineTo", "closePath"):
                raise ValueError("The supplied pixel glyph unexpectedly contains a curve or open contour.")
            if operation == "lineTo" and last is not None:
                x, y = points[0]; dx, dy = x-last[0], y-last[1]
                if dx and dy:
                    raise ValueError("The supplied pixel glyph has a non-orthogonal edge.")
                step_lengths.append(int(abs(dx or dy)))
            if points:
                last = points[-1]
        raw_pen = SVGPathPen(gs, ntos=n)
        gs[name].draw(raw_pen)
        records.append({
            "index": index, "character": char, "unicode": f"U+{ord(char):04X}",
            "glyph_id": info.codepoint, "glyph_name": name, "cluster": info.cluster,
            "font_bounds": [xmin, ymin, xmax, ymax], "advance_font_units": pos.x_advance,
            "offset_font_units": [pos.x_offset, pos.y_offset],
            "tracking_before_u": extra, "tracking_before_exact_u": str(extra),
            "raw_origin_font_units": cursor+pos.x_offset,
            "origin_unanchored_u": raw_origin+tracking, "font_path": raw_pen.getCommands(),
        })
        previous_right, cursor = raw_right, cursor+pos.x_advance
    first_left = records[0]["origin_unanchored_u"]+records[0]["font_bounds"][0]*factor
    text_start = geo["width_u"]+q(p["typography"]["emblem_gap_u"])
    for record in records:
        record["origin_local_u"] = record.pop("origin_unanchored_u")-first_left+text_start
        xmin, ymin, xmax, ymax = record["font_bounds"]
        record["ink_bounds_local_u"] = [record["origin_local_u"]+xmin*factor, baseline-ymax*factor,
                                          record["origin_local_u"]+xmax*factor, baseline-ymin*factor]
    ink_width = records[-1]["ink_bounds_local_u"][2]
    c = p["composition"]
    canvas_w = ink_width+2*q(c["horizontal_padding_u"])
    canvas_h = canvas_w/q(c["canvas_ratio"])
    ox = q(c["horizontal_padding_u"])+q(c["offset_x_u"])
    oy = (canvas_h-geo["height_u"])/2+q(c["offset_y_u"])
    clear = q(c["minimum_clearance_u"])
    if min(ox, canvas_w-ox-ink_width, oy, canvas_h-oy-geo["height_u"]) < clear:
        raise ValueError("Canvas padding/ratio/offset violates minimum clearance.")
    if min(r["ink_bounds_local_u"][1] for r in records) < 0 or max(r["ink_bounds_local_u"][3] for r in records) > geo["height_u"]:
        raise ValueError("Typography extends beyond the emblem height. Adjust cap height or baseline shift.")
    ppu = q(p["export"]["pixels_per_u"])
    if any((dimension*ppu).denominator != 1 for dimension in (canvas_w, canvas_h)):
        raise ValueError("Canvas width and height must resolve to whole pixels; choose a compatible pixels_per_u.")
    coordinates = []
    for record in records:
        transform = (float(factor*ppu), 0, 0, -float(factor*ppu),
                     float((ox+record["origin_local_u"])*ppu), float((oy+baseline)*ppu))
        pen = SVGPathPen(gs, ntos=n)
        gs[record["glyph_name"]].draw(TransformPen(pen, transform))
        record["path_px"] = pen.getCommands()
        recorder = RecordingPen()
        gs[record["glyph_name"]].draw(TransformPen(recorder, transform))
        coordinates.extend(v for op, points in recorder.value for point in points for v in point)
    layout = {
        "logical_text": "BlendLib", "outlined_text": text, "initial_rendering": "folded B emblem",
        "font": {"family": tt["name"].getDebugName(1), "style": tt["name"].getDebugName(2),
                 "version": tt["name"].getDebugName(5), "units_per_em": tt["head"].unitsPerEm,
                 "cap_height_font_units": cap, "x_height_font_units": tt["OS/2"].sxHeight,
                 "pixel_step_font_units": math.gcd(*step_lengths), "sha256": sha(font_bytes)},
        "font_scale_u_per_font_unit": factor, "font_scale_exact": str(factor),
        "font_pixel_u": math.gcd(*step_lengths)*factor,
        "baseline_local_u": baseline, "cap_line_local_u": baseline-target_cap,
        "x_height_line_local_u": baseline-tt["OS/2"].sxHeight*factor,
        "centered_baseline_u": (geo["height_u"]+target_cap)/2,
        "optical_baseline_shift_u": q(p["typography"]["baseline_optical_shift_u"]),
        "baseline_to_vertex_C_u": baseline-geo["points_u"]["C"][1],
        "visible_gap_u": wanted_gap, "text_ink_width_u": ink_width-text_start,
        "lockup_ink_width_u": ink_width, "lockup_ink_height_u": geo["height_u"],
        "canvas_u": [canvas_w, canvas_h], "origin_u": [ox, oy],
        "canvas_px": [int(canvas_w*ppu), int(canvas_h*ppu)],
        "glyph_vertices_on_integer_export_pixels": all(abs(v-round(v)) < 1e-7 for v in coordinates),
        "glyphs": records,
    }
    tt.close()
    return layout, source


def svg_start(width, height, title, desc):
    return (f'<?xml version="1.0" encoding="UTF-8"?>\n<svg xmlns="{NS}" width="{n(width)}" height="{n(height)}" '
            f'viewBox="0 0 {n(width)} {n(height)}" role="img" aria-labelledby="title description">\n'
            f'<title id="title">{html.escape(title)}</title>\n<desc id="description">{html.escape(desc)}</desc>\n')


def master_group(p, geo, layout, prefix=""):
    ox, oy = layout["origin_u"]
    ppu = q(p["export"]["pixels_per_u"])
    emblem = f'<g id="{prefix}emblem" transform="translate({n(ox*ppu)} {n(oy*ppu)}) scale({n(ppu)})">\n'
    emblem += geometry.paths(geo, p["palette"], prefix)+"\n</g>\n"
    type_paths = [f'<path id="{prefix}glyph-{g["index"]}-{g["character"]}" data-character="{g["character"]}" d="{g["path_px"]}"/>' for g in layout["glyphs"]]
    return emblem+f'<g id="{prefix}outlined-lendLib" fill="{p["palette"]["graphite"]}" fill-rule="nonzero">\n'+"\n".join(type_paths)+"\n</g>\n"


def master_svg(p, geo, layout):
    width, height = layout["canvas_px"]
    header = svg_start(width, height, "BlendLib", "Folded B as the initial letter, followed by lendLib outlined from the supplied Minecraft.otf. Transparent background. No construction guides or live font dependency.")
    metadata = {"logical_text": "BlendLib", "outlined_text": "lendLib", "font_sha256": layout["font"]["sha256"],
                "unit_px": p["export"]["pixels_per_u"], "font_scale_exact_u": layout["font_scale_exact"]}
    return header+"<metadata>"+html.escape(jb(metadata).decode().strip())+"</metadata>\n"+master_group(p, geo, layout)+"</svg>\n"


def construction_svg(p, geo, layout):
    parts = [svg_start(1800, 1460, "BlendLib geometry and typography specification",
                       "Separate construction sheet. The logo artwork is the same outlined geometry as the master; guides are not part of that artwork.")]
    def add(value): parts.append(value)
    def text(x, y, value, size=17, color=INK, weight=400, anchor="start"):
        add(f'<text x="{n(x)}" y="{n(y)}" font-family="Arial, Helvetica, sans-serif" font-size="{n(size)}" fill="{color}" font-weight="{weight}" text-anchor="{anchor}">{html.escape(str(value))}</text>')
    def line(x1, y1, x2, y2, color=GUIDE, width=1, dash=""):
        extra = f' stroke-dasharray="{dash}"' if dash else ""
        add(f'<line x1="{n(x1)}" y1="{n(y1)}" x2="{n(x2)}" y2="{n(y2)}" stroke="{color}" stroke-width="{n(width)}"{extra}/>')
    def rect(x, y, w, h, fill="none", stroke="#C3D0CF", dash=""):
        add(f'<rect x="{n(x)}" y="{n(y)}" width="{n(w)}" height="{n(h)}" fill="{fill}" stroke="{stroke}" stroke-width=".8"'+(f' stroke-dasharray="{dash}"' if dash else "")+'/>')
    def dh(x1, x2, y, label):
        line(x1,y,x2,y)
        for x in (x1,x2): line(x-4,y+5,x+4,y-5)
        text((x1+x2)/2,y-9,label,16,GUIDE,anchor="middle")
    def dv(x,y1,y2,label):
        line(x,y1,x,y2)
        for y in (y1,y2): line(x-5,y+4,x+5,y-4)
        text(x-10,(y1+y2)/2+5,label,16,GUIDE,anchor="end")

    rect(0,0,1800,1460,PAPER,PAPER)
    text(64,55,"BLENDLIB / PARAMETRIC WORDMARK",17,GUIDE,600)
    text(64,114,"Geometry + type specification",43,INK,600)
    text(64,151,"Folded B as the initial letter. The remaining seven glyphs come from Minecraft.otf.",19,MUTED)
    text(1736,55,"CONSTRUCTION / 01",16,MUTED,anchor="end")
    line(64,176,1736,176,"#CBD3D1")

    # Full lockup with real paths; use a single affine placement of the native master.
    frame_y = 225
    scale = min(F(1660,layout["canvas_px"][0]),F(555,layout["canvas_px"][1]))
    frame_w = layout["canvas_px"][0]*scale
    frame_x = (1800-frame_w)/2
    frame_right = frame_x+frame_w
    pu = q(p["export"]["pixels_per_u"])*scale
    ox, oy = layout["origin_u"]
    sx = lambda x: F(frame_x)+(ox+q(x))*pu
    sy = lambda y: F(frame_y)+(oy+q(y))*pu
    frame_h = layout["canvas_px"][1]*scale
    rect(frame_x,frame_y,frame_w,frame_h,"#FFFFFF")
    add(f'<g transform="translate({n(frame_x)} {frame_y}) scale({n(scale)})">'+master_group(p,geo,layout,"layout-")+"</g>")
    dh(frame_x,frame_right,210,f"Canvas {n(layout['canvas_u'][0])}u x {n(layout['canvas_u'][1])}u / {n(p['composition']['canvas_ratio'])}:1")
    dh(sx(0),sx(layout["lockup_ink_width_u"]),sy(0)-28,f"Ink width = {n(layout['lockup_ink_width_u'])}u")
    for y,label in [(layout["cap_line_local_u"],f"CAP LINE / {n(p['typography']['cap_height_u'])}u"),
                    (layout["baseline_local_u"],f"BASELINE / y = {n(layout['baseline_local_u'])}u")]:
        line(sx(0),sy(y),frame_right-10,sy(y),GUIDE,.75,"6 6")
        label_y=frame_y+frame_h-15 if label.startswith("BASELINE") else sy(y)-9
        text(frame_right-12,label_y,label,14,GUIDE,anchor="end")
    line(sx(0),sy(geo["height_u"]),sx(geo["width_u"]),sy(geo["height_u"]),GUIDE,.75,"4 5")
    for record in layout["glyphs"]:
        l,t,r,b=record["ink_bounds_local_u"]
        rect(sx(l),sy(t),(r-l)*pu,(b-t)*pu,"none","#85AEB3","3 4")
        text((sx(l)+sx(r))/2,sy(layout["baseline_local_u"])+35,record["character"],18,GUIDE,600,"middle")
    gap_y = sy(geo["height_u"])+58
    dh(sx(geo["width_u"]),sx(layout["glyphs"][0]["ink_bounds_local_u"][0]),gap_y,n(p["typography"]["emblem_gap_u"])+"u")
    for a,b in zip(layout["glyphs"],layout["glyphs"][1:]):
        dh(sx(a["ink_bounds_local_u"][2]),sx(b["ink_bounds_local_u"][0]),gap_y,n(layout["visible_gap_u"])+"u")
    text(frame_x+10,frame_y+frame_h-15,"Visible gaps are measured between ink bounds, not advance boxes.",15,MUTED)
    line(64,813,1736,813,"#CBD3D1")

    text(64,850,"02 / EMBLEM COORDINATES",16,GUIDE,600)
    scale_b = min(F(18),F(378)/geo["height_u"],F(280)/geo["width_u"])
    bx,by=166,908
    for i in range(math.ceil(float(geo["width_u"]))+1):
        line(bx+i*scale_b,by-12,bx+i*scale_b,by+geo["height_u"]*scale_b+12,"#DCE7E4",.65)
    for i in range(math.ceil(float(geo["height_u"]))+1):
        line(bx-12,by+i*scale_b,bx+geo["width_u"]*scale_b+12,by+i*scale_b,"#DCE7E4",.65)
    add(f'<g transform="translate({bx} {by}) scale({n(scale_b)})">'+geometry.paths(geo,p["palette"],"detail-")+"</g>")
    offsets={"A":(0,-13),"B":(-17,0),"C":(-17,5),"D":(0,22),"E":(16,7),"F":(16,-4),
             "G":(17,4),"H":(16,5),"I":(16,-5),"J":(-14,-2),"K":(-14,2),"L":(0,-11),
             "M":(-8,-12),"N":(-14,5),"O":(-14,5),"P":(-7,-11),"Q":(-10,12)}
    for key,(x,y) in geo["points_u"].items():
        x,y=bx+x*scale_b,by+y*scale_b
        add(f'<circle cx="{n(x)}" cy="{n(y)}" r="2.8" fill="{PAPER}" stroke="{GUIDE}" stroke-width="1"/>')
        dx,dy=offsets[key];text(x+dx,y+dy,key,14,GUIDE,600,"middle")
    dh(bx,bx+geo["width_u"]*scale_b,881,"W = "+n(geo["width_u"])+"u")
    dv(105,by,by+geo["height_u"]*scale_b,"H = "+n(geo["height_u"])+"u")
    text(64,1340,f"m = {n(geo['slope'])} / +/- {geo['angle_degrees']:.3f} deg",18)
    text(64,1370,"Two equal triangular counters; shared facet intersections.",15,MUTED)
    line(548,846,548,1380,"#D5DCDA")

    text(592,850,"03 / FONT OUTLINES & METRICS",16,GUIDE,600)
    text(592,901,"Minecraft / Medium",29,INK,600)
    entries=[
        ("Units per em",str(layout["font"]["units_per_em"])),
        ("Font cap height",f"{layout['font']['cap_height_font_units']} fu -> {n(p['typography']['cap_height_u'])}u"),
        ("Font pixel",f"{layout['font']['pixel_step_font_units']} fu -> {layout['font_pixel_u']}u"),
        ("Type / B height",f"{n(p['typography']['cap_height_u'])}u / {n(geo['height_u'])}u"),
        ("Visible letter gap",n(layout["visible_gap_u"])+"u"),
        ("Extra tracking",layout["glyphs"][1]["tracking_before_exact_u"]+"u"),
        ("Baseline optical shift",("+" if layout["optical_baseline_shift_u"]>=0 else "")+n(layout["optical_baseline_shift_u"])+"u"),
        ("Baseline minus C.y",n(layout["baseline_to_vertex_C_u"])+"u"),
        ("Anisotropic scaling", "None / equal x and y scale"),
        ("Per-pair manual corrections", "0u"),
    ]
    for i,(key,value) in enumerate(entries):
        y=942+i*38
        text(592,y,key,17,MUTED)
        text(1193,y,value,18,INK,600,"end")
    text(592,1367,"All type paths are extracted from the supplied CFF font.",16,MUTED)
    line(1232,892,1232,1378,"#D5DCDA")

    # Enlarged L/e glyphs, drawn from the font's native outline data, on its pixel grid.
    glyph_scale, glyph_base = 16,1164
    for char,left in [("L",1275),("e",1520)]:
        rec=next(g for g in layout["glyphs"] if g["character"]==char)
        xmin,ymin,xmax,ymax=rec["font_bounds"]
        for i in range(6): line(left+32*i,940,left+32*i,1164,"#C8DED9",.8)
        for i in range(8): line(left,940+32*i,left+160,940+32*i,"#C8DED9",.8)
        add(f'<g transform="translate({n(left-xmin*glyph_scale)} {glyph_base}) scale({glyph_scale} {-glyph_scale})"><path id="native-{char}" d="{rec["font_path"]}" fill="{p["palette"]["graphite"]}"/></g>')
        text(left+80,1210,rec["unicode"],17,GUIDE,600,"middle")
    text(1275,905,"FONT PIXEL GRID",15,GUIDE,600)
    text(1275,1250,"One square = 2 font units.",17,MUTED)
    text(1275,1290,f"Native PNG: {layout['canvas_px'][0]} x {layout['canvas_px'][1]}",17)
    text(1275,1321,f"1u = {n(p['export']['pixels_per_u'])}px",17)
    text(1275,1352,"Integer type vertices: "+("YES" if layout["glyph_vertices_on_integer_export_pixels"] else "NO"),17)
    line(64,1402,1736,1402,"#CBD3D1")
    text(64,1435,"MASTER: 10 filled paths / transparent background / no live text, grid, dimensions or annotations.",16,MUTED)
    text(1736,1435,"PARAMETERS -> SHAPING -> OUTLINES -> EXPORT",14,GUIDE,600,"end")
    add("</svg>\n")
    return "\n".join(parts)


def dimensions(p,geo,layout):
    rows=[
        ("B width",geo["width_u"],"u","s+r+d"),
        ("B height",geo["height_u"],"u","2t+2h+g"),
        ("Stem width",q(p["emblem"]["stem_u"]),"u","s"),
        ("Fold depth run",q(p["emblem"]["return_u"]),"u","d"),
        ("Diagonal angle",geo["angle_degrees"],"degrees","atan(rise/run)"),
        ("Counter run",q(p["emblem"]["counter_run_u"]),"u","r"),
        ("Counter height",geo["counter_height_u"],"u","h=2mr"),
        ("Counter pitch",geo["pitch_u"],"u","p=h+g"),
        ("Counter gap",q(p["emblem"]["counter_gap_u"]),"u","g"),
        ("Band vertical intercept",geo["band_u"],"u","t=2md; not normal thickness"),
        ("Type cap height",q(p["typography"]["cap_height_u"]),"u","measured font cap height times uniform scale"),
        ("Type/B height ratio",q(p["typography"]["cap_height_u"])/geo["height_u"],"ratio","cap height / B height"),
        ("Type baseline",layout["baseline_local_u"],"u","(B height+cap height)/2 + optical shift"),
        ("Optical baseline shift",layout["optical_baseline_shift_u"],"u","relative to centered type ink box"),
        ("Cap line",layout["cap_line_local_u"],"u","baseline - cap height"),
        ("X-height line",layout["x_height_line_local_u"],"u","baseline - 10 font units times scale"),
        ("Visible letter gap",layout["visible_gap_u"],"u","next ink left - previous ink right"),
        ("B-to-type gap",q(p["typography"]["emblem_gap_u"]),"u","first glyph ink left - B right bound"),
        ("Extra tracking per gap",layout["glyphs"][1]["tracking_before_u"],"u",layout["glyphs"][1]["tracking_before_exact_u"]),
        ("Font scale",layout["font_scale_u_per_font_unit"],"u/font unit",layout["font_scale_exact"]),
        ("Font pixel step",layout["font_pixel_u"],"u","2 font units times scale"),
        ("Text ink width",layout["text_ink_width_u"],"u","font outlines plus six visible gaps"),
        ("Lockup ink width",layout["lockup_ink_width_u"],"u","B width + emblem gap + text ink width"),
        ("Canvas width",layout["canvas_u"][0],"u","lockup width + 2*horizontal padding"),
        ("Canvas height",layout["canvas_u"][1],"u",f"canvas width / {n(p['composition']['canvas_ratio'])}"),
        ("Origin x",layout["origin_u"][0],"u","horizontal padding + offset x"),
        ("Origin y",layout["origin_u"][1],"u","(canvas height-B height)/2 + offset y"),
        ("Export unit",q(p["export"]["pixels_per_u"]),"px/u","uniform scale"),
    ]
    output=io.StringIO(newline="")
    writer=csv.writer(output,lineterminator="\n")
    writer.writerow(["dimension","value","unit","definition"])
    for label,value,unit,definition in rows: writer.writerow([label,n(value),unit,definition])
    md=["# 尺寸比例表", "", "由 parameters.json、真实字体度量和几何公式生成。所有 u 均属于同一坐标体系。", "",
        "| 尺寸 | 数值 | 单位 | 定义 |", "| --- | ---: | --- | --- |"]
    md += [f"| {label} | {n(value)} | {unit} | {definition} |" for label,value,unit,definition in rows]
    md += ["", "## B 顶点", "", "| 顶点 | x / u | y / u |", "| --- | ---: | ---: |"]
    md += [f"| {key} | {n(x)} | {n(y)} |" for key,(x,y) in geo["points_u"].items()]
    md += ["", "## 字形位置", "", "相对 B 局部原点；所有字形共用同一基线。左右边界按实际墨迹测量。", "",
           "| 字符 | Unicode | Glyph ID | 原生 advance / fu | 左边界 / u | 右边界 / u | 前置附加字距 / u |",
           "| --- | --- | ---: | ---: | ---: | ---: | ---: |"]
    for g in layout["glyphs"]:
        md.append(f"| {g['character']} | {g['unicode']} | {g['glyph_id']} | {g['advance_font_units']} | {n(g['ink_bounds_local_u'][0])} | {n(g['ink_bounds_local_u'][2])} | {g['tracking_before_exact_u']} |")
    return output.getvalue().encode("utf-8"),("\n".join(md)+"\n").encode("utf-8")


def raster(svg):
    with fitz.open(stream=svg.encode("utf-8"),filetype="svg") as doc:
        pix=doc[0].get_pixmap(alpha=True)
        return Image.open(io.BytesIO(pix.tobytes("png"))).convert("RGBA")


def png_bytes(bitmap):
    out=io.BytesIO()
    bitmap.save(out,format="PNG",compress_level=9)
    return out.getvalue()


def on_white(bitmap):
    white=Image.new("RGBA",bitmap.size,"#FFFFFF")
    white.alpha_composite(bitmap)
    return white.convert("RGB")


def render_outputs(p,geo,layout,master,construction):
    bitmap=raster(master)
    if bitmap.size != tuple(layout["canvas_px"]):
        raise ValueError("Native PNG dimensions differ from the master viewBox.")
    alpha=bitmap.getchannel("A")
    ox,oy=layout["origin_u"]
    ppu=q(p["export"]["pixels_per_u"])
    counter_alpha={}
    for key in ("upper_counter","lower_counter"):
        pts=[geo["points_u"][c] for c in geo["loops"][key]]
        x=(ox+sum(v[0] for v in pts)/3)*ppu
        y=(oy+sum(v[1] for v in pts)/3)*ppu
        counter_alpha[key]=alpha.getpixel((round(x),round(y)))
    if any(counter_alpha.values()) or any(alpha.getpixel(pt) for pt in [(0,0),(bitmap.width-1,0),(0,bitmap.height-1),(bitmap.width-1,bitmap.height-1)]):
        raise ValueError("Background or triangular counters are not transparent.")

    width,height=layout["canvas_px"]
    type_svg=svg_start(width,height,"lendLib font outline proof","Type only, extracted from the supplied font.")
    type_svg+=f'<g fill="{p["palette"]["graphite"]}">'+"".join(f'<path d="{g["path_px"]}"/>' for g in layout["glyphs"])+"</g></svg>"
    type_image=raster(type_svg)
    type_alpha=type_image.getchannel("A")
    # Independent FreeType rasterization of the original font, with the measured
    # shaping positions/tracking. This does not reuse SVG path data.
    exact_font_size=layout["font"]["units_per_em"]*layout["font_scale_u_per_font_unit"]*ppu
    font_size=round(exact_font_size)
    ft_font=ImageFont.truetype(str(ASSETS/"Minecraft.otf"),size=font_size)
    ft_alpha=Image.new("L",bitmap.size,0)
    ft_draw=ImageDraw.Draw(ft_alpha)
    for g in layout["glyphs"]:
        position=(float((ox+g["origin_local_u"])*ppu),float((oy+layout["baseline_local_u"])*ppu))
        ft_draw.text(position,g["character"],font=ft_font,anchor="ls",fill=255)
    diff=ImageChops.difference(ft_alpha,type_alpha)
    differing=sum(count for level,count in enumerate(diff.histogram()) if level)
    type_levels=[i for i,count in enumerate(type_alpha.histogram()) if count]
    if layout["glyph_vertices_on_integer_export_pixels"] and exact_font_size.denominator == 1 and differing:
        raise ValueError(f"Independent FreeType/outlined SVG mismatch: {differing} pixels.")
    if layout["glyph_vertices_on_integer_export_pixels"] and type_levels != [0,255]:
        raise ValueError("Integer-aligned pixel font unexpectedly has blurred alpha edges.")

    native_white=on_white(bitmap)
    preview=native_white.resize((1533,round(1533*native_white.height/native_white.width)),Image.Resampling.LANCZOS)
    comparison=Image.new("RGB",(1472,1230),PAPER)
    draw=ImageDraw.Draw(comparison)
    proof_font=lambda size:ImageFont.load_default(size=size)
    draw.text((64,39),"BLENDLIB / SOURCE AND OUTLINED RECONSTRUCTION",font=proof_font(18),fill=GUIDE)
    draw.text((64,78),"Folded B + Minecraft.otf",font=proof_font(38),fill=INK)
    draw.line((64,137,1408,137),fill="#CBD3D1")
    source=Image.open(ASSETS/"source-wordmark-white.png").convert("RGB")
    draw.text((64,155),"01 / EXISTING WORDMARK",font=proof_font(17),fill=GUIDE)
    def panel(image):
        fitted=image.copy()
        fitted.thumbnail((1344,448),Image.Resampling.LANCZOS)
        tile=Image.new("RGB",(1344,448),"white")
        tile.paste(fitted,((1344-fitted.width)//2,(448-fitted.height)//2))
        return tile
    comparison.paste(panel(source),(64,187))
    draw.text((64,647),"Original raster / original lettering",font=proof_font(17),fill=MUTED)
    draw.line((64,691,1408,691),fill="#CBD3D1")
    draw.text((64,714),"02 / FONT OUTLINES + PARAMETRIC EMBLEM",font=proof_font(17),fill=GUIDE)
    comparison.paste(panel(native_white),(64,746))
    draw.text((64,1206),"B is the initial letter. The font shapes lendLib. Together: BlendLib.",font=proof_font(16),fill=MUTED)

    font_proof=Image.new("RGB",(1400,718),PAPER)
    pd=ImageDraw.Draw(font_proof)
    pd.text((48,32),"SUPPLIED FONT / OUTLINED SVG",font=proof_font(24),fill=INK)
    crop=type_alpha.getbbox()
    otf_color=Image.new("RGB",bitmap.size,p["palette"]["graphite"])
    ft_white=Image.composite(otf_color,Image.new("RGB",bitmap.size,"white"),ft_alpha)
    svg_white=on_white(type_image)
    for y,label,img in [(107,"FreeType / original Minecraft.otf",ft_white),(407,"SVG / extracted CFF paths",svg_white)]:
        pd.text((48,y-26),label,font=proof_font(17),fill=GUIDE)
        cropped=img.crop(crop)
        resized=cropped.resize((1304,round(1304*cropped.height/cropped.width)),Image.Resampling.LANCZOS)
        font_proof.paste(resized,(48,y))
    pd.text((48,687),f"Native alpha mismatch: {differing} pixels. No font substitution or synthetic weight.",font=proof_font(16),fill=MUTED)

    metrics={
        "transparent_background":True,"counter_alpha_at_centroids":counter_alpha,
        "native_png_mode":bitmap.mode,"native_png_size":list(bitmap.size),
        "native_type_alpha_levels":type_levels,
        "font_vertices_on_integer_pixels":layout["glyph_vertices_on_integer_export_pixels"],
        "freetype_exact_font_size_px":str(exact_font_size),"freetype_used_font_size_px":font_size,
        "freetype_vs_svg_differing_alpha_pixels":differing,
        "freetype_comparison_exact_size":exact_font_size.denominator==1,
        "scope":"SVG raster and source-font comparison; construction sheet is separate from the master",
    }
    return {
        "blendlib-wordmark.png":png_bytes(bitmap),
        "construction.png":png_bytes(on_white(raster(construction))),
        "preview.png":png_bytes(preview),"comparison.png":png_bytes(comparison),
        "font-outline-proof.png":png_bytes(font_proof),
    },metrics


def generate_all(p):
    geo=geometry.construct(p["emblem"])
    layout,source=shape_and_outline(p,geo)
    master=master_svg(p,geo,layout)
    construction=construction_svg(p,geo,layout)
    table_csv,table_md=dimensions(p,geo,layout)
    files={"blendlib-wordmark-master.svg":master.encode("utf-8"),
           "construction.svg":construction.encode("utf-8"),
           "dimensions.csv":table_csv,"dimensions.md":table_md,
           "geometry.json":jb(geo),"layout.json":jb(layout)}
    renders,metrics=render_outputs(p,geo,layout,master,construction)
    files.update(renders)
    files["render-report.json"]=jb(metrics)
    versions={"fontTools":fontTools.__version__,"uharfbuzz":hb.__version__,
              "HarfBuzz":hb.version_string(),"PyMuPDF":fitz.VersionBind,"Pillow":PIL.__version__}
    manifest={
        "schema_version":1,"logical_text":"BlendLib","outlined_text":"lendLib",
        "parameters_sha256":sha(jb(p)),
        "script_sha256":{name:sha((ROOT/name).read_bytes().replace(b"\r\n",b"\n")) for name in ("generate.py","geometry.py")},
        "source":source,"engines":versions,
        "assets_sha256":{f.name:sha(f.read_bytes()) for f in sorted(ASSETS.iterdir()) if f.is_file()},
        "checks":{"glyph_count":7,"master_path_count":10,"outline_source":"provided Minecraft.otf CFF",
                  "text_case":"PASS","fold_geometry":"PASS","minimum_clearance":"PASS"},
        "outputs":{name:{"bytes":len(data),"sha256":sha(data)} for name,data in sorted(files.items())},
    }
    files["manifest.json"]=jb(manifest)
    files["SHA256SUMS"]="".join(f"{sha(data)}  {name}\n" for name,data in sorted(files.items())).encode("utf-8")
    return files


def main():
    parser=argparse.ArgumentParser(description=__doc__,formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--params",type=Path,default=ROOT/"parameters.json")
    parser.add_argument("--out",type=Path,default=ROOT/"generated")
    parser.add_argument("--check",action="store_true",help="Regenerate expected bytes and compare all generated files without rewriting them")
    args=parser.parse_args()
    try:
        p=parameters(args.params)
        files=generate_all(p)
        if args.check:
            failed=[name for name,data in files.items() if not (args.out/name).is_file() or (args.out/name).read_bytes()!=data]
            if failed:raise ValueError("Missing or stale outputs: "+", ".join(failed))
            print(f"PASS: {len(files)} generated files match byte-for-byte; font, geometry and raster checks passed.")
        else:
            args.out.mkdir(parents=True,exist_ok=True)
            for name,data in files.items():(args.out/name).write_bytes(data)
            print(f"Generated {len(files)} files in {args.out.resolve()}")
        return 0
    except (OSError,ValueError,KeyError,TypeError,ZeroDivisionError) as exc:
        print(f"ERROR: {exc}",file=sys.stderr)
        return 1


if __name__=="__main__":
    raise SystemExit(main())
