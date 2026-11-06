#!/usr/bin/env python3
"""Does the run-to-run spread of one arm follow the core cluster its JVM runs on?

    python3 analysis/core_placement.py results-probe/core-placement/<dir>
    python3 analysis/core_placement.py --self-test

Reads a probe written by campaign.ps1 -CorePlacement: one arm on one database, timed in three
placements interleaved over several rounds (not pinned, pinned to the first L3 cache, pinned to
the second). The runtime of a run is the sum of runtime_ms over its updates (iteration > 0), as in
analysis/session_drift.py.

The criteria were fixed before the probe ran (spread = slowest run over fastest run of a setting):

    supported    both pinned spreads <= 1 + TOL, the two pinned settings do not overlap
                 (every run of one is faster than every run of the other), and every unpinned
                 run lies within the pinned runs widened by TOL;
    refuted      both pinned spreads keep at least half of the unpinned spread;
    inconclusive the unpinned spread itself is <= 1 + TOL: the instability did not appear;
    partial      anything else; the conditions that held are printed.

Every run must report the same patterns and the same explored nodes; otherwise the runs did
different work and no verdict is given. Prints how many runs each setting contributed.
Exit 0 with a verdict, 1 when the runs disagree on the counters, 2 when a setting has fewer than
two usable runs.
"""
import argparse
import json
import pathlib
import sys
import tempfile

import pandas as pd

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from session_drift import runtime  # noqa: E402

TOL = 0.03
SETTINGS = ("unpinned", "l3-0", "l3-1")


def counters(cell):
    d = pd.concat(pd.read_csv(f) for f in cell.glob("*.csv"))
    return int(d["total_patterns_found"].iloc[-1]), int(d["explored_nodes"].sum())


def load(probe):
    meta = json.loads((probe / "run_meta.json").read_text(encoding="utf-8"))
    runs = {s: [] for s in SETTINGS}
    work = set()
    for c in meta["cells"]:
        cell = probe / f"r{c['round']}" / c["setting"]
        if c["exit_code"] != 0 or not any(cell.glob("*.csv")):
            print(f"  skipped round {c['round']} {c['setting']}: exit {c['exit_code']}, "
                  f"csv {'present' if any(cell.glob('*.csv')) else 'missing'}")
            continue
        t = runtime(cell)
        if t is None:
            print(f"  skipped round {c['round']} {c['setting']}: no positive runtime")
            continue
        runs[c["setting"]].append(t / 1000.0)
        work.add(counters(cell))
    return meta, runs, work


def verdict(runs):
    spread = {s: max(v) / min(v) for s, v in runs.items()}
    fast, slow = sorted(("l3-0", "l3-1"), key=lambda s: min(runs[s]))
    pinned_all = runs["l3-0"] + runs["l3-1"]
    held = {
        "pinned spreads within tolerance": spread["l3-0"] <= 1 + TOL and spread["l3-1"] <= 1 + TOL,
        "pinned settings do not overlap": max(runs[fast]) < min(runs[slow]),
        "unpinned runs lie between the clusters": all(
            min(pinned_all) / (1 + TOL) <= x <= max(pinned_all) * (1 + TOL) for x in runs["unpinned"]),
    }
    if spread["unpinned"] <= 1 + TOL:
        name = "inconclusive"
    elif all(held.values()):
        name = "supported"
    elif all(spread[s] - 1 >= 0.5 * (spread["unpinned"] - 1) for s in ("l3-0", "l3-1")):
        name = "refuted"
    else:
        name = "partial"
    return name, spread, held


def report(probe):
    meta, runs, work = load(probe)
    print(f"{meta.get('arm')} on {meta.get('dataset')}, {meta.get('machine')}, L3 masks "
          f"{', '.join(meta.get('l3_masks', []))}, commit {meta.get('git_commit', '')[:9]}")
    for s in SETTINGS:
        v = runs[s]
        masks = sorted({c.get("mask_read", "") for c in meta["cells"] if c["setting"] == s})
        line = ", ".join(f"{x:.1f}" for x in v)
        print(f"  {s:9s} runs {len(v)}  mask read back {'/'.join(masks)}  seconds: {line}"
              + (f"  max/min {max(v) / min(v):.3f}" if len(v) > 1 else ""))
    if len(work) > 1:
        print(f"runs disagree on (patterns, explored nodes): {sorted(work)}; no verdict")
        return 1
    if any(len(v) < 2 for v in runs.values()):
        print("a setting has fewer than two usable runs; no verdict")
        return 2
    name, _, held = verdict(runs)
    for k, v in held.items():
        print(f"  {'yes' if v else 'no '}  {k}")
    print(f"core-cluster hypothesis: {name} (tolerance {TOL:.0%}; every run {sorted(work)[0][0]} "
          f"patterns, {sorted(work)[0][1]} nodes)")
    return 0


def write_probe(root, totals, nodes=None, exit_codes=None):
    cells = []
    for r, row in enumerate(zip(*(totals[s] for s in SETTINGS)), start=1):
        for s, t in zip(SETTINGS, row):
            cell = root / f"r{r}" / s
            cell.mkdir(parents=True)
            n = (nodes or {}).get((r, s), 1000)
            pd.DataFrame({"iteration": [0, 1, 2], "runtime_ms": [999, t * 500, t * 500],
                          "explored_nodes": [n, 0, 0], "total_patterns_found": [7, 7, 7]}
                         ).to_csv(cell / "x.csv", index=False)
            cells.append({"round": r, "setting": s, "exit_code": (exit_codes or {}).get((r, s), 0),
                          "mask_read": "0x0"})
    (root / "run_meta.json").write_text(json.dumps({"arm": "A", "dataset": "D", "cells": cells}))


def self_test():
    cases = [
        ("supported", {"unpinned": [100, 108, 101, 107], "l3-0": [100, 101, 100.5, 101.5],
                       "l3-1": [107, 108, 107.5, 108.2]}, None, None, 0),
        ("refuted", {"unpinned": [100, 110, 103, 108], "l3-0": [100, 109, 104, 101],
                     "l3-1": [101, 110, 105, 100]}, None, None, 0),
        ("inconclusive", {"unpinned": [100, 101, 102, 101], "l3-0": [100, 101, 100, 101],
                          "l3-1": [104, 105, 104, 105]}, None, None, 0),
        ("partial", {"unpinned": [100, 110, 103, 108], "l3-0": [100, 102, 101, 101],
                     "l3-1": [101, 102, 101.5, 100.5]}, None, None, 0),
        ("partial", {"unpinned": [100, 104, 101, 103.5], "l3-0": [100, 102, 101, 101.5],
                     "l3-1": [101.5, 102.5, 102, 102]}, None, None, 0),        # the clusters overlap
        ("partial", {"unpinned": [100, 111, 101, 108], "l3-0": [100, 101, 100.5, 101.5],
                     "l3-1": [107, 112, 108, 111]}, None, None, 0),             # one pinned spread 4.7%
        ("counters differ", {"unpinned": [100, 108], "l3-0": [100, 101], "l3-1": [107, 108]},
         {(2, "l3-1"): 999}, None, 1),
        ("missing run", {"unpinned": [100, 108], "l3-0": [100, 101], "l3-1": [107, 108]},
         None, {(1, "l3-0"): 1}, 2),
    ]
    failures = 0
    for expect, totals, nodes, codes, rc_expect in cases:
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            write_probe(root, totals, nodes, codes)
            got_rc = report(root)
            ok = got_rc == rc_expect
            if rc_expect == 0:
                _, runs, _ = load(root)
                ok = ok and verdict(runs)[0] == expect
            print(f"SELF-TEST {expect}: {'ok' if ok else 'FAILED'}\n")
            failures += not ok
    print(f"self-test: {len(cases) - failures}/{len(cases)} cases classified as expected")
    return 1 if failures else 0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("probe", nargs="?")
    ap.add_argument("--self-test", action="store_true")
    a = ap.parse_args()
    if a.self_test:
        sys.exit(self_test())
    if not a.probe:
        ap.error("give the probe directory or --self-test")
    probe = pathlib.Path(a.probe)
    if not (probe / "run_meta.json").is_file():
        sys.exit(f"{probe.resolve().as_posix()}: no run_meta.json")
    sys.exit(report(probe))


if __name__ == "__main__":
    main()
