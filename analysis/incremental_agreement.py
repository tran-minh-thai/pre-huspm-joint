#!/usr/bin/env python3
"""Do the incremental algorithms report the same number of HUSPs after every operation?

    python3 analysis/incremental_agreement.py [--out DIR]

Compares total_patterns_found of every algorithm with Adaptive-Full, row by row, on the
scenarios with an update stream (SC2, SC5), over the timing campaign. A row is compared only
when both algorithms finished it (runtime_ms >= 0). Prints, per scenario, algorithm and
database, how many rows were compared and how many report fewer or more patterns.
Counters only; machine-independent.
"""
import argparse
import collections
import csv
import glob
import os

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OTHERS = ["Remine-from-scratch", "Pre-HUSPM", "Pre-HUSPM-TSU", "IncUSP-Miner+"]


def rows(sc, alg):
    out = {}
    for f in glob.glob(os.path.join(REPO, "results", "campaign", sc, "*", alg, "*.csv")):
        with open(f, newline="", encoding="utf-8") as fh:
            for r in csv.DictReader(fh):
                r = {k.strip(): (v or "").strip() for k, v in r.items() if k}
                if float(r["runtime_ms"]) < 0:
                    continue
                out[(r["dataset"], r["op_type"], r.get("iteration"))] = int(float(r["total_patterns_found"]))
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=os.path.join(REPO, "results", "invariant-incremental-agreement"))
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    lines = [["scenario", "algorithm", "database", "rows_compared", "fewer", "more", "max_share_missing"]]
    for sc in ["SC2", "SC5"]:
        ref = rows(sc, "Adaptive-Full")
        for alg in OTHERS:
            other = rows(sc, alg)
            per = collections.defaultdict(lambda: [0, 0, 0, 0.0])
            for k in set(ref) & set(other):
                p = per[k[0]]
                p[0] += 1
                if other[k] < ref[k]:
                    p[1] += 1
                    p[3] = max(p[3], (ref[k] - other[k]) / ref[k])
                elif other[k] > ref[k]:
                    p[2] += 1
            for db, p in sorted(per.items()):
                lines.append([sc, alg, db, p[0], p[1], p[2], f"{p[3]:.4f}"])
    with open(os.path.join(a.out, "agreement.csv"), "w", newline="", encoding="utf-8") as fh:
        csv.writer(fh, lineterminator="\n").writerows(lines)
    for ln in lines:
        print("\t".join(str(x) for x in ln))


if __name__ == "__main__":
    main()
