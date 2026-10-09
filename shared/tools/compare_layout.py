#!/usr/bin/env python3
"""Compares two layout-results-*.json files (FORMAT.md §7.10). Stdlib only.

    python3 shared/tools/compare_layout.py layout-results-android.json layout-results-ios.json

Line count and per-line start/end must match exactly; w, h and per-line x, width, top,
height, baseline within ±TOL px. Exit 0 = parity.
"""
import json
import os
import sys

TOL = 1.0
CASES = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "fixtures", "layout", "cases.json")


def main(a_path, b_path):
    a, b = (json.load(open(p, encoding="utf-8")) for p in (a_path, b_path))
    ids = [c["id"] for c in json.load(open(CASES, encoding="utf-8"))["cases"]]
    ca, cb = ({c["id"]: c for c in x["cases"]} for x in (a, b))
    la, lb = a.get("platform", "A"), b.get("platform", "B")
    bad = 0
    for cid in ids:
        if cid not in ca or cid not in cb:
            print("FAIL %-24s missing in %s" % (cid, la if cid not in ca else lb))
            bad += 1
            continue
        x, y = ca[cid], cb[cid]
        errs = []
        for k in ("w", "h"):
            if abs(x[k] - y[k]) > TOL:
                errs.append("%s %.2f vs %.2f" % (k, x[k], y[k]))
        if len(x["lines"]) != len(y["lines"]):
            errs.append("lines %d vs %d" % (len(x["lines"]), len(y["lines"])))
        for i, (p, q) in enumerate(zip(x["lines"], y["lines"])):
            if (p["start"], p["end"]) != (q["start"], q["end"]):
                errs.append("line %d range [%d,%d) vs [%d,%d)" % (i, p["start"], p["end"], q["start"], q["end"]))
            for k in ("x", "width", "top", "height", "baseline"):
                if abs(p[k] - q[k]) > TOL:
                    errs.append("line %d %s %.2f vs %.2f" % (i, k, p[k], q[k]))
        print("%s %-24s %s" % ("FAIL" if errs else "ok  ", cid, "; ".join(errs[:4])))
        bad += bool(errs)
    print("\n%s vs %s: %d/%d cases differ (tolerance ±%g px)" % (la, lb, bad, len(ids), TOL))
    return 1 if bad else 0


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    sys.exit(main(sys.argv[1], sys.argv[2]))
