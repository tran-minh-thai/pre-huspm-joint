#!/usr/bin/env python3
"""Check that runs of the method made the decisions of the break-even floor rule.

    python3 analysis/check_break_even.py --results DIR [--plans FILE] [--arm NAME]

For every stream of the arm in DIR (SC2_incremental.csv; SC6_idm_mixed.csv, SC6D_idm_mixed.csv and
SC6B_idm_mixed.csv when --plans gives their operations), the stream is replayed from the data with
analysis/floor_policy_probe.py's replay of the JOINT rule, following the floor the run chose at each
rescan, and the rule's inputs are recomputed independently of the Java code: the absorption of every
cycle after a rescan at r_l and, through a shadow at r_l, of every cycle after a rescan at UUT. At
each rescan with a logged decision it checks

  1. that the replay rescans exactly where the run rescanned (the JOINT decision and the merged
     modification),
  2. that the mean absorption the run logged (floor_absorption) equals the recomputed one,
  3. that the floor chosen satisfies the rule with the logged rho and delta:
     at UUT iff absorption < (rho - 1) / (1 - delta); the first rescan is at UUT.

rho and delta come from measured times, so they are taken from the run; everything else is
recomputed. Exits 1 when a check fails or when no decision was checked.
"""
import argparse
import csv
import math
import pathlib
import statistics
import sys
from collections import defaultdict

ROOT = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "analysis"))
import floor_policy_probe as fp  # noqa: E402
import make_figures as mf  # noqa: E402


def stream_units(db, plan_ops=None):
    seqs = fp.load(db)
    n_init = mf.DATASETS[db]["d_init"] if plan_ops is None else int(plan_ops[0]["d_init"])
    init = seqs[:n_init]
    if plan_ops is None:
        return init, [[(+1, b)] for b in fp.even_batches(seqs[n_init:], fp.K) if b]
    idx = lambda s: [seqs[int(x)] for x in s.split()] if s.strip() else []
    units = []
    for o in plan_ops:
        if o["kind"] == "INS":
            units.append([(+1, idx(o["a"]))])
        elif o["kind"] == "DEL":
            units.append([(-1, idx(o["a"]))])
        else:
            units.append([(-1, idx(o["a"])), (+1, idx(o["b"]))])
    return init, units


def check(db, rows, init, units, window=5):
    """rows: the arm's rows after the initial mine, in order. Returns (checked, failures)."""
    su, rl = mf.DATASETS[db]["default_uut"], 0.8
    r = fp.Replay(su, rl, sum(u for u, _ in init))
    shadow, shadow_n, absorbed, direct_since_low = None, 0, [], -1
    checked, fails = 0, []
    prev_rc = prev_ru = 0
    first = True
    for t, (unit, row) in enumerate(zip(units, rows)):
        rc, ru = int(row["rescan_count"]), int(float(row["rescans_at_uut"] or 0))
        rescanned, at_uut = rc - prev_rc, ru - prev_ru
        prev_rc, prev_ru = rc, ru
        trig = False
        for ph in unit:
            trig = r.step(ph) or trig
        if shadow is not None:
            s_trig = False
            for ph in unit:
                s_trig = shadow.step(ph) or s_trig
            if s_trig:
                absorbed.append(shadow_n)
                shadow = None
            else:
                shadow_n += 1
        if bool(trig) != bool(rescanned) or rescanned > 1:
            fails.append(f"{db} unit {t + 1}: replay rescans {trig}, run rescanned {rescanned}")
            return checked, fails
        if not trig:
            if direct_since_low >= 0:
                direct_since_low += 1
            continue
        if direct_since_low >= 0:
            absorbed.append(direct_since_low)
        a_hat = statistics.mean(absorbed[-window:]) if absorbed else 1.0
        rho, delta, a_log = (float(row[k]) for k in ("floor_rho", "floor_delta", "floor_absorption"))
        if first:
            ok = at_uut == 1
            first = False
        else:
            want_uut = a_log < (rho - 1) / max(1e-9, 1 - delta)
            ok = math.isclose(a_log, a_hat, rel_tol=0, abs_tol=1e-9) and want_uut == (at_uut == 1)
        checked += 1
        if not ok:
            fails.append(f"{db} unit {t + 1}: logged a={a_log} recomputed {a_hat}, rho={rho}, delta={delta}, "
                         f"chose {'UUT' if at_uut else 'r_l'}")
        up = at_uut == 1
        r.rescan(up)
        if up and shadow is None:
            shadow, shadow_n = fp.Replay(su, rl, r.total), 0
        direct_since_low = -1 if up else 0
    return checked, fails


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--results", required=True)
    ap.add_argument("--plans")
    ap.add_argument("--arm", default=mf.PROPOSED)
    a = ap.parse_args()
    res = pathlib.Path(a.results)
    total_checked, failures, streams = 0, [], 0
    sc2 = res / "SC2_incremental.csv"
    if sc2.exists():
        by = defaultdict(list)
        for row in csv.DictReader(open(sc2)):
            if row["algorithm"] == a.arm and int(row["iteration"]) > 0:
                by[(row["dataset"], row["seed"], row["repeat"])].append(row)
        for (db, _, _), rows in by.items():
            init, units = stream_units(db)
            c, f = check(db, sorted(rows, key=lambda x: int(x["iteration"])), init, units)
            total_checked += c; failures += f; streams += 1
    if a.plans:
        plans = defaultdict(list)
        for o in csv.DictReader(open(a.plans), delimiter="\t"):
            plans[(o["scenario"], o["dataset"], int(o["seed"]))].append(o)
        for sc in ("SC6", "SC6D", "SC6B"):
            f_ = res / f"{sc}_idm_mixed.csv"
            if not f_.exists():
                continue
            by = defaultdict(list)
            for row in csv.DictReader(open(f_)):
                if row["algorithm"] == a.arm and int(row["iteration"]) > 0:
                    by[(row["dataset"], int(row["seed"]), row["repeat"])].append(row)
            for (db, seed, _), rows in by.items():
                init, units = stream_units(db, plans[(sc, db, seed)])
                c, f = check(db, sorted(rows, key=lambda x: int(x["iteration"])), init, units)
                total_checked += c; failures += f; streams += 1
    for f in failures[:20]:
        print("FAILED " + f)
    print(f"streams: {streams}; break-even decisions checked: {total_checked}; failing: {len(failures)}")
    return 1 if failures or total_checked == 0 else 0


if __name__ == "__main__":
    sys.exit(main())
