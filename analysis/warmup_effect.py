#!/usr/bin/env python3
"""Effect of the static warm-up on each algorithm's runtime: the static timing runs measured
without it (read from git at COLD_COMMIT, the last commit that holds them) against the runs
measured with it (the working tree).

    python3 analysis/warmup_effect.py [--cold-commit 2a7997c4] [--out results/static-warmup-effect]

For SC1, SC3 and SC7's static arms, per (scenario, database, threshold, algorithm), the mean
runtime over the replicates without and with the warm-up, and their ratio; then per scenario and
algorithm the geometric mean, minimum and maximum of that ratio. Counters (patterns, explored
nodes, candidates) must agree between the two: a difference ends the run with exit status 1, since
the warm-up may change when a mine is timed, never what it computes. Writes warmup_effect.csv
(per cell) and warmup_effect_summary.csv (per scenario and algorithm).
"""
import argparse
import csv
import io
import math
import os
import pathlib
import subprocess
import sys
from collections import defaultdict

ROOT = pathlib.Path(__file__).resolve().parent.parent
TREES = ["campaign", "campaign-replicates/s0r1", "campaign-replicates/s0r2"]
SC7_STATIC = {"Pre-HUSPM-Joint", "Pre-HUSPM-Joint-no-IUB", "Pre-HUSPM-Joint-no-IPS", "Pre-HUSPM"}
COUNTERS = ("total_patterns_found", "explored_nodes", "candidate_count")


def static_csv(path):
    parts = pathlib.PurePosixPath(path).parts
    sc = next((p for p in parts if p in ("SC1", "SC3", "SC7")), None)
    return sc if sc and (sc != "SC7" or parts[-2] in SC7_STATIC) and path.endswith(".csv") else None


def rows_from(text, sc):
    for r in csv.DictReader(io.StringIO(text)):
        if float(r["runtime_ms"]) < 0:
            continue
        if sc == "SC7" and r["op_type"] not in ("SC7", "SC7_ref"):
            continue
        yield (sc, r["dataset"], r["op_type"], r["algorithm"]), r


def cold(commit):
    out = defaultdict(list)
    names = subprocess.run(["git", "ls-tree", "-r", "--name-only", commit, "results"], cwd=ROOT,
                           capture_output=True, text=True, check=True).stdout.split()
    for n in names:
        if not any(n.startswith(f"results/{t}/") for t in TREES):
            continue
        sc = static_csv(n)
        if sc is None:
            continue
        text = subprocess.run(["git", "show", f"{commit}:{n}"], cwd=ROOT, capture_output=True, text=True,
                              check=True).stdout
        for k, r in rows_from(text, sc):
            out[k].append(r)
    return out


def warm():
    out = defaultdict(list)
    for t in TREES:
        for p in (ROOT / "results" / t).rglob("*.csv"):
            rel = p.relative_to(ROOT).as_posix()
            sc = static_csv(rel)
            if sc is None:
                continue
            for k, r in rows_from(p.read_text(encoding="utf-8"), sc):
                out[k].append(r)
    return out


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--cold-commit", default="2a7997c4")
    ap.add_argument("--out", default=str(ROOT / "results" / "static-warmup-effect"))
    a = ap.parse_args()
    c, w = cold(a.cold_commit), warm()
    cells, bad, compared = [], 0, 0
    for k in sorted(set(c) & set(w)):
        for col in COUNTERS:
            vals = {r[col] for r in c[k] + w[k]}
            compared += 1
            if len(vals) != 1:
                bad += 1
                print(f"counter {col} differs at {k}: {sorted(vals)}")
        mc = sum(float(r["runtime_ms"]) for r in c[k]) / len(c[k])
        mw = sum(float(r["runtime_ms"]) for r in w[k]) / len(w[k])
        cells.append(dict(scenario=k[0], dataset=k[1], op_type=k[2], algorithm=k[3], runs_cold=len(c[k]),
                          runs_warm=len(w[k]), cold_ms=f"{mc:.3f}", warm_ms=f"{mw:.3f}",
                          warm_over_cold=f"{mw / mc:.6f}" if mc > 0 else ""))
    only = sorted(set(c) ^ set(w))
    for k in only:
        print(f"cell in one side only: {k}")
    summ = defaultdict(list)
    for x in cells:
        if x["warm_over_cold"]:
            summ[(x["scenario"], x["algorithm"])].append(float(x["warm_over_cold"]))
    os.makedirs(a.out, exist_ok=True)
    with open(os.path.join(a.out, "warmup_effect.csv"), "w", newline="", encoding="utf-8") as fh:
        wr = csv.DictWriter(fh, fieldnames=list(cells[0]))
        wr.writeheader()
        wr.writerows(cells)
    with open(os.path.join(a.out, "warmup_effect_summary.csv"), "w", newline="", encoding="utf-8") as fh:
        wr = csv.writer(fh)
        wr.writerow(["scenario", "algorithm", "cells", "geomean_warm_over_cold", "min", "max"])
        for (sc, alg), v in sorted(summ.items()):
            g = math.exp(sum(map(math.log, v)) / len(v))
            wr.writerow([sc, alg, len(v), f"{g:.4f}", f"{min(v):.4f}", f"{max(v):.4f}"])
            print(f"{sc} {alg:24s} cells {len(v):3d}  warm/cold geomean {g:.3f}  {min(v):.2f}..{max(v):.2f}")
    print(f"cells: {len(cells)}; counter values compared: {compared}; differing: {bad}; cells on one side only: {len(only)}")
    return 1 if bad or only or not cells else 0


if __name__ == "__main__":
    sys.exit(main())
