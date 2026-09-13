"""Shared-vertex folded B, constructed in exact rational units."""

from fractions import Fraction as F
import math


def number(value):
    return F(str(value))


def fmt(value):
    value = round(float(value), 8)
    return "0" if value == 0 else f"{value:.8f}".rstrip("0").rstrip(".")


def construct(p):
    s, r, d, g = [number(p[k]) for k in ("stem_u", "counter_run_u", "return_u", "counter_gap_u")]
    m = number(p["slope_rise"]) / number(p["slope_run"])
    t, h = 2*m*d, 2*m*r
    pitch, w, height = h+g, s+r+d, 2*t+2*h+g
    a = m*(r+d)
    mx, gx = w-pitch/(2*m), w-pitch/(2*m)+d
    if not s < mx < s+r < gx < w or not m*s < height/2:
        raise ValueError("Invalid folded-B topology: require s < M.x < s+r < G.x < W and m*s < H/2.")
    points = {
        "A": (s, 0), "B": (0, m*s), "C": (0, height-m*s), "D": (s, height),
        "E": (w, height-a), "F": (w, a+pitch), "G": (gx, a+pitch/2+m*d),
        "H": (w, a+t), "I": (w, a), "J": (s, t), "K": (s, t+h),
        "L": (s+r, t+h/2), "M": (mx, a+pitch/2), "N": (s, t+pitch),
        "O": (s, t+pitch+h), "P": (s+r, t+pitch+h/2), "Q": (s+r, height-m*r),
    }
    loops = {"outline": "AIHGFEDCB", "upper_counter": "JLK", "lower_counter": "NPO",
             "upper_fold": "MIHG", "lower_fold": "PFEQ"}
    for loop in loops.values():
        for start, end in zip(loop, loop[1:]+loop[:1]):
            x1, y1 = points[start]; x2, y2 = points[end]
            if x1 != x2 and abs((y2-y1)/(x2-x1)) != m:
                raise ValueError("Unexpected edge direction.")
    return {"points_u": points, "loops": loops, "width_u": w, "height_u": height,
            "slope": m, "angle_degrees": math.degrees(math.atan(float(m))),
            "band_u": t, "counter_height_u": h, "pitch_u": pitch, "counter_area_u2": r*h/2}


def path(loop, geo):
    return "M " + " L ".join(f"{fmt(geo['points_u'][key][0])} {fmt(geo['points_u'][key][1])}" for key in loop) + " Z"


def paths(geo, palette, prefix=""):
    loops = geo["loops"]
    body = " ".join(path(loops[k], geo) for k in ("outline", "upper_counter", "lower_counter"))
    return "\n".join([
        f'<path id="{prefix}body" fill="{palette["graphite"]}" fill-rule="evenodd" d="{body}"/>',
        f'<path id="{prefix}upper-fold" fill="{palette["fold"]}" d="{path(loops["upper_fold"], geo)}"/>',
        f'<path id="{prefix}lower-fold" fill="{palette["fold"]}" d="{path(loops["lower_fold"], geo)}"/>',
    ])
