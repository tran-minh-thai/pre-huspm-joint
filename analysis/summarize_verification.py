#!/usr/bin/env python3
"""Markdown table of a DirectPathVerifier run, read from its verification.csv.

    python3 analysis/summarize_verification.py results/verify-joint/adversarial/verification.csv

One row per database: operations by kind and path, the direct insertions that follow a
direct deletion or a direct modification (the case the earlier rule got wrong), and the
mismatches against remining. A final row sums the columns. Standard library only.
"""
import csv
import sys
from collections import OrderedDict


def main(path):
    rows = list(csv.DictReader(open(path, encoding="utf-8")))
    if not rows:
        sys.exit(f"{path}: no rows")
    by = OrderedDict()
    for r in rows:
        by.setdefault(r["dataset"], []).append(r)
    cols = ["operations", "insert direct", "insert rescan", "delete direct", "delete rescan",
            "modify direct", "modify rescan", "direct insert after direct delete",
            "direct insert after direct modify", "mismatches"]
    total = dict.fromkeys(cols, 0)
    print("| database | " + " | ".join(cols) + " |")
    print("|---|" + "---:|" * len(cols))
    for ds, rs in by.items():
        c = dict.fromkeys(cols, 0)
        c["operations"] = len(rs)
        for r in rs:
            c[f"{r['op_type']} {r['path']}"] += 1
            if r["op_type"] == "insert" and r["path"] == "direct":
                if r["previous_path"] == "delete/direct":
                    c["direct insert after direct delete"] += 1
                if r["previous_path"] == "modify/direct":
                    c["direct insert after direct modify"] += 1
            if r["clean"] != "true":
                c["mismatches"] += 1
        for k in cols:
            total[k] += c[k]
        print(f"| {ds} | " + " | ".join(str(c[k]) for k in cols) + " |")
    print("| all | " + " | ".join(f"**{total[k]}**" for k in cols) + " |")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    main(sys.argv[1])
