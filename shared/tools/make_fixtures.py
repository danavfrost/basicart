#!/usr/bin/env python3
"""Regenerates shared/fixtures/ (stdlib only). Run: python3 shared/tools/make_fixtures.py

The output is committed; re-running must be deterministic. After changing this file,
run validate_fixtures.py.
"""
import hashlib
import json
import math
import os
import shutil
import struct
import warnings
import zipfile
import zlib

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "fixtures")
T0 = "2026-10-08T12:00:00Z"
T1 = "2026-10-08T12:30:00Z"


# ---------------------------------------------------------------- PNG writer
def png_bytes(w, h, pixel):  # pixel(x, y) -> (r, g, b, a)
    raw = b"".join(b"\x00" + b"".join(bytes(pixel(x, y)) for x in range(w)) for y in range(h))

    def chunk(t, d):
        return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xFFFFFFFF)

    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))


def gradient_photo(x, y):  # 64x48 opaque "photo": sky-to-grass gradient with a sun
    w, h = 64, 48
    if (x - 46) ** 2 + (y - 12) ** 2 <= 36:
        return (255, 214, 64, 255)
    if y < 30:
        t = y / 30
        return (int(90 + 60 * t), int(160 + 50 * t), 235, 255)
    t = (y - 30) / 18
    return (int(70 - 30 * t), int(150 - 40 * t), int(60 - 20 * t), 255)


def sticker(x, y):  # 32x32 with alpha: red disc, soft edge, transparent outside
    d = math.hypot(x - 15.5, y - 15.5)
    a = max(0.0, min(1.0, 14.5 - d))
    return (229, 57, 53, int(round(255 * a)))


# ---------------------------------------------------------------- helpers
def u16len(s):
    return len(s.encode("utf-16-le")) // 2


def u16index(s, sub):
    """UTF-16 offset of the first occurrence of sub in s."""
    return u16len(s[: s.index(sub)])


def tr(x, y, scale=1, rotation=0):
    return {"x": x, "y": y, "scale": scale, "rotation": rotation}


def base(id_, type_, name, transform, visible=True, locked=False, opacity=1):
    return {"id": id_, "type": type_, "name": name, "visible": visible, "locked": locked,
            "opacity": opacity, "blendMode": "normal", "transform": transform}


def outline(enabled=False, style="solid", color="#000000FF", width=0.06, color2="#FFFFFFFF",
            width2=0.06, glow=0.3, join="round"):
    return {"enabled": enabled, "style": style, "color": color, "width": width, "color2": color2,
            "width2": width2, "glowRadius": glow, "join": join}


def shadow(enabled=False, color="#00000080", blur=0.1, ox=0.05, oy=0.05):
    return {"enabled": enabled, "color": color, "blur": blur, "offsetX": ox, "offsetY": oy}


def bgbox(enabled=False, color="#00000099", padding=0.25, radius=0.15):
    return {"enabled": enabled, "color": color, "padding": padding, "cornerRadius": radius}


def text_layer(id_, name, t, text, **kw):
    L = base(id_, "text", name, t, kw.pop("visible", True), kw.pop("locked", False), kw.pop("opacity", 1))
    L.update({"text": text, "fontId": "inter", "weight": 400, "fontSize": 64, "bold": False,
              "italic": False, "underline": False, "strike": False, "align": "center",
              "letterSpacing": 0, "lineHeight": 1.2, "textCase": "none", "autoWidth": True,
              "boxWidth": 0, "fill": {"type": "solid", "color": "#000000FF"}, "outline": outline(),
              "shadow": shadow(), "backgroundBox": bgbox(), "curve": 0, "skew": 0, "spans": []})
    L.update(kw)
    return L


def image_layer(id_, name, t, asset, nw, nh, **kw):
    L = base(id_, "image", name, t, kw.pop("visible", True), kw.pop("locked", False), kw.pop("opacity", 1))
    L.update({"assetRef": asset, "naturalWidth": nw, "naturalHeight": nh,
              "crop": {"x": 0, "y": 0, "width": nw, "height": nh}, "rotate90": 0, "flipH": False,
              "flipV": False, "adjust": {"brightness": 0, "contrast": 0, "saturation": 0, "warmth": 0},
              "cornerRadius": 0, "border": {"enabled": False, "width": 0, "color": "#FFFFFFFF"}})
    L.update(kw)
    return L


def shape_layer(id_, name, t, shape, w, h, **kw):
    L = base(id_, "shape", name, t, kw.pop("visible", True), kw.pop("locked", False), kw.pop("opacity", 1))
    L.update({"shape": shape, "width": w, "height": h,
              "fill": {"enabled": True, "color": "#3478F6FF"},
              "stroke": {"enabled": False, "color": "#000000FF", "width": 8, "join": "miter"},
              "cornerRadius": 24, "arrowHeads": "end"})
    L.update(kw)
    return L


def drawing_layer(id_, name, t, w, h, **kw):
    L = base(id_, "drawing", name, t, kw.pop("visible", True), kw.pop("locked", False), kw.pop("opacity", 1))
    L.update({"width": w, "height": h})
    return L


def stroke(brush, pts, size=12, color="#000000FF", opacity=1, pressure=None):
    s = {"brush": brush, "size": size, "color": color, "opacity": opacity,
         "points": [round(v, 2) for p in pts for v in p]}
    if pressure is not None:
        s["pressure"] = pressure
    return s


def project(id_, name, w, h, bg, layers, version=1):
    return {"formatVersion": version, "id": id_, "name": name, "created": T0, "modified": T1,
            "generator": "Basic Art fixture generator", "canvas": {"width": w, "height": h, "background": bg},
            "layers": layers}


class Fixture:
    def __init__(self, name):
        self.dir = os.path.join(ROOT, name)
        os.makedirs(self.dir)

    def asset(self, data, ext):
        os.makedirs(os.path.join(self.dir, "assets"), exist_ok=True)
        fn = hashlib.sha256(data).hexdigest() + "." + ext
        with open(os.path.join(self.dir, "assets", fn), "wb") as f:
            f.write(data)
        return fn

    def strokes(self, layer_id, strokes):
        os.makedirs(os.path.join(self.dir, "strokes"), exist_ok=True)
        self.json(os.path.join("strokes", layer_id + ".json"),
                  {"formatVersion": 1, "layerId": layer_id, "strokes": strokes})

    def mask(self, layer_id, strokes):
        os.makedirs(os.path.join(self.dir, "strokes"), exist_ok=True)
        self.json(os.path.join("strokes", layer_id + ".mask.json"),
                  {"formatVersion": 1, "layerId": layer_id, "strokes": strokes})

    def json(self, rel, obj):
        with open(os.path.join(self.dir, rel), "w", encoding="utf-8") as f:
            json.dump(obj, f, indent=2, ensure_ascii=False)
            f.write("\n")

    def raw(self, rel, text):
        with open(os.path.join(self.dir, rel), "w", encoding="utf-8") as f:
            f.write(text)


PHOTO = png_bytes(64, 48, gradient_photo)
STICKER = png_bytes(32, 32, sticker)


def wave(n, x0, x1, y, amp, phase=0):
    return [(x0 + (x1 - x0) * i / (n - 1), y + amp * math.sin(phase + i * 0.9)) for i in range(n)]


def main():
    if os.path.isdir(ROOT):
        for e in os.listdir(ROOT):
            p = os.path.join(ROOT, e)
            if os.path.isdir(p) and e != "layout":  # layout/cases.json is hand-maintained
                shutil.rmtree(p)
    os.makedirs(ROOT, exist_ok=True)

    # 01 — one layer of every type, simplest settings
    f = Fixture("01-all-layer-types")
    a = f.asset(PHOTO, "png")
    f.strokes("draw-1", [stroke("pen", wave(8, 100, 980, 900, 40), size=16, color="#E53935FF")])
    f.json("project.json", project("01-all-layer-types", "All layer types", 1080, 1080, "#FFFFFFFF", [
        image_layer("img-photo", "Photo", tr(540, 405, 16.875), a, 64, 48),
        shape_layer("shape-rect", "Rectangle", tr(540, 980), "rect", 600, 120,
                    fill={"enabled": True, "color": "#FDD835FF"},
                    stroke={"enabled": True, "color": "#000000FF", "width": 6, "join": "miter"}),
        drawing_layer("draw-1", "Drawing 1", tr(540, 540), 1080, 1080),
        text_layer("text-title", "Title", tr(540, 120), "Hello, Basic Art!", fontSize=96, bold=True,
                   fill={"type": "solid", "color": "#1E88E5FF"}),
    ]))

    # 02 — every text effect
    f = Fixture("02-text-effects")
    f.json("project.json", project("02-text-effects", "Text effects", 1200, 1600, "#1E1E2EFF", [
        text_layer("t-meme", "Meme", tr(600, 100), "top text", fontId="anton", fontSize=110,
                   textCase="upper", fill={"type": "solid", "color": "#FFFFFFFF"},
                   outline=outline(True, width=0.08)),
        text_layer("t-shadow", "Outline + shadow", tr(600, 260), "Outline & Shadow", fontSize=80,
                   fill={"type": "solid", "color": "#FDD835FF"},
                   outline=outline(True, color="#000000FF", width=0.05, join="miter"),
                   shadow=shadow(True, "#000000B3", 0.15, 0.06, 0.08)),
        text_layer("t-double", "Double outline", tr(600, 400), "Double", fontId="bungee", fontSize=100,
                   fill={"type": "solid", "color": "#FFFFFFFF"},
                   outline=outline(True, "double", "#E53935FF", 0.05, "#000000FF", 0.07)),
        text_layer("t-glow", "Glow", tr(600, 540), "neon nights", fontId="bebas-neue", fontSize=100,
                   fill={"type": "solid", "color": "#FFE6FBFF"},
                   outline=outline(True, "glow", "#FF10F0FF", 0.03, glow=0.4)),
        text_layer("t-linear", "Linear gradient, rotated", tr(600, 700, 1, 37.5), "Gradient 37.5°",
                   fontSize=90, bold=True,
                   fill={"type": "linear", "angle": 90, "stops": [
                       {"offset": 0, "color": "#FFD23FFF"}, {"offset": 0.5, "color": "#FF7B54FF"},
                       {"offset": 1, "color": "#E0368CFF"}]}),
        text_layer("t-radial", "Radial gradient", tr(600, 860), "Radial", fontSize=120, weight=700,
                   fill={"type": "radial", "stops": [
                       {"offset": 0, "color": "#FFFFFFFF"}, {"offset": 1, "color": "#00ACC1FF"}]}),
        text_layer("t-curve-up", "Curve up", tr(600, 1020), "Curved upward text", fontSize=70,
                   curve=60, fill={"type": "solid", "color": "#AEEA00FF"}),
        text_layer("t-curve-down", "Curve down", tr(600, 1160), "Smile curve", fontSize=70, curve=-45,
                   fill={"type": "solid", "color": "#0FF0FCFF"}, outline=outline(True, width=0.04)),
        text_layer("t-curve-multi", "Multi-line curve (concentric arcs)", tr(600, 1250, 0.6),
                   "first line on top\nmiddle\nlast line", fontSize=56, curve=40, align="center",
                   fill={"type": "solid", "color": "#FDD835FF"}),
        text_layer("t-box", "Background box, wrap, justify, skew", tr(600, 1330),
                   "a caption that wraps inside a fixed box and is justified", fontSize=48,
                   autoWidth=False, boxWidth=700, align="justify", lineHeight=1.3, skew=12,
                   fill={"type": "solid", "color": "#FFFFFFFF"},
                   backgroundBox=bgbox(True, "#000000A6", 0.3, 0.2)),
        text_layer("t-case", "Title case + spacing + fallback font", tr(600, 1500),
                   "the QUICK brown fox\nline two", fontId="no-such-font", fontSize=56,
                   textCase="title", letterSpacing=0.1, align="left", underline=True,
                   fill={"type": "solid", "color": "#FFFFFFFF"}),
    ]))

    # 03 — rich-text spans, weights
    f = Fixture("03-rich-text-spans")
    s1 = "Hello brave new world 👋🏽 done"
    s2 = "Italic except THIS part"
    s3 = "Black weight, B toggled"
    sp = lambda s, sub, **fl: dict({"start": u16index(s, sub), "end": u16index(s, sub) + u16len(sub)}, **fl)
    f.json("project.json", project("03-rich-text-spans", "Rich text spans", 1080, 1080, "#FFFFFFFF", [
        text_layer("t-mixed", "Mixed spans", tr(540, 300), s1, fontSize=64, spans=[
            sp(s1, "brave", bold=True),
            sp(s1, "new", italic=True, underline=True),
            sp(s1, "world", strike=True),
            sp(s1, "👋🏽", bold=True),
        ]),
        text_layer("t-italic-layer", "Italic layer with upright span", tr(540, 540), s2, fontSize=56,
                   italic=True, spans=[sp(s2, "THIS part", italic=False, bold=True)]),
        text_layer("t-weight", "Weight 900 + B, span un-bolded", tr(540, 780), s3, fontSize=72, weight=900,
                   bold=True, spans=[sp(s3, "Black weight", bold=False)]),
    ]))

    # 04 — flags, transparent background, every shape, image adjustments
    f = Fixture("04-flags-shapes-image")
    a = f.asset(PHOTO, "png")
    b = f.asset(STICKER, "png")
    sh = lambda c, w, j="miter": {"enabled": True, "color": c, "width": w, "join": j}
    f.json("project.json", project("04-flags-shapes-image", "Flags, shapes, image", 800, 600, "#00000000", [
        image_layer("img-adjusted", "Adjusted photo", tr(400, 300, 8), a, 64, 48,
                    crop={"x": 8, "y": 4, "width": 40, "height": 30}, rotate90=1, flipH=True,
                    adjust={"brightness": 20, "contrast": -30, "saturation": 50, "warmth": 40},
                    cornerRadius=4, border={"enabled": True, "width": 2, "color": "#FFFFFFFF"}),
        image_layer("img-alpha", "Sticker (alpha PNG)", tr(700, 100, 3, 315), b, 32, 32, opacity=0.5),
        shape_layer("shape-rect", "Rect", tr(100, 80), "rect", 160, 100, stroke=sh("#000000FF", 4)),
        shape_layer("shape-round", "Round rect", tr(300, 80), "roundRect", 160, 100, cornerRadius=30,
                    fill={"enabled": True, "color": "#43A047FF"}),
        shape_layer("shape-ellipse", "Ellipse (no fill)", tr(500, 80), "ellipse", 160, 100,
                    fill={"enabled": False, "color": "#3478F6FF"}, stroke=sh("#8E24AAFF", 10, "round")),
        shape_layer("shape-line", "Line", tr(150, 520, 1, 45), "line", 200, 6, stroke=sh("#000000FF", 6, "round")),
        shape_layer("shape-arrow", "Arrow both", tr(450, 520), "arrow", 260, 12, arrowHeads="both",
                    stroke=sh("#E53935FF", 12)),
        shape_layer("shape-hidden", "Hidden", tr(650, 520), "ellipse", 80, 80, visible=False),
        shape_layer("shape-locked", "Locked", tr(400, 420, 1.5), "rect", 60, 40, locked=True,
                    fill={"enabled": True, "color": "#FB8C0080"}),
        text_layer("t-hidden-locked", "Hidden + locked text", tr(400, 300), "secret", visible=False,
                   locked=True),
    ]))

    # 05 — drawing: every brush, pressure, eraser, transformed layer, missing strokes file
    f = Fixture("05-drawing-brushes")
    brushes = [("pen", "#000000FF"), ("marker", "#1E88E5FF"), ("highlighter", "#FDD835FF"),
               ("airbrush", "#E53935FF"), ("calligraphy", "#3949ABFF"), ("pencil", "#757575FF")]
    strokes = []
    for i, (br, col) in enumerate(brushes):
        strokes.append(stroke(br, wave(10, 40, 470, 50 + i * 70, 15, i), size=10 + i * 4, color=col,
                              opacity=0.9 if br != "pen" else 1))
    strokes.append(stroke("pen", wave(6, 40, 470, 480, 10), size=20, color="#43A047FF",
                          pressure=[0.1, 0.3, 0.5, 0.7, 0.9, 1.0]))
    strokes.append(stroke("pen", [(256, 440)], size=30, color="#8E24AAFF"))  # single-point dot
    strokes.append(stroke("eraser", [(256, 20), (256, 500)], size=24, opacity=1))
    f.strokes("draw-brushes", strokes)
    f.strokes("draw-transformed", [stroke("marker", [(10, 10), (190, 10), (190, 90), (10, 90), (10, 10)],
                                          size=8, color="#00C853FF"),
                                   stroke("pen", [(-50, 50), (250, 50)], size=6, color="#000000FF")])
    f.json("project.json", project("05-drawing-brushes", "Drawing brushes", 512, 512, "#FFFFFFFF", [
        drawing_layer("draw-brushes", "All brushes", tr(256, 256), 512, 512),
        drawing_layer("draw-transformed", "Scaled + rotated, clipped", tr(380, 380, 0.5, 30), 200, 100,
                      opacity=0.8),
        drawing_layer("draw-no-file", "No strokes file", tr(256, 256), 512, 512),
    ]))

    # 06 — referenced asset missing → opens, placeholder
    f = Fixture("06-missing-asset")
    missing = hashlib.sha256(b"this asset is intentionally absent").hexdigest() + ".jpg"
    f.json("project.json", project("06-missing-asset", "Missing asset", 600, 400, "#FFFFFFFF", [
        image_layer("img-missing", "Missing photo", tr(300, 200, 0.25), missing, 1600, 1200),
        text_layer("t-caption", "Caption", tr(300, 360), "still opens", fontSize=40),
    ]))

    # 07 — newer format version
    f = Fixture("07-future-version")
    fut = project("07-future-version", "From the future", 1080, 1080, "#FFFFFFFF", [
        {"id": "x1", "type": "hologram", "transform": {"matrix": [1, 0, 0, 1, 0, 0]}, "beams": 3}], version=999)
    fut["canvas"]["colorSpace"] = "display-p3"
    f.json("project.json", fut)

    # 08 — truncated JSON
    f = Fixture("08-corrupt-truncated")
    good = json.dumps(project("08-corrupt-truncated", "Truncated", 1080, 1080, "#FFFFFFFF", [
        text_layer("t1", "Text", tr(540, 540), "cut off")]), indent=2)
    f.raw("project.json", good[: len(good) // 2])

    # 09 — valid JSON, duplicate layer ids
    f = Fixture("09-corrupt-duplicate-ids")
    f.json("project.json", project("09-corrupt-duplicate-ids", "Duplicate ids", 400, 400, "#FFFFFFFF", [
        shape_layer("dup", "A", tr(100, 100), "rect", 50, 50),
        shape_layer("dup", "B", tr(300, 300), "ellipse", 50, 50)]))

    # 10 — valid JSON, wrong types / missing required
    f = Fixture("10-corrupt-bad-types")
    bad = project("10-corrupt-bad-types", "Bad types", 1080, 1080, "#FFFFFFFF", [
        text_layer("t1", "Text", tr(540, 540), "hi")])
    bad["canvas"]["width"] = "1080"
    del bad["layers"][0]["transform"]
    f.json("project.json", bad)


    # 11 — eraser masks on non-drawing layers (§9.1)
    f = Fixture("11-eraser-masks")
    a = f.asset(PHOTO, "png")
    er = lambda pts, size, op=1, pr=None: stroke("eraser", pts, size=size, opacity=op, pressure=pr)
    f.mask("img-masked", [er([(0, 0), (24, 24), (48, 30)], 10),
                          er([(5, 40), (20, 44), (40, 45)], 6, 0.5, [0.2, 0.6, 1.0])])
    f.mask("t-masked", [er([(-20, -10), (120, 90)], 30)])  # starts outside the layout box
    f.mask("shape-masked", [er([(0, 100), (200, 100)], 40)])
    f.raw("strokes/shape-bad-mask.mask.json", '{"formatVersion": 1, "layerId": "shape-bad-mask", "strokes": [')
    f.strokes("draw-plain", [stroke("pen", [(20, 20), (380, 380)], size=10, color="#1E88E5FF")])
    f.mask("draw-plain", [er([(0, 400), (400, 0)], 50)])  # must be IGNORED (drawing layers use inline erasers)
    f.json("project.json", project("11-eraser-masks", "Eraser masks", 800, 800, "#FFFFFFFF", [
        image_layer("img-masked", "Masked photo (cropped, rotated)", tr(250, 250, 8, 10), a, 64, 48,
                    crop={"x": 8, "y": 0, "width": 48, "height": 40}, rotate90=1, flipV=True),
        shape_layer("shape-masked", "Masked ellipse", tr(600, 250), "ellipse", 200, 200),
        shape_layer("shape-bad-mask", "Corrupt mask file", tr(600, 600), "rect", 150, 150,
                    fill={"enabled": True, "color": "#43A047FF"}),
        drawing_layer("draw-plain", "Drawing with stray mask", tr(400, 400), 400, 400),
        text_layer("t-masked", "Masked text", tr(250, 600, 1, 350), "Erase me", fontId="anton",
                   fontSize=90, fill={"type": "solid", "color": "#FFFFFFFF"},
                   outline=outline(True, width=0.1), shadow=shadow(True)),
    ]))

    # 12 — mixed character styles: colors over a gradient, two extra fonts, several sizes, emoji, outline + curve
    f = Fixture("12-mixed-styles")
    m = "Big SALE today 🎉 only!\nsmall print"
    f.json("project.json", project("12-mixed-styles", "Mixed styles", 1080, 1080, "#1E1E2EFF", [
        text_layer("t-styled", "Mixed styles, curved", tr(540, 420), m, fontSize=60, curve=35,
                   fill={"type": "linear", "angle": 0, "stops": [
                       {"offset": 0, "color": "#0FF0FCFF"}, {"offset": 1, "color": "#BC13FEFF"}]},
                   outline=outline(True, width=0.06), shadow=shadow(True, "#00000099", 0.1, 0.04, 0.06),
                   spans=[sp(m, "Big", fontId="anton", size=110, color="#FFD23FFF"),
                          sp(m, "SALE", fontId="anton", size=110, color="#E53935FF", underline=True),
                          sp(m, "today", color="#FFFFFFFF"),
                          sp(m, "🎉", size=90),
                          sp(m, "small print", fontId="caveat", size=32, italic=True)]),
        text_layer("t-solid", "Solid fill + colored word, straight", tr(540, 850), "plain RED plain",
                   fontSize=72, weight=700, fill={"type": "solid", "color": "#FFFFFFFF"},
                   spans=[sp("plain RED plain", "RED", color="#E53935FF", weight=900)]),
    ]))
    f.json("editor-state.json", {"selectedLayerIds": ["t-styled"], "activeTool": "text", "textTab": "color",
                                 "view": {"zoom": 1.5, "centerX": 540, "centerY": 420},
                                 "textSelection": {"layerId": "t-styled", "start": 4, "end": 8}})

    # Machine-readable expectations (literal values; validate_fixtures.py recomputes and compares).
    L = lambda id_, type_, box=None, visible=True, locked=False: dict(
        {"id": id_, "type": type_, "visible": visible, "locked": locked}, **({"box": box} if box else {}))
    E = {
        "01-all-layer-types": {"result": "ok", "canvas": [1080, 1080, "#FFFFFFFF"], "layers": [
            L("img-photo", "image", [64, 48]), L("shape-rect", "shape", [600, 120]),
            L("draw-1", "drawing", [1080, 1080]), L("text-title", "text")], "strokeCounts": {"draw-1": 1}},
        "02-text-effects": {"result": "ok", "canvas": [1200, 1600, "#1E1E2EFF"], "layers": [
            L(i, "text") for i in ["t-meme", "t-shadow", "t-double", "t-glow", "t-linear", "t-radial",
                                   "t-curve-up", "t-curve-down", "t-curve-multi", "t-box", "t-case"]]},
        "03-rich-text-spans": {"result": "ok", "canvas": [1080, 1080, "#FFFFFFFF"], "layers": [
            L("t-mixed", "text"), L("t-italic-layer", "text"), L("t-weight", "text")],
            "spans": {"t-mixed": [[6, 11], [12, 15], [16, 21], [22, 26]],
                      "t-italic-layer": [[14, 23]], "t-weight": [[0, 12]]}},
        "04-flags-shapes-image": {"result": "ok", "canvas": [800, 600, "#00000000"], "layers": [
            L("img-adjusted", "image", [30, 40]), L("img-alpha", "image", [32, 32]),
            L("shape-rect", "shape", [160, 100]), L("shape-round", "shape", [160, 100]),
            L("shape-ellipse", "shape", [160, 100]), L("shape-line", "shape", [200, 6]),
            L("shape-arrow", "shape", [260, 12]), L("shape-hidden", "shape", [80, 80], visible=False),
            L("shape-locked", "shape", [60, 40], locked=True),
            L("t-hidden-locked", "text", visible=False, locked=True)]},
        "05-drawing-brushes": {"result": "ok", "canvas": [512, 512, "#FFFFFFFF"], "layers": [
            L("draw-brushes", "drawing", [512, 512]), L("draw-transformed", "drawing", [200, 100]),
            L("draw-no-file", "drawing", [512, 512])],
            "strokeCounts": {"draw-brushes": 9, "draw-transformed": 2, "draw-no-file": 0}},
        "06-missing-asset": {"result": "ok", "canvas": [600, 400, "#FFFFFFFF"], "layers": [
            L("img-missing", "image", [1600, 1200]), L("t-caption", "text")], "missingAssets": ["img-missing"]},
        "07-future-version": {"result": "newerVersion"},
        "12-mixed-styles": {"result": "ok", "canvas": [1080, 1080, "#1E1E2EFF"], "layers": [
            L("t-styled", "text"), L("t-solid", "text")],
            "spans": {"t-styled": [[0, 3], [4, 8], [9, 14], [15, 17], [24, 35]], "t-solid": [[6, 9]]}},
        "11-eraser-masks": {"result": "ok", "canvas": [800, 800, "#FFFFFFFF"], "layers": [
            L("img-masked", "image", [40, 48]), L("shape-masked", "shape", [200, 200]),
            L("shape-bad-mask", "shape", [150, 150]), L("draw-plain", "drawing", [400, 400]),
            L("t-masked", "text")],
            "strokeCounts": {"draw-plain": 1},
            "maskStrokeCounts": {"img-masked": 2, "shape-masked": 1, "shape-bad-mask": 0,
                                 "draw-plain": 0, "t-masked": 1}},
        "08-corrupt-truncated": {"result": "corrupt"},
        "09-corrupt-duplicate-ids": {"result": "corrupt"},
        "10-corrupt-bad-types": {"result": "corrupt"},
    }
    P = {
        "valid-12-mixed-styles.zip": {"result": "ok", "source": "12-mixed-styles", "editorState": True},
        "valid-11-eraser-masks.zip": {"result": "ok", "source": "11-eraser-masks", "editorState": False},
        "evil-paths.zip": {"result": "rejected",
                           "reasons": ["duplicateEntry", "symlink", "unexpectedEntry", "unsafePath"]},
        "evil-zip-bomb.zip": {"result": "rejected", "reasons": ["compressionRatio"]},
        "newer-package-version.zip": {"result": "rejected", "reasons": ["newerVersion"]},
        "not-a-package.zip": {"result": "rejected", "reasons": ["notAPackage"]},
    }
    with open(os.path.join(ROOT, "expectations.json"), "w") as fh:
        json.dump({"formatVersion": 1, "fixtures": E, "packages": P}, fh, indent=2)
        fh.write("\n")

    make_packages()
    print("fixtures written to", os.path.normpath(ROOT))


# ---------------------------------------------------------------- packages (§13)
ZDATE = (2026, 10, 9, 10, 0, 0)


def zadd(z, name, data, symlink=False, compress=zipfile.ZIP_DEFLATED):
    zi = zipfile.ZipInfo(name, ZDATE)
    zi.compress_type = compress
    zi.create_system = 3
    zi.external_attr = ((0o120777 if symlink else 0o100644) << 16)
    if isinstance(data, str):
        data = data.encode("utf-8")
    with warnings.catch_warnings():
        warnings.simplefilter("ignore")  # duplicate names are intentional in the evil package
        z.writestr(zi, data)


def manifest(platform="android", package_version=1, fv=1):
    return json.dumps({"package": "basicart-project", "packageVersion": package_version, "formatVersion": fv,
                       "appPlatform": platform, "appVersion": "1.0.0", "exported": "2026-10-09T10:00:00Z"}, indent=2)


def add_project(z, src):
    d = os.path.join(ROOT, src)
    for dp, _, files in sorted(os.walk(d)):
        for fn in sorted(files):
            full = os.path.join(dp, fn)
            rel = os.path.relpath(full, d).replace(os.sep, "/")
            with open(full, "rb") as fh:
                zadd(z, rel, fh.read())


def make_packages():
    pd = os.path.join(ROOT, "packages")
    os.makedirs(pd)

    def new(name):
        return zipfile.ZipFile(os.path.join(pd, name), "w")

    with new("valid-12-mixed-styles.zip") as z:          # incl. editor-state.json
        zadd(z, "basicart-package.json", manifest("ios"))
        add_project(z, "12-mixed-styles")
    with new("valid-11-eraser-masks.zip") as z:          # assets, strokes, masks (one bad), macOS junk
        zadd(z, "basicart-package.json", manifest("android"))
        zadd(z, "assets/", b"")
        add_project(z, "11-eraser-masks")
        zadd(z, "__MACOSX/._project.json", b"\x00\x05\x16\x07junk")
        zadd(z, ".DS_Store", b"junk")
    with new("evil-paths.zip") as z:
        zadd(z, "basicart-package.json", manifest())
        add_project(z, "12-mixed-styles")
        zadd(z, "../evil.txt", "escape")
        zadd(z, "/etc/abs.txt", "absolute")
        zadd(z, "assets/" + "0" * 64 + ".png", "/etc/passwd", symlink=True)
        zadd(z, "PROJECT.json", "{}")                    # duplicate of project.json (case-insensitive)
        zadd(z, "notes.txt", "not allowed")
    zeros = bytes(20 * 1024 * 1024)
    with new("evil-zip-bomb.zip") as z:
        zadd(z, "basicart-package.json", manifest())
        add_project(z, "12-mixed-styles")
        zadd(z, "assets/" + hashlib.sha256(zeros).hexdigest() + ".png", zeros)
    with new("newer-package-version.zip") as z:
        zadd(z, "basicart-package.json", manifest(package_version=2))
        add_project(z, "12-mixed-styles")
    with new("not-a-package.zip") as z:                  # a project zipped without a manifest
        add_project(z, "01-all-layer-types")


if __name__ == "__main__":
    main()
