#!/usr/bin/env python3
"""SC11, the log of Online Retail II replayed by day: the keys of the paper and the table of examples.

    python3 analysis/replay_log.py                       # campaign trees, keys to figures/campaign
    python3 analysis/replay_log.py --root DIR --no-done  # any tree holding SC11_long.csv files (a check)

Reads
    timing      <root>/SC11/ONLINERETAIL/<arm>/SC11_long.csv (with DONE) and the replicate trees
                results/campaign-replicates/s0r<n>/SC11, through long_streams (mean over the runs, the
                counters of every run equal to the main run's)
    memory      <mem-root>/SC11/ONLINERETAIL/<arm>/SC11_long.csv, -MemMode live: the live set per operation
    build       results-invariant/onlineretail-build/<date>/out/ONLINERETAIL_build.json
    staleness   results-invariant/stale-result/<date>-uut-<default UUT>/ (verify.StaleResult)
    patterns    results/verify-joint/husps-long-sc11/verify_husps.csv (-Dverify.husps)
    items       datasets/ONLINERETAIL_items.tsv (descriptions, for the examples)

Writes into --out
    table_orii_streams.csv     long_streams.stream_rows for SC11, one row per arm
    table_orii_examples.tex    the patterns that leave the result by a removal and enter it by a fall of the
                               threshold (verify.StaleResult's examples), block orii_examples of the manuscript
    paper_numbers_orii.csv     key, value, source

Comparisons: the time of an arm over the method's, summed over the operations after the initial mine, each
run in its own virtual machine; the ratio of the mean times, its class by the A/A tie band (above the band
the method is faster), and a percentile bootstrap interval over the runs with the resamples and seed of
scenario_tables (bootstrap_ratio_samples). One stream, so every class counts one comparison. Nothing is
rounded before a ratio is formed; a key holds the value as printed.
"""
import argparse
import glob
import json
import os
import re
import sys

import numpy as np
import pandas as pd

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import long_streams as ls  # noqa: E402
import make_figures as mf  # noqa: E402

SC = "SC11"
DB = "ONLINERETAIL"
TOTAL = "Joint-rule+total-counter"
ARMS = [ls.METHOD, ls.FIXED, ls.METHOD + "-w5", "Remine-from-scratch", "Remine-own-UUT", ls.REUSE, TOTAL]
INCREMENTAL = {ls.METHOD, ls.FIXED, ls.METHOD + "-w5", TOTAL}
TAG = dict(ls.TAG, **{TOTAL: "PIHUPMOD"})
INVARIANT = os.path.join(mf.REPO_DIR, "results-invariant")
VERIFY = os.path.join(mf.DATA_DIR, "verify-joint", "husps-long-sc11")


def newest(pattern):
    hits = sorted(glob.glob(pattern))
    if not hits:
        sys.exit(f"[{SC}] nothing matches {os.path.relpath(pattern, mf.REPO_DIR)}")
    return hits[-1]


def pct(a, b):
    return 100.0 * a / b


def build_keys(put):
    path = newest(os.path.join(INVARIANT, "onlineretail-build", "*", "out", "ONLINERETAIL_build.json"))
    b = json.load(open(path, encoding="utf-8"))
    src = os.path.relpath(path, mf.REPO_DIR)
    put("ORII_invoices", f"{b['raw_invoices']:,}", src)
    put("ORII_cancel_invoices", f"{b['raw_cancellation_invoices']:,}", src)
    put("ORII_cancel_share_invoices", f"{pct(b['raw_cancellation_invoices'], b['raw_invoices']):.1f}%", src)
    ops = b["operations"]
    put("ORII_ops", f"ins {ops.get('INS', 0)}, mod {ops.get('MOD', 0)}, del {ops.get('DEL', 0)}", src)
    put("ORII_operations", sum(ops.values()), src)
    put("ORII_days", b["days_with_operations"], src)
    put("ORII_mod_by_cancel", b["modifications_lowering_a_purchase"], src)
    L = b["lines"]
    put("ORII_cancel_lines", f"{L['from 2010-12-01: cancellation lines']:,}", src)
    put("ORII_cancel_removing", f"{L['from 2010-12-01: cancellations removing a purchase']:,}", src)
    put("ORII_cancel_lowering_only", f"{L['from 2010-12-01: cancellations lowering the units only (no change at internal utility 1)']:,}", src)
    put("ORII_cancel_unmatched", f"{L['from 2010-12-01: cancellations without a purchase (dropped)']:,}", src)
    put("ORII_cancel_unmatched_before", f"{L['before 2010-12-01: cancellations without a purchase (dropped)']:,}", src)
    for k in ("no customer", "stock code not a product", "price <= 0"):
        put("ORII_dropped_" + re.sub(r"\W+", "_", k).strip("_"), f"{L['dropped: ' + k]:,}", src)
    put("ORII_overlap_rows", f"{L['overlap rows checked identical']:,}", src)
    put("ORII_d_init_sequences", f"{b['d_init']['sequences']:,}", src)
    put("ORII_final_sequences", f"{b['final']['sequences']:,}", src)
    put("ORII_sequences_inserted", f"{b['sequences_inserted']:,}", src)
    put("ORII_sequences_modified", f"{b['sequences_modified']:,}", src)
    put("ORII_sequences_deleted", f"{b['sequences_deleted']:,}", src)
    return b


def stale_keys(put, uut):
    d = newest(os.path.join(INVARIANT, "stale-result", f"*-uut-{uut}"))
    src = os.path.relpath(os.path.join(d, "stale_by_day.csv"), mf.REPO_DIR)
    t = pd.read_csv(os.path.join(d, "stale_by_day.csv"))
    end = t.iloc[-1]
    put("ORII_result_size_end", int(end["result"]), src)
    put("ORII_stale_false_end", int(end["false_patterns"]), src)
    put("ORII_stale_missed_end", int(end["missed_patterns"]), src)
    i = int(t["wrong_patterns"].idxmax())
    put("ORII_stale_wrong_max", int(t.loc[i, "wrong_patterns"]), src)
    put("ORII_stale_wrong_max_day", t.loc[i, "day"], src)
    put("ORII_stale_days_with_wrong", f"{int((t['wrong_patterns'] > 0).sum())} of {len(t) - 1}", src)
    put("ORII_result_size_range", f"{int(t['result'].min())} to {int(t['result'].max())}", src)
    put("ORII_tu_without_cancellations_end_pct",
        f"{pct(end['total_utility_without_cancellations'] - end['total_utility'], end['total_utility']):.2f}%", src)
    if "utility_overstated_max_pct" in t:
        j = int(t["utility_overstated_max_pct"].idxmax())
        put("ORII_stale_utility_overstated_max_pct", f"{t.loc[j, 'utility_overstated_max_pct']:.2f}%", src)
        put("ORII_stale_utility_overstated_max_day", t.loc[j, "day"], src)
        put("ORII_stale_utility_overstated_end_max_pct", f"{end['utility_overstated_max_pct']:.2f}%", src)
    return d


def describe(pattern, items):
    """'12 (3 4) 5' -> the descriptions, elements in angle brackets as in the paper."""
    els = re.findall(r"\(([^)]*)\)|(\d+)", pattern)
    out = []
    for grp, single in els:
        ids = [int(x) for x in (grp.split() if grp else [single])]
        out.append(", ".join(tex(items[i]) for i in ids))
    return "$\\langle$" + "; ".join(f"({e})" for e in out) + "$\\rangle$"


def tex(s):
    s = s.strip().capitalize()
    return re.sub(r"([&%$#_{}])", r"\\\1", s).replace("~", "\\textasciitilde{}").replace("^", "\\textasciicircum{}")


def examples_table(stale_dir, out, put):
    """Block orii_examples: the examples of verify.StaleResult with the product descriptions."""
    ex = pd.read_csv(os.path.join(stale_dir, "stale_examples.csv"))
    items_path = os.path.join(mf.REPO_DIR, "datasets", "ONLINERETAIL_items.tsv")
    items = {int(r["item"]): r["description"] for _, r in pd.read_csv(items_path, sep="\t").iterrows()}
    src = "table_orii_examples.tex"
    put("ORII_examples_leave", int((ex["kind"] == "leave by removal").sum()), src)
    put("ORII_examples_enter", int((ex["kind"] == "enter by threshold").sum()), src)
    lines = ["\\begin{table}[!htbp]\\centering", "\\setlength{\\tabcolsep}{3pt}", "\\footnotesize",
             "\\caption{Patterns leaving and entering the result on SC11}\\label{tab:orii_examples}",
             "\\begin{tabular*}{\\textwidth}{@{\\extracolsep\\fill}llp{7.2cm}rr@{}}\\toprule",
             "Change & Day & Pattern & Utility & Threshold \\\\\\midrule"]
    for r in ex.itertuples():
        change = "leaves" if r.kind.startswith("leave") else "enters"
        lines.append(f"{change} & {r.day} & {describe(r.pattern, items)} & "
                     f"{r.utility_before:,.2f} $\\to$ {r.utility_after:,.2f} & "
                     f"{r.threshold_before:,.2f} $\\to$ {r.threshold_after:,.2f} \\\\")
    if len(ex) == 0:
        lines.append("\\multicolumn{5}{@{}l}{no pattern leaves or enters the result} \\\\")
    lines[-1] += "\\bottomrule"
    lines += ["\\end{tabular*}", "\\end{table}", ""]
    open(os.path.join(out, src), "w", encoding="utf-8").write("\n".join(lines))


def verify_keys(put):
    f = os.path.join(VERIFY, "verify_husps.csv")
    meta = os.path.join(VERIFY, "run_meta.json")
    if not (os.path.exists(f) and os.path.exists(meta)):
        print(f"[{SC}] no finished pattern check in {os.path.relpath(VERIFY, mf.REPO_DIR)}; ORII_verify_* not written")
        return
    m = json.load(open(meta, encoding="utf-8"))
    if m.get("exit_code") != 0:
        print(f"[{SC}] the pattern check ended with exit code {m.get('exit_code')}; ORII_verify_* not written")
        return
    v = pd.read_csv(f)
    src = os.path.relpath(f, mf.REPO_DIR)
    cmp_rows = v[v["status"] != "reference"]
    put("ORII_verify_comparisons", f"{len(cmp_rows):,}", src)
    put("ORII_verify_mismatches", int((cmp_rows["status"] != "match").sum()), src)
    put("ORII_verify_arms", "; ".join(sorted(set(cmp_rows["arm"]))), src)
    put("ORII_verify_reference_arm", "; ".join(sorted(set(v.loc[v["status"] == "reference", "arm"]))), src)
    put("ORII_verify_operations", int(v["iteration"].max()), src)


def run_totals(raw, arm):
    """The stream time (s) of each run of an arm that finished every operation."""
    a = raw[(raw["algorithm"] == arm) & (raw["kind"] != "INIT")]
    ok = a.groupby("replicate")["status"].apply(lambda s: bool((s == "OK").all()))
    return [float(g["runtime_ms"].sum()) / 1000.0 for tag, g in a.groupby("replicate") if ok[tag]]


def timing_keys(put, raw, df, runs_of, out):
    import scenario_tables as st
    rows = ls.stream_rows(SC, df, runs_of, None, arms=ARMS, incremental=INCREMENTAL, datasets=[DB])
    t = pd.DataFrame(rows)
    mf.write_csv(t, os.path.join(out, "table_orii_streams.csv"))
    src = "table_orii_streams.csv"
    band = st.aa_band()
    if band is None:
        sys.exit(f"[{SC}] no A/A band")
    r_aa = band["r"]
    m_runs = run_totals(raw, ls.METHOD)
    runs = []
    for r in t.to_dict("records"):
        tag = TAG[r["arm"]]
        n = len(run_totals(raw, r["arm"]))
        runs.append(n)
        put(f"ORII_{tag}_runs", n, src)
        if pd.notna(r.get("stream_s_mean")):
            put(f"ORII_{tag}_stream_s", f"{r['stream_s_mean']:.2f}", src)
        if pd.notna(r.get("latency_ms_p99")):
            put(f"ORII_p99_{tag}_ms", f"{r['latency_ms_p99']:.3f}", src)
            put(f"ORII_median_{tag}_ms", f"{r['latency_ms_median']:.3f}", src)
        if r["arm"] in INCREMENTAL:
            put(f"ORII_rescans_{tag}", int(r["rescans"]), src)
            put(f"ORII_rescans_at_threshold_{tag}", int(r["rescans_at_uut"]), src)
        if r["arm"] == ls.METHOD:
            put("ORII_rescans", int(r["rescans"]), src)
            put("ORII_rescans_at_threshold", int(r["rescans_at_uut"]), src)
            continue
        a_runs = run_totals(raw, r["arm"])
        if not a_runs or not m_runs:
            print(f"[{SC}] {r['arm']}: no finished run; no ratio")
            continue
        ratio = float(np.mean(a_runs) / np.mean(m_runs))           # the arm's time over the method's
        cls = "win" if ratio > r_aa else ("loss" if ratio < 1 / r_aa else "tie")
        put(f"classes_aa_ORII_vs_{tag}", f"wins {int(cls == 'win')}, ties {int(cls == 'tie')}, losses {int(cls == 'loss')} of 1", src)
        put(f"ORII_{tag}_over_method", f"{ratio:.3f}", src)
        put(f"ORII_method_over_{tag}", f"{1 / ratio:.3f}", src)
        k = f"summary_ratio_ORII_{tag}"
        put(f"{k}_geomean", f"{ratio:.3f}", src)
        single = min(len(a_runs), len(m_runs)) < 2
        if not single:
            lo, hi = np.percentile(st.bootstrap_ratio_samples(a_runs, m_runs), [2.5, 97.5])
            put(f"{k}_ci95", f"{lo:.3f} to {hi:.3f}", src)
        put(f"{k}_comparisons", 1, src)
        put(f"{k}_single_run_comparisons", int(single), src)
    put("ORII_runs_per_arm", min(runs) if runs else 0, src)
    put("ORII_runs_per_arm_range", f"{min(runs)} to {max(runs)}" if runs else "0", src)


def memory_keys(put, mem_root, no_done):
    root = os.path.join(mem_root, SC)
    if not os.path.isdir(root):
        print(f"[{SC}] no memory tree {os.path.relpath(root, mf.REPO_DIR)}; ORII_mem_* not written")
        return
    mem = ls.load(root, SC, need_done=not no_done)
    if mem is None:
        print(f"[{SC}] no memory rows; ORII_mem_* not written")
        return
    src = os.path.relpath(root, mf.REPO_DIR)
    for arm, g in mem.groupby("algorithm"):
        if arm not in TAG:
            continue
        ops = g[g["kind"] != "INIT"]
        if "mem_live_mb" not in ops or ops["mem_live_mb"].isna().all():
            continue
        put(f"ORII_mem_{TAG[arm]}_max_mb", f"{ops['mem_live_mb'].max():.1f}", src)
        put(f"ORII_mem_{TAG[arm]}_final_mb", f"{ops.sort_values('iteration')['mem_live_mb'].iloc[-1]:.1f}", src)


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=os.path.join(mf.DATA_DIR, mf.TIMING_TREE))
    ap.add_argument("--mem-root", default=os.path.join(mf.DATA_DIR, "campaign-mem"))
    ap.add_argument("--out", default=mf.OUT_DIR)
    ap.add_argument("--no-done", action="store_true", help="read arms without a DONE file")
    a = ap.parse_args(argv)
    os.makedirs(a.out, exist_ok=True)
    nums = []

    def put(key, value, source):
        nums.append(dict(key=key, value=value, source=source))

    build_keys(put)
    uut = mf.DATASETS[DB]["default_uut"]
    put("ORII_uut_pct", f"{100 * uut:.3f}", "src/common/Datasets.java")
    stale_dir = stale_keys(put, f"{uut:g}")
    examples_table(stale_dir, a.out, put)
    verify_keys(put)
    root = a.root if a.no_done else os.path.join(a.root, SC)
    default_root = os.path.abspath(a.root) == os.path.abspath(os.path.join(mf.DATA_DIR, mf.TIMING_TREE))
    reps = [(os.path.basename(p), os.path.join(p, SC))
            for p in sorted(glob.glob(os.path.join(mf.DATA_DIR, mf.REPLICATE_TREE, ls.REPLICATE_RUN + "*")))
            if default_root and os.path.isdir(os.path.join(p, SC))]
    raw = ls.load(root, SC, need_done=not a.no_done, replicate_roots=reps) if os.path.isdir(root) else None
    if raw is None:
        print(f"[{SC}] no timing rows under {os.path.relpath(root, mf.REPO_DIR)}; timing keys not written")
    else:
        df, runs_of = ls.mean_over_runs(SC, raw)
        timing_keys(put, raw, df, runs_of, a.out)
    memory_keys(put, a.mem_root, a.no_done)
    k = pd.DataFrame(nums)
    if k["key"].duplicated().any():
        sys.exit(f"[{SC}] a key written twice: {sorted(set(k.loc[k['key'].duplicated(), 'key']))}")
    mf.write_csv(k, os.path.join(a.out, "paper_numbers_orii.csv"))
    print(f"[{SC}] {len(k)} keys -> {os.path.relpath(os.path.join(a.out, 'paper_numbers_orii.csv'), mf.REPO_DIR)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
