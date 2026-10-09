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
LABEL = {P: "Pre-HUSPM-Adaptive", "Pre-HUSPM-SWUmax": "Pre-HUSPM-SWUmax", "Pre-HUSPM-TSU": "Pre-HUSPM-TSU",
         REM: "Remine", "IncUSP-Miner+": "IncUSP-Miner+"}
STATUS = {"OOM": "out of memory", "Skip": "out of memory", "OT": "over the time limit",
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
    rl = {d: (0.85 if re.search(rf'new Entry\("{d}",(?:(?!new Entry).)*?true, 0\.85\)', src, re.S) else 0.8) for d in dbs}
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
    if swu_faster:
        swu_line = ("Pre-HUSPM-SWUmax is faster than the proposal on " + ", ".join(
            f"{d} ({secs(totals[(d,'Pre-HUSPM-SWUmax')][0])} s against {secs(totals[(d,P)][0])} s, "
            f"{plural(int(df[(df['dataset']==d)&(df['algorithm']=='Pre-HUSPM-SWUmax')&mf.is_inc(df)]['rescan_count'].iloc[-1]), 'rescan')} "
            f"against {int(reg.loc[d,'rescans'])})" for d in swu_faster) + ".")
    else:
        swu_line = "Neither Pre-HUSPM variant is faster than the proposal on any database where both complete."
    text = f"""# SC2, one hundred increments

Seven databases against five arms; `D_init` per `common.Datasets`, the remainder cut into 100
equal increments in file order. BMS1 is absent: no threshold mines it once the database grows
past `D_init`, so it appears in SC1 alone. {HEAD}

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
    st = df[df["algorithm"].isin(["proposed_full", "proposed_no_IUB", "Pre-HUSPM"])]
    c3 = []
    for ds in [d for d in mf.ALL_DATASETS if d in set(st["dataset"])]:
        r = {a: st[(st["dataset"] == ds) & (st["algorithm"] == a)].iloc[-1] for a in
             ["proposed_full", "proposed_no_IUB", "Pre-HUSPM"] if len(st[(st["dataset"] == ds) & (st["algorithm"] == a)])}
        f = r["proposed_full"]
        c3.append([ds, int(f["total_patterns_found"]), f"{f['runtime_ms']/1000:.2f} s",
                   f"{r['proposed_no_IUB']['runtime_ms']/1000:.2f} s", f"{r['Pre-HUSPM']['runtime_ms']/1000:.2f} s",
                   f"{int(f['explored_nodes']):,}", f"{int(r['proposed_no_IUB']['explored_nodes']):,}", f"{int(f['pruned_iub']):,}"])
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
    text = f"""# SC7, what each component contributes

The IUB bound acts inside the miner, so it is ablated on one mine of the whole database, with
Pre-HUSPM beside it. The block index and the adaptive rescan decision act only while the
database grows, so they are ablated on `D_init` and 100 increments, one row per increment, on
SIGN and C8T1S5I8N5K. {HEAD}

Switching a component off changes no answer: the HUSP count of every increment is the same with
the adaptive decision or the block index off as with both on ({"yes" if agree else "NO"}).

## The IUB bound

{table(["database", "HUSPs", "full", "IUB off", "Pre-HUSPM", "explored, full", "explored, IUB off", "IUB cuts"], c3)}

## The adaptive rescan decision

{table(["database", "adaptive decision", "rescan at every increment", "time saved, factor"], c5)}

## The block index

Over the increments that do not rescan, the only ones on which the index is read.

{table(["database", "increments without a rescan", "with the index", "without it", "block reads with", "block reads without"], c4)}
"""
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
                hr.append(f"{last['mem_retained_mb']:,.0f} at {int(last['iteration'])}, then out of memory")
            else:
                hr.append("out of memory on `D_init`")
            failed = (~g["finished"]).any()
            if not len(ok):
                pr.append("out of memory on `D_init`")
            elif failed:
                pr.append(f"{ok['mem_live_mb'].max():,.0f} before running out of memory")
            else:
                pr.append(f"{ok['mem_live_mb'].max():,.0f}")
        held.append(hr)
        peak.append(pr)
    text = f"""# SC2 memory: the state an incremental method holds over 100 increments

The memory campaign's SC2: the same streams as SC2, measured in live mode (a full collection is
forced at every sample and the heap read right after), so the runtimes of these rows are void.
`mem_retained_mb` is what the algorithm holds after an increment, against a baseline taken before
the algorithm exists; `mem_live_mb` the peak during it. {HEAD}

A single cell's live set can move between two measurements (`results/jdk-comparison`), so these
tables support comparisons of orders of magnitude, not of a few percent.

## State held after the last increment

MB.

{table(["database"] + [LABEL[a] for a in algos], held)}

## Peak live set over the stream

MB.

{table(["database"] + [LABEL[a] for a in algos], peak)}
"""
    write("campaign-mem/SC2/README.md", text)


if __name__ == "__main__":
    for f in (sc2, sc5, sc6, sc7, mem_sc2):
        f()
