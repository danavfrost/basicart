#!/usr/bin/env python3
"""Validates shared/ data against shared/project.schema.json + the semantic rules in FORMAT.md.

Stdlib only. Run from anywhere:  python3 shared/tools/validate_fixtures.py
Exit code 0 = everything matches shared/fixtures/expectations.json.

Classification mirrors what the apps must do (FORMAT.md §10.2):
  parse JSON -> formatVersion int > SUPPORTED => "newerVersion" -> schema + semantics => "ok" | "corrupt"
"""
import hashlib
import json
import os
import shutil
import tempfile
import zipfile
import re
import struct
import sys
import unicodedata

SHARED = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
FIXTURES = os.path.join(SHARED, "fixtures")
SUPPORTED = 1


# ------------------------------------------------------------ mini JSON Schema (subset)
class SchemaValidator:
    """Supports: $ref (#/...), allOf, oneOf, anyOf, const, enum, type, properties, required,
    items, minItems, maxItems, minimum, maximum, minLength, maxLength, pattern."""

    def __init__(self, root):
        self.root = root

    def resolve(self, ref):
        node = self.root
        for part in ref.lstrip("#/").split("/"):
            node = node[part]
        return node

    def errors(self, inst, sch, path="$"):
        if "$ref" in sch:
            return self.errors(inst, self.resolve(sch["$ref"]), path)
        errs = []
        for sub in sch.get("allOf", []):
            errs += self.errors(inst, sub, path)
        if "oneOf" in sch:
            results = [self.errors(inst, s, path) for s in sch["oneOf"]]
            ok = [r for r in results if not r]
            if len(ok) != 1:
                if not ok:  # report the branch with the fewest errors
                    errs += min(results, key=len)
                else:
                    errs.append("%s: matches %d oneOf branches" % (path, len(ok)))
        if "anyOf" in sch and all(self.errors(inst, s, path) for s in sch["anyOf"]):
            errs.append("%s: matches no anyOf branch" % path)
        if "const" in sch and (inst != sch["const"] or type(inst) != type(sch["const"])):
            errs.append("%s: expected const %r, got %r" % (path, sch["const"], inst))
        if "enum" in sch and inst not in sch["enum"]:
            errs.append("%s: %r not in %r" % (path, inst, sch["enum"]))
        t = sch.get("type")
        if t and not self.type_ok(inst, t):
            return errs + ["%s: expected %s, got %s" % (path, t, type(inst).__name__)]
        if isinstance(inst, dict):
            for k in sch.get("required", []):
                if k not in inst:
                    errs.append("%s: missing required '%s'" % (path, k))
            for k, sub in sch.get("properties", {}).items():
                if k in inst:
                    errs += self.errors(inst[k], sub, "%s.%s" % (path, k))
        if isinstance(inst, list):
            if len(inst) < sch.get("minItems", 0):
                errs.append("%s: fewer than %d items" % (path, sch["minItems"]))
            if "maxItems" in sch and len(inst) > sch["maxItems"]:
                errs.append("%s: more than %d items" % (path, sch["maxItems"]))
            if "items" in sch:
                for i, v in enumerate(inst):
                    errs += self.errors(v, sch["items"], "%s[%d]" % (path, i))
        if isinstance(inst, (int, float)) and not isinstance(inst, bool):
            if "minimum" in sch and inst < sch["minimum"]:
                errs.append("%s: %r < minimum %r" % (path, inst, sch["minimum"]))
            if "maximum" in sch and inst > sch["maximum"]:
                errs.append("%s: %r > maximum %r" % (path, inst, sch["maximum"]))
        if isinstance(inst, str):
            if len(inst) < sch.get("minLength", 0) or len(inst) > sch.get("maxLength", 10 ** 9):
                errs.append("%s: bad length" % path)
            if "pattern" in sch and not re.search(sch["pattern"], inst):
                errs.append("%s: %r does not match %s" % (path, inst, sch["pattern"]))
        return errs

    @staticmethod
    def type_ok(v, t):
        return {
            "object": lambda: isinstance(v, dict),
            "array": lambda: isinstance(v, list),
            "string": lambda: isinstance(v, str),
            "boolean": lambda: isinstance(v, bool),
            "integer": lambda: isinstance(v, int) and not isinstance(v, bool)
            or (isinstance(v, float) and v.is_integer()),
            "number": lambda: isinstance(v, (int, float)) and not isinstance(v, bool),
        }[t]()


# ------------------------------------------------------------ helpers
def png_size(path):
    with open(path, "rb") as f:
        head = f.read(24)
    if head[:8] != b"\x89PNG\r\n\x1a\n":
        return None
    return struct.unpack(">II", head[16:24])


def u16(s):
    return s.encode("utf-16-le")


def u16len(s):
    return len(u16(s)) // 2


def is_grapheme_boundary(text, off):
    """Conservative check: offset is not inside a surrogate pair and does not split a
    base from a combining mark / variation selector / ZWJ sequence / skin-tone modifier."""
    b = u16(text)
    n = len(b) // 2
    if off <= 0 or off >= n:
        return True
    unit = struct.unpack("<H", b[off * 2: off * 2 + 2])[0]
    if 0xDC00 <= unit <= 0xDFFF:
        return False
    after = b[off * 2:].decode("utf-16-le")[:1]
    before = b[: off * 2].decode("utf-16-le")[-1:]
    cp = ord(after)
    if unicodedata.combining(after) or 0xFE00 <= cp <= 0xFE0F or cp == 0x200D or 0x1F3FB <= cp <= 0x1F3FF:
        return False
    if before == "‍":
        return False
    return True


def box_size(layer):
    t = layer["type"]
    if t == "image":
        c = layer.get("crop") or {"width": layer["naturalWidth"], "height": layer["naturalHeight"]}
        w, h = c["width"], c["height"]
        return [h, w] if layer.get("rotate90", 0) % 2 else [w, h]
    if t in ("drawing", "shape"):
        return [layer["width"], layer["height"]]
    return None


FLAGS = ("bold", "italic", "underline", "strike")
SPAN_FIELDS = FLAGS + ("color", "fontId", "weight", "size")


def semantic_errors(doc, folder):
    """Rules from FORMAT.md that a structural schema can't express."""
    errs, warns = [], []
    ids = [l.get("id") for l in doc["layers"]]
    dup = sorted({i for i in ids if ids.count(i) > 1})
    if dup:
        errs.append("duplicate layer ids: %s" % dup)
    if doc["id"] != os.path.basename(folder):
        warns.append("project id %r != folder name (folder wins)" % doc["id"])
    for l in doc["layers"]:
        lid, t = l["id"], l["type"]
        if t == "image":
            nw, nh = l["naturalWidth"], l["naturalHeight"]
            c = l.get("crop")
            if c and (c["x"] + c["width"] > nw or c["y"] + c["height"] > nh):
                errs.append("%s: crop exceeds natural size" % lid)
            ap = os.path.join(folder, "assets", l["assetRef"])
            if not os.path.isfile(ap):
                warns.append("%s: asset missing -> placeholder (§10.3)" % lid)
            else:
                data = open(ap, "rb").read()
                if hashlib.sha256(data).hexdigest() != l["assetRef"].split(".")[0]:
                    errs.append("%s: asset sha256 does not match file name" % lid)
                if l["assetRef"].endswith(".png") and png_size(ap) != (nw, nh):
                    errs.append("%s: PNG is %s, layer says %dx%d" % (lid, png_size(ap), nw, nh))
        elif t == "text":
            if l.get("autoWidth") is False and not l.get("boxWidth", 0) >= 1:
                errs.append("%s: autoWidth false needs boxWidth >= 1" % lid)
            fill = l.get("fill", {})
            if "stops" in fill:
                offs = [s["offset"] for s in fill["stops"]]
                if offs != sorted(offs):
                    warns.append("%s: gradient stops unsorted (readers sort)" % lid)
            text, spans = l["text"], l.get("spans", [])
            n = u16len(text)
            fill = l.get("fill", {"type": "solid", "color": "#000000FF"})
            layer_val = {f: l.get(f, False) for f in FLAGS}
            layer_val.update(fontId=l.get("fontId", "inter"), weight=l.get("weight", 400),
                             size=l.get("fontSize", 64))
            if fill.get("type") == "solid":
                layer_val["color"] = fill["color"].upper()
            prev = None
            for i, s in enumerate(spans):
                where = "%s.spans[%d]" % (lid, i)
                if not (0 <= s["start"] < s["end"] <= n):
                    errs.append("%s: range out of bounds (text has %d UTF-16 units)" % (where, n))
                    continue
                for off in (s["start"], s["end"]):
                    if not is_grapheme_boundary(text, off):
                        errs.append("%s: offset %d is not a grapheme boundary" % (where, off))
                style = {k2: v for k2, v in s.items() if k2 in SPAN_FIELDS}
                if not style:
                    errs.append("%s: not normalized (no style fields)" % where)
                for k2, v in style.items():
                    if k2 in layer_val and (v.upper() if k2 == "color" else v) == layer_val[k2]:
                        errs.append("%s: not normalized (%s equals layer value)" % (where, k2))
                if prev:
                    if s["start"] < prev["end"]:
                        errs.append("%s: overlaps or unsorted" % where)
                    elif s["start"] == prev["end"] and style == {k2: v for k2, v in prev.items() if k2 in SPAN_FIELDS}:
                        errs.append("%s: not normalized (should merge with previous)" % where)
                prev = s
        if t != "drawing":
            mp = os.path.join(folder, "strokes", lid + ".mask.json")
            if os.path.isfile(mp) and mask_count(folder, l) == 0:
                warns.append("%s: mask file invalid -> no mask (§10.3)" % lid)
        elif os.path.isfile(os.path.join(folder, "strokes", lid + ".mask.json")):
            warns.append("%s: drawing layer has a mask file -> ignored (§9.1)" % lid)
        if t == "drawing":
            sp = os.path.join(folder, "strokes", lid + ".json")
            if os.path.isfile(sp):
                try:
                    sdoc = json.load(open(sp, encoding="utf-8"))
                except ValueError as e:
                    warns.append("%s: strokes file invalid JSON -> empty layer (%s)" % (lid, e))
                    continue
                se = V.errors(sdoc, {"$ref": "#/$defs/strokesFile"}, "strokes/%s.json" % lid)
                if sdoc.get("layerId") != lid:
                    se.append("strokes/%s.json: layerId mismatch" % lid)
                for j, st in enumerate(sdoc.get("strokes", [])):
                    pts = st.get("points", [])
                    if len(pts) % 2:
                        se.append("%s stroke %d: odd points length" % (lid, j))
                    if "pressure" in st and len(st["pressure"]) != len(pts) // 2:
                        se.append("%s stroke %d: pressure count != point count" % (lid, j))
                errs += se
    return errs, warns


def mask_count(folder, layer):
    """Effective mask stroke count as an app must compute it (§9.1, §10.3)."""
    if layer["type"] == "drawing":
        return 0
    p = os.path.join(folder, "strokes", layer["id"] + ".mask.json")
    if not os.path.isfile(p):
        return 0
    try:
        doc = json.load(open(p, encoding="utf-8"))
    except ValueError:
        return 0
    if V.errors(doc, {"$ref": "#/$defs/maskFile"}) or doc.get("layerId") != layer["id"]:
        return 0
    return len(doc["strokes"])


PENCIL_GRAIN_SHA256 = "84e46aa5abb160be583d2f609760f24f6964dd7dde7af5b556cf6ad3fa8b9bc1"


def pencil_grain():
    """FORMAT.md §9.2 reference implementation: 1024 alpha bytes, row-major 32x32."""
    seed, out = 1, bytearray()
    for _ in range(1024):
        seed = (seed * 1103515245 + 12345) % (1 << 31)
        out.append((11475 + 55 * ((seed >> 16) & 0xFF)) // 100)
    return bytes(out)


# ------------------------------------------------------------ project packages (§13)
MAX_ENTRIES, MAX_TOTAL, MAX_ENTRY, RATIO_MIN, MAX_RATIO = 10000, 1 << 30, 256 << 20, 1 << 20, 100
ALLOWED = [re.compile(x) for x in (
    r"^basicart-package\.json$", r"^project\.json$", r"^thumb\.png$", r"^editor-state\.json$",
    r"^assets/$", r"^strokes/$", r"^assets/[0-9a-f]{64}\.(png|jpg|webp|gif|heic)$",
    r"^strokes/[A-Za-z0-9_-]{1,64}(\.mask)?\.json$")]


def is_junk(name):
    return name.startswith("__MACOSX/") or name.rsplit("/", 1)[-1] == ".DS_Store"


def inspect_package(path):
    """Reference implementation of FORMAT.md §13.2. Returns (result, reasons, extracted_dir_or_None).
    Collects every central-directory reason (apps may stop at the first)."""
    reasons = set()
    try:
        z = zipfile.ZipFile(path)
        infos = z.infolist()
    except (zipfile.BadZipFile, OSError):
        return "rejected", ["corruptZip"], None
    seen, total = set(), 0
    if len(infos) > MAX_ENTRIES:
        reasons.add("tooManyEntries")
    for i in infos:
        n = i.filename
        segs = n.rstrip("/").split("/")
        if (n.startswith("/") or "\\" in n or re.match(r"^[A-Za-z]:", n) or "\x00" in n
                or ".." in segs or "" in segs):
            reasons.add("unsafePath")
        if (i.external_attr >> 16) & 0o170000 == 0o120000:
            reasons.add("symlink")
        key = n.rstrip("/").lower()
        if key in seen:
            reasons.add("duplicateEntry")
        seen.add(key)
        if i.flag_bits & 0x1:
            reasons.add("encrypted")
        if not is_junk(n) and not any(r.match(n) for r in ALLOWED):
            reasons.add("unexpectedEntry")
        total += i.file_size
        if i.file_size > MAX_ENTRY:
            reasons.add("tooLarge")
        if i.file_size >= RATIO_MIN and i.file_size > MAX_RATIO * max(i.compress_size, 1):
            reasons.add("compressionRatio")
    if total > MAX_TOTAL:
        reasons.add("tooLarge")
    if reasons:
        return "rejected", sorted(reasons), None
    try:
        man = json.loads(z.read("basicart-package.json").decode("utf-8"))
    except (KeyError, ValueError):
        return "rejected", ["notAPackage"], None
    if not isinstance(man, dict) or man.get("package") != "basicart-project":
        return "rejected", ["notAPackage"], None
    if V.errors(man, {"$ref": "#/$defs/packageManifest"}):
        return "rejected", ["notAPackage"], None
    if man["packageVersion"] > 1 or man["formatVersion"] > SUPPORTED:
        return "rejected", ["newerVersion"], None
    tmp = tempfile.mkdtemp(prefix=".import-")
    try:
        for i in infos:
            if is_junk(i.filename) or i.filename.endswith("/"):
                continue
            out = os.path.join(tmp, *i.filename.split("/"))
            os.makedirs(os.path.dirname(out), exist_ok=True)
            written = 0
            with z.open(i) as src, open(out, "wb") as dst:  # count real bytes, don't trust headers
                while True:
                    chunk = src.read(1 << 16)
                    if not chunk:
                        break
                    written += len(chunk)
                    if written > MAX_ENTRY or (written >= RATIO_MIN and written > MAX_RATIO * max(i.compress_size, 1)):
                        raise ValueError("compressionRatio")
                    dst.write(chunk)
        result, doc, errs, _ = classify(tmp)
        if result == "newerVersion":
            raise ValueError("newerVersion")
        if result != "ok":
            raise ValueError("corruptProject")
        if doc["formatVersion"] != man["formatVersion"]:
            raise ValueError("corruptProject")
        return "ok", [], tmp
    except ValueError as e:
        shutil.rmtree(tmp, ignore_errors=True)  # leave nothing behind
        return "rejected", [str(e)], None


def stroke_count(folder, lid):
    p = os.path.join(folder, "strokes", lid + ".json")
    if not os.path.isfile(p):
        return 0
    return len(json.load(open(p, encoding="utf-8"))["strokes"])


def classify(folder):
    """Returns (result, doc_or_None, errors, warnings)."""
    p = os.path.join(folder, "project.json")
    try:
        with open(p, encoding="utf-8") as f:
            doc = json.load(f)
    except (OSError, ValueError) as e:
        return "corrupt", None, ["cannot parse project.json: %s" % e], []
    if not isinstance(doc, dict):
        return "corrupt", None, ["project.json is not an object"], []
    fv = doc.get("formatVersion")
    if isinstance(fv, int) and not isinstance(fv, bool) and fv > SUPPORTED:
        return "newerVersion", doc, [], []
    errs = V.errors(doc, SCHEMA)
    warns = []
    if not errs:
        e2, warns = semantic_errors(doc, folder)
        errs += e2
    return ("corrupt" if errs else "ok"), doc, errs, warns


# ------------------------------------------------------------ main
SCHEMA = json.load(open(os.path.join(SHARED, "project.schema.json"), encoding="utf-8"))
V = SchemaValidator(SCHEMA)


def check_shared_file(rel, defname, required=True):
    p = os.path.join(SHARED, rel)
    if not os.path.isfile(p):
        return ([] if not required else ["%s missing" % rel]), None
    try:
        doc = json.load(open(p, encoding="utf-8"))
    except ValueError as e:
        return ["%s: invalid JSON: %s" % (rel, e)], None
    return V.errors(doc, {"$ref": "#/$defs/" + defname}, rel), doc


def main():
    failures, notes = [], []
    exp_all = json.load(open(os.path.join(FIXTURES, "expectations.json"), encoding="utf-8"))
    exp = exp_all["fixtures"]
    folders = sorted(d for d in os.listdir(FIXTURES)
                     if os.path.isdir(os.path.join(FIXTURES, d)) and d not in ("packages", "layout"))
    for name in sorted(set(folders) | set(exp)):
        folder = os.path.join(FIXTURES, name)
        e = exp.get(name)
        if e is None:
            failures.append("%s: no entry in expectations.json" % name)
            continue
        if name not in folders:
            failures.append("%s: folder missing" % name)
            continue
        result, doc, errs, warns = classify(folder)
        fails = []
        if result != e["result"]:
            fails.append("classified %s, expected %s; errors: %s" % (result, e["result"], errs[:5]))
        elif result == "ok":
            cv = doc["canvas"]
            if [cv["width"], cv["height"], cv.get("background", "#FFFFFFFF")] != e["canvas"]:
                fails.append("canvas mismatch")
            got = [{"id": l["id"], "type": l["type"], "visible": l.get("visible", True),
                    "locked": l.get("locked", False)} for l in doc["layers"]]
            want = [{k: v for k, v in x.items() if k != "box"} for x in e["layers"]]
            if got != want:
                fails.append("layers mismatch:\n    got  %s\n    want %s" % (got, want))
            for l, x in zip(doc["layers"], e["layers"]):
                if "box" in x and box_size(l) != x["box"]:
                    fails.append("%s: box %s, expected %s" % (l["id"], box_size(l), x["box"]))
            byid = {l["id"]: l for l in doc["layers"]}
            for lid, rngs in e.get("spans", {}).items():
                got_r = [[s["start"], s["end"]] for s in byid[lid].get("spans", [])]
                if got_r != rngs:
                    fails.append("%s: spans %s, expected %s" % (lid, got_r, rngs))
            for lid, n in e.get("strokeCounts", {}).items():
                if stroke_count(folder, lid) != n:
                    fails.append("%s: %d strokes, expected %d" % (lid, stroke_count(folder, lid), n))
            for lid, n in e.get("maskStrokeCounts", {}).items():
                if mask_count(folder, byid[lid]) != n:
                    fails.append("%s: %d mask strokes, expected %d" % (lid, mask_count(folder, byid[lid]), n))
            for lid in e.get("missingAssets", []):
                if os.path.isfile(os.path.join(folder, "assets", byid[lid]["assetRef"])):
                    fails.append("%s: asset should be missing" % lid)
        status = "FAIL" if fails else "ok  "
        detail = "" if result == "ok" else "  (" + (errs[0] if errs else "formatVersion %s" % doc.get("formatVersion")) + ")"
        print("%s %-28s -> %-12s%s" % (status, name, result, detail[:110]))
        for w in warns:
            print("       note: " + w)
        failures += ["%s: %s" % (name, f) for f in fails]

    # Project packages (§13)
    for name, e in sorted(exp_all.get("packages", {}).items()):
        path = os.path.join(FIXTURES, "packages", name)
        if not os.path.isfile(path):
            failures.append("packages/%s: missing" % name)
            continue
        result, reasons, tmp = inspect_package(path)
        fails = []
        if result != e["result"]:
            fails.append("got %s %s, expected %s" % (result, reasons, e["result"]))
        elif result == "rejected" and sorted(reasons) != sorted(e["reasons"]):
            fails.append("reasons %s, expected %s" % (reasons, e["reasons"]))
        elif result == "ok":
            src = os.path.join(FIXTURES, e["source"])
            a = json.load(open(os.path.join(tmp, "project.json"), encoding="utf-8"))
            b = json.load(open(os.path.join(src, "project.json"), encoding="utf-8"))
            if a != b:
                fails.append("project.json differs from source fixture %s" % e["source"])
            has_es = os.path.isfile(os.path.join(tmp, "editor-state.json"))
            if has_es != e["editorState"]:
                fails.append("editor-state.json presence %s, expected %s" % (has_es, e["editorState"]))
            if has_es:
                es = json.load(open(os.path.join(tmp, "editor-state.json"), encoding="utf-8"))
                ee = V.errors(es, {"$ref": "#/$defs/editorState"}, "editor-state.json")
                if ee:
                    fails.append("editor-state.json: %s" % ee[:3])
            for dp, _, fs in os.walk(tmp):
                for fn in fs:
                    if fn == ".DS_Store" or "__MACOSX" in dp:
                        fails.append("junk entry was extracted")
            shutil.rmtree(tmp, ignore_errors=True)
        print("%s packages/%-24s -> %-9s %s" % ("FAIL" if fails else "ok  ", name, result, ",".join(reasons)))
        failures += ["packages/%s: %s" % (name, f) for f in fails]

    # editor-state.json inside fixture folders (optional; invalid = ignored, so notes only)
    for name in folders:
        ep = os.path.join(FIXTURES, name, "editor-state.json")
        if os.path.isfile(ep):
            try:
                ee = V.errors(json.load(open(ep, encoding="utf-8")), {"$ref": "#/$defs/editorState"})
            except ValueError as x:
                ee = [str(x)]
            print("%s %s/editor-state.json" % ("ok  " if not ee else "WARN", name))
            notes += ["%s/editor-state.json: %s" % (name, x) for x in ee]

    # Layout parity cases (§7.10) + font metrics (§7.3 step 7)
    lc = json.load(open(os.path.join(FIXTURES, "layout", "cases.json"), encoding="utf-8"))
    mfile = os.path.join(SHARED, "fonts", "metrics.json")
    font_ids = set()
    fj = os.path.join(SHARED, "fonts", "fonts.json")
    if os.path.isfile(fj):
        fcat = json.load(open(fj, encoding="utf-8"))
        font_ids = {f["id"] for f in fcat["fonts"]}
        if os.path.isfile(mfile):
            mpaths = set(json.load(open(mfile, encoding="utf-8"))["files"])
            missing = {x["path"] for f in fcat["fonts"] for x in f["files"]} - mpaths
            if missing:
                failures.append("fonts/metrics.json stale: %d font files missing (run font_metrics.py)" % len(missing))
        else:
            failures.append("fonts/metrics.json missing (run shared/tools/font_metrics.py)")
    lerr, seen_ids = [], set()
    for case in lc["cases"]:
        if case["id"] in seen_ids:
            lerr.append("duplicate case id %s" % case["id"])
        seen_ids.add(case["id"])
        L = case["layer"]
        lerr += V.errors(L, {"$ref": "#/$defs/textProps"}, "layout/%s" % case["id"])
        fake = dict(L, id="x", type="text", transform={"x": 0, "y": 0, "scale": 1, "rotation": 0})
        e2, _ = semantic_errors({"id": "layout", "layers": [fake]}, os.path.join(FIXTURES, "layout"))
        lerr += ["layout/%s: %s" % (case["id"], x) for x in e2]
        for fid in [L.get("fontId", "inter")] + [sp.get("fontId") for sp in L.get("spans", []) if sp.get("fontId")]:
            if font_ids and fid not in font_ids:
                lerr.append("layout/%s: unknown fontId %s" % (case["id"], fid))
    print("%s layout/cases.json (%d cases)" % ("FAIL" if lerr else "ok  ", len(lc["cases"])))
    failures += lerr

    # Pencil grain reference tile (§9.2)
    g = pencil_grain()
    ok = hashlib.sha256(g).hexdigest() == PENCIL_GRAIN_SHA256 and list(g[:8]) == [223, 184, 185, 173, 156, 252, 239, 252]
    print("%s pencil grain tile (§9.2)" % ("ok  " if ok else "FAIL"))
    if not ok:
        failures.append("pencil grain tile checksum mismatch")

    # Shared data files
    for rel, d, req in [("palettes/palettes.json", "palettesFile", True),
                        ("presets/text-presets.json", "presetsFile", True),
                        ("fonts/fonts.json", "fontsFile", False)]:
        errs, doc = check_shared_file(rel, d, req)
        if rel.startswith("fonts") and doc is None and not errs:
            notes.append("fonts/fonts.json not present yet (fonts agent) — skipped")
            continue
        if rel.startswith("fonts") and doc is not None:
            fonts = doc.get("fonts", [])
            ids = [f.get("id") for f in fonts]
            gids = [g.get("id") for g in doc.get("groups", [])]
            for g in doc.get("groups", []):
                if g.get("sampleFontId") not in ids:
                    errs.append("group %s: sampleFontId %r unknown" % (g.get("id"), g.get("sampleFontId")))
            for f in fonts:
                if f.get("group") not in gids:
                    errs.append("font %s: group %r unknown" % (f.get("id"), f.get("group")))
                keys = [(x.get("weight"), x.get("italic")) for x in f.get("files", [])]
                if len(keys) != len(set(keys)):
                    errs.append("font %s: duplicate (weight, italic)" % f.get("id"))
                for x in f.get("files", []) + [f.get("license", {})]:
                    path = x.get("path") or x.get("file")
                    if path and not os.path.isfile(os.path.join(SHARED, "fonts", path)):
                        errs.append("font %s: file %s missing" % (f.get("id"), path))
            if len(ids) != len(set(ids)):
                errs.append("duplicate font ids")
            for pid in ("inter", "anton", "bebas-neue", "caveat", "permanent-marker", "bungee"):
                if pid not in ids:
                    errs.append("planned font id %r missing" % pid)
            # fonts.json is owned by another agent: report, don't fail the fixture run
            for x in errs:
                notes.append("fonts.json: " + x)
            print("%s %s" % ("ok  " if not errs else "WARN", rel))
            continue
        print("%s %s" % ("ok  " if not errs else "FAIL", rel))
        failures += errs
    for n in notes:
        print("note: " + n)
    if failures:
        print("\n%d FAILURE(S):" % len(failures))
        for f in failures:
            print("  - " + f)
        return 1
    print("\nAll fixtures and shared data match expectations.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
