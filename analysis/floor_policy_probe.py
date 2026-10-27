#!/usr/bin/env python3
"""Probe: would choosing the rescan floor by cost bring the proposal down to remining at the threshold?

    python3 analysis/floor_policy_probe.py --out results-probe/floor-policy/<date> [--sc5] [--sc6] [--db NAME ...]

The joint rule decides from the data alone (A_i, the inserted and deleted utility since the last
rescan, TSU at the last rescan, the floor), never from what a mine returns. The rescan decisions of
any floor policy can therefore be replayed without mining, one phase at a time: an insertion, a
deletion, or the two phases of a modification (its deletion, then its insertion, each with its own
decision, as PreHUSPMAdaptiveAlgo.runModification makes them).

Streams: the seven SC2 streams; with --sc5 the 48 SC5 cells, whose increment sizes are read from the
runner's log; with --sc6 the 60 streams of SC6, SC6D and SC6B (four databases, five seeds), whose
operations are read from a plan written by verify.MixedPlanDump (--plans).

Before pricing a stream the replay must reproduce what was measured: for the proposal (rescan floor
selection, window 5) and for the fixed lower threshold, the rescans of every operation and how many of
them mined at the threshold. A stream that disagrees is reported and not priced.

Costs, measured on the measurement machine (results/campaign and results/campaign-replicates, mean over
the runs of the stream, per operation):

    a rescan at the lower threshold   the time of Remine-from-scratch at that operation
    a rescan at the threshold         the time of Remine-own-UUT at that operation
    a direct phase                    the median time of the fixed-threshold arm's operations of that
                                      kind that made no rescan

Policies priced: the fixed lower threshold, window 5, remining at the threshold after every operation,
the break-even rule, and the offline optimum over floor choices (a lower bound no online rule beats).

Break-even rule: a rescan at the lower threshold costs rho times one at the threshold and is followed
by a phases taken directly at delta each (delta = a direct phase over a rescan at the threshold), so it
is the cheaper per phase iff a >= (rho - 1) / (1 - delta). rho is the ratio of the last costs observed
at the two floors (the initial mine is at the lower threshold, the first rescan is made at the
threshold, so both are known from there on); a is the mean absorption of the last five cycles. While
the stream runs at the threshold, a shadow replay at the lower floor, restarted after each of its own
rescans, records how many phases a rescan at the lower threshold would have absorbed: exact, since the
decisions depend on the data alone.

The totals are sums of times from different runs, so they are estimates for deciding what to build,
not measurements; the script also prices the measured policies so the size of that error is shown.
"""
import argparse
import csv
import pathlib
import re
import statistics
import sys
from collections import defaultdict

ROOT = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "analysis"))
import make_figures as mf  # noqa: E402

DBS = ["SIGN", "TAFENG", "C8T1S5I8N5K", "BIBLE", "LEVIATHAN", "FIFA", "KOSARAK"]
K = 100


def load(name):
    """Per sequence: its utility and its set of items, read as common.DatasetLoader reads them."""
    profit = {}
    for line in open(ROOT / "datasets" / f"{name}_eui.txt"):
        if line.startswith("#") or not line.strip():
            continue
        i, p = line.strip().split(":")
        profit[int(i)] = float(p)
    seqs = []
    for line in open(ROOT / "datasets" / f"{name}_seq.txt"):
        if line.startswith("#") or not line.strip():
            continue
        u, items = 0.0, set()
        for tok in line.split():
            if "[" in tok:
                i, q = tok[:-1].split("[")
                i = int(i)
                u += int(q) * profit.get(i, 0.0)
                items.add(i)
        if items:                      # the loader skips sequences without an item
            seqs.append((u, items))
    return seqs


def even_batches(rest, k):
    n = len(rest)
    m = n - k
    sizes = [m // k + 1] * k
    for i in range(m - (m // k) * k):
        sizes[i] += 1
    out, idx = [], 0
    for s in sizes:
        out.append(rest[idx:idx + s])
        idx += s
    return out


class Replay:
    """The joint rule with tracking by utility and the floor selection's counter, as
    PreHUSPMAdaptiveAlgo runs them. A phase is (+1, sequences) for an insertion, (-1, sequences)
    for a deletion."""

    def __init__(self, su, rl, init_total):
        self.su, self.rl = su, rl
        self.tsu_ref = init_total
        self.floor = rl * su           # the initial mine is at the lower threshold
        self.iau, self.iau_max = {}, 0.0
        self.inserted = self.deleted = 0.0
        self.total = init_total
        self.over_low_run = 0

    def step(self, phase):
        sign, seqs = phase
        bt = sum(u for u, _ in seqs)
        if sign > 0:
            swu = {}
            for u, items in seqs:
                for i in items:
                    swu[i] = swu.get(i, 0.0) + u
                    v = self.iau.get(i, 0.0) + u
                    self.iau[i] = v
                    if v > self.iau_max:
                        self.iau_max = v
            self.total += bt
            self.inserted += bt
            own = (max(swu.values()) if swu else 0.0) - self.su * bt
        else:
            self.total -= bt
            self.deleted += bt
            own = self.su * bt
        self.over_low_run = self.over_low_run + 1 if own > (self.su - self.rl * self.su) * self.total else 0
        phi = self.iau_max - self.su * self.inserted + self.su * self.deleted
        return phi > (self.su - self.floor) * self.tsu_ref

    def rescan(self, at_threshold):
        self.iau, self.iau_max = {}, 0.0
        self.inserted = self.deleted = 0.0
        self.tsu_ref = self.total
        self.floor = self.su if at_threshold else self.rl * self.su


class Stream:
    """One measured stream: D_init, its phases with the operation each belongs to and its kind, and
    the rows measured on it (operations after the initial mine)."""

    def __init__(self, label, db, init, phases, op_of, kind_of, rows, n_ops):
        self.label, self.db, self.init, self.phases = label, db, init, phases
        self.op_of, self.kind_of, self.rows, self.n_ops = op_of, kind_of, rows, n_ops


def units_of(stream, merge):
    """Decision units: one per phase, or, with merge, the two phases of a modification as one unit
    that rescans once, after its insertion phase, when either phase triggers."""
    out, p = [], 0
    while p < len(stream.phases):
        if merge and stream.kind_of[p] == "MOD" and p + 1 < len(stream.phases) and stream.op_of[p + 1] == stream.op_of[p]:
            out.append([p, p + 1])
            p += 2
        else:
            out.append([p])
            p += 1
    return out


def run(stream, su, rl, choose, merge=False):
    """Replays a policy; choose(u, path, replay) -> True to rescan at the threshold. Returns per
    unit 'D' (direct), 'L' or 'U' (rescan at the lower threshold or at the threshold)."""
    r = Replay(su, rl, sum(u for u, _ in stream.init))
    path = []
    for u, unit in enumerate(units_of(stream, merge)):
        trig = False
        for p in unit:
            trig = r.step(stream.phases[p]) or trig
        if not trig:
            path.append("D")
            continue
        up = choose(u, path, r)
        r.rescan(up)
        path.append("U" if up else "L")
    return path


def per_op(path, stream, merge=False):
    """(rescans, rescans at the threshold) per operation, from a unit path."""
    out = [[0, 0] for _ in range(stream.n_ops)]
    for unit, x in zip(units_of(stream, merge), path):
        if x != "D":
            out[stream.op_of[unit[0]] - 1][0] += 1
            out[stream.op_of[unit[0]] - 1][1] += x == "U"
    return [tuple(v) for v in out]


def measured_per_op(rows):
    """(rescans, rescans at the threshold) per operation of the first run, from the cumulative
    counters of the rows."""
    rows = rows[rows["replicate"] == sorted(rows["replicate"].unique())[0]].sort_values("iteration")
    out, prev_r, prev_u = [], 0, 0
    for _, x in rows.iterrows():
        rc = int(x["rescan_count"])
        ru = int(x["rescans_at_uut"]) if x["rescans_at_uut"] == x["rescans_at_uut"] else 0
        out.append((rc - prev_r, ru - prev_u))
        prev_r, prev_u = rc, ru
    return out


def evaluate(stream, su, rl):
    rows_of = lambda arm: stream.rows[stream.rows["algorithm"] == arm]
    m_w5, m_fx = measured_per_op(rows_of(mf.PROPOSED)), measured_per_op(rows_of(mf.FIXED))
    window5 = lambda u, path, r: r.over_low_run >= 5
    fixed = lambda u, path, r: False
    w5, fx = run(stream, su, rl, window5), run(stream, su, rl, fixed)
    agree_w5 = sum(a == b for a, b in zip(per_op(w5, stream), m_w5))
    agree_fx = sum(a == b for a, b in zip(per_op(fx, stream), m_fx))
    row = dict(stream=stream.label, dataset=stream.db, operations=stream.n_ops,
               replay_window5=f"{agree_w5}/{len(m_w5)}", replay_fixed=f"{agree_fx}/{len(m_fx)}")
    print(f"{stream.label}: replay agrees on {agree_w5}/{len(m_w5)} operations (window 5) and "
          f"{agree_fx}/{len(m_fx)} (fixed lower threshold)")
    if not (agree_w5 == len(m_w5) == stream.n_ops and agree_fx == len(m_fx) == stream.n_ops):
        return row

    def mean_per_op(arm):
        return {int(i): g["runtime_ms"].mean() / 1000 for i, g in rows_of(arm).groupby("iteration")}
    cL, cU = mean_per_op("Remine-from-scratch"), mean_per_op("Remine-own-UUT")
    nodes = lambda arm: {int(i): g["explored_nodes"].mean() for i, g in rows_of(arm).groupby("iteration")}
    nL, nU = nodes("Remine-from-scratch"), nodes("Remine-own-UUT")
    fxr = rows_of(mf.FIXED).sort_values(["replicate", "iteration"])
    fxr = fxr.assign(rescanned=fxr.groupby("replicate")["rescan_count"].diff().fillna(fxr["rescan_count"]) > 0)
    direct = fxr[~fxr["rescanned"]]
    kinds = direct["op_type"].astype(str).str.extract(r"_(INS|DEL|MOD|INC)$")[0].replace({"INC": "INS"})
    cD_kind = {k: float(direct[kinds == k]["runtime_ms"].median()) / 1000 for k in ("INS", "DEL", "MOD") if (kinds == k).any()}
    cD_any = float(direct["runtime_ms"].median()) / 1000 if len(direct) else 0.0
    # a direct phase; a modification taken directly in both phases costs the median of such rows,
    # split over its two phases
    cD_phase = lambda p: (cD_kind.get("MOD", cD_any) / 2 if stream.kind_of[p] == "MOD" else cD_kind.get(stream.kind_of[p], cD_any))

    def price(path, merge):
        units = units_of(stream, merge)
        return sum(sum(cD_phase(p) for p in unit) if x == "D" else (cU if x == "U" else cL)[stream.op_of[unit[0]]]
                   for unit, x in zip(units, path))

    def break_even(merge, window=5, dL=None, dU=None):
        units = units_of(stream, merge)
        r = Replay(su, rl, sum(u for u, _ in stream.init))
        shadow, shadow_n = None, 0
        absorbed, path = [], []
        xL, xU = (cL, cU) if dL is None else (dL, dU)
        last_L, last_U = xL[1], None
        for u, unit in enumerate(units):
            trig = False
            for p in unit:
                trig = r.step(stream.phases[p]) or trig
            if shadow is not None:
                s_trig = False
                for p in unit:
                    s_trig = shadow.step(stream.phases[p]) or s_trig
                if s_trig:
                    absorbed.append(shadow_n)
                    shadow = None
                else:
                    shadow_n += 1
            if not trig:
                path.append("D")
                continue
            k = len(path) - 1
            while k >= 0 and path[k] == "D":
                k -= 1
            if k >= 0 and path[k] == "L":
                absorbed.append(len(path) - 1 - k)
            if last_U is None:
                up = True
            else:
                d = sum(cD_phase(p) for p in unit) if dL is None else 0.0
                rho, delta = last_L / last_U, d / last_U
                a_hat = statistics.mean(absorbed[-window:]) if absorbed else 1.0
                up = a_hat < (rho - 1) / max(1e-9, 1 - delta)
            r.rescan(up)
            o = stream.op_of[unit[0]]
            if up:
                last_U = xU[o]
                if shadow is None:
                    shadow, shadow_n = Replay(su, rl, r.total), 0
            else:
                last_L = xL[o]
            path.append("U" if up else "L")
        return path

    def window_rule(merge, W):
        """Lower threshold at a rescan iff, over the last W units, the stream at the fixed lower
        threshold (replayed alongside, from the data alone) rescanned at a rate r with
        r * rho + (1 - r) * delta < 1, i.e. it cost less per unit than rescanning every unit at the
        threshold; rho and delta as in the break-even rule. The first rescan is at the threshold."""
        units = units_of(stream, merge)
        r = Replay(su, rl, sum(u for u, _ in stream.init))
        lo = Replay(su, rl, sum(u for u, _ in stream.init))   # the fixed lower threshold, alongside
        lo_hist, path = [], []
        last_L, last_U = cL[1], None
        for u, unit in enumerate(units):
            trig = lo_trig = False
            for p in unit:
                trig = r.step(stream.phases[p]) or trig
                lo_trig = lo.step(stream.phases[p]) or lo_trig
            if lo_trig:
                lo.rescan(False)
            lo_hist.append(lo_trig)
            if not trig:
                path.append("D")
                continue
            if last_U is None:
                up = True
            else:
                recent = lo_hist[-W:]
                rate = sum(recent) / len(recent)
                d = sum(cD_phase(p) for p in unit)
                rho, delta = last_L / last_U, d / last_U
                up = rate * rho + (1 - rate) * delta >= 1
            r.rescan(up)
            o = stream.op_of[unit[0]]
            if up:
                last_U = cU[o]
            else:
                last_L = cL[o]
            path.append("U" if up else "L")
        return path

    def optimum(merge):
        """Offline optimum: right after a rescan the counters are zero, so the next rescan depends
        only on the unit of that rescan and its floor; a dynamic programme over (unit, floor)."""
        units = units_of(stream, merge)
        totals = [sum(u for u, _ in stream.init)]
        for unit in units:
            totals.append(totals[-1] + sum(stream.phases[p][0] * sum(u for u, _ in stream.phases[p][1]) for p in unit))
        n = len(units)
        nxt, memo = {}, {}

        def forward(u0, up):
            if (u0, up) not in nxt:
                r = Replay(su, rl, totals[u0 + 1])
                r.floor = su if up else rl * su
                u, cost = u0 + 1, 0.0
                while u < n:
                    trig = False
                    for p in units[u]:
                        trig = r.step(stream.phases[p]) or trig
                    if trig:
                        break
                    cost += sum(cD_phase(p) for p in units[u])
                    u += 1
                nxt[(u0, up)] = (u, cost)
            return nxt[(u0, up)]

        def V(u0, up):
            if (u0, up) not in memo:
                u, cost = forward(u0, up)
                if u < n:
                    o = stream.op_of[units[u][0]]
                    cost += min(cL[o] + V(u, False), cU[o] + V(u, True))
                memo[(u0, up)] = cost
            return memo[(u0, up)]
        sys.setrecursionlimit(20000)
        return V(-1, False)

    total = lambda arm: rows_of(arm).groupby("replicate")["runtime_ms"].sum().mean() / 1000
    row.update(measured_proposal_s=round(total(mf.PROPOSED), 3), priced_window5_s=round(price(w5, False), 3),
               measured_fixed_s=round(total(mf.FIXED), 3), priced_fixed_s=round(price(fx, False), 3),
               measured_remine_s=round(total("Remine-from-scratch"), 3),
               measured_remine_uut_s=round(total("Remine-own-UUT"), 3),
               priced_always_threshold_s=round(price(run(stream, su, rl, lambda u, path, r: True), False), 3),
               priced_break_even_s=round(price(break_even(False), False), 3),
               priced_break_even_nodes_s=round(price(break_even(False, dL=nL, dU=nU), False), 3),
               priced_offline_optimum_s=round(optimum(False), 3),
               priced_window_rule10_s=round(price(window_rule(False, 10), False), 3),
               priced_window_rule20_s=round(price(window_rule(False, 20), False), 3))
    if any(k == "MOD" for k in stream.kind_of):
        # a modification mines once, after its insertion phase, when either phase triggers
        row.update(priced_window5_merged_s=round(price(run(stream, su, rl, window5, True), True), 3),
                   priced_always_threshold_merged_s=round(price(run(stream, su, rl, lambda u, path, r: True, True), True), 3),
                   priced_break_even_merged_s=round(price(break_even(True), True), 3),
                   priced_break_even_nodes_merged_s=round(price(break_even(True, dL=nL, dU=nU), True), 3),
                   priced_offline_optimum_merged_s=round(optimum(True), 3),
                   priced_window_rule10_merged_s=round(price(window_rule(True, 10), True), 3),
                   priced_window_rule20_merged_s=round(price(window_rule(True, 20), True), 3))
    print("  " + ", ".join(f"{k} {v}" for k, v in row.items() if k.startswith(("measured", "priced"))))
    return row


def streams(a):
    sc2 = mf.load_scenario("SC2", replicates=True)
    cache = {}
    def seqs_of(db):
        if db not in cache:
            cache[db] = load(db)
        return cache[db]

    def insert_stream(label, db, rows, init, batches):
        batches = [b for b in batches if b]
        return Stream(label, db, init, [(+1, b) for b in batches], list(range(1, len(batches) + 1)),
                      ["INS"] * len(batches), rows, len(batches))

    for db in a.db:
        seqs, n_init = seqs_of(db), mf.DATASETS[db]["d_init"]
        rows = sc2[(sc2["dataset"] == db) & (sc2["iteration"] > 0)]
        yield insert_stream(db, db, rows, seqs[:n_init], even_batches(seqs[n_init:], K))
    if a.sc5:
        sc5 = mf.load_scenario("SC5", replicates=True)
        for db in [d for d in a.db if d in set(sc5["dataset"])]:
            seqs, n_init = seqs_of(db), mf.DATASETS[db]["d_init"]
            rest = seqs[n_init:]
            log = (ROOT / "results" / "campaign" / "SC5" / db / mf.PROPOSED / "run.log").read_text(errors="replace")
            for m in re.finditer(r">> \[SC5\] " + re.escape(db) + r" (SC5_K=\d+_strat=\w+) seed=\d+ -> sizes=\[([0-9, ]+)\]", log):
                tag, sizes = m.group(1), [int(x) for x in m.group(2).split(",")]
                assert sum(sizes) == len(rest), (db, tag)
                batches, idx = [], 0
                for z in sizes:
                    batches.append(rest[idx:idx + z])
                    idx += z
                rows = sc5[(sc5["dataset"] == db) & (sc5["iteration"] > 0)
                           & sc5["op_type"].astype(str).str.startswith(tag + "_")]
                yield insert_stream(f"{db} {tag}", db, rows, seqs[:n_init], batches)
    if a.sc6:
        plans = defaultdict(list)
        for r in csv.DictReader(open(ROOT / a.plans), delimiter="\t"):
            plans[(r["scenario"], r["dataset"], int(r["seed"]))].append(r)
        for sc in ("SC6", "SC6D", "SC6B"):
            df = mf.load_scenario(sc, replicates=True)
            for (scn, db, seed), ops in sorted(plans.items()):
                if scn != sc or db not in a.db:
                    continue
                seqs = seqs_of(db)
                n_init = int(ops[0]["d_init"])
                idx = lambda s: [seqs[int(x)] for x in s.split()] if s.strip() else []
                phases, op_of, kind_of = [], [], []
                for o in ops:
                    it = int(o["iter"])
                    if o["kind"] == "INS":
                        phases.append((+1, idx(o["a"]))); op_of.append(it); kind_of.append("INS")
                    elif o["kind"] == "DEL":
                        phases.append((-1, idx(o["a"]))); op_of.append(it); kind_of.append("DEL")
                    else:
                        phases.append((-1, idx(o["a"]))); op_of.append(it); kind_of.append("MOD")
                        phases.append((+1, idx(o["b"]))); op_of.append(it); kind_of.append("MOD")
                rows = df[(df["dataset"] == db) & (df["iteration"] > 0) & (df["seed"] == seed)]
                yield Stream(f"{sc} {db} seed {seed}", db, seqs[:n_init], phases, op_of, kind_of, rows, len(ops))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    ap.add_argument("--db", nargs="*", default=DBS)
    ap.add_argument("--sc5", action="store_true", help="also the 48 cells of SC5")
    ap.add_argument("--sc6", action="store_true", help="also SC6, SC6D and SC6B (needs --plans)")
    ap.add_argument("--plans", default="results-probe/floor-policy/mixed-plans/mixed_plans.tsv",
                    help="plans written by verify.MixedPlanDump, relative to the repository")
    a = ap.parse_args()
    out = ROOT / a.out
    out.mkdir(parents=True, exist_ok=True)
    summary = [evaluate(s, mf.DATASETS[s.db]["default_uut"], 0.8) for s in streams(a)]
    keys = list(dict.fromkeys(k for r in summary for k in r))
    with open(out / "floor_policy.csv", "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=keys, lineterminator="\n")
        w.writeheader()
        w.writerows(summary)
    bad = [r["stream"] for r in summary if "priced_break_even_s" not in r]
    print(f"streams: {len(summary)}; replayed and priced: {len(summary) - len(bad)}; not replayed: {bad}")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
