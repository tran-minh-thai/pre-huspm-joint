#!/usr/bin/env python3
"""Rename the proposal's arms in the result trees from their historical names to their roles.

    python3 analysis/rename_arms.py [--apply] [--root DIR]

Historical names (measured up to commit 12db2815) and their new names:

    scenario                       old                              new
    SC2, SC5, SC6, SC6D, SC6B      Adaptive-FloorSelect             Pre-HUSPM-Joint
                                   Adaptive-FloorSelect-w<n>        Pre-HUSPM-Joint-w<n>
                                   Adaptive-Full                    Pre-HUSPM-Joint-fixed-threshold
    SC1, SC3, SC4                  Adaptive-Full                    Pre-HUSPM-Joint
    SC4 (rows)                     Adaptive[sl=<r>]                 Pre-HUSPM-Joint[sl=<r>]
    SC7                            proposed_full                    Pre-HUSPM-Joint
                                   proposed_no_IUB                  Pre-HUSPM-Joint-no-IUB
                                   proposed_no_IPS                  Pre-HUSPM-Joint-no-IPS
                                   proposed_C5_on                   Pre-HUSPM-Joint-rescan-rule
                                   proposed_C5_off                  Pre-HUSPM-Joint-rescan-every-batch
                                   proposed_no_block_index          Pre-HUSPM-Joint-no-block-index
                                   proposed_bsz_<n>                 Pre-HUSPM-Joint-bsz-<n>

In the incremental scenarios Pre-HUSPM-Joint is the method with the rescan threshold selection
(window 5) and Pre-HUSPM-Joint-fixed-threshold the method at the fixed lower threshold; in the static
scenarios Pre-HUSPM-Joint is the method's static mine. SC7's incremental arms run at the fixed lower
threshold.

Under the campaign trees (results/campaign*), every arm directory with an old name is moved to its
new name with `git mv`, and in its CSV files the fields `algorithm` and the `run_id` prefix are
rewritten; every other byte, line endings included, is kept. Logs (run.log, run.err), DONE files
and the records of results/verify-joint and results/invariant-* are left as written: they are the
output of the code that ran under the old names. Without --apply the tool only reports.

Self-check, always run: every rewritten CSV, mapped back with the inverse table, must equal the
original bytes, and no old name may remain in the algorithm column of a renamed tree.
"""
import argparse
import csv
import io
import pathlib
import re
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
INCREMENTAL = {"SC2", "SC5", "SC6", "SC6D", "SC6B"}
STATIC = {"SC1", "SC3", "SC4"}
METHOD = "Pre-HUSPM-Joint"
SC7 = {"proposed_full": METHOD, "proposed_no_IUB": METHOD + "-no-IUB", "proposed_no_IPS": METHOD + "-no-IPS",
       "proposed_C5_on": METHOD + "-rescan-rule", "proposed_C5_off": METHOD + "-rescan-every-batch",
       "proposed_no_block_index": METHOD + "-no-block-index"}


def new_name(scenario, old):
    """The new name of arm `old` in `scenario`, or None when the name is not an old proposal name."""
    if scenario in INCREMENTAL:
        if old == "Adaptive-FloorSelect":
            return METHOD
        m = re.fullmatch(r"Adaptive-FloorSelect-w(\d+)", old)
        if m:
            return f"{METHOD}-w{m.group(1)}"
        if old == "Adaptive-Full":
            return METHOD + "-fixed-threshold"
    if scenario in STATIC:
        if old == "Adaptive-Full":
            return METHOD
        m = re.fullmatch(r"Adaptive\[sl=([0-9.]+)\]", old)
        if m:
            return f"{METHOD}[sl={m.group(1)}]"
    if scenario == "SC7":
        if old in SC7:
            return SC7[old]
        m = re.fullmatch(r"proposed_bsz_(\d+)", old)
        if m:
            return f"{METHOD}-bsz-{m.group(1)}"
    return None


def scenario_of(path):
    for p in path.parts:
        if re.fullmatch(r"SC\d+[A-Z]?", p):
            return p
    return None


def rewrite(data, scenario):
    """(new bytes, rows changed) of one CSV: algorithm and the run_id prefix renamed, all else kept."""
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
        if not body:
            out.append(raw)
            continue
        fields = body.split(",")
        if '"' in body:
            # quoted fields: the plain split is valid only if the fields before and at the two
            # rewritten columns are unquoted, which the csv parse confirms
            parsed = next(csv.reader(io.StringIO(body)))
            if parsed[:max(ia, ir or 0) + 1] != fields[:max(ia, ir or 0) + 1]:
                sys.exit(f"quoted field before column {ia}: cannot rewrite in place")
        new = new_name(scenario, fields[ia])
        if new is None:
            out.append(raw)
            continue
        old = fields[ia]
        fields[ia] = new
        if ir is not None:
            if not fields[ir].startswith(old + "_"):
                sys.exit(f"run_id {fields[ir]!r} does not start with {old!r}_")
            fields[ir] = new + fields[ir][len(old):]
        out.append((",".join(fields) + end).encode("utf-8"))
        changed += 1
    return b"\n".join(out), changed


def inverse(data, scenario):
    """Map the new names back to the old ones, for the self-check."""
    table = {}
    for old in (["Adaptive-FloorSelect", "Adaptive-Full"] + [f"Adaptive-FloorSelect-w{w}" for w in (1, 2, 3, 10)]
                + [f"Adaptive[sl={r}]" for r in ("0.3", "0.5", "0.7", "0.9")] + list(SC7)
                + [f"proposed_bsz_{n}" for n in (1, 5, 10, 50, 200)]):
        n = new_name(scenario, old)
        if n is not None:
            if n in table:
                sys.exit(f"{scenario}: {old} and {table[n]} both become {n}")
            table[n] = old
    lines = data.split(b"\n")
    header = next(csv.reader(io.StringIO(lines[0].decode("utf-8").rstrip("\r"))))
    if "algorithm" not in header:
        return data
    ia, ir = header.index("algorithm"), (header.index("run_id") if "run_id" in header else None)
    out = [lines[0]]
    for raw in lines[1:]:
        text = raw.decode("utf-8")
        end = "\r" if text.endswith("\r") else ""
        body = text[:-1] if end else text
        fields = body.split(",")
        if body and fields[ia] in table:
            new = fields[ia]
            fields[ia] = table[new]
            if ir is not None:
                fields[ir] = table[new] + fields[ir][len(new):]
            out.append((",".join(fields) + end).encode("utf-8"))
        else:
            out.append(raw)
    return b"\n".join(out)


def arm_dirs(root):
    """Directories that hold one arm's run (a DONE file or a CSV), under a scenario of a campaign tree."""
    found = []
    for tree in sorted((root / "results").glob("campaign*")):
        for d in sorted(tree.rglob("*")):
            if not d.is_dir() or scenario_of(d.relative_to(root)) is None:
                continue
            if (d / "DONE").exists() or any(d.glob("*.csv")):
                found.append(d)
    return found


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--apply", action="store_true")
    ap.add_argument("--root", default=str(ROOT))
    a = ap.parse_args()
    root = pathlib.Path(a.root).resolve()
    moves, rows, files, collisions = [], 0, 0, []
    for d in arm_dirs(root):
        sc = scenario_of(d.relative_to(root))
        new = new_name(sc, d.name)
        if new is None:
            continue
        target = d.with_name(new)
        if target.exists():
            collisions.append((d, target))
        moves.append((d, target, sc))
    for d, t in collisions:
        print(f"collision: {d.relative_to(root).as_posix()} -> {t.relative_to(root).as_posix()} exists")
    if collisions:
        return 1
    bad = 0
    for d, target, sc in moves:
        for f in sorted(d.glob("*.csv")):
            data = f.read_bytes()
            new, n = rewrite(data, sc)
            if inverse(new, sc) != data:
                print(f"self-check failed: {f.relative_to(root).as_posix()}")
                bad += 1
            rows += n
            files += 1
            if a.apply and n:
                f.write_bytes(new)
        if a.apply:
            subprocess.run(["git", "mv", str(d), str(target)], cwd=root, check=True)
    left = 0
    if a.apply:
        for d in arm_dirs(root):
            sc = scenario_of(d.relative_to(root))
            for f in d.glob("*.csv"):
                with open(f, newline="", encoding="utf-8") as fh:
                    for r in csv.DictReader(fh):
                        if new_name(sc, r.get("algorithm", "")) is not None:
                            left += 1
    print(f"arm directories to rename: {len(moves)}; CSV files: {files}; rows rewritten: {rows}; "
          f"self-check failures: {bad}; old names left after --apply: {left if a.apply else 'not applied'}")
    return 1 if bad or left else 0


if __name__ == "__main__":
    sys.exit(main())
