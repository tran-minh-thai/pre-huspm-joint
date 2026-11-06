#!/usr/bin/env python3
"""How much does a measurement move between days on the same machine when nothing else changes?

    python3 analysis/session_drift.py [--host PC-THAI] [--threshold 0.05]

Finds every arm (scenario, database, algorithm) of the timing campaign that was measured with
the same seed and the same source commit (the commit= field of DONE, the last commit that
changed src/) on at least two different days on one host, across the main tree and the
replicate trees s0r<n>. The code, the data, the seed, the JDK and the machine are then the
same, so the ratio of the later day's runtime to the earlier day's is the session effect.

Prints one line per arm, sorted by deviation, then the median deviation and how many arms
deviate by more than --threshold. Prints how many arms it could compare: zero comparable arms
means the question is unanswered, not that there is no drift.
"""
import argparse
import collections
import pathlib
import re
import statistics

import pandas as pd

ROOT = pathlib.Path(__file__).resolve().parent.parent


def runtime(cell):
    frames = [pd.read_csv(f) for f in cell.glob("*.csv")]
    if not frames:
        return None
    d = pd.concat(frames)
    if "iteration" in d and (d.iteration > 0).any():
        d = d[d.iteration > 0]
    s = d["runtime_ms"].sum()
    return float(s) if s > 0 else None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="PC-THAI")
    ap.add_argument("--threshold", type=float, default=0.05)
    a = ap.parse_args()
    trees = [ROOT / "results" / "campaign"] + sorted((ROOT / "results" / "campaign-replicates").glob("s0r*"))
    groups = collections.defaultdict(lambda: collections.defaultdict(list))
    for tree in trees:
        for done in tree.glob("*/*/*/DONE"):
            m = re.search(r"host=(\S+) finished=(\S+) .*?commit=(\S+)", done.read_text(encoding="utf-8"))
            if not m or m.group(1) != a.host:
                continue
            sc, db, arm = done.parts[-4:-1]
            r = runtime(done.parent)
            if r is not None:
                groups[(sc, db, arm, m.group(3))][m.group(2)].append(r)
    rows = []
    for (sc, db, arm, commit), days in groups.items():
        if len(days) < 2:
            continue
        first, last = sorted(days)[0], sorted(days)[-1]
        within = [max(v) / min(v) for v in days.values() if len(v) > 1]
        rows.append((sc, db, arm, commit, first, last,
                     statistics.mean(days[last]) / statistics.mean(days[first]),
                     max(within) if within else None, sum(map(len, days.values()))))
    rows.sort(key=lambda r: abs(r[6] - 1), reverse=True)
    for r in rows:
        w = f"{r[7]:.3f}" if r[7] else "-"
        print(f"{r[0]:5s} {r[1]:12s} {r[2]:40s} {r[3]} {r[4]}->{r[5]} later/earlier {r[6]:.3f} "
              f"within-day max/min {w} runs {r[8]}")
    print(f"comparable arms: {len(rows)} (same host, seed and source commit on at least two days)")
    if rows:
        dev = [abs(r[6] - 1) for r in rows]
        print(f"median deviation {statistics.median(dev):.3f}; above {a.threshold:.0%}: "
              f"{sum(x > a.threshold for x in dev)}; above 10%: {sum(x > 0.10 for x in dev)}")
        per_db = collections.defaultdict(list)
        for r, x in zip(rows, dev):
            per_db[r[1]].append(x)
        for db, v in sorted(per_db.items()):
            print(f"  {db:12s} arms {len(v):3d} max deviation {max(v):.3f}")


if __name__ == "__main__":
    main()
