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


def joint_decisions(init_total, batches, uut, rl):
    """The joint rule on an insertion-only stream: (increment, alpha, rescan) per batch."""
    tsu_ref = rolling = init_total
    a, a_max, u_plus = {}, 0.0, 0.0
    rows = []
    for b, batch in enumerate(batches, start=1):
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


def floor_selection(init_total, batches, uut, rl, w):
    """The joint rule with the rescan floor selection of the proposal (PreHUSPMAdaptiveAlgo
    floorWindow = w) on an insertion-only stream: a rescan mines at UUT (floor ratio 1, budget 0)
    when the last w batches each charged more than the low budget (1 - r_l) * UUT * TU on their own,
    phi = max_i (utility of the batch's sequences holding i) - UUT * (the batch's utility), TU the
    total after the batch; at r_l otherwise. (increment, rescan, floor ratio in force after it)."""
    tsu_ref = rolling = init_total
    floor = rl
    a, a_max, u_plus, run = {}, 0.0, 0.0, 0
    rows = []
    for b, batch in enumerate(batches, start=1):
        bt, ab = 0.0, {}
        for items, u in batch:
            bt += u
            for i in items:
                ab[i] = ab.get(i, 0.0) + u
                v = a.get(i, 0.0) + u
                a[i] = v
                if v > a_max:
                    a_max = v
        u_plus += bt
        rolling += bt
        phi = (max(ab.values()) if ab else 0.0) - uut * bt
        run = run + 1 if phi > (uut - rl * uut) * rolling else 0
        rescan = (a_max - uut * u_plus) > (uut - floor * uut) * tsu_ref
        if rescan:
            a, a_max, u_plus, tsu_ref = {}, 0.0, 0.0, rolling
            floor = 1.0 if run >= w else rl
        rows.append((b, rescan, floor))
    return rows


def prehuspm_rescans(init_total, batches, uut, rl, variant):
    """Increments at which Pre-HUSPM's test rescans (Yan et al., Algorithm 1): buf + q > f with
    q = TSU_d or SWUmax_d = max_i (utility of the sequences of d holding i), buf reset to 0 and
    f = (UUT - LUT_min) / (1 - UUT) * TU at every rescan."""
    rolling = init_total
    f = (uut - rl * uut) / (1 - uut) * rolling
    buf, out = 0.0, []
    for b, batch in enumerate(batches, start=1):
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
            out.append(b)
            buf = 0.0
            f = (uut - rl * uut) / (1 - uut) * rolling
        else:
            buf += q
    return out


def rescan_order(joint, swumax):
    """(comparisons, violations) of: the k-th rescan of the joint rule is not earlier than the
    k-th rescan of the SWUmax test, for every k, and the joint rule has no more rescans."""
    n = min(len(joint), len(swumax))
    bad = sum(1 for k in range(n) if joint[k] < swumax[k])
    bad += 1 if len(joint) > len(swumax) else 0
    return n + 1, bad


def simulate(seqs, d_init, uut, rl):
    init, rest = seqs[:d_init], seqs[d_init:]
    return joint_decisions(sum(u for _, u in init), even_batches(rest), uut, rl)


def simulate_prehuspm(seqs, d_init, uut, rl, variant):
    """Rescans of Pre-HUSPM's test on the SC2 stream."""
    init, rest = seqs[:d_init], seqs[d_init:]
    return len(prehuspm_rescans(sum(u for _, u in init), even_batches(rest), uut, rl, variant))


def random_order_check(trials, seed):
    """rescan_order on random insertion-only streams: varying item alphabets (so alpha varies
    from near UUT to 1), batch counts and sizes, thresholds and floor ratios."""
    import random
    r = random.Random(seed)
    comps = bad = trials_with_rescans = 0
    first = None
    for t in range(trials):
        n_items = r.choice([2, 3, 5, 10, 50, 500])
        uut = r.choice([0.001, 0.01, 0.05, 0.1, 0.2])
        rl = r.choice([0.3, 0.5, 0.8, 0.9])
        def seq():
            k = r.randint(1, min(8, n_items))
            return frozenset(r.sample(range(n_items), k)), float(r.randint(1, 100))
        init_total = sum(seq()[1] for _ in range(r.randint(1, 50)))
        batches = [[seq() for _ in range(r.randint(0, 6))] for _ in range(r.randint(1, 60))]
        j = [b for b, _, rs in joint_decisions(init_total, batches, uut, rl) if rs]
        s = prehuspm_rescans(init_total, batches, uut, rl, "swumax")
        c, v = rescan_order(j, s)
        comps += c
        bad += v
        if j and s:
            trials_with_rescans += 1
        if v and first is None:
            first = f"trial {t}: joint {j[:8]} swumax {s[:8]} UUT={uut} r_l={rl}"
    return trials, comps, bad, trials_with_rescans, first


def campaign_rows(tree, name, arm="Adaptive-Full"):
    paths = glob.glob(os.path.join(tree, "SC2", name, arm, "*.csv"))
    rows = []
    for p in paths:
        for r in csv.DictReader(open(p, encoding="utf-8")):
            r = {k.strip(): v for k, v in r.items()}
            if "_INC" in r.get("op_type", "") and r.get("algorithm") == arm:
                rows.append(r)
    return rows


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=os.path.join(ROOT, "results", "invariant-insertion-alpha"))
    ap.add_argument("--campaign", default=None, help="a tree holding SC2/<DB>/Adaptive-Full/*.csv")
    ap.add_argument("--data", default=os.path.join(ROOT, "datasets"))
    ap.add_argument("--random-order", nargs=2, metavar=("TRIALS", "SEED"),
                    help="check the rescan order of the joint rule and the SWUmax test on random streams")
    ap.add_argument("--floor-windows", type=int, nargs="+", default=None, metavar="W",
                    help="also simulate the rescan floor selection for each window W; writes "
                         "floor_selection.csv and floor_selection_batches.csv")
    ap.add_argument("--floor-campaign-window", type=int, default=5,
                    help="the window of arm Adaptive-FloorSelect, compared with --campaign")
    ap.add_argument("databases", nargs="*")
    a = ap.parse_args()
    floor_rows, floor_batch_rows = [], []
    tab = table()
    names = a.databases or [n for n in tab if n != "BMS1"]   # BMS1 is not in SC2
    os.makedirs(a.out, exist_ok=True)
    stream_rows, batch_rows = [], []
    compared = mismatched = 0
    order_comparisons = order_violations = 0
    if a.random_order:
        tr, sd = a.random_order
        n, c, v, w, first = random_order_check(int(tr), int(sd))
        print(f"random streams: {n} trials, {w} with rescans under both tests, {c} comparisons "
              f"(k-th rescans and totals), {v} with the joint rule's rescan earlier")
        if first:
            print("  first: " + first)
        sys.exit(1 if v or w == 0 else 0)
    for name in names:
        d_init, uut, rl = tab[name]
        seqs = load(name, a.data)
        d_init = max(1, min(d_init, len(seqs)))
        alpha_s, total = alpha_of(seqs[d_init:])
        sim = simulate(seqs, d_init, uut, rl)
        alphas = sorted(al for _, al, _ in sim)
        med = (alphas[len(alphas) // 2] + alphas[(len(alphas) - 1) // 2]) / 2
        ratio = (1 - uut) / (alpha_s - uut) if alpha_s > uut else float("inf")
        sw_list = prehuspm_rescans(sum(u for _, u in seqs[:d_init]), even_batches(seqs[d_init:]), uut, rl, "swumax")
        sw = len(sw_list)
        oc, ov = rescan_order([b for b, _, rs in sim if rs], sw_list)
        order_comparisons += oc
        order_violations += ov
        ts = simulate_prehuspm(seqs, d_init, uut, rl, "tsu")
        stream_rows.append([name, len(seqs) - d_init, f"{total:.6f}", repr(alpha_s), repr(med), uut, rl,
                            repr(ratio), sum(r for _, _, r in sim), sw, ts])
        for b, al, rs in sim:
            batch_rows.append([name, b, repr(al), "true" if rs else "false"])
        print(f"{name}: insertion sequences={len(seqs) - d_init} alpha(stream)={alpha_s:.6f} UUT={uut} "
              f"increments={len(sim)} rescans={sum(r for _, _, r in sim)} "
              f"increments with alpha<=UUT={sum(al <= uut for _, al, _ in sim)}")
        if a.floor_windows:
            it = sum(u for _, u in seqs[:d_init])
            bt = even_batches(seqs[d_init:])
            for w in a.floor_windows:
                fs = floor_selection(it, bt, uut, rl, w)
                resc = [f for _, rs, f in fs if rs]
                floor_rows.append([name, w, rl, sum(r for _, _, r in sim), len(resc), sum(1 for f in resc if f == 1.0)])
                for b, rs, f in fs:
                    floor_batch_rows.append([name, w, b, "true" if rs else "false", f])
                print(f"  floor selection w={w}: rescans {len(resc)} ({sum(1 for f in resc if f == 1.0)} at UUT), "
                      f"joint rule {sum(r for _, _, r in sim)}")
            if a.campaign:
                fs = {b: (rs, f) for b, rs, f in floor_selection(it, bt, uut, rl, a.floor_campaign_window)}
                got = {int(r["iteration"]): r for r in campaign_rows(a.campaign, name, "Adaptive-FloorSelect")}
                n = bad = 0
                for b, (rs, f) in fs.items():
                    r = got.get(b)
                    if r is None:
                        continue
                    n += 1
                    ok = ((r["cub_triggered"].strip().lower() == "true") == rs
                          and abs(float(r["sl_min_eff"]) - f) <= 1e-9)
                    if not ok:
                        bad += 1
                        if bad <= 3:
                            print(f"  MISMATCH {name} increment {b}: rescan {rs} floor {f} vs "
                                  f"cub_triggered {r['cub_triggered']} sl_min_eff {r['sl_min_eff']}")
                print(f"  floor selection compared with {a.campaign}: {n} increments, {bad} mismatching")
                compared += n
                mismatched += bad
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
    if a.floor_windows:
        with open(os.path.join(a.out, "floor_selection.csv"), "w", newline="", encoding="utf-8") as fh:
            w = csv.writer(fh, lineterminator="\n")
            w.writerow(["dataset", "window", "r_l", "rescans_joint", "rescans_selection", "rescans_at_uut"])
            w.writerows(floor_rows)
        with open(os.path.join(a.out, "floor_selection_batches.csv"), "w", newline="", encoding="utf-8") as fh:
            w = csv.writer(fh, lineterminator="\n")
            w.writerow(["dataset", "window", "increment", "rescan", "floor_ratio"])
            w.writerows(floor_batch_rows)
    print(f"rescan order (k-th rescan of the joint rule not earlier than that of the SWUmax test, and no "
          f"more rescans): {order_comparisons} comparisons, {order_violations} violations")
    if order_violations:
        sys.exit(1)
    if a.campaign:
        print(f"campaign comparison: {compared} increments, {mismatched} mismatching")
        if compared == 0 or mismatched:
            sys.exit(1)


if __name__ == "__main__":
    main()
