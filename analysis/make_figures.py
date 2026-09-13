# -*- coding: utf-8 -*-
# =============================================================================
#  make_figures.py  -  Aggregation + academic figures for the HUSP experiment
#  Q1-journal pipeline (Google Colab OR local).  Reads SC1..SC7 CSVs produced by
#  AutomatedExperimentRunner.java (31-column schema, incl. `explored_nodes`).
#
#  Design notes
#  ------------
#  * The requirements file (yeucautonghopthucnghiem.txt) is a GUIDE; this script
#    is fitted to the ACTUAL data structure of each scenario (see SCENARIO MAP).
#  * Core deps: pandas + matplotlib + numpy (always present). seaborn is OPTIONAL
#    (used for nicer heatmaps; falls back to matplotlib.imshow when absent).
#  * Every figure is saved as VECTOR PDF (LaTeX-ready), serif font, DPI 300.
#  * Robust to: missing files (SC6/SC7 not yet run), missing `explored_nodes`
#    column (old 30-col CSVs), and OOM/OT rows (runtime_ms < 0).
#
#  SCENARIO MAP (algorithm column / key parsing)
#  ---------------------------------------------
#   SC1 static     : 6 ds × suSweep × {USpan,HUSP-ULL,IncUSP-Miner+,Pre-HUSPM,Adaptive-Full}
#                    su  <- op_type "SC1_su=<v>"             ; iteration=0
#   SC2 incremental: 6 ds × {IncUSP,Pre-HUSPM,Adaptive}      ; 5 EVEN batches
#                    phase/iter <- iteration 0..5, op_type "SC2_INIT/INC"
#   SC3 su-sens    : SUBSET3 × suSweep × 5 algos (full DB)   ; su <- "SC3_su=<v>"
#   SC4 sl-sens    : SUBSET3 × sl{0.3,0.5,0.7,0.9} × {Pre-HUSPM[sl=x],Adaptive[sl=x]}
#                    sl  <- regex "[sl=<v>]" in algorithm
#   SC5 batch grid : SUBSET3 × K{3,5,10} × strat{EVEN,INCREASING,DECREASING,BURST}
#                    K,strat <- op_type "SC5_K=<k>_strat=<s>_INIT/INC"
#   SC6 mixed IDM  : SUBSET3 × Adaptive-Full ; 10 ops 70:15:15
#                    op  <- op_type "SC6_INIT/INS/DEL/MOD", iteration 0..10
#   SC7 ablation   : 6 ds × {proposed_full,no_IUB(-C3),
#                    no_block_index(-C4),C5_on,C5_off(-C5),Pre-HUSPM(ref)}
#
#  Sentinels: runtime_ms == -1 -> FAILED ; == -2 -> OT (>60 min) ; both = OOM/Fail.
# =============================================================================

# %% ------------------------------------------------------------------ Cell 1
#    SETUP, CONFIG, STYLE
import os, re, sys, warnings
import numpy as np
import pandas as pd
import matplotlib
matplotlib.use("Agg")  # headless-safe; Colab/inline still works
import matplotlib.pyplot as plt
from matplotlib.ticker import LogLocator

# seaborn is optional (heatmaps). Fallback handled in plot_heatmap().
try:
    import seaborn as sns
    HAS_SNS = True
except Exception:
    HAS_SNS = False

# ---- Data / output locations -------------------------------------------------
# Resolution order (single source of truth for local + Colab):
#   1) env HUSP_DATA_DIR / HUSP_OUT_DIR  (run.sh and the Colab notebook set these)
#   2) Colab default placeholder (edit PROJECT below if not using env)
#   3) local: <repo>/results and <repo>/figures
IN_COLAB = "google.colab" in sys.modules
DATA_DIR = os.environ.get("HUSP_DATA_DIR")
OUT_DIR  = os.environ.get("HUSP_OUT_DIR")
if not DATA_DIR:
    if IN_COLAB:
        from google.colab import drive            # noqa
        drive.mount("/content/drive", force_remount=False)
        PROJECT  = "/content/drive/MyDrive/.../pre-huspm-adaptive"  # <-- EDIT if not using env
        DATA_DIR = PROJECT + "/results"
        OUT_DIR  = OUT_DIR or (PROJECT + "/figures")
    else:
        HERE     = os.path.dirname(os.path.abspath(__file__))
        ROOT     = os.path.dirname(HERE)
        DATA_DIR = os.path.join(ROOT, "results")
        OUT_DIR  = OUT_DIR or os.path.join(ROOT, "figures")
if not OUT_DIR:
    OUT_DIR = os.path.join(os.path.dirname(DATA_DIR.rstrip("/")), "figures")
os.makedirs(OUT_DIR, exist_ok=True)

# ---- IEEE/ACM-compliant aesthetics ------------------------------------------
plt.rcParams.update({
    "font.family":       "serif",
    "font.serif":        ["Times New Roman", "Times", "DejaVu Serif"],
    "mathtext.fontset":  "stix",
    "font.size":         9,
    "axes.titlesize":    9,
    "axes.labelsize":    9,
    "legend.fontsize":   7.5,
    "xtick.labelsize":   8,
    "ytick.labelsize":   8,
    "axes.grid":         True,
    "grid.alpha":        0.30,
    "grid.linestyle":    ":",
    "axes.axisbelow":    True,
    "figure.dpi":        300,
    "savefig.dpi":       300,
    "savefig.bbox":      "tight",
    "pdf.fonttype":      42,   # editable text in vector PDF (TrueType)
    "ps.fonttype":       42,
})

# ---- Canonical algorithm order, display labels, colors, markers --------------
ALGO_ORDER = ["USpan", "HUSP-ULL", "IncUSP-Miner+", "Pre-HUSPM", "Adaptive-Full"]
ALGO_LABEL = {
    "USpan": "USpan", "HUSP-ULL": "HUSP-ULL", "IncUSP-Miner+": "IncUSP-Miner+",
    "Pre-HUSPM": "Pre-HUSPM", "Adaptive-Full": "Proposed",
}
ALGO_COLOR = {
    "USpan": "#7f7f7f", "HUSP-ULL": "#1f77b4", "IncUSP-Miner+": "#2ca02c",
    "Pre-HUSPM": "#ff7f0e", "Adaptive-Full": "#d62728",
}
ALGO_MARKER = {
    "USpan": "o", "HUSP-ULL": "s", "IncUSP-Miner+": "^",
    "Pre-HUSPM": "D", "Adaptive-Full": "*",
}
PROPOSED = "Adaptive-Full"

# 3 representative datasets for ALL figures (= runner SUBSET_3: dense+sparse mix)
# -> every multi-dataset figure has exactly 3 sub-plots (readable at journal width).
# Full 6-dataset results live in the TABLES (table_SC1_summary / table_SC7_ablation).
FIG_DATASETS = ["SIGN", "LEVIATHAN", "KOSARAK"]

# SC7 ablation variant -> short label / module
SC7_LABEL = {
    "proposed_full":         "Full",
    "proposed_no_IUB":       "$-$IUB\n(C3)",
    "proposed_no_block_index": "$-$Block\n(C4)",
    "proposed_C5_off":       "$-$CUB\n(C5)",
    "proposed_C5_on":        "Full$+$",
    "Pre-HUSPM":             "Pre-\nHUSPM",
}

# Metrics we aggregate (requirement: mean/std of these)
AGG_METRICS = ["runtime_ms", "peak_heap_mb", "candidate_count",
               "explored_nodes", "rescan_count"]


# %% ------------------------------------------------------------------ Cell 2
#    LOADING + PARSING + AGGREGATION HELPERS
def load_csv(name):
    """Load one scenario CSV; return None if missing. Normalize schema so the
    rest of the pipeline never KeyErrors on absent columns (old 30-col files)."""
    path = os.path.join(DATA_DIR, name)
    if not os.path.exists(path):
        print(f"[skip] {name} not found in {DATA_DIR}")
        return None
    df = pd.read_csv(path)
    df.columns = [c.strip() for c in df.columns]
    # back-fill any expected column that an older run may not have written
    for col in AGG_METRICS + ["op_type", "algorithm", "dataset", "iteration",
                              "prelarge_count", "total_patterns_found"]:
        if col not in df.columns:
            df[col] = np.nan
    for c in [c for c in df.columns if c not in ("algorithm", "dataset",
              "op_type", "run_id", "timestamp", "cub_triggered")]:
        df[c] = pd.to_numeric(df[c], errors="coerce")
    # ResultCollector uses APPEND mode -> a partial re-run can leave duplicate
    # rows for the same config. Keep the LAST occurrence (most recent run) so
    # every config contributes exactly one sample (consistent across plots).
    key = ["dataset", "algorithm", "op_type", "iteration"]
    ndup = df.duplicated(key, keep="last").sum()
    if ndup:
        print(f"[dedup] {name}: dropped {ndup} duplicate row(s) (append-mode); kept latest")
        df = df.drop_duplicates(key, keep="last").reset_index(drop=True)
    return df


def classify_status(op_type, runtime_ms):
    """Precise outcome per AutomatedExperimentRunner encoding. The op_type SUFFIX
    is authoritative (set at measure() time); runtime sentinel is the fallback.
        finished (rt>=0)        -> 'OK'
        _OT     | rt==-2        -> 'OT'    (Overtime, >60-min timeout)
        _OOM                    -> 'OOM'   (OutOfMemory / StackOverflow)
        _BUDGET                 -> 'MemSkip' (pre-run memory-guard skip)
        _SKIPPED_POISONED       -> 'Skip'  (algorithm poisoned by an EARLIER op)
        _FAILED | rt==-1 (else) -> 'Fail'  (other exception)
    NOTE: OT, OOM, Fail are independent failures of THAT config; Skip/MemSkip are
    CONSEQUENCES of a previous failure (esp. incremental SC2/SC5) - keep distinct."""
    ot = str(op_type)
    if pd.notna(runtime_ms) and runtime_ms >= 0:
        return "OK"
    if "SKIPPED_POISONED" in ot: return "Skip"
    if ot.endswith("_OOM"):      return "OOM"
    if ot.endswith("_OT"):       return "OT"
    if ot.endswith("_BUDGET"):   return "MemSkip"
    if ot.endswith("_FAILED"):   return "Fail"
    if runtime_ms == -2:         return "OT"
    return "Fail"


# colour + display order for the failure taxonomy (shown as rotated text on plots)
STATUS_COLOR = {"OK": None, "OT": "#b8860b", "OOM": "#c0392b",
                "Fail": "#000000", "Skip": "#7f7f7f", "MemSkip": "#8e44ad"}
STATUS_ORDER = ["OT", "OOM", "Fail", "MemSkip", "Skip"]


def add_status(df):
    """Attach precise `status` (OK/OT/OOM/Fail/MemSkip/Skip) and `finished` flag."""
    df["status"] = [classify_status(o, r)
                    for o, r in zip(df["op_type"], df["runtime_ms"])]
    df["finished"] = df["status"] == "OK"
    return df


def parse_su(df):
    """su (utility threshold) <- op_type 'SC?_su=<v>'."""
    df["su"] = df["op_type"].astype(str).str.extract(r"su=([0-9.]+)").astype(float)
    return df


def parse_sl(df):
    """SC4: sl <- 'X[sl=<v>]'; base_algo strips the [sl=..] suffix."""
    df["sl"] = df["algorithm"].astype(str).str.extract(r"\[sl=([0-9.]+)\]").astype(float)
    df["base_algo"] = df["algorithm"].astype(str).str.replace(r"\[sl=[0-9.]+\]",
                                                              "", regex=True)
    return df


def parse_batch(df):
    """SC5: K and strategy <- op_type 'SC5_K=<k>_strat=<S>_(INIT|INC)...'."""
    df["K"] = df["op_type"].astype(str).str.extract(r"K=(\d+)").astype(float)
    df["strat"] = df["op_type"].astype(str).str.extract(r"strat=([A-Z]+)")
    df["phase"] = np.where(df["op_type"].astype(str).str.contains("INIT"), "INIT", "INC")
    return df


def parse_phase_idm(df):
    """SC6: operation kind <- op_type 'SC6_(INIT|INS|DEL|MOD)'."""
    df["op_kind"] = df["op_type"].astype(str).str.extract(r"SC6_([A-Z]+)")
    return df


def aggregate(df, keys, metrics=AGG_METRICS):
    """Mean/std over repeated runs (requirement). NOTE: the runner currently does
    ONE run per config (iteration is a SEMANTIC axis, not a repetition), so std
    is ~0/NaN here - the machinery is kept so error bars appear automatically if
    repeated seeds are enabled later. Only FINISHED rows feed the mean."""
    ok = df[df["finished"]].copy()
    if ok.empty:
        return pd.DataFrame()
    g = ok.groupby(keys, dropna=False)
    out = g[metrics].agg(["mean", "std"])
    out.columns = [f"{m}_{s}" for m, s in out.columns]
    return out.reset_index()


# ---- unit + annotation helpers ----------------------------------------------
def ms_to_s(x):    return np.asarray(x, float) / 1000.0
def mb_to_gb(x):   return np.asarray(x, float) / 1024.0

def smart_time(ax, col="runtime_ms"):
    """Choose s vs ms axis label by magnitude (keeps plots clean)."""
    return "Runtime (s)", ms_to_s

def annotate_fail(ax, xpos, status="Fail"):
    """Mark a non-finished config at the top of the axis with its PRECISE outcome
    (OT / OOM / Fail / MemSkip / Skip), colour-coded - never the generic 'OOM/Fail'."""
    ax.annotate(status, xy=(xpos, 0.97), xycoords=("data", "axes fraction"),
                ha="center", va="top", fontsize=6, rotation=90, weight="bold",
                color=STATUS_COLOR.get(status, "black"), alpha=0.9)

def save(fig, name):
    path = os.path.join(OUT_DIR, name)
    fig.savefig(path, format="pdf")
    plt.close(fig)
    print(f"[saved] {path}")


def safe_log(ax):
    """Apply log y-scale ONLY if the axis holds positive data; otherwise just fix
    cosmetics (no finished runs in this panel). The per-config status labels
    (OT/OOM/Fail/Skip) drawn by annotate_fail already explain WHY it is empty -
    avoids matplotlib's 'no positive values' warning and any misleading text."""
    ys = [np.asarray(ln.get_ydata(), float) for ln in ax.get_lines()]
    ys += [np.array([p.get_height()], float) for p in ax.patches]
    ally = np.concatenate(ys) if ys else np.array([])
    if ally.size and np.nanmax(ally) > 0:
        ax.set_yscale("log")
    else:
        ax.set_yticks([]); ax.set_ylim(0, 1)


# %% ------------------------------------------------------------------ Cell 3
#    SET 1 - SCALABILITY (SC1 & SC3): Runtime & Memory vs threshold su
def figset_scalability(csv_name, tag, datasets=None, metrics=None):
    df = load_csv(csv_name)
    if df is None:
        return
    df = add_status(parse_su(df))
    if datasets is None:
        datasets = FIG_DATASETS
    datasets = [d for d in datasets if d in set(df["dataset"])]

    # one figure PER metric, datasets across a single row (fits journal width)
    all_metrics = [
        ("runtime_ms",     "Runtime (s)",          ms_to_s,  True),
        ("peak_heap_mb",   "Memory Footprint (MB)", lambda x: np.asarray(x,float), False),
        ("explored_nodes", "Explored Nodes",        lambda x: np.asarray(x,float), True),
    ]
    if metrics is not None:
        all_metrics = [m for m in all_metrics if m[0] in metrics]
    for metric, ylab, conv, logy in all_metrics:
        n = len(datasets)
        fig, axes = plt.subplots(1, n, figsize=(2.9 * n, 2.7), squeeze=False)
        axes = axes[0]
        for ax, ds in zip(axes, datasets):
            sub = df[df["dataset"] == ds]
            for algo in ALGO_ORDER:
                a = sub[sub["algorithm"] == algo].sort_values("su")
                if a.empty:
                    continue
                ok = a[a["finished"]]
                ax.plot(ok["su"], conv(ok[metric]),
                        marker=ALGO_MARKER[algo], color=ALGO_COLOR[algo],
                        ms=5, lw=1.3, label=ALGO_LABEL[algo],
                        markeredgewidth=0.4, markeredgecolor="white")
                # annotate non-finished points with their PRECISE outcome
                for _, r in a[~a["finished"]].iterrows():
                    annotate_fail(ax, r["su"], r["status"])
            ax.set_title(ds)
            ax.set_xlabel(r"Threshold $su$")
            if logy:
                safe_log(ax)
            if ax is axes[0]:
                ax.set_ylabel(ylab)
        # shared legend on top
        h, l = axes[0].get_legend_handles_labels()
        if not h:  # first axis may be empty for some metric
            for ax in axes:
                h, l = ax.get_legend_handles_labels()
                if h: break
        fig.legend(h, l, loc="upper center", ncol=len(l), frameon=False,
                   bbox_to_anchor=(0.5, 1.08))
        fig.tight_layout()
        save(fig, f"{tag}_{metric}.pdf")


# %% ------------------------------------------------------------------ Cell 4
#    SET 2 - INCREMENTAL / MIXED (SC2 & SC6): clustered bars across iterations
def figset_incremental(csv_name="SC2_incremental.csv", tag="set2_SC2",
                       datasets=None, metrics=None):
    df = load_csv(csv_name)
    if df is None:
        return
    df = add_status(df)
    algos = [a for a in ["IncUSP-Miner+", "Pre-HUSPM", "Adaptive-Full"]
             if a in df["algorithm"].unique()]
    datasets = [d for d in (datasets or FIG_DATASETS) if d in set(df["dataset"])]

    all_metrics = [
        ("runtime_ms",   "Runtime (s)",           ms_to_s, True),
        ("peak_heap_mb", "Memory Footprint (MB)",  lambda x: np.asarray(x,float), False),
    ]
    if metrics is not None:
        all_metrics = [m for m in all_metrics if m[0] in metrics]
    for metric, ylab, conv, logy in all_metrics:
        n = len(datasets)
        fig, axes = plt.subplots(1, n, figsize=(2.9 * n, 2.7), squeeze=False)
        axes = axes[0]
        for ax, ds in zip(axes, datasets):
            sub = df[df["dataset"] == ds]
            iters = sorted(sub["iteration"].dropna().unique())
            x = np.arange(len(iters))
            w = 0.8 / max(1, len(algos))
            for i, algo in enumerate(algos):
                a = sub[sub["algorithm"] == algo].set_index("iteration")
                vals, stats = [], []
                for it in iters:
                    if it in a.index and bool(a.loc[it, "finished"]):
                        vals.append(conv([a.loc[it, metric]])[0]); stats.append(None)
                    elif it in a.index:
                        vals.append(np.nan); stats.append(str(a.loc[it, "status"]))
                    else:
                        vals.append(np.nan); stats.append(None)
                pos = x + (i - (len(algos)-1)/2) * w
                ax.bar(pos, np.nan_to_num(vals), width=w, color=ALGO_COLOR[algo],
                       label=ALGO_LABEL[algo], edgecolor="white", linewidth=0.3)
                for p, st in zip(pos, stats):
                    if st:
                        annotate_fail(ax, p, st)
            ax.set_title(ds)
            ax.set_xlabel("Increment (0 = init)")
            ax.set_xticks(x); ax.set_xticklabels([int(it) for it in iters])
            if logy:
                safe_log(ax)
            if ax is axes[0]:
                ax.set_ylabel(ylab)
        h, l = axes[0].get_legend_handles_labels()
        fig.legend(h, l, loc="upper center", ncol=len(l), frameon=False,
                   bbox_to_anchor=(0.5, 1.08))
        fig.tight_layout()
        save(fig, f"{tag}_{metric}.pdf")


def figset_mixed_idm(csv_name="SC6_idm_mixed.csv", tag="set2_SC6"):
    """SC6 is Adaptive self-compare over a 70:15:15 I/D/M op stream."""
    df = load_csv(csv_name)
    if df is None:
        return
    df = add_status(parse_phase_idm(df))
    datasets = sorted(df["dataset"].dropna().unique())
    op_color = {"INIT": "#7f7f7f", "INS": "#2ca02c", "DEL": "#d62728", "MOD": "#1f77b4"}

    n = len(datasets)
    fig, axes = plt.subplots(1, n, figsize=(2.9 * n, 2.7), squeeze=False)
    axes = axes[0]
    for ax, ds in zip(axes, datasets):
        sub = df[df["dataset"] == ds].sort_values("iteration")
        x = np.arange(len(sub))
        colors = [op_color.get(k, "#999999") for k in sub["op_kind"]]
        ax.bar(x, ms_to_s(sub["runtime_ms"].clip(lower=0)), color=colors,
               edgecolor="white", linewidth=0.3)
        ax.set_title(ds); ax.set_xlabel("Operation #")
        ax.set_xticks(x); ax.set_xticklabels([int(i) for i in sub["iteration"]],
                                             fontsize=6)
        if ax is axes[0]:
            ax.set_ylabel("Runtime (s)")
    handles = [plt.Rectangle((0, 0), 1, 1, color=c) for c in op_color.values()]
    fig.legend(handles, list(op_color.keys()), loc="upper center",
               ncol=4, frameon=False, bbox_to_anchor=(0.5, 1.08))
    fig.tight_layout()
    save(fig, f"{tag}_runtime.pdf")


# %% ------------------------------------------------------------------ Cell 5
#    SET 3 - ABLATION (SC7): contribution of each module (Runtime & Memory)
def figset_ablation(csv_name="SC7_ablation.csv", tag="set3_SC7"):
    df = load_csv(csv_name)
    if df is None:
        return
    df = add_status(df)
    # Single full-mine variants only (comparable workload). C5 is an INCREMENTAL
    # ablation (different workload) -> its own figure in figset_c5_ablation().
    order = ["proposed_full", "proposed_no_IUB",
             "proposed_no_block_index", "Pre-HUSPM"]
    order = [o for o in order if o in df["algorithm"].unique()]
    datasets = sorted(df["dataset"].dropna().unique())

    # explored_nodes carries the C1 evidence (PEU value-bound applied pre-materialization).
    # proposed method materialize far more nodes (often -> OT), exposing C1's value.
    for metric, ylab, conv, logy in [
        ("runtime_ms",     "Runtime (s)",           ms_to_s, True),
        ("peak_heap_mb",   "Memory Footprint (MB)",  lambda x: np.asarray(x,float), False),
        ("explored_nodes", "Explored Nodes",         lambda x: np.asarray(x,float), True),
    ]:
        n = len(datasets)
        fig, axes = plt.subplots(1, n, figsize=(2.9 * n, 3.0), squeeze=False)
        axes = axes[0]
        for ax, ds in zip(axes, datasets):
            sub = df[df["dataset"] == ds].set_index("algorithm")
            xs, vals, cols, stats = [], [], [], []
            for o in order:
                xs.append(SC7_LABEL.get(o, o))
                if o in sub.index and bool(np.atleast_1d(sub.loc[o, "finished"])[0]):
                    v = np.atleast_1d(sub.loc[o, metric])[0]
                    vals.append(conv([v])[0]); stats.append(None)
                elif o in sub.index:
                    vals.append(np.nan); stats.append(str(np.atleast_1d(sub.loc[o, "status"])[0]))
                else:
                    vals.append(np.nan); stats.append(None)
                cols.append("#d62728" if o == "proposed_full" else "#4c72b0")
            x = np.arange(len(xs))
            ax.bar(x, np.nan_to_num(vals), color=cols, edgecolor="white", linewidth=0.3)
            for xi, st in zip(x, stats):
                if st:
                    annotate_fail(ax, xi, st)
            ax.set_title(ds)
            ax.set_xticks(x); ax.set_xticklabels(xs, fontsize=6.5, rotation=0)
            ax.tick_params(axis="x", pad=1)
            if logy:
                safe_log(ax)
            if ax is axes[0]:
                ax.set_ylabel(ylab)
        # legend explaining the red = Full reference bar
        handles = [plt.Rectangle((0,0),1,1,color="#d62728"),
                   plt.Rectangle((0,0),1,1,color="#4c72b0")]
        fig.legend(handles, ["Proposed (Full)", "Ablation / baseline"],
                   loc="upper center", ncol=2, frameon=False,
                   bbox_to_anchor=(0.5, 1.06))
        fig.tight_layout()
        save(fig, f"{tag}_{metric}.pdf")


def figset_c5_ablation(csv_name="SC7_ablation.csv", tag="set3_SC7_C5"):
    """C5 (adaptive rescan) ablation - INCREMENTAL workload (D_init + 5 batches),
    so kept separate from the single-mine ablation. C5_on = adaptive rescan,
    C5_off = naive rescan-every-batch. Shows total runtime + #rescans; the
    OT/OOM status is annotated (e.g. BMS1: C5_on finishes, C5_off times out)."""
    df = load_csv(csv_name)
    if df is None:
        return
    df = add_status(df)
    df = df[df["algorithm"].isin(["proposed_C5_on", "proposed_C5_off"])]
    if df.empty:
        return
    # order datasets per C5_ABLATION_SET (BMS1, SIGN, KOSARAK) if present
    pref = ["BMS1", "SIGN", "KOSARAK"]
    datasets = [d for d in pref if d in set(df["dataset"])] + \
               [d for d in sorted(df["dataset"].dropna().unique()) if d not in pref]
    variants = [("proposed_C5_on", "C5 on (adaptive)", "#d62728"),
                ("proposed_C5_off", "C5 off (rescan/batch)", "#4c72b0")]
    # ONE figure, 2 sub-plots: total runtime + #rescans
    fig, axes = plt.subplots(1, 2, figsize=(6.6, 2.7))
    for ax, (metric, ylab, conv, logy) in zip(axes, [
        ("runtime_ms",   "Total runtime (s)", ms_to_s, True),
        ("rescan_count", "# Rescans",          lambda x: np.asarray(x, float), False),
    ]):
        x = np.arange(len(datasets)); w = 0.38
        for i, (algo, lbl, col) in enumerate(variants):
            a = df[df["algorithm"] == algo].set_index("dataset")
            vals, stats = [], []
            for ds in datasets:
                if ds in a.index and bool(np.atleast_1d(a.loc[ds, "finished"])[0]):
                    vals.append(conv([np.atleast_1d(a.loc[ds, metric])[0]])[0]); stats.append(None)
                elif ds in a.index:
                    vals.append(np.nan); stats.append(str(np.atleast_1d(a.loc[ds, "status"])[0]))
                else:
                    vals.append(np.nan); stats.append(None)
            pos = x + (i - 0.5) * w
            ax.bar(pos, np.nan_to_num(vals), width=w, color=col, label=lbl,
                   edgecolor="white", linewidth=0.3)
            for p, st in zip(pos, stats):
                if st: annotate_fail(ax, p, st)
        ax.set_xticks(x); ax.set_xticklabels(datasets)
        ax.set_ylabel(ylab)
        if logy: safe_log(ax)
    h, l = axes[0].get_legend_handles_labels()
    fig.legend(h, l, loc="upper center", ncol=2, frameon=False,
               bbox_to_anchor=(0.5, 1.06))
    fig.tight_layout()
    save(fig, f"{tag}.pdf")


# %% ------------------------------------------------------------------ Cell 6
#    SET 4 - SENSITIVITY (SC4 sl  &  SC5 batch grid)
def figset_sl_sensitivity(csv_name="SC4_sl_sensitivity.csv", tag="set4_SC4",
                          datasets=None, metrics=None):
    df = load_csv(csv_name)
    if df is None:
        return
    df = add_status(parse_sl(df))
    datasets = [d for d in (datasets or FIG_DATASETS) if d in set(df["dataset"])]
    pair = [("Pre-HUSPM", "#ff7f0e"), ("Adaptive", "#d62728")]

    all_metrics = [
        ("runtime_ms",     "Runtime (s)",  ms_to_s, True),
        ("prelarge_count", "Pre-large set size", lambda x: np.asarray(x,float), True),
        ("explored_nodes", "Explored Nodes", lambda x: np.asarray(x,float), True),
    ]
    if metrics is not None:
        all_metrics = [m for m in all_metrics if m[0] in metrics]
    for metric, ylab, conv, logy in all_metrics:
        n = len(datasets)
        fig, axes = plt.subplots(1, n, figsize=(2.9 * n, 2.7), squeeze=False)
        axes = axes[0]
        for ax, ds in zip(axes, datasets):
            sub = df[df["dataset"] == ds]
            sls = sorted(sub["sl"].dropna().unique())
            x = np.arange(len(sls)); w = 0.38
            for i, (base, col) in enumerate(pair):
                a = sub[sub["base_algo"] == base].set_index("sl")
                vals, stats = [], []
                for s in sls:
                    if s in a.index and bool(np.atleast_1d(a.loc[s, "finished"])[0]):
                        vals.append(conv([np.atleast_1d(a.loc[s, metric])[0]])[0]); stats.append(None)
                    elif s in a.index:
                        vals.append(np.nan); stats.append(str(np.atleast_1d(a.loc[s, "status"])[0]))
                    else:
                        vals.append(np.nan); stats.append(None)
                pos = x + (i - 0.5) * w
                lbl = "Proposed" if base == "Adaptive" else base
                ax.bar(pos, np.nan_to_num(vals), width=w, color=col, label=lbl,
                       edgecolor="white", linewidth=0.3)
                for p, st in zip(pos, stats):
                    if st: annotate_fail(ax, p, st)
            ax.set_title(ds); ax.set_xlabel(r"Pre-large ratio $s_l/s_u$")
            ax.set_xticks(x); ax.set_xticklabels(sls)
            if logy: safe_log(ax)
            if ax is axes[0]: ax.set_ylabel(ylab)
        h, l = axes[0].get_legend_handles_labels()
        fig.legend(h, l, loc="upper center", ncol=2, frameon=False,
                   bbox_to_anchor=(0.5, 1.08))
        fig.tight_layout()
        save(fig, f"{tag}_{metric}.pdf")


def figset_memory_bar(csv_name="SC1_static.csv", tag="fig_memory",
                      datasets=None, default_su=None):
    """One consolidated MEMORY figure (peak heap) - the proposed method's headline
    advantage (e.g. 95 MB vs ~8 GB). 3 sub-plots (representative datasets), 5 algo
    bars each at that dataset's default su; log-y (magnitudes span MB..GB)."""
    df = load_csv(csv_name)
    if df is None:
        return
    df = add_status(parse_su(df))
    datasets = [d for d in (datasets or FIG_DATASETS) if d in set(df["dataset"])]
    default_su = default_su or {"SIGN": 0.025, "BMS1": 0.014, "BIBLE": 0.025,
                                "LEVIATHAN": 0.060, "FIFA": 0.05, "KOSARAK": 0.03}
    n = len(datasets)
    fig, axes = plt.subplots(1, n, figsize=(2.5 * n, 2.7), squeeze=False)
    axes = axes[0]
    for ax, ds in zip(axes, datasets):
        g = df[df["dataset"] == ds]
        sus = sorted(g["su"].dropna().unique())
        su_star = min(sus, key=lambda v: abs(v - default_su.get(ds, np.median(sus)))) if sus else None
        gg = g[np.isclose(g["su"], su_star)] if su_star is not None else g
        vals, cols, stats = [], [], []
        for algo in ALGO_ORDER:
            a = gg[gg["algorithm"] == algo]
            if not a.empty and bool(a.iloc[-1]["finished"]):
                vals.append(float(a.iloc[-1]["peak_heap_mb"])); stats.append(None)
            elif not a.empty:
                vals.append(np.nan); stats.append(str(a.iloc[-1]["status"]))
            else:
                vals.append(np.nan); stats.append(None)
            cols.append(ALGO_COLOR[algo])
        x = np.arange(len(ALGO_ORDER))
        ax.bar(x, np.nan_to_num(vals), color=cols, edgecolor="white", linewidth=0.3)
        for xi, st in zip(x, stats):
            if st: annotate_fail(ax, xi, st)
        ax.set_title(f"{ds} ($su$={su_star:g})" if su_star is not None else ds)
        ax.set_xticks(x)
        ax.set_xticklabels([ALGO_LABEL[a] for a in ALGO_ORDER], rotation=40,
                           ha="right", fontsize=6)
        safe_log(ax)
        if ax is axes[0]:
            ax.set_ylabel("Memory Footprint (MB)")
    fig.tight_layout()
    save(fig, f"{tag}.pdf")


def plot_heatmap(ax, mat, xt, yt, title, cbar_label):
    """seaborn heatmap if available, else matplotlib imshow."""
    if HAS_SNS:
        sns.heatmap(mat, annot=True, fmt=".1f", cmap="rocket_r", ax=ax,
                    xticklabels=xt, yticklabels=yt,
                    cbar_kws={"label": cbar_label}, annot_kws={"size": 6})
    else:
        im = ax.imshow(mat, cmap="magma_r", aspect="auto")
        ax.set_xticks(range(len(xt))); ax.set_xticklabels(xt)
        ax.set_yticks(range(len(yt))); ax.set_yticklabels(yt)
        for i in range(mat.shape[0]):
            for j in range(mat.shape[1]):
                if np.isfinite(mat[i, j]):
                    ax.text(j, i, f"{mat[i,j]:.1f}", ha="center", va="center",
                            fontsize=6, color="w")
        ax.figure.colorbar(im, ax=ax, label=cbar_label, fraction=0.046)
    ax.set_title(title)


def figset_batch_grid(csv_name="SC5_batch_strategy.csv", tag="set4_SC5",
                      algo="Adaptive-Full"):
    """SC5: heatmap of TOTAL incremental runtime (sum over INC iters) for the
    proposed method, K (rows) × strategy (cols), one panel per dataset."""
    df = load_csv(csv_name)
    if df is None:
        return
    df = add_status(parse_batch(df))
    df = df[df["algorithm"] == algo]
    datasets = sorted(df["dataset"].dropna().unique())
    Ks = [3, 5, 10]
    strats = ["EVEN", "INCREASING", "DECREASING", "BURST"]
    # sum only the INC phase, finished rows -> total incremental cost
    inc = df[(df["phase"] == "INC") & (df["finished"])]
    n = len(datasets)
    fig, axes = plt.subplots(1, n, figsize=(3.0 * n, 2.8), squeeze=False)
    axes = axes[0]
    for ax, ds in zip(axes, datasets):
        sub = inc[inc["dataset"] == ds]
        mat = np.full((len(Ks), len(strats)), np.nan)
        for r, k in enumerate(Ks):
            for c, s in enumerate(strats):
                cell = sub[(sub["K"] == k) & (sub["strat"] == s)]
                if not cell.empty:
                    mat[r, c] = ms_to_s(cell["runtime_ms"].sum())
        plot_heatmap(ax, mat, [s[:4] for s in strats], Ks, ds,
                     "Total inc. runtime (s)")
        ax.set_xlabel("Batch strategy");
        if ax is axes[0]: ax.set_ylabel("#Batches K")
    fig.tight_layout()
    save(fig, f"{tag}_runtime_heatmap.pdf")


# %% ------------------------------------------------------------------ Cell 7
#    SUMMARY TABLES (for the paper text) - main static comparison + speedups
def export_summary_tables(csv_name="SC1_static.csv"):
    """Main comparison table at each dataset's DEFAULT su (SC1, D_init 50%):
    runtime/memory/candidate/explored/patterns per algorithm, plus speedup of
    the Proposed method vs every baseline. Exports CSV + LaTeX (booktabs)."""
    df = load_csv(csv_name)
    if df is None:
        return
    df = add_status(parse_su(df))
    # per-dataset DEFAULT su (mirrors AutomatedExperimentRunner DatasetConfig)
    DEFAULT_SU = {"SIGN": 0.025, "BMS1": 0.014, "BIBLE": 0.025,
                  "LEVIATHAN": 0.060, "FIFA": 0.05, "KOSARAK": 0.03}
    rows = []
    for ds, g in df.groupby("dataset"):
        sus = sorted(g["su"].dropna().unique())
        if not sus:
            continue
        target = DEFAULT_SU.get(ds, float(np.median(sus)))
        su_star = min(sus, key=lambda v: abs(v - target))   # snap to an ACTUAL su
        gg = g[np.isclose(g["su"], su_star)]
        for algo in ALGO_ORDER:
            a = gg[gg["algorithm"] == algo]
            if a.empty:
                continue
            r = a.iloc[-1]
            rows.append(dict(dataset=ds, su=su_star, algorithm=algo,
                             status=r["status"],
                             runtime_s=r["runtime_ms"]/1000 if r["finished"] else np.nan,
                             memory_mb=r["peak_heap_mb"] if r["finished"] else np.nan,
                             candidate=r["candidate_count"] if r["finished"] else np.nan,
                             explored=r["explored_nodes"] if r["finished"] else np.nan,
                             patterns=r["total_patterns_found"] if r["finished"] else np.nan))
    tab = pd.DataFrame(rows)
    if tab.empty:
        return
    # speedup vs Proposed (runtime_baseline / runtime_proposed) per dataset
    sp = []
    for ds, g in tab.groupby("dataset"):
        prop = g[g["algorithm"] == PROPOSED]
        if prop.empty or not np.isfinite(prop["runtime_s"].iloc[0]):
            continue
        rp = prop["runtime_s"].iloc[0]
        for _, r in g.iterrows():
            sp.append(dict(dataset=ds, algorithm=r["algorithm"],
                           speedup_vs_proposed=(r["runtime_s"]/rp)
                           if np.isfinite(r["runtime_s"]) else np.nan))
    spd = pd.DataFrame(sp)
    out_csv = os.path.join(OUT_DIR, "table_SC1_summary.csv")
    tab.to_csv(out_csv, index=False)
    spd.to_csv(os.path.join(OUT_DIR, "table_SC1_speedup.csv"), index=False)
    # LaTeX (booktabs) - runtime focus; failed cells show the PRECISE status.
    piv = tab.pivot_table(index="dataset", columns="algorithm",
                          values="runtime_s", aggfunc="first").reindex(columns=ALGO_ORDER)
    stat = tab.pivot_table(index="dataset", columns="algorithm", values="status",
                           aggfunc="first").reindex(columns=ALGO_ORDER)
    piv = piv.rename(columns=ALGO_LABEL); stat = stat.rename(columns=ALGO_LABEL)
    # Build LaTeX tabular manually (no pandas.to_latex -> no jinja2 dependency).
    cols = list(piv.columns)
    lines = ["\\begin{table}[t]", "\\centering",
             "\\caption{Runtime (s) on $D_{init}$ (SC1, representative $su$). "
             "Lower is better. \\textbf{OT}=overtime ($>$60\\,min), "
             "\\textbf{OOM}=out-of-memory, \\textbf{Fail}=other failure.}",
             "\\label{tab:sc1_runtime}",
             "\\begin{tabular}{l" + "r" * len(cols) + "}",
             "\\toprule",
             "Dataset & " + " & ".join(cols) + " \\\\", "\\midrule"]
    for ds, row in piv.iterrows():
        cells = []
        for c in cols:
            v = row[c]
            cells.append(f"{v:.2f}" if pd.notna(v)
                         else str(stat.loc[ds, c]))   # OT / OOM / Fail / ...
        lines.append(f"{ds} & " + " & ".join(cells) + " \\\\")
    lines += ["\\bottomrule", "\\end{tabular}", "\\end{table}", ""]
    with open(os.path.join(OUT_DIR, "table_SC1_runtime.tex"), "w") as fh:
        fh.write("\n".join(lines))
    print(f"[saved] {out_csv}  (+ speedup csv + LaTeX)")


# %% ------------------------------------------------------------------ Cell 7b
#    STATUS SUMMARY - count OK / OT / OOM / Fail / Skip per scenario & dataset
def export_status_summary():
    """Make the OT/OOM/Fail/Skip breakdown EXPLICIT (per scenario x dataset) so
    analysis text can state e.g. 'SC7/BMS1: 5 OT, 1 OOM, 1 OK' instead of
    lumping everything as OOM."""
    files = {"SC1": "SC1_static.csv", "SC2": "SC2_incremental.csv",
             "SC3": "SC3_su.csv", "SC4": "SC4_sl_sensitivity.csv",
             "SC5": "SC5_batch_strategy.csv", "SC6": "SC6_idm_mixed.csv",
             "SC7": "SC7_ablation.csv"}
    order = ["OK"] + STATUS_ORDER
    rows = []
    for sc, name in files.items():
        df = load_csv(name)
        if df is None:
            continue
        df = add_status(df)
        for ds, g in df.groupby("dataset"):
            vc = g["status"].value_counts().to_dict()
            rec = dict(scenario=sc, dataset=ds, total=len(g))
            for s in order:
                rec[s] = int(vc.get(s, 0))
            rows.append(rec)
    if not rows:
        return
    out = pd.DataFrame(rows)[["scenario", "dataset", "total"] + order]
    path = os.path.join(OUT_DIR, "table_status_summary.csv")
    out.to_csv(path, index=False)
    print(f"[saved] {path}")
    # quick console view of the non-OK breakdown
    bad = out[out[STATUS_ORDER].sum(axis=1) > 0]
    if not bad.empty:
        print("    non-OK breakdown (scenario/dataset -> OT/OOM/Fail/MemSkip/Skip):")
        for _, r in bad.iterrows():
            parts = [f"{s}={r[s]}" for s in STATUS_ORDER if r[s] > 0]
            print(f"      {r['scenario']}/{r['dataset']}: " + ", ".join(parts))


# %% ------------------------------------------------------------------ Cell 7c
#    ABLATION TABLE (SC7 single-mine variants) - full 6 datasets, precise status
def export_ablation_table(csv_name="SC7_ablation.csv"):
    """SC7 single-mine ablation as a TABLE (more precise than bars on the easy
    subset): variant x dataset, runtime(s) / explored / status. CSV + LaTeX."""
    df = load_csv(csv_name)
    if df is None:
        return
    df = add_status(df)
    order = ["proposed_full", "proposed_no_IUB",
             "proposed_no_block_index", "Pre-HUSPM"]
    label = {"proposed_full": "Full",
             "proposed_no_IUB": "-IUB(C3)", "proposed_no_block_index": "-Block(C4)",
             "Pre-HUSPM": "Pre-HUSPM"}
    order = [o for o in order if o in set(df["algorithm"])]
    datasets = sorted(df["dataset"].dropna().unique())
    rows = []
    for ds in datasets:
        g = df[df["dataset"] == ds].set_index("algorithm")
        for o in order:
            if o not in g.index:
                continue
            r = g.loc[o]
            r = r.iloc[-1] if hasattr(r, "iloc") and getattr(r, "ndim", 1) > 1 else r
            fin = bool(np.atleast_1d(r["finished"])[0])
            rows.append(dict(dataset=ds, variant=label[o],
                             status=str(np.atleast_1d(r["status"])[0]),
                             runtime_s=(float(np.atleast_1d(r["runtime_ms"])[0])/1000 if fin else np.nan),
                             explored=(float(np.atleast_1d(r["explored_nodes"])[0]) if fin else np.nan)))
    tab = pd.DataFrame(rows)
    if tab.empty:
        return
    tab.to_csv(os.path.join(OUT_DIR, "table_SC7_ablation.csv"), index=False)
    # LaTeX: rows = (dataset, variant), cols = runtime / explored / status
    lines = ["\\begin{table}[t]", "\\centering",
             "\\caption{Ablation (SC7, single full-mine). Runtime\\,(s) and explored "
             "nodes; \\textbf{OT}=overtime, \\textbf{OOM}=out-of-memory.}",
             "\\label{tab:sc7_ablation}",
             "\\begin{tabular}{llrr}", "\\toprule",
             "Dataset & Variant & Runtime (s) & Explored \\\\", "\\midrule"]
    prev = None
    for _, r in tab.iterrows():
        ds = r["dataset"] if r["dataset"] != prev else ""
        prev = r["dataset"]
        rt = f"{r['runtime_s']:.2f}" if pd.notna(r["runtime_s"]) else r["status"]
        ex = f"{int(r['explored']):,}" if pd.notna(r["explored"]) else r["status"]
        lines.append(f"{ds} & {r['variant']} & {rt} & {ex} \\\\")
    lines += ["\\bottomrule", "\\end{tabular}", "\\end{table}", ""]
    with open(os.path.join(OUT_DIR, "table_SC7_ablation.tex"), "w") as fh:
        fh.write("\n".join(lines))
    print(f"[saved] {os.path.join(OUT_DIR, 'table_SC7_ablation.csv')}  (+ LaTeX)")


# %% ------------------------------------------------------------------ Cell 8
#    DRIVER - CURATED set (page-limited): 7 figures (each 3 sub-plots = SIGN/
#    LEVIATHAN/KOSARAK) + tables for everything a table conveys better.
def main():
    print(f"DATA_DIR = {DATA_DIR}\nOUT_DIR  = {OUT_DIR}\nseaborn  = {HAS_SNS}\n")

    # --- FIGURES (7) -----------------------------------------------------------
    # 1-2. Scalability vs su (SC3, full DB): runtime + explored_nodes (C1 visual)
    figset_scalability("SC3_su.csv", "fig_scalability",
                       metrics=["runtime_ms", "explored_nodes"])
    # 3. Incremental runtime across batches (SC2)
    figset_incremental("SC2_incremental.csv", "fig_incremental",
                       metrics=["runtime_ms"])
    # 4. C5 adaptive-rescan ablation (incremental) - runtime + #rescans, 1 figure
    figset_c5_ablation("SC7_ablation.csv", "fig_C5_ablation")
    # 5. Pre-large (Sl) sensitivity (SC4) - pre-large explosion + robustness
    figset_sl_sensitivity("SC4_sl_sensitivity.csv", "fig_sl_sensitivity",
                          metrics=["prelarge_count"])
    # 6. Batch-strategy heatmap (SC5)
    figset_batch_grid("SC5_batch_strategy.csv", "fig_batch_heatmap")
    # 7. Consolidated memory footprint (SC1, default su)
    figset_memory_bar("SC1_static.csv", "fig_memory")

    # --- TABLES (everything a table conveys better than a figure) --------------
    export_summary_tables("SC1_static.csv")   # SC1 static: runtime/mem/explored/patterns + speedup
    export_ablation_table("SC7_ablation.csv") # SC7 single-mine ablation
    export_status_summary()                   # explicit OT/OOM/Fail/Skip breakdown
    print("\nDONE.")


# %% ------------------------------------------------------------------ Cell 9
#    (optional) FULL set - uncomment to regenerate every figure incl. dropped ones
def main_all():
    for csv, tag in [("SC1_static.csv", "set1_SC1"), ("SC3_su.csv", "set1_SC3")]:
        figset_scalability(csv, tag, datasets=None)        # all metrics
    figset_incremental("SC2_incremental.csv", "set2_SC2")
    figset_mixed_idm("SC6_idm_mixed.csv", "set2_SC6")
    figset_ablation("SC7_ablation.csv", "set3_SC7")
    figset_c5_ablation("SC7_ablation.csv", "set3_SC7_C5")
    figset_sl_sensitivity("SC4_sl_sensitivity.csv", "set4_SC4")
    figset_batch_grid("SC5_batch_strategy.csv", "set4_SC5")
    figset_memory_bar("SC1_static.csv", "fig_memory")
    export_summary_tables(); export_ablation_table(); export_status_summary()


if __name__ == "__main__":
    main()
