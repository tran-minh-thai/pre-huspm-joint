#!/usr/bin/env python3
"""Summary tables of the campaign, computed once and read by both the paper tables and the
scenario READMEs.

    python3 analysis/scenario_tables.py        # writes the CSV and LaTeX files below to OUT_DIR

make_figures.main() calls export_all(). Writes:

    table_static_summary.csv     SC1, SC3: HUSP-count agreement, cells where the proposal is
                                 fastest, the fastest baseline over the proposal per cell
    table_static_geomean.csv     SC1, SC3: geometric mean of each baseline's runtime over the
                                 proposal's, on all cells and at the default threshold
    table_SC4_cells.csv          SC4: pre-large set, heap and runtime per database, floor, arm
    table_SC2_memory.csv         memory SC2: state after the last increment and peak live set
    table_SC5_cells.csv          SC5: total time per (database, K, shape, arm), the proposal's
                                 rescans and the fastest arm of the cell
    table_SC6_by_op.csv          SC6: rescans and time by kind of operation, all seeds
    table_SC7_block_index.csv    SC7: the increments without a rescan, with and without the index
    table_agreement.csv          HUSP-count agreement of every finished arm in SC1, SC3, SC5
    paper_numbers.csv            every number the paper quotes, key -> value
    table_SC2_time.tex, table_SC2_rule.tex   the two SC2 tables of the paper
"""
import glob
import json
import os
import re
import sys

import numpy as np
import pandas as pd

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import make_figures as mf  # noqa: E402

P = mf.PROPOSED
REM = "Remine-from-scratch"
INC_ARMS = [P, "Pre-HUSPM-SWUmax", "Pre-HUSPM-TSU", REM, "IncUSP-Miner+"]
# SC2's competitors: the incremental baselines and every way of remining. The arms that change
# one component of the proposal (ISOLATION_ARMS) are not competitors and appear in their own table.
SC2_ARMS = INC_ARMS + ["Remine-HUSP-ULL", "Remine-HUSP-SP"]
ISOLATION_ARMS = ["Joint-miner+SWUmax", "Joint-miner+TSU", "Joint-rule+SWU-tracking"]


def gmean(x):
    x = np.asarray(x, float)
    x = x[np.isfinite(x) & (x > 0)]
    return float(np.exp(np.log(x).mean())) if len(x) else float("nan")


# ------------------------------------------------------------------------------ static
def static_cells(df):
    """(database, threshold, algorithm) -> the row of that run. With several replicates per
    cell (SC1) the row carries the mean runtime of the replicates that finished, and the cell
    counts as finished when at least one did; the pattern count must agree over them."""
    df = mf.parse_su(df)
    cells = {}
    for key, g in df.groupby(["dataset", "su", "algorithm"]):
        ok = g[g["finished"]]
        r = (ok if len(ok) else g).iloc[0].copy()
        if len(ok):
            if ok["total_patterns_found"].nunique() != 1:
                raise ValueError(f"{key}: the replicates disagree on the pattern count")
            r["runtime_ms"] = float(ok["runtime_ms"].mean())
            r["runtime_min"] = float(ok["runtime_ms"].min())
            r["runtime_max"] = float(ok["runtime_ms"].max())
        r["replicates"] = len(ok)
        cells[(key[0], float(key[1]), key[2])] = r
    return cells


def static_summary(cells, default_only=False):
    """Agreement of the HUSP counts, and the proposal's runtime against each baseline."""
    pairs = sorted({(d, su) for d, su, _ in cells})
    if default_only:
        pairs = [(d, su) for d, su in pairs if np.isclose(su, mf.default_su(d))]
    agree = n_pairs = fastest = n_fast = 0
    separated, ties = [], []     # wins every run of which beats every run of the best baseline;
                                 # cells whose runs overlap those of the best baseline
    disagree, vs_best, excluded = [], [], []
    per_base = {a: [] for a in mf.STATIC_ORDER if a != P}
    faster_on = {a: [] for a in mf.STATIC_ORDER if a != P}   # cells where the baseline wins
    for d, su in pairs:
        fin = {a: cells[(d, su, a)] for a in mf.STATIC_ORDER
               if (d, su, a) in cells and bool(cells[(d, su, a)]["finished"])}
        if len(fin) >= 2:
            n_pairs += 1
            if len({int(r["total_patterns_found"]) for r in fin.values()}) == 1:
                agree += 1
            else:
                disagree.append(f"{d} at {su:g}")
        if P not in fin:
            continue
        pm_ = float(fin[P]["runtime_ms"])
        others = {a: float(r["runtime_ms"]) for a, r in fin.items() if a != P}
        if pm_ <= 0 or any(v <= 0 for v in others.values()):
            excluded.append(f"{d} at {su:g}")
            continue
        if others:
            n_fast += 1
            fastest += int(pm_ < min(others.values()))
            vs_best.append(min(others.values()) / pm_)
            best = min(others, key=others.get)
            p_r, b_r = fin[P], fin[best]
            if p_r.get("replicates", 1) >= 2 and b_r.get("replicates", 1) >= 2:
                if p_r["runtime_max"] < b_r["runtime_min"]:
                    separated.append(f"{d} at {su:g}")
                elif not b_r["runtime_max"] < p_r["runtime_min"]:
                    ties.append(f"{d} at {su:g} ({best})")
        for a, v in others.items():
            per_base[a].append(v / pm_)
            if v < pm_:
                faster_on[a].append((d, su, v / pm_))
    return dict(pairs=n_pairs, agree=agree, disagree=disagree, fastest=fastest, n_fast=n_fast,
                vs_best=vs_best, per_base=per_base, faster_on=faster_on, excluded=excluded,
                separated=separated, ties=ties)


def static_tables():
    summ_rows, geo_rows = [], []
    for sc in ("SC1", "SC3"):
        # SC1 and SC3 were measured three times in fresh JVMs; every comparison uses the mean
        # of the three, as the tables do
        df = mf.load_scenario(sc, replicates=True)
        if df is None:
            continue
        cells = static_cells(df)
        for scope, default_only in (("all thresholds", False), ("default threshold", True)):
            s = static_summary(cells, default_only)
            vb = np.asarray(s["vs_best"], float)
            summ_rows.append(dict(scenario=sc, scope=scope, pairs=s["pairs"], pairs_agree=s["agree"],
                                  cells=s["n_fast"], proposal_fastest=s["fastest"],
                                  proposal_fastest_separated=len(s["separated"]),
                                  ties=len(s["ties"]), tie_cells="; ".join(s["ties"]),
                                  best_over_proposal_median=float(np.median(vb)) if len(vb) else np.nan,
                                  best_over_proposal_min=float(vb.min()) if len(vb) else np.nan,
                                  best_over_proposal_max=float(vb.max()) if len(vb) else np.nan,
                                  excluded="; ".join(s["excluded"])))
            for a, v in s["per_base"].items():
                won = s["faster_on"][a]
                geo_rows.append(dict(scenario=sc, scope=scope, baseline=a, geomean_over_proposal=gmean(v),
                                     cells=len(v), cells_baseline_faster=int(sum(x < 1 for x in v)),
                                     baseline_faster_on="; ".join(
                                         f"{d}{'' if default_only else f' at {su:g}'} {x:.2f}" for d, su, x in won)))
    return pd.DataFrame(summ_rows), pd.DataFrame(geo_rows)


# ------------------------------------------------------------------------------ SC4
def sc4_cells():
    df = mf.load_scenario("SC4")
    if df is None:
        return pd.DataFrame()
    df = mf.parse_sl(df)
    rows = []
    for (d, sl, base), g in df.groupby(["dataset", "sl", "base_algo"]):
        r = g.iloc[-1]
        ok = bool(r["finished"])
        rows.append(dict(dataset=d, r_l=float(sl), algorithm="Pre-HUSPM-Joint" if base == "Adaptive" else base,
                         status=r["status"], husps=int(r["total_patterns_found"]) if ok else np.nan,
                         prelarge=int(r["prelarge_count"]) if ok else np.nan,
                         peak_heap_mb=float(r["peak_heap_mb"]) if ok else np.nan,
                         runtime_s=float(r["runtime_ms"]) / 1000 if ok else np.nan))
    return pd.DataFrame(rows)


def sc4_ratio(cells):
    """Pre-HUSPM's pre-large set over the proposal's, on the cells where both completed."""
    t = cells.pivot_table(index=["dataset", "r_l"], columns="algorithm", values="prelarge")
    if not {"Pre-HUSPM", "Pre-HUSPM-Joint"} <= set(t.columns):
        return np.array([])
    r = (t["Pre-HUSPM"] / t["Pre-HUSPM-Joint"]).dropna()
    return r[np.isfinite(r)].to_numpy()


# ------------------------------------------------------------------------------ memory SC2
def sc2_memory():
    df = mf.load_scenario("SC2", tree=mf.MEMORY_TREE)
    if df is None:
        return pd.DataFrame()
    rows = []
    for d in [x for x in mf.SC2_DATASETS if x in set(df["dataset"])]:
        for a in INC_ARMS:
            g = df[(df["dataset"] == d) & (df["algorithm"] == a)].sort_values("iteration")
            if g.empty:
                continue
            ok = g[g["finished"]]
            inc = g[mf.is_inc(g)]
            done = int(inc["finished"].sum())
            last = ok.iloc[-1] if len(ok) else None
            rows.append(dict(dataset=d, algorithm=a, increments_done=done, increments=len(inc),
                             retained_after_last_mb=float(last["mem_retained_mb"]) if last is not None else np.nan,
                             peak_live_mb=float(ok["mem_live_mb"].max()) if len(ok) else np.nan))
    t = pd.DataFrame(rows)
    full = t[t["increments_done"] == t["increments"]]
    least = full.loc[full.groupby("dataset")["retained_after_last_mb"].idxmin(), ["dataset", "algorithm"]]
    t["least_retained"] = [bool(((least["dataset"] == r.dataset) & (least["algorithm"] == r.algorithm)).any())
                           for r in t.itertuples()]
    return t


# ------------------------------------------------------------------------------ SC5
def sc2_memory_keys(put, m2):
    """The memory run of SC2: per database and arm, the state held after the last increment and
    the peak live set during an increment; for each arm, the range over the databases it
    completes; and, for an arm that stopped early, the state it held at its last increment."""
    src = "table_SC2_memory.csv"
    for r in m2.itertuples():
        k = f"SC2_mem_{r.dataset}_{r.algorithm}"
        if r.increments_done == r.increments:
            put(f"{k}_retained_mb", f"{r.retained_after_last_mb:,.0f}", src)
            put(f"{k}_peak_mb", f"{r.peak_live_mb:,.0f}", src)
        elif pd.notna(r.retained_after_last_mb):
            put(f"{k}_increments_done", int(r.increments_done), src)
            put(f"{k}_retained_at_stop_mb", f"{r.retained_after_last_mb:,.0f}", src)
            # GB as the heap size is written: 48 GB is 49,152 MB
            put(f"{k}_retained_at_stop_gb", half_up(r.retained_after_last_mb / 1024, 1), src)
    full = m2[m2["increments_done"] == m2["increments"]]
    groups = {a: [a] for a in INC_ARMS}
    groups["Pre-HUSPM-both"] = ["Pre-HUSPM-SWUmax", "Pre-HUSPM-TSU"]
    for name, arms in groups.items():
        g = full[full["algorithm"].isin(arms)]
        if len(g):
            lo, hi = g.loc[g["retained_after_last_mb"].idxmin()], g.loc[g["retained_after_last_mb"].idxmax()]
            put(f"SC2_mem_{name}_retained_range", f"{lo.retained_after_last_mb:,.0f} ({lo.dataset}) to "
                f"{hi.retained_after_last_mb:,.0f} ({hi.dataset})", src)
    # where an arm holds less than the proposal at its peak, or comes within 10% of it after the
    # last increment
    for d, g in full.groupby("dataset"):
        pr = g[g["algorithm"] == P]
        if pr.empty:
            continue
        pr = pr.iloc[0]
        for r in g[g["algorithm"] != P].itertuples():
            if r.peak_live_mb < pr.peak_live_mb:
                put(f"SC2_mem_{d}_{r.algorithm}_peak_below_proposal", f"{r.peak_live_mb:,.0f} vs {pr.peak_live_mb:,.0f}", src)
            if r.retained_after_last_mb < 1.10 * pr.retained_after_last_mb:
                put(f"SC2_mem_{d}_{r.algorithm}_retained_within_10pct",
                    f"{r.retained_after_last_mb:,.0f} vs {pr.retained_after_last_mb:,.0f}", src)


def sc5_run_totals():
    """(database, K, shape, algorithm) -> the time summed over the increments, one value per
    replicate that finished every increment."""
    df = mf.load_scenario("SC5", replicates=True)
    if df is None:
        return {}
    df = mf.parse_batch(df)
    inc = df[df["phase"] == "INC"]
    out = {}
    for (d, k, s, a, r), g in inc.groupby(["dataset", "K", "strat", "algorithm", "replicate"]):
        if g["finished"].all():
            out.setdefault((d, int(k), s, a), []).append(float(mf.ms_to_s(g["runtime_ms"]).sum()))
    return out


def sc5_cells():
    df = mf.load_mean("SC5")
    if df is None:
        return pd.DataFrame()
    df = mf.parse_batch(df)
    inc = df[df["phase"] == "INC"]
    runs = sc5_run_totals()
    rows = []
    for (d, k, s), g in inc.groupby(["dataset", "K", "strat"]):
        tot = {}
        for a, ga in g.groupby("algorithm"):
            tot[a] = mf.ms_to_s(ga["runtime_ms"]).sum() if ga["finished"].all() else np.nan
        done = {a: v for a, v in tot.items() if np.isfinite(v)}
        fastest = min(done, key=done.get) if done else ""
        # the closest other arm, and whether every run of the proposal beats every run of it
        others = {a: v for a, v in done.items() if a != P}
        close = min(others, key=others.get) if others else ""
        rp, rc = runs.get((d, int(k), s, P), []), runs.get((d, int(k), s, close), [])
        runs_vs_closest = ("one run" if len(rp) < 2 or len(rc) < 2 else
                           "separated" if max(rp) < min(rc) else
                           "behind" if max(rc) < min(rp) else "overlapping")
        pr = g[g["algorithm"] == P]
        rows.append(dict(dataset=d, K=int(k), shape=s,
                         **{f"total_s_{a}": tot.get(a, np.nan) for a in INC_ARMS},
                         proposal_rescans=int(pr["rescan_count"].max()) if len(pr) else np.nan,
                         proposal_rescan_share=float(pr["rescan_count"].max()) / int(k) if len(pr) else np.nan,
                         remine_over_proposal=tot.get(REM, np.nan) / tot.get(P, np.nan)
                         if tot.get(P) else np.nan,
                         fastest=fastest, closest=close,
                         closest_over_proposal=others[close] / tot[P] if close and tot.get(P) else np.nan,
                         runs_proposal=len(rp), runs_closest=len(rc), runs_vs_closest=runs_vs_closest))
    return pd.DataFrame(rows)


# ------------------------------------------------------------------------------ SC6
def sc6_by_op():
    df = mf.load_scenario("SC6", replicates=True)
    if df is None:
        return pd.DataFrame()
    ops = df[~df["op_type"].astype(str).str.contains("INIT")].copy()
    ops["kind"] = ops["op_type"].astype(str).str.extract(r"SC6_(INS|DEL_BIG|DEL|MOD)")[0]
    name = {"INS": "insertion", "DEL": "deletion", "MOD": "modification"}
    rows = []
    for d, g in ops.groupby("dataset", sort=False):
        p = g[g["algorithm"] == P].sort_values(["replicate", "iteration"])
        p = p.assign(rescanned=p.groupby("replicate")["rescan_count"].diff().fillna(p["rescan_count"]) > 0)
        for k in ["INS", "DEL", "MOD"]:
            pk = p[p["kind"] == k]
            qk = g[(g["algorithm"] == REM) & (g["kind"] == k)]
            a, b = mf.ms_to_s(pk["runtime_ms"]).sum(), mf.ms_to_s(qk["runtime_ms"]).sum()
            rows.append(dict(dataset=d, kind=name[k], operations=len(pk), rescans=int(pk["rescanned"].sum()),
                             proposal_s=a, remine_s=b, remine_over_proposal=b / a if a > 0 else np.nan))
    return pd.DataFrame(rows)


# Remining arms of the mixed streams (AutomatedExperimentRunner.MIXED_ARMS after the proposal)
MIXED_REMINE = ["Remine-from-scratch", "Remine-HUSP-ULL", "Remine-HUSP-SP"]
MIXED_KINDS = {"INS": "insertion", "DEL": "deletion", "MOD": "modification",
               "DEL_BIG": "deletion beyond the budget"}


def mixed_by_op(sc):
    """SC6, SC6D or SC6B by kind of operation, one row per (database, kind), the seeds as
    replicates. For the proposal: operations over all seeds, how many rescanned and how many
    took the direct update, and its time per seed. For each remining arm: the ratio of its time
    to the proposal's on that kind, computed per seed and reported as mean and sample standard
    deviation over the seeds where both arms finished every operation of the kind (seeds), and
    its time per seed. A row of kind "stream" does the same over every operation after the
    initial mine, with the proposal's time over the arm's as the ticket asks."""
    df = mf.load_scenario(sc, replicates=True)
    if df is None:
        return pd.DataFrame()
    ops = df[~df["op_type"].astype(str).str.contains("INIT")].copy()
    ops["kind"] = ops["op_type"].astype(str).str.extract(r"SC6_(INS|DEL_BIG|DEL|MOD)")[0]
    ops = ops[ops["kind"].notna()]
    rows = []
    for d in [x for x in mf.ALL_DATASETS if x in set(ops["dataset"])]:
        g = ops[ops["dataset"] == d]
        p = g[g["algorithm"] == P].sort_values(["replicate", "iteration"])
        # rescan_count is cumulative within a replicate: an operation rescanned when it grew
        p = p.assign(rescanned=p.groupby("replicate")["rescan_count"].diff().fillna(p["rescan_count"]) > 0)
        kinds = [k for k in ["INS", "DEL", "MOD", "DEL_BIG"] if k in set(p["kind"])] + ["stream"]
        for k in kinds:
            pk = p if k == "stream" else p[p["kind"] == k]
            row = dict(dataset=d, kind="stream" if k == "stream" else MIXED_KINDS[k],
                       operations=len(pk), seeds=pk["replicate"].nunique(),
                       rescans=int(pk["rescanned"].sum()), direct=int((~pk["rescanned"]).sum()))
            per_p = {r: mf.ms_to_s(x["runtime_ms"]).sum() for r, x in pk.groupby("replicate")
                     if x["finished"].all()}
            row["proposal_s_mean"] = np.mean(list(per_p.values())) if per_p else np.nan
            for arm in MIXED_REMINE:
                q = g[g["algorithm"] == arm]
                qk = q if k == "stream" else q[q["kind"] == k]
                per_q = {r: mf.ms_to_s(x["runtime_ms"]).sum() for r, x in qk.groupby("replicate")
                         if x["finished"].all() and len(x) == len(pk[pk["replicate"] == r])}
                common = sorted(set(per_p) & set(per_q))
                if k == "stream":
                    ratios = [per_p[r] / per_q[r] for r in common if per_q[r] > 0]
                    key = "proposal_over"
                else:
                    ratios = [per_q[r] / per_p[r] for r in common if per_p[r] > 0]
                    key = "over_proposal"
                tag = arm.replace("Remine-from-scratch", "Remine")
                row[f"{tag}_s_mean"] = np.mean([per_q[r] for r in common]) if common else np.nan
                row[f"{key}_{tag}_mean"] = np.mean(ratios) if ratios else np.nan
                row[f"{key}_{tag}_sd"] = np.std(ratios, ddof=1) if len(ratios) > 1 else np.nan
                row[f"{key}_{tag}_seeds"] = len(ratios)
            rows.append(row)
    return pd.DataFrame(rows)


# ------------------------------------------------------------------------------ SC7
def sc7_block_index():
    df = mf.load_scenario("SC7")
    if df is None:
        return pd.DataFrame()
    inc = df[df["algorithm"].isin(["proposed_C5_on", "proposed_no_block_index"]) & mf.is_inc(df)]
    rows = []
    for d in [x for x in mf.ALL_DATASETS if x in set(inc["dataset"])]:
        on = inc[(inc["dataset"] == d) & (inc["algorithm"] == "proposed_C5_on")]
        nb = inc[(inc["dataset"] == d) & (inc["algorithm"] == "proposed_no_block_index")]
        d_on = on[on["cub_triggered"].astype(str).str.lower() == "false"]
        d_nb = nb[nb["cub_triggered"].astype(str).str.lower() == "false"]
        rows.append(dict(dataset=d, increments_without_rescan=len(d_on),
                         with_index_ms=float(d_on["runtime_ms"].sum()), without_index_ms=float(d_nb["runtime_ms"].sum()),
                         sequences_read_with=int(d_on["blocks_read"].sum()),
                         sequences_read_without=int(d_nb["blocks_read"].sum()),
                         total_with_s=float(mf.ms_to_s(on["runtime_ms"]).sum()),
                         total_without_s=float(mf.ms_to_s(nb["runtime_ms"]).sum())))
    return pd.DataFrame(rows)


def sc7_block_size():
    """Block-size sweep of the block index on the SC2 stream. proposed_C5_on is the default
    block size; proposed_bsz_<n> the others. Sequences read and time are summed over the
    increments that took the direct update; the rescan decisions do not read the index, so
    every arm has the same set of such increments. counts_equal compares HUSP and pre-large
    counts with the default arm row by row."""
    df = mf.load_scenario("SC7")
    if df is None:
        return pd.DataFrame()
    arms = {"proposed_C5_on": None}
    arms.update({a: int(a.rsplit("_", 1)[1]) for a in set(df["algorithm"]) if str(a).startswith("proposed_bsz_")})
    if len(arms) == 1:
        return pd.DataFrame()
    rows = []
    for d in [x for x in mf.ALL_DATASETS if x in set(df["dataset"])]:
        ref = df[(df["dataset"] == d) & (df["algorithm"] == "proposed_C5_on")]
        if ref.empty:
            continue
        default_bsz = int(ref["block_size"].iloc[0])
        ref_sig = list(zip(ref["total_patterns_found"], ref["prelarge_count"]))
        ref_direct = set(ref.loc[mf.is_inc(ref) & (ref["cub_triggered"].astype(str).str.lower() == "false"),
                                 "iteration"])
        for arm, bsz in arms.items():
            g = df[(df["dataset"] == d) & (df["algorithm"] == arm)]
            if g.empty:
                continue
            inc = g[mf.is_inc(g)]
            direct = inc[inc["cub_triggered"].astype(str).str.lower() == "false"]
            sig = list(zip(g["total_patterns_found"], g["prelarge_count"]))
            rows.append(dict(dataset=d, block_size=default_bsz if bsz is None else bsz,
                             default=bsz is None,
                             increments_without_rescan=len(direct),
                             same_direct_increments=set(direct["iteration"]) == ref_direct,
                             sequences_read=int(direct["blocks_read"].sum()),
                             direct_ms=float(direct["runtime_ms"].sum()),
                             total_s=float(mf.ms_to_s(g["runtime_ms"]).sum()),
                             counts_equal=f"{sum(a == b for a, b in zip(sig, ref_sig))}/{len(ref_sig)}"))
    order = {d: i for i, d in enumerate(mf.ALL_DATASETS)}
    out = pd.DataFrame(rows)
    out = out.sort_values(["dataset", "block_size"], key=lambda c: c.map(order) if c.name == "dataset" else c)
    out = out.reset_index(drop=True)
    # Nested blocks: if a block size divides the next one, the smaller blocks refine the larger
    # ones and the sequences read cannot decrease. Checked on every consecutive pair that nests.
    checks = []
    for d, g in out.groupby("dataset"):
        g = g.sort_values("block_size")
        for (_, a), (_, b) in zip(g.iterrows(), g.iloc[1:].iterrows()):
            if b["block_size"] % a["block_size"] == 0:
                checks.append(a["sequences_read"] <= b["sequences_read"])
    out.attrs["nested_pairs"] = len(checks)
    out.attrs["nested_pairs_monotone"] = sum(checks)
    return out


# ------------------------------------------------------------------------------ explored nodes, one convention
ROOT_ITEMS = os.path.join(mf.DATA_DIR, "invariant-root-items", "root_items.csv")


def root_stopped():
    """(scenario, database, threshold, ips) -> root items that receive a list and are stopped
    at once by the node bound (analysis/root_items.py); empty when the file is missing."""
    if not os.path.exists(ROOT_ITEMS):
        return {}
    r = pd.read_csv(ROOT_ITEMS)
    return {(x["scenario"], x["dataset"], round(float(x["su"]), 9), x["ips"]): int(x["root_stopped"])
            for _, x in r.iterrows()}


def explored_one_convention():
    """Explored nodes of SC1 and SC3 with the proposal counted as the baselines count: a node only
    when it passes the bound, i.e. explored_nodes minus the root items stopped at once. Ratios of
    each baseline's count over the proposal's, per database and threshold; a cell whose root count
    is missing is named in `missing`, not dropped."""
    stop = root_stopped()
    rows = []
    for sc in ("SC1", "SC3"):
        df = mf.load_scenario(sc)
        if df is None:
            continue
        cells = static_cells(df)
        for d, su in sorted({(d, su) for d, su, _ in cells}):
            pr = cells.get((d, su, P))
            if pr is None or not bool(pr["finished"]):
                continue
            k = (sc, d, round(su, 9), "once")
            row = dict(scenario=sc, dataset=d, su=f"{su:g}", default=bool(np.isclose(su, mf.default_su(d))),
                       proposal_explored=int(pr["explored_nodes"]),
                       root_stopped=stop.get(k), missing=k not in stop)
            row["proposal_explored_bound"] = (row["proposal_explored"] - row["root_stopped"]
                                              if row["root_stopped"] is not None else None)
            for a in mf.STATIC_ORDER:
                if a == P:
                    continue
                r = cells.get((d, su, a))
                ok = r is not None and bool(r["finished"])
                row[f"explored_{a}"] = int(r["explored_nodes"]) if ok else None
                pb = row["proposal_explored_bound"]
                row[f"ratio_{a}"] = (int(r["explored_nodes"]) / pb) if ok and pb else None
            rows.append(row)
    return pd.DataFrame(rows)


# ------------------------------------------------------------------------------ verification
VERIFY_DIR = os.path.join(mf.DATA_DIR, "verify-joint")


def _console(name):
    path = os.path.join(VERIFY_DIR, name, "console.txt")
    return open(path, encoding="utf-8").read() if os.path.exists(path) else None


def _status(name):
    """From the run record that analysis/record_run.py writes when the command ends: 'missing'
    with no output, 'not finished' while the record is absent, 'exit <n>' on a non-zero exit."""
    meta = os.path.join(VERIFY_DIR, name, "run_meta.json")
    if not os.path.isdir(os.path.join(VERIFY_DIR, name)):
        return "missing"
    if not os.path.exists(meta):
        return "not finished"
    code = json.load(open(meta, encoding="utf-8")).get("exit_code")
    return "finished" if code == 0 else f"exit {code}"


def verification():
    """The correctness checks of results/verify-joint, read from their outputs.

    Returns (summary, by_database). summary has one row per check; a check whose output is
    missing or did not finish is a row with status 'missing' or 'did not finish', never left
    out. by_database counts the operations of the two DirectPathVerifier runs per database,
    kind and path."""
    rows, per_db = [], []
    fuzz = _console("fuzz")
    if _status("fuzz") != "finished":
        rows.append(dict(check="random streams (MixedBudgetFuzz)", status=_status("fuzz")))
    else:
        for rule in ("joint", "iaumax"):
            lines = [l for l in fuzz.splitlines() if l.startswith(f"rule={rule} ")]
            num = lambda pat: sum(int(re.search(pat, l).group(1)) for l in lines)
            rows.append(dict(check=f"random streams (MixedBudgetFuzz), rule {rule}", status=_status("fuzz"),
                             runs=len(lines), operations=num(r"ops=(\d+)"),
                             direct_delete=num(r"direct-update=\{delete=(\d+)"),
                             direct_insert=num(r"direct-update=\{[^}]*insert=(\d+)"),
                             direct_modify=num(r"direct-update=\{[^}]*modify=(\d+)"),
                             mismatches=num(r"mismatching-ops=(\d+)")))
        probes = [l for l in fuzz.splitlines() if "total mismatching patterns over" in l]
        for rule, l in zip(("joint", "iaumax"), probes):
            m = re.search(r"over (\d+) checks = (\d+)", l)
            rows.append(dict(check=f"hand-built cases (MixedBudgetProbe), rule {rule}", status=_status("fuzz"),
                             operations=int(m.group(1)), mismatches=int(m.group(2))))
    acc = _console("acceptance")
    m = re.search(r"acceptance: (\d+) passed, (\d+) failed", acc or "")
    rows.append(dict(check="rescan rule acceptance (RescanRuleAcceptanceTest)",
                     status=_status("acceptance") if m else "missing",
                     operations=int(m.group(1)) + int(m.group(2)) if m else None,
                     mismatches=int(m.group(2)) if m else None))
    # Irrelevant item pruning against exhaustive enumeration, and with the pruning on and off.
    out = _console("ips-random")
    if _status("ips-random") != "finished":
        rows.append(dict(check="item pruning against exhaustive enumeration (ItemRemovalCheck random)",
                         status=_status("ips-random")))
    else:
        m = re.search(r"trials=(\d+).*databases-with->=1-pattern=(\d+)", out)
        bad = sum(int(x) for x in re.findall(r"^  (?:once|fixpoint)\s+mismatching-databases=(\d+)", out, flags=re.M))
        nodes = re.search(r"^  once .*dbs-with-node-removal=(\d+)", out, flags=re.M)
        rows.append(dict(check="item pruning against exhaustive enumeration (ItemRemovalCheck random)",
                         status="finished", runs=int(m.group(1)), operations=int(m.group(2)),
                         with_target=int(nodes.group(1)) if nodes else None, mismatches=bad))
    out = _console("ips-real")
    if _status("ips-real") != "finished":
        rows.append(dict(check="item pruning on and off on real databases (ItemRemovalCheck real)",
                         status=_status("ips-real")))
    else:
        for scope in ("init", "whole"):
            blocks = re.findall(r"^real (\S+) scope=" + scope + r" .*?\nRESULT ([^\n]*)", out, flags=re.M | re.S)
            rows.append(dict(check=f"item pruning on and off on real databases, {scope} (ItemRemovalCheck real)",
                             status="finished", runs=len(blocks),
                             operations=sum(1 for _, r in blocks if r.startswith("every mode returns")),
                             mismatches=sum(1 for _, r in blocks if not r.startswith("every mode returns"))))
    for name, label in (("fault-injection", "fault injection, random streams and hand-built cases"),
                        ("fault-injection-real", "fault injection, adversarial runs on real databases"),
                        ("fault-injection-ips", "fault injection into the item pruning")):
        out = _console(name)
        if _status(name) != "finished":
            rows.append(dict(check=label, status=_status(name)))
            continue
        faults = re.findall(r"^  caught: (True|False)", out, flags=re.M)
        rows.append(dict(check=label, status=_status(name),
                         operations=len(faults), caught=sum(f == "True" for f in faults)))
    # Every DirectPathVerifier run, found by its output file rather than by name.
    runs = sorted(os.path.basename(os.path.dirname(f))
                  for f in glob.glob(os.path.join(VERIFY_DIR, "*", "verification.csv")))
    for name in runs or ["random", "adversarial"]:
        path = os.path.join(VERIFY_DIR, name, "verification.csv")
        out = _console(name)
        if not os.path.exists(path):
            rows.append(dict(check=f"direct path on real databases, {name} operations", status="missing"))
            continue
        v = pd.read_csv(path)
        finished = _status(name) == "finished" and out is not None and "mismatching operations:" in out
        for d, g in v.groupby("dataset", sort=False):
            r = dict(check=name, dataset=d, operations=len(g),
                     with_target=int(g["target"].fillna("").astype(str).str.len().gt(0).sum()),
                     mismatches=int((g["clean"].astype(str).str.lower() != "true").sum()))
            for op in ("insert", "delete", "modify"):
                for path_kind in ("direct", "rescan"):
                    r[f"{op}_{path_kind}"] = int(((g["op_type"] == op) & (g["path"] == path_kind)).sum())
            per_db.append(r)
        rows.append(dict(check=f"direct path on real databases, {name} operations",
                         status="finished" if finished else _status(name),
                         runs=v["dataset"].nunique(), operations=len(v),
                         with_target=int(v["target"].fillna("").astype(str).str.len().gt(0).sum()),
                         direct_delete=int(((v["op_type"] == "delete") & (v["path"] == "direct")).sum()),
                         direct_insert=int(((v["op_type"] == "insert") & (v["path"] == "direct")).sum()),
                         direct_modify=int(((v["op_type"] == "modify") & (v["path"] == "direct")).sum()),
                         mismatches=int((v["clean"].astype(str).str.lower() != "true").sum())))
    summary = pd.DataFrame(rows)
    for col in summary.columns.drop(["check", "status"]):
        summary[col] = summary[col].astype("Int64")
    return summary, pd.DataFrame(per_db)


# ------------------------------------------------------------------------------ agreement
def agreement():
    """Rows (per database and threshold, or per increment) where every finished arm reports
    the same HUSP count."""
    rows = []
    for sc, key in (("SC1", ["dataset", "op_type"]), ("SC3", ["dataset", "op_type"]),
                    ("SC5", ["dataset", "op_type", "iteration"])):
        df = mf.load_scenario(sc)
        if df is None:
            continue
        fin = df[df["finished"]]
        if sc == "SC5":
            fin = fin[mf.is_inc(fin)]
        groups = fin.groupby(key)["total_patterns_found"]
        multi = groups.size() >= 2
        same = groups.nunique() == 1
        rows.append(dict(scenario=sc, comparisons=int(multi.sum()), disagreeing=int((multi & ~same).sum())))
    return pd.DataFrame(rows)


# ------------------------------------------------------------------------------ noise
def noise():
    """How much a measurement moves between runs: runtime over the three SC1 runs (relative
    standard deviation per configuration), and the live set between the memory run and its
    replicate (relative difference per cell, SC1 at every threshold, SC2 after the last
    increment). Cells reading below 1 MB are left out."""
    rows = []
    rep = mf.load_scenario("SC1", replicates=True)
    if rep is not None:
        rep = mf.parse_su(rep)
        for (d, su, a), g in rep[rep["finished"]].groupby(["dataset", "su", "algorithm"]):
            t = mf.ms_to_s(g["runtime_ms"])
            if len(t) >= 2 and t.mean() > 0:
                rows.append(dict(measure="SC1 runtime", dataset=d, threshold=su, algorithm=a, runs=len(t),
                                 mean=float(t.mean()), relative_spread=float(np.std(t, ddof=1) / t.mean())))
    for sc, col in (("SC1", "mem_live_mb"), ("SC2", "mem_retained_mb")):
        main = mf.load_scenario(sc, tree=mf.memory_tree(sc))
        reps = []
        for rd in mf.memory_replicate_dirs(sc):
            r = mf.load_scenario(sc, tree=os.path.relpath(os.path.join(rd), mf.DATA_DIR))
            if r is not None:
                reps.append(r)
        if main is None or not reps:
            continue
        for r in reps:
            key = ["dataset", "algorithm", "op_type", "iteration"]
            a_ = main[main["finished"]]
            b_ = r[r["finished"]]
            if sc == "SC2":
                a_ = a_.sort_values("iteration").groupby(["dataset", "algorithm"]).tail(1)
                b_ = b_.sort_values("iteration").groupby(["dataset", "algorithm"]).tail(1)
            m = a_.merge(b_, on=key, suffixes=("_a", "_b"))
            for _, x in m.iterrows():
                va, vb = float(x[f"{col}_a"]), float(x[f"{col}_b"])
                if va >= 1 and vb >= 1:
                    rows.append(dict(measure=f"{sc} live set" if sc == "SC1" else "SC2 state after last increment",
                                     dataset=x["dataset"],
                                     threshold=float(str(x["op_type"]).split("=")[1]) if sc == "SC1" else np.nan,
                                     algorithm=x["algorithm"], runs=2,
                                     mean=(va + vb) / 2, relative_spread=abs(va - vb) / ((va + vb) / 2)))
    return pd.DataFrame(rows)


# ------------------------------------------------------------------------------ paper
def sc2_stats(totals, reps=None):
    """(database, arm) -> n, mean and standard deviation of the SC2 runtime summed over the
    increments: the mean of the replicates where at least two finished without a failure,
    else the one run; None for an arm that did not finish all increments."""
    R = {} if reps is None else {(r["dataset"], r["algorithm"]): r for _, r in reps.iterrows()
                                 if int(r["n"]) >= 2 and int(r["n_failed"]) == 0}
    out = {}
    for _, r in totals.iterrows():
        k = (r["dataset"], r["algorithm"])
        if r["increments_done"] != r["increments"]:
            out[k] = None
        elif k in R:
            q = R[k]
            out[k] = dict(n=int(q["n"]), mean=float(q["total_s_mean"]), std=float(q["total_s_std"]),
                          min=float(q["total_s_min"]), max=float(q["total_s_max"]))
        else:
            v = float(r["runtime_s"])
            out[k] = dict(n=1, mean=v, std=np.nan, min=v, max=v)
    return out


def sc2_speedups(put, totals, reps=None):
    """How many streams the proposal is the fastest arm on, and each baseline's time over the
    proposal's per database, from the values the SC2 table prints (means where three runs exist)."""
    S = sc2_stats(totals, reps)
    dbs = sorted({d for d, _ in S})
    fastest = [d for d in dbs if S.get((d, P)) and all(
        S[(d, a)] is None or S[(d, a)]["mean"] > S[(d, P)]["mean"] for a in SC2_ARMS if a != P and (d, a) in S)]
    put("SC2_proposal_fastest", f"{len(fastest)}/{len(dbs)}", "table_SC2_totals.csv")
    for a in SC2_ARMS:
        if a == P:
            continue
        r = {d: S[(d, a)]["mean"] / S[(d, P)]["mean"] for d in dbs
             if S.get((d, a)) and S.get((d, P))}
        if not r:
            continue
        for d, v in r.items():
            put(f"SC2_{a}_over_proposal_{d}", f"{v:.2f}", "table_SC2_totals.csv")
        lo, hi = min(r, key=r.get), max(r, key=r.get)
        put(f"SC2_{a}_over_proposal_min", f"{r[lo]:.2f} ({lo})", "table_SC2_totals.csv")
        put(f"SC2_{a}_over_proposal_max", f"{r[hi]:.2f} ({hi})", "table_SC2_totals.csv")
        put(f"SC2_{a}_over_proposal_databases", len(r), "table_SC2_totals.csv")
    second = {d: min((S[(d, b)]["mean"] for b in SC2_ARMS if b != P and S.get((d, b))), default=None)
              for d in dbs}
    # per database: the runner-up, its time over the proposal's, the runs behind both, and
    # whether every run of the proposal is faster than every run of the runner-up
    for d in dbs:
        if not S.get((d, P)):
            continue
        rivals = {b: S[(d, b)] for b in SC2_ARMS if b != P and S.get((d, b))}
        if not rivals:
            continue
        b = min(rivals, key=lambda x: rivals[x]["mean"])
        if S[(d, P)]["n"] < 2 or rivals[b]["n"] < 2:
            sep = "not comparable, a side has one run"
        else:
            sep = "separated" if S[(d, P)]["max"] < rivals[b]["min"] else "overlapping"
        put(f"SC2_runner_up_{d}", f"{rivals[b]['mean'] / S[(d, P)]['mean']:.2f} ({b}; runs {S[(d, P)]['n']} "
            f"and {rivals[b]['n']}; {sep})", "table_SC2_totals.csv")
    rr = {d: second[d] / S[(d, P)]["mean"] for d in dbs if second.get(d) and S.get((d, P))}
    lo, hi = min(rr, key=rr.get), max(rr, key=rr.get)
    put("SC2_runner_up_over_proposal_min", f"{rr[lo]:.2f} ({lo})", "table_SC2_totals.csv")
    put("SC2_runner_up_over_proposal_max", f"{rr[hi]:.2f} ({hi})", "table_SC2_totals.csv")


def sc2_isolation(totals, reps=None):
    """SC2 with one component of the proposal changed at a time, beside the proposal and
    Pre-HUSPM: per database and arm, the time over the 100 increments (the mean of the runs
    where there are several, as in the SC2 table), its runs, the rescans, the tracked set after
    the last increment (HUSPs plus pre-large patterns), and the time split into the increments
    that rescanned and those that took the direct update (means of the runs per increment,
    load_mean; which increments rescanned is read from the counters of the main run)."""
    S = sc2_stats(totals, reps)
    arms = [P] + ISOLATION_ARMS + ["Pre-HUSPM-SWUmax", "Pre-HUSPM-TSU"]
    df = mf.load_mean("SC2")
    if df is None:
        return pd.DataFrame()
    inc = df[mf.is_inc(df)]
    rows = []
    for d in [x for x in mf.ALL_DATASETS if x in set(inc["dataset"])]:
        for a in arms:
            g = inc[(inc["dataset"] == d) & (inc["algorithm"] == a)].sort_values("iteration")
            if g.empty or (d, a) not in S:
                continue
            st = S[(d, a)]
            rc = g["rescan_count"].astype(float)
            rescanned = rc.diff().fillna(rc) > 0
            t = mf.ms_to_s(g["runtime_ms"])
            last = g.iloc[-1]
            rows.append(dict(dataset=d, algorithm=a, runs=st["n"] if st else np.nan,
                             time_s=st["mean"] if st else np.nan, time_s_std=st["std"] if st else np.nan,
                             finished=st is not None, rescans=int(rescanned.sum()),
                             rescan_time_s=float(t[rescanned].sum()), direct_time_s=float(t[~rescanned].sum()),
                             tracked_after_last=int(last["total_patterns_found"] + last["prelarge_count"])
                             if st else np.nan))
    return pd.DataFrame(rows)


def sc2_isolation_keys(put, iso):
    src = "table_SC2_rule_isolation.csv"
    for r in iso.itertuples():
        if not r.finished:
            continue
        put(f"SC2_isolation_{r.algorithm}_{r.dataset}_time", f"{r.time_s:.2f}", src)
        put(f"SC2_isolation_{r.algorithm}_{r.dataset}_rescans", r.rescans, src)
        put(f"SC2_isolation_{r.algorithm}_{r.dataset}_tracked", f"{r.tracked_after_last:,}", src)
        put(f"SC2_isolation_{r.algorithm}_{r.dataset}_rescan_s", f"{r.rescan_time_s:.2f}", src)
        put(f"SC2_isolation_{r.algorithm}_{r.dataset}_direct_s", f"{r.direct_time_s:.2f}", src)
    T = {(r.dataset, r.algorithm): r for r in iso.itertuples() if r.finished}
    for d in sorted({r.dataset for r in iso.itertuples()}):
        pr = T.get((d, P))
        if pr is None:
            continue
        # the rule: the proposal's miner and tracking behind each Pre-HUSPM test, against the proposal
        for a, pre in (("Joint-miner+SWUmax", "Pre-HUSPM-SWUmax"), ("Joint-miner+TSU", "Pre-HUSPM-TSU")):
            x, y = T.get((d, a)), T.get((d, pre))
            if x is not None:
                put(f"SC2_isolation_rule_{a}_minus_proposal_{d}_s", f"{x.time_s - pr.time_s:.2f}", src)
                put(f"SC2_isolation_rule_{a}_over_proposal_{d}", f"{x.time_s / pr.time_s:.2f}", src)
            if x is not None and y is not None:
                # what remains between Pre-HUSPM and the same test on the proposal's miner and tracking
                put(f"SC2_isolation_rest_{pre}_minus_{a}_{d}_s", f"{y.time_s - x.time_s:.2f}", src)
                put(f"SC2_isolation_rest_{pre}_over_{a}_{d}", f"{y.time_s / x.time_s:.2f}", src)
        # the tracking: the joint rule tracking by the bound, against the proposal
        x = T.get((d, "Joint-rule+SWU-tracking"))
        if x is not None:
            put(f"SC2_isolation_tracking_minus_proposal_{d}_s", f"{x.time_s - pr.time_s:.2f}", src)
            put(f"SC2_isolation_tracking_over_proposal_{d}", f"{x.time_s / pr.time_s:.2f}", src)
            put(f"SC2_isolation_tracking_tracked_over_proposal_{d}",
                f"{x.tracked_after_last / pr.tracked_after_last:.2f}" if pr.tracked_after_last else "", src)


def sc2_tex(totals, region, reps=None):
    """The SC2 runtime and rescan-rule tables. A cell measured three times (reps, from
    table_SC2_replicates.csv) prints the mean and standard deviation of its runs; the
    others print their one run. The lowest time of each row and the fewest rescans of the
    three rules are set in bold; a run that did not finish takes no part."""
    T = {(r["dataset"], r["algorithm"]): r for _, r in totals.iterrows()}
    S = sc2_stats(totals, reps)
    op = region.set_index("dataset")
    order = [d for d in ["SIGN", "TAFENG", "C8T1S5I8N5K", "BIBLE", "LEVIATHAN", "FIFA", "KOSARAK"] if d in op.index]

    def stat(d, a):
        return S.get((d, a))

    def row(d, arms):
        st = {a: stat(d, a) for a in arms}
        low = dict(zip(arms, mf.lowest([f"{st[a]['mean']:.{mf.pm_digits(st[a]['mean'])}f}"
                                        if st[a] else None for a in arms],
                                       [(st[a]["min"], st[a]["max"], st[a]["n"]) if st[a] else None
                                        for a in arms])))
        cells = []
        for a in arms:
            r = T.get((d, a))
            if r is None:
                cells.append("--")
            elif st[a] is None:
                # "Skip" with no failed row before it is the harness's memory guard: after a
                # full collection less than a tenth of the heap was free, so the arm was
                # stopped before the next increment rather than left to run out of memory
                why = "Stopped" if r.get("first_failure") == "Skip" else str(r.get("first_failure"))
                cells.append(f"{why} ({int(r['increments_done'])}/{int(r['increments'])})")
            else:
                cells.append(mf.pm(st[a], bold=low[a]))
        return cells

    head = ("Database & \\begin{tabular}[b]{@{}r@{}}Pre-HUSPM-\\\\Joint\\end{tabular} & "
            "\\begin{tabular}[b]{@{}r@{}}Pre-HUSPM-\\\\SWUmax\\end{tabular} & "
            "\\begin{tabular}[b]{@{}r@{}}Pre-HUSPM-\\\\TSU\\end{tabular} & Remine & "
            "\\begin{tabular}[b]{@{}r@{}}IncUSP-\\\\Miner$^{+}$\\end{tabular} \\\\\\midrule")
    rows = [f"{d} & " + " & ".join(row(d, INC_ARMS)) + " \\\\" for d in order]
    time_tex = "\n".join(["\\begin{table}[!htbp]\\centering",
                          "\\caption{Runtime (s) of SC2 over 100 increments; mean and standard deviation where "
                          "three runs were made}\\label{tab:sc2_time}",
                          "\\setlength{\\tabcolsep}{4pt}",
                          "\\begin{tabular*}{\\textwidth}{@{\\extracolsep\\fill}lrrrrr}\\toprule", head] + rows +
                         ["\\bottomrule\\end{tabular*}", "\\end{table}", ""])
    # Remining in three ways beside the proposal, in a table of its own: with the two static
    # miners the runtime table is wider than the text (measured: 89 pt over). None when
    # neither remining arm with a static miner has a row.
    remine_arms = [P, REM, "Remine-HUSP-ULL", "Remine-HUSP-SP"]
    remine_tex = None
    if any((d, a) in T for d in order for a in remine_arms[2:]):
        rhead = ("Database & \\begin{tabular}[b]{@{}r@{}}Pre-HUSPM-\\\\Joint\\end{tabular} & "
                 "\\begin{tabular}[b]{@{}r@{}}Remine\\\\(Pre-HUSPM-Joint)\\end{tabular} & "
                 "\\begin{tabular}[b]{@{}r@{}}Remine\\\\(HUSP-ULL)\\end{tabular} & "
                 "\\begin{tabular}[b]{@{}r@{}}Remine\\\\(HUSP-SP)\\end{tabular} \\\\\\midrule")
        rrows = [f"{d} & " + " & ".join(row(d, remine_arms)) + " \\\\" for d in order]
        remine_tex = "\n".join(["\\begin{table}[!htbp]\\centering",
                                "\\caption{Runtime (s) of SC2 against remining after every increment; mean and "
                                "standard deviation where three runs were made}\\label{tab:sc2_remine}",
                                "\\begin{tabular*}{\\textwidth}{@{\\extracolsep\\fill}lrrrr}\\toprule", rhead] + rrows +
                               ["\\bottomrule\\end{tabular*}", "\\end{table}", ""])
    def rescans(d):
        v = [str(int(op.loc[d, c])) for c in ("rescans", "rescans_swumax", "rescans_tsu")]
        return " & ".join(f"\\textbf{{{x}}}" if b else x for x, b in zip(v, mf.lowest(v)))
    rows = [f"{d} & {100 * op.loc[d, 'alpha_median']:.1f} & {100 * op.loc[d, 'alpha_min']:.1f}--{100 * op.loc[d, 'alpha_max']:.1f} & "
            f"{op.loc[d, 'gain_at_decisions']:.2f} & "
            f"{100 * op.loc[d, 'ratio_median']:.0f} & {int(op.loc[d, 'within_budget'])} & {rescans(d)} \\\\"
            for d in order]
    rule_tex = "\n".join(["\\begin{table}[!htbp]\\centering",
                          "\\caption{Rescan rule in SC2}\\label{tab:sc2_rule}",
                          "\\setlength{\\tabcolsep}{4pt}",
                          "\\begin{tabular*}{\\textwidth}{@{\\extracolsep\\fill}lrrrrrrrr}\\toprule",
                          " & \\multicolumn{2}{c}{$\\alpha$ (\\%)} & & & & \\multicolumn{3}{c}{Rescans} \\\\\\cmidrule(lr){2-3}\\cmidrule(l){7-9}",
                          "Database & Median & Range & Gain & \\begin{tabular}[b]{@{}r@{}}Median\\\\$\\Phi/\\beta$ (\\%)\\end{tabular} & "
                          "\\begin{tabular}[b]{@{}r@{}}No\\\\rescan\\end{tabular} & Joint & SWUmax & TSU \\\\\\midrule"]
                         + rows + ["\\bottomrule\\end{tabular*}", "\\end{table}", ""])
    return time_tex, rule_tex, remine_tex


def sc7_static_runtime(put, t7):
    """SC7, one mine of the whole database: each variant's mean runtime against the full
    miner's, per database, and whether the runs of the two separate."""
    runs = {}
    df = mf.load_scenario("SC7", replicates=True)
    if df is not None:
        for (d, a), g in df[df["finished"]].groupby(["dataset", "algorithm"]):
            runs[(d, a)] = mf.ms_to_s(g["runtime_ms"]).tolist()
    arm = {"Full": "proposed_full", "-IUB": "proposed_no_IUB", "-IPS": "proposed_no_IPS", "Pre-HUSPM": "Pre-HUSPM"}
    full = t7[t7["variant"] == "Full"].set_index("dataset")["runtime_s"]
    for v in ("-IUB", "-IPS", "Pre-HUSPM"):
        x = t7[t7["variant"] == v].set_index("dataset")["runtime_s"]
        tag = v.strip("-").replace("-", "_")
        slower, faster, state = {}, {}, {}
        for d in x.index.intersection(full.index):
            if not (pd.notna(x[d]) and pd.notna(full[d]) and full[d] > 0):
                continue
            ratio = x[d] / full[d]
            (slower if ratio > 1 else faster)[d] = ratio
            rv, rf = runs.get((d, arm[v]), []), runs.get((d, arm["Full"]), [])
            state[d] = ("one run" if len(rv) < 2 or len(rf) < 2 else
                        "separated" if max(rv) < min(rf) or max(rf) < min(rv) else "overlapping")
            put(f"SC7_{tag}_over_full_{d}", f"{ratio:.2f} ({state[d]})", "table_SC7_static.csv")
            put(f"SC7_{tag}_{d}_s", f"{half_up(x[d])} vs {half_up(full[d])}", "table_SC7_static.csv")
        if slower:
            lo, hi = min(slower, key=slower.get), max(slower, key=slower.get)
            put(f"SC7_{tag}_slower_on", f"{len(slower)} ({slower[lo]:.2f} {lo} to {slower[hi]:.2f} {hi})",
                "table_SC7_static.csv")
        put(f"SC7_{tag}_faster_on", "; ".join(f"{d} {r:.2f}" for d, r in sorted(faster.items())) or "none",
            "table_SC7_static.csv")
        put(f"SC7_{tag}_runs_overlapping", "; ".join(d for d, st_ in sorted(state.items()) if st_ == "overlapping")
            or "none", "table_SC7_static.csv")


def half_up(value, digits=2):
    """value to the given decimals, rounded half up on its decimal string: in binary 8.995
    lies just below itself and would print 8.99."""
    from decimal import Decimal, ROUND_HALF_UP
    return format(Decimal(repr(float(value))).quantize(Decimal(1).scaleb(-digits), rounding=ROUND_HALF_UP), "f")


def pct4(value):
    """A ratio written as a percentage with four significant digits, rounded half up on its
    decimal string (not on the binary float)."""
    from decimal import Decimal, ROUND_HALF_UP
    d = Decimal(str(value)) * 100
    exp = d.adjusted() - 3                       # keep four significant digits
    q = d.quantize(Decimal(1).scaleb(exp), rounding=ROUND_HALF_UP)
    return format(q, "f")


def memory_guard_stops(put):
    """SC2 arms stopped by the harness's memory guard (AutomatedExperimentRunner, MEM_SAFE_FRACTION):
    the increment after which they stopped and the heap free at that point, read from the
    warning the guard writes to run.err."""
    for tree in (mf.TIMING_TREE, mf.MEMORY_TREE):
        for err in sorted(glob.glob(os.path.join(mf.DATA_DIR, tree, "SC2", "*", "*", "run.err"))):
            m = re.search(r"protect the main thread \[(.+?)/(.+?)/SC2_INC\] availableMB=(\d+)",
                          open(err, encoding="utf-8", errors="replace").read())
            if not m:
                continue
            algo, ds, free = m.group(1), m.group(2), int(m.group(3))
            h = re.search(r"Heap max: (\d+) MB", open(os.path.join(os.path.dirname(err), "run.log"),
                                                     encoding="utf-8", errors="replace").read())
            if not h:
                raise ValueError(f"no heap size in the run.log next to {err}")
            heap_mb = int(h.group(1))
            csv = glob.glob(os.path.join(os.path.dirname(err), "*.csv"))[0]
            rows = pd.read_csv(csv)
            done = int((rows["op_type"].astype(str) == "SC2_INC").sum())
            src = os.path.relpath(err, mf.DATA_DIR).replace(os.sep, "/")
            k = f"SC2_{'mem_' if tree == mf.MEMORY_TREE else ''}{ds}_{algo}_guard"
            put(f"{k}_increments_done", done, src)
            put(f"{k}_free_mb", free, src)
            put(f"{k}_free_pct", f"{100 * free / heap_mb:.1f}", src)
            put(f"{k}_live_gb", f"{(heap_mb - free) / 1024:.1f}", src)


def bms1_whole(put):
    """BMS1 on the whole database, the reason it is measured in SC1 alone."""
    d = os.path.join(mf.DATA_DIR, "thresholds", "bms1-whole")
    path = os.path.join(d, "thresholds-pc-thai.csv")
    if not os.path.exists(path):
        print(f"[missing] {path}: no BMS1 whole-database keys")
        return
    src = "thresholds/bms1-whole"
    for _, r in pd.read_csv(path).iterrows():
        tag = pct4(r["uut"])
        if r["status"] == "ok":
            put(f"BMS1_whole_uut_{tag}_patterns", int(r["patterns"]), src)
        else:
            put(f"BMS1_whole_uut_{tag}_status", r["status"], src)
    first = open(os.path.join(d, "console.txt"), encoding="utf-8").readline()
    m = re.match(r"budgets: ([\d,]+) candidates", first)
    if not m:
        raise ValueError(f"no candidate budget in the first line of {d}/console.txt: {first!r}")
    put("BMS1_whole_candidate_budget", m.group(1), src)


def export_all(out_dir=None):
    out = out_dir or mf.OUT_DIR
    os.makedirs(out, exist_ok=True)
    nums = []

    def put(key, value, source):
        nums.append(dict(key=key, value=value, source=source))

    summ, geo = static_tables()
    mf.write_csv(summ, os.path.join(out, "table_static_summary.csv"))
    mf.write_csv(geo, os.path.join(out, "table_static_geomean.csv"))
    for _, r in summ.iterrows():
        k = f"{r['scenario']}_{'all' if r['scope'].startswith('all') else 'default'}"
        put(f"{k}_proposal_fastest", f"{r['proposal_fastest']}/{r['cells']}", "table_static_summary.csv")
        # a win counts as separated when every run of the proposal beats every run of the
        # fastest baseline; a tie is a cell whose runs overlap those of the fastest baseline
        put(f"{k}_proposal_fastest_separated", f"{r['proposal_fastest_separated']}/{r['cells']}",
            "table_static_summary.csv")
        put(f"{k}_ties_with_fastest_baseline", f"{r['ties']}/{r['cells']}", "table_static_summary.csv")
        if r["tie_cells"]:
            put(f"{k}_tie_cells", r["tie_cells"], "table_static_summary.csv")
        put(f"{k}_pairs_agree", f"{r['pairs_agree']}/{r['pairs']}", "table_static_summary.csv")
    for _, r in geo.iterrows():
        k = f"{r['scenario']}_{'all' if r['scope'].startswith('all') else 'default'}"
        put(f"{k}_geomean_{r['baseline']}", f"{r['geomean_over_proposal']:.2f}", "table_static_geomean.csv")
        put(f"{k}_baseline_faster_{r['baseline']}", f"{r['cells_baseline_faster']}/{r['cells']}",
            "table_static_geomean.csv")
        if r["baseline_faster_on"]:
            put(f"{k}_baseline_faster_on_{r['baseline']}", r["baseline_faster_on"], "table_static_geomean.csv")

    c4 = sc4_cells()
    mf.write_csv(c4, os.path.join(out, "table_SC4_cells.csv"))
    rr = sc4_ratio(c4)
    if len(rr):
        put("SC4_prelarge_ratio_min", f"{rr.min():.0f}", "table_SC4_cells.csv")
        put("SC4_prelarge_ratio_max", f"{rr.max():.0f}", "table_SC4_cells.csv")
        put("SC4_prelarge_ratio_cells", len(rr), "table_SC4_cells.csv")

    m2 = sc2_memory()
    mf.write_csv(m2, os.path.join(out, "table_SC2_memory.csv"))
    if len(m2):
        lead = m2[m2["least_retained"]]
        put("SC2_memory_proposal_least", f"{int((lead['algorithm'] == P).sum())}/{lead['dataset'].nunique()}",
            "table_SC2_memory.csv")
        sc2_memory_keys(put, m2)

    # SC1 live set, each baseline over the proposal: geometric mean, and the cells where the
    # baseline holds less or more, split by whether its measurements and the proposal's
    # overlap (two measurements per cell where the live set was measured twice)
    mr = mf.memory_ratios()
    if mr is not None:
        for r in mr.itertuples():
            k = f"SC1_mem_{'all' if r.scope.startswith('all') else 'default'}_{r.baseline}"
            src = "table_SC1_memory_ratios.csv"
            put(f"{k}_geomean", f"{r.geomean_ratio:.3g}", src)
            put(f"{k}_cells_used", f"{r.cells_used}/{r.cells}", src)
            put(f"{k}_holds_less", f"{r.baseline_holds_less}/{r.cells_used}", src)
            for col in ("less_separated", "less_overlapping", "less_one_run",
                        "more_separated", "more_overlapping", "more_one_run", "equal"):
                put(f"{k}_{col}", int(getattr(r, col)), src)
            if r.overlapping_cells:
                put(f"{k}_overlapping_cells", r.overlapping_cells, src)

    c5 = sc5_cells()
    mf.write_csv(c5, os.path.join(out, "table_SC5_cells.csv"))
    if len(c5):
        put("SC5_proposal_fastest", f"{int((c5['fastest'] == P).sum())}/{len(c5)}", "table_SC5_cells.csv")
        for state in ("separated", "overlapping", "behind", "one run"):
            put(f"SC5_runs_vs_closest_{state.replace(' ', '_')}", f"{int((c5['runs_vs_closest'] == state).sum())}/{len(c5)}",
                "table_SC5_cells.csv")
        near = c5[c5["closest_over_proposal"] < 1.10]
        put("SC5_cells_within_10pct", f"{len(near)}/{len(c5)}", "table_SC5_cells.csv")
        if len(near):
            put("SC5_closest_over_proposal_min", f"{near['closest_over_proposal'].min():.3f}", "table_SC5_cells.csv")
            put("SC5_cells_within_10pct_runs", "; ".join(sorted(set(near["runs_vs_closest"]))), "table_SC5_cells.csv")
        # median over the four shapes of Remine's time over the proposal's, per database and K,
        # and the share of increments the proposal rescans
        for (d, k), g in c5.groupby(["dataset", "K"]):
            put(f"SC5_{d}_K{k}_remine_over_proposal_median", f"{g['remine_over_proposal'].median():.2f}",
                "table_SC5_cells.csv")
            put(f"SC5_{d}_K{k}_rescan_share_range", f"{100 * g['proposal_rescan_share'].min():.0f}-"
                f"{100 * g['proposal_rescan_share'].max():.0f}%", "table_SC5_cells.csv")
        n5 = c5[c5["closest_over_proposal"] < 1.05].sort_values("closest_over_proposal")
        put("SC5_cells_within_5pct", f"{len(n5)}/{len(c5)}", "table_SC5_cells.csv")
        if len(n5):
            put("SC5_cells_within_5pct_range", f"{n5['closest_over_proposal'].min():.3f} to "
                f"{n5['closest_over_proposal'].max():.3f}", "table_SC5_cells.csv")
            put("SC5_cells_within_5pct_list", "; ".join(f"{r.dataset} K={r.K} {r.shape.lower()} {r.runs_vs_closest}"
                                                       for r in n5.itertuples()), "table_SC5_cells.csv")

    o6 = sc6_by_op()
    mf.write_csv(o6, os.path.join(out, "table_SC6_by_op.csv"))

    # The mixed streams with every remining arm: SC6 (table_SC6_by_op_arms.csv beside the
    # table above, which keeps its form), SC6D and SC6B.
    for sc, fname in (("SC6", "table_SC6_by_op_arms.csv"), ("SC6D", "table_SC6D_by_op.csv"),
                      ("SC6B", "table_SC6B_by_op.csv")):
        path = os.path.join(out, fname)
        mx = mixed_by_op(sc)
        if not len(mx):
            if os.path.exists(path):
                os.remove(path)
            print(f"[not written] {fname}: no finished arm of {sc}")
            continue
        mf.write_csv(mx, path)
        for _, r in mx.iterrows():
            kd = r["kind"].replace(" ", "_")
            base = f"{sc}_{r['dataset']}_{kd}"
            if r["kind"] != "stream":
                put(f"{base}_rescans", f"{r['rescans']}/{r['operations']}", fname)
                put(f"{base}_direct", f"{r['direct']}/{r['operations']}", fname)
            for arm in ("Remine", "Remine-HUSP-ULL", "Remine-HUSP-SP"):
                key = "proposal_over" if r["kind"] == "stream" else "over_proposal"
                m, sd, n = r.get(f"{key}_{arm}_mean"), r.get(f"{key}_{arm}_sd"), r.get(f"{key}_{arm}_seeds")
                if pd.notna(m):
                    name = f"{base}_proposal_over_{arm}" if r["kind"] == "stream" else f"{base}_{arm}_over_proposal"
                    put(name, f"{m:.2f}" + (f" ± {sd:.2f}" if pd.notna(sd) else "") + f" ({int(n)} seeds)", fname)
                if r["kind"] == "deletion beyond the budget" and pd.notna(r.get(f"{arm}_s_mean")):
                    put(f"{base}_{arm}_s", f"{r[f'{arm}_s_mean']:.3f}", fname)
            if r["kind"] == "deletion beyond the budget" and pd.notna(r["proposal_s_mean"]):
                put(f"{base}_proposal_s", f"{r['proposal_s_mean']:.3f}", fname)

    b7 = sc7_block_index()
    mf.write_csv(b7, os.path.join(out, "table_SC7_block_index.csv"))
    for _, r in b7.iterrows():
        put(f"SC7_block_index_{r['dataset']}_ms", f"{r['with_index_ms']:.0f}/{r['without_index_ms']:.0f}",
            "table_SC7_block_index.csv")
        put(f"SC7_block_index_{r['dataset']}_sequences_read",
            f"{r['sequences_read_with']}/{r['sequences_read_without']}", "table_SC7_block_index.csv")

    bs = sc7_block_size()
    bs_path = os.path.join(out, "table_SC7_block_size.csv")
    if not len(bs):
        if os.path.exists(bs_path):
            os.remove(bs_path)
        print("[not written] table_SC7_block_size.csv: no proposed_bsz_<n> arm in results/campaign/SC7")
    else:
        mf.write_csv(bs, bs_path)
        put("SC7_block_size_nested_monotone", f"{bs.attrs['nested_pairs_monotone']}/{bs.attrs['nested_pairs']}",
            "table_SC7_block_size.csv")
        eq = bs["counts_equal"].str.split("/", expand=True).astype(int)
        put("SC7_block_size_counts_equal", f"{int((eq[0] == eq[1]).sum())}/{len(bs)}", "table_SC7_block_size.csv")
        for _, r in bs.iterrows():
            put(f"SC7_block_size_{r['dataset']}_{r['block_size']}_sequences_read", r["sequences_read"],
                "table_SC7_block_size.csv")
            put(f"SC7_block_size_{r['dataset']}_{r['block_size']}_direct_ms", f"{r['direct_ms']:.0f}",
                "table_SC7_block_size.csv")

    vs, vd = verification()
    mf.write_csv(vs, os.path.join(out, "table_verification.csv"))
    mf.write_csv(vd, os.path.join(out, "table_verification_by_database.csv"))
    for _, r in vs.iterrows():
        key = "verify_" + re.sub(r"[^a-z0-9]+", "_", r["check"].lower()).strip("_")
        put(f"{key}_status", r["status"], "table_verification.csv")
        for col in ("operations", "with_target", "direct_delete", "direct_insert", "direct_modify", "mismatches",
                    "caught"):
            if col in r and pd.notna(r[col]):
                put(f"{key}_{col}", int(r[col]), "table_verification.csv")
        parts = [r.get(c) for c in ("direct_delete", "direct_insert", "direct_modify")]
        if all(pd.notna(v) for v in parts):
            put(f"{key}_direct_total", int(sum(parts)), "table_verification.csv")

    # SC7 over the incremental stream: the time of each variant summed over the 100 increments,
    # and what the block index saves
    st7i = os.path.join(out, "table_SC7_incremental.csv")
    if os.path.exists(st7i):
        t7i = pd.read_csv(st7i)
        short = {"Full": "full", "-C5 (rescan every increment)": "rescan_every_increment",
                 "-C4 (no block index)": "no_block_index"}
        for ds, g in t7i.groupby("dataset"):
            v = {short[x]: float(y) for x, y in zip(g["variant"], g["runtime_s"]) if x in short}
            for k, y in v.items():
                put(f"SC7_stream_{ds}_{k}_s", half_up(y), "table_SC7_incremental.csv")
            if "full" in v and "no_block_index" in v:
                put(f"SC7_stream_{ds}_block_index_saves_s", half_up(round(v["no_block_index"] - v["full"], 9)),
                    "table_SC7_incremental.csv")

    ex = explored_one_convention()
    ex_path = os.path.join(out, "table_explored_nodes.csv")
    if ex.empty or ex["missing"].all():
        if os.path.exists(ex_path):
            os.remove(ex_path)
        print(f"[not written] table_explored_nodes.csv: no root counts in {ROOT_ITEMS}")
    else:
        if ex["missing"].any():
            print("[warning] table_explored_nodes.csv: no root count for "
                  + ", ".join(f"{r.scenario} {r.dataset} {r.su:g}" for r in ex[ex["missing"]].itertuples()))
        mf.write_csv(ex, ex_path)
        # at the default threshold: the root items the node bound stops at once, the proposal's
        # count with them, and each baseline's count over it (the convention of the baselines
        # leaves them out; these keys let a note give the other reading)
        for r in ex[ex["default"] & ~ex["missing"]].itertuples():
            k = f"{r.scenario}_{r.dataset}_default"
            put(f"{k}_root_stopped", int(r.root_stopped), "table_explored_nodes.csv")
            put(f"{k}_proposal_explored_with_root", int(r.proposal_explored), "table_explored_nodes.csv")
            put(f"{k}_proposal_explored", int(r.proposal_explored_bound), "table_explored_nodes.csv")
            for a in mf.STATIC_ORDER:
                col = f"explored_{a}"
                if a == P or col not in ex.columns or pd.isna(ex.loc[r.Index, col]):
                    continue
                put(f"{k}_proposal_with_root_over_{a}", f"{r.proposal_explored / ex.loc[r.Index, col]:.2f}",
                    "table_explored_nodes.csv")
        for sc in ("SC1", "SC3"):
            for scope, g in (("default", ex[(ex.scenario == sc) & ex["default"]]), ("all", ex[ex.scenario == sc])):
                for a in ("HUSP-SP", "HUSP-ULL", "Pre-HUSPM"):
                    v = g[f"ratio_{a}"].dropna()
                    if len(v):
                        lo, hi = g.loc[v.idxmin()], g.loc[v.idxmax()]
                        put(f"{sc}_{scope}_explored_{a}_over_proposal_min", f"{v.min():.2f} ({lo['dataset']})",
                            "table_explored_nodes.csv")
                        put(f"{sc}_{scope}_explored_{a}_over_proposal_max", f"{v.max():.2f} ({hi['dataset']})",
                            "table_explored_nodes.csv")
                        inv = 1.0 / v
                        lo, hi = g.loc[inv.idxmin()], g.loc[inv.idxmax()]
                        put(f"{sc}_{scope}_explored_proposal_over_{a}_min", f"{inv.min():.2f} ({lo['dataset']})",
                            "table_explored_nodes.csv")
                        put(f"{sc}_{scope}_explored_proposal_over_{a}_max", f"{inv.max():.2f} ({hi['dataset']})",
                            "table_explored_nodes.csv")
    st7 = os.path.join(out, "table_SC7_static.csv")
    if os.path.exists(st7):
        t7 = pd.read_csv(st7)
        if "explored_bound" in t7:
            f = t7[t7["variant"] == "Full"].set_index("dataset")["explored_bound"]
            n = t7[t7["variant"] == "-IPS"].set_index("dataset")["explored_bound"]
            r = (n / f).dropna()
            if len(r):
                put("SC7_no_IPS_explored_over_full_min", f"{r.min():.2f} ({r.idxmin()})", "table_SC7_static.csv")
                put("SC7_no_IPS_explored_over_full_max", f"{r.max():.2f} ({r.idxmax()})", "table_SC7_static.csv")
        sc7_static_runtime(put, t7)

    # The keys the manuscript asked for by name.
    def row_of(prefix):
        hit = vs[vs["check"].str.startswith(prefix)]
        return hit.iloc[0] if len(hit) else None
    r = row_of("item pruning against exhaustive enumeration")
    if r is not None and r["status"] == "finished":
        put("verify_ips_vs_exhaustive_databases", int(r["runs"]), "table_verification.csv")
        put("verify_ips_vs_exhaustive_databases_with_patterns", int(r["operations"]), "table_verification.csv")
        put("verify_ips_vs_exhaustive_mismatches", int(r["mismatches"]), "table_verification.csv")
    for scope in ("init", "whole"):
        r = row_of(f"item pruning on and off on real databases, {scope}")
        if r is not None and r["status"] == "finished":
            put(f"verify_ips_vs_noips_real_{scope}_equal", f"{int(r['operations'])}/{int(r['runs'])}",
                "table_verification.csv")
    r = row_of("fault injection into the item pruning")
    if r is not None and r["status"] == "finished":
        put("verify_ips_faults_caught", f"{int(r['caught'])}/{int(r['operations'])}", "table_verification.csv")

    ag = agreement()
    mf.write_csv(ag, os.path.join(out, "table_agreement.csv"))
    for _, r in ag.iterrows():
        put(f"{r['scenario']}_count_agreement", f"{r['comparisons'] - r['disagreeing']}/{r['comparisons']}",
            "table_agreement.csv")

    df2 = mf.load_scenario("SC2")
    if df2 is not None:
        region = mf.operating_region(df2)
        totals = pd.read_csv(os.path.join(out, "table_SC2_totals.csv")) \
            if os.path.exists(os.path.join(out, "table_SC2_totals.csv")) else None
        if totals is not None and len(region):
            rp = os.path.join(out, "table_SC2_replicates.csv")
            reps = pd.read_csv(rp) if os.path.exists(rp) else None
            time_tex, rule_tex, remine_tex = sc2_tex(totals, region, reps)
            remine_path = os.path.join(out, "table_SC2_remine.tex")
            if remine_tex is None:
                if os.path.exists(remine_path):
                    os.remove(remine_path)
                print("[not written] table_SC2_remine.tex: no finished arm of Remine-HUSP-ULL or Remine-HUSP-SP")
            else:
                open(remine_path, "w", encoding="utf-8").write(remine_tex)
            sc2_speedups(put, totals, reps)
            iso = sc2_isolation(totals, reps)
            iso_path = os.path.join(out, "table_SC2_rule_isolation.csv")
            if len(iso) and set(iso["algorithm"]) & set(ISOLATION_ARMS):
                mf.write_csv(iso, iso_path)
                sc2_isolation_keys(put, iso)
            else:
                if os.path.exists(iso_path):
                    os.remove(iso_path)
                print("[not written] table_SC2_rule_isolation.csv: no finished arm of " + ", ".join(ISOLATION_ARMS))
            open(os.path.join(out, "table_SC2_time.tex"), "w", encoding="utf-8").write(time_tex)
            open(os.path.join(out, "table_SC2_rule.tex"), "w", encoding="utf-8").write(rule_tex)
            h = mf.rescan_hypothesis(region)
            put("SC2_prediction", h["verdict"], "table_SC2_operating_region.csv")
            # alpha and the gain of the corollary, at the rescan decisions (median, min, max over
            # the 100 decisions) and, for reference, over the whole insertion stream
            src = "table_SC2_operating_region.csv"
            for r in region.itertuples():
                put(f"SC2_alpha_decisions_{r.dataset}_median_pct", f"{100 * r.alpha_median:.1f}", src)
                put(f"SC2_alpha_decisions_{r.dataset}_range_pct", f"{100 * r.alpha_min:.1f} to {100 * r.alpha_max:.1f}", src)
                put(f"SC2_gain_decisions_{r.dataset}", f"{r.gain_at_decisions:.2f}", src)
                put(f"SC2_alpha_stream_{r.dataset}_pct", f"{100 * r.alpha_stream:.1f}", src)
                put(f"SC2_gain_stream_{r.dataset}", f"{r.joint_over_tsu:.2f}", src)
            # the registered prediction names the databases with the largest and the smallest
            # gain; are they still those when the gain is taken at the decisions?
            g = region.set_index("dataset")["gain_at_decisions"].dropna()
            k_l, k_s = len(mf.PREDICTED_LARGEST), len(mf.PREDICTED_SMALLEST)
            top = set(g.sort_values(ascending=False).index[:k_l])
            bottom = set(g.sort_values().index[:k_s])
            same = top == set(mf.PREDICTED_LARGEST) and bottom == set(mf.PREDICTED_SMALLEST)
            put("SC2_prediction_sets_from_decision_gain", "same" if same else
                f"differ: largest {sorted(top)}, smallest {sorted(bottom)}", src)
            pairs, more, raised = mf.rescan_order_check(region)
            put("SC2_joint_le_swumax", f"{len(pairs) - len(more)}/{len(pairs)}", "table_SC2_operating_region.csv")

    th = [dict(dataset=d, d_init=v["d_init"], default_uut=v["default_uut"], r_l=v["sl_ratio"],
               thresholds=" ".join(f"{x:.6f}" for x in v["sweep"])) for d, v in mf.DATASETS.items()]
    mf.write_csv(pd.DataFrame(th), os.path.join(out, "table_thresholds.csv"))
    lines = ["\\begin{table}[!htbp]\\centering", "\\caption{Thresholds of SC1 and SC3}\\label{tab:thresholds}",
             "\\begin{tabular*}{\\textwidth}{@{\\extracolsep\\fill}lrrrr}\\toprule",
             "Database & \\multicolumn{4}{c}{$\\UUT$ (\\%)} \\\\\\midrule"]
    for r in th:
        # In percent, four significant digits, rounded half up on the decimal value: in binary
        # 0.022895 * 100 falls just below 2.2895 and would print 2.289.
        lines.append(r["dataset"] + " & " + " & ".join(pct4(v) for v in r["thresholds"].split()) + " \\\\")
    lines += ["\\bottomrule\\end{tabular*}", "\\end{table}", ""]
    open(os.path.join(out, "table_thresholds.tex"), "w", encoding="utf-8").write("\n".join(lines))

    bms1_whole(put)
    memory_guard_stops(put)

    nz = noise()
    mf.write_csv(nz, os.path.join(out, "table_noise.csv"))
    for m, g in nz.groupby("measure"):
        key = m.replace(" ", "_")
        put(f"noise_{key}_cells", len(g), "table_noise.csv")
        # as a ratio to four decimals and as a percentage to one, both from the unrounded spread
        for stat, v in (("median", g["relative_spread"].median()), ("max", g["relative_spread"].max())):
            put(f"noise_{key}_{stat}", f"{v:.4f}", "table_noise.csv")
            put(f"noise_{key}_{stat}_pct", f"{100 * v:.1f}", "table_noise.csv")
        if m == "SC1 runtime":
            big = g[g["mean"] >= 1]
            put("noise_SC1_runtime_over_1s_cells", len(big), "table_noise.csv")
            for stat, v in (("median", big["relative_spread"].median()), ("max", big["relative_spread"].max())):
                put(f"noise_SC1_runtime_over_1s_{stat}", f"{v:.4f}", "table_noise.csv")
                put(f"noise_SC1_runtime_over_1s_{stat}_pct", f"{100 * v:.1f}", "table_noise.csv")
        if m == "SC1 live set":
            # below 5 MB the 1 MB resolution alone moves a value by 20% or more
            big = g[g["mean"] >= 5]
            put("noise_SC1_live_set_at_least_5mb_cells", len(big), "table_noise.csv")
            for stat, v in (("median", big["relative_spread"].median()), ("max", big["relative_spread"].max())):
                put(f"noise_SC1_live_set_at_least_5mb_{stat}", f"{v:.4f}", "table_noise.csv")
                put(f"noise_SC1_live_set_at_least_5mb_{stat}_pct", f"{100 * v:.1f}", "table_noise.csv")

    mf.write_csv(pd.DataFrame(nums), os.path.join(out, "paper_numbers.csv"))
    print(f"[saved] scenario tables and paper_numbers.csv ({len(nums)} numbers) in {out}")


if __name__ == "__main__":
    export_all()
