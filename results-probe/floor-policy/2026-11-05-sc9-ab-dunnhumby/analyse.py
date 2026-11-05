"""Reads the six runs of run_ab.sh. Checks first that each run reproduces the counters of the campaign's
run of the same arm and seed (rescans, rescans at the threshold, explored nodes, patterns per operation),
then prints, for every pairing of a method run with a fixed run, the median ratio of mine times over the
mines with the same operation and the same explored nodes, and each run's total time.

    python3 results-probe/floor-policy/2026-11-05-sc9-ab-dunnhumby/analyse.py
"""
import pathlib
import sys

import pandas as pd

ROOT = pathlib.Path(__file__).resolve().parents[3]
HERE = pathlib.Path(__file__).resolve().parent
M, F = "Pre-HUSPM-Joint", "Pre-HUSPM-Joint-fixed-threshold"
SEED = 1337
COUNTERS = ["rescan_count", "rescans_at_uut", "explored_nodes", "total_patterns_found"]

runs = {}
for d in sorted(HERE.glob("run*-*")):
    n, arm = d.name.split("-", 1)
    f = d / "SC9_long.csv"
    if not f.exists():
        print(f"{d.name}: no SC9_long.csv")
        continue
    runs[(int(n[3:]), arm)] = pd.read_csv(f, low_memory=False).sort_values("iteration").set_index("iteration")

bad = 0
for (n, arm), r in sorted(runs.items()):
    c = pd.read_csv(ROOT / "results" / "campaign" / "SC9" / "DUNNHUMBY" / arm / "SC9_long.csv", low_memory=False)
    c = c[c["seed"] == SEED].sort_values("iteration").set_index("iteration")
    same = all(len(r) == len(c) and (r[k].fillna(-1).values == c[k].fillna(-1).values).all() for k in COUNTERS)
    bad += not same
    ops = r[r.index > 0]
    print(f"run {n} {arm}: {len(r)} rows, counters equal to the campaign's: {same}; "
          f"time of the operations {ops['runtime_ms'].sum() / 1000:.3f} s, of the mines {ops['mine_ms'].sum() / 1000:.3f} s")
if bad:
    print(f"{bad} run(s) do not reproduce the campaign's counters; the pairings below are not comparable")

print("pairing (method run / fixed run): identical mines, median ratio [q10, q90]")
for (nm, am), rm in sorted(runs.items()):
    if am != M:
        continue
    for (nf, af), rf in sorted(runs.items()):
        if af != F:
            continue
        a, b = rm[(rm.index > 0) & rm["mine_ms"].notna()], rf[(rf.index > 0) & rf["mine_ms"].notna()]
        j = a.join(b, lsuffix="_m", rsuffix="_f", how="inner")
        j = j[j["explored_nodes_m"] == j["explored_nodes_f"]]
        q = j["mine_ms_m"] / j["mine_ms_f"]
        order = "method first" if nm < nf else "fixed first"
        print(f"  run {nm} / run {nf} ({order}): {len(j)} mines, {q.median():.4f} [{q.quantile(.1):.4f}, {q.quantile(.9):.4f}]")
sys.exit(1 if bad else 0)
