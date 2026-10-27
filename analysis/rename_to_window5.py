#!/usr/bin/env python3
"""Rename the runs of the window rule from Pre-HUSPM-Joint to Pre-HUSPM-Joint-w5 in SC2 and SC5.

    python3 analysis/rename_to_window5.py [--apply]

Up to the break-even floor rule (PreHUSPMAdaptiveAlgo.FloorRule.BREAK_EVEN), the arm Pre-HUSPM-Joint
of the incremental scenarios was the method with the window selection, w = 5. The method now uses the
break-even rule and the window selection is the comparison arm Pre-HUSPM-Joint-w5. SC2 and SC5 insert
only, and the window rule's code path did not change for insertions, so their runs are the arm
Pre-HUSPM-Joint-w5 and keep their measurements under the new name. (The mixed streams are measured
again: a modification now mines at most once.)

Under the campaign trees, every SC2 and SC5 arm directory Pre-HUSPM-Joint is moved with `git mv` to
Pre-HUSPM-Joint-w5, and in its CSV files the field `algorithm` and the `run_id` prefix are rewritten;
every other byte, line endings included, is kept (analysis/rename_arms.py's rewrite). Logs and DONE
files are left as written. Without --apply the tool only reports.

Self-check, always run: every rewritten CSV, mapped back, must equal the original bytes; after
--apply, no row of a renamed directory may still carry the old name.
"""
import argparse
import csv
import io
import pathlib
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
SCENARIOS = {"SC2", "SC5"}
OLD, NEW = "Pre-HUSPM-Joint", "Pre-HUSPM-Joint-w5"


def rewrite(data, old, new):
    """(new bytes, rows changed): algorithm == old becomes new, with the run_id prefix."""
    lines = data.split(b"\n")
    header = next(csv.reader(io.StringIO(lines[0].decode("utf-8").rstrip("\r"))))
    if "algorithm" not in header:
        return data, 0
    ia, ir = header.index("algorithm"), (header.index("run_id") if "run_id" in header else None)
    out, changed = [lines[0]], 0
    for raw in lines[1:]:
        text = raw.decode("utf-8")
        end = "\r" if text.endswith("\r") else ""
        body = text[:-1] if end else text
        fields = body.split(",")
        if not body or fields[ia] != old:
            out.append(raw)
            continue
        if '"' in body:
            parsed = next(csv.reader(io.StringIO(body)))
            if parsed[:max(ia, ir or 0) + 1] != fields[:max(ia, ir or 0) + 1]:
                sys.exit(f"quoted field before column {ia}: cannot rewrite in place")
        fields[ia] = new
        if ir is not None:
            if not fields[ir].startswith(old + "_"):
                sys.exit(f"run_id {fields[ir]!r} does not start with {old!r}_")
            fields[ir] = new + fields[ir][len(old):]
        out.append((",".join(fields) + end).encode("utf-8"))
        changed += 1
    return b"\n".join(out), changed


def targets():
    """Arm directories Pre-HUSPM-Joint under SC2 or SC5 of a campaign tree."""
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
    for d in dirs:
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
    print(f"arm directories: {len(dirs)}; CSV files: {files}; rows rewritten: {rows}; self-check failures: {bad}; "
          f"old name left after --apply: {left if a.apply else 'not applied'}")
    return 1 if bad or left else 0


if __name__ == "__main__":
    sys.exit(main())
