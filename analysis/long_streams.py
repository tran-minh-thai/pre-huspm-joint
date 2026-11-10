#!/usr/bin/env python3
"""Tables of the long mixed streams (SC8, SC8P, SC9, SC10, SC10H; 500 operations, three seeds).

    python3 analysis/long_streams.py                    # campaign trees under results/, to OUT_DIR
    python3 analysis/long_streams.py --root DIR --out DIR --no-done
                                                        # any tree holding <SC>_long.csv files

Reads every <SC>_long.csv under results/campaign/<SC>/<database>/<arm>/ (an arm counts only with
its DONE file) and, for the index columns and the live set, under results/campaign-mem/SC9/.
The arm of a row is its `algorithm` column and its seed its `seed` column, so a verification run
holding all arms in one file is read the same way.

Replicates: with the default root, the trees results/campaign-replicates/s0r<n>/<SC>/ are read too
(run s0r0 is the main tree). Their counters (rescans, rescans at UUT, patterns, explored nodes,
pre-large set) must equal the main run's on every row, or the script stops. A run of an (arm, seed)
is used when every one of its rows finished; the time of an operation is then the mean over the
used runs, the counters are the main run's, and `runs` gives the number of runs averaged. With one
run per (arm, seed) the tables are those of a single run.

The method over an arm: two arms of a run were measured in one session when their DONE files were
written at most SESSION_HOURS apart (the arms of one campaign step run back to back). Where a
(database, arm) has such runs, the ratio is taken inside each of them (the method's stream time
over the arm's, summed over the seeds both finished) and `method_over_arm_pooled` is the geometric
mean of those ratios, with their smallest and largest, the number of runs and in how many the
method was the faster; `ratio_basis` is then `paired`. Runs measured in different sessions take no
part in it: on a database whose times move between sessions, a ratio across sessions measures the
sessions. Without such a run the ratio is that of the mean times, as before (`ratio_basis` is
`pooled`). A gap between SESSION_HOURS and OTHER_SESSION_HOURS is neither and stops the script. Writes:

    table_long_streams.csv   one row per (scenario, database, arm): seeds, operations per seed,
                             time of the stream (operations after the initial mine, summed) per
                             seed as mean, minimum and maximum, the pooled time of the method over
                             the arm's (sum over the seeds of the method's stream time divided by
                             the same sum for the arm, on the seeds both finished; paired or pooled
                             over the runs as described above), the runs averaged
                             per seed and, when there is more than one, the stream time summed
                             over the seeds in each run (smallest and largest run), per-operation
                             latency over all operations of all seeds (median, 95th and 99th
                             percentiles by linear interpolation, maximum; from runtime_us, in
                             ms, or from runtime_ms on rows without that column), and per kind of
                             operation (insertion, deletion, modification): operations, rescans,
                             direct updates, direct share, time per seed; for the incremental arms
                             the rescans at UUT and at the low floor LUT_min, summed over the seeds
    table_long_index.csv     the incremental arms every 50 operations, per seed: blocks of the
                             block index, live sequences of the database, bits set in the item
                             bitmaps, bits those bitmaps hold, pre-large set; from the timing tree
                             and, when present, the live set (MB) from the memory tree
    paper_numbers_long.csv   key, value, source for every number above that the paper may quote

The floor of a rescan is either UUT or LUT_min (PreHUSPMAdaptiveAlgorithm sets Sl_min to Su or to
slMinRatioEff * Su), so the rescans at LUT_min are the rescans minus those at UUT. rescan_count and
rescans_at_uut are cumulative within a stream; an operation rescanned when rescan_count grew.
"""
import argparse
import glob
import os
import re
import sys

import numpy as np
import pandas as pd

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import make_figures as mf  # noqa: E402

SCENARIOS = ["SC8", "SC8P", "SC9", "SC10", "SC10H"]
METHOD = mf.PROPOSED
FIXED = mf.FIXED
REBUILD = METHOD + "-index-rebuild"
ARMS = [METHOD, FIXED, METHOD + "-w5", "Remine-from-scratch", "Remine-own-UUT", REBUILD]
INCREMENTAL = {METHOD, FIXED, METHOD + "-w5", REBUILD}
TAG = {METHOD: "Method", FIXED: "FixedFloor", METHOD + "-w5": "Window5", "Remine-from-scratch": "Remine",
       "Remine-own-UUT": "RemineUUT", REBUILD: "IndexRebuild"}
KINDS = {"INS": "insertion", "DEL": "deletion", "MOD": "modification"}
INDEX_STEP = 50
REPLICATE_RUN = "s0r"          # replicates of the long streams: seed index 0, repeat n
SESSION_HOURS = 6.0            # DONE files at most this far apart: the two arms ran in one session
OTHER_SESSION_HOURS = 24.0     # at least this far apart: different sessions; in between, undecided
FINISHED = re.compile(r"finished=(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2})")
COUNTERS = ["rescan_count", "rescans_at_uut", "total_patterns_found", "explored_nodes", "prelarge_count"]
KEY = ["algorithm", "dataset", "seed", "iteration"]


def finished_at(arm_dir):
    """When the arm's DONE file says the run finished, or NaT without a DONE file or a time in it."""
    try:
        with open(os.path.join(arm_dir, "DONE"), encoding="utf-8") as fh:
            m = FINISHED.search(fh.read())
    except OSError:
        return pd.NaT
    return pd.Timestamp(m.group(1)) if m else pd.NaT


def load(root, sc, need_done=True, replicate_roots=()):
    """Rows of one scenario under root, from every <sc>_long.csv below it, tagged with the run
    (`replicate`): s0r0 for root, the tree's name for each (name, directory) of replicate_roots."""
    frames = []
    for tag, base in [("s0r0", root)] + list(replicate_roots):
        for p in sorted(glob.glob(os.path.join(base, "**", f"{sc}_long.csv"), recursive=True)):
            if need_done and not os.path.exists(os.path.join(os.path.dirname(p), "DONE")):
                print(f"[{sc}] left out, no DONE: {os.path.relpath(p, base)} ({tag})")
                continue
            frames.append(pd.read_csv(p).assign(replicate=tag, finished=finished_at(os.path.dirname(p))))
    if not frames:
        return None
    df = pd.concat(frames, ignore_index=True)
    df.columns = [c.strip() for c in df.columns]
    df = mf.apply_rivals(df, sc, "algorithm")
    df["status"] = [mf.classify_status(o, r) for o, r in zip(df["op_type"], df["runtime_ms"])]
    df["kind"] = df["op_type"].astype(str).str.extract(r"SC6_(INS|DEL|MOD|INIT)")[0]
    dup = df.duplicated(KEY + ["replicate"], keep=False)
    if dup.any():
        sys.exit(f"[{sc}] {int(dup.sum())} rows repeat an (arm, database, seed, operation, run)")
    return df


def mean_over_runs(sc, df):
    """One row per (arm, database, seed, operation): the main run's row, with runtime_ms and
    runtime_us the mean over the runs of that (arm, seed) whose rows all finished. Returns the rows
    and, per (arm, database, seed), the stream time (operations after the initial mine) of each run
    used. Stops when a replicate has no main run, other operations, or other counters."""
    main = df[df["replicate"] == "s0r0"].set_index(KEY).sort_index()
    have = set(main.index.droplevel(3))
    for (tag, alg, d, seed), x in df[df["replicate"] != "s0r0"].groupby(["replicate", "algorithm", "dataset", "seed"]):
        if (alg, d, seed) not in have:
            sys.exit(f"[{sc}] {tag}: {alg} {d} seed {seed} has no main run")
        m = main.loc[(alg, d, seed)]
        x = x.set_index("iteration").sort_index()
        if not x.index.equals(m.index):
            sys.exit(f"[{sc}] {tag}: {alg} {d} seed {seed}: the operations differ from the main run's")
        for c in COUNTERS:
            if c in x and not np.array_equal(x[c].fillna(-1).to_numpy(float), m[c].fillna(-1).to_numpy(float)):
                sys.exit(f"[{sc}] {tag}: {alg} {d} seed {seed}: {c} differs from the main run's")
    run = ["algorithm", "dataset", "seed", "replicate"]
    used = df.groupby(run)["status"].transform(lambda s: bool((s == "OK").all())).astype(bool)
    ok = df[used]
    streams = {k: list(g[g["kind"] != "INIT"].groupby("replicate")["runtime_ms"].sum() / 1000.0)
               for k, g in ok.groupby(["algorithm", "dataset", "seed"])}
    out = main.copy()
    agg = ok.groupby(KEY).agg(mean_ms=("runtime_ms", "mean"), runs=("replicate", "nunique"))
    idx = out.index.intersection(agg.index)
    out["runs"] = 0
    out.loc[idx, "runs"] = agg.loc[idx, "runs"].astype(int)
    # Only rows averaged over more than one run, or whose main run did not finish, change, so a
    # single run reads exactly as before.
    multi = idx[(agg.loc[idx, "runs"].to_numpy() > 1) | (out.loc[idx, "status"].to_numpy() != "OK")]
    out.loc[multi, "status"] = "OK"
    if len(multi):
        out["runtime_ms"] = out["runtime_ms"].astype(float)
        out.loc[multi, "runtime_ms"] = agg.loc[multi, "mean_ms"]
        if "runtime_us" in ok:
            out["runtime_us"] = out["runtime_us"].astype(float)
            out.loc[multi, "runtime_us"] = ok.groupby(KEY)["runtime_us"].mean().loc[multi]
    return out.reset_index(), streams


def paired_ratios(sc, df):
    """Per (database, arm other than the method): for every run in which the method and the arm were
    measured in one session, (run, the method's stream time over the arm's on the seeds both
    finished, hours between their DONE files). Runs without a finish time take no part."""
    if "finished" not in df or df["finished"].isna().all():
        return {}
    ops = df[df["kind"] != "INIT"]
    run = ["replicate", "dataset", "algorithm", "seed"]
    ok = ops[ops.groupby(run)["status"].transform(lambda s: bool((s == "OK").all())).astype(bool)]
    t = ok.groupby(run)["runtime_ms"].sum() / 1000.0
    fin = df.groupby(["replicate", "dataset", "algorithm"])["finished"].first()
    out = {}
    for (tag, d), g in t.groupby(level=["replicate", "dataset"]):
        g = g.droplevel(["replicate", "dataset"])
        arms = set(g.index.get_level_values("algorithm"))
        if METHOD not in arms or pd.isna(fin.get((tag, d, METHOD), pd.NaT)):
            continue
        m = g.loc[METHOD]
        for arm in sorted(arms - {METHOD}):
            a = g.loc[arm]
            common = m.index.intersection(a.index)
            fa = fin.get((tag, d, arm), pd.NaT)
            if not len(common) or pd.isna(fa) or a[common].sum() <= 0:
                continue
            gap = abs((fin[(tag, d, METHOD)] - fa).total_seconds()) / 3600.0
            if SESSION_HOURS < gap < OTHER_SESSION_HOURS:
                sys.exit(f"[{sc}] {tag}: {METHOD} and {arm} on {d} finished {gap:.1f} h apart, between "
                         f"{SESSION_HOURS:g} h (one session) and {OTHER_SESSION_HOURS:g} h (two): undecided")
            if gap <= SESSION_HOURS:
                out.setdefault((d, arm), []).append((tag, float(m[common].sum() / a[common].sum()), gap))
    return out


def latency_ms(x):
    """Per-operation times in ms, from the microsecond column when every row has it."""
    if "runtime_us" in x and x["runtime_us"].notna().all():
        return x["runtime_us"].to_numpy(float) / 1000.0
    return x["runtime_ms"].to_numpy(float)


def stream_rows(sc, df, runs_of=None, paired=None):
    """runs_of: per (arm, database, seed), the stream time of each run averaged (mean_over_runs).
    paired: per (database, arm), the same-session ratios of paired_ratios."""
    rows = []
    for d in [x for x in mf.ALL_DATASETS if x in set(df["dataset"])]:
        g = df[df["dataset"] == d]
        per_seed = {}
        for arm in [a for a in ARMS if a in set(g["algorithm"])]:
            a = g[g["algorithm"] == arm].sort_values(["seed", "iteration"])
            ops = a[a["kind"] != "INIT"]
            done = {s: x for s, x in ops.groupby("seed") if (x["status"] == "OK").all()}
            per_seed[arm] = {s: x["runtime_ms"].sum() / 1000.0 for s, x in done.items()}
            lat = np.concatenate([latency_ms(x) for x in done.values()]) if done else np.array([])
            r = dict(scenario=sc, dataset=d, arm=arm, seeds=len(done),
                     seeds_unfinished=ops["seed"].nunique() - len(done),
                     operations_per_seed=int(ops.groupby("seed").size().max()) if len(ops) else 0)
            t = list(per_seed[arm].values())
            r.update(stream_s_mean=np.mean(t) if t else np.nan, stream_s_min=min(t) if t else np.nan,
                     stream_s_max=max(t) if t else np.nan)
            runs = [len(runs_of[(arm, d, s)]) if runs_of and (arm, d, s) in runs_of else 1 for s in done]
            r["runs"] = min(runs) if runs else 0
            if runs and min(runs) > 1:
                per_run = np.sum([runs_of[(arm, d, s)][:min(runs)] for s in sorted(done)], axis=0)
                r.update(run_total_s_min=float(per_run.min()), run_total_s_max=float(per_run.max()))
            if len(lat):
                r["latency_source"] = "runtime_us" if "runtime_us" in a and a["runtime_us"].notna().all() else "runtime_ms"
                r.update(latency_ms_median=np.percentile(lat, 50), latency_ms_p95=np.percentile(lat, 95),
                         latency_ms_p99=np.percentile(lat, 99), latency_ms_max=lat.max(), latency_ops=len(lat))
            resc = pd.concat([x["rescan_count"].diff().fillna(x["rescan_count"]) > 0 for _, x in a.groupby("seed")]) \
                if arm in INCREMENTAL else pd.Series(False, index=a.index)
            a = a.assign(rescanned=resc.reindex(a.index))
            for k, name in KINDS.items():
                ak = a[(a["kind"] == k) & a["seed"].isin(done)]
                r[f"{name}_operations"] = len(ak)
                r[f"{name}_s_per_seed"] = ak["runtime_ms"].sum() / 1000.0 / max(1, len(done))
                if arm in INCREMENTAL:
                    r[f"{name}_rescans"] = int(ak["rescanned"].sum())
                    r[f"{name}_direct"] = int((~ak["rescanned"]).sum())
                    r[f"{name}_direct_share"] = r[f"{name}_direct"] / len(ak) if len(ak) else np.nan
            if arm in INCREMENTAL:
                last = a[a["seed"].isin(done)].groupby("seed").tail(1)
                # the initial mine is not a rescan, so the counters at the last operation are the stream's
                r["rescans"] = int(last["rescan_count"].sum())
                r["rescans_at_uut"] = int(last["rescans_at_uut"].sum())
                r["rescans_at_lut_min"] = r["rescans"] - r["rescans_at_uut"]
            rows.append(r)
        for r in rows:
            if r["scenario"] != sc or r["dataset"] != d or METHOD not in per_seed:
                continue
            m, o = per_seed[METHOD], per_seed[r["arm"]]
            common = sorted(set(m) & set(o))
            r["pooled_seeds"] = len(common)
            den = sum(o[s] for s in common)
            r["method_over_arm_pooled"] = sum(m[s] for s in common) / den if common and den > 0 else np.nan
            r["ratio_basis"] = "pooled"
            p = (paired or {}).get((d, r["arm"]))
            if p:
                q = np.array([x[1] for x in p])
                r.update(method_over_arm_pooled=float(np.exp(np.log(q).mean())), ratio_basis="paired",
                         paired_runs=len(q), paired_min=float(q.min()), paired_max=float(q.max()),
                         paired_method_faster=int((q < 1).sum()), paired_gap_h_max=max(x[2] for x in p),
                         paired_run_names=" ".join(x[0] for x in p))
    return rows


def index_rows(sc, df, mem):
    rows = []
    for (d, arm, seed), a in df[df["algorithm"].isin(INCREMENTAL)].groupby(["dataset", "algorithm", "seed"]):
        a = a.set_index("iteration")
        mm = None
        if mem is not None:
            mm = mem[(mem["dataset"] == d) & (mem["algorithm"] == arm) & (mem["seed"] == seed)].set_index("iteration")
        for it in range(0, int(a.index.max()) + 1, INDEX_STEP):
            if it not in a.index:
                continue
            x = a.loc[it]
            r = dict(scenario=sc, dataset=d, arm=arm, seed=int(seed), operation=it,
                     index_blocks=int(x["index_blocks"]), live_sequences=int(x["live_sequences"]),
                     blocks_per_live_sequence=x["index_blocks"] / x["live_sequences"] if x["live_sequences"] > 0 else np.nan,
                     index_set_bits=int(x["index_set_bits"]), index_allocated_bits=int(x["index_allocated_bits"]),
                     prelarge=int(x["prelarge_count"]), rescans_so_far=int(x["rescan_count"]))
            if mm is not None and it in mm.index:
                r["mem_live_mb"] = float(mm.loc[it, "mem_live_mb"])
            rows.append(r)
    return rows


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=os.path.join(mf.DATA_DIR, mf.TIMING_TREE))
    ap.add_argument("--mem-root", default=os.path.join(mf.DATA_DIR, "campaign-mem"))
    ap.add_argument("--out", default=mf.OUT_DIR)
    ap.add_argument("--no-done", action="store_true", help="read arms without a DONE file")
    args = ap.parse_args(argv)
    streams, index, nums = [], [], []

    def put(key, value, source):
        nums.append(dict(key=key, value=value, source=source))

    for sc in SCENARIOS:
        root = args.root if args.no_done or os.path.basename(args.root.rstrip("/")) == sc else os.path.join(args.root, sc)
        default_root = os.path.abspath(args.root) == os.path.abspath(os.path.join(mf.DATA_DIR, mf.TIMING_TREE))
        reps = [(os.path.basename(p), os.path.join(p, sc))
                for p in sorted(glob.glob(os.path.join(mf.DATA_DIR, mf.REPLICATE_TREE, REPLICATE_RUN + "*")))
                if default_root and os.path.isdir(os.path.join(p, sc))]
        raw = load(root, sc, need_done=not args.no_done, replicate_roots=reps)
        if raw is None:
            print(f"[{sc}] no rows under {root}")
            continue
        df, runs_of = mean_over_runs(sc, raw)
        if reps:
            print(f"[{sc}] runs read: s0r0 and {', '.join(t for t, _ in reps)}")
        mem = None
        mroot = os.path.join(args.mem_root, sc)
        if os.path.isdir(mroot):
            mem = load(mroot, sc, need_done=not args.no_done)
        streams += stream_rows(sc, df, runs_of, paired_ratios(sc, raw))
        index += index_rows(sc, df, mem)
    os.makedirs(args.out, exist_ok=True)
    st, ix = pd.DataFrame(streams), pd.DataFrame(index)
    mf.write_csv(st, os.path.join(args.out, "table_long_streams.csv"))
    mf.write_csv(ix, os.path.join(args.out, "table_long_index.csv"))
    src = "table_long_streams.csv"
    for r in st.to_dict("records"):
        b = f"LONG_{r['scenario']}_{r['dataset']}_{TAG[r['arm']]}"
        put(f"{b}_seeds", r["seeds"], src)
        if r.get("runs", 1) > 1:
            put(f"{b}_runs", r["runs"], src)
            put(f"{b}_stream_s_runs", f"{r['run_total_s_min']:.2f} to {r['run_total_s_max']:.2f}", src)
        if pd.notna(r.get("stream_s_mean")):
            put(f"{b}_stream_s", f"{r['stream_s_mean']:.2f} ({r['stream_s_min']:.2f} to {r['stream_s_max']:.2f})", src)
        for q in ("median", "p95", "p99", "max"):
            if pd.notna(r.get(f"latency_ms_{q}")):
                put(f"{b}_latency_ms_{q}", f"{r[f'latency_ms_{q}']:.3f}", src)
        if r["arm"] != METHOD and pd.notna(r.get("method_over_arm_pooled")):
            put(f"{b}_method_over_arm_pooled", f"{r['method_over_arm_pooled']:.3f}", src)
            put(f"{b}_method_over_arm_basis", r["ratio_basis"], src)
            if r["ratio_basis"] == "paired":
                put(f"{b}_method_over_arm_paired_range", f"{r['paired_min']:.3f} to {r['paired_max']:.3f}", src)
                put(f"{b}_method_over_arm_paired_runs", int(r["paired_runs"]), src)
                put(f"{b}_method_over_arm_paired_method_faster",
                    f"{int(r['paired_method_faster'])} of {int(r['paired_runs'])}", src)
        for name in KINDS.values():
            put(f"{b}_{name}_s_per_seed", f"{r[f'{name}_s_per_seed']:.2f}", src)
            if r["arm"] in INCREMENTAL:
                put(f"{b}_{name}_rescans", f"{r[f'{name}_rescans']} of {r[f'{name}_operations']}", src)
                if pd.notna(r.get(f"{name}_direct_share")):
                    put(f"{b}_{name}_direct_share", f"{100 * r[f'{name}_direct_share']:.1f}%", src)
        if r["arm"] in INCREMENTAL:
            put(f"{b}_rescans", r["rescans"], src)
            put(f"{b}_rescans_at_uut", r["rescans_at_uut"], src)
            put(f"{b}_rescans_at_lut_min", r["rescans_at_lut_min"], src)
    if len(ix):
        last = ix.sort_values("operation").groupby(["scenario", "dataset", "arm", "seed"]).tail(1)
        for (sc, d, arm), g in last.groupby(["scenario", "dataset", "arm"]):
            b = f"LONG_{sc}_{d}_{TAG[arm]}"
            put(f"{b}_final_blocks_per_live_sequence",
                f"{g['blocks_per_live_sequence'].min():.2f} to {g['blocks_per_live_sequence'].max():.2f}", "table_long_index.csv")
    mf.write_csv(pd.DataFrame(nums), os.path.join(args.out, "paper_numbers_long.csv"))
    print(f"streams: {len(st)} rows, index: {len(ix)} rows, keys: {len(nums)} -> {args.out}")


if __name__ == "__main__":
    main()
