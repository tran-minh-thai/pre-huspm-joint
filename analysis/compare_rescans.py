#!/usr/bin/env python3
"""Operating region and rescans of the proposal on SC2 under two rules, side by side.

    python3 analysis/compare_rescans.py NEW_GLOB OLD_GLOB

Each argument is a glob of SC2_incremental.csv files of the proposal, one per database,
for example results/probe-joint-sc2/raw/*/SC2_incremental.csv for the joint rule and a
directory of files extracted from an earlier commit for iaumax. Prints a markdown table:
per database the rule's operating region (ratio of the test quantity to its bound) and its
rescans, the other rule's rescans, and whether each table's consistency check passed.
Counters only; no timing is read.
"""
import glob
import os
import sys

import pandas as pd

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import make_figures as mf  # noqa: E402


def frame(pattern):
    paths = sorted(glob.glob(pattern))
    if not paths:
        sys.exit(f"no file matches {pattern}")
    parts = []
    for p in paths:
        d = pd.read_csv(p)
        d.columns = [c.strip() for c in d.columns]
        parts.append(d)
    df = pd.concat(parts, ignore_index=True)
    df["status"] = [mf.classify_status(o, r) for o, r in zip(df["op_type"], df["runtime_ms"])]
    df["finished"] = df["status"] == "OK"
    return df


def main(new_glob, old_glob):
    new = mf.operating_region(frame(new_glob)).set_index("dataset")
    old = mf.operating_region(frame(old_glob)).set_index("dataset")
    order = [d for d in mf.SC2_DATASETS if d in new.index or d in old.index]
    rn = new["rule"].iloc[0] if len(new) else "new"
    ro = old["rule"].iloc[0] if len(old) else "old"
    print(f"| database | increments | {rn}: ratio median | min | max | within budget | "
          f"{rn}: rescans | {ro}: rescans | difference | check |")
    print("|---|---:|---:|---:|---:|---:|---:|---:|---:|---|")
    for d in order:
        if d not in new.index:
            print(f"| {d} | not measured | | | | | | {int(old.loc[d, 'rescans'])} | | |")
            continue
        n = new.loc[d]
        o_resc = int(old.loc[d, "rescans"]) if d in old.index else None
        same_len = d in old.index and int(old.loc[d, "increments"]) == int(n["increments"])
        diff = (int(n["rescans"]) - o_resc) if (o_resc is not None and same_len) else ""
        check = n["check"] if d not in old.index else f"{n['check']} / {old.loc[d, 'check']}"
        print(f"| {d} | {int(n['increments'])} | {n['ratio_median']:.3f} | {n['ratio_min']:.3f} | "
              f"{n['ratio_max']:.3f} | {int(n['within_budget'])} | {int(n['rescans'])} | "
              f"{'' if o_resc is None else o_resc} | {diff} | {check} |")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    main(sys.argv[1], sys.argv[2])
