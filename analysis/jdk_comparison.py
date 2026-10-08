#!/usr/bin/env python3
"""How far apart are the two JDK builds the campaign ran on?

The arms measured before 2026-10-08 ran on one build of JDK 25 and later arms on another
(campaign.ps1 now pins the build). This compares the same configurations measured on both,
cell by cell, and reports the geometric mean of old/new with a bootstrap 95% interval,
beside the same statistic between two runs on the new build as a reference: a difference
between builds smaller than the difference between two sessions on one build cannot be
told from session noise.

    python3 analysis/jdk_comparison.py [--out DIR]

Runtime: SC1, main tree (old build) against replicates s0r1 and s0r2 (new build), cells
of at least one second. Memory: the live set of the memory campaign's SC1 and SC2 (old
build) against results/campaign-mem-replicates/s0r1 (new build); for SC2 the peak live
set over the stream of each arm. A part with no pairs yet says so and writes nothing.
Counters are compared too: they must be identical across builds.
"""
import argparse
import os
import sys

import numpy as np
import pandas as pd

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import make_figures as mf  # noqa: E402

COUNTERS = ["total_patterns_found", "candidate_count", "explored_nodes", "prelarge_count"]


def gm_ci(x, seed=1, b=20000):
    lx = np.log(np.asarray(x, float))
    rng = np.random.default_rng(seed)
    bs = np.exp(np.array([lx[rng.integers(0, len(lx), len(lx))].mean() for _ in range(b)]))
    return float(np.exp(lx.mean())), float(np.quantile(bs, .025)), float(np.quantile(bs, .975))


def load_mem_replicate(sc, tag="s0r1"):
    base = os.path.join(mf.DATA_DIR, "campaign-mem-replicates", tag, sc)
    frames = []
    for root, _, files in os.walk(base):
        if "DONE" not in files:
            continue
        for f in files:
            if f.endswith(".csv"):
                d = pd.read_csv(os.path.join(root, f))
                d.columns = [c.strip() for c in d.columns]
                frames.append(d)
    if not frames:
        return None
    df = pd.concat(frames, ignore_index=True)
    df["status"] = [mf.classify_status(o, r) for o, r in zip(df["op_type"], df["runtime_ms"])]
    df["finished"] = df["status"] == "OK"
    return df


def line(name, x, ref=None):
    g, lo, hi = gm_ci(x)
    return dict(comparison=name, cells=len(x), factor=g, ci_low=lo, ci_high=hi)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=os.path.join(mf.REPO_DIR, "results", "jdk-comparison"))
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    rows, notes = [], []

    df = mf.parse_su(mf.load_scenario("SC1", replicates=True))
    ok = df[df["finished"]]
    piv = ok.pivot_table(index=["dataset", "su", "algorithm"], columns="replicate",
                         values="runtime_ms", aggfunc="first").dropna()
    piv = piv[piv.min(axis=1) >= 1000]
    new = np.sqrt(piv["s0r1"] * piv["s0r2"])
    rows.append(line("runtime SC1: old build / new build", piv["s0r0"] / new))
    rows.append(line("runtime SC1 reference: new r1 / new r2", piv["s0r1"] / piv["s0r2"]))
    for al, g in (piv["s0r0"] / new).groupby(level="algorithm"):
        rows.append(line(f"runtime SC1 old/new, {al}", g))
    cnt = ok.groupby(["dataset", "su", "algorithm"])
    diff = sum(int(g[c].nunique() > 1) for _, g in cnt if g["replicate"].nunique() >= 3 for c in COUNTERS)
    pairs = sum(len(COUNTERS) for _, g in cnt if g["replicate"].nunique() >= 3)
    notes.append(f"SC1 counters {', '.join(COUNTERS)}: {diff} of {pairs} differ across the three runs")

    old1 = mf.load_scenario("SC1", tree=mf.MEMORY_TREE)
    new1 = load_mem_replicate("SC1")
    if old1 is None or new1 is None:
        notes.append("memory SC1: no re-measured arms yet")
    else:
        old1, new1 = mf.parse_su(old1), mf.parse_su(new1)
        k = ["dataset", "su", "algorithm"]
        m = old1[old1["finished"]].merge(new1[new1["finished"]], on=k, suffixes=("_old", "_new"))
        m = m[(m["mem_live_mb_old"] > 0) & (m["mem_live_mb_new"] > 0)]
        if len(m):
            rows.append(line("live set SC1: old build / new build", m["mem_live_mb_old"] / m["mem_live_mb_new"]))
            d = sum(int(r[f"{c}_old"] != r[f"{c}_new"]) for _, r in m.iterrows() for c in COUNTERS)
            notes.append(f"memory SC1 counters: {d} of {len(m) * len(COUNTERS)} differ")
        else:
            notes.append("memory SC1: re-measured arms present but no cell completed on both builds")

    old2 = mf.load_scenario("SC2", tree=mf.MEMORY_TREE)
    new2 = load_mem_replicate("SC2")
    if old2 is None or new2 is None:
        notes.append("memory SC2: no re-measured arms yet")
    else:
        def peaks(d):
            d = d[d["finished"]]
            return d.groupby(["dataset", "algorithm"])["mem_live_mb"].max()
        p = pd.concat([peaks(old2).rename("old"), peaks(new2).rename("new")], axis=1).dropna()
        p = p[(p["old"] > 0) & (p["new"] > 0)]
        if len(p):
            rows.append(line("peak live set SC2: old build / new build", p["old"] / p["new"]))
        else:
            notes.append("memory SC2: no arm completed on both builds")

    tab = pd.DataFrame(rows)
    tab.round(4).to_csv(os.path.join(a.out, "jdk_comparison.csv"), index=False)
    with open(os.path.join(a.out, "notes.txt"), "w", encoding="utf-8", newline="\n") as fh:
        fh.write("\n".join(notes) + "\n")
    print(tab.round(4).to_string(index=False))
    print("\n".join(notes))


if __name__ == "__main__":
    main()
