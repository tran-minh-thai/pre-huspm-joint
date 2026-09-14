#!/usr/bin/env python3
"""
Dung bo du lieu chuoi HUSPM tu Ta-Feng (11/2000 - 02/2001).

SALES_PRICE trong Ta-Feng la TONG TIEN cua dong, khong phai gia don vi: ty so
SALES_PRICE giua AMOUNT=2 va AMOUNT=1 cho cung mat hang do duoc la 2.000 (trung vi
tren 4.378 mat hang). Gia don vi = SALES_PRICE / AMOUNT. Gia dinh nguoc lai se lam
moi utility sai dung bang he so AMOUNT, va sai mot cach im lang.

Luu y ve thu tu: TRANSACTION_DT chi co do phan giai NGAY. Cac dong cung ngay giu
nguyen thu tu trong file. Thu tu GIUA cac ngay la thu tu thoi gian that, va do moi
la thu tu ma khai thac mau tuan tu dua vao.
"""
import argparse, csv, io, statistics, zipfile
from collections import defaultdict, Counter


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--zip', required=True)
    ap.add_argument('--member', default=None, help='ten file trong zip; mac dinh lay file dau')
    ap.add_argument('--out-dir', required=True)
    ap.add_argument('--name', required=True)
    ap.add_argument('--segment', choices=('month', 'day', 'customer'), default='month')
    ap.add_argument('--min-events', type=int, default=2)
    a = ap.parse_args()

    z = zipfile.ZipFile(a.zip)
    member = a.member or z.infolist()[0].filename

    drop = Counter()
    unit_prices = defaultdict(list)
    groups = defaultdict(list)          # khoa -> [(date, product, qty)]
    kept = 0

    with z.open(member) as fh:
        rd = csv.DictReader(io.TextIOWrapper(fh, encoding='utf-8-sig'))
        for d in rd:
            cust = (d.get('CUSTOMER_ID') or '').strip()
            if not cust: drop['khong co customer'] += 1; continue
            pid = (d.get('PRODUCT_ID') or '').strip()
            if not pid: drop['khong co product'] += 1; continue

            try: qty = int(d['AMOUNT'])
            except (TypeError, ValueError, KeyError): drop['so luong khong hop le'] += 1; continue
            if qty <= 0: drop['so luong <= 0'] += 1; continue

            try: total = float(d['SALES_PRICE'])
            except (TypeError, ValueError, KeyError): drop['gia khong hop le'] += 1; continue
            if total <= 0: drop['gia <= 0'] += 1; continue

            dt = (d.get('TRANSACTION_DT') or '').strip()
            try:
                m, day, y = (int(x) for x in dt.split('/'))
            except ValueError: drop['ngay khong hop le'] += 1; continue

            unit_prices[pid].append(total / qty)
            if a.segment == 'month':   key = (cust, f"{y:04d}-{m:02d}")
            elif a.segment == 'day':   key = (cust, f"{y:04d}-{m:02d}-{day:02d}")
            else:                      key = (cust,)
            groups[key].append(((y, m, day), pid, qty))
            kept += 1

    if not kept: raise SystemExit("khong giu lai duoc dong nao")

    items = sorted(unit_prices)
    item_id = {p: i + 1 for i, p in enumerate(items)}
    price = {p: statistics.median(unit_prices[p]) for p in items}

    seq_path = f"{a.out_dir}/{a.name}_seq.txt"
    eui_path = f"{a.out_dir}/{a.name}_eui.txt"

    lens, maxq, written, short = [], 0, 0, 0
    with open(seq_path, 'w', encoding='utf-8', newline='\n') as f:
        for key in sorted(groups):
            rows = sorted(groups[key], key=lambda r: r[0])       # sap theo ngay, giu thu tu trong ngay
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
    print(f"nguon        : {a.zip} :: {member}")
    print(f"cat theo     : {a.segment}, min-events={a.min_events}")
    print(f"dong giu lai : {kept:,}")
    for k, v in drop.most_common(): print(f"   bo {k:<28} {v:>9,}")
    print(f"\nchuoi ghi ra : {written:,}   (bo {short:,} chuoi < {a.min_events} su kien)")
    print(f"item rieng   : {len(items):,}")
    print(f"su kien/chuoi: tb {statistics.mean(lens):.2f}  trung vi {statistics.median(lens):.0f}  max {lens[-1]:,}")
    for p in (90, 95, 99):
        print(f"   p{p}         = {lens[min(len(lens)-1, len(lens)*p//100)]:,}")
    print(f"so luong max : {maxq:,}")
    print(f"tong utility : {tu:,.0f}")
    print(f"\n-> {seq_path}\n-> {eui_path}")


if __name__ == '__main__':
    main()
