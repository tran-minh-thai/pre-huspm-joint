#!/usr/bin/env python3
"""Where the method's time differs from its fixed-threshold version, operation by operation.

    python3 analysis/floor_gap.py [--out figures/campaign]     # tables and keys
    python3 analysis/floor_gap.py --self-test

The method (Pre-HUSPM-Joint) and its version at the fixed lower threshold
(Pre-HUSPM-Joint-fixed-threshold) replay the same stream; they differ only in the floor a rescan
mines at. Streams are compared when both arms finished them; on a database whose times drift
between sessions (check_rival_rebuild.UNSTABLE) only when both were measured in one session (their
DONE files at most SESSION_HOURS apart). Every operation of a compared stream falls in one class:

    both mine at the lower threshold     the same mine in both arms
    both mine, method at the threshold   the method's mine is the smaller one
    method mines, fixed direct           the fixed version absorbs the operation; the method, whose
                                         last rescan was at the threshold, has no buffer and mines
    method direct, fixed mines
    both direct

The time difference of a stream (method minus fixed) is the sum over the classes, which the script
checks. Two tables:

    table_floor_gap.csv         per scenario, database, run and seed: both times, their ratio, and
                                per class the operations and the time difference
    table_floor_absorption.csv  per scenario and database, from the counters alone: how many
                                operations a rescan at the lower threshold absorbs (cycles, mean,
                                standard deviation, share of cycles that absorb none, lag-1
                                autocorrelation), the node ratio rho of the two mines as the method
                                logged it, the break-even absorption rho - 1, the share of windows
                                of five cycles whose mean is below it, and the method's rescans

and paper_numbers_floor_gap.csv, the keys of both. Scenarios: SC2, the mixed streams and the long
streams; every database measured.
"""
import argparse
import collections
import math
import pathlib
import re
import shutil
import sys
import tempfile

import pandas as pd

ROOT = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "analysis"))
import make_figures as mf  # noqa: E402
from check_rival_rebuild import SESSION_HOURS, UNSTABLE  # noqa: E402

P, F = mf.PROPOSED, mf.FIXED
SCENARIOS = ["SC2", "SC6", "SC6D", "SC6B", "SC8", "SC8P", "SC9", "SC10", "SC10H"]
WINDOW = 5
CLASSES = ["both mine at the lower threshold", "both mine, method at the threshold",
           "method mines, fixed direct", "method direct, fixed mines", "both direct"]
SLUG = {"both mine at the lower threshold": "same_mine", "both mine, method at the threshold": "method_at_threshold",
        "method mines, fixed direct": "method_mines_fixed_direct", "method direct, fixed mines": "method_direct_fixed_mines",
        "both direct": "both_direct"}
FINISHED = re.compile(r"finished=(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2})")


def trees(results):
    out = [("s0r0", results / "campaign")]
    rep = results / "campaign-replicates"
    if rep.is_dir():
        out += [(d.name, d) for d in sorted(rep.iterdir()) if d.is_dir()]
    return out


def read_arm(d):
    """(rows, finish time) of a finished arm directory, or (None, None)."""
    if not (d / "DONE").is_file():
        return None, None
    files = sorted(d.glob("*.csv"))
    if not files:
        return None, None
    x = pd.concat((pd.read_csv(f, low_memory=False) for f in files), ignore_index=True)
    x.columns = [c.strip() for c in x.columns]
    m = FINISHED.search((d / "DONE").read_text(encoding="utf-8"))
    return x, (pd.Timestamp(m.group(1)) if m else None)


def op_time_ms(x):
    """Milliseconds of each row, from the microsecond column where the run has it."""
    if "runtime_us" in x.columns and x["runtime_us"].notna().all():
        return x["runtime_us"].astype(float) / 1000.0
    return x["runtime_ms"].astype(float)


def steps(x):
    """The operations of one stream in order, with whether each rescanned and whether at the threshold."""
    x = x[x["iteration"] > 0].sort_values("iteration").set_index("iteration")
    rescanned = x["rescan_count"].diff().fillna(x["rescan_count"]) > 0
    # rows written before the column existed do not say where a rescan mined
    at_threshold = (x["rescans_at_uut"].diff().fillna(x["rescans_at_uut"]) > 0
                    if "rescans_at_uut" in x.columns and x["rescans_at_uut"].notna().all() else None)
    return x, rescanned, at_threshold


def classify(m, f):
    """Per operation: (class, method ms, fixed ms). Both arms must have finished the same operations."""
    xm, rm, um = steps(m)
    xf, rf, _ = steps(f)
    if not xm.index.equals(xf.index):
        raise ValueError("the two arms did not run the same operations")
    if um is None:
        raise ValueError("the method's rows do not say which rescans mined at the threshold")
    if (xm["runtime_ms"] < 0).any() or (xf["runtime_ms"] < 0).any():
        raise ValueError("an operation did not complete")
    tm, tf = op_time_ms(xm), op_time_ms(xf)
    out = []
    for i in xm.index:
        if rm[i] and rf[i]:
            c = CLASSES[1] if um[i] else CLASSES[0]
        elif rm[i]:
            c = CLASSES[2]
        elif rf[i]:
            c = CLASSES[3]
        else:
            c = CLASSES[4]
        out.append((c, float(tm[i]), float(tf[i])))
    return out


def absorption(f):
    """Operations absorbed after each rescan of the fixed version (the cycles closed by a later rescan)."""
    _, rescanned, _ = steps(f)
    cycles, n = [], None
    for r in rescanned.tolist():
        if r:
            if n is not None:
                cycles.append(n)
            n = 0
        elif n is not None:
            n += 1
    return cycles


def streams_of(x):
    """Split an arm's rows into its streams: one per seed."""
    return {int(s): g for s, g in x.groupby("seed")} if "seed" in x.columns else {0: x}


def gap_rows(results):
    rows, skipped = [], collections.Counter()
    for rep, root in trees(results):
        for sc in SCENARIOS:
            for d in sorted((root / sc).glob("*/" + P)) if (root / sc).is_dir() else []:
                db = d.parent.name
                m, tm = read_arm(d)
                f, tf = read_arm(d.with_name(F))
                if m is None or f is None:
                    skipped["an arm missing"] += 1
                    continue
                one = tm is not None and tf is not None and abs((tm - tf).total_seconds()) <= SESSION_HOURS * 3600
                if not one and db in UNSTABLE:
                    skipped[f"two sessions on {db}"] += 1
                    continue
                fm, ff = streams_of(m), streams_of(f)
                for seed in sorted(set(fm) & set(ff)):
                    try:
                        ops = classify(fm[seed], ff[seed])
                    except ValueError as e:
                        skipped[str(e)] += 1
                        continue
                    r = dict(scenario=sc, dataset=db, replicate=rep, seed=seed, sessions="one" if one else "two",
                             operations=len(ops),
                             method_s=sum(o[1] for o in ops) / 1000, fixed_s=sum(o[2] for o in ops) / 1000)
                    r["method_over_fixed"] = r["method_s"] / r["fixed_s"]
                    total = 0.0
                    for c in CLASSES:
                        g = [o for o in ops if o[0] == c]
                        diff = sum(o[1] - o[2] for o in g) / 1000
                        r[SLUG[c] + "_operations"] = len(g)
                        r[SLUG[c] + "_difference_s"] = diff
                        total += diff
                    if abs(total - (r["method_s"] - r["fixed_s"])) > 1e-6:
                        raise SystemExit(f"{sc} {db} {rep} seed {seed}: the classes do not add up to the difference")
                    g = [o for o in ops if o[0] == CLASSES[0]]
                    r["same_mine_method_over_fixed"] = (sum(o[1] for o in g) / sum(o[2] for o in g)) if g else float("nan")
                    rows.append(r)
    return pd.DataFrame(rows), skipped


def absorption_rows(results):
    """From the main tree: the counters do not depend on the run."""
    rows = []
    root = results / "campaign"
    for sc in SCENARIOS:
        for d in sorted((root / sc).glob("*/" + F)) if (root / sc).is_dir() else []:
            db = d.parent.name
            f, _ = read_arm(d)
            m, _ = read_arm(d.with_name(P))
            if f is None or m is None:
                continue
            cycles, rescans, direct, m_rescans, m_at_thr, rho = [], 0, 0, 0, 0, []
            for seed, g in streams_of(f).items():
                cycles += absorption(g)
                _, r, _ = steps(g)
                rescans += int(r.sum())
                direct += int((~r).sum())
            for seed, g in streams_of(m).items():
                x, r, u = steps(g)
                m_rescans += int(r.sum())
                m_at_thr = None if u is None or m_at_thr is None else m_at_thr + int(u.sum())
                if "floor_rho" in x.columns:
                    rho += x["floor_rho"].dropna().tolist()
            s = pd.Series(cycles, dtype=float)
            rho_med = float(pd.Series(rho).median()) if rho else float("nan")
            even = rho_med - 1
            win = s.rolling(WINDOW).mean().dropna() if len(s) >= WINDOW else pd.Series(dtype=float)
            rows.append(dict(
                scenario=sc, dataset=db, streams=len(streams_of(f)), fixed_rescans=rescans, fixed_direct=direct,
                cycles=len(s), absorbed_mean=float(s.mean()) if len(s) else float("nan"),
                absorbed_sd=float(s.std()) if len(s) > 1 else float("nan"),
                cycles_absorbing_none=int((s == 0).sum()),
                share_absorbing_none=float((s == 0).mean()) if len(s) else float("nan"),
                lag1_autocorrelation=float(s.autocorr(1)) if len(s) >= 10 and s[1:].std() > 0 and s[:-1].std() > 0 else float("nan"),
                rho_nodes_median=rho_med, break_even_absorption=even,
                windows=len(win),
                share_windows_below_break_even=float((win < even).mean()) if len(win) and even == even else float("nan"),
                method_rescans=m_rescans,
                method_rescans_at_threshold=float("nan") if m_at_thr is None else m_at_thr))
    return pd.DataFrame(rows)


def keys(gap, ab):
    out = []
    src_g, src_a = "table_floor_gap.csv", "table_floor_absorption.csv"

    def put(k, v, src):
        out.append(dict(key=k, value=v, source=src))

    for (sc, db), g in gap.groupby(["scenario", "dataset"], sort=False):
        k = f"GAP_{sc}_{db}"
        put(f"{k}_streams", len(g), src_g)
        tot_m, tot_f = g["method_s"].sum(), g["fixed_s"].sum()
        put(f"{k}_method_over_fixed", f"{tot_m / tot_f:.3f}", src_g)
        put(f"{k}_difference_s", f"{tot_m - tot_f:.1f}", src_g)
        for c in CLASSES:
            put(f"{k}_{SLUG[c]}_operations", int(g[SLUG[c] + '_operations'].sum()), src_g)
            put(f"{k}_{SLUG[c]}_difference_s", f"{g[SLUG[c] + '_difference_s'].sum():.1f}", src_g)
        # the decisions: the two classes in which the arms did different work
        dec = g[SLUG[CLASSES[1]] + "_difference_s"].sum() + g[SLUG[CLASSES[2]] + "_difference_s"].sum() \
            + g[SLUG[CLASSES[3]] + "_difference_s"].sum()
        put(f"{k}_different_work_difference_s", f"{dec:.1f}", src_g)
        same = g["same_mine_method_over_fixed"].dropna()
        if len(same):
            put(f"{k}_same_mine_method_over_fixed_range", f"{same.min():.3f} to {same.max():.3f}", src_g)
    for r in ab.itertuples():
        k = f"ABSORB_{r.scenario}_{r.dataset}"
        put(f"{k}_cycles", r.cycles, src_a)
        if r.cycles:
            put(f"{k}_mean", f"{r.absorbed_mean:.3f}", src_a)
            put(f"{k}_share_absorbing_none", f"{r.share_absorbing_none:.3f}", src_a)
        if r.lag1_autocorrelation == r.lag1_autocorrelation:
            put(f"{k}_lag1_autocorrelation", f"{r.lag1_autocorrelation:.3f}", src_a)
        if r.rho_nodes_median == r.rho_nodes_median:
            put(f"{k}_rho_nodes", f"{r.rho_nodes_median:.3f}", src_a)
            put(f"{k}_break_even", f"{r.break_even_absorption:.3f}", src_a)
        if r.share_windows_below_break_even == r.share_windows_below_break_even:
            put(f"{k}_share_windows_below_break_even", f"{r.share_windows_below_break_even:.3f}", src_a)
        if r.method_rescans_at_threshold == r.method_rescans_at_threshold:
            put(f"{k}_method_rescans_at_threshold", f"{int(r.method_rescans_at_threshold)} of {r.method_rescans}", src_a)
    return pd.DataFrame(out)


def export(results, out):
    gap, skipped = gap_rows(results)
    ab = absorption_rows(results)
    if gap.empty:
        print("no stream of the method and its fixed version measured in one session")
        return 2
    out.mkdir(parents=True, exist_ok=True)
    mf.write_csv(gap, str(out / "table_floor_gap.csv"))
    mf.write_csv(ab, str(out / "table_floor_absorption.csv"))
    k = keys(gap, ab)
    if k["key"].duplicated().any():
        raise SystemExit("a key is written twice")
    mf.write_csv(k, str(out / "paper_numbers_floor_gap.csv"))
    print(f"streams compared: {len(gap)} ({gap[['scenario', 'dataset']].drop_duplicates().shape[0]} scenario and database pairs); "
          f"left out: {dict(skipped) or 'none'}")
    print(f"absorption rows: {len(ab)}; keys: {len(k)} -> {out}")
    return 0


# ------------------------------------------------------------------------------------- self-test
def _arm(d, rows, finished="2026-01-01 00:00:00"):
    d.mkdir(parents=True)
    pd.DataFrame(rows).to_csv(d / "x.csv", index=False)
    (d / "DONE").write_text(f"host=T finished={finished} seconds=1 rc=0 rows={len(rows)}\n", encoding="utf-8")


def _rows(times, rescans, at_thr, arm, rho=None):
    """iteration 0 is the initial mine; rescans and at_thr are cumulative counts per operation."""
    out = [dict(algorithm=arm, dataset="T", iteration=0, runtime_ms=100, runtime_us=100000, rescan_count=0,
                rescans_at_uut=0, seed=1, floor_rho=float("nan"))]
    for i, (t, r, u) in enumerate(zip(times, rescans, at_thr), 1):
        out.append(dict(algorithm=arm, dataset="T", iteration=i, runtime_ms=int(t), runtime_us=int(t * 1000),
                        rescan_count=r, rescans_at_uut=u, seed=1, floor_rho=rho if rho else float("nan")))
    return out


def self_test():
    fails = 0

    def check(name, ok):
        nonlocal fails
        fails += not ok
        print(("PASS " if ok else "FAIL ") + name)

    # fixed: mine, direct, mine, direct, mine, mine  (cycles closed: 1, 1, 0)
    fixed = _rows([100, 2, 100, 2, 100, 100], [1, 1, 2, 2, 3, 4], [0] * 6, F)
    # method: threshold mine, mine (fixed direct), low mine, direct, low mine, threshold mine
    method = _rows([80, 80, 110, 2, 100, 80], [1, 2, 3, 3, 4, 5], [1, 2, 2, 2, 2, 3], P, rho=1.3)
    with tempfile.TemporaryDirectory() as tmp:
        t = pathlib.Path(tmp)
        _arm(t / "campaign/SC8/T" / F, fixed)
        _arm(t / "campaign/SC8/T" / P, method)
        gap, skipped = gap_rows(t)
        r = gap.iloc[0]
        check("one stream compared", len(gap) == 1 and not skipped)
        check("classes counted", (r["method_at_threshold_operations"], r["method_mines_fixed_direct_operations"],
                                  r["same_mine_operations"], r["both_direct_operations"]) == (2, 1, 2, 1))
        check("differences per class", abs(r["method_at_threshold_difference_s"] - (-0.040)) < 1e-9
              and abs(r["method_mines_fixed_direct_difference_s"] - 0.078) < 1e-9
              and abs(r["same_mine_difference_s"] - 0.010) < 1e-9)
        check("the classes add up", abs(r["method_s"] - r["fixed_s"] - 0.048) < 1e-9)
        check("same-mine ratio", abs(r["same_mine_method_over_fixed"] - 210 / 200) < 1e-9)
        ab = absorption_rows(t).iloc[0]
        check("absorption cycles 1, 1, 0", ab["cycles"] == 3 and abs(ab["absorbed_mean"] - 2 / 3) < 1e-9
              and ab["cycles_absorbing_none"] == 1)
        check("break-even is rho - 1", abs(ab["break_even_absorption"] - 0.3) < 1e-9)
        check("method's rescans at the threshold", (ab["method_rescans"], ab["method_rescans_at_threshold"]) == (5, 3))
        k = keys(gap, absorption_rows(t)).set_index("key")["value"]
        check("keys written", k["GAP_SC8_T_method_mines_fixed_direct_difference_s"] == "0.1"
              and k["ABSORB_SC8_T_method_rescans_at_threshold"] == "3 of 5")
    with tempfile.TemporaryDirectory() as tmp:
        t = pathlib.Path(tmp)
        _arm(t / "campaign/SC8/T" / F, fixed, finished="2026-01-01 00:00:00")
        _arm(t / "campaign/SC8/T" / P, method, finished="2026-01-02 00:00:00")
        gap, skipped = gap_rows(t)
        check("arms of two sessions on a stable database are compared and marked",
              len(gap) == 1 and gap.iloc[0]["sessions"] == "two" and not skipped)
    with tempfile.TemporaryDirectory() as tmp:
        t = pathlib.Path(tmp)
        db = sorted(UNSTABLE)[0]
        _arm(t / "campaign/SC8" / db / F, fixed, finished="2026-01-01 00:00:00")
        _arm(t / "campaign/SC8" / db / P, method, finished="2026-01-02 00:00:00")
        gap, skipped = gap_rows(t)
        check("arms of two sessions on an unstable database are left out and counted",
              gap.empty and skipped[f"two sessions on {db}"] == 1)
    with tempfile.TemporaryDirectory() as tmp:
        t = pathlib.Path(tmp)
        _arm(t / "campaign/SC8/T" / F, fixed)
        _arm(t / "campaign/SC8/T" / P, method[:-1])
        gap, skipped = gap_rows(t)
        check("arms with different operations are left out and counted", gap.empty and sum(skipped.values()) == 1)
    with tempfile.TemporaryDirectory() as tmp:
        t = pathlib.Path(tmp)
        bad = [dict(r) for r in method]
        bad[3]["runtime_ms"] = -2
        _arm(t / "campaign/SC8/T" / F, fixed)
        _arm(t / "campaign/SC8/T" / P, bad)
        gap, skipped = gap_rows(t)
        check("a stream with an operation that did not complete is left out", gap.empty and sum(skipped.values()) == 1)
    with tempfile.TemporaryDirectory() as tmp:
        t = pathlib.Path(tmp)
        _arm(t / "campaign/SC8/T" / F, fixed)
        _arm(t / "campaign/SC8/T" / P, [dict(r, algorithm=P) for r in fixed])
        r = gap_rows(t)[0].iloc[0]
        check("the same arm twice: no difference, every mine the same mine",
              abs(r["method_s"] - r["fixed_s"]) < 1e-12 and r["same_mine_operations"] == 4 and r["both_direct_operations"] == 2)
    print(f"failed: {fails}")
    return 1 if fails else 0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--self-test", action="store_true")
    ap.add_argument("--results", default=str(ROOT / "results"))
    ap.add_argument("--out", default=str(ROOT / "figures" / "campaign"))
    a = ap.parse_args()
    if a.self_test:
        return self_test()
    return export(pathlib.Path(a.results), pathlib.Path(a.out))


if __name__ == "__main__":
    sys.exit(main())
