"""Compares the counters of the rerun arms (run_all.sh) with the campaign's rows of the same
scenario, database and arm: patterns, explored nodes, candidates, and in the incremental
scenarios rescans and pre-large patterns, operation by operation. Counters do not depend on
the run, so any difference means the change altered what the algorithm computes.

    python3 compare.py RERUN_DIR [CAMPAIGN_DIR]

Prints the number of rows compared per file and exits 1 on any difference or on a file with
no comparable row."""
import pathlib
import sys

import pandas as pd

RERUN = pathlib.Path(sys.argv[1])
CAMP = pathlib.Path(sys.argv[2]) if len(sys.argv) > 2 else pathlib.Path(__file__).resolve().parents[3] / "results" / "campaign"
COUNTERS = ["total_patterns_found", "explored_nodes", "candidate_count", "rescan_count", "prelarge_count"]
KEY = ["algorithm", "op_type", "iteration"]
bad = files = rows = 0
for f in sorted(RERUN.glob("*/*/*/*.csv")):
    sc, db, arm = f.parts[-4], f.parts[-3], f.parts[-2]
    c = CAMP / sc / db / arm / f.name
    files += 1
    if not c.exists():
        print(f"{sc}/{db}/{arm}/{f.name}: no campaign file"); bad += 1; continue
    a = pd.read_csv(f, low_memory=False)
    b = pd.read_csv(c, low_memory=False)
    if "repeat" in b:
        b = b[b["repeat"] == 0]
    cols = [k for k in COUNTERS if k in a and k in b]
    j = a[KEY + cols].merge(b[KEY + cols], on=KEY, suffixes=("_new", "_old"))
    diff = sum(int((j[k + "_new"].fillna(-1) != j[k + "_old"].fillna(-1)).sum()) for k in cols)
    rows += len(j)
    ok = len(j) > 0 and len(j) == len(a) and diff == 0
    bad += not ok
    print(f"{sc}/{db}/{arm}: {len(j)} of {len(a)} rows matched to the campaign, {len(cols)} counters, {diff} differing values")
print(f"{files} files, {rows} rows compared, {bad} failing files")
sys.exit(1 if bad or files == 0 else 0)
