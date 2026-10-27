#!/usr/bin/env python3
"""Probe: would choosing the rescan floor by cost bring the proposal down to remining at the threshold?

    python3 analysis/floor_policy_probe.py --out results-probe/floor-policy/<date> [--db NAME ...]

The joint rule decides from the data alone (A_i, the inserted utility, TSU at the last rescan, the
floor), never from what a mine returns, so the rescan decisions of any floor policy on an SC2 stream
can be replayed without mining. This script replays them and prices them with costs measured on the
measurement machine in results/campaign and results/campaign-replicates (mean of the five runs, per
increment):

    a rescan at the lower threshold   the time of Remine-from-scratch at that increment
    a rescan at the threshold         the time of Remine-own-UUT at that increment
    a direct update                   the median time of the direct updates of the fixed-threshold arm

It first checks the replay: the policy the proposal ran (window 5) and the fixed lower threshold must
reproduce, increment by increment, the rescans and the rescans at the threshold that were measured.
A database whose replay disagrees is reported and not priced.

Policies priced: the fixed lower threshold, window 5 (the proposal), remining at the threshold after
every operation, two cost rules, the break-even rule, and the offline optimum over floor choices (a
lower bound no online rule can beat). The cost rule chooses the lower threshold at a rescan only when the mean time per
operation of a rescan at the lower threshold, spread over the operations it absorbed on average after
earlier such rescans, is below the time of a rescan at the threshold; costs are the last observed
ones at each floor, and one rescan at the threshold is tried at the first rescan so both are known.

The totals are sums of times from different runs, so they are estimates for deciding what to build,
not measurements; the script also prices the measured policies so the size of that error is shown.
"""
import argparse
import csv
import os
import pathlib
import statistics
import sys

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
    """The joint rule with tracking by utility, on insertions, as PreHUSPMAdaptiveAlgo runs it."""

    def __init__(self, su, rl, init_total):
        self.su, self.rl = su, rl
        self.tsu_ref = init_total
        self.floor = rl * su           # the initial mine is at the lower threshold
        self.iau = {}
        self.iau_max = 0.0
        self.inserted = 0.0
        self.total = init_total
        self.over_low_run = 0

    def step(self, batch):
        """Adds one batch; returns (rescan, own_charge_over_low_budget)."""
        bt = sum(u for u, _ in batch)
        swu = {}
        for u, items in batch:
            for i in items:
                swu[i] = swu.get(i, 0.0) + u
                v = self.iau.get(i, 0.0) + u
                self.iau[i] = v
                if v > self.iau_max:
                    self.iau_max = v
        self.total += bt
        self.inserted += bt
        own = (max(swu.values()) if swu else 0.0) - self.su * bt
        over_low = own > (self.su - self.rl * self.su) * self.total
        self.over_low_run = self.over_low_run + 1 if over_low else 0
        phi = self.iau_max - self.su * self.inserted
        return phi > (self.su - self.floor) * self.tsu_ref, over_low

    def rescan(self, at_threshold):
        self.iau, self.iau_max, self.inserted = {}, 0.0, 0.0
        self.tsu_ref = self.total
        self.floor = self.su if at_threshold else self.rl * self.su


def run_policy(su, rl, init_total, batches, choose):
    """choose(t, history) -> True to rescan at the threshold. Returns per increment: 'D' direct,
    'L' rescan at the lower threshold, 'U' rescan at the threshold."""
    r = Replay(su, rl, init_total)
    path = []
    for t, b in enumerate(batches):
        rescan, _ = r.step(b)
        if not rescan:
            path.append("D")
            continue
        up = choose(t, path, r)
        r.rescan(up)
        path.append("U" if up else "L")
    return path


def measured(df, db, arm):
    rows = df[(df["dataset"] == db) & (df["algorithm"] == arm) & (df["iteration"] > 0)]
    return rows


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    ap.add_argument("--db", nargs="*", default=DBS)
    a = ap.parse_args()
    out = ROOT / a.out
    out.mkdir(parents=True, exist_ok=True)
    df = mf.load_scenario("SC2", replicates=True)
    summary = []
    for db in a.db:
        su, rl = mf.DATASETS[db]["default_uut"], 0.8
        n_init = mf.DATASETS[db]["d_init"]
        seqs = load(db)
        init, batches = seqs[:n_init], even_batches(seqs[n_init:], K)
        init_total = sum(u for u, _ in init)

        # what was measured, per increment (iteration 1..K), mean over the runs
        def per_inc(arm):
            rows = measured(df, db, arm)
            return {int(i): g["runtime_ms"].mean() / 1000 for i, g in rows.groupby("iteration")}
        cL, cU = per_inc("Remine-from-scratch"), per_inc("Remine-own-UUT")
        fx = measured(df, db, mf.FIXED)
        direct_rows = fx[fx["cub_triggered"].astype(str).str.lower() == "false"]
        cD = float(direct_rows["runtime_ms"].median()) / 1000 if len(direct_rows) else 0.0

        # replay check against the measured runs (first run of each arm)
        def measured_path(arm):
            rows = measured(df, db, arm)
            rows = rows[rows["replicate"] == "s0r0"].sort_values("iteration")
            path, prev_r, prev_u = [], 0, 0
            for _, x in rows.iterrows():
                rc, ru = int(x["rescan_count"]), int(x["rescans_at_uut"]) if x["rescans_at_uut"] == x["rescans_at_uut"] else 0
                path.append("D" if rc == prev_r else ("U" if ru > prev_u else "L"))
                prev_r, prev_u = rc, ru
            return path
        w5 = lambda t, path, r: r.over_low_run >= 5
        fixed = lambda t, path, r: False
        sim_w5 = run_policy(su, rl, init_total, batches, w5)
        sim_fixed = run_policy(su, rl, init_total, batches, fixed)
        m_w5, m_fixed = measured_path(mf.PROPOSED), measured_path(mf.FIXED)
        agree_w5 = sum(x == y for x, y in zip(sim_w5, m_w5))
        agree_fx = sum(x == y for x, y in zip(sim_fixed, m_fixed))
        ok = agree_w5 == K == len(m_w5) and agree_fx == K == len(m_fixed)
        print(f"{db}: replay agrees with the measured decisions on {agree_w5}/{len(m_w5)} increments (window 5) "
              f"and {agree_fx}/{len(m_fixed)} (fixed lower threshold)")
        if not ok:
            summary.append(dict(dataset=db, replay_window5=f"{agree_w5}/{len(m_w5)}",
                                replay_fixed=f"{agree_fx}/{len(m_fixed)}"))
            continue

        def price(path):
            return sum(cD if p == "D" else (cU[t + 1] if p == "U" else cL[t + 1]) for t, p in enumerate(path))

        # the cost rule (online): last observed cost at each floor; absorbed operations after earlier
        # rescans at the lower threshold; one rescan at the threshold tried at the first rescan
        def cost_rule(t, path, r):
            seen_U = [i for i, p in enumerate(path) if p == "U"]
            seen_L = [i for i, p in enumerate(path) if p == "L"]
            if not seen_U:
                return True
            last_U, last_L = cU[seen_U[-1] + 1], (cL[seen_L[-1] + 1] if seen_L else cL[0 + 1])
            absorbed = []
            for i in seen_L:
                j = i + 1
                while j < len(path) and path[j] == "D":
                    j += 1
                if j < len(path):
                    absorbed.append(j - i - 1)
            a_hat = statistics.mean(absorbed[-5:]) if absorbed else 1.0
            return (last_L + a_hat * cD) / (1 + a_hat) > last_U
        sim_cost = run_policy(su, rl, init_total, batches, cost_rule)

        # The cost rule with shadow absorption. The rule decides from the data alone, so how many
        # operations a rescan at the lower threshold would have absorbed can be computed while the
        # stream runs at the threshold: a shadow replay at the lower floor, restarted after each of
        # its own triggers, records that count without mining. Costs are the last observed ones at
        # each floor, scaled by the total utility since they were observed.
        def run_cost_rule2():
            r = Replay(su, rl, init_total)
            shadow, shadow_ops = None, 0
            absorbed = []
            last = {"L": (cL[1], init_total), "U": None}
            path = []
            for t, b in enumerate(batches):
                rescan, _ = r.step(b)
                if shadow is not None:
                    s_rescan, _ = shadow.step(b)
                    if s_rescan:
                        absorbed.append(shadow_ops)
                        shadow = None
                    else:
                        shadow_ops += 1
                if not rescan:
                    path.append("D")
                    continue
                # absorbed by the real rescan at the lower threshold that ends here
                if path:
                    k = len(path) - 1
                    while k >= 0 and path[k] == "D":
                        k -= 1
                    if k >= 0 and path[k] == "L":
                        absorbed.append(len(path) - 1 - k)
                if last["U"] is None:
                    up = True
                else:
                    a_hat = statistics.mean(absorbed[-5:]) if absorbed else 1.0
                    cl = last["L"][0] * r.total / last["L"][1]
                    cu = last["U"][0] * r.total / last["U"][1]
                    up = (cl + a_hat * cD) / (1 + a_hat) > cu
                r.rescan(up)
                last["U" if up else "L"] = ((cU if up else cL)[t + 1], r.total)
                if up and shadow is None:
                    shadow = Replay(su, rl, r.total)
                    shadow.tsu_ref = r.total
                    shadow_ops = 0
                path.append("U" if up else "L")
            return path
        sim_cost2 = run_cost_rule2()

        # Break-even rule: a rescan at the lower threshold costs rho times one at the threshold and
        # absorbs a operations at delta each (delta = direct / rescan at the threshold), so it is the
        # cheaper per operation iff a >= (rho - 1) / (1 - delta). rho is the ratio of the last costs
        # observed at the two floors (the initial mine is at the lower threshold; the first rescan is
        # at the threshold, so both are known from there on); a is the mean absorption of the last
        # cycles, real or shadow.
        def run_break_even(window=5):
            r = Replay(su, rl, init_total)
            shadow, shadow_ops = None, 0
            absorbed = []
            last_L, last_U = cL[1], None
            path = []
            for t, b in enumerate(batches):
                rescan, _ = r.step(b)
                if shadow is not None:
                    if shadow.step(b)[0]:
                        absorbed.append(shadow_ops)
                        shadow = None
                    else:
                        shadow_ops += 1
                if not rescan:
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
                    rho = last_L / last_U
                    delta = cD / last_U
                    a_hat = statistics.mean(absorbed[-window:]) if absorbed else 1.0
                    up = a_hat < (rho - 1) / max(1e-9, 1 - delta)
                r.rescan(up)
                if up:
                    last_U = cU[t + 1]
                    if shadow is None:
                        shadow, shadow_ops = Replay(su, rl, r.total), 0
                else:
                    last_L = cL[t + 1]
                path.append("U" if up else "L")
            return path
        sim_be = run_break_even()
        sim_U = run_policy(su, rl, init_total, batches, lambda t, path, r: True)

        # offline optimum: right after a rescan the counters are zero, so the next rescan depends only on
        # the increment t of that rescan and its floor; a dynamic programme over (t, floor) is exact
        totals = [init_total]
        for b in batches:
            totals.append(totals[-1] + sum(u for u, _ in b))
        nxt = {}
        def forward(t0, up):
            """After a rescan at index t0 (-1: the initial mine) at the threshold (up) or not: the index of
            the next rescan (K if none) and the direct updates before it."""
            if (t0, up) in nxt:
                return nxt[(t0, up)]
            r = Replay(su, rl, totals[t0 + 1])
            r.floor = su if up else rl * su
            t, d = t0 + 1, 0
            while t < K:
                rescan, _ = r.step(batches[t])
                if rescan:
                    break
                d += 1
                t += 1
            nxt[(t0, up)] = (t, d)
            return t, d
        memo = {}
        def V(t0, up):
            if (t0, up) in memo:
                return memo[(t0, up)]
            t, d = forward(t0, up)
            v = d * cD
            if t < K:
                v += min(cL[t + 1] + V(t, False), cU[t + 1] + V(t, True))
            memo[(t0, up)] = v
            return v
        sys.setrecursionlimit(10000)
        v_opt = V(-1, False)

        meas = lambda arm: measured(df, db, arm).groupby("replicate")["runtime_ms"].sum().mean() / 1000
        row = dict(dataset=db, replay_window5=f"{agree_w5}/{K}", replay_fixed=f"{agree_fx}/{K}",
                   measured_proposal_s=round(meas(mf.PROPOSED), 2), priced_window5_s=round(price(sim_w5), 2),
                   measured_fixed_s=round(meas(mf.FIXED), 2), priced_fixed_s=round(price(sim_fixed), 2),
                   measured_remine_uut_s=round(meas("Remine-own-UUT"), 2), priced_always_threshold_s=round(price(sim_U), 2),
                   priced_cost_rule_s=round(price(sim_cost), 2), priced_cost_rule_shadow_s=round(price(sim_cost2), 2), priced_break_even_s=round(price(sim_be), 2),
                   break_even_path="".join(sim_be),
                   priced_offline_optimum_s=round(v_opt, 2), cost_rule_shadow_path="".join(sim_cost2),
                   window5_path="".join(sim_w5), cost_rule_path="".join(sim_cost), direct_s=round(cD, 4))
        summary.append(row)
        print(f"  measured: proposal {row['measured_proposal_s']} s, fixed {row['measured_fixed_s']} s, "
              f"Remine-UUT {row['measured_remine_uut_s']} s")
        print(f"  priced:   window 5 {row['priced_window5_s']}, fixed {row['priced_fixed_s']}, at the threshold "
              f"always {row['priced_always_threshold_s']}, cost rule {row['priced_cost_rule_s']}, with shadow "
              f"{row['priced_cost_rule_shadow_s']}, break-even {row['priced_break_even_s']}, offline optimum "
              f"{row['priced_offline_optimum_s']}")
    keys = sorted({k for r in summary for k in r}, key=lambda k: list(summary[-1]).index(k) if k in summary[-1] else 99)
    with open(out / "floor_policy.csv", "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=keys)
        w.writeheader()
        w.writerows(summary)
    return 0


if __name__ == "__main__":
    sys.exit(main())
