#!/usr/bin/env python3
"""
Build ONLINERETAIL and the replayed log of SC11 from Online Retail II (UCI dataset 502, CC BY 4.0,
Chen 2012, doi:10.24432/C5CG6D). Standard library only; the build is deterministic.

    python onlineretail/build_onlineretail.py --xlsx online_retail_II.xlsx --out-dir datasets

Roles of the columns:
    Customer ID   the owner of a sequence; a sequence is one customer in one calendar month
    Invoice       one element (its products, quantities of a product summed); an invoice whose
                  number starts with C is a cancellation
    InvoiceDate   the order of the elements, the day of an operation and the month of a sequence
    StockCode     the item; only codes of five digits and an optional letter suffix name a product
    Quantity      the units bought and cancelled; the build nets cancellations on them, but every
                  item occurrence written has internal utility 1 (see below)
    Price         the unit price; the external utility of a product is the median of its unit
                  prices over the purchase lines kept (quantity > 0), written with two decimals
    Description   written to the item table, for reading patterns; most frequent text per code

Lines dropped, in this order: no customer, a stock code that is not a product, a nonpositive
price, and a nonpositive quantity on an invoice that is not a cancellation. The two sheets of the
file overlap from 2010-12-01 to 2010-12-09 with identical rows; the rows of the first sheet dated
on or after the first day of the second sheet are dropped (the build checks the two copies agree).
Rows repeated within a sheet are kept: they are recorded lines of the invoice.

Internal utility 1. With the units as internal utility, the largest customer-month of D_init, a
wholesale order, holds 1.37% of the total utility, and the thresholds at which patterns exist sit
at its share: 0.014 gives one pattern, 0.013 gives 2,560 subsets of that order, and 0.012 and below
exceed the search budget, so no threshold passes the cost check at the lower threshold
(dataset-build probe of 2026-11-25). With every occurrence at one unit the largest sequence holds
0.28% and 0.004 to 0.007 give 13 to 2 patterns. A cancellation therefore changes the database when
it removes a purchase (its units reach zero); one that only lowers the units leaves the sequence as
it was and yields no operation. Both kinds are counted.

The log is processed in the order of time, invoice number and row. D_init is the database after
every line dated before 2010-12-01: the sequences of December 2009 to November 2010. The rest is
replayed by day. A purchase opens the sequence of its customer and month or appends its invoice
to it. A cancellation lowers by its quantity the most recent earlier purchase of the same product
by the same customer that is still in the database; it is capped at the quantity of that purchase
(the surplus is counted), and dropped and counted when there is no such purchase. An item left at
zero units is removed from its element, an empty element from its sequence. At the end of a day
the sequences it touched are compared with the start of the day: a new nonempty sequence is
inserted, a changed one is modified (old version out, new version in), an emptied one is deleted.
So a day yields at most one insertion, one modification and one deletion, in this order.

Outputs, with NAME = ONLINERETAIL:
    NAME_seq.txt            D_init, one sequence per line in the order (month, customer)
    NAME_eui.txt            external utilities, itemID:price
    NAME_ops.txt            the operations of SC11 (format below)
    NAME_ops_nocancel.txt   the same log with every cancellation after D_init ignored: what a
                            method for insertion only maintains
    NAME_final_seq.txt      the database after the last operation, for an end-to-end check
    NAME_items.tsv          itemID, stock code, description, price
    NAME_build.json         counts of the build

Operation file: a header line "OP <n> <kind> <day> <k>" (kind INS, MOD or DEL) followed by k
lines. An INS line is "<new id> <sequence>", a MOD line "<old id> <new id> <sequence>", a DEL line
"<old id>". The sequences of D_init have ids 1..|D_init| in file order; every new version of a
sequence takes the next unused id. A sequence is written in the SPMF utility format of the other
databases: item[quantity] items of one element separated by spaces, -1 after each element, -2 at
the end.
"""
import argparse
import datetime
import hashlib
import json
import re
import statistics
import sys
import xml.etree.ElementTree as ET
import zipfile
from collections import Counter, defaultdict

NS = "{http://schemas.openxmlformats.org/spreadsheetml/2006/main}"
RNS = "{http://schemas.openxmlformats.org/officeDocument/2006/relationships}"
PRODUCT = re.compile(r"^\d{5}[A-Za-z]*$")
SPLIT = datetime.datetime(2010, 12, 1)          # first instant after D_init
NAME = "ONLINERETAIL"


def col_index(ref):
    n = 0
    for ch in ref:
        if not ch.isalpha():
            break
        n = n * 26 + ord(ch.upper()) - 64
    return n - 1


def read_xlsx(path):
    """(sheet name, row values) for every row of every sheet, with the standard library only."""
    z = zipfile.ZipFile(path)
    strings = []
    if "xl/sharedStrings.xml" in z.namelist():
        for _, el in ET.iterparse(z.open("xl/sharedStrings.xml")):
            if el.tag == NS + "si":
                strings.append("".join(t.text or "" for t in el.iter(NS + "t")))
                el.clear()
    wb = ET.parse(z.open("xl/workbook.xml")).getroot()
    rels = ET.parse(z.open("xl/_rels/workbook.xml.rels")).getroot()
    target = {r.get("Id"): r.get("Target") for r in rels}
    for s in wb.iter(NS + "sheet"):
        part = "xl/" + target[s.get(RNS + "id")].lstrip("/").replace("xl/", "")
        for _, el in ET.iterparse(z.open(part)):
            if el.tag != NS + "row":
                continue
            row = {}
            for c in el.iter(NS + "c"):
                v, t = c.find(NS + "v"), c.get("t")
                if t == "inlineStr":
                    val = "".join(x.text or "" for x in c.iter(NS + "t"))
                elif v is None:
                    val = None
                elif t == "s":
                    val = strings[int(v.text)]
                else:
                    val = v.text
                row[col_index(c.get("r"))] = val
            el.clear()
            yield s.get("name"), ([row.get(i) for i in range(max(row) + 1)] if row else [])


def excel_date(v):
    # whole seconds: the file stores minutes, and float noise must not split one invoice in two
    t = datetime.datetime(1899, 12, 30) + datetime.timedelta(days=float(v))
    return (t + datetime.timedelta(microseconds=500_000)).replace(microsecond=0)


def read_log(path, counts):
    """The kept lines as (time, invoice, row, customer, stock, qty, price, description, is_cancel),
    all raw invoices, and the overlap check."""
    rows, header, sheets = [], None, []
    overlap = {"first sheet": Counter(), "second sheet": Counter()}
    raw_invoices = set()
    order = 0
    for sheet, row in read_xlsx(path):
        if not row:
            continue
        low = [str(x).strip().lower() if x is not None else "" for x in row]
        if "invoice" in low and "stockcode" in low:
            header = {k: low.index(k) for k in ("invoice", "stockcode", "description", "quantity",
                                                "invoicedate", "price", "customer id")}
            sheets.append(sheet)
            continue
        g = lambda k: row[header[k]] if header[k] < len(row) else None
        when = excel_date(g("invoicedate"))
        if len(sheets) == 2 and when < SPLIT + datetime.timedelta(days=9):
            overlap["second sheet"][tuple(str(x) for x in row)] += 1
        if len(sheets) == 1 and when >= SPLIT:
            overlap["first sheet"][tuple(str(x) for x in row)] += 1
            counts["rows of the first sheet in the overlap, read from the second"] += 1
            continue
        counts["rows read"] += 1
        inv = str(g("invoice") or "").strip()
        raw_invoices.add(inv)
        cust, stock = g("customer id"), str(g("stockcode") or "").strip()
        if cust in (None, ""):
            counts["dropped: no customer"] += 1
            continue
        if not PRODUCT.match(stock):
            counts["dropped: stock code not a product"] += 1
            counts["code:" + stock] += 1
            continue
        qty, price = int(round(float(g("quantity")))), float(g("price"))
        if price <= 0:
            counts["dropped: price <= 0"] += 1
            continue
        cancel = inv.upper().startswith("C")
        if not cancel and qty <= 0:
            counts["dropped: quantity <= 0 outside a cancellation"] += 1
            continue
        if cancel and qty >= 0:
            counts["dropped: cancellation with quantity >= 0"] += 1
            continue
        order += 1
        rows.append((when, inv, order, str(int(float(cust))), stock, abs(qty), price,
                     str(g("description") or "").strip(), cancel))
    if sheets != ["Year 2009-2010", "Year 2010-2011"]:
        raise SystemExit(f"unexpected sheets {sheets}")
    second = Counter({k: c for k, c in overlap["second sheet"].items() if excel_date_from_row(k) >= SPLIT})
    if overlap["first sheet"] != second:
        raise SystemExit("the overlap of the two sheets is not identical; the build would double or lose rows")
    counts["overlap rows checked identical"] = sum(second.values())
    return rows, raw_invoices


def excel_date_from_row(k):
    return excel_date(k[4])


class Database:
    """Sequences keyed by (customer, month); a sequence is a list of elements
    [invoice, time, {stock: qty}]; occurrences index the purchases still in the database."""

    def __init__(self):
        self.seqs = {}
        self.occ = defaultdict(list)          # (customer, stock) -> [(time, order, key, invoice)]

    def purchase(self, when, inv, order, cust, stock, qty):
        key = (cust, f"{when.year:04d}-{when.month:02d}")
        seq = self.seqs.setdefault(key, [])
        if not seq or seq[-1][0] != inv:
            seq.append([inv, when, {}])
        el = seq[-1][2]
        if stock not in el:
            self.occ[(cust, stock)].append((when, order, key, inv))
        el[stock] = el.get(stock, 0) + qty
        return key

    def cancel(self, cust, stock, qty, counts):
        """Lower the most recent earlier purchase still present; returns the key of the sequence when
        the purchase is removed, None when it is only lowered or there is none."""
        lst = self.occ.get((cust, stock))
        while lst:
            when, order, key, inv = lst[-1]
            el = self._element(key, inv)
            if el is None or stock not in el:
                lst.pop()                       # removed earlier
                continue
            have = el[stock]
            take = min(qty, have)
            if qty > have:
                counts["cancellations capped at the purchase"] += 1
                counts["units of capped cancellations not applied"] += qty - have
            el[stock] = have - take
            counts["units cancelled"] += take
            if el[stock] > 0:
                counts["cancellations lowering the units only (no change at internal utility 1)"] += 1
                return None
            counts["cancellations removing a purchase"] += 1
            del el[stock]
            lst.pop()
            seq = self.seqs[key]
            seq[:] = [e for e in seq if e[2]]
            return key
        counts["cancellations without a purchase (dropped)"] += 1
        return None

    def _element(self, key, inv):
        for e in self.seqs.get(key, ()):
            if e[0] == inv:
                return e[2]
        return None

    def snapshot(self, key):
        seq = self.seqs.get(key)
        if not seq:
            return None
        return tuple(tuple((stock, 1) for stock in sorted(e[2])) for e in seq if e[2])


def fmt(snap, item_id):
    return " ".join(" ".join(f"{item_id[s]}[{q}]" for s, q in sorted(el, key=lambda x: item_id[x[0]])) + " -1"
                    for el in snap) + " -2"


def replay(rows, item_id, counts, apply_cancellations):
    """D_init snapshots and the operations after it."""
    db = Database()
    days = defaultdict(list)
    pre, post = Counter(), Counter()
    for r in rows:
        if r[0] < SPLIT:
            when, inv, order, cust, stock, qty, price, desc, cancel = r
            if cancel:
                db.cancel(cust, stock, qty, pre)
                pre["cancellation lines"] += 1
            else:
                db.purchase(when, inv, order, cust, stock, qty)
        else:
            days[r[0].date()].append(r)
    init = sorted((k for k in db.seqs if db.snapshot(k)), key=lambda k: (k[1], int(k[0])))
    ident, current, next_id = {}, {}, 1
    for k in init:
        ident[k], current[k], next_id = next_id, db.snapshot(k), next_id + 1
    init_snaps = [current[k] for k in init]
    ops = []
    for day in sorted(days):
        touched = set()
        for when, inv, order, cust, stock, qty, price, desc, cancel in days[day]:
            if cancel:
                if not apply_cancellations:
                    continue
                post["cancellation lines"] += 1
                k = db.cancel(cust, stock, qty, post)
                if k is not None:
                    touched.add(k)
            else:
                touched.add(db.purchase(when, inv, order, cust, stock, qty))
        ins, mod, dele = [], [], []
        for k in sorted(touched, key=lambda k: (k[1], int(k[0]))):
            snap = db.snapshot(k)
            old = current.get(k)
            if old is None and snap is not None:
                ins.append((next_id, snap)); ident[k] = next_id; current[k] = snap; next_id += 1
            elif old is not None and snap is None:
                dele.append(ident.pop(k)); del current[k]
            elif old is not None and snap != old:
                mod.append((ident[k], next_id, snap, k)); ident[k] = next_id; current[k] = snap; next_id += 1
        for kind, lst in (("INS", ins), ("MOD", mod), ("DEL", dele)):
            if lst:
                ops.append((kind, day, lst))
    for k, v in pre.items():
        counts["before 2010-12-01: " + k] += v
    for k, v in post.items():
        counts["from 2010-12-01: " + k] += v
    return init_snaps, ops, current


def mod_by_cancel(rows, item_id, counts):
    """Modifications of SC11 that lower a purchase: replay once more, recording which touched keys a
    cancellation reached on each day."""
    db = Database()
    hit = defaultdict(set)
    days = defaultdict(list)
    for r in rows:
        if r[0] < SPLIT:
            when, inv, order, cust, stock, qty, price, desc, cancel = r
            (db.cancel(cust, stock, qty, Counter()) if cancel else db.purchase(when, inv, order, cust, stock, qty))
        else:
            days[r[0].date()].append(r)
    for day in sorted(days):
        for when, inv, order, cust, stock, qty, price, desc, cancel in days[day]:
            if cancel:
                k = db.cancel(cust, stock, qty, Counter())
                if k is not None:
                    hit[day].add(k)
            else:
                db.purchase(when, inv, order, cust, stock, qty)
    return hit


def write_ops(path, ops, item_id):
    lines = []
    for i, (kind, day, lst) in enumerate(ops, start=1):
        lines.append(f"OP {i} {kind} {day.isoformat()} {len(lst)}")
        for e in lst:
            if kind == "INS":
                lines.append(f"{e[0]} {fmt(e[1], item_id)}")
            elif kind == "MOD":
                lines.append(f"{e[0]} {e[1]} {fmt(e[2], item_id)}")
            else:
                lines.append(str(e))
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines) + "\n")


def apply_ops(seq_path, ops_path):
    """Replay an operation file on the D_init file by id, as the experiment harness does; returns the
    sorted lines of the database after the last operation, and fails on any id that is missing,
    reused or deleted twice."""
    cur = {i + 1: line.rstrip("\n") for i, line in enumerate(open(seq_path, encoding="utf-8"))}
    used = set(cur)
    lines = open(ops_path, encoding="utf-8").read().splitlines()
    i = 0
    while i < len(lines):
        _, n, kind, day, k = lines[i].split()
        for line in lines[i + 1:i + 1 + int(k)]:
            if kind == "INS":
                new, seq = line.split(" ", 1)
                if int(new) in used:
                    raise SystemExit(f"operation {n}: id {new} reused")
                cur[int(new)] = seq
                used.add(int(new))
            elif kind == "MOD":
                old, new, seq = line.split(" ", 2)
                if int(old) not in cur or int(new) in used:
                    raise SystemExit(f"operation {n}: id {old} missing or {new} reused")
                del cur[int(old)]
                cur[int(new)] = seq
                used.add(int(new))
            else:
                if int(line) not in cur:
                    raise SystemExit(f"operation {n}: deletion of missing id {line}")
                del cur[int(line)]
        i += 1 + int(k)
    return sorted(cur.values())


def describe(snaps, eui):
    n_el = [len(s) for s in snaps]
    n_it = [sum(len(e) for e in s) for s in snaps]
    tu = sum(q * eui[st] for s in snaps for e in s for st, q in e)
    return dict(sequences=len(snaps), items=len({st for s in snaps for e in s for st, _ in e}),
                elements_per_sequence=round(statistics.mean(n_el), 4),
                items_per_sequence=round(statistics.mean(n_it), 4),
                items_per_element=round(sum(n_it) / sum(n_el), 4), total_utility=round(tu, 2))


def sha256(path):
    return hashlib.sha256(open(path, "rb").read()).hexdigest()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--xlsx", required=True)
    ap.add_argument("--out-dir", required=True)
    a = ap.parse_args()
    counts = Counter()
    rows, raw_invoices = read_log(a.xlsx, counts)
    rows.sort(key=lambda r: (r[0], r[1], r[2]))

    prices, names = defaultdict(list), defaultdict(Counter)
    for r in rows:
        if not r[8]:
            prices[r[4]].append(r[6])
            names[r[4]][r[7]] += 1
    items = sorted(prices, key=lambda s: (int(s[:5]), s))
    item_id = {s: i + 1 for i, s in enumerate(items)}
    # the median unit price as written to NAME_eui.txt, two decimals; every count below uses this value
    eui = {s: float(f"{statistics.median(prices[s]):.2f}") for s in items}
    cancel_rows = [r for r in rows if r[8]]
    for r in cancel_rows:
        if r[4] not in item_id:
            counts["cancellation lines of a product never bought (dropped)"] += 1
    rows = [r for r in rows if r[4] in item_id]

    init_snaps, ops, final = replay(rows, item_id, counts, True)
    init_nc, ops_nc, _ = replay(rows, item_id, Counter(), False)
    if init_nc != init_snaps:
        raise SystemExit("D_init differs between the two replays")
    hit = mod_by_cancel(rows, item_id, counts)
    mod_cancel = 0
    for kind, day, lst in ops:
        if kind == "MOD" and any(e[3] in hit.get(day, ()) for e in lst):
            mod_cancel += 1

    out = lambda s: f"{a.out_dir}/{NAME}{s}"
    with open(out("_seq.txt"), "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(fmt(s, item_id) for s in init_snaps) + "\n")
    with open(out("_eui.txt"), "w", encoding="utf-8", newline="\n") as f:
        f.write("# itemID:profit, measured (Online Retail II Price, median unit price per StockCode)\n")
        for s in items:
            f.write(f"{item_id[s]}:{eui[s]:.2f}\n")
    write_ops(out("_ops.txt"), [(k, d, [e[:3] if k == "MOD" else e for e in l]) for k, d, l in ops], item_id)
    write_ops(out("_ops_nocancel.txt"), [(k, d, [e[:3] if k == "MOD" else e for e in l]) for k, d, l in ops_nc], item_id)
    fin = sorted(final, key=lambda k: (k[1], int(k[0])))
    with open(out("_final_seq.txt"), "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(fmt(final[k], item_id) for k in fin) + "\n")
    with open(out("_items.tsv"), "w", encoding="utf-8", newline="\n") as f:
        f.write("item\tstock_code\tdescription\tprice\n")
        for s in items:
            f.write(f"{item_id[s]}\t{s}\t{names[s].most_common(1)[0][0]}\t{eui[s]:.2f}\n")

    # End to end: the operations on D_init give the final database, and the no-cancellation log only
    # inserts and modifies.
    replayed = apply_ops(out("_seq.txt"), out("_ops.txt"))
    final_lines = sorted(open(out("_final_seq.txt"), encoding="utf-8").read().splitlines())
    if replayed != final_lines:
        raise SystemExit(f"the operations do not rebuild the final database: {len(replayed)} against "
                         f"{len(final_lines)} sequences")
    if any(k == "DEL" for k, _, _ in ops_nc):
        raise SystemExit("the log without cancellations deletes a sequence")
    apply_ops(out("_seq.txt"), out("_ops_nocancel.txt"))
    kinds = Counter(k for k, _, _ in ops)
    stats = {
        "input_sha256": sha256(a.xlsx),
        "raw_invoices": len(raw_invoices),
        "raw_cancellation_invoices": sum(1 for i in raw_invoices if i.upper().startswith("C")),
        "lines": {k: v for k, v in sorted(counts.items()) if not k.startswith("code:") and not k.startswith("_")},
        "dropped_stock_codes": {k[5:]: v for k, v in counts.most_common() if k.startswith("code:")},
        "items": len(items),
        "d_init": describe(init_snaps, eui),
        "final": describe([final[k] for k in fin], eui),
        "operations": dict(kinds), "days_with_operations": len({d for _, d, _ in ops}),
        "modifications_lowering_a_purchase": mod_cancel,
        "sequences_inserted": sum(len(l) for k, _, l in ops if k == "INS"),
        "sequences_modified": sum(len(l) for k, _, l in ops if k == "MOD"),
        "sequences_deleted": sum(len(l) for k, _, l in ops if k == "DEL"),
        "nocancel_operations": dict(Counter(k for k, _, _ in ops_nc)),
        "end_to_end_check": f"the {len(ops)} operations rebuild the {len(final_lines)} sequences of the final database",
        "files": {},
    }
    for s in ("_seq.txt", "_eui.txt", "_ops.txt", "_ops_nocancel.txt", "_final_seq.txt", "_items.tsv"):
        stats["files"][NAME + s] = sha256(out(s))
    with open(out("_build.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(stats, f, indent=1)
        f.write("\n")
    print(json.dumps(stats, indent=1))


if __name__ == "__main__":
    sys.exit(main())
