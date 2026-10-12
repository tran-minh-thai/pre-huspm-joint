#!/usr/bin/env python3
"""Root items of the proposal's miner that receive an occurrence list and are stopped at once.

    python3 analysis/root_items.py [--out DIR] [--data DIR]

At the root the proposal builds an occurrence list for every item that the irrelevant item
pruning keeps, and the node bound then stops each item whose summed prefix extension utility
is below the cutoff. Such an item is counted in explored_nodes. The baselines count a node only
when it passes their bound (HUSP-SP and HUSP-ULL: calls of the pattern-growth procedure;
Pre-HUSPM: patterns that pass its bound), so the proposal's count under their convention is
explored_nodes minus the items stopped at the root.

Computed from the definitions on the `_seq` and `_eui` files, for every static configuration
of the campaign: SC1 (D_init, the four thresholds of common.Datasets), SC3 (the whole database,
the same thresholds, SIGN, LEVIATHAN, C8T1S5I8N5K) and SC7 (the whole database at the default
threshold, with the pruning as in proposed_full and proposed_no_IUB, and without it as in
proposed_no_IPS). The cutoff is r_l * UUT * TU(database).

    ips = once:  B(i) = sum of u(s) over the sequences s holding i; items with B(i) < cutoff
                 are removed; remaining utility and PEU then ignore removed items.
    PEU(<i>) = sum over sequences of max over positions q of i of (u_q + utility after q).

Writes root_items.csv into --out (default results/invariant-root-items).
"""
import argparse
import csv
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, "analysis"))

SUBSET_3 = ["SIGN", "LEVIATHAN", "C8T1S5I8N5K"]


def load(name, data_dir):
    """Sequences as lists of (item, utility) in file order, as common.DatasetLoader reads them:
    comment lines and sequences without items are skipped."""
    eui = {}
    with open(os.path.join(data_dir, name + "_eui.txt"), encoding="utf-8") as fh:
        for line in fh:
            t = line.strip()
            if not t or t[0] in "#%":
                continue
            p = t.split(":")
            if len(p) == 2:
                eui[int(p[0])] = float(p[1])
    seqs = []
    with open(os.path.join(data_dir, name + "_seq.txt"), encoding="utf-8") as fh:
        for line in fh:
            t = line.strip()
            if not t or t[0] in "#%@":
                continue
            flat = []
            for tok in t.split():
                a, b = tok.find("["), tok.find("]")
                if a < 0 or b < 0:
                    continue
                i = int(tok[:a])
                flat.append((i, float(tok[a + 1:b]) * eui.get(i, 0.0)))
            if flat:
                seqs.append(flat)
    return seqs


def root_counts(db, cutoff, ips):
    """(items, removed at the root, lists built at the root, lists stopped at once)."""
    items = {i for s in db for i, _ in s}
    removed = set()
    if ips == "once":
        b = {}
        for s in db:
            us = sum(u for _, u in s)
            for i in {i for i, _ in s}:
                b[i] = b.get(i, 0.0) + us
        removed = {i for i, v in b.items() if v < cutoff}
    peu = {}
    for s in db:
        ru, best = 0.0, {}
        for i, u in reversed(s):
            if i in removed:
                continue
            v = u + ru
            if v > best.get(i, 0.0):
                best[i] = v
            ru += u
        for i, v in best.items():
            peu[i] = peu.get(i, 0.0) + v
    kept = items - removed
    stopped = sum(1 for i in kept if peu.get(i, 0.0) < cutoff)
    return len(items), len(removed), len(kept), stopped


def sweeps():
    """name -> (d_init, default UUT, r_l, sweep) from common.Datasets, via make_figures' parser."""
    import make_figures as mf
    return {n: (v["d_init"], v["default_uut"], v["sl_ratio"], v["sweep"]) for n, v in mf.DATASETS.items()}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=os.path.join(ROOT, "results", "invariant-root-items"))
    ap.add_argument("--data", default=os.path.join(ROOT, "datasets"))
    a = ap.parse_args()
    tab = sweeps()
    rows = []
    for name, (d_init, uut, rl, sweep) in tab.items():
        seqs = load(name, a.data)
        cells = [("SC1", "D_init", seqs[:d_init], su, "once") for su in sweep]
        if name in SUBSET_3:
            cells += [("SC3", "whole", seqs, su, "once") for su in sweep]
        if name != "BMS1":
            cells += [("SC7", "whole", seqs, uut, "once"), ("SC7", "whole", seqs, uut, "off")]
        for sc, scope, db, su, ips in cells:
            tu = sum(u for s in db for _, u in s)
            cutoff = rl * su * tu
            n_items, n_removed, n_lists, n_stopped = root_counts(db, cutoff, ips)
            rows.append(dict(scenario=sc, dataset=name, scope=scope, sequences=len(db), su=f"{su:g}",
                             ips=ips, cutoff=repr(cutoff), items=n_items, removed_at_root=n_removed,
                             root_lists=n_lists, root_stopped=n_stopped))
            print(f"{sc} {name:12s} {scope:6s} su={su:g} ips={ips:4s} items={n_items} removed={n_removed} "
                  f"lists={n_lists} stopped={n_stopped}")
    os.makedirs(a.out, exist_ok=True)
    path = os.path.join(a.out, "root_items.csv")
    with open(path, "w", encoding="utf-8", newline="") as fh:
        w = csv.DictWriter(fh, fieldnames=list(rows[0]), lineterminator="\n")
        w.writeheader()
        w.writerows(rows)
    print(f"{len(rows)} configurations -> {os.path.relpath(path, ROOT)}")


if __name__ == "__main__":
    main()
