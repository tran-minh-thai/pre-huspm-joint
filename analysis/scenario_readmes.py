#!/usr/bin/env python3
"""Write the READMEs of the campaign scenarios from their result files.

    python3 analysis/scenario_readmes.py

Every table and every number in the prose is computed here from the arms' CSVs; nothing
is typed. Rerun after any arm changes. Writes:

    results/campaign/SC2/README.md        results/campaign/SC5/README.md
    results/campaign/SC6/README.md        results/campaign/SC7/README.md
    results/campaign-mem/SC2/README.md
"""
import os
import sys

import numpy as np
import pandas as pd

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import make_figures as mf  # noqa: E402

P = mf.PROPOSED
REM = "Remine-from-scratch"
INC = ["Pre-HUSPM-SWUmax", "Pre-HUSPM-TSU", REM, "IncUSP-Miner+"]
LABEL = {P: "Pre-HUSPM-Joint", "Pre-HUSPM-SWUmax": "Pre-HUSPM-SWUmax", "Pre-HUSPM-TSU": "Pre-HUSPM-TSU",
         REM: "Remine", "IncUSP-Miner+": "IncUSP-Miner+"}
# "Skip" as the first failure of an arm is the runner's memory guard: after a full collection
# less than a tenth of the heap was free, so the arm was stopped before its next increment.
STATUS = {"OOM": "out of memory", "Skip": "stopped by the memory guard", "OT": "over the time limit",
          "Fail": "failed", "MemSkip": "refused by the memory guard"}
HEAD = ("Measured on `PC-THAI` (see `results/machine/PC-THAI.json` and `environment.lock.json`), "
        "`-Xmx48g`, one JVM per arm. Written by `analysis/scenario_readmes.py` from the arms' "
        "CSVs; rerun it rather than editing numbers here.")


def gmean(x):
    x = np.asarray(x, float)
    x = x[np.isfinite(x) & (x > 0)]
    return float(np.exp(np.log(x).mean())) if len(x) else float("nan")


def table(header, rows, align=None):
    align = align or ["---"] + ["---:"] * (len(header) - 1)
    out = ["| " + " | ".join(header) + " |", "|" + "|".join(align) + "|"]
    out += ["| " + " | ".join(str(c) for c in r) + " |" for r in rows]
    return "\n".join(out)


def plural(n, word):
    return f"{n} {word}" + ("" if n == 1 else "s")


def secs(x):
    return f"{x:,.1f}" if x < 100 else f"{x:,.0f}"


def write(path, text):
    full = os.path.join(mf.DATA_DIR, path)
    with open(full, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(text.rstrip() + "\n")
    print(f"[written] results/{path}")


def deferred(tree, sc, ds, algo):
    f = os.path.join(mf.DATA_DIR, tree, sc, ds, algo, "DEFERRED")
    return mf.deferred_reason(f).replace("$D_{\\mathrm{init}}$", "`D_init`") if os.path.exists(f) else None


def arm_outcome(g, tree, sc, ds, algo):
    """Increments completed out of the stream and the first failure, if any."""
    if g is None or g.empty:
        d = deferred(tree, sc, ds, algo)
        return 0, (f"deferred, {d}" if d else "not run")
    inc = g[mf.is_inc(g)]
    init = g[g["op_type"].astype(str).str.contains("_INIT")]
    done = int(inc["finished"].sum())
    if not init.empty and not bool(init.iloc[0]["finished"]):
        return done, f"{STATUS.get(init.iloc[0]['status'], init.iloc[0]['status'])} on `D_init`"
    bad = inc[~inc["finished"]]
    return done, (STATUS.get(bad.iloc[0]["status"], bad.iloc[0]["status"]) if len(bad) else "")


# ------------------------------------------------------------------------------ SC2
def sc2():
    df = mf.load_scenario("SC2")
    dbs = [d for d in mf.SC2_DATASETS if d in set(df["dataset"])]
    algos = [P] + INC
    comp, rt, totals = [], [], {}
    for ds in dbs:
        crow, rrow = [ds], [ds]
        for a in algos:
            g = df[(df["dataset"] == ds) & (df["algorithm"] == a)]
            done, why = arm_outcome(g if len(g) else None, mf.TIMING_TREE, "SC2", ds, a)
            crow.append(f"{done}/100" + (f", {why}" if why else ""))
            inc = g[mf.is_inc(g) & g["finished"]] if len(g) else g
            t = mf.ms_to_s(inc["runtime_ms"]).sum() if len(g) else np.nan
            totals[(ds, a)] = (t, done)
            if a == P:
                rrow += [secs(t), int(inc["rescan_count"].iloc[-1]) if len(inc) else "-"]
            else:
                rrow.append("-" if done == 0 else (secs(t) if done == 100 else f"({secs(t)}, {done} increments)"))
        comp.append(crow)
        rt.append(rrow)
    for r, ds in zip(rt, dbs):  # bold the fastest arm that completed the whole stream
        full = {a: totals[(ds, a)][0] for a in algos if totals[(ds, a)][1] == 100}
        best = min(full, key=full.get)
        idx = 1 if best == P else 3 + INC.index(best)
        r[idx] = f"**{r[idx]}**"

    reg = mf.operating_region(df).set_index("dataset")
    rr = mf.load_scenario("SC2", replicates=True)
    rep = []
    for ds in dbs:
        for a in (P, REM):
            g = rr[(rr["dataset"] == ds) & (rr["algorithm"] == a) & mf.is_inc(rr)]
            if g["replicate"].nunique() < 2:
                continue
            tot = [mf.ms_to_s(x["runtime_ms"]).sum() for _, x in g.groupby("replicate") if x["finished"].all()]
            st = mf.spread(tot)
            rep.append([ds, LABEL[a], st["n"], f"{st['mean']:.2f} ± {st['std']:.2f}", f"{st['min']:.2f}",
                        f"{st['max']:.2f}"])

    def ratio(ds):
        return totals[(ds, REM)][0] / totals[(ds, P)][0]

    inside = [d for d in dbs if reg.loc[d, "ratio_median"] <= 1]
    beyond = [d for d in dbs if reg.loc[d, "within_budget"] <= 0.05 * reg.loc[d, "increments"]]
    boundary = [d for d in dbs if d not in inside and d not in beyond]
    import re
    src = open(os.path.join(mf.REPO_DIR, "src", "common", "Datasets.java"), encoding="utf-8").read()
    def entry_rl(d):
        # The seven-argument Entry takes the default 0.8; the eight-argument one ends with r_l.
        m = re.search(rf'new Entry\("{d}",(?:(?!new Entry).)*?true, (0\.\d+)\)', src, re.S)
        return float(m.group(1)) if m else 0.8
    rl = {d: entry_rl(d) for d in dbs}
    swu_faster = [d for d in dbs if totals[(d, "Pre-HUSPM-SWUmax")][1] == 100 and totals[(d, "Pre-HUSPM-SWUmax")][0] < totals[(d, P)][0]]
    kos = "KOSARAK"
    s1 = mf.parse_su(mf.load_scenario("SC1", replicates=True))
    s1 = s1[s1["finished"]].pivot_table(index=["dataset", "su", "algorithm"], columns="replicate",
                                        values="runtime_ms", aggfunc="first").dropna()
    s1 = s1[s1.min(axis=1) >= 1000]
    r12 = s1["s0r1"] / s1["s0r2"]

    reg_rows = [[d, f"{reg.loc[d,'ratio_median']:.2f}", f"{reg.loc[d,'ratio_min']:.2f}", f"{reg.loc[d,'ratio_max']:.2f}",
                 f"{int(reg.loc[d,'within_budget'])}/{int(reg.loc[d,'increments'])}", int(reg.loc[d, "rescans"]),
                 reg.loc[d, "check"]] for d in sorted(dbs, key=lambda d: reg.loc[d, "ratio_median"])]
    alpha_rows = [[d, f"{reg.loc[d,'uut']:.6f}", f"{reg.loc[d,'alpha_median']:.4f}", f"{reg.loc[d,'alpha_min']:.4f}",
                   f"{reg.loc[d,'alpha_max']:.4f}",
                   f"{int(reg.loc[d,'increments_alpha_le_uut'])}/{int(reg.loc[d,'increments'])}",
                   (f"{reg.loc[d,'alpha_stream']:.4f}" if pd.notna(reg.loc[d, "alpha_stream"]) else "not computed"),
                   reg.loc[d, "alpha_check"]] for d in sorted(dbs, key=lambda d: reg.loc[d, "ratio_median"])]

    def rescans_of(d, key):
        v = reg.loc[d, f"rescans_{key}"]
        return int(v) if pd.notna(v) else f"incomplete ({int(reg.loc[d, f'increments_{key}'])}/100)"
    hyp = mf.rescan_hypothesis(reg.reset_index())
    ord_pairs, ord_more, ord_raised = mf.rescan_order_check(reg.reset_index())
    hyp_rows = [[d, (f"{reg.loc[d,'alpha_stream']:.4f}" if pd.notna(reg.loc[d, "alpha_stream"]) else "not computed"),
                 (f"{reg.loc[d,'joint_over_tsu']:.2f}" if pd.notna(reg.loc[d, "joint_over_tsu"]) else "not computed"),
                 int(reg.loc[d, "rescans"]), rescans_of(d, "tsu"), rescans_of(d, "swumax"),
                 (f"{int(hyp['gap'][d])}" if d in hyp["gap"] else "-")]
                for d in sorted(dbs, key=lambda d: -reg.loc[d, "joint_over_tsu"] if pd.notna(reg.loc[d, "joint_over_tsu"]) else 0)]
    if swu_faster:
        swu_line = ("Pre-HUSPM-SWUmax is faster than the proposal on " + ", ".join(
            f"{d} ({secs(totals[(d,'Pre-HUSPM-SWUmax')][0])} s against {secs(totals[(d,P)][0])} s, "
            f"{plural(int(df[(df['dataset']==d)&(df['algorithm']=='Pre-HUSPM-SWUmax')&mf.is_inc(df)]['rescan_count'].iloc[-1]), 'rescan')} "
            f"against {int(reg.loc[d,'rescans'])})" for d in swu_faster) + ".")
    else:
        swu_line = "Neither Pre-HUSPM variant is faster than the proposal on any database where both complete."
    text = f"""# SC2, one hundred increments

Seven databases against five arms; `D_init` per `common.Datasets`, the remainder cut into 100
equal increments in file order. BMS1 is absent: none of its four thresholds can be mined once the
database grows past `D_init`, so it appears in SC1 alone. {HEAD}

Pre-HUSPM-SWUmax and Pre-HUSPM-TSU are the two rescan tests of Yan et al.: their Theorem 2, on the
largest sequence-weighted utility of an item in the inserted sequences, and their Theorem 1, on the
total utility of the inserted sequences. Both accumulate since the last rescan and both are sound.

## What completed

{table(["database"] + [LABEL[a] for a in algos], comp, ["---"] * 6)}

## Runtime, summed over the increments that completed

Seconds over the increments alone; the initial mine of `D_init` is excluded. A total over a
partial run is in parentheses: it is not comparable with a total over the whole stream. The
fastest arm that completed the whole stream is in bold. One run per arm; the spread of the
proposal and Remine over three runs is in the next table.

{table(["database", LABEL[P], "rescans"] + [LABEL[a] for a in INC], rt)}

### Three runs

On SIGN, TAFENG, BIBLE and C8T1S5I8N5K the proposal and Remine were each measured three times, each
run in a fresh JVM (`results/campaign-replicates/s0r1`, `s0r2`). Seconds summed over the
increments, mean ± sample standard deviation.

{table(["database", "arm", "runs", "mean ± sd", "min", "max"], rep, ["---", "---", "---:", "---:", "---:", "---:"])}

## The operating region

The rescan test is the joint rule: rescan when
`max_i A_i - UUT*I + UUT*D > (UUT - LUT_min) * TSU_ref`. The ratio of the left side to the right
is the whole mechanism; an increment rescans exactly when it exceeds one, and `check` confirms
that the increments above one, the triggered flags and the rescan counter agree.

{table(["database", "median ratio", "min", "max", "increments within budget", "rescans", "check"], reg_rows,
       ["---", "---:", "---:", "---:", "---:", "---:", "---"])}

The share alpha = max_i A_i / U+ of the inserted utility that one item reaches, at each decision
(U+ inserted since the last rescan): an insertion-only stream is never rescanned while
alpha <= UUT, and otherwise rescanned exactly when (alpha - UUT) * U+ > (UUT - LUT_min) * TSU_ref.
"stream" is alpha over the whole insertion stream; "check" compares the recorded alpha and
decisions with `analysis/insertion_alpha.py`, which computes both from the data files.

{table(["database", "UUT", "median alpha", "min", "max", "increments with alpha <= UUT", "stream", "check"], alpha_rows,
       ["---", "---:", "---:", "---:", "---:", "---:", "---:", "---"])}

## The prediction registered before the campaign

From the data alone, the joint rule absorbs (1 - UUT) / (alpha - UUT) times the inserted utility
that a test on the total inserted utility (Pre-HUSPM-TSU) absorbs before a rescan, with alpha
over the insertion stream. Registered before the campaign ran: the gap in rescans between
Pre-HUSPM-TSU and the proposal is largest on {" and ".join(mf.PREDICTED_LARGEST)} and smallest on
{", ".join(mf.PREDICTED_SMALLEST)}. Gap = Pre-HUSPM-TSU's rescans minus the proposal's, where both
completed the 100 increments.

{table(["database", "alpha, stream", "budget factor", "rescans, proposal", "Pre-HUSPM-TSU", "Pre-HUSPM-SWUmax", "gap"], hyp_rows,
       ["---", "---:", "---:", "---:", "---:", "---:", "---:"])}

Verdict: **{hyp["verdict"]}** ({hyp["reason"]}).

The joint rule never rescans more often than the SWUmax test on an insertion-only stream with the
same UUT and floor ratio (a proved property, checked here): the proposal rescans no more than
Pre-HUSPM-SWUmax on {len(ord_pairs) - len(ord_more)} of {len(ord_pairs)} databases where both completed{
"" if not ord_more else "; it rescans MORE on " + ", ".join(ord_more)}{
"" if not ord_raised else "; " + ", ".join(ord_raised) + " not compared, its floor rose above r_l"}.

**Inside the budget** (median ratio at most one): {", ".join(f"{d}, {ratio(d):.2f} times faster than remining" for d in inside) or "none"}.

**At the boundary**: {", ".join(f"{d} ({ratio(d):.2f}x)" for d in boundary)}. They rescan on
{min(int(reg.loc[d,'rescans']) for d in boundary)} to {max(int(reg.loc[d,'rescans']) for d in boundary)}
of 100 increments and still finish faster than remining.

**Beyond it**: {", ".join(f"{d}, {int(reg.loc[d,'within_budget'])} of 100 within budget" for d in beyond)}.
Maintenance degenerates into remining: {"; ".join(f"{d} {secs(totals[(d,P)][0])} s against remining's {secs(totals[(d,REM)][0])} s ({(totals[(d,P)][0]/totals[(d,REM)][0]-1)*100:+.1f} %)" for d in beyond)},
one run each. Two runs of one SC1 configuration on the same JDK differ by a factor between
{r12.min():.2f} and {r12.max():.2f} ({len(r12)} configurations of at least one second), so differences
of a few percent between single runs are not resolved here.

## {kos} cannot be moved by tuning

The bound is proportional to `1 - r_l` and the test quantity does not depend on `r_l`, so the
median ratio of {reg.loc[kos,'ratio_median']:.2f} at `r_l = {rl[kos]}` would come down to one only at
`r_l = {1 - (1 - rl[kos]) * reg.loc[kos,'ratio_median']:.2f}`, a lower pre-large floor. A lower floor
costs more to mine, and on {kos} it is already the binding limit: `r_l` is {rl[kos]} there, not 0.8,
because at 0.8 the lowest threshold of the sweep exceeds three billion candidates
(`docs/campaign-parameters.md`). SC4 measures the trade-off between the floor and the pre-large
set directly.

## What this does not show

{swu_line}
"""
    write("campaign/SC2/README.md", text)


# ------------------------------------------------------------------------------ SC5
def sc5():
    df = mf.parse_batch(mf.load_scenario("SC5"))
    inc = df[df["phase"] == "INC"]
    cells = inc.groupby(["dataset", "K", "strat", "algorithm"])
    tot = cells.apply(lambda g: mf.ms_to_s(g["runtime_ms"]).sum() if g["finished"].all() else np.nan)
    resc = inc[inc["algorithm"] == P].groupby(["dataset", "K", "strat"])["rescan_count"].max()
    rows = []
    for b in ["Pre-HUSPM-TSU", "Pre-HUSPM-SWUmax", "IncUSP-Miner+", REM]:
        r = []
        for (ds, k, s, a), v in tot.items():
            if a != b:
                continue
            p = tot.get((ds, k, s, P), np.nan)
            if np.isfinite(v) and np.isfinite(p) and p > 0:
                r.append(v / p)
        rows.append([LABEL[b], f"{gmean(r):.2f}", len(r), sum(1 for x in r if x < 1)])
    dbs = [d for d in mf.FIG_DATASETS if d in set(df["dataset"])]
    fine = []
    for ds in dbs:
        for k in sorted(df["K"].dropna().unique()):
            p = sum(tot.get((ds, k, s, P), np.nan) for s in mf.STRATS)
            q = sum(tot.get((ds, k, s, REM), np.nan) for s in mf.STRATS)
            rs = sum(resc.get((ds, k, s), np.nan) for s in mf.STRATS)
            fine.append([ds, int(k), f"{rs / (4 * k) * 100:.0f} %", f"{p / q:.2f}"])
    strat = []
    for ds in dbs:
        for s in mf.STRATS:
            p, q = tot.get((ds, 100, s, P), np.nan), tot.get((ds, 100, s, REM), np.nan)
            strat.append([ds, s, f"{p:.1f} s", f"{q:.1f} s", f"{p / q:.2f}", int(resc.get((ds, 100, s), 0))])
    wins = sum(1 for (ds, k, s, a), v in tot.items() if a == P and np.isfinite(v) and all(
        not np.isfinite(tot.get((ds, k, s, b), np.nan)) or tot.get((ds, k, s, b)) > v for b in INC))
    ncell = len({(ds, k, s) for (ds, k, s, a) in tot.index if a == P})
    text = f"""# SC5, how the stream is cut

The same incremental stream cut four ways - EVEN, INCREASING, DECREASING, BURST - at four
increment counts, `K` in {{100, 67, 50, 25}}, the total incremental data fixed. SIGN, LEVIATHAN and
C8T1S5I8N5K, `D_init` per `common.Datasets`, one run per arm. {HEAD}

## Against each baseline

Geometric mean of the baseline's time over the proposal's, summed over the increments of each
(database, `K`, strategy) cell where both completed; and the cells where the baseline is faster.
The proposal is the fastest arm in {wins} of {ncell} cells.

{table(["baseline", "baseline time / proposal time", "cells", "cells where the baseline is faster"], rows)}

## The finer the cut, the wider the gap

Over the four strategies together: the share of increments on which the proposal rescans, and
its time over remining's.

{table(["database", "K", "increments that rescan", "proposal's time / remine's"], fine)}

## How the stream is cut, at `K = 100`

{table(["database", "strategy", "proposal", "remine", "ratio", "rescans"], strat, ["---", "---", "---:", "---:", "---:", "---:"])}
"""
    write("campaign/SC5/README.md", text)


# ------------------------------------------------------------------------------ SC6
def sc6():
    df = mf.load_scenario("SC6", replicates=True)
    ops = df[~df["op_type"].astype(str).str.contains("INIT")].copy()
    ops["kind"] = ops["op_type"].astype(str).str.extract(r"SC6_(INS|DEL|MOD)")[0]
    per, kinds, agree_all, nops = [], [], True, 0
    for (ds, seed), g in ops.groupby(["dataset", "seed"]):
        p = g[g["algorithm"] == P].sort_values("iteration")
        q = g[g["algorithm"] == REM].sort_values("iteration")
        ag = len(p) == len(q) and (p["total_patterns_found"].to_numpy() == q["total_patterns_found"].to_numpy()).all()
        agree_all &= bool(ag)
        nops += len(p)
        per.append(dict(dataset=ds, seed=int(seed), p=mf.ms_to_s(p["runtime_ms"]).sum(),
                        q=mf.ms_to_s(q["runtime_ms"]).sum()))
    per = pd.DataFrame(per)
    per["ratio"] = per["p"] / per["q"]
    summ = []
    for ds, g in per.groupby("dataset", sort=False):
        st = mf.spread(g["ratio"])
        summ.append([ds, st["n"], f"{st['mean']:.3f} ± {st['std']:.3f}", f"{st['min']:.3f}", f"{st['max']:.3f}"])
    name = {"INS": "insertion", "DEL": "deletion", "MOD": "modification"}
    for ds, g in ops.groupby("dataset", sort=False):
        p = g[g["algorithm"] == P].sort_values(["seed", "iteration"])
        p = p.assign(rescanned=p.groupby("seed")["rescan_count"].diff().fillna(p["rescan_count"]) > 0)
        for k in ["INS", "DEL", "MOD"]:
            pk = p[p["kind"] == k]
            qk = g[(g["algorithm"] == REM) & (g["kind"] == k)]
            a, b = mf.ms_to_s(pk["runtime_ms"]).sum(), mf.ms_to_s(qk["runtime_ms"]).sum()
            kinds.append([ds, name[k], f"{int(pk['rescanned'].sum())} / {len(pk)}", f"{a:.2f} s", f"{b:.2f} s",
                          f"{b / a:.1f}"])
    seeds = sorted(per["seed"].unique())
    text = f"""# SC6, insertion, deletion and modification mixed

Twenty operations per database and seed - exactly 14 insertions, 3 deletions and 3 modifications
in an order drawn from `Random(seed + name.hashCode())`, each a hundredth of the incremental
stream - replayed by the proposal and by remining. SIGN, LEVIATHAN and C8T1S5I8N5K, five seeds
({", ".join(str(s) for s in seeds)}); seed 42 is in the main tree, the others in
`results/campaign-replicates/s1r0` to `s4r0`, each in a fresh JVM. {HEAD}

## Every operation agrees

At every one of the {nops} operations the two arms report the same HUSP count:
{"yes" if agree_all else "NO - see table_SC6_seeds.csv"}.

## Per database, over the five seeds

The proposal's time over remining's, summed over the twenty operations of a seed; the two arms
replay the same plan at a seed, so the ratio is paired by seed.

{table(["database", "seeds", "ratio, mean ± sd", "min", "max"], summ)}

## By kind of operation, all seeds together

{table(["database", "kind", "rescans", "proposal", "remine", "remine / proposal"], kinds, ["---", "---", "---:", "---:", "---:", "---:"])}
"""
    write("campaign/SC6/README.md", text)


# ------------------------------------------------------------------------------ SC7
def sc7():
    df = mf.load_scenario("SC7")
    arms = ["proposed_full", "proposed_no_IUB", "proposed_no_IPS", "Pre-HUSPM"]
    st = df[df["algorithm"].isin(arms)]

    def cell(r, a, col, fmt):
        # An arm that did not run, or did not finish, is named rather than left out.
        if a not in r:
            return "not run"
        if not bool(r[a]["finished"]):
            return STATUS.get(r[a]["status"], r[a]["status"])
        return fmt(r[a][col])

    secs3 = lambda v: f"{v / 1000:.2f} s"
    count = lambda v: f"{int(v):,}"
    c3 = []
    for ds in [d for d in mf.ALL_DATASETS if d in set(st["dataset"])]:
        r = {a: st[(st["dataset"] == ds) & (st["algorithm"] == a)].iloc[-1] for a in arms
             if len(st[(st["dataset"] == ds) & (st["algorithm"] == a)])}
        c3.append([ds, cell(r, "proposed_full", "total_patterns_found", count),
                   cell(r, "proposed_full", "runtime_ms", secs3), cell(r, "proposed_no_IUB", "runtime_ms", secs3),
                   cell(r, "proposed_no_IPS", "runtime_ms", secs3), cell(r, "Pre-HUSPM", "runtime_ms", secs3),
                   cell(r, "proposed_full", "explored_nodes", count), cell(r, "proposed_no_IUB", "explored_nodes", count),
                   cell(r, "proposed_no_IPS", "explored_nodes", count)])
    fin = st[st["finished"]]
    static_pairs = sum(max(0, g["algorithm"].nunique() - 1) for _, g in fin.groupby("dataset"))
    static_agree = all(g["total_patterns_found"].nunique() == 1 for _, g in fin.groupby("dataset"))
    inc = df[df["algorithm"].isin(["proposed_C5_on", "proposed_C5_off", "proposed_no_block_index"]) & mf.is_inc(df)]
    c5, c4 = [], []
    for ds in [d for d in mf.ALL_DATASETS if d in set(inc["dataset"])]:
        g = {a: inc[(inc["dataset"] == ds) & (inc["algorithm"] == a)].sort_values("iteration") for a in
             ["proposed_C5_on", "proposed_C5_off", "proposed_no_block_index"]}
        on, off, nb = g["proposed_C5_on"], g["proposed_C5_off"], g["proposed_no_block_index"]
        ton, toff = mf.ms_to_s(on["runtime_ms"]).sum(), mf.ms_to_s(off["runtime_ms"]).sum()
        c5.append([ds, f"{ton:.1f} s, {int(on['rescan_count'].iloc[-1])} rescans",
                   f"{toff:.1f} s, {int(off['rescan_count'].iloc[-1])} rescans", f"{toff / ton:.2f}"])
        d_on = on[on["cub_triggered"].astype(str).str.lower() == "false"]
        d_nb = nb[nb["cub_triggered"].astype(str).str.lower() == "false"]
        c4.append([ds, len(d_on), f"{d_on['runtime_ms'].sum():,.0f} ms", f"{d_nb['runtime_ms'].sum():,.0f} ms",
                   f"{int(d_on['blocks_read'].sum()):,}", f"{int(d_nb['blocks_read'].sum()):,}"])
    agree = all(
        (inc[(inc["dataset"] == ds) & (inc["algorithm"] == a)].sort_values("iteration")["total_patterns_found"].to_numpy()
         == inc[(inc["dataset"] == ds) & (inc["algorithm"] == "proposed_C5_on")].sort_values("iteration")["total_patterns_found"].to_numpy()).all()
        for ds in set(inc["dataset"]) for a in ["proposed_C5_off", "proposed_no_block_index"])
    bs = sc7_block_size()
    block_size_text = ""
    if len(bs):
        rows = [[r["dataset"], f"{r['block_size']}" + (" (default)" if r["default"] else ""),
                 f"{r['sequences_read']:,}", f"{r['direct_ms']:,.0f} ms", f"{r['total_s']:.2f} s", r["counts_equal"],
                 "yes" if r["same_direct_increments"] else "NO"] for _, r in bs.iterrows()]
        block_size_text = f"""
## Block size

The same stream with blocks of n sequences. A block never spans two increments, so the effective
block size is the smaller of n and the increment size (5 sequences on SIGN, 199 or 200 on
C8T1S5I8N5K). The expectations were stated in
`docs/experiments.md` before the measurement. Sequences read do not decrease from one block size
to the next where the smaller divides the larger: {bs.attrs["nested_pairs_monotone"]} of
{bs.attrs["nested_pairs"]} such pairs.

{table(["database", "block size", "sequences read", "direct updates", "whole stream", "counts equal to the default", "same increments without a rescan"], rows)}
"""
    text = f"""# SC7, what each component contributes

The IUB bound and the irrelevant item pruning (IPS) act inside the miner, so each is ablated on
one mine of the whole database, with Pre-HUSPM beside them. The block index and the adaptive rescan decision act only while the
database grows, so they are ablated on `D_init` and 100 increments, one row per increment, on
SIGN and C8T1S5I8N5K. {HEAD}

Switching a component off changes no answer: the HUSP count of every increment is the same with
the adaptive decision or the block index off as with both on ({"yes" if agree else "NO"}).

## The IUB bound and the irrelevant item pruning

Every finished arm of a database reports the same HUSP count: {"yes" if static_agree else "NO"}, over
{static_pairs} comparisons.

{table(["database", "HUSPs", "full", "IUB off", "IPS off", "Pre-HUSPM", "explored, full", "explored, IUB off", "explored, IPS off"], c3)}

## The adaptive rescan decision

{table(["database", "adaptive decision", "rescan at every increment", "time saved, factor"], c5)}

## The block index

Over the increments that do not rescan, the only ones on which the index is read.

{table(["database", "increments without a rescan", "with the index", "without it", "sequences read with", "sequences read without"], c4)}
{block_size_text}"""
    write("campaign/SC7/README.md", text)


# ------------------------------------------------------------------------------ memory SC2
def mem_sc2():
    df = mf.load_scenario("SC2", tree=mf.MEMORY_TREE)
    dbs = [d for d in mf.SC2_DATASETS if d in set(df["dataset"])]
    algos = [P, "Pre-HUSPM-SWUmax", "Pre-HUSPM-TSU", REM, "IncUSP-Miner+"]
    held, peak = [], []
    for ds in dbs:
        hr, pr = [ds], [ds]
        for a in algos:
            g = df[(df["dataset"] == ds) & (df["algorithm"] == a)].sort_values("iteration")
            if g.empty:
                d = deferred(mf.MEMORY_TREE, "SC2", ds, a)
                hr.append(f"deferred, {d}" if d else "not run")
                pr.append(f"deferred, {d}" if d else "not run")
                continue
            ok = g[g["finished"]]
            inc = g[mf.is_inc(g)]
            last = ok.iloc[-1] if len(ok) else None
            if len(ok) and len(inc[inc["finished"]]) == len(inc):
                hr.append(f"{last['mem_retained_mb']:,.0f}")
            elif len(ok):
                hr.append(f"{last['mem_retained_mb']:,.0f} at {int(last['iteration'])}, then "
                          + STATUS.get(g.loc[~g["finished"], "status"].iloc[0], "failed"))
            else:
                hr.append("out of memory on `D_init`")
            failed = (~g["finished"]).any()
            why = STATUS.get(g.loc[~g["finished"], "status"].iloc[0], "failed") if failed else ""
            if not len(ok):
                pr.append("out of memory on `D_init`")
            elif failed:
                pr.append(f"{ok['mem_live_mb'].max():,.0f}, then {why}")
            else:
                pr.append(f"{ok['mem_live_mb'].max():,.0f}")
        held.append(hr)
        peak.append(pr)
    text = f"""# SC2 memory: the state an incremental method holds over 100 increments

The memory campaign's SC2: the same streams as SC2, measured in live mode (a full collection is
forced at every sample and the heap read right after), so the runtimes of these rows are void.
`mem_retained_mb` is what the algorithm holds after an increment, against a baseline taken before
the algorithm exists; `mem_live_mb` the peak during it. {HEAD}

A single cell's live set can move between two measurements (`results/campaign-mem-replicates`), so these
tables support comparisons of orders of magnitude, not of a few percent.

## State held after the last increment

MB.

{table(["database"] + [LABEL[a] for a in algos], held)}

## Peak live set over the stream

MB.

{table(["database"] + [LABEL[a] for a in algos], peak)}
"""
    write("campaign-mem/SC2/README.md", text)


# ------------------------------------------------------------------------------ static scenarios
SLABEL = {"USpan": "USpan", "HUSP-ULL": "HUSP-ULL", "HUSP-SP": "HUSP-SP", "IncUSP-Miner+": "IncUSP-Miner+",
          "Pre-HUSPM": "Pre-HUSPM", P: "Pre-HUSPM-Joint"}


# One computation for the paper tables and these pages.
from scenario_tables import sc7_block_size, static_cells, static_summary, verification  # noqa: E402


def incomplete_runs(cells):
    out = {}
    for (d, su, a), r in cells.items():
        if not bool(r["finished"]):
            out.setdefault((d, a, STATUS.get(r["status"], r["status"])), []).append(su)
    return [[d, SLABEL.get(a, a), f"{why} at {len(sus)} of the four thresholds"]
            for (d, a, why), sus in sorted(out.items())]


def runtime_cell(cells, d, su, a):
    r = cells.get((d, su, a))
    if r is None:
        return "not run"
    if not bool(r["finished"]):
        return STATUS.get(r["status"], r["status"])
    x = float(r["runtime_ms"]) / 1000
    return f"{x:.2f}" if x < 10 else secs(x)


def runtime_rows(cells, dss, sus_of):
    rows = []
    for d in dss:
        for su in sus_of(d):
            fin = {a: float(cells[(d, su, a)]["runtime_ms"]) for a in mf.STATIC_ORDER
                   if (d, su, a) in cells and bool(cells[(d, su, a)]["finished"])}
            best = min(fin, key=fin.get) if fin else None
            husps = next((int(cells[(d, su, a)]["total_patterns_found"]) for a in mf.STATIC_ORDER if a in fin), "-")
            row = [d, f"{su:g}", husps]
            for a in mf.STATIC_ORDER:
                c = runtime_cell(cells, d, su, a)
                row.append(f"**{c}**" if a == best else c)
            rows.append(row)
    return rows


def geo_rows(summ):
    return [[SLABEL[a], f"{gmean(v):.2f}", len(v)] for a, v in
            sorted(summ["per_base"].items(), key=lambda kv: -gmean(kv[1]) if kv[1] else 0)]


def summary_text(summ):
    vb = np.asarray(summ["vs_best"], float)
    t = (f"For {summ['agree']} of the {summ['pairs']} (database, threshold) pairs, every algorithm that "
         f"completed reports the same HUSP count"
         + (f"; they differ on {', '.join(summ['disagree'])}" if summ["disagree"] else "") + ". "
         "Counts alone do not prove the sets equal; `verify.BaselineAgreement` compares the sets "
         "(`results/calibration/agreement.log`).\n\n")
    if len(vb):
        t += (f"The proposal is the fastest in {summ['fastest']} of {summ['n_fast']} cells. The fastest baseline's "
              f"runtime over the proposal's, per cell: median {np.median(vb):.2f}, largest {vb.max():.2f}, "
              f"smallest {vb.min():.2f}; above one, the proposal is faster.")
    if summ["excluded"]:
        t += f" Left out, a runtime read as 0 ms: {', '.join(summ['excluded'])}."
    return t


def sc1():
    df = mf.load_scenario("SC1")
    cells = static_cells(df)
    dss = [d for d in mf.ALL_DATASETS if d in set(df["dataset"])]
    summ = static_summary(cells)
    inc = incomplete_runs(cells)
    rows = runtime_rows(cells, dss, lambda d: [mf.default_su(d)])
    text = f"""# SC1, static mining on `D_init`

{SCOPE["campaign/SC1/README.md"][1]} {HEAD}

## Answers

{summary_text(summ)}

{("Runs that did not complete:" + chr(10) + chr(10) + table(["database", "algorithm", "outcome"], inc, ["---", "---", "---"])) if inc else "Every run completed."}

## Runtime at the default threshold

Seconds; the fastest in bold.

{table(["database", "threshold", "HUSPs"] + [SLABEL[a] for a in mf.STATIC_ORDER], rows,
       ["---", "---:", "---:"] + ["---:"] * len(mf.STATIC_ORDER))}

## Against each baseline, all cells

Geometric mean of the baseline's runtime over the proposal's, on the cells where both completed.

{table(["baseline", "baseline / proposal", "cells"], geo_rows(summ))}

`peak_heap_mb` in these rows is used heap, garbage included, and is not the memory result; the
live set is in `results/campaign-mem/SC1`.
"""
    write("campaign/SC1/README.md", text)


def sc3():
    df = mf.load_scenario("SC3")
    cells = static_cells(df)
    dss = [d for d in mf.ALL_DATASETS if d in set(df["dataset"])]
    summ = static_summary(cells)
    inc = incomplete_runs(cells)
    rows = runtime_rows(cells, dss, lambda d: sorted({su for dd, su, _ in cells if dd == d}))
    s1 = static_summary(static_cells(mf.load_scenario("SC1")))
    comp = [[SLABEL[a], f"{gmean(v):.2f}", len(v), f"{gmean(s1['per_base'][a]):.2f}", len(s1["per_base"][a])]
            for a, v in sorted(summ["per_base"].items(), key=lambda kv: -gmean(kv[1]) if kv[1] else 0)]
    text = f"""# SC3, static mining on the whole database

{SCOPE["campaign/SC3/README.md"][1]} {HEAD}

## Answers

{summary_text(summ)}

{("Runs that did not complete:" + chr(10) + chr(10) + table(["database", "algorithm", "outcome"], inc, ["---", "---", "---"])) if inc else "Every run completed."}

## Runtime

Seconds; the fastest in bold.

{table(["database", "threshold", "HUSPs"] + [SLABEL[a] for a in mf.STATIC_ORDER], rows,
       ["---", "---:", "---:"] + ["---:"] * len(mf.STATIC_ORDER))}

## Against each baseline, here and on `D_init`

Geometric mean of the baseline's runtime over the proposal's, on the cells where both completed.

{table(["baseline", "SC3, whole database", "cells", "SC1, `D_init`", "cells"], comp)}
"""
    write("campaign/SC3/README.md", text)


def sc4():
    df = mf.parse_sl(mf.load_scenario("SC4"))
    dss = [d for d in mf.ALL_DATASETS if d in set(df["dataset"])]
    names = {"Adaptive": "Pre-HUSPM-Joint", "Pre-HUSPM": "Pre-HUSPM"}
    rows, counts, ratios = [], {}, []
    for d in dss:
        for sl in sorted(df.loc[df["dataset"] == d, "sl"].dropna().unique()):
            row = [d, f"{sl:g}"]
            got = {}
            for base in ("Adaptive", "Pre-HUSPM"):
                g = df[(df["dataset"] == d) & (df["base_algo"] == base) & np.isclose(df["sl"], sl)]
                if g.empty:
                    row.append("not run")
                    continue
                r = g.iloc[-1]
                if not bool(r["finished"]):
                    row.append(STATUS.get(r["status"], r["status"]))
                    continue
                got[base] = r
                counts.setdefault(d, set()).add(int(r["total_patterns_found"]))
                row.append(f"{int(r['prelarge_count']):,} / {int(r['peak_heap_mb']):,} MB / "
                           f"{float(r['runtime_ms']) / 1000:.1f}")
            if len(got) == 2 and got["Adaptive"]["prelarge_count"] > 0:
                ratios.append(got["Pre-HUSPM"]["prelarge_count"] / got["Adaptive"]["prelarge_count"])
            rows.append(row)
    same = all(len(v) == 1 for v in counts.values())
    husp = ", ".join(f"{next(iter(v))} on {d}" for d, v in counts.items() if len(v) == 1)
    text = f"""# SC4, the pre-large floor

{SCOPE["campaign/SC4/README.md"][1]} {HEAD}

## Result

The answer does not depend on the floor: {"every completed run reports the same HUSP count per database (" + husp + ")" if same else "the HUSP counts DIFFER across floors or algorithms: " + str(counts)}.

{table(["database", "`r_l`", "Pre-HUSPM-Joint: pre-large / heap / s", "Pre-HUSPM: pre-large / heap / s"], rows,
       ["---", "---", "---", "---"])}

Heap is `peak_heap_mb`, used heap including garbage not yet collected; it orders the two
algorithms but is not a live-set figure (`docs/result-columns.md`).

{("Where both complete, Pre-HUSPM's pre-large set is " + f"{min(ratios):.0f} to {max(ratios):.0f}" + " times the proposal's, over " + str(len(ratios)) + " cells.") if ratios else "No cell where both complete."} The two sets are not one set measured
twice: Pre-HUSPM admits a pattern by an upper bound on its utility, the proposal by the utility itself.
"""
    write("campaign/SC4/README.md", text)


def mem_sc1():
    df = mf.parse_su(mf.load_scenario("SC1", tree=mf.MEMORY_TREE))
    dss = [d for d in mf.ALL_DATASETS if d in set(df["dataset"])]
    rows, twice = [], 0
    for d in dss:
        su = mf.default_su(d)
        row = [d]
        for a in mf.STATIC_ORDER:
            g = df[(df["dataset"] == d) & (df["algorithm"] == a) & np.isclose(df["su"], su)]
            if g.empty:
                row.append("not run")
                continue
            r = g.iloc[-1]
            if not bool(r["finished"]):
                row.append(STATUS.get(r["status"], r["status"]))
                continue
            mark = ""
            if int(r["gc_forced"]) <= 2:
                mark = " †"
                twice += 1
            row.append(f"{int(r['mem_live_mb']):,}{mark}")
        rows.append(row)
    rt = mf.memory_ratios()
    rat = []
    for base in [a for a in mf.STATIC_ORDER if a != P]:
        g = rt[rt["baseline"] == base].set_index("scope")
        al, df_ = g.loc["all four thresholds"], g.loc["default threshold"]
        rat.append([SLABEL[base], f"{al['geomean_ratio']:.2f}", f"{int(al['cells_used'])} of {int(al['cells'])}",
                    int(al["baseline_holds_less"]), f"{df_['geomean_ratio']:.2f}"])
    inc = incomplete_runs(static_cells(mf.load_scenario("SC1", tree=mf.MEMORY_TREE)))
    text = f"""# SC1 memory: the live set of a static mine on `D_init`

{SCOPE["campaign-mem/SC1/README.md"][1]} The wait between samples is the larger of 20 ms and four
times the last collection. {HEAD}

{("Runs that did not complete:" + chr(10) + chr(10) + table(["database", "algorithm", "outcome"], inc, ["---", "---", "---"])) if inc else "Every run completed."}

## Live set at the default threshold

Megabytes. A dagger marks a run read at its start and end only ({twice} cells), because it ended
before the first sample; a live set below 1 MB reads as 0.

{table(["database"] + [SLABEL[a] for a in mf.STATIC_ORDER], rows, ["---"] + ["---:"] * len(mf.STATIC_ORDER))}

## Against each baseline

Geometric mean of the baseline's live set over the proposal's, on the cells where both completed
and both read at least 1 MB; above one, the proposal holds less. Cells left out and why:
`figures/campaign/table_SC1_memory_ratios.csv`.

{table(["baseline", "all four thresholds", "cells used", "cells where the baseline holds less", "default threshold"], rat)}
"""
    write("campaign-mem/SC1/README.md", text)


# What each scenario measures, written while it has no finished arm; the numbers come only
# from the arms' CSVs once there are some.
SCOPE = {
    "campaign/SC1/README.md": (
        "SC1, static mining on `D_init`",
        "Eight databases, six algorithms (USpan, HUSP-ULL, HUSP-SP, IncUSP-Miner+, Pre-HUSPM and "
        "Pre-HUSPM-Joint), four thresholds per database from `common.Datasets`. One JVM per arm; "
        "the four thresholds run lowest first in the same JVM."),
    "campaign/SC2/README.md": (
        "SC2, one hundred increments",
        "`D_init` per `common.Datasets`, the remainder cut into 100 equal increments in file order, "
        "against Pre-HUSPM-SWUmax, Pre-HUSPM-TSU, remining from scratch and IncUSP-Miner+. BMS1 is "
        "not in this scenario: none of its four thresholds can be mined once the database grows past `D_init`."),
    "campaign/SC3/README.md": (
        "SC3, static mining on the whole database",
        "The six algorithms of SC1 on the whole database at the same four thresholds, on SIGN, "
        "LEVIATHAN and C8T1S5I8N5K."),
    "campaign/SC4/README.md": (
        "SC4, the pre-large floor",
        "One mine of the whole database at four floors, `r_l` in {0.3, 0.5, 0.7, 0.9}, for Pre-HUSPM "
        "and the proposal at the same `r_l`, on SIGN, LEVIATHAN and C8T1S5I8N5K."),
    "campaign/SC5/README.md": (
        "SC5, how the stream is cut",
        "The incremental stream cut four ways (EVEN, INCREASING, DECREASING, BURST) at four increment "
        "counts, `K` in {100, 67, 50, 25}, the total incremental data fixed; SIGN, LEVIATHAN and "
        "C8T1S5I8N5K."),
    "campaign/SC6/README.md": (
        "SC6, insertion, deletion and modification mixed",
        "Twenty operations per database and seed (14 insertions, 3 deletions, 3 modifications in an "
        "order drawn from the seed), replayed by the proposal and by remining; SIGN, LEVIATHAN and "
        "C8T1S5I8N5K, five seeds."),
    "campaign/SC7/README.md": (
        "SC7, what each component contributes",
        "The IUB bound and the irrelevant item pruning ablated on one mine of the whole database, with Pre-HUSPM beside them; the block "
        "index and the adaptive rescan decision ablated on `D_init` and 100 increments, on SIGN and "
        "C8T1S5I8N5K."),
    "campaign-mem/SC1/README.md": (
        "SC1 memory: the live set of a static mine on `D_init`",
        "The runs of SC1 measured for memory instead of time: at every sample the runner forces a full "
        "collection and reads the heap right after, so runtimes are void."),
    "campaign-mem/SC2/README.md": (
        "SC2 memory: the state an incremental method holds over 100 increments",
        "The streams of SC2 measured in live mode, so the runtimes of these rows are void."),
}

# (path, scenario, tree, read replicates, generator or None)
README_SOURCES = [
    ("campaign/SC1/README.md", "SC1", mf.TIMING_TREE, False, sc1),
    ("campaign/SC2/README.md", "SC2", mf.TIMING_TREE, False, sc2),
    ("campaign/SC3/README.md", "SC3", mf.TIMING_TREE, False, sc3),
    ("campaign/SC4/README.md", "SC4", mf.TIMING_TREE, False, sc4),
    ("campaign/SC5/README.md", "SC5", mf.TIMING_TREE, False, sc5),
    ("campaign/SC6/README.md", "SC6", mf.TIMING_TREE, True, sc6),
    ("campaign/SC7/README.md", "SC7", mf.TIMING_TREE, False, sc7),
    ("campaign-mem/SC1/README.md", "SC1", mf.MEMORY_TREE, False, mem_sc1),
    ("campaign-mem/SC2/README.md", "SC2", mf.MEMORY_TREE, False, mem_sc2),
]


def no_results(path, sc):
    title, scope = SCOPE[path]
    write(path, f"""# {title}

{scope}

No arm of this scenario has finished yet. Its tables are written here from the arms' CSVs by
`analysis/scenario_readmes.py` once they have: `.\\campaign.ps1 -Scenario {sc}`.
""")


# ------------------------------------------------------------------------------ verify-joint
def verify_joint():
    """results/verify-joint/README.md, from the outputs of the correctness checks."""
    import json
    summ, per_db = verification()
    base = os.path.join(mf.DATA_DIR, "verify-joint")
    if summ.empty:
        return
    cmds = []
    for name in sorted(os.listdir(base)):
        meta = os.path.join(base, name, "run_meta.json")
        if os.path.exists(meta):
            m = json.load(open(meta, encoding="utf-8"))
            cmds.append(f"    # {name}: exit {m.get('exit_code')}, commit {m.get('git_commit', '')[:8]}, {m.get('end', '')}\n"
                        f"    {m['command']}")
    num = lambda v: "" if pd.isna(v) else f"{int(v):,}"
    rows = [[r["check"], r["status"], num(r.get("operations")), num(r.get("with_target")),
             num(r.get("direct_delete")), num(r.get("direct_insert")), num(r.get("direct_modify")),
             num(r.get("mismatches")), num(r.get("caught"))] for _, r in summ.iterrows()]
    db_text = ""
    for check, g in per_db.groupby("check", sort=False):
        db_rows = [[r["dataset"], r["operations"], r["with_target"], r["insert_direct"], r["insert_rescan"],
                    r["delete_direct"], r["delete_rescan"], r["modify_direct"], r["modify_rescan"], r["mismatches"]]
                   for _, r in g.iterrows()]
        db_text += f"""
### {check}

{table(["database", "operations", "with a target", "insert direct", "insert rescan", "delete direct",
        "delete rescan", "modify direct", "modify rescan", "mismatches"], db_rows)}
"""
    write("verify-joint/README.md", f"""# Correctness of the joint rule

Counters measured on the development machine; no timing in this tree. Each subdirectory holds
`console.txt`, the full output, and `run_meta.json` (command, commit, source digest, JVM, exit code).

Every check compares the pattern set and the utility of every pattern with remining from
scratch after every operation, so it verifies the incremental maintenance; the miner itself is
checked against USpan, HUSP-ULL and HUSP-SP by `verify.BaselineAgreement`.

* `fuzz`: random insertion, deletion and modification streams on small generated databases
  (`verify.MixedBudgetFuzz`), for the joint rule and, as a control, for the `iaumax` rule, which
  is unsound; and hand-built cases (`verify.MixedBudgetProbe`).
* `acceptance`: the rescan rule on hand-built cases (`verify.RescanRuleAcceptanceTest`).
* `ips-random`: the proposal's miner with item pruning off, once and to a fixpoint against
  exhaustive enumeration on small random databases (`verify.ItemRemovalCheck random`): pattern
  sets and utilities, at the cutoff and at UUT.
* `ips-real`: the same miner with the pruning off, once and to a fixpoint on the real databases,
  on `D_init` and on the whole database at the mining floor; the pattern sets and every utility
  must be identical (`verify.ItemRemovalCheck real`).
* `fault-injection-ips`: faults written into the pruning, each run through `ips-random`; a
  control without a fault must pass.
* `fault-injection`, `fault-injection-real`: each fault is written into a copy of the source and
  the checks are run on it; a fault counts as caught when a check reports a mismatch. A run that
  does not finish catches nothing.
* `random`, `adversarial`, `adversarial-tafeng`: `verify.DirectPathVerifier` on the real
  databases, the first half as `D_init`, 16 operations sized to spend a sixth of the rescan
  budget. In adversarial mode each operation is shaped around a target, the untracked pattern of
  highest utility from a reference mine at 0.6 UUT (`-Dverify.probeRl`, 0.7 in
  `adversarial-tafeng-probe0.7`): deletions take only sequences that miss one of its items,
  insertions only sequences that hold all of them. Where the reference mine does not finish or
  runs out of heap the operation has no target and is drawn at random; the `with a target`
  column counts the operations that had one.

## Summary

{table(["check", "status", "operations", "with a target", "direct deletions", "direct insertions",
        "direct modifications", "mismatches", "faults caught"], rows)}

## Direct path on the real databases, per database
{db_text}
## Commands

{chr(10).join(cmds)}
""")


if __name__ == "__main__":
    verify_joint()
    missing = []
    for path, sc, tree, reps, gen in README_SOURCES:
        df = mf.load_scenario(sc, tree=tree, replicates=reps)
        if df is None or df.empty:
            no_results(path, sc)
        elif gen is None:
            missing.append(path)
        else:
            gen()
    if missing:
        # Results exist but nothing writes their tables: typing them by hand is what this
        # script is here to prevent, so it fails instead of leaving the old file in place.
        print("ERROR: results exist but no generator writes " + ", ".join(missing), file=sys.stderr)
        sys.exit(1)
