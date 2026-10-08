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
#  Core deps: pandas, numpy, matplotlib.
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
#                            proposed_no_block_index ("SC7_C5_*" / "SC7_C4_*", 101 rows)
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
import matplotlib.ticker


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
# Black and white, no fills: series are told apart by line style and hollow marker,
# bars by hatching. Times New Roman throughout, as in the manuscript (mathptmx), with
# every font embedded as TrueType. Figures are drawn at the width they are printed at,
# so a 9 pt font here is 9 pt on the page: the manuscript's \linewidth, measured from
# its class and geometry, is 324.36 pt.
from matplotlib import font_manager
try:
    font_manager.findfont("Times New Roman", fallback_to_default=False)
except Exception:
    sys.exit("Times New Roman is not installed; refusing to draw with a substitute font.")

LINEWIDTH_IN = 324.36 / 72.27          # \linewidth of the manuscript, in inches
FONT_PT = 9
plt.rcParams.update({
    "font.family":        "Times New Roman",
    "mathtext.fontset":   "custom",
    "mathtext.rm":        "Times New Roman",
    "mathtext.it":        "Times New Roman:italic",
    "mathtext.bf":        "Times New Roman:bold",
    "font.size":          FONT_PT,
    "axes.titlesize":     FONT_PT,
    "axes.labelsize":     FONT_PT,
    "legend.fontsize":    FONT_PT,
    "xtick.labelsize":    FONT_PT,
    "ytick.labelsize":    FONT_PT,
    "axes.linewidth":     0.6,
    "lines.linewidth":    0.9,
    "lines.markersize":   4,
    "xtick.major.width":  0.6,
    "ytick.major.width":  0.6,
    "axes.grid":          True,
    "grid.color":         "0.8",
    "grid.linewidth":     0.4,
    "grid.linestyle":     ":",
    "axes.axisbelow":     True,
    "hatch.linewidth":    0.5,
    "legend.frameon":     False,
    "figure.dpi":         300,
    "savefig.dpi":        300,
    "savefig.bbox":       "tight",
    "savefig.pad_inches": 0.02,
    "pdf.fonttype":       42,
    "ps.fonttype":        42,
})

# ---- Algorithms: order, labels, line styles, markers --------------------------
STATIC_ORDER = ["USpan", "HUSP-ULL", "HUSP-SP", "IncUSP-Miner+", "Pre-HUSPM", "Adaptive-Full"]
INC_ORDER    = ["IncUSP-Miner+", "Pre-HUSPM", "Pre-HUSPM-TSU", "Remine-from-scratch", "Adaptive-Full"]
PROPOSED = "Adaptive-Full"
PROPOSED_LABEL = "Pre-HUSPM-Adaptive"
ALGO_LABEL = {
    "USpan": "USpan", "HUSP-ULL": "HUSP-ULL", "HUSP-SP": "HUSP-SP",
    "IncUSP-Miner+": "IncUSP-Miner$^{+}$", "Pre-HUSPM": "Pre-HUSPM",
    "Pre-HUSPM-TSU": "Pre-HUSPM-TSU", "Remine-from-scratch": "Remine",
    "Adaptive-Full": PROPOSED_LABEL,
}
ALGO_LINESTYLE = {
    "USpan": (0, (1, 1.5)), "HUSP-ULL": (0, (4, 1.5)), "HUSP-SP": (0, (4, 1.5, 1, 1.5)),
    "IncUSP-Miner+": (0, (6, 2)), "Pre-HUSPM": (0, (2, 1.5)), "Pre-HUSPM-TSU": (0, (6, 1.5, 1, 1.5, 1, 1.5)),
    "Remine-from-scratch": (0, (8, 2, 2, 2)), "Adaptive-Full": "-",
}
ALGO_MARKER = {
    "USpan": "o", "HUSP-ULL": "s", "HUSP-SP": "v", "IncUSP-Miner+": "^",
    "Pre-HUSPM": "D", "Pre-HUSPM-TSU": "P", "Remine-from-scratch": "x",
    "Adaptive-Full": "*",
}
ALGO_HATCH = {
    "USpan": "", "HUSP-ULL": "////", "HUSP-SP": "\\\\", "IncUSP-Miner+": "xxxx",
    "Pre-HUSPM": "....", "Pre-HUSPM-TSU": "----", "Remine-from-scratch": "++",
    "Adaptive-Full": "||||",
}


def line_style(algo, markevery=None):
    """Keyword arguments for one series: black, its own dash pattern, hollow marker."""
    kw = dict(color="black", linestyle=ALGO_LINESTYLE[algo], marker=ALGO_MARKER[algo],
              markerfacecolor="none", markeredgecolor="black", markeredgewidth=0.7,
              lw=1.2 if algo == PROPOSED else 0.9, ms=6 if algo == PROPOSED else 4,
              label=ALGO_LABEL[algo])
    if markevery is not None:
        kw["markevery"] = markevery
    return kw


def bar_style(hatch, label):
    return dict(facecolor="white", edgecolor="black", hatch=hatch, linewidth=0.6, label=label)


# SUBSET_3 of the runner: the three databases SC3-SC6 measure, and the panels of
# every three-panel figure.
SUBSET_3 = ["SIGN", "LEVIATHAN", "C8T1S5I8N5K"]
FIG_DATASETS = SUBSET_3
# Display order of all eight: smallest to largest, as in the README table.
ALL_DATASETS = ["SIGN", "LEVIATHAN", "FIFA", "BIBLE", "BMS1", "KOSARAK", "C8T1S5I8N5K", "TAFENG"]
SC2_DATASETS = [d for d in ALL_DATASETS if d != "BMS1"]   # BMS1 is SC1 only

KS     = [25, 50, 67, 100]
STRATS = ["EVEN", "INCREASING", "DECREASING", "BURST"]

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


REPLICATE_TREE = "campaign-replicates"


def load_scenario(sc, tree=TIMING_TREE, finished_only=True, replicates=False):
    """All rows of one scenario: every <DB>/<ARM>/*.csv under results/<tree>/<sc>,
    with `arm`, `replicate` and `status`/`finished` columns. Arms without DONE are
    skipped. With replicates=True the timing tree is read together with every replicate
    tree under results/campaign-replicates/s<k>r<n>; the main tree is replicate s0r0.
    Without it only the main tree is read, so a figure drawn from one run per cell is
    never handed several rows per cell."""
    bases = [(os.path.join(DATA_DIR, tree, sc), "s0r0")]
    if replicates and tree == TIMING_TREE:
        for rd in sorted(glob.glob(os.path.join(DATA_DIR, REPLICATE_TREE, "s*r*"))):
            bases.append((os.path.join(rd, sc), os.path.basename(rd)))
    frames, skipped = [], []
    for base, tag in bases:
      for csv_path in sorted(glob.glob(os.path.join(base, "*", "*", "*.csv"))):
        arm_dir = os.path.dirname(csv_path)
        if finished_only and not os.path.exists(os.path.join(arm_dir, "DONE")):
            skipped.append(f"{tag}:{os.path.relpath(arm_dir, base)}")
            continue
        df = pd.read_csv(csv_path)
        df.columns = [c.strip() for c in df.columns]
        df["arm"] = os.path.basename(arm_dir)
        df["replicate"] = tag
        frames.append(df)
    if skipped:
        print(f"[{tree}/{sc}] left out, not DONE: {', '.join(skipped)}")
    if not frames:
        print(f"[skip] no finished arms under {base}")
        return None
    df = pd.concat(frames, ignore_index=True)
    for c in df.columns:
        if c not in ("algorithm", "dataset", "op_type", "run_id", "timestamp",
                     "cub_triggered", "arm", "mem_mode", "trigger_rule", "replicate"):
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


STATUS_MARK = {"OOM": ("x", "Out of memory (48 GB)"), "OT": ("+", "Over the time limit"),
               "Fail": ("1", "Failed"), "MemSkip": ("2", "Skipped by the memory guard"),
               "Skip": ("3", "Skipped after an earlier failure")}


def annotate_fail(ax, xpos, status="Fail"):
    """Mark a configuration that did not finish, at the foot of the axis, with a symbol
    per outcome; the legend names the outcome. A label in the plot would not fit a bar
    slot at a legible size."""
    mark, label = STATUS_MARK.get(status, STATUS_MARK["Fail"])
    shown = getattr(ax.figure, "_status_shown", set())
    ax.plot([xpos], [0.04], transform=ax.get_xaxis_transform(), marker=mark, color="black",
            ms=6, mew=1.0, linestyle="none", clip_on=False,
            label=None if status in shown else label)
    shown.add(status)
    ax.figure._status_shown = shown


def write_csv(df, path):
    """Tables are read by people: round floats to three decimals."""
    df.round(3).to_csv(path, index=False)


def _source_commit():
    """The repository commit the figures are drawn from, with -dirty when a tracked file
    under results/ or analysis/ differs from it (new, untracked result directories are not
    read by any figure); written into every PDF so a figure says where it came from."""
    try:
        import subprocess
        c = subprocess.check_output(["git", "rev-parse", "--short", "HEAD"], cwd=REPO_DIR,
                                    text=True, stderr=subprocess.DEVNULL).strip()
        d = subprocess.check_output(["git", "status", "--porcelain", "--untracked-files=no",
                                     "--", "results", "analysis"],
                                    cwd=REPO_DIR, text=True, stderr=subprocess.DEVNULL).strip()
        return c + ("-dirty" if d else "")
    except Exception:
        return "unknown"


SOURCE_COMMIT = _source_commit()


def save(fig, name):
    path = os.path.join(OUT_DIR, name)
    fig.savefig(path, format="pdf",
                metadata={"Title": name, "Subject": f"pre-huspm-adaptive {SOURCE_COMMIT}",
                          "Creator": "analysis/make_figures.py"})
    plt.close(fig)
    print(f"[saved] {path}")


def safe_log(ax):
    """Log y-scale only when the axis holds positive data."""
    ys = [np.asarray(ln.get_ydata(), float) for ln in ax.get_lines()]
    ys += [np.array([p.get_height()], float) for p in ax.patches]
    ally = np.concatenate(ys) if ys else np.array([])
    if ally.size and np.nanmax(ally) > 0:
        ax.set_yscale("log")
        # decades only: minor labels such as 6x10^1 crowd a short axis
        ax.yaxis.set_minor_formatter(matplotlib.ticker.NullFormatter())
    else:
        ax.set_yticks([]); ax.set_ylim(0, 1)


STATUS_WORDS = {"OOM": "out of memory", "OT": "over the time limit", "Fail": "failed",
                "MemSkip": "refused by the memory guard", "Skip": "out of memory"}


def deferred_reason(path):
    """Why an arm was set aside, read from its DEFERRED file. Two reasons occur: it ran out
    of memory on D_init in the timing campaign, or it does not finish within the arm's
    eight-hour cap. Anything else is reported, not guessed."""
    text = open(path, encoding="utf-8-sig", errors="replace").read().lower()
    if "d_init within 48 gb" in text or "past d_init" in text:
        return "out of memory on $D_{\\mathrm{init}}$"
    if "eight-hour cap" in text or "within the eight-hour" in text or "measured, not finished" in text:
        return "not finished within 8 h"
    print(f"[warn] {path}: reason not recognised; labelled 'set aside'")
    return "set aside"


def note_absent(sub, expected, deferred_root=None, ds=None):
    """The expected arms that have no curve, and why: failed on D_init (its INIT row did
    not finish) or set aside (DEFERRED: it did not finish within the arm's time cap).
    Returned, not drawn: a note inside a panel covers the curves."""
    notes = []
    for algo in expected:
        a = sub[sub["algorithm"] == algo]
        if a.empty:
            path = os.path.join(deferred_root, ds, algo, "DEFERRED") if deferred_root else None
            if path and os.path.exists(path):
                notes.append(f"{ALGO_LABEL[algo]} {deferred_reason(path)}")
            continue
        init = a[a["op_type"].astype(str).str.contains("_INIT")]
        if not init.empty and not bool(init.iloc[0]["finished"]):
            st = str(init.iloc[0]["status"])
            notes.append(f"{ALGO_LABEL[algo]} {STATUS_WORDS.get(st, st)} on $D_{{\\mathrm{{init}}}}$")
    return notes


def absent_footnote(fig, absent, width_chars=72):
    """One note under the panels naming, per panel, the arms that have no curve."""
    import textwrap
    parts = [f"({chr(ord('a') + i)}) {ds}: " + "; ".join(n) + "." for i, ds, n in absent if n]
    if not parts:
        return
    text = "No curve - " + " ".join(parts)
    fig.text(0.0, 0.0, "\n".join(textwrap.wrap(text, width_chars, break_on_hyphens=False)),
             ha="left", va="top",
             transform=fig.transFigure)


def shared_legend(fig, axes, ncol=None, y=0.97):
    """One legend for the whole figure, above the panels: every series once, in the
    order first drawn, then the outcome symbols of runs that did not finish."""
    handles, labels = [], []
    for ax in np.atleast_1d(axes).ravel():
        for h, l in zip(*ax.get_legend_handles_labels()):
            if l not in labels:
                handles.append(h); labels.append(l)
    outcome = {lbl for _, lbl in STATUS_MARK.values()}
    order = [i for i, l in enumerate(labels) if l not in outcome] + \
            [i for i, l in enumerate(labels) if l in outcome]
    handles = [handles[i] for i in order]; labels = [labels[i] for i in order]
    if handles:
        fig.legend(handles, labels, loc="lower center", ncol=ncol or min(len(labels), 3),
                   frameon=False, bbox_to_anchor=(0.5, y), handlelength=2.6, columnspacing=0.8,
                   handletextpad=0.4)


def panel_xlabel(ax, i, name, xlabel):
    """No titles inside a figure: the panel is named under its axis, as (a) NAME."""
    ax.set_xlabel(f"{xlabel}\n({chr(ord('a') + i)}) {name}")


def panel_grid(n, ncols, h=1.9, width=LINEWIDTH_IN):
    nrows = int(np.ceil(n / ncols))
    fig, axes = plt.subplots(nrows, ncols, figsize=(width, h * nrows), squeeze=False)
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
    ncols = min(len(datasets), 3)
    for metric in metrics:
        ylab, conv = spec[metric]
        fig, axes = panel_grid(len(datasets), ncols, h=1.9)
        for i, (ax, ds) in enumerate(zip(axes, datasets)):
            sub = df[df["dataset"] == ds]
            sus = sorted(sub["su"].dropna().unique())
            level = {v: k for k, v in enumerate(sus)}
            for algo in STATIC_ORDER:
                a = sub[sub["algorithm"] == algo].sort_values("su")
                if a.empty:
                    continue
                ok = a[a["finished"]]
                # the four levels are evenly spaced on the axis and labelled with their
                # values: they are not evenly spaced in value, and their labels collide
                ax.plot([level[v] for v in ok["su"]], conv(ok[metric]), **line_style(algo))
                for _, r in a[~a["finished"]].iterrows():
                    annotate_fail(ax, level[r["su"]], r["status"])
            ax.set_xticks(range(len(sus)))
            ax.set_xticklabels([f"{v:.4g}" for v in sus], rotation=45, ha="right")
            ax.set_xlim(-0.3, len(sus) - 0.7)
            ax.minorticks_off()
            panel_xlabel(ax, i, ds, "UUT")
            safe_log(ax)
            if i % ncols == 0:
                ax.set_ylabel(ylab)
        fig.tight_layout(w_pad=0.6)
        shared_legend(fig, axes, ncol=3)
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
    fig, axes = panel_grid(len(datasets), 2, h=1.8)
    absent = []
    for i, (ax, ds) in enumerate(zip(axes, datasets)):
        sub = df[(df["dataset"] == ds) & is_inc(df)]
        for algo in INC_ORDER:
            a = sub[sub["algorithm"] == algo].sort_values("iteration")
            if a.empty:
                continue
            ok = a[a["finished"]]
            if ok.empty:
                # nothing to draw: failed on D_init, every increment skipped
                ax.plot([], [], **line_style(algo))
                continue
            y = ms_to_s(ok["runtime_ms"]).cumsum()
            ax.plot(ok["iteration"], y, **line_style(algo, markevery=20))
            if len(ok) < len(a):
                ax.plot(ok["iteration"].iloc[-1], y[-1], marker="X", color="black",
                        markerfacecolor="black", ms=5, linestyle="none")
        absent.append((i, ds, note_absent(df[df["dataset"] == ds], INC_ORDER,
                                          os.path.join(DATA_DIR, TIMING_TREE, "SC2"), ds)))
        panel_xlabel(ax, i, ds, "Increment")
        safe_log(ax)
        if i % 2 == 0:
            ax.set_ylabel("Cumulative runtime (s)")
    fig.tight_layout(w_pad=0.6)
    shared_legend(fig, axes, ncol=3)
    absent_footnote(fig, absent)
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


def operating_region(df):
    """Per database, the proposal's rescan test over the increments: the ratio of the
    test quantity to its bound (iau_max / sft_val under the joint rule), its median,
    minimum and maximum, the increments within budget, and the rescans. An increment
    rescans exactly when the ratio exceeds 1, so the rescan count read from the
    counter must equal the number of increments above 1; `check` says whether it does,
    and a mismatch is reported rather than smoothed over."""
    rows = []
    for ds, g in df[(df["algorithm"] == PROPOSED) & is_inc(df)].groupby("dataset", sort=False):
        g = g.sort_values("iteration")
        ok = g[g["finished"]]
        if ok.empty:
            continue
        rules = sorted(set(ok["trigger_rule"].astype(str)))
        ratio = ok["iau_max"].to_numpy(float) / ok["sft_val"].to_numpy(float)
        above = int(np.sum(ratio > 1.0))
        triggered = int(ok["cub_triggered"].astype(str).str.lower().eq("true").sum())
        rescans = int(ok["rescan_count"].iloc[-1])
        rows.append(dict(dataset=ds, rule="/".join(rules), increments=len(ok),
                         ratio_median=float(np.median(ratio)), ratio_min=float(np.min(ratio)),
                         ratio_max=float(np.max(ratio)), within_budget=len(ok) - above,
                         rescans=rescans,
                         check="ok" if above == rescans == triggered
                               else f"MISMATCH above={above} triggered={triggered} rescans={rescans}"))
    return pd.DataFrame(rows)


def export_operating_region():
    df = load_scenario("SC2")
    if df is None:
        return
    tab = operating_region(df)
    if tab.empty:
        return
    path = os.path.join(OUT_DIR, "table_SC2_operating_region.csv")
    write_csv(tab, path)
    print(f"[saved] {path}")
    bad = tab[tab["check"] != "ok"]
    for _, r in bad.iterrows():
        print(f"      {r['dataset']}: {r['check']}")


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
    variants = [("proposed_C5_on", "Adaptive rescan decision", ""),
                ("proposed_C5_off", "Rescan at every increment", "////")]
    fig, axes = plt.subplots(1, 2, figsize=(LINEWIDTH_IN, 2.0))
    for i, (ax, (metric, ylab)) in enumerate(zip(axes, [("runtime_ms", "Runtime over 100 increments (s)"),
                                                        ("rescan_count", "Rescans")])):
        x = np.arange(len(datasets)); w = 0.38
        for j, (algo, lbl, hatch) in enumerate(variants):
            vals = []
            for ds in datasets:
                g = df[(df["algorithm"] == algo) & (df["dataset"] == ds)].sort_values("iteration")
                if g.empty or not g["finished"].all():
                    vals.append(np.nan); continue
                vals.append(ms_to_s(g["runtime_ms"]).sum() if metric == "runtime_ms"
                            else g["rescan_count"].iloc[-1])
            ax.bar(x + (j - 0.5) * w, np.nan_to_num(vals), width=w, **bar_style(hatch, lbl))
        ax.set_xticks(x); ax.set_xticklabels(datasets)
        ax.set_xlabel(f"({chr(ord('a') + i)})")
        ax.set_ylabel(ylab)
    fig.tight_layout(w_pad=1.0)
    shared_legend(fig, axes, ncol=2)
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
           "proposed_no_block_index": "-C4 (no block index)"}
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
             "\\caption{Runtime and explored nodes with and without the IUB bound.}",
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
    pair = [("Pre-HUSPM", "////", "Pre-HUSPM"), ("Adaptive", "", PROPOSED_LABEL)]
    spec = {"prelarge_count": ("Pre-large set size", lambda x: np.asarray(x, float)),
            "runtime_ms": ("Runtime (s)", ms_to_s)}
    for metric in metrics:
        ylab, conv = spec[metric]
        fig, axes = panel_grid(len(datasets), len(datasets), h=1.9)
        for i, (ax, ds) in enumerate(zip(axes, datasets)):
            sub = df[df["dataset"] == ds]
            sls = sorted(sub["sl"].dropna().unique())
            x = np.arange(len(sls)); w = 0.38
            for j, (base, hatch, lbl) in enumerate(pair):
                a = sub[sub["base_algo"] == base].set_index("sl")
                vals, stats = [], []
                for sl in sls:
                    if sl in a.index and bool(a.loc[sl, "finished"]):
                        vals.append(conv([a.loc[sl, metric]])[0]); stats.append(None)
                    elif sl in a.index:
                        vals.append(np.nan); stats.append(str(a.loc[sl, "status"]))
                    else:
                        vals.append(np.nan); stats.append(None)
                pos = x + (j - 0.5) * w
                ax.bar(pos, np.nan_to_num(vals), width=w, **bar_style(hatch, lbl))
                for pp, st in zip(pos, stats):
                    if st:
                        annotate_fail(ax, pp, st)
            ax.set_xticks(x); ax.set_xticklabels([f"{v:g}" for v in sls])
            panel_xlabel(ax, i, ds, r"$r_l$")
            safe_log(ax)
            if i == 0:
                ax.set_ylabel(ylab)
        fig.tight_layout(w_pad=0.6)
        shared_legend(fig, axes, ncol=2)
        save(fig, f"{tag}_{metric}.pdf")


STRAT_STYLE = {"EVEN": ("-", "o"), "INCREASING": ((0, (4, 1.5)), "s"),
               "DECREASING": ((0, (1, 1.5)), "^"), "BURST": ((0, (6, 1.5, 1, 1.5)), "D")}


def figset_batch_ratio(tag="fig_batch_ratio"):
    """SC5: the proposal's time over remine-from-scratch's, summed over the increments,
    against K, one line per strategy. Below one, the proposal is faster."""
    df = load_scenario("SC5")
    if df is None:
        return
    df = parse_batch(df)
    inc = df[(df["phase"] == "INC") & df["finished"]]
    datasets = [d for d in FIG_DATASETS if d in set(df["dataset"])]
    fig, axes = panel_grid(len(datasets), len(datasets), h=1.9)
    for i, (ax, ds) in enumerate(zip(axes, datasets)):
        for strat in STRATS:
            ys = []
            for k in KS:
                cell = inc[(inc["dataset"] == ds) & (inc["K"] == k) & (inc["strat"] == strat)]
                p = cell[cell["algorithm"] == PROPOSED]["runtime_ms"].sum()
                q = cell[cell["algorithm"] == "Remine-from-scratch"]["runtime_ms"].sum()
                ys.append(p / q if p > 0 and q > 0 else np.nan)
            ls, mk = STRAT_STYLE[strat]
            ax.plot(KS, ys, color="black", linestyle=ls, marker=mk, markerfacecolor="none",
                    markeredgecolor="black", markeredgewidth=0.7, lw=0.9, ms=4,
                    label=strat.capitalize())
        ax.axhline(1.0, color="black", lw=0.5)
        ax.set_xticks(KS)
        panel_xlabel(ax, i, ds, "Increments $K$")
        if i == 0:
            ax.set_ylabel(f"{PROPOSED_LABEL} / Remine")
    fig.tight_layout(w_pad=0.6)
    shared_legend(fig, axes, ncol=4)
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
    fig, ax = plt.subplots(figsize=(LINEWIDTH_IN, 2.3))
    x = np.arange(len(datasets)); w = 0.84 / len(STATIC_ORDER)
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
        ax.bar(pos, np.nan_to_num(vals), width=w, **bar_style(ALGO_HATCH[algo], ALGO_LABEL[algo]))
        for pp, st in zip(pos, stats):
            if st:
                annotate_fail(ax, pp, st)
    ax.set_xticks(x); ax.set_xticklabels(datasets, rotation=30, ha="right")
    ax.set_ylabel("Live set (MB)")
    safe_log(ax)
    fig.tight_layout()
    shared_legend(fig, [ax], ncol=3)
    save(fig, f"{tag}.pdf")


def figset_memory_incremental(tag="fig_memory_incremental", datasets=None):
    """SC2 memory: the state each algorithm holds after every increment
    (mem_retained_mb against a baseline fixed before the arm's algorithm exists)."""
    df = load_scenario("SC2", tree=MEMORY_TREE)
    if df is None:
        return
    datasets = [d for d in (datasets or SC2_DATASETS) if d in set(df["dataset"])]
    fig, axes = panel_grid(len(datasets), 2, h=1.8)
    absent = []
    for i, (ax, ds) in enumerate(zip(axes, datasets)):
        sub = df[df["dataset"] == ds]
        for algo in INC_ORDER:
            a = sub[sub["algorithm"] == algo].sort_values("iteration")
            if a.empty:
                continue
            ok = a[a["finished"]]
            if ok.empty:
                ax.plot([], [], **line_style(algo))
                continue
            y = np.maximum(ok["mem_retained_mb"].to_numpy(float), 1.0)
            ax.plot(ok["iteration"], y, **line_style(algo, markevery=20))
            if len(ok) < len(a):
                ax.plot(ok["iteration"].iloc[-1], y[-1], marker="X", color="black",
                        markerfacecolor="black", ms=5, linestyle="none")
        absent.append((i, ds, note_absent(sub, INC_ORDER, os.path.join(DATA_DIR, MEMORY_TREE, "SC2"), ds)))
        panel_xlabel(ax, i, ds, "Increment (0 = $D_{init}$)")
        safe_log(ax)
        if i % 2 == 0:
            ax.set_ylabel("State held (MB)")
    fig.tight_layout(w_pad=0.6)
    shared_legend(fig, axes, ncol=3)
    absent_footnote(fig, absent)
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

    # The LaTeX runtime table is written by export_replicate_tables, from every replicate.
    print(f"[saved] {out_csv}  (+ speedup csv)")


def export_memory_ratios():
    """SC1 live set, each baseline over the proposal, over every (database, threshold)
    cell where both completed: geometric mean, cells used, cells left out and why (the
    outcome of whichever did not finish), and the cells where the baseline holds less.
    Leaving out a baseline's out-of-memory cell lowers the ratio, so it does not favour
    the proposal. Two scopes: all four thresholds, and the default threshold alone."""
    df = load_scenario("SC1", tree=MEMORY_TREE)
    if df is None:
        return
    df = parse_su(df)
    rows = []
    for base in [a for a in STATIC_ORDER if a != PROPOSED]:
        for scope in ("all four thresholds", "default threshold"):
            ratios, left_out, cells = [], [], 0
            for ds in [d for d in ALL_DATASETS if d in set(df["dataset"])]:
                sub = df[df["dataset"] == ds]
                sus = sorted(sub["su"].dropna().unique()) if scope.startswith("all") else [default_su(ds)]
                for su in sus:
                    cells += 1
                    p = sub[(sub["algorithm"] == PROPOSED) & np.isclose(sub["su"], su)]
                    b = sub[(sub["algorithm"] == base) & np.isclose(sub["su"], su)]
                    if p.empty or b.empty:
                        left_out.append(f"{ds}@{su:g}:absent"); continue
                    p, b = p.iloc[-1], b.iloc[-1]
                    if not (p["finished"] and b["finished"]):
                        who = base if not b["finished"] else PROPOSED
                        st = b["status"] if not b["finished"] else p["status"]
                        left_out.append(f"{ds}@{su:g}:{who} {st}"); continue
                    ratios.append(float(b["mem_live_mb"]) / float(p["mem_live_mb"]))
            r = np.asarray(ratios, float)
            rows.append(dict(baseline=base, scope=scope, cells=cells, cells_used=len(r),
                             geomean_ratio=float(np.exp(np.mean(np.log(r)))) if len(r) else np.nan,
                             baseline_holds_less=int(np.sum(r < 1)),
                             left_out="; ".join(left_out)))
    path = os.path.join(OUT_DIR, "table_SC1_memory_ratios.csv")
    write_csv(pd.DataFrame(rows), path)
    print(f"[saved] {path}")


def spread(values):
    """n, mean, sample standard deviation (NaN for one value), min, max."""
    v = np.asarray(values, float)
    n = len(v)
    return dict(n=n, mean=float(np.mean(v)) if n else np.nan,
                std=float(np.std(v, ddof=1)) if n > 1 else np.nan,
                min=float(np.min(v)) if n else np.nan, max=float(np.max(v)) if n else np.nan)


def pm(st, digits=None):
    """'mean +- std' for LaTeX, or the mean alone when there is one replicate. Decimals
    follow the size of the mean - none from 100, one from 10, two below - so that a row
    of six algorithms fits the column width."""
    if not np.isfinite(st["mean"]):
        return "--"
    if digits is None:
        digits = 0 if st["mean"] >= 100 else (1 if st["mean"] >= 10 else 2)
    if st["n"] > 1 and np.isfinite(st["std"]):
        # a deviation below the printed resolution reads as no spread at all; say so
        dev = (f"{st['std']:.{digits}f}" if round(st["std"], digits) > 0 or st["std"] == 0
               else f"<{10 ** -digits:.{digits}f}")
        # mean above, the deviation below in a smaller size: one column holds the wider of
        # the two rather than both side by side, which is what lets six columns fit
        return (f"\\begin{{tabular}}[t]{{@{{}}r@{{}}}}{st['mean']:.{digits}f}\\\\"
                f"{{\\scriptsize $\\pm${dev}}}\\end{{tabular}}")
    return f"{st['mean']:.{digits}f}"


def stacked_header(label):
    """A long column label split at its last hyphen onto two lines."""
    if len(label) <= 7 or "-" not in label:
        return label
    head, tail = label.rsplit("-", 1)
    return f"\\begin{{tabular}}[b]{{@{{}}r@{{}}}}{head}-\\\\{tail}\\end{{tabular}}"


def export_replicate_tables():
    """Runtime spread over the replicates, each measured in a fresh JVM.

    SC1   per database at the default threshold, every algorithm: runtime over the
          replicates, mean, sample standard deviation, min, max; a replicate that did
          not finish is counted in n_failed and left out of the figures.
    SC2   per database and algorithm that has replicates: the runtime summed over the
          increments (D_init excluded) per replicate, then its spread.
    SC6   per database and seed: the proposal's and remining's time summed over the
          twenty operations, their ratio, and whether every HUSP count agreed; then the
          ratio's spread over the seeds. Both arms replay the same plan at a seed, so
          the ratio is paired by seed.
    Counters (patterns, rescans, explored nodes) are deterministic for a given stream
    and are not tabulated here: their spread over repeats is zero by construction."""
    out = []
    df = load_scenario("SC1", replicates=True)
    if df is not None:
        df = parse_su(df)
        rows = []
        for ds in [d for d in ALL_DATASETS if d in set(df["dataset"])]:
            su = default_su(ds)
            for algo in STATIC_ORDER:
                a = df[(df["dataset"] == ds) & (df["algorithm"] == algo) & np.isclose(df["su"], su)]
                if a.empty:
                    continue
                ok = a[a["finished"]]
                st = spread(ms_to_s(ok["runtime_ms"]))
                rows.append(dict(dataset=ds, su=f"{su:g}", algorithm=algo, n_failed=int((~a["finished"]).sum()),
                                 status=(a.loc[~a["finished"], "status"].iloc[0] if (~a["finished"]).any() else "OK"),
                                 **{f"runtime_s_{k}" if k != "n" else "n": v for k, v in st.items()}))
        tab = pd.DataFrame(rows)
        write_csv(tab, os.path.join(OUT_DIR, "table_SC1_runtime_replicates.csv"))
        out.append("table_SC1_runtime_replicates.csv")
        cols = [a for a in STATIC_ORDER if a in set(tab["algorithm"])]
        nmax = int(tab["n"].max()) if len(tab) else 0
        lines = ["\\begin{table}[t]", "\\centering",
                 f"\\caption{{Runtime (s) on $D_{{\\mathrm{{init}}}}$ at the default threshold"
                 + (f", mean $\\pm$ standard deviation over {nmax} runs" if nmax > 1 else "") + ".}",
                 "\\label{tab:sc1_runtime}",
                 "\\small\\setlength{\\tabcolsep}{4pt}",
                 "\\begin{tabular}{l" + "r" * len(cols) + "}", "\\toprule",
                 "Dataset & " + " & ".join(stacked_header(ALGO_LABEL[c]) for c in cols) + " \\\\",
                 "\\midrule"]
        for ds in [d for d in ALL_DATASETS if d in set(tab["dataset"])]:
            cells = []
            for c in cols:
                r = tab[(tab["dataset"] == ds) & (tab["algorithm"] == c)]
                if r.empty:
                    cells.append("--"); continue
                r = r.iloc[0]
                cells.append(r["status"] if r["n"] == 0 else
                             pm(dict(n=r["n"], mean=r["runtime_s_mean"], std=r["runtime_s_std"])))
            lines.append(f"{ds} & " + " & ".join(cells) + " \\\\")
        lines += ["\\bottomrule", "\\end{tabular}", "\\end{table}", ""]
        with open(os.path.join(OUT_DIR, "table_SC1_runtime.tex"), "w", encoding="utf-8") as fh:
            fh.write("\n".join(lines))
        out.append("table_SC1_runtime.tex")

    df = load_scenario("SC2", replicates=True)
    if df is not None:
        inc = df[is_inc(df)]
        reps = inc.groupby(["dataset", "algorithm"])["replicate"].nunique()
        rows = []
        for (ds, algo), nrep in reps.items():
            if nrep < 2:
                continue
            totals, failed = [], 0
            for rp, g in inc[(inc["dataset"] == ds) & (inc["algorithm"] == algo)].groupby("replicate"):
                if g["finished"].all():
                    totals.append(ms_to_s(g["runtime_ms"]).sum())
                else:
                    failed += 1
            st = spread(totals)
            rows.append(dict(dataset=ds, algorithm=algo, n_failed=failed,
                             **{f"total_s_{k}" if k != "n" else "n": v for k, v in st.items()}))
        if rows:
            write_csv(pd.DataFrame(rows), os.path.join(OUT_DIR, "table_SC2_replicates.csv"))
            out.append("table_SC2_replicates.csv")

    df = load_scenario("SC6", replicates=True)
    if df is not None:
        ops = df[~df["op_type"].astype(str).str.contains("INIT")]
        rows = []
        for (ds, seed), g in ops.groupby(["dataset", "seed"]):
            p = g[g["algorithm"] == PROPOSED].sort_values("iteration")
            q = g[g["algorithm"] == "Remine-from-scratch"].sort_values("iteration")
            if p.empty or q.empty:
                continue
            agree = (len(p) == len(q)
                     and (p["total_patterns_found"].to_numpy() == q["total_patterns_found"].to_numpy()).all())
            tp, tq = ms_to_s(p["runtime_ms"]).sum(), ms_to_s(q["runtime_ms"]).sum()
            done = bool(p["finished"].all() and q["finished"].all())
            rows.append(dict(dataset=ds, seed=int(seed), proposal_s=tp if done else np.nan,
                             remine_s=tq if done else np.nan, ratio=tp / tq if done and tq > 0 else np.nan,
                             counts_agree=agree, operations=len(p)))
        if rows:
            per_seed = pd.DataFrame(rows)
            write_csv(per_seed, os.path.join(OUT_DIR, "table_SC6_seeds.csv"))
            summ = []
            for ds, g in per_seed.groupby("dataset", sort=False):
                st = spread(g["ratio"].dropna())
                summ.append(dict(dataset=ds, seeds=len(g), counts_agree_all=bool(g["counts_agree"].all()),
                                 **{f"ratio_{k}" if k != "n" else "seeds_with_ratio": v for k, v in st.items()}))
            write_csv(pd.DataFrame(summ), os.path.join(OUT_DIR, "table_SC6_seed_summary.csv"))
            out += ["table_SC6_seeds.csv", "table_SC6_seed_summary.csv"]
    print("[saved] " + ", ".join(out))


def measurement_hosts():
    """Hosts named in the DONE files of every timing and memory arm, replicates included."""
    hosts = set()
    for pattern in (os.path.join(DATA_DIR, TIMING_TREE, "*", "*", "*", "DONE"),
                    os.path.join(DATA_DIR, MEMORY_TREE, "*", "*", "*", "DONE"),
                    os.path.join(DATA_DIR, REPLICATE_TREE, "*", "*", "*", "*", "DONE")):
        for f in glob.glob(pattern):
            m = re.search(r"host=(\S+)", open(f, encoding="utf-8", errors="replace").read())
            if m:
                hosts.add(m.group(1))
    return hosts


def export_platform():
    """The manuscript's sentence on the measurement platform, generated from the machine
    record (record-machine.ps1 -> results/machine/<host>.json), never typed. Refuses when
    the arms name more than one host or the host has no record, and names the fields the
    record could not read."""
    import json
    hosts = measurement_hosts()
    if len(hosts) != 1:
        print(f"[platform] not written: the arms name {len(hosts)} hosts: {sorted(hosts)}")
        return
    host = hosts.pop()
    path = os.path.join(DATA_DIR, "machine", f"{host}.json")
    if not os.path.exists(path):
        print(f"[platform] not written: no machine record {path}; run record-machine.ps1 on {host}")
        return
    rec = json.load(open(path, encoding="utf-8"))
    props = rec.get("java_properties") or {}
    if isinstance(props, str):
        props = {}
    os_name = rec.get("os", "")
    if rec.get("os_display") not in (None, "unavailable"):
        os_name += f" {rec['os_display']}"
    if rec.get("os_build") not in (None, "unavailable"):
        os_name += f" (build {rec['os_build']})"
    # The JDK is named by the environment lock, the one place it is pinned: the arms ran on
    # two builds of OpenJDK 25.0.4 (results/jdk-comparison), and the label covers both.
    lock_path = os.path.join(REPO_DIR, "environment.lock.json")
    if not os.path.exists(lock_path):
        print(f"[platform] not written: no {lock_path}")
        return
    jvm = json.load(open(lock_path, encoding="utf-8"))["jdk"]["paper_label"]
    # installed memory: the sum of the modules, when they could be read, rather than the
    # usable figure Windows reports after reserving part of it
    mods = rec.get("memory_modules")
    memory = rec.get("memory_gb")
    if isinstance(mods, list) and mods:
        memory = int(round(sum(float(m.get("capacity_gb", 0)) for m in mods)))
    sentence = (f"All runtimes and memory figures were measured on one machine with an "
                f"{rec.get('cpu')} ({rec.get('cpu_cores')} cores, {rec.get('cpu_threads')} threads) "
                f"and {memory}\\,GB of memory, running {os_name} and "
                f"{jvm}, with \\texttt{{{rec.get('jvm_flags')}}} and one JVM per run.")
    with open(os.path.join(OUT_DIR, "platform.tex"), "w", encoding="utf-8") as fh:
        fh.write(sentence + "\n")
    missing = rec.get("unavailable") or []
    print(f"[saved] platform.tex from {path}" + (f"; UNREAD FIELDS: {missing}" if missing else ""))


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
    print(f"DATA_DIR = {DATA_DIR}\nOUT_DIR  = {OUT_DIR}")
    print(f"databases from common.Datasets: {', '.join(DATASETS) or 'none found'}\n")

    figset_scalability("SC3", "fig_scalability")       # runtime + explored vs su, whole DB
    figset_incremental()                               # SC2 cumulative runtime, 7 databases
    figset_c5_ablation()                               # SC7 C5 on / off
    figset_sl_sensitivity()                            # SC4 pre-large set vs r_l
    figset_batch_ratio()                               # SC5 proposed / remine against K
    figset_memory_static()                             # SC1 live set, default threshold
    figset_memory_incremental()                        # SC2 state held per increment

    export_summary_tables()                            # SC1 runtime / live set / speedup
    export_memory_ratios()                             # SC1 live set, baselines over the proposal
    export_replicate_tables()                          # runtime spread over replicates (SC1, SC2, SC6)
    export_platform()                                  # the platform sentence, from the machine record
    export_incremental_table()                         # SC2 totals
    export_operating_region()                          # SC2 rescan test, ratio to budget
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
