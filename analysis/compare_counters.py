#!/usr/bin/env python3
"""Compare the counted columns of two result directories row by row, for a change that must not
alter what the algorithm reads, finds or decides.

    python3 analysis/compare_counters.py BEFORE_DIR AFTER_DIR [--columns c1,c2,...]

Rows are matched on (file, algorithm, dataset, op_type, iteration, seed, repeat). Every CSV file of
BEFORE_DIR must exist in AFTER_DIR with the same keys. The default columns are the sequences read
by the direct updates (blocks_read), the patterns reported and tracked, the rescan decision and
count, and the work of the mines. Prints the number of rows and values compared per file and every
difference; exits 1 on a difference, a missing file or key, or when nothing was compared.
"""
import argparse
import csv
import pathlib
import sys

KEY = ("algorithm", "dataset", "op_type", "iteration", "seed", "repeat")
COLUMNS = ("blocks_read", "total_patterns_found", "prelarge_count", "cub_triggered", "rescan_count",
           "candidate_count", "explored_nodes")


def rows(path):
    with open(path, newline="", encoding="utf-8") as fh:
        out = {}
        for r in csv.DictReader(fh):
            k = tuple(r.get(c, "") for c in KEY)
            if k in out:
                sys.exit(f"{path}: key {k} occurs twice")
            out[k] = r
        return out


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("before")
    ap.add_argument("after")
    ap.add_argument("--columns", default=",".join(COLUMNS))
    a = ap.parse_args()
    cols = a.columns.split(",")
    before, after = pathlib.Path(a.before), pathlib.Path(a.after)
    bad = compared = 0
    files = sorted(p for p in before.rglob("*.csv") if p.name != "verify_husps.csv")
    for f in files:
        g = after / f.relative_to(before)
        if not g.exists():
            print(f"missing in {after}: {g.relative_to(after).as_posix()}")
            bad += 1
            continue
        rb, ra = rows(f), rows(g)
        n = 0
        for k, r in rb.items():
            if k not in ra:
                print(f"{f.name}: row {k} missing after")
                bad += 1
                continue
            for c in cols:
                if c not in r:
                    continue
                n += 1
                if r[c] != ra[k][c]:
                    bad += 1
                    print(f"{f.name} {k} {c}: {r[c]} before, {ra[k][c]} after")
        extra = set(ra) - set(rb)
        if extra:
            print(f"{f.name}: {len(extra)} rows only after")
            bad += len(extra)
        compared += n
        print(f"{f.relative_to(before).as_posix()}: {len(rb)} rows, {n} values compared")
    print(f"values compared: {compared}; differences: {bad}")
    return 1 if bad or compared == 0 else 0


if __name__ == "__main__":
    sys.exit(main())
