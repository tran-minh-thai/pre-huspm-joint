#!/usr/bin/env python3
"""Aggregate repeated runs into mean +/- standard deviation.

Two sources of variation are kept apart, because they answer different questions.

    repeat  reruns of an identical configuration. This measures JIT and GC noise
            in the harness. It is what the reported standard deviation refers to.
    seed    a different configuration: which sequences land in which batch, and
            which operations happen when. This tests whether a conclusion survives
            a different draw, and is reported as a spread across configurations,
            never pooled with measurement noise.

Rows that did not complete are excluded from the statistics and counted separately:
runtime_ms is -1 for a failure, -2 for overtime, and -3 for a live-heap run whose
timing is void by construction.

Usage:
    python analysis/aggregate.py results/run-.../SC1_static.csv
    python analysis/aggregate.py results/run-.../            # every CSV in the folder
    python analysis/aggregate.py results/run-.../ --by dataset algorithm op_type
    python analysis/aggregate.py results/run-.../ --metric runtime_ms mem_live_mb
    python analysis/aggregate.py results/run-.../ --csv out.csv
"""

import argparse
import csv
import glob
import math
import os
import sys
from collections import defaultdict

# Columns that identify a configuration. Anything not in here and not a metric is
# ignored for grouping.
DEFAULT_KEYS = ["dataset", "algorithm", "op_type", "iteration", "seed"]

DEFAULT_METRICS = ["runtime_ms", "peak_heap_mb", "mem_live_mb", "allocated_mb",
                   "candidate_count", "explored_nodes", "total_patterns_found",
                   "rescan_count"]

# runtime_ms uses negative sentinels rather than a separate status column.
SENTINELS = {-1: "FAILED", -2: "OVERTIME", -3: "LIVE_MODE_VOID"}


def read_rows(path):
    with open(path, newline="", encoding="utf-8-sig") as f:
        return list(csv.DictReader(f))


def to_float(v):
    try:
        return float(v)
    except (TypeError, ValueError):
        return None


def mean_sd(xs):
    n = len(xs)
    if n == 0:
        return None, None, 0
    m = sum(xs) / n
    if n == 1:
        return m, 0.0, 1
    # Sample standard deviation: we are estimating the spread of the process, not
    # describing this particular handful of numbers.
    var = sum((x - m) ** 2 for x in xs) / (n - 1)
    return m, math.sqrt(var), n


def aggregate(rows, keys, metrics):
    groups = defaultdict(lambda: defaultdict(list))
    status = defaultdict(lambda: defaultdict(int))

    for r in rows:
        key = tuple(r.get(k, "") for k in keys)
        rt = to_float(r.get("runtime_ms"))
        if rt is not None and rt < 0:
            status[key][SENTINELS.get(int(rt), "UNKNOWN")] += 1
            # A failed or timed-out call has no meaningful metric values.
            if int(rt) in (-1, -2):
                continue
        for m in metrics:
            v = to_float(r.get(m))
            if v is None:
                continue
            if m == "runtime_ms" and v < 0:
                continue          # sentinel, already counted above
            groups[key][m].append(v)
    return groups, status


def fmt(m, sd, n):
    if m is None:
        return "-"
    if n == 1:
        return f"{m:,.1f} (n=1)"
    return f"{m:,.1f} +/- {sd:,.1f} (n={n})"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("path", help="a result CSV, or a directory of them")
    ap.add_argument("--by", nargs="+", default=DEFAULT_KEYS,
                    help="columns identifying a configuration (default: %s)" % " ".join(DEFAULT_KEYS))
    ap.add_argument("--metric", nargs="+", default=None,
                    help="metrics to aggregate (default: the usual runtime and memory set)")
    ap.add_argument("--csv", help="write the aggregate to this file instead of printing a table")
    args = ap.parse_args()

    files = ([args.path] if os.path.isfile(args.path)
             else sorted(glob.glob(os.path.join(args.path, "**", "*.csv"), recursive=True)))
    if not files:
        sys.exit("no CSV found under " + args.path)

    out_rows = []
    for path in files:
        rows = read_rows(path)
        if not rows:
            continue
        present = rows[0].keys()
        keys = [k for k in args.by if k in present]
        metrics = [m for m in (args.metric or DEFAULT_METRICS) if m in present]
        if not metrics:
            continue

        # How many repeats are actually in this file? If it is 1 everywhere, say so
        # rather than printing "+/- 0.0" and letting it read as a measured spread.
        reps = {r.get("repeat", "") for r in rows}
        groups, status = aggregate(rows, keys, metrics)

        print()
        print("=" * 100)
        try:
            shown = os.path.relpath(path)
        except ValueError:
            shown = path          # different drive on Windows
        print(shown)
        print(f"  {len(rows)} rows, {len(groups)} configurations, "
              f"repeat values seen: {sorted(reps) if reps != {''} else 'column absent'}")
        if reps in ({""}, {"0"}):
            print("  NOTE: one run per configuration, so no standard deviation can be computed.")
        print("=" * 100)

        for key in sorted(groups):
            label = " | ".join(f"{k}={v}" for k, v in zip(keys, key) if v != "")
            parts = []
            for m in metrics:
                mu, sd, n = mean_sd(groups[key][m])
                if mu is None:
                    continue
                parts.append(f"{m}={fmt(mu, sd, n)}")
            st = status.get(key)
            if st:
                parts.append("status=" + ",".join(f"{k}x{v}" for k, v in sorted(st.items())))
            print(f"  {label}")
            for p in parts:
                print(f"      {p}")

            row = dict(zip(keys, key))
            row["source_file"] = os.path.basename(path)
            for m in metrics:
                mu, sd, n = mean_sd(groups[key][m])
                row[m + "_mean"] = "" if mu is None else f"{mu:.4f}"
                row[m + "_sd"] = "" if mu is None else f"{sd:.4f}"
                row[m + "_n"] = n
            for k, v in (status.get(key) or {}).items():
                row["n_" + k.lower()] = v
            out_rows.append(row)

    if args.csv and out_rows:
        cols = []
        for r in out_rows:
            for c in r:
                if c not in cols:
                    cols.append(c)
        with open(args.csv, "w", newline="", encoding="utf-8") as f:
            w = csv.DictWriter(f, fieldnames=cols)
            w.writeheader()
            w.writerows(out_rows)
        print()
        print("-> " + args.csv)


if __name__ == "__main__":
    main()
