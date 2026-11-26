#!/usr/bin/env python3
"""Old against new arithmetic on the SC2 streams (pre-registered in docs/exact-arithmetic-timing.md).

    python3 analysis/exact_timing.py [--old results/campaign-exact-old] [--new results/campaign-exact-new]
                                     [--aa results/campaign-aa] [--out DIR]
    python3 analysis/exact_timing.py --self-test

A run is <tree>/s0r<k>/SC2/<stream>/<arm>/*.csv with a DONE file. Its total is the time summed over
the increments (op_type *_INC), used only when every increment finished. The pair (stream, arm, k) is
the old run and the new run of repeat k; q = max(old/new, new/old). The bound r is the A/A bound
(analysis/aa_band.py on --aa). A (stream, arm) keeps its campaign timings when its four pairs have both
runs, each has q <= r, and the counters of every increment are equal between old and new.

Writes table_exact_timing_pairs.csv (one row per pair) and table_exact_timing_cells.csv (one row per
stream and arm, with the verdict), and prints them. Exits 1 when a (stream, arm) does not keep its
timings or when no pair could be compared.
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

ARMS = ["Pre-HUSPM-Joint", "Remine-own-UUT-rebuild"]
STREAMS = ["BIBLE", "C8T1S5I8N5K", "DUNNHUMBY", "FIFA", "KOSARAK", "LEVIATHAN", "SIGN", "TAFENG"]
PAIRS = 4
COUNTERS = ["candidate_count", "explored_nodes", "total_patterns_found", "rescan_count"]


def read_run(tree, k, stream, arm, status):
    """(total seconds or None, counters frame or None) of one run; None when it has no DONE file."""
    d = os.path.join(tree, f"s0r{k}", "SC2", stream, arm)
    csvs = sorted(glob.glob(os.path.join(d, "*.csv")))
    if not csvs or not os.path.exists(os.path.join(d, "DONE")):
        return None, None
    f = pd.read_csv(csvs[0])
    f.columns = [c.strip() for c in f.columns]
    inc = f[f["op_type"].astype(str).str.contains("_INC")]
    ok = [status(o, r) == "OK" for o, r in zip(inc["op_type"], inc["runtime_ms"])]
    total = float(pd.to_numeric(inc["runtime_ms"]).sum()) / 1000 if len(inc) and all(ok) else None
    return total, f[["iteration", *COUNTERS]].reset_index(drop=True)


def same_counters(a, b):
    """(rows compared, rows equal); a differing length counts every row of the longer one as unequal."""
    if a is None or b is None:
        return 0, 0
    n = max(len(a), len(b))
    if len(a) != len(b):
        return n, 0
    eq = (a.astype(str).values == b.astype(str).values).all(axis=1)
    return n, int(eq.sum())


def analyse(old, new, r, status, campaign=None, streams=STREAMS, arms=ARMS, pairs=PAIRS):
    rows = []
    for s in streams:
        for a in arms:
            ref = None
            if campaign:
                _, ref = read_run_flat(os.path.join(campaign, "SC2", s, a), status)
            for k in range(pairs):
                to, co = read_run(old, k, s, a, status)
                tn, cn = read_run(new, k, s, a, status)
                q = max(to / tn, tn / to) if to and tn else np.nan
                n, e = same_counters(co, cn)
                rn, re_ = same_counters(cn, ref) if ref is not None else (0, 0)
                rows.append(dict(stream=s, arm=a, pair=k, old_s=to, new_s=tn, new_over_old=tn / to if to and tn else np.nan,
                                 q=q, rows=n, rows_equal=e, campaign_rows=rn, campaign_rows_equal=re_))
    p = pd.DataFrame(rows)
    cells = []
    for (s, a), g in p.groupby(["stream", "arm"], sort=False):
        both = int(g["q"].notna().sum())
        why = []
        if both < pairs:
            why.append(f"{pairs - both} of {pairs} pairs missing")
        if (g["q"] > r).any():
            why.append(f"{int((g['q'] > r).sum())} pairs above the bound")
        if (g["rows"] == 0).any() or (g["rows_equal"] < g["rows"]).any():
            why.append(f"counters differ in {int((g['rows'] - g['rows_equal']).sum())} rows"
                       + (f", {int((g['rows'] == 0).sum())} pairs not compared" if (g["rows"] == 0).any() else ""))
        cells.append(dict(stream=s, arm=a, pairs=both, max_q=float(g["q"].max()) if both else np.nan,
                          median_new_over_old=float(g["new_over_old"].median()) if both else np.nan,
                          rows=int(g["rows"].sum()), rows_equal=int(g["rows_equal"].sum()),
                          campaign_rows=int(g["campaign_rows"].sum()), campaign_rows_equal=int(g["campaign_rows_equal"].sum()),
                          keeps_timings=not why, reason="; ".join(why)))
    return p, pd.DataFrame(cells)


def read_run_flat(d, status):
    csvs = sorted(glob.glob(os.path.join(d, "*.csv")))
    if not csvs:
        return None, None
    f = pd.read_csv(csvs[0])
    f.columns = [c.strip() for c in f.columns]
    return None, f[["iteration", *COUNTERS]].reset_index(drop=True)


def aa_bound(aa):
    import aa_band
    b = aa_band.band(aa_band.pairs(aa_band.run_totals(aa)))
    if b is None:
        sys.exit(f"no A/A pair with both runs in {aa}")
    return b["r"], b["pairs"]


def report(p, c, r, npairs_aa):
    print(f"A/A bound r = {r:.6f} (from {npairs_aa} pairs)")
    print(p.to_string(index=False))
    print(c.to_string(index=False))
    for a, g in p.groupby("arm"):
        print(f"{a}: {int(g['q'].notna().sum())} pairs, median new/old {g['new_over_old'].median():.4f}, "
              f"largest q {g['q'].max():.4f}, counter rows {int(g['rows_equal'].sum())} equal of {int(g['rows'].sum())}")
    keep = int(c["keeps_timings"].sum())
    print(f"{keep} of {len(c)} (stream, arm) keep their campaign timings")
    return keep == len(c) and p["q"].notna().any()


def self_test():
    ok = True
    st = lambda o, t: "OK" if t >= 0 else "FAIL"
    with tempfile.TemporaryDirectory() as tmp:
        def put(tree, k, s, a, times, nodes, done=True):
            d = os.path.join(tmp, tree, f"s0r{k}", "SC2", s, a)
            os.makedirs(d, exist_ok=True)
            rows = [dict(iteration=0, op_type="SC2_INIT", runtime_ms=999, candidate_count=1, explored_nodes=1,
                         total_patterns_found=1, rescan_count=0)]
            rows += [dict(iteration=i + 1, op_type="SC2_INC", runtime_ms=t, candidate_count=5, explored_nodes=x,
                          total_patterns_found=3, rescan_count=0) for i, (t, x) in enumerate(zip(times, nodes))]
            pd.DataFrame(rows).to_csv(os.path.join(d, "SC2_incremental.csv"), index=False)
            if done:
                open(os.path.join(d, "DONE"), "w").close()

        def build(tag, fault=None):
            o, n = f"{tag}-old", f"{tag}-new"
            for k in range(2):
                for s in ("X", "Y"):
                    put(o, k, s, "A", [100, 100], [7, 8])
                    put(n, k, s, "A", [104, 101] if not (fault == "slow" and s == "Y" and k == 1) else [150, 150],
                        [7, 8] if not (fault == "nodes" and s == "X") else [7, 9],
                        done=not (fault == "missing" and s == "Y" and k == 0))
            return os.path.join(tmp, o), os.path.join(tmp, n)

        cases = [(None, 2), ("slow", 1), ("nodes", 1), ("missing", 1)]
        for fault, want in cases:
            o, n = build(str(fault), fault)
            _, c = analyse(o, n, 1.1, st, streams=["X", "Y"], arms=["A"], pairs=2)
            got = int(c["keeps_timings"].sum())
            good = got == want
            ok &= good
            print(f"  case {fault or 'clean'}: {got} of 2 keep (expected {want}) {'ok' if good else 'WRONG'}; "
                  f"{'; '.join(x for x in c['reason'] if x)}")
    print("self-test:", "pass" if ok else "FAIL")
    return ok


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--old", default=os.path.join(ROOT, "results", "campaign-exact-old"))
    ap.add_argument("--new", default=os.path.join(ROOT, "results", "campaign-exact-new"))
    ap.add_argument("--aa", default=os.path.join(ROOT, "results", "campaign-aa"))
    ap.add_argument("--campaign", default=os.path.join(ROOT, "results", "campaign"))
    ap.add_argument("--out", default=os.path.join(ROOT, "results-invariant", "exact-timing"))
    ap.add_argument("--self-test", action="store_true")
    a = ap.parse_args()
    if a.self_test:
        sys.exit(0 if self_test() else 1)
    for t in (a.old, a.new):
        if not os.path.isdir(t):
            sys.exit(f"{t} does not exist")
    import make_figures as mf
    r, npairs = aa_bound(a.aa)
    p, c = analyse(a.old, a.new, r, mf.classify_status, campaign=a.campaign)
    os.makedirs(a.out, exist_ok=True)
    p.to_csv(os.path.join(a.out, "table_exact_timing_pairs.csv"), index=False)
    c.to_csv(os.path.join(a.out, "table_exact_timing_cells.csv"), index=False)
    sys.exit(0 if report(p, c, r, npairs) else 1)


if __name__ == "__main__":
    main()
