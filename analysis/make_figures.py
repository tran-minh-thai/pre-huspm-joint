# -*- coding: utf-8 -*-
# =============================================================================
#  make_figures.py  -  figures and tables for the paper, from the campaign results
#
#  Reads the per-arm CSVs the campaign writes,
#      results/campaign/<SC>/<DATABASE>/<ARM>/<file>.csv       timing campaign
#      results/campaign-mem/<SC>/<DATABASE>/<ARM>/<file>.csv   memory campaign
#  and writes vector PDFs plus CSV/LaTeX tables to figures/campaign/.
#
#  Only arms that finished are read: an arm directory counts when it holds DONE.
#  DEFERRED arms carry a partial CSV and are left out, so a figure never mixes a
#  curve that stops part way with ones that cover the whole stream.
#
#  Thresholds, D_init and the sweeps come from src/common/Datasets.java, the one
#  place they are defined; nothing here carries its own copy.
#
#  Core deps: pandas, numpy, matplotlib. seaborn is optional (nicer heatmaps).
#
#  SCENARIO MAP (how each scenario's rows are keyed)
#  -------------------------------------------------
#   SC1 static on D_init   : op_type "SC1_su=<v>", one row per threshold
#   SC2 incremental        : op_type "SC2_INIT" then "SC2_INC" x 100, iteration 0..100
#   SC3 static, whole DB   : op_type "SC3_su=<v>"
#   SC4 pre-large floor    : algorithm "<Pre-HUSPM|Adaptive>[sl=<v>]", r_l in {.3,.5,.7,.9}
#   SC5 batch grid         : op_type "SC5_K=<k>_strat=<S>_(INIT|INC)", K in {100,67,50,25}
#   SC6 mixed I/D/M        : op_type "SC6_(INIT|INS|DEL|MOD)", Adaptive and Remine
#   SC7 ablation           : static  proposed_full / proposed_no_IUB / Pre-HUSPM (one mine);
#                            incremental proposed_C5_on / proposed_C5_off /
#                            proposed_no_block_index / proposed_no_C2
#                            ("SC7_C5_*" / "SC7_C4_*" / "SC7_C2_*", 101 rows)
#  Status suffixes on op_type: _OOM, _OT, _FAILED, _BUDGET, _SKIPPED_POISONED.
#  runtime_ms: -1 failed, -2 overtime, -3 completed in live memory mode (time void).
# =============================================================================

# %% ------------------------------------------------------------------ Cell 1
#    SETUP, CONFIG, STYLE
import glob, os, re, sys, warnings
import numpy as np
import pandas as pd
import matplotlib
matplotlib.use("Agg")  # headless-safe; Colab/inline still works
import matplotlib.pyplot as plt

try:
    import seaborn as sns
    HAS_SNS = True
except Exception:
    HAS_SNS = False

# ---- Data / output locations -------------------------------------------------
# Resolution order:
#   1) env HUSP_DATA_DIR / HUSP_OUT_DIR  (the Colab notebook sets these)
#   2) Colab default placeholder (edit PROJECT below if not using env)
#   3) local: <repo>/results and <repo>/figures/campaign
IN_COLAB = "google.colab" in sys.modules
DATA_DIR = os.environ.get("HUSP_DATA_DIR")
OUT_DIR  = os.environ.get("HUSP_OUT_DIR")
if not DATA_DIR:
    if IN_COLAB:
        from google.colab import drive            # noqa
        drive.mount("/content/drive", force_remount=False)
        PROJECT  = "/content/drive/MyDrive/.../pre-huspm-adaptive"  # <-- EDIT if not using env
        DATA_DIR = PROJECT + "/results"
    else:
        HERE     = os.path.dirname(os.path.abspath(__file__))
        DATA_DIR = os.path.join(os.path.dirname(HERE), "results")
REPO_DIR = os.path.dirname(DATA_DIR.rstrip("/\\"))
if not OUT_DIR:
    OUT_DIR = os.path.join(REPO_DIR, "figures", "campaign")
os.makedirs(OUT_DIR, exist_ok=True)

TIMING_TREE = "campaign"
MEMORY_TREE = "campaign-mem"

# ---- Journal aesthetics -------------------------------------------------------
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

# ---- Algorithms: order, labels, colours, markers -----------------------------
STATIC_ORDER = ["USpan", "HUSP-ULL", "HUSP-SP", "IncUSP-Miner+", "Pre-HUSPM", "Adaptive-Full"]
INC_ORDER    = ["IncUSP-Miner+", "Pre-HUSPM", "Pre-HUSPM-TSU", "Remine-from-scratch", "Adaptive-Full"]
ALGO_LABEL = {
    "USpan": "USpan", "HUSP-ULL": "HUSP-ULL", "HUSP-SP": "HUSP-SP",
    "IncUSP-Miner+": "IncUSP-Miner+", "Pre-HUSPM": "Pre-HUSPM",
    "Pre-HUSPM-TSU": "Pre-HUSPM-TSU", "Remine-from-scratch": "Remine",
    "Adaptive-Full": "Proposed",
}
ALGO_COLOR = {
    "USpan": "#7f7f7f", "HUSP-ULL": "#1f77b4", "HUSP-SP": "#9467bd",
    "IncUSP-Miner+": "#2ca02c", "Pre-HUSPM": "#ff7f0e", "Pre-HUSPM-TSU": "#8c564b",
    "Remine-from-scratch": "#17becf", "Adaptive-Full": "#d62728",
}
ALGO_MARKER = {
    "USpan": "o", "HUSP-ULL": "s", "HUSP-SP": "v", "IncUSP-Miner+": "^",
    "Pre-HUSPM": "D", "Pre-HUSPM-TSU": "P", "Remine-from-scratch": "X",
    "Adaptive-Full": "*",
}
PROPOSED = "Adaptive-Full"

# SUBSET_3 of the runner: the three databases SC3-SC6 measure, and the panels of
# every three-panel figure.
SUBSET_3 = ["SIGN", "LEVIATHAN", "C8T1S5I8N5K"]
FIG_DATASETS = SUBSET_3
# Display order of all eight: smallest to largest, as in the README table.
ALL_DATASETS = ["SIGN", "LEVIATHAN", "FIFA", "BIBLE", "BMS1", "KOSARAK", "C8T1S5I8N5K", "TAFENG"]
SC2_DATASETS = [d for d in ALL_DATASETS if d != "BMS1"]   # BMS1 is SC1 only

KS     = [25, 50, 67, 100]
STRATS = ["EVEN", "INCREASING", "DECREASING", "BURST"]

STATUS_COLOR = {"OK": None, "OT": "#b8860b", "OOM": "#c0392b",
                "Fail": "#000000", "Skip": "#7f7f7f", "MemSkip": "#8e44ad"}
STATUS_ORDER = ["OT", "OOM", "Fail", "MemSkip", "Skip"]


# %% ------------------------------------------------------------------ Cell 2
#    DATABASES (from common.Datasets) + LOADING
def load_datasets_table():
    """name -> dict(d_init, default_uut, sweep) parsed from src/common/Datasets.java."""
    path = os.path.join(REPO_DIR, "src", "common", "Datasets.java")
    if not os.path.exists(path):
        warnings.warn(f"{path} not found; default thresholds unavailable")
        return {}
    text = open(path, encoding="utf-8").read()
    pat = re.compile(r'new Entry\("([A-Za-z0-9_]+)",\s*(\d+),\s*([0-9.]+),\s*'
                     r'new double\[\]\{([^}]*)\}')
    out = {}
    for name, dinit, uut, sweep in pat.findall(text):
        out[name] = dict(d_init=int(dinit), default_uut=float(uut),
                         sweep=[float(v) for v in sweep.split(",")])
    return out


DATASETS = load_datasets_table()


def classify_status(op_type, runtime_ms):
    """Outcome of one row. The op_type suffix is authoritative; the runtime sentinel
    is the fallback. -3 is a completed run in live memory mode (its time is void)."""
    ot = str(op_type)
    if "SKIPPED_POISONED" in ot: return "Skip"
    if ot.endswith("_OOM"):      return "OOM"
    if ot.endswith("_OT"):       return "OT"
    if ot.endswith("_BUDGET"):   return "MemSkip"
    if ot.endswith("_FAILED"):   return "Fail"
    if pd.notna(runtime_ms) and (runtime_ms >= 0 or runtime_ms == -3):
        return "OK"
    if runtime_ms == -2:         return "OT"
    return "Fail"


def load_scenario(sc, tree=TIMING_TREE, finished_only=True):
    """All rows of one scenario: every <DB>/<ARM>/*.csv under results/<tree>/<sc>,
    with `arm` and `status`/`finished` columns. Arms without DONE are skipped."""
    base = os.path.join(DATA_DIR, tree, sc)
    frames, skipped = [], []
    for csv_path in sorted(glob.glob(os.path.join(base, "*", "*", "*.csv"))):
        arm_dir = os.path.dirname(csv_path)
        if finished_only and not os.path.exists(os.path.join(arm_dir, "DONE")):
            skipped.append(os.path.relpath(arm_dir, base))
            continue
        df = pd.read_csv(csv_path)
        df.columns = [c.strip() for c in df.columns]
        df["arm"] = os.path.basename(arm_dir)
        frames.append(df)
    if skipped:
        print(f"[{tree}/{sc}] left out, not DONE: {', '.join(skipped)}")
    if not frames:
        print(f"[skip] no finished arms under {base}")
        return None
    df = pd.concat(frames, ignore_index=True)
    for c in df.columns:
        if c not in ("algorithm", "dataset", "op_type", "run_id", "timestamp",
                     "cub_triggered", "arm", "mem_mode", "trigger_rule"):
            df[c] = pd.to_numeric(df[c], errors="coerce")
    df["status"] = [classify_status(o, r) for o, r in zip(df["op_type"], df["runtime_ms"])]
    df["finished"] = df["status"] == "OK"
    return df


def parse_su(df):
    df["su"] = df["op_type"].astype(str).str.extract(r"su=([0-9.]+)")[0].astype(float)
    return df


def parse_sl(df):
    df["sl"] = df["algorithm"].astype(str).str.extract(r"\[sl=([0-9.]+)\]")[0].astype(float)
    df["base_algo"] = df["algorithm"].astype(str).str.replace(r"\[sl=[0-9.]+\]", "", regex=True)
    return df


def parse_batch(df):
    ot = df["op_type"].astype(str)
    df["K"] = ot.str.extract(r"K=(\d+)")[0].astype(float)
    df["strat"] = ot.str.extract(r"strat=([A-Z]+)")[0]
    df["phase"] = np.where(ot.str.contains("_INIT"), "INIT", "INC")
    return df


def is_inc(df):
    return df["op_type"].astype(str).str.contains("_INC")


def default_su(ds):
    return DATASETS.get(ds, {}).get("default_uut")


# ---- unit + annotation helpers ----------------------------------------------
def ms_to_s(x):  return np.asarray(x, float) / 1000.0


def annotate_fail(ax, xpos, status="Fail"):
    """Mark a configuration that did not finish with its precise outcome."""
    ax.annotate(status, xy=(xpos, 0.97), xycoords=("data", "axes fraction"),
                ha="center", va="top", fontsize=6, rotation=90, weight="bold",
                color=STATUS_COLOR.get(status, "black"), alpha=0.9)


def write_csv(df, path):
    """Tables are read by people: round floats to three decimals."""
    df.round(3).to_csv(path, index=False)


def save(fig, name):
    path = os.path.join(OUT_DIR, name)
    fig.savefig(path, format="pdf")
    plt.close(fig)
    print(f"[saved] {path}")


def safe_log(ax):
    """Log y-scale only when the axis holds positive data."""
    ys = [np.asarray(ln.get_ydata(), float) for ln in ax.get_lines()]
    ys += [np.array([p.get_height()], float) for p in ax.patches]
    ally = np.concatenate(ys) if ys else np.array([])
    if ally.size and np.nanmax(ally) > 0:
        ax.set_yscale("log")
    else:
        ax.set_yticks([]); ax.set_ylim(0, 1)


def note_absent(ax, sub, expected, deferred_root=None, ds=None):
    """Under the title, name each expected arm that has no curve and why: out of
    memory on D_init (its INIT row failed) or deferred (no finished arm)."""
    notes = []
    for algo in expected:
        a = sub[sub["algorithm"] == algo]
        if a.empty:
            if deferred_root and os.path.exists(os.path.join(deferred_root, ds, algo, "DEFERRED")):
                notes.append(f"{ALGO_LABEL[algo]}: deferred")
            continue
        init = a[a["op_type"].astype(str).str.contains("_INIT")]
        if not init.empty and not bool(init.iloc[0]["finished"]):
            notes.append(f"{ALGO_LABEL[algo]}: {init.iloc[0]['status']} on $D_{{init}}$")
    if notes:
        ax.text(0.5, 1.0, "; ".join(notes), transform=ax.transAxes, ha="center",
                va="bottom", fontsize=5.5, style="italic")
        ax.set_title(ax.get_title(), pad=9)


def shared_legend(fig, axes, ncol=None, y=1.06):
    for ax in np.atleast_1d(axes).ravel():
        h, l = ax.get_legend_handles_labels()
        if h:
            fig.legend(h, l, loc="upper center", ncol=ncol or len(l), frameon=False,
                       bbox_to_anchor=(0.5, y))
            return


def panel_grid(n, ncols, w=2.6, h=2.3):
    nrows = int(np.ceil(n / ncols))
    fig, axes = plt.subplots(nrows, ncols, figsize=(w * ncols, h * nrows), squeeze=False)
    axes = axes.ravel()
    for ax in axes[n:]:
        ax.set_visible(False)
    return fig, axes[:n]


# %% ------------------------------------------------------------------ Cell 3
#    STATIC MINING (SC1 on D_init, SC3 on the whole database): metric vs threshold
def figset_scalability(sc="SC3", tag="fig_scalability", datasets=None,
                       metrics=("runtime_ms", "explored_nodes")):
    df = load_scenario(sc)
    if df is None:
        return
    df = parse_su(df)
    datasets = [d for d in (datasets or FIG_DATASETS) if d in set(df["dataset"])]
    spec = {"runtime_ms": ("Runtime (s)", ms_to_s),
            "explored_nodes": ("Explored nodes", lambda x: np.asarray(x, float))}
    for metric in metrics:
        ylab, conv = spec[metric]
        fig, axes = panel_grid(len(datasets), min(len(datasets), 4), w=2.9, h=2.7)
        for ax, ds in zip(axes, datasets):
            sub = df[df["dataset"] == ds]
            for algo in STATIC_ORDER:
                a = sub[sub["algorithm"] == algo].sort_values("su")
                if a.empty:
                    continue
                ok = a[a["finished"]]
                ax.plot(ok["su"], conv(ok[metric]), marker=ALGO_MARKER[algo],
                        color=ALGO_COLOR[algo], ms=7 if algo == PROPOSED else 5, lw=1.3,
                        label=ALGO_LABEL[algo], markeredgewidth=0.4, markeredgecolor="white")
                for _, r in a[~a["finished"]].iterrows():
                    annotate_fail(ax, r["su"], r["status"])
            ax.set_title(ds)
            ax.set_xlabel(r"Threshold $su$")
            sus = sorted(sub["su"].dropna().unique())
            ax.set_xticks(sus)
            ax.set_xticklabels([f"{v:.4g}" for v in sus], rotation=30, ha="right", fontsize=6.5)
            ax.minorticks_off()
            safe_log(ax)
            if list(axes).index(ax) % 4 == 0:
                ax.set_ylabel(ylab)
        shared_legend(fig, axes, y=1.10 if len(datasets) <= 4 else 1.05)
        fig.tight_layout()
        save(fig, f"{tag}_{sc}_{metric}.pdf")


# %% ------------------------------------------------------------------ Cell 4
#    INCREMENTAL (SC2): cumulative runtime along the stream, one panel per database
def figset_incremental(tag="fig_incremental_cumulative", datasets=None):
    """Cumulative runtime over the 100 increments, the initial mine excluded. A curve
    that stops early is an arm that ran out of memory; its end is marked."""
    df = load_scenario("SC2")
    if df is None:
        return
    datasets = [d for d in (datasets or SC2_DATASETS) if d in set(df["dataset"])]
    fig, axes = panel_grid(len(datasets), 4, w=2.5, h=2.2)
    for ax, ds in zip(axes, datasets):
        sub = df[(df["dataset"] == ds) & is_inc(df)]
        for algo in INC_ORDER:
            a = sub[sub["algorithm"] == algo].sort_values("iteration")
            if a.empty:
                continue
            ok = a[a["finished"]]
            if ok.empty:
                # nothing to draw: failed on D_init, every increment skipped
                ax.plot([], [], color=ALGO_COLOR[algo], label=ALGO_LABEL[algo])
                continue
            y = ms_to_s(ok["runtime_ms"]).cumsum()
            ax.plot(ok["iteration"], y, color=ALGO_COLOR[algo], lw=1.2,
                    label=ALGO_LABEL[algo])
            if len(ok) < len(a):
                ax.plot(ok["iteration"].iloc[-1], y[-1], marker="x",
                        color=ALGO_COLOR[algo], ms=6, mew=1.5)
        ax.set_title(ds)
        note_absent(ax, df[df["dataset"] == ds], INC_ORDER,
                    os.path.join(DATA_DIR, TIMING_TREE, "SC2"), ds)
        ax.set_xlabel("Increment")
        safe_log(ax)
        if ax is axes[0] or list(axes).index(ax) % 4 == 0:
            ax.set_ylabel("Cumulative runtime (s)")
    shared_legend(fig, axes, y=1.04)
    fig.tight_layout()
    save(fig, f"{tag}.pdf")


def export_incremental_table():
    """SC2: seconds over the increments that completed, rescans, increments done."""
    df = load_scenario("SC2")
    if df is None:
        return
    rows = []
    for (ds, algo), g in df[is_inc(df)].groupby(["dataset", "algorithm"]):
        ok = g[g["finished"]]
        rows.append(dict(dataset=ds, algorithm=algo,
                         increments_done=len(ok), increments=len(g),
                         runtime_s=ms_to_s(ok["runtime_ms"]).sum(),
                         rescans=(ok["rescan_count"].iloc[-1] if len(ok) else np.nan),
                         first_failure=(g.loc[~g["finished"], "status"].iloc[0]
                                        if (~g["finished"]).any() else "")))
    tab = pd.DataFrame(rows)
    path = os.path.join(OUT_DIR, "table_SC2_totals.csv")
    write_csv(tab, path)
    print(f"[saved] {path}")


# %% ------------------------------------------------------------------ Cell 5
#    ABLATION (SC7): C5 on the stream, C3 on one mine
def figset_c5_ablation(tag="fig_C5_ablation"):
    """C5 on (adaptive rescan) against C5 off (rescan at every increment), on the
    databases SC7 runs it on: total runtime over the increments and rescans."""
    df = load_scenario("SC7")
    if df is None:
        return
    df = df[df["algorithm"].isin(["proposed_C5_on", "proposed_C5_off"]) & is_inc(df)]
    if df.empty:
        return
    datasets = [d for d in ALL_DATASETS if d in set(df["dataset"])]
    variants = [("proposed_C5_on", "C5 on (adaptive)", "#d62728"),
                ("proposed_C5_off", "C5 off (rescan every increment)", "#4c72b0")]
    fig, axes = plt.subplots(1, 2, figsize=(6.0, 2.5))
    for ax, (metric, ylab) in zip(axes, [("runtime_ms", "Runtime over 100 increments (s)"),
                                         ("rescan_count", "Rescans")]):
        x = np.arange(len(datasets)); w = 0.38
        for i, (algo, lbl, col) in enumerate(variants):
            vals = []
            for ds in datasets:
                g = df[(df["algorithm"] == algo) & (df["dataset"] == ds)].sort_values("iteration")
                if g.empty or not g["finished"].all():
                    vals.append(np.nan); continue
                vals.append(ms_to_s(g["runtime_ms"]).sum() if metric == "runtime_ms"
                            else g["rescan_count"].iloc[-1])
            ax.bar(x + (i - 0.5) * w, np.nan_to_num(vals), width=w, color=col,
                   label=lbl, edgecolor="white", linewidth=0.3)
        ax.set_xticks(x); ax.set_xticklabels(datasets)
        ax.set_ylabel(ylab)
    shared_legend(fig, axes, ncol=2, y=1.08)
    fig.tight_layout()
    save(fig, f"{tag}.pdf")


def export_ablation_table():
    """SC7 as a table. Static: full, IUB off (C3) and Pre-HUSPM on one mine of the
    whole database - runtime, explored nodes, IUB cuts. Incremental: C5 and C4 arms
    summed over the 100 increments - runtime, rescans, block reads."""
    df = load_scenario("SC7")
    if df is None:
        return
    static = {"proposed_full": "Full", "proposed_no_IUB": "-IUB (C3)", "Pre-HUSPM": "Pre-HUSPM"}
    rows = []
    for ds in [d for d in ALL_DATASETS if d in set(df["dataset"])]:
        for arm, label in static.items():
            g = df[(df["dataset"] == ds) & (df["algorithm"] == arm)]
            if g.empty:
                continue
            r = g.iloc[-1]
            rows.append(dict(dataset=ds, variant=label, status=r["status"],
                             patterns=r["total_patterns_found"],
                             runtime_s=r["runtime_ms"] / 1000 if r["finished"] else np.nan,
                             explored=r["explored_nodes"] if r["finished"] else np.nan,
                             iub_cuts=r["pruned_iub"] if r["finished"] else np.nan))
    tab = pd.DataFrame(rows)
    write_csv(tab, os.path.join(OUT_DIR, "table_SC7_static.csv"))

    inc = {"proposed_C5_on": "Full", "proposed_C5_off": "-C5 (rescan every increment)",
           "proposed_no_block_index": "-C4 (no block index)",
           "proposed_no_C2": "-C2 (band fixed at LUT_min)"}
    rows = []
    for ds in [d for d in ALL_DATASETS if d in set(df["dataset"])]:
        for arm, label in inc.items():
            g = df[(df["dataset"] == ds) & (df["algorithm"] == arm) & is_inc(df)]
            if g.empty:
                continue
            ok = g[g["finished"]]
            rows.append(dict(dataset=ds, variant=label, increments_done=len(ok),
                             runtime_s=ms_to_s(ok["runtime_ms"]).sum(),
                             rescans=ok["rescan_count"].iloc[-1] if len(ok) else np.nan,
                             blocks_read=ok["blocks_read"].sum(),
                             mean_prelarge=ok["prelarge_count"].mean(),
                             final_patterns=ok["total_patterns_found"].iloc[-1] if len(ok) else np.nan))
    write_csv(pd.DataFrame(rows), os.path.join(OUT_DIR, "table_SC7_incremental.csv"))

    lines = ["\\begin{table}[t]", "\\centering",
             "\\caption{Ablation of C3 on one mine of the whole database (SC7). "
             "Runtime\\,(s) and explored nodes.}",
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
    with open(os.path.join(OUT_DIR, "table_SC7_ablation.tex"), "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines))
    print(f"[saved] {os.path.join(OUT_DIR, 'table_SC7_static.csv')}  (+ incremental csv + LaTeX)")


# %% ------------------------------------------------------------------ Cell 6
#    SENSITIVITY: SC4 pre-large floor, SC5 batch grid
def figset_sl_sensitivity(tag="fig_sl_sensitivity", metrics=("prelarge_count",)):
    df = load_scenario("SC4")
    if df is None:
        return
    df = parse_sl(df)
    datasets = [d for d in FIG_DATASETS if d in set(df["dataset"])]
    pair = [("Pre-HUSPM", "#ff7f0e", "Pre-HUSPM"), ("Adaptive", "#d62728", "Proposed")]
    spec = {"prelarge_count": ("Pre-large set size", lambda x: np.asarray(x, float)),
            "runtime_ms": ("Runtime (s)", ms_to_s)}
    for metric in metrics:
        ylab, conv = spec[metric]
        fig, axes = panel_grid(len(datasets), len(datasets), w=2.9, h=2.7)
        for ax, ds in zip(axes, datasets):
            sub = df[df["dataset"] == ds]
            sls = sorted(sub["sl"].dropna().unique())
            x = np.arange(len(sls)); w = 0.38
            for i, (base, col, lbl) in enumerate(pair):
                a = sub[sub["base_algo"] == base].set_index("sl")
                vals, stats = [], []
                for s in sls:
                    if s in a.index and bool(a.loc[s, "finished"]):
                        vals.append(conv([a.loc[s, metric]])[0]); stats.append(None)
                    elif s in a.index:
                        vals.append(np.nan); stats.append(str(a.loc[s, "status"]))
                    else:
                        vals.append(np.nan); stats.append(None)
                pos = x + (i - 0.5) * w
                ax.bar(pos, np.nan_to_num(vals), width=w, color=col, label=lbl,
                       edgecolor="white", linewidth=0.3)
                for p, st in zip(pos, stats):
                    if st:
                        annotate_fail(ax, p, st)
            ax.set_title(ds); ax.set_xlabel(r"Pre-large floor $r_l$")
            ax.set_xticks(x); ax.set_xticklabels([f"{s:g}" for s in sls])
            safe_log(ax)
            if ax is axes[0]:
                ax.set_ylabel(ylab)
        shared_legend(fig, axes, ncol=2, y=1.08)
        fig.tight_layout()
        save(fig, f"{tag}_{metric}.pdf")


def plot_heatmap(ax, mat, xt, yt, title, cbar_label, fmt=".2f", cmap="RdYlGn_r",
                 vmin=None, vmax=None, cbar=True):
    if HAS_SNS:
        sns.heatmap(mat, annot=True, fmt=fmt, cmap=cmap, ax=ax, vmin=vmin, vmax=vmax,
                    xticklabels=xt, yticklabels=yt, cbar=cbar,
                    cbar_kws={"label": cbar_label} if cbar else None, annot_kws={"size": 6})
    else:
        im = ax.imshow(mat, cmap=cmap, aspect="auto", vmin=vmin, vmax=vmax)
        cbar_on = cbar
        ax.set_xticks(range(len(xt))); ax.set_xticklabels(xt)
        ax.set_yticks(range(len(yt))); ax.set_yticklabels(yt)
        for i in range(mat.shape[0]):
            for j in range(mat.shape[1]):
                if np.isfinite(mat[i, j]):
                    ax.text(j, i, format(mat[i, j], fmt), ha="center", va="center", fontsize=6)
        if cbar_on:
            ax.figure.colorbar(im, ax=ax, label=cbar_label, fraction=0.046)
    ax.set_title(title)


def figset_batch_grid(tag="fig_batch_heatmap"):
    """SC5: the proposal's time over remine-from-scratch's, summed over the
    increments, K (rows) x strategy (columns). Below one, the proposal is faster."""
    df = load_scenario("SC5")
    if df is None:
        return
    df = parse_batch(df)
    inc = df[(df["phase"] == "INC") & df["finished"]]
    datasets = [d for d in FIG_DATASETS if d in set(df["dataset"])]
    fig, axes = panel_grid(len(datasets), len(datasets), w=3.0, h=2.6)
    for ax, ds in zip(axes, datasets):
        mat = np.full((len(KS), len(STRATS)), np.nan)
        for r, k in enumerate(KS):
            for c, s in enumerate(STRATS):
                cell = inc[(inc["dataset"] == ds) & (inc["K"] == k) & (inc["strat"] == s)]
                p = cell[cell["algorithm"] == PROPOSED]["runtime_ms"].sum()
                q = cell[cell["algorithm"] == "Remine-from-scratch"]["runtime_ms"].sum()
                if p > 0 and q > 0:
                    mat[r, c] = p / q
        plot_heatmap(ax, mat, ["EVEN", "INC.", "DEC.", "BURST"], KS, ds,
                     "Proposed / Remine", vmin=0.4, vmax=1.0, cbar=(ax is axes[-1]))
        ax.set_xlabel("Strategy")
        if ax is axes[0]:
            ax.set_ylabel("Increments K")
    fig.tight_layout()
    save(fig, f"{tag}.pdf")


# %% ------------------------------------------------------------------ Cell 7
#    MEMORY (live set, from the memory campaign)
def figset_memory_static(tag="fig_memory_static", datasets=None):
    """SC1 memory: live set at each database's default threshold, every algorithm."""
    df = load_scenario("SC1", tree=MEMORY_TREE)
    if df is None:
        return
    df = parse_su(df)
    datasets = [d for d in (datasets or ALL_DATASETS) if d in set(df["dataset"])]
    fig, ax = plt.subplots(figsize=(7.2, 2.6))
    x = np.arange(len(datasets)); w = 0.8 / len(STATIC_ORDER)
    for i, algo in enumerate(STATIC_ORDER):
        vals, stats = [], []
        for ds in datasets:
            su = default_su(ds)
            a = df[(df["dataset"] == ds) & (df["algorithm"] == algo)]
            a = a[np.isclose(a["su"], su)] if su is not None else a.iloc[0:0]
            if a.empty:
                vals.append(np.nan); stats.append(None)
            elif bool(a.iloc[-1]["finished"]):
                vals.append(max(float(a.iloc[-1]["mem_live_mb"]), 1.0)); stats.append(None)
            else:
                vals.append(np.nan); stats.append(str(a.iloc[-1]["status"]))
        pos = x + (i - (len(STATIC_ORDER) - 1) / 2) * w
        ax.bar(pos, np.nan_to_num(vals), width=w, color=ALGO_COLOR[algo],
               label=ALGO_LABEL[algo], edgecolor="white", linewidth=0.3)
        for p, st in zip(pos, stats):
            if st:
                annotate_fail(ax, p, st)
    ax.set_xticks(x); ax.set_xticklabels(datasets, fontsize=7)
    ax.set_ylabel("Live set (MB)")
    safe_log(ax)
    shared_legend(fig, [ax], ncol=6, y=1.10)
    fig.tight_layout()
    save(fig, f"{tag}.pdf")


def figset_memory_incremental(tag="fig_memory_incremental", datasets=None):
    """SC2 memory: the state each algorithm holds after every increment
    (mem_retained_mb against a baseline fixed before the arm's algorithm exists)."""
    df = load_scenario("SC2", tree=MEMORY_TREE)
    if df is None:
        return
    datasets = [d for d in (datasets or SC2_DATASETS) if d in set(df["dataset"])]
    fig, axes = panel_grid(len(datasets), 4, w=2.5, h=2.2)
    for ax, ds in zip(axes, datasets):
        sub = df[df["dataset"] == ds]
        for algo in INC_ORDER:
            a = sub[sub["algorithm"] == algo].sort_values("iteration")
            if a.empty:
                continue
            ok = a[a["finished"]]
            if ok.empty:
                ax.plot([], [], color=ALGO_COLOR[algo], label=ALGO_LABEL[algo])
                continue
            y = np.maximum(ok["mem_retained_mb"].to_numpy(float), 1.0)
            ax.plot(ok["iteration"], y, color=ALGO_COLOR[algo], lw=1.2, label=ALGO_LABEL[algo])
            if len(ok) < len(a):
                ax.plot(ok["iteration"].iloc[-1], y[-1], marker="x",
                        color=ALGO_COLOR[algo], ms=6, mew=1.5)
        ax.set_title(ds)
        note_absent(ax, sub, INC_ORDER, os.path.join(DATA_DIR, MEMORY_TREE, "SC2"), ds)
        ax.set_xlabel("Increment (0 = $D_{init}$)")
        safe_log(ax)
        if list(axes).index(ax) % 4 == 0:
            ax.set_ylabel("State held (MB)")
    shared_legend(fig, axes, y=1.04)
    fig.tight_layout()
    save(fig, f"{tag}.pdf")


# %% ------------------------------------------------------------------ Cell 8
#    TABLES: SC1 at the default threshold, status summary
def export_summary_tables():
    """SC1 at each database's default threshold: runtime, explored nodes, patterns
    and status per algorithm, the proposal's speedup over each, and live set from
    the memory campaign. CSV + LaTeX."""
    df = load_scenario("SC1")
    if df is None:
        return
    df = parse_su(df)
    mem = load_scenario("SC1", tree=MEMORY_TREE)
    mem = parse_su(mem) if mem is not None else None
    rows = []
    for ds in [d for d in ALL_DATASETS if d in set(df["dataset"])]:
        su = default_su(ds)
        for algo in STATIC_ORDER:
            a = df[(df["dataset"] == ds) & (df["algorithm"] == algo) & np.isclose(df["su"], su)]
            if a.empty:
                continue
            r = a.iloc[-1]
            live = np.nan
            if mem is not None:
                m = mem[(mem["dataset"] == ds) & (mem["algorithm"] == algo) & np.isclose(mem["su"], su)]
                if not m.empty and bool(m.iloc[-1]["finished"]):
                    live = m.iloc[-1]["mem_live_mb"]
            rows.append(dict(dataset=ds, su=su, algorithm=algo, status=r["status"],
                             runtime_s=r["runtime_ms"] / 1000 if r["finished"] else np.nan,
                             live_set_mb=live,
                             explored=r["explored_nodes"] if r["finished"] else np.nan,
                             patterns=r["total_patterns_found"] if r["finished"] else np.nan))
    tab = pd.DataFrame(rows)
    if tab.empty:
        return
    sp = []
    for ds, g in tab.groupby("dataset", sort=False):
        rp = g.loc[g["algorithm"] == PROPOSED, "runtime_s"]
        if rp.empty or not np.isfinite(rp.iloc[0]):
            continue
        for _, r in g.iterrows():
            sp.append(dict(dataset=ds, algorithm=r["algorithm"],
                           speedup_of_proposed=r["runtime_s"] / rp.iloc[0]))
    out_csv = os.path.join(OUT_DIR, "table_SC1_summary.csv")
    write_csv(tab, out_csv)
    write_csv(pd.DataFrame(sp), os.path.join(OUT_DIR, "table_SC1_speedup.csv"))

    piv = tab.pivot_table(index="dataset", columns="algorithm", values="runtime_s",
                          aggfunc="first").reindex(index=tab["dataset"].unique(), columns=STATIC_ORDER)
    stat = tab.pivot_table(index="dataset", columns="algorithm", values="status",
                           aggfunc="first").reindex(index=tab["dataset"].unique(), columns=STATIC_ORDER)
    cols = list(piv.columns)
    lines = ["\\begin{table}[t]", "\\centering",
             "\\caption{Runtime (s) on $D_{init}$ at each database's default threshold (SC1). "
             "\\textbf{OOM}: out of memory at 48\\,GB.}",
             "\\label{tab:sc1_runtime}",
             "\\begin{tabular}{l" + "r" * len(cols) + "}", "\\toprule",
             "Dataset & " + " & ".join(ALGO_LABEL[c] for c in cols) + " \\\\", "\\midrule"]
    for ds, row in piv.iterrows():
        cells = [f"{row[c]:.2f}" if pd.notna(row[c]) else str(stat.loc[ds, c]) for c in cols]
        lines.append(f"{ds} & " + " & ".join(cells) + " \\\\")
    lines += ["\\bottomrule", "\\end{tabular}", "\\end{table}", ""]
    with open(os.path.join(OUT_DIR, "table_SC1_runtime.tex"), "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines))
    print(f"[saved] {out_csv}  (+ speedup csv + LaTeX)")


def export_status_summary():
    """Rows per outcome, per tree x scenario x database: OK / OT / OOM / Fail / Skip."""
    order = ["OK"] + STATUS_ORDER
    rows = []
    for tree in (TIMING_TREE, MEMORY_TREE):
        for sc in ["SC1", "SC2", "SC3", "SC4", "SC5", "SC6", "SC7"]:
            if not os.path.isdir(os.path.join(DATA_DIR, tree, sc)):
                continue
            df = load_scenario(sc, tree=tree)
            if df is None:
                continue
            for ds, g in df.groupby("dataset"):
                vc = g["status"].value_counts().to_dict()
                rec = dict(tree=tree, scenario=sc, dataset=ds, total=len(g))
                rec.update({s: int(vc.get(s, 0)) for s in order})
                rows.append(rec)
    if not rows:
        return
    out = pd.DataFrame(rows)[["tree", "scenario", "dataset", "total"] + order]
    path = os.path.join(OUT_DIR, "table_status_summary.csv")
    write_csv(out, path)
    print(f"[saved] {path}")
    bad = out[out[STATUS_ORDER].sum(axis=1) > 0]
    for _, r in bad.iterrows():
        parts = [f"{s}={r[s]}" for s in STATUS_ORDER if r[s] > 0]
        print(f"      {r['tree']}/{r['scenario']}/{r['dataset']}: " + ", ".join(parts))


# %% ------------------------------------------------------------------ Cell 9
#    DRIVER - the figures the paper uses, and the tables behind its text
def main():
    print(f"DATA_DIR = {DATA_DIR}\nOUT_DIR  = {OUT_DIR}\nseaborn  = {HAS_SNS}")
    print(f"databases from common.Datasets: {', '.join(DATASETS) or 'none found'}\n")

    figset_scalability("SC3", "fig_scalability")       # runtime + explored vs su, whole DB
    figset_incremental()                               # SC2 cumulative runtime, 7 databases
    figset_c5_ablation()                               # SC7 C5 on / off
    figset_sl_sensitivity()                            # SC4 pre-large set vs r_l
    figset_batch_grid()                                # SC5 proposed / remine, K x strategy
    figset_memory_static()                             # SC1 live set, default threshold
    figset_memory_incremental()                        # SC2 state held per increment

    export_summary_tables()                            # SC1 runtime / live set / speedup
    export_incremental_table()                         # SC2 totals
    export_ablation_table()                            # SC7 static + incremental
    export_status_summary()                            # every non-OK row, by outcome
    print("\nDONE.")


def main_all():
    """Everything above plus the figures the paper does not use."""
    main()
    figset_scalability("SC1", "fig_scalability", datasets=ALL_DATASETS)
    figset_sl_sensitivity(metrics=("prelarge_count", "runtime_ms"))


if __name__ == "__main__":
    main()
