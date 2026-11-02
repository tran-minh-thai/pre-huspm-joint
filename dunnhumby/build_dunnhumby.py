#!/usr/bin/env python3
"""
Build a HUSPM sequence database from dunnhumby "The Complete Journey" (2,500 households, two years).

    python dunnhumby/build_dunnhumby.py --dir <folder with transaction_data.csv and product.csv> \
        --out-dir datasets --name DUNNHUMBY

Roles of the columns:
    household_key   the owner of a sequence; a sequence is one household in one block of four weeks
                    (WEEK_NO 1-4, 5-8, ...)
    BASKET_ID       one element: the products of one shopping trip, quantities of a product summed
    DAY, TRANS_TIME the order of the elements
    PRODUCT_ID      the item
    QUANTITY        the internal utility (units bought)
    SALES_VALUE     the line total, not the unit price: for the 29,016 products bought at quantities
                    1 and 2, the median ratio of their sales values is 2.000. The unit price is
                    SALES_VALUE / QUANTITY, and an item's profit is the median of its unit prices.

Dropped: lines of the commodity COUPON/MISC ITEMS (fuel and miscellaneous transactions; their
QUANTITY is not a number of units, the median for fuel is 10,005), lines with QUANTITY <= 0 or
SALES_VALUE <= 0, and sequences with fewer than two elements.
"""
import argparse
import csv
import statistics
from collections import Counter, defaultdict


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--dir', required=True)
    ap.add_argument('--out-dir', required=True)
    ap.add_argument('--name', required=True)
    a = ap.parse_args()

    misc = set()
    with open(f"{a.dir}/product.csv", encoding='utf-8', newline='') as f:
        for r in csv.DictReader(f):
            if r['COMMODITY_DESC'].strip() == 'COUPON/MISC ITEMS':
                misc.add(r['PRODUCT_ID'])

    drop = Counter()
    unit_prices = defaultdict(list)
    groups = defaultdict(list)          # (household, block) -> [(day, time, basket, line, product, qty)]
    kept = 0
    with open(f"{a.dir}/transaction_data.csv", encoding='utf-8', newline='') as f:
        for line, r in enumerate(csv.DictReader(f)):
            pid = r['PRODUCT_ID']
            if pid in misc: drop['COUPON/MISC ITEMS'] += 1; continue
            qty, value = int(r['QUANTITY']), float(r['SALES_VALUE'])
            if qty <= 0: drop['quantity <= 0'] += 1; continue
            if value <= 0: drop['sales value <= 0'] += 1; continue
            unit_prices[pid].append(value / qty)
            key = (int(r['household_key']), (int(r['WEEK_NO']) - 1) // 4)
            groups[key].append((int(r['DAY']), int(r['TRANS_TIME']), r['BASKET_ID'], line, pid, qty))
            kept += 1
    if not kept: raise SystemExit("no row kept")

    items = sorted(unit_prices, key=int)
    item_id = {p: i + 1 for i, p in enumerate(items)}
    price = {p: statistics.median(unit_prices[p]) for p in items}

    seq_path = f"{a.out_dir}/{a.name}_seq.txt"
    eui_path = f"{a.out_dir}/{a.name}_eui.txt"
    written = short = 0
    elements, sizes = [], []
    with open(seq_path, 'w', encoding='utf-8', newline='\n') as f:
        for key in sorted(groups):
            order, basket = [], defaultdict(Counter)
            for _day, _time, b, _line, pid, qty in sorted(groups[key]):
                if b not in basket: order.append(b)
                basket[b][pid] += qty
            if len(order) < 2: short += 1; continue
            parts = []
            for b in order:
                el = sorted(basket[b].items(), key=lambda x: item_id[x[0]])
                parts.append(' '.join(f"{item_id[p]}[{q}]" for p, q in el))
                sizes.append(len(el))
            f.write(' -1 '.join(parts) + ' -1 -2\n')
            elements.append(len(order)); written += 1

    with open(eui_path, 'w', encoding='utf-8', newline='\n') as f:
        f.write("# itemID:profit, measured (dunnhumby SALES_VALUE / QUANTITY, median per product)\n")
        for p in items:
            f.write(f"{item_id[p]}:{price[p]:.4f}\n")

    elements.sort()
    print(f"source        : {a.dir}")
    print(f"rows kept     : {kept:,}")
    for k, v in drop.most_common(): print(f"   dropped, {k:<24} {v:>9,}")
    print(f"\nsequences     : {written:,}   ({short:,} with fewer than 2 elements dropped)")
    print(f"distinct items: {len(items):,}")
    print(f"elements/seq  : mean {statistics.mean(elements):.2f}  median {statistics.median(elements):.0f}  max {elements[-1]:,}")
    print(f"items/element : mean {statistics.mean(sizes):.2f}  median {statistics.median(sizes):.0f}  max {max(sizes):,}")
    print(f"\n-> {seq_path}\n-> {eui_path}")


if __name__ == '__main__':
    main()
