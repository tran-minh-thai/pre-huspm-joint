#!/usr/bin/env python3
"""Which half of a modification makes it rescan, in SC6, SC6D and SC6B.

    python3 analysis/modification_halves.py --out results/invariant-modification-halves/<date>

A modification is one decision unit: its deletion half is tested, then its insertion half, and the
unit rescans once, after the insertion, when either test exceeds the budget. Each mixed stream is
replayed (analysis/floor_policy_probe.py's Replay) along the floors the measured method chose, and
at every modification the test after the deletion half and the test after the whole unit are
recorded. The replay must make the measured rescans at every operation, or the stream is reported
and the run fails. Writes modification_halves.csv (one row per stream) and prints the totals per
scenario."""
import argparse
import csv
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "analysis"))
import floor_policy_probe as fp  # noqa: E402
import make_figures as mf  # noqa: E402


def halves(stream, su, rl):
    rows = stream.rows[stream.rows["algorithm"] == mf.PROPOSED]
    measured = fp.measured_per_op(rows)
    r = fp.Replay(su, rl, sum(u for u, _ in stream.init))
    replayed = [[0, 0] for _ in range(stream.n_ops)]
    n_mod = only_deletion = only_whole = both = 0
    for unit in fp.units_of(stream, True):
        op = stream.op_of[unit[0]]
        first = r.step(stream.phases[unit[0]])
        whole = first
        if len(unit) == 2:
            whole = r.step(stream.phases[unit[1]])
            n_mod += 1
            only_deletion += first and not whole
            only_whole += whole and not first
            both += first and whole
        if first or whole:
            at_threshold = measured[op - 1][1] > 0
            r.rescan(at_threshold)
            replayed[op - 1][0] += 1
            replayed[op - 1][1] += at_threshold
    agree = sum(tuple(a) == tuple(b) for a, b in zip(replayed, measured))
    return dict(stream=stream.label, operations=stream.n_ops, replay_agrees=f"{agree}/{len(measured)}",
                modifications=n_mod, rescan_by_deletion_half_only=only_deletion,
                rescan_by_whole_unit_only=only_whole, rescan_by_both=both,
                modifications_rescanned=only_deletion + only_whole + both), agree == len(measured)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    ap.add_argument("--plans", default="results/invariant-offline-optimum-nodes/mixed_plans.tsv")
    a = ap.parse_args()
    out = ROOT / a.out
    out.mkdir(parents=True, exist_ok=True)
    ns = argparse.Namespace(db=["SIGN", "LEVIATHAN", "C8T1S5I8N5K", "TAFENG"], sc5=False, sc6=True, plans=a.plans)
    rows, bad = [], []
    for s in fp.streams(ns):
        if not s.label.startswith("SC6"):
            continue
        row, ok = halves(s, mf.DATASETS[s.db]["default_uut"], 0.8)
        rows.append(row)
        if not ok:
            bad.append(s.label)
    with open(out / "modification_halves.csv", "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0]), lineterminator="\n")
        w.writeheader()
        w.writerows(rows)
    for sc in ("SC6", "SC6D", "SC6B"):
        g = [x for x in rows if x["stream"].split()[0] == sc]
        tot = lambda k: sum(x[k] for x in g)
        print(f"{sc}: {len(g)} streams, {tot('modifications')} modifications, {tot('modifications_rescanned')} rescanned: "
              f"{tot('rescan_by_deletion_half_only')} by the deletion half only, {tot('rescan_by_whole_unit_only')} by the "
              f"whole unit only, {tot('rescan_by_both')} by both")
    print(f"streams: {len(rows)}; replay agrees with the measured rescans on every operation: {len(rows) - len(bad)}; not: {bad}")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
