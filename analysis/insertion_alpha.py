#!/usr/bin/env python3
"""The share alpha of the inserted utility that one item can reach, from the data files.

    python3 analysis/insertion_alpha.py [--out DIR] [--campaign DIR] [DATABASE ...]

With U+ the utility inserted since the last rescan and A_i the part of it in the sequences
that hold item i, alpha = max_i A_i / U+. Under the joint rule an insertion-only stream never
rescans while alpha <= UUT, and otherwise rescans exactly when
(alpha - UUT) * U+ > (UUT - LUT_min) * TSU_ref.

Everything here is computed from the definitions on the `_seq` and `_eui` files, not read from
the miner: the SC2 stream (D_init, then the rest in 100 equal increments in file order), the
joint rule's decision at every increment, and alpha at that decision. Writes into --out
(default results/invariant-insertion-alpha):

    alpha_stream.csv   per database, alpha over the whole insertion stream, the median alpha at
                       the decisions, and joint_over_tsu = (1 - UUT) / (alpha - UUT): how much
                       more inserted utility the joint rule absorbs before a rescan than a test
                       on the total inserted utility (Hong et al.; Pre-HUSPM-TSU) at that alpha;
                       and the rescans over the 100 increments of the joint rule and of the two
                       Pre-HUSPM tests (buf accumulating TSU_d or SWUmax_d against
                       f = (UUT - LUT_min) / (1 - UUT) * TSU at the last rescan), simulated here
    alpha_batches.csv  per database and increment, alpha and the rescan decision

With --campaign, the SC2 rows of the proposal under that tree are compared with this
computation: alpha against 1 / iau_tightness (iau_tightness is U+ / max_i A_i at the decision)
and the decision against cub_triggered. Exits 1 on any mismatch, and prints how many
increments it compared.
"""
import argparse
import csv
import glob
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BATCHES = 100
REL_TOL = 1e-9


def table():
    """Name -> (D_init, default UUT, r_l), parsed from common/Datasets.java."""
    src = open(os.path.join(ROOT, "src", "common", "Datasets.java"), encoding="utf-8").read()
    out = {}
    for m in re.finditer(r'new Entry\("(\w+)",\s*(\d+),\s*([\d.]+),', src):
        name = m.group(1)
        rest = src[m.end():]
        nxt = rest.find("new Entry(")
        body = rest if nxt < 0 else rest[:nxt]
        rl = re.search(r'true,\s*(0\.\d+)\)', body)
        out[name] = (int(m.group(2)), float(m.group(3)), float(rl.group(1)) if rl else 0.8)
    if not out:
        sys.exit("no database entries found in Datasets.java")
    return out


def load(name, data_dir):
    """Sequences as (distinct items, utility), as common.DatasetLoader reads them."""
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
            itemsets, cur = [], []
            for tok in t.split():
                if tok == "-1":
                    if cur:
                        itemsets.append(cur)
                        cur = []
                elif tok == "-2":
                    if cur:
                        itemsets.append(cur)
                    cur = []
                    break
                else:
                    a, b = tok.find("["), tok.find("]")
                    if a < 0 or b < 0:
                        continue
                    try:
                        i = int(tok[:a])
                        q = float(tok[a + 1:b])
                    except ValueError:
                        continue
                    cur.append((i, q * eui.get(i, 0.0)))
            if cur:
                itemsets.append(cur)
            if not itemsets:
                continue
            u = 0.0                       # summed from the end, as Sequence does
            for its in reversed(itemsets):
                for _, x in reversed(its):
                    u += x
            seqs.append((frozenset(i for its in itemsets for i, _ in its), u))
    return seqs


def even_batches(stream, k=BATCHES):
    """AutomatedExperimentRunner.splitByWeights with equal weights."""
    n = len(stream)
    if n < k:
        sizes = [1] * n + [0] * (k - n)
    else:
        q, r = divmod(n - k, k)
        sizes = [1 + q + (1 if i < r else 0) for i in range(k)]
    out, pos = [], 0
    for s in sizes:
        out.append(stream[pos:pos + s])
        pos += s
    return out


def alpha_of(seqs):
    a, total = {}, 0.0
    for items, u in seqs:
        total += u
        for i in items:
            a[i] = a.get(i, 0.0) + u
    return (max(a.values()) / total if total > 0 else float("nan")), total


def simulate(seqs, d_init, uut, rl):
    init, rest = seqs[:d_init], seqs[d_init:]
    tsu_ref = rolling = sum(u for _, u in init)
    a, a_max, u_plus = {}, 0.0, 0.0
    rows = []
    for b, batch in enumerate(even_batches(rest), start=1):
        bt = 0.0
        for items, u in batch:
            bt += u
            for i in items:
                v = a.get(i, 0.0) + u
                a[i] = v
                if v > a_max:
                    a_max = v
        u_plus += bt
        rolling += bt
        alpha = a_max / u_plus if u_plus > 0 else float("nan")
        rescan = (a_max - uut * u_plus) > (uut - rl * uut) * tsu_ref
        rows.append((b, alpha, rescan))
        if rescan:
            a, a_max, u_plus, tsu_ref = {}, 0.0, 0.0, rolling
    return rows


def simulate_prehuspm(seqs, d_init, uut, rl, variant):
    """Rescans of Pre-HUSPM's test (Yan et al., Algorithm 1) on the SC2 stream."""
    init, rest = seqs[:d_init], seqs[d_init:]
    rolling = sum(u for _, u in init)
    f = (uut - rl * uut) / (1 - uut) * rolling
    buf, n = 0.0, 0
    for batch in even_batches(rest):
        bt = sum(u for _, u in batch)
        rolling += bt
        if variant == "tsu":
            q = bt
        else:
            a = {}
            for items, u in batch:
                for i in items:
                    a[i] = a.get(i, 0.0) + u
            q = max(a.values()) if a else 0.0
        if buf + q > f:
            n += 1
            buf = 0.0
            f = (uut - rl * uut) / (1 - uut) * rolling
        else:
            buf += q
    return n


def campaign_rows(tree, name):
    paths = glob.glob(os.path.join(tree, "SC2", name, "Adaptive-Full", "*.csv"))
    rows = []
    for p in paths:
        for r in csv.DictReader(open(p, encoding="utf-8")):
            r = {k.strip(): v for k, v in r.items()}
            if "_INC" in r.get("op_type", "") and r.get("algorithm") == "Adaptive-Full":
                rows.append(r)
    return rows


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=os.path.join(ROOT, "results", "invariant-insertion-alpha"))
    ap.add_argument("--campaign", default=None, help="a tree holding SC2/<DB>/Adaptive-Full/*.csv")
    ap.add_argument("--data", default=os.path.join(ROOT, "datasets"))
    ap.add_argument("databases", nargs="*")
    a = ap.parse_args()
    tab = table()
    names = a.databases or [n for n in tab if n != "BMS1"]   # BMS1 is not in SC2
    os.makedirs(a.out, exist_ok=True)
    stream_rows, batch_rows = [], []
    compared = mismatched = 0
    for name in names:
        d_init, uut, rl = tab[name]
        seqs = load(name, a.data)
        d_init = max(1, min(d_init, len(seqs)))
        alpha_s, total = alpha_of(seqs[d_init:])
        sim = simulate(seqs, d_init, uut, rl)
        alphas = sorted(al for _, al, _ in sim)
        med = (alphas[len(alphas) // 2] + alphas[(len(alphas) - 1) // 2]) / 2
        ratio = (1 - uut) / (alpha_s - uut) if alpha_s > uut else float("inf")
        sw = simulate_prehuspm(seqs, d_init, uut, rl, "swumax")
        ts = simulate_prehuspm(seqs, d_init, uut, rl, "tsu")
        stream_rows.append([name, len(seqs) - d_init, f"{total:.6f}", repr(alpha_s), repr(med), uut, rl,
                            repr(ratio), sum(r for _, _, r in sim), sw, ts])
        for b, al, rs in sim:
            batch_rows.append([name, b, repr(al), "true" if rs else "false"])
        print(f"{name}: insertion sequences={len(seqs) - d_init} alpha(stream)={alpha_s:.6f} UUT={uut} "
              f"increments={len(sim)} rescans={sum(r for _, _, r in sim)} "
              f"increments with alpha<=UUT={sum(al <= uut for _, al, _ in sim)}")
        if a.campaign:
            got = {int(r["iteration"]): r for r in campaign_rows(a.campaign, name)}
            n = bad = 0
            for b, al, rs in sim:
                r = got.get(b)
                if r is None:
                    continue
                n += 1
                t = float(r["iau_tightness"])
                al_c = 1.0 / t if t > 0 else float("nan")
                ok_a = abs(al_c - al) <= REL_TOL * max(1.0, abs(al))
                ok_r = (r["cub_triggered"].strip().lower() == "true") == rs
                if not (ok_a and ok_r):
                    bad += 1
                    if bad <= 3:
                        print(f"  MISMATCH {name} increment {b}: alpha {al!r} vs 1/iau_tightness {al_c!r}; "
                              f"rescan {rs} vs cub_triggered {r['cub_triggered']}")
            print(f"  compared with {a.campaign}: {n} increments, {bad} mismatching")
            compared += n
            mismatched += bad
    with open(os.path.join(a.out, "alpha_stream.csv"), "w", newline="", encoding="utf-8") as fh:
        w = csv.writer(fh, lineterminator="\n")
        w.writerow(["dataset", "insertion_sequences", "insertion_utility", "alpha_stream",
                    "alpha_median_at_decisions", "uut", "r_l", "joint_over_tsu",
                    "sim_rescans_joint", "sim_rescans_swumax", "sim_rescans_tsu"])
        w.writerows(stream_rows)
    with open(os.path.join(a.out, "alpha_batches.csv"), "w", newline="", encoding="utf-8") as fh:
        w = csv.writer(fh, lineterminator="\n")
        w.writerow(["dataset", "increment", "alpha", "rescan"])
        w.writerows(batch_rows)
    if a.campaign:
        print(f"campaign comparison: {compared} increments, {mismatched} mismatching")
        if compared == 0 or mismatched:
            sys.exit(1)


if __name__ == "__main__":
    main()
