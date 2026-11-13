#!/usr/bin/env python3
"""Row-level facts of the raw Ta-Feng file that the build relies on (standard library only).

    python3 tafeng/describe_tafeng.py --zip <archive.zip> [--member NAME]

Prints the columns, the row count, the rows identical in every column to an earlier row, the rows
that repeat the (day, customer, product) of an earlier row, and the lines per customer and day. The
file has no invoice identifier, so build_tafeng.py makes every line an element of its own.
"""
import argparse, collections, csv, io, statistics, zipfile

ap = argparse.ArgumentParser()
ap.add_argument("--zip", required=True)
ap.add_argument("--member", default=None)
a = ap.parse_args()
z = zipfile.ZipFile(a.zip)
member = a.member or z.infolist()[0].filename
seen_full, seen_key, per_day = set(), set(), collections.Counter()
rows = full = key = 0
with z.open(member) as fh:
    rd = csv.reader(io.TextIOWrapper(fh, encoding="utf-8-sig"))
    head = next(rd)
    ix = {c: i for i, c in enumerate(head)}
    for r in rd:
        rows += 1
        t = tuple(r)
        full += t in seen_full
        seen_full.add(t)
        k = (r[ix["TRANSACTION_DT"]], r[ix["CUSTOMER_ID"]], r[ix["PRODUCT_ID"]])
        key += k in seen_key
        seen_key.add(k)
        per_day[(r[ix["TRANSACTION_DT"]], r[ix["CUSTOMER_ID"]])] += 1
v = sorted(per_day.values())
print(f"columns: {', '.join(head)}")
print(f"rows: {rows:,}")
print(f"rows identical in every column to an earlier row: {full:,}")
print(f"rows repeating the (day, customer, product) of an earlier row: {key:,}")
print(f"customer-days: {len(v):,}; lines per customer-day: median {statistics.median(v):g}, max {v[-1]:,}")
