#!/usr/bin/env python3
"""Shape of each database, counted on the data files: sequences, distinct items that occur, the
largest item identifier, elements per sequence, items per element and per sequence, and the
total utility. No timing.

    python3 analysis/database_stats.py [--datasets datasets] [--out results/invariant-database-stats]

A sequence line holds `item[quantity]` tokens, `-1` closing an element and `-2` the sequence (an
element left open at `-2` is closed by it); lines starting with `#` and empty lines are skipped,
as `common.DatasetLoader` does. The utility of an occurrence is its quantity times the item's
profit in the `_eui` file.
"""
import argparse
import csv
import os
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_DBS = ["SIGN", "LEVIATHAN", "FIFA", "BIBLE", "BMS1", "KOSARAK", "C8T1S5I8N5K", "TAFENG"]
TOKEN = re.compile(r"^(-?\d+)(?:\[(\d+(?:\.\d+)?)\])?$")


def profits(path):
    out = {}
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            item, profit = line.split(":")
            out[int(item)] = float(profit)
    return out


def scan_lines(lines, profit):
    """Counts over an iterable of sequence lines; raises on a token it cannot read."""
    seqs = elems = occ = 0
    items, max_item, tu = set(), -1, 0.0
    for raw in lines:
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        open_elem = 0
        for tok in line.split():
            m = TOKEN.match(tok)
            if not m:
                raise ValueError(f"unreadable token {tok!r}")
            v = int(m.group(1))
            if v == -1:
                if open_elem:
                    elems += 1
                open_elem = 0
            elif v == -2:
                if open_elem:
                    elems += 1
                open_elem = 0
                seqs += 1
            elif v >= 0:
                q = float(m.group(2)) if m.group(2) else 1.0
                items.add(v)
                max_item = max(max_item, v)
                occ += 1
                open_elem += 1
                tu += q * profit.get(v, 0.0)
            else:
                raise ValueError(f"unexpected separator {tok!r}")
    return dict(sequences=seqs, items_occurring=len(items), max_item_id=max_item, elements=elems,
                occurrences=occ, elements_per_sequence=elems / seqs if seqs else 0.0,
                items_per_element=occ / elems if elems else 0.0,
                items_per_sequence=occ / seqs if seqs else 0.0, total_utility=tu)


def self_test():
    """Hand-built lines with known counts, including an element closed by -2 alone."""
    p = {1: 2.0, 2: 1.0, 7: 3.0}
    r = scan_lines(["# comment", "", "1[2] 2[1] -1 7[1] -1 -2", "2[3] -1 1[1] 7[2] -2"], p)
    want = dict(sequences=2, items_occurring=3, max_item_id=7, elements=4, occurrences=6, total_utility=19.0)
    for k, v in want.items():
        assert r[k] == v, (k, r[k], v)
    try:
        scan_lines(["1[2] x -2"], p)
        raise AssertionError("an unreadable token was accepted")
    except ValueError:
        pass
    return len(want) + 1


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--datasets", default=str(ROOT / "datasets"))
    ap.add_argument("--out", default=str(ROOT / "results" / "invariant-database-stats"))
    ap.add_argument("dbs", nargs="*", default=DEFAULT_DBS)
    a = ap.parse_args(argv)
    print(f"self-test: {self_test()} checks passed")
    rows = []
    for name in a.dbs:
        seq = os.path.join(a.datasets, f"{name}_seq.txt")
        eui = os.path.join(a.datasets, f"{name}_eui.txt")
        if not (os.path.exists(seq) and os.path.exists(eui)):
            print(f"{name}: data files missing in {a.datasets}", file=sys.stderr)
            return 1
        with open(seq, encoding="utf-8") as fh:
            r = scan_lines(fh, profits(eui))
        rows.append(dict(dataset=name, **r))
        print(f"{name}: {r['sequences']} sequences, {r['items_occurring']} items occur (largest id "
              f"{r['max_item_id']}), {r['elements_per_sequence']:.4f} elements per sequence, "
              f"{r['items_per_element']:.4f} items per element, {r['items_per_sequence']:.4f} per sequence, "
              f"TU {r['total_utility']:.0f}")
    os.makedirs(a.out, exist_ok=True)
    with open(os.path.join(a.out, "database_stats.csv"), "w", newline="", encoding="utf-8") as fh:
        w = csv.DictWriter(fh, fieldnames=list(rows[0]))
        w.writeheader()
        w.writerows(rows)
    return 0


if __name__ == "__main__":
    sys.exit(main())
