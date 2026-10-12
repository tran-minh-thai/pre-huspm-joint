#!/usr/bin/env python3
"""Ranges of the external utilities (unit profits) and internal utilities (quantities) of the
databases, and every value that breaks the model's assumption p(i) > 0, q > 0.

    python3 analysis/utility_ranges.py [--datasets DIR] [--out DIR] [DB ...]

Reads <DB>_eui.txt and <DB>_seq.txt the way common.DatasetLoader does: comment lines skipped,
"item[quantity]" tokens, -1 ends an itemset, -2 a sequence. The loader gives an item that is
missing from the _eui file a profit of 0 and drops a token it cannot parse, so both are
counted here as well: either would put a zero or a hole into the data without a message.

Writes <out>/utility_ranges.csv (one row per database) and prints the same table. Exits with
code 1 when any database has a value at or below zero, an occurrence of an item without a
profit, or an unparsable token.
"""
import argparse
import csv
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
DEFAULT_DBS = ["SIGN", "LEVIATHAN", "FIFA", "BIBLE", "BMS1", "KOSARAK", "C8T1S5I8N5K", "TAFENG"]
FIELDS = ["dataset", "profit_items", "profit_min", "profit_max", "profit_le0",
          "occurrences", "quantity_min", "quantity_max", "quantity_le0", "quantity_non_integer",
          "occurrences_without_profit", "items_without_profit", "unparsable_tokens"]


def read_profits(path):
    profits = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        t = line.strip()
        if not t or t.startswith(("#", "%")):
            continue
        parts = t.split(":")
        if len(parts) == 2:
            profits[int(parts[0])] = float(parts[1])
    return profits


def scan(name, data_dir):
    profits = read_profits(data_dir / f"{name}_eui.txt")
    pv = list(profits.values())
    row = dict(dataset=name, profit_items=len(pv), profit_min=min(pv), profit_max=max(pv),
               profit_le0=sum(v <= 0 for v in pv), occurrences=0, quantity_min=None,
               quantity_max=None, quantity_le0=0, quantity_non_integer=0,
               occurrences_without_profit=0, items_without_profit=0, unparsable_tokens=0)
    missing = set()
    with open(data_dir / f"{name}_seq.txt", encoding="utf-8") as fh:
        for line in fh:
            t = line.strip()
            if not t or t.startswith(("#", "%", "@")):
                continue
            for tok in t.split():
                if tok in ("-1", "-2"):
                    continue
                l, r = tok.find("["), tok.find("]")
                try:
                    if l < 0 or r < 0:
                        raise ValueError
                    item, q = int(tok[:l]), float(tok[l + 1:r])
                except ValueError:
                    row["unparsable_tokens"] += 1
                    continue
                row["occurrences"] += 1
                row["quantity_min"] = q if row["quantity_min"] is None else min(row["quantity_min"], q)
                row["quantity_max"] = q if row["quantity_max"] is None else max(row["quantity_max"], q)
                row["quantity_le0"] += q <= 0
                row["quantity_non_integer"] += q != int(q)
                if item not in profits:
                    row["occurrences_without_profit"] += 1
                    missing.add(item)
    row["items_without_profit"] = len(missing)
    return row


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--datasets", default=str(ROOT / "datasets"))
    ap.add_argument("--out", default=str(ROOT / "results" / "invariant-utility-ranges"))
    ap.add_argument("dbs", nargs="*", default=DEFAULT_DBS)
    a = ap.parse_args(argv)
    rows = [scan(n, pathlib.Path(a.datasets)) for n in a.dbs]
    out = pathlib.Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    with open(out / "utility_ranges.csv", "w", newline="", encoding="utf-8") as fh:
        w = csv.DictWriter(fh, fieldnames=FIELDS, lineterminator="\n")
        w.writeheader()
        w.writerows(rows)
    print(",".join(FIELDS))
    for r in rows:
        print(",".join(f"{r[f]:g}" if isinstance(r[f], float) else str(r[f]) for f in FIELDS))
    bad = [r["dataset"] for r in rows if r["profit_le0"] or r["quantity_le0"]
           or r["occurrences_without_profit"] or r["unparsable_tokens"]]
    print(f"{len(rows)} databases; breaking p(i) > 0, q > 0 or unparsable: "
          f"{', '.join(bad) if bad else 'none'}")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
