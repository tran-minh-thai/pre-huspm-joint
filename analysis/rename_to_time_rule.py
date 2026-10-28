#!/usr/bin/env python3
"""Rename the runs of the break-even rule by time from Pre-HUSPM-Joint to Pre-HUSPM-Joint-time.

    python3 analysis/rename_to_time_rule.py [--apply]

From 0a56c546 the method, Pre-HUSPM-Joint, measures mines by the nodes they explore; up to it the
arm measured them by their time. The rule by time is kept as the comparison arm Pre-HUSPM-Joint-time,
whose code path is the earlier method's (the time branch of FloorRule.BREAK_EVEN, merged
modifications), so the earlier runs are that arm and keep their measurements under the new name.

Under every campaign tree, each arm directory Pre-HUSPM-Joint of SC2, SC5, SC6, SC6D or SC6B is moved
with `git mv` to Pre-HUSPM-Joint-time, and in its CSV files the field `algorithm` and the `run_id`
prefix are rewritten with analysis/rename_to_window5.py's byte-preserving rewrite. Logs and DONE
files are left as written. Without --apply the tool only reports.

Self-check, always run: every rewritten CSV, mapped back, must equal the original bytes; after
--apply, no row of a renamed directory may still carry the old name.
"""
import argparse
import csv
import pathlib
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "analysis"))
from rename_to_window5 import rewrite  # noqa: E402

SCENARIOS = {"SC2", "SC5", "SC6", "SC6D", "SC6B"}
OLD, NEW = "Pre-HUSPM-Joint", "Pre-HUSPM-Joint-time"


def targets():
    found = []
    for tree in sorted((ROOT / "results").glob("campaign*")):
        for d in sorted(tree.rglob(OLD)):
            if d.is_dir() and d.parent.parent.name in SCENARIOS:
                found.append(d)
    return found


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--apply", action="store_true")
    a = ap.parse_args()
    dirs = targets()
    collisions = [d for d in dirs if d.with_name(NEW).exists()]
    for d in collisions:
        print(f"collision: {d.with_name(NEW).relative_to(ROOT).as_posix()} exists")
    if collisions:
        return 1
    files = rows = bad = 0
    per_tree = {}
    for d in dirs:
        key = "/".join(d.relative_to(ROOT / "results").parts[:-2])
        per_tree[key] = per_tree.get(key, 0) + 1
        for f in sorted(d.glob("*.csv")):
            data = f.read_bytes()
            new, n = rewrite(data, OLD, NEW)
            back, _ = rewrite(new, NEW, OLD)
            if back != data or n == 0:
                print(f"self-check failed: {f.relative_to(ROOT).as_posix()} ({n} rows)")
                bad += 1
            files += 1
            rows += n
            if a.apply:
                f.write_bytes(new)
        if a.apply:
            subprocess.run(["git", "mv", str(d), str(d.with_name(NEW))], cwd=ROOT, check=True)
    left = 0
    if a.apply:
        for d in dirs:
            for f in d.with_name(NEW).glob("*.csv"):
                with open(f, newline="", encoding="utf-8") as fh:
                    left += sum(1 for r in csv.DictReader(fh) if r.get("algorithm") == OLD)
    for k in sorted(per_tree):
        print(f"  {k}: {per_tree[k]} arm directories")
    print(f"arm directories: {len(dirs)}; CSV files: {files}; rows rewritten: {rows}; self-check failures: {bad}; "
          f"old name left after --apply: {left if a.apply else 'not applied'}")
    return 1 if bad or left else 0


if __name__ == "__main__":
    sys.exit(main())
