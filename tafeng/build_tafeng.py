#!/usr/bin/env python3
"""
Build a HUSPM sequence database from Ta-Feng (Nov 2000 - Feb 2001).

SALES_PRICE in Ta-Feng is the line total, not the unit price: for the same item, the
ratio of SALES_PRICE at AMOUNT=2 to AMOUNT=1 is 2.0 (median over 4,378 items). The unit
price is therefore SALES_PRICE / AMOUNT; treating SALES_PRICE as a unit price would
silently scale every utility by AMOUNT.

Ordering: TRANSACTION_DT has day resolution only. Rows of the same day keep their file
order. The order between days is the true chronological order, which is the order
sequential pattern mining relies on.
"""
import argparse, csv, io, statistics, zipfile
from collections import defaultdict, Counter


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--zip', required=True)
    ap.add_argument('--member', default=None, help='member of the zip to read; default: the first one')
    ap.add_argument('--out-dir', required=True)
    ap.add_argument('--name', required=True)
    ap.add_argument('--segment', choices=('month', 'day', 'customer'), default='month')
    ap.add_argument('--min-events', type=int, default=2)
    a = ap.parse_args()

    z = zipfile.ZipFile(a.zip)
    member = a.member or z.infolist()[0].filename

    drop = Counter()
    unit_prices = defaultdict(list)
    groups = defaultdict(list)          # key -> [(date, product, qty)]
    kept = 0

    with z.open(member) as fh:
        rd = csv.DictReader(io.TextIOWrapper(fh, encoding='utf-8-sig'))
        for d in rd:
            cust = (d.get('CUSTOMER_ID') or '').strip()
            if not cust: drop['no customer'] += 1; continue
            pid = (d.get('PRODUCT_ID') or '').strip()
            if not pid: drop['no product'] += 1; continue

            try: qty = int(d['AMOUNT'])
            except (TypeError, ValueError, KeyError): drop['invalid quantity'] += 1; continue
            if qty <= 0: drop['quantity <= 0'] += 1; continue

            try: total = float(d['SALES_PRICE'])
            except (TypeError, ValueError, KeyError): drop['invalid price'] += 1; continue
            if total <= 0: drop['price <= 0'] += 1; continue

            dt = (d.get('TRANSACTION_DT') or '').strip()
            try:
                m, day, y = (int(x) for x in dt.split('/'))
            except ValueError: drop['invalid date'] += 1; continue

            unit_prices[pid].append(total / qty)
            if a.segment == 'month':   key = (cust, f"{y:04d}-{m:02d}")
            elif a.segment == 'day':   key = (cust, f"{y:04d}-{m:02d}-{day:02d}")
            else:                      key = (cust,)
            groups[key].append(((y, m, day), pid, qty))
            kept += 1

    if not kept: raise SystemExit("no row kept")

    items = sorted(unit_prices)
    item_id = {p: i + 1 for i, p in enumerate(items)}
    price = {p: statistics.median(unit_prices[p]) for p in items}

    seq_path = f"{a.out_dir}/{a.name}_seq.txt"
    eui_path = f"{a.out_dir}/{a.name}_eui.txt"

    lens, maxq, written, short = [], 0, 0, 0
    with open(seq_path, 'w', encoding='utf-8', newline='\n') as f:
        for key in sorted(groups):
            rows = sorted(groups[key], key=lambda r: r[0])       # by date; file order within a day
            if len(rows) < a.min_events: short += 1; continue
            parts = []
            for _d, pid, qty in rows:
                parts.append(f"{item_id[pid]}[{qty}]")
                if qty > maxq: maxq = qty
            f.write(' -1 '.join(parts) + ' -1 -2\n')
            lens.append(len(rows)); written += 1

    with open(eui_path, 'w', encoding='utf-8', newline='\n') as f:
        f.write("# itemID:profit, measured (Ta-Feng SALES_PRICE / AMOUNT, median per item)\n")
        for p in items:
            f.write(f"{item_id[p]}:{price[p]:.2f}\n")

    tu = sum(q * price[pid] for key, rows in groups.items() if len(rows) >= a.min_events
             for _d, pid, q in rows)
    lens.sort()
    print(f"source        : {a.zip} :: {member}")
    print(f"segment by    : {a.segment}, min-events={a.min_events}")
    print(f"rows kept     : {kept:,}")
    for k, v in drop.most_common(): print(f"   dropped, {k:<24} {v:>9,}")
    print(f"\nsequences     : {written:,}   ({short:,} with fewer than {a.min_events} events dropped)")
    print(f"distinct items: {len(items):,}")
    print(f"events/seq    : mean {statistics.mean(lens):.2f}  median {statistics.median(lens):.0f}  max {lens[-1]:,}")
    for p in (90, 95, 99):
        print(f"   p{p}          = {lens[min(len(lens)-1, len(lens)*p//100)]:,}")
    print(f"max quantity  : {maxq:,}")
    print(f"total utility : {tu:,.0f}")
    print(f"\n-> {seq_path}\n-> {eui_path}")


if __name__ == '__main__':
    main()
