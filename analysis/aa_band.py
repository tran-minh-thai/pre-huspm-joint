#!/usr/bin/env python3
"""Tie band from the A/A runs of the proposed method (pre-registered in docs/aa-tie-band.md).

    python3 analysis/aa_band.py --results results/campaign-aa [--out DIR]
    python3 analysis/aa_band.py --self-test

A run is results/campaign-aa/s0r<n>/SC2/<database>/Pre-HUSPM-Joint/*.csv with a DONE file. Its total
is the time summed over the increments (op_type *_INC), used only when every increment finished. The
pair k of a database is the runs with repeats 2k and 2k+1; q = max(a/b, b/a); the band is [1/r, r]
with r the largest q over the pairs that have both runs. Writes table_aa_pairs.csv (one row per pair)
and prints the band, the pair count, the largest q per database and the 95th and 99th percentiles.
Exits 1 when no pair has both runs.
"""
import argparse
import glob
import os
import re
import sys
import tempfile

import numpy as np
import pandas as pd

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, "analysis"))

ARM = "Pre-HUSPM-Joint"
STREAMS = ["BIBLE", "C8T1S5I8N5K", "DUNNHUMBY", "FIFA", "KOSARAK", "LEVIATHAN", "SIGN", "TAFENG"]
PAIRS_PER_STREAM = 10


def run_totals(results, status=None):
    """(database, repeat) -> total seconds over the increments, or None when an increment did not finish."""
    if status is None:
        import make_figures as mf
        status = mf.classify_status
    out = {}
    for csv in sorted(glob.glob(os.path.join(results, "s0r*", "SC2", "*", ARM, "*.csv"))):
        arm_dir = os.path.dirname(csv)
        if not os.path.exists(os.path.join(arm_dir, "DONE")):
            continue
        rep = int(re.search(r"s0r(\d+)", csv).group(1))
        db = os.path.basename(os.path.dirname(arm_dir))
        f = pd.read_csv(csv)
        f.columns = [c.strip() for c in f.columns]
        inc = f[f["op_type"].astype(str).str.contains("_INC")]
        ok = [status(o, r) == "OK" for o, r in zip(inc["op_type"], inc["runtime_ms"])]
        out[(db, rep)] = float(inc["runtime_ms"].sum()) / 1000 if len(inc) and all(ok) else None
    return out


def pairs(totals, streams=STREAMS, k_max=PAIRS_PER_STREAM):
    rows = []
    for db in streams:
        for k in range(k_max):
            a, b = totals.get((db, 2 * k)), totals.get((db, 2 * k + 1))
            q = max(a / b, b / a) if a and b else np.nan
            rows.append(dict(dataset=db, pair=k, first_s=a, second_s=b, q=q))
    return pd.DataFrame(rows)


def band(t):
    q = t["q"].dropna()
    if not len(q):
        return None
    return dict(r=float(q.max()), pairs=int(len(q)), missing=int(t["q"].isna().sum()),
                p95=float(np.percentile(q, 95)), p99=float(np.percentile(q, 99)),
                per_stream={d: float(g["q"].max()) for d, g in t.groupby("dataset") if g["q"].notna().any()})


def self_test():
    ok = True
    st = lambda o, r: "OK" if r >= 0 else "FAIL"
    with tempfile.TemporaryDirectory() as tmp:
        def put(rep, db, times, done=True):
            d = os.path.join(tmp, f"s0r{rep}", "SC2", db, ARM)
            os.makedirs(d, exist_ok=True)
            rows = [dict(op_type="INIT", runtime_ms=999)] + [dict(op_type="SC2_INC", runtime_ms=t) for t in times]
            pd.DataFrame(rows).to_csv(os.path.join(d, "SC2_incremental.csv"), index=False)
            if done:
                open(os.path.join(d, "DONE"), "w").write("rc=0\n")
        put(0, "A", [1000, 1000]); put(1, "A", [1100, 1100])       # q = 1.1
        put(2, "A", [1000, 1000]); put(3, "A", [1000, 1250])       # q = 1.125, the largest
        put(0, "B", [500]); put(1, "B", [-1])                      # an unfinished increment: pair missing
        put(2, "B", [500]); put(3, "B", [520], done=False)         # no DONE: pair missing
        t = pairs(run_totals(tmp, st), streams=["A", "B"], k_max=2)
        b = band(t)
        checks = [("largest q", abs(b["r"] - 1.125) < 1e-12), ("pairs with both runs", b["pairs"] == 2),
                  ("missing pairs", b["missing"] == 2), ("init row left out", abs(t.loc[0, "first_s"] - 2.0) < 1e-12),
                  ("per stream", set(b["per_stream"]) == {"A"})]
        for name, good in checks:
            print(f"  {'ok  ' if good else 'FAIL'} {name}")
            ok &= good
        # injected faults: each must change the outcome
        fq = t["q"].dropna().min()
        caught = abs(fq - b["r"]) > 1e-12
        print(f"  {'ok  ' if caught else 'FAIL'} the fault 'smallest q as the band' would be caught ({fq:.3f} vs {b['r']:.3f})")
        ok &= caught
        empty = band(pairs({}, streams=["A"], k_max=1)) is None
        print(f"  {'ok  ' if empty else 'FAIL'} no pair gives no band")
        ok &= empty
    print("self-test " + ("passed" if ok else "FAILED"))
    return 0 if ok else 1


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--results", default=os.path.join(ROOT, "results", "campaign-aa"))
    ap.add_argument("--out", default=None)
    ap.add_argument("--self-test", action="store_true")
    a = ap.parse_args()
    if a.self_test:
        return self_test()
    t = pairs(run_totals(a.results))
    out = a.out or a.results
    os.makedirs(out, exist_ok=True)
    t.to_csv(os.path.join(out, "table_aa_pairs.csv"), index=False)
    b = band(t)
    if b is None:
        print(f"no pair with both runs under {a.results}")
        return 1
    print(f"pairs with both runs: {b['pairs']} of {len(t)} ({b['missing']} missing)")
    print(f"band: {1 / b['r']:.3f} to {b['r']:.3f} (largest q {b['r']:.4f})")
    print(f"q percentiles: 95th {b['p95']:.4f}, 99th {b['p99']:.4f}")
    for d, v in b["per_stream"].items():
        print(f"  {d:12s} largest q {v:.4f}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
