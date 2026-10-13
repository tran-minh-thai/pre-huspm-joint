# -*- coding: utf-8 -*-
# =============================================================================
#  make_figures.py  -  figures and tables from the campaign results
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
#   SC7 ablation           : static  proposed_full / proposed_no_IUB / proposed_no_IPS / Pre-HUSPM (one mine);
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
# The repository root is found from this file, so the tool works from any directory.
# Defaults: <repo>/results (the campaign trees) and <repo>/figures/campaign.
# HUSP_DATA_DIR and HUSP_OUT_DIR override them; HUSP_DATA_DIR must hold campaign trees
# (campaign/<SC>/<DATABASE>/<ARM>/...), not the flat directory of a single run.sh run.
REPO_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DATA_DIR = os.environ.get("HUSP_DATA_DIR") or os.path.join(REPO_DIR, "results")
OUT_DIR  = os.environ.get("HUSP_OUT_DIR") or os.path.join(REPO_DIR, "figures", "campaign")
os.makedirs(OUT_DIR, exist_ok=True)

TIMING_TREE = "campaign"
MEMORY_TREE = "campaign-mem"

# ---- Journal aesthetics -------------------------------------------------------
# No fills: series are told apart by colour, line style and hollow marker, bars by hatching.
# Lettering follows the artwork rules of Data Mining and Knowledge Discovery (Springer): Arial,
# a sans-serif face, consistently sized within 8-12 pt at final size, fonts embedded, lines at
# least 0.3 pt. The journal prints at 155 x 235 mm and sizes figures to 119 mm wide and at
# most 195 mm high, so every figure is drawn at 119 mm and a 9 pt font here is 9 pt in print.
# The manuscript includes them at its \linewidth, which only scales them up.
from matplotlib import font_manager
try:
    font_manager.findfont("Arial", fallback_to_default=False)
except Exception:
    sys.exit("Arial is not installed; refusing to draw with a substitute font.")

LINEWIDTH_IN = 119 / 25.4              # final figure width of the journal, in inches
MAX_HEIGHT_IN = 195 / 25.4             # final figure height limit of the journal
FONT_PT = 9
plt.rcParams.update({
    "font.family":        "Arial",
    "mathtext.fontset":   "custom",
    "mathtext.rm":        "Arial",
    "mathtext.it":        "Arial:italic",
    "mathtext.bf":        "Arial:bold",
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

# Sub- and superscripts (10^3, D_init, B_sz, r_l, IncUSP-Miner^+) are drawn at 0.7 of the base
# size by matplotlib, 6.3 pt here, below the journal's 8 pt. The factor is a module constant of
# the pinned matplotlib (3.11.2); raise it so that they print at 8 pt.
import matplotlib._mathtext as _mathtext
if not hasattr(_mathtext, "SHRINK_FACTOR"):
    sys.exit("matplotlib._mathtext.SHRINK_FACTOR is gone; sub- and superscripts would print below 8 pt.")
_mathtext.SHRINK_FACTOR = 8 / FONT_PT

# ---- Algorithms: order, labels, line styles, markers --------------------------
STATIC_ORDER = ["USpan", "HUSP-ULL", "HUSP-SP", "IncUSP-Miner+", "Pre-HUSPM", "Adaptive-Full"]
INC_ORDER    = ["IncUSP-Miner+", "Pre-HUSPM-SWUmax", "Pre-HUSPM-TSU", "Remine-from-scratch", "Adaptive-Full"]
PROPOSED = "Adaptive-Full"
PROPOSED_LABEL = "Pre-HUSPM-Joint"
ALGO_LABEL = {
    "USpan": "USpan", "HUSP-ULL": "HUSP-ULL", "HUSP-SP": "HUSP-SP",
    "IncUSP-Miner+": "IncUSP-Miner$^{+}$", "Pre-HUSPM": "Pre-HUSPM", "Pre-HUSPM-SWUmax": "Pre-HUSPM-SWUmax",
    "Pre-HUSPM-TSU": "Pre-HUSPM-TSU", "Remine-from-scratch": "Remine",
    "Adaptive-Full": PROPOSED_LABEL,
}
ALGO_LINESTYLE = {
    "USpan": (0, (1, 1.5)), "HUSP-ULL": (0, (4, 1.5)), "HUSP-SP": (0, (4, 1.5, 1, 1.5)),
    "IncUSP-Miner+": (0, (6, 2)), "Pre-HUSPM": (0, (2, 1.5)), "Pre-HUSPM-SWUmax": (0, (2, 1.5)), "Pre-HUSPM-TSU": (0, (6, 1.5, 1, 1.5, 1, 1.5)),
    "Remine-from-scratch": (0, (8, 2, 2, 2)), "Adaptive-Full": "-",
}
ALGO_MARKER = {
    "USpan": "o", "HUSP-ULL": "s", "HUSP-SP": "v", "IncUSP-Miner+": "^",
    "Pre-HUSPM": "D", "Pre-HUSPM-SWUmax": "D", "Pre-HUSPM-TSU": "P", "Remine-from-scratch": "x",
    "Adaptive-Full": "*",
}
ALGO_HATCH = {
    "USpan": "", "HUSP-ULL": "////", "HUSP-SP": "\\\\", "IncUSP-Miner+": "xxxx",
    "Pre-HUSPM": "....", "Pre-HUSPM-SWUmax": "....", "Pre-HUSPM-TSU": "----", "Remine-from-scratch": "++",
    "Adaptive-Full": "||||",
}


# Line colours: one fixed colour per method in every figure (Okabe-Ito, readable with colour
# vision deficiency). Lines and marker edges are coloured; nothing is filled, and the dash
# patterns and hollow markers stay, so a figure printed in black and white still reads.
ALGO_COLOR = {
    "Adaptive-Full": "#D55E00", "Pre-HUSPM": "#0072B2", "Pre-HUSPM-SWUmax": "#0072B2",
    "Pre-HUSPM-TSU": "#56B4E9", "Remine-from-scratch": "#000000", "IncUSP-Miner+": "#009E73",
    "HUSP-ULL": "#E69F00", "HUSP-SP": "#CC79A7", "USpan": "#7F7F7F",
}
# Series that are not methods (variants, stream cuts, databases) take colours in this order, in
# the order they are drawn; the first is the proposal's, kept by the full variant.
SERIES_COLORS = ["#D55E00", "#000000", "#009E73", "#CC79A7", "#E69F00", "#56B4E9", "#0072B2", "#7F7F7F"]
PANEL_COLS = 3          # every multi-panel figure: three columns of equal panels
PANEL_H = 1.9           # height of one row of panels, in inches


def series_style(k, linestyle="-", marker="o", label=None):
    """A series that is not a method: the k-th colour of SERIES_COLORS, hollow marker."""
    c = SERIES_COLORS[k % len(SERIES_COLORS)]
    return dict(color=c, linestyle=linestyle, marker=marker, markerfacecolor="none",
                markeredgecolor=c, markeredgewidth=0.7, lw=0.9, ms=4, label=label)


def line_style(algo, markevery=None):
    """Keyword arguments for one series: the method's colour, its own dash pattern, hollow marker."""
    c = ALGO_COLOR[algo]
    kw = dict(color=c, linestyle=ALGO_LINESTYLE[algo], marker=ALGO_MARKER[algo],
              markerfacecolor="none", markeredgecolor=c, markeredgewidth=0.7,
              lw=1.2 if algo == PROPOSED else 0.9, ms=6 if algo == PROPOSED else 4,
              label=ALGO_LABEL[algo])
    if markevery is not None:
        kw["markevery"] = markevery
    return kw


def bar_style(hatch, label, color="black"):
    """A bar: white, outlined and hatched in the series colour, never filled."""
    return dict(facecolor="white", edgecolor=color, hatch=hatch, linewidth=0.7, label=label)


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
    """name -> dict(d_init, default_uut, sweep, sl_ratio) parsed from src/common/Datasets.java.
    sl_ratio is the last argument of the eight-argument Entry, else DEFAULT_SL_MIN_RATIO."""
    path = os.path.join(REPO_DIR, "src", "common", "Datasets.java")
    if not os.path.exists(path):
        warnings.warn(f"{path} not found; default thresholds unavailable")
        return {}
    text = open(path, encoding="utf-8").read()
    pat = re.compile(r'new Entry\("([A-Za-z0-9_]+)",\s*(\d+),\s*([0-9.]+),\s*'
                     r'new double\[\]\{([^}]*)\}')
    default_rl = re.search(r'DEFAULT_SL_MIN_RATIO\s*=\s*([0-9.]+)', text)
    default_rl = float(default_rl.group(1)) if default_rl else 0.8
    out = {}
    for m in pat.finditer(text):
        name, dinit, uut, sweep = m.groups()
        rest = text[m.end():]
        body = rest[:rest.find("new Entry(")] if "new Entry(" in rest else rest
        rl = re.search(r'true,\s*(0\.\d+)\)', body)
        out[name] = dict(d_init=int(dinit), default_uut=float(uut),
                         sweep=[float(v) for v in sweep.split(",")],
                         sl_ratio=float(rl.group(1)) if rl else default_rl)
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


def load_mean(sc, tree=TIMING_TREE):
    """One row per measurement (database, algorithm, arm, operation, iteration) of a scenario,
    with its runtime the mean over the replicates that finished it: what every table, figure
    and number of the scenario reports, so that they agree with each other. Counters (patterns,
    explored nodes, rescans, pre-large set) are deterministic and are taken from the main run;
    the replicates agree with it on every row (checked when they arrive). Adds `runs`, the
    number of replicates averaged, and `runtime_ms_std`. A measurement no replicate finished
    keeps the main run's row and status."""
    df = load_scenario(sc, tree=tree, replicates=True)
    if df is None:
        return None
    key = ["dataset", "algorithm", "arm", "op_type", "iteration"]
    ok = df[df["finished"]]
    agg = ok.groupby(key)["runtime_ms"].agg(["mean", "std", "count", "min", "max"])
    main = df[df["replicate"] == "s0r0"].set_index(key).copy()
    main["runtime_ms"] = main["runtime_ms"].astype(float)
    idx = main.index.intersection(agg.index)
    main.loc[idx, "runtime_ms"] = agg.loc[idx, "mean"]
    main["runtime_ms_std"] = np.nan
    main.loc[idx, "runtime_ms_std"] = agg.loc[idx, "std"]
    main["runs"] = 0
    main.loc[idx, "runs"] = agg.loc[idx, "count"].astype(int)
    main["runtime_ms_min"] = main["runtime_ms"]
    main["runtime_ms_max"] = main["runtime_ms"]
    main.loc[idx, "runtime_ms_min"] = agg.loc[idx, "min"]
    main.loc[idx, "runtime_ms_max"] = agg.loc[idx, "max"]
    return main.reset_index()


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
               "Fail": ("1", "Failed"), "MemSkip": ("2", "Stopped by the candidate or time budget"),
               "Skip": ("3", "Skipped after a failed batch or a memory-guard stop")}


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


def save(fig, name, full_width=False):
    """Save a PDF cropped to its content. full_width keeps the whole figure width (crops only
    above and below), for a figure whose panels are centred: cropping its side margins would
    make it narrower than the others, and it would be scaled up, text included, on the page."""
    path = os.path.join(OUT_DIR, name)
    tb = fig.get_tightbbox(fig.canvas.get_renderer())
    if tb.height > MAX_HEIGHT_IN or tb.width > LINEWIDTH_IN + 0.05:
        raise ValueError(f"{name}: {tb.width * 25.4:.1f} x {tb.height * 25.4:.1f} mm exceeds the "
                         f"journal's {LINEWIDTH_IN * 25.4:.0f} x {MAX_HEIGHT_IN * 25.4:.0f} mm")
    bbox = "tight"
    if full_width:
        from matplotlib.transforms import Bbox
        bbox = Bbox([[0, tb.y0 - 0.02], [fig.get_figwidth(), tb.y1 + 0.02]])
    fig.savefig(path, format="pdf", bbox_inches=bbox,
                metadata={"Title": name, "Subject": f"pre-huspm-joint {SOURCE_COMMIT}",
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
                "MemSkip": "stopped by the candidate or time budget",
                "Skip": "skipped after a failed batch or a memory-guard stop"}


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


def _legend_entries(axes):
    """Every series once, in the order first drawn, then the outcome symbols."""
    handles, labels = [], []
    for ax in np.atleast_1d(axes).ravel():
        for h, l in zip(*ax.get_legend_handles_labels()):
            if l not in labels:
                handles.append(h); labels.append(l)
    outcome = {lbl for _, lbl in STATUS_MARK.values()}
    order = [i for i, l in enumerate(labels) if l not in outcome] + \
            [i for i, l in enumerate(labels) if l in outcome]
    return [handles[i] for i in order], [labels[i] for i in order]


def _data_points(ax):
    """Display coordinates of what the axis draws: line vertices with the segments between
    them sampled, marker positions, and the corners and interior of every bar."""
    from matplotlib.patches import Rectangle
    pts = []
    for ln in ax.get_lines():
        xy = ln.get_xydata()
        if len(xy) == 0:
            continue
        xy = ln.get_transform().transform(np.asarray(xy, float))
        xy = xy[np.isfinite(xy).all(axis=1)]
        pts.append(xy)
        if ln.get_linestyle() not in ("None", "none", "", " ") and len(xy) > 1:
            for a, b in zip(xy[:-1], xy[1:]):
                t = np.linspace(0, 1, 25)[:, None]
                pts.append(a + t * (b - a))
    for p in ax.patches:
        if isinstance(p, Rectangle) and p.get_visible() and p.get_height() != 0:
            bb = p.get_window_extent()
            xs = np.linspace(bb.x0, bb.x1, 6); ys = np.linspace(bb.y0, bb.y1, 12)
            pts.append(np.array([(x, y) for x in xs for y in ys]))
    return np.vstack(pts) if pts else np.empty((0, 2))


def _legend_clear(fig, ax, leg, pad=2.0):
    """True when the legend lies inside the axis and covers no drawn point (pad in pixels)."""
    fig.canvas.draw()
    lb = leg.get_window_extent()
    ab = ax.get_window_extent()
    if lb.x0 < ab.x0 or lb.x1 > ab.x1 or lb.y0 < ab.y0 or lb.y1 > ab.y1:
        return False
    pts = _data_points(ax)
    if len(pts) == 0:
        return True
    inside = ((pts[:, 0] >= lb.x0 - pad) & (pts[:, 0] <= lb.x1 + pad) &
              (pts[:, 1] >= lb.y0 - pad) & (pts[:, 1] <= lb.y1 + pad))
    return not inside.any()


LEGEND_LOCS = ["upper right", "upper left", "lower right", "lower left", "upper center",
               "lower center", "center right", "center left", "center"]


def place_legend(fig, axes, tag, spare=None, ncol=None):
    """One legend for the whole figure, placed where no data is drawn.

    spare: hidden grid cells; the legend goes there when given. Otherwise the panels are
    tried in turn, each at the positions of LEGEND_LOCS, at 9 pt then 8 pt, with one or more
    columns; the first placement inside a panel that covers no point, segment or bar is kept,
    as measured on the drawn figure. When none is clear the legend goes above the panels and
    the figure is named."""
    handles, labels = _legend_entries(axes)
    if not handles:
        return
    kw = dict(frameon=False, handlelength=2.6, columnspacing=0.8, handletextpad=0.4,
              borderaxespad=0.3)
    # Inside a panel the line sample is shorter: a third of the column is about 74 pt wide.
    kw_in = dict(kw, handlelength=1.5, columnspacing=0.6, handletextpad=0.3, borderaxespad=0.2)
    if spare:
        # The empty grid cells: the legend must stay inside them, or the figure grows wider
        # than the column and is scaled down, text included, on the page.
        fig.canvas.draw()
        bb = spare[0].get_position()
        for a in spare[1:]:
            bb = bb.union([bb, a.get_position()])
        region = bb.transformed(fig.transFigure)
        for nc in range(len(labels), 0, -1):     # fewest rows that fit the cells
            leg = fig.legend(handles, labels, loc="center", bbox_to_anchor=(bb.x0, bb.y0, bb.width, bb.height),
                             bbox_transform=fig.transFigure, ncol=nc, **kw_in)
            fig.canvas.draw()
            lb = leg.get_window_extent()
            if (lb.x0 >= region.x0 and lb.x1 <= region.x1 and lb.y0 >= region.y0 and lb.y1 <= region.y1
                    and lb.x1 <= fig.bbox.x1):
                return
            leg.remove()
        print(f"[legend] {tag}: the legend does not fit the empty cells; legend above the panels")
        shared_legend(fig, axes, ncol=ncol)
        return
    axes = list(np.atleast_1d(axes).ravel())
    ncols = list(range(len(labels), 0, -1))       # fewest rows first
    for fs in (FONT_PT, 8):
        for ax in axes[::-1]:
            for loc in LEGEND_LOCS:
                for nc in ncols:
                    leg = ax.legend(handles, labels, loc=loc, ncol=nc, fontsize=fs, **kw_in)
                    if _legend_clear(fig, ax, leg):
                        return
                    leg.remove()
    print(f"[legend] {tag}: no clear area inside a panel; legend above the panels")
    shared_legend(fig, axes, ncol=ncol)


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
    if not handles:
        return
    # As few rows as fit the figure width.
    for nc in range(len(labels), 0, -1):
        leg = fig.legend(handles, labels, loc="lower center", ncol=nc,
                         frameon=False, bbox_to_anchor=(0.5, y), handlelength=2.6, columnspacing=0.8,
                         handletextpad=0.4)
        fig.canvas.draw()
        if leg.get_window_extent().width <= fig.bbox.width or nc == 1:
            return
        leg.remove()


def panel_xlabel(ax, i, name, xlabel):
    """No titles inside a figure: the panel is named under its axis, as (a) NAME."""
    ax.set_xlabel(f"{xlabel}\n({chr(ord('a') + i)}) {name}")


def panel_grid(n, ncols=PANEL_COLS, h=PANEL_H, width=LINEWIDTH_IN):
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
    df = load_mean(sc)
    if df is None:
        return
    df = parse_su(df)
    datasets = [d for d in (datasets or FIG_DATASETS) if d in set(df["dataset"])]
    spec = {"runtime_ms": ("Runtime (s)", ms_to_s),
            "explored_nodes": ("Explored nodes", lambda x: np.asarray(x, float))}
    ncols = PANEL_COLS
    for metric in metrics:
        ylab, conv = spec[metric]
        fig, axes = panel_grid(len(datasets))
        for i, (ax, ds) in enumerate(zip(axes, datasets)):
            sub = df[df["dataset"] == ds]
            sus = sorted(sub["su"].dropna().unique())
            level = {v: k for k, v in enumerate(sus)}
            for algo in STATIC_ORDER:
                a = sub[sub["algorithm"] == algo].sort_values("su")
                if a.empty:
                    continue
                ok = a[a["finished"]]
                ys = conv(ok[metric])
                if metric == "explored_nodes" and algo == PROPOSED:
                    # Counted as the baselines count: a node only when it passes the bound. The
                    # proposal also counts the root items stopped at once by its node bound.
                    import scenario_tables
                    stop = scenario_tables.root_stopped()
                    keys = [(sc, ds, round(float(v), 9), "once") for v in ok["su"]]
                    missing = [k for k in keys if k not in stop]
                    if missing:
                        sys.exit(f"{tag}_{sc}_{metric}: no root count for {missing}; "
                                 "run analysis/root_items.py")
                    ys = ys - np.array([stop[k] for k in keys], float)
                # the four levels are evenly spaced on the axis and labelled with their
                # values: they are not evenly spaced in value, and their labels collide
                ax.plot([level[v] for v in ok["su"]], ys, **line_style(algo))
                for _, r in a[~a["finished"]].iterrows():
                    annotate_fail(ax, level[r["su"]], r["status"])
            ax.set_xticks(range(len(sus)))
            # UUT in percent, three significant digits (trailing zeros kept), flat. Where the
            # four labels overlap at 9 pt (measured on the drawn figure), only the two ends are
            # labelled and the figure is named.
            ax.set_xticklabels([f"{100 * v:#.3g}" for v in sus])
            ax.set_xlim(-0.3, len(sus) - 0.7)
            ax.minorticks_off()
            panel_xlabel(ax, i, ds, "UUT (%)")
            safe_log(ax)
            if i % ncols == 0:
                ax.set_ylabel(ylab)
        fig.tight_layout(w_pad=0.6)
        # Measured after the layout: where the labels of a panel overlap, keep the two ends.
        fig.canvas.draw()
        for i, (ax, ds) in enumerate(zip(axes, datasets)):
            texts = [t.get_text() for t in ax.get_xticklabels()]
            ex = [t.get_window_extent() for t in ax.get_xticklabels() if t.get_text()]
            if any(b.x0 - a.x1 < 1.0 for a, b in zip(ex, ex[1:])):
                ax.set_xticklabels([t if k in (0, len(texts) - 1) else "" for k, t in enumerate(texts)])
                print(f"[labels] {tag}_{sc}_{metric} ({chr(ord('a') + i)}) {ds}: the UUT labels overlap "
                      "at 9 pt; only the lowest and highest are labelled")
        place_legend(fig, axes, f"{tag}_{sc}_{metric}", ncol=3)
        save(fig, f"{tag}_{sc}_{metric}.pdf")


# %% ------------------------------------------------------------------ Cell 4
#    INCREMENTAL (SC2): cumulative runtime along the stream, one panel per database
def figset_incremental(tag="fig_incremental_cumulative", datasets=None):
    """Cumulative runtime over the 100 increments, the initial mine excluded, from the mean of
    the replicates where an arm has them. A curve that stops early is an arm that did not
    finish (out of memory, or stopped by the runner's memory guard); its end is marked."""
    df = load_mean("SC2")
    if df is None:
        return
    datasets = [d for d in (datasets or SC2_DATASETS) if d in set(df["dataset"])]
    fig, axes = panel_grid(len(datasets))
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
        ax.set_xticks([0, 50, 100])
        panel_xlabel(ax, i, ds, "Increment")
        safe_log(ax)
        if i % PANEL_COLS == 0:
            ax.set_ylabel("Cumulative runtime (s)")
    fig.tight_layout(w_pad=0.6)
    place_legend(fig, axes, tag, spare=[a for a in fig.axes if not a.get_visible()], ncol=2)
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
    and a mismatch is reported rather than smoothed over.

    alpha = max_i A_i / U+ at each decision, read as 1 / iau_tightness (iau_tightness is
    U+ / max_i A_i at the decision): its median, minimum and maximum, and the increments with
    alpha <= UUT, which the joint rule never rescans. alpha_stream is alpha over the whole
    insertion stream, from results/invariant-insertion-alpha/alpha_stream.csv, which
    analysis/insertion_alpha.py computes from the data files; alpha_check compares the
    per-increment alpha and decisions with that computation (alpha_batches.csv)."""
    inv = os.path.join(DATA_DIR, "invariant-insertion-alpha")
    stream = {}
    factor = {}
    batches = {}
    if os.path.exists(os.path.join(inv, "alpha_stream.csv")):
        for _, r in pd.read_csv(os.path.join(inv, "alpha_stream.csv")).iterrows():
            stream[r["dataset"]] = float(r["alpha_stream"])
            factor[r["dataset"]] = float(r["joint_over_tsu"])
        for _, r in pd.read_csv(os.path.join(inv, "alpha_batches.csv")).iterrows():
            batches[(r["dataset"], int(r["increment"]))] = (float(r["alpha"]), str(r["rescan"]).lower() == "true")
    else:
        print(f"[warn] {inv} is missing: alpha_stream and alpha_check are left empty; "
              "run python3 analysis/insertion_alpha.py")
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
        tight = ok["iau_tightness"].to_numpy(float)
        alpha = np.where(tight > 0, 1.0 / np.where(tight > 0, tight, 1.0), np.nan)
        uut = default_su(ds)
        if batches:
            n_cmp = n_bad = 0
            for it, al, trig in zip(ok["iteration"].astype(int), alpha,
                                    ok["cub_triggered"].astype(str).str.lower().eq("true")):
                ref = batches.get((ds, it))
                if ref is None:
                    continue
                n_cmp += 1
                if abs(ref[0] - al) > 1e-9 * max(1.0, abs(ref[0])) or ref[1] != bool(trig):
                    n_bad += 1
            alpha_check = f"{n_bad} of {n_cmp} differ" if n_cmp else "0 compared"
        else:
            alpha_check = "not computed"
        rows.append(dict(dataset=ds, rule="/".join(rules), increments=len(ok),
                         ratio_median=float(np.median(ratio)), ratio_min=float(np.min(ratio)),
                         ratio_max=float(np.max(ratio)), within_budget=len(ok) - above,
                         rescans=rescans,
                         alpha_median=float(np.nanmedian(alpha)), alpha_min=float(np.nanmin(alpha)),
                         alpha_max=float(np.nanmax(alpha)), uut=uut,
                         increments_alpha_le_uut=int(np.sum(alpha <= uut)),
                         # the gain of the corollary at the median alpha of the rescan decisions,
                         # each alpha taken over the insertions since the last rescan
                         gain_at_decisions=float((1 - uut) / (np.nanmedian(alpha) - uut))
                         if np.nanmedian(alpha) > uut else np.nan,
                         alpha_stream=stream.get(ds, np.nan), joint_over_tsu=factor.get(ds, np.nan),
                         alpha_check=alpha_check,
                         sl_min_eff_max=float(ok["sl_min_eff"].max()),
                         **baseline_rescans(df, ds),
                         check="ok" if above == rescans == triggered
                               else f"MISMATCH above={above} triggered={triggered} rescans={rescans}"))
    return pd.DataFrame(rows)


def baseline_rescans(df, ds):
    """Rescans of the two Pre-HUSPM arms on one database, and how many of the 100 increments
    each completed; a count over an incomplete stream is not comparable and is left empty."""
    out = {}
    for arm, key in (("Pre-HUSPM-TSU", "tsu"), ("Pre-HUSPM-SWUmax", "swumax")):
        g = df[(df["dataset"] == ds) & (df["algorithm"] == arm) & is_inc(df)]
        ok = g[g["finished"]]
        done = len(ok)
        out[f"increments_{key}"] = done
        out[f"rescans_{key}"] = int(ok["rescan_count"].iloc[-1]) if done == 100 else np.nan
    return out


# Registered before the campaign ran (commit c24fcd3): from the data alone, the joint
# rule's budget is (1 - UUT) / (alpha - UUT) times that of a test on the total inserted utility,
# with alpha over the insertion stream. So the gap in rescans between Pre-HUSPM-TSU and the
# proposal should be largest on the two databases with the lowest alpha and smallest on the
# three with the highest.
PREDICTED_LARGEST = ("C8T1S5I8N5K", "TAFENG")
PREDICTED_SMALLEST = ("SIGN", "LEVIATHAN", "KOSARAK")


def rescan_hypothesis(tab):
    """The registered prediction read off the operating-region table.

    gap = rescans of Pre-HUSPM-TSU - rescans of the proposal, on databases where both
    completed the 100 increments. The prediction holds when every database in
    PREDICTED_LARGEST has a larger gap than every other database, and every database in
    PREDICTED_SMALLEST a smaller gap than every database outside it. Ties at either
    boundary do not count as holding. A database the prediction names without a complete
    pair makes it undecidable, and the verdict names it."""
    t = tab.set_index("dataset")
    gap = {d: t.loc[d, "rescans_tsu"] - t.loc[d, "rescans"] for d in t.index
           if pd.notna(t.loc[d, "rescans_tsu"]) and t.loc[d, "increments"] == 100}
    missing = [d for d in PREDICTED_LARGEST + PREDICTED_SMALLEST if d not in gap]
    if missing:
        return dict(verdict="undecidable", gap=gap, missing=missing,
                    reason="no complete Pre-HUSPM-TSU and proposal pair on " + ", ".join(missing))
    others_l = [gap[d] for d in gap if d not in PREDICTED_LARGEST]
    others_s = [gap[d] for d in gap if d not in PREDICTED_SMALLEST]
    large_ok = all(gap[d] > max(others_l) for d in PREDICTED_LARGEST) if others_l else True
    small_ok = all(gap[d] < min(others_s) for d in PREDICTED_SMALLEST) if others_s else True
    return dict(verdict="holds" if large_ok and small_ok else "refuted", gap=gap, missing=[],
                reason=f"largest-gap part {'holds' if large_ok else 'fails'}, "
                       f"smallest-gap part {'holds' if small_ok else 'fails'}")


def rescan_order_check(tab):
    """On an insertion-only stream with the same UUT and r_l, the joint rule never rescans more
    often than the SWUmax test (proved in the paper; checked here, not predicted). Returns
    (databases compared, databases where the proposal rescans more, databases where the
    proposal's floor rose above its configured r_l, which voids the comparison there)."""
    t = tab.set_index("dataset")
    raised = [d for d in t.index if t.loc[d, "sl_min_eff_max"] > DATASETS.get(d, {}).get("sl_ratio", 0.8) + 1e-12]
    pairs = [d for d in t.index if pd.notna(t.loc[d, "rescans_swumax"]) and t.loc[d, "increments"] == 100
             and d not in raised]
    more = [d for d in pairs if t.loc[d, "rescans"] > t.loc[d, "rescans_swumax"]]
    return pairs, more, raised


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
    pairs, more, raised = rescan_order_check(tab)
    print(f"      rescans of the proposal <= Pre-HUSPM-SWUmax: {len(pairs) - len(more)} of {len(pairs)} databases"
          + (f"; MORE on {', '.join(more)}" if more else "")
          + (f"; floor raised above r_l on {', '.join(raised)}, not compared" if raised else ""))
    h = rescan_hypothesis(tab)
    print(f"      registered rescan prediction: {h['verdict']} ({h['reason']}); "
          f"gaps TSU - proposal: {h['gap']}")


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
    variants = [("proposed_C5_on", "Joint rescan rule", ""),
                ("proposed_C5_off", "Rescan at every increment", "////")]
    # Two panels of the common size (a third of the width each), centred.
    fig = plt.figure(figsize=(LINEWIDTH_IN, PANEL_H))
    gs = fig.add_gridspec(1, 2 * PANEL_COLS)
    axes = [fig.add_subplot(gs[0, 1:3]), fig.add_subplot(gs[0, 3:5])]
    for i, (ax, (metric, ylab)) in enumerate(zip(axes, [("runtime_ms", "Runtime (s)"),
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
            ax.bar(x + (j - 0.5) * w, np.nan_to_num(vals), width=w, **bar_style(hatch, lbl, SERIES_COLORS[j]))
        ax.set_xticks(x); ax.set_xticklabels(datasets, rotation=30, ha="right", rotation_mode="anchor")
        ax.set_xlabel(f"({chr(ord('a') + i)}) " + ("Runtime over 100 increments" if metric == "runtime_ms" else "Rescans"))
        ax.set_ylabel(ylab)
    fig.tight_layout(w_pad=1.0)
    place_legend(fig, axes, tag, ncol=2)
    save(fig, f"{tag}.pdf", full_width=True)


def figset_block_size(tag="fig_block_size"):
    """SC7 block-size arms on the SC2 stream, three panels against B_sz: sequences read on the
    direct updates, the time of the direct updates, and the time of the whole stream. One line
    per database; the default block size is marked."""
    import scenario_tables
    bs = scenario_tables.sc7_block_size()
    if bs.empty:
        print(f"[not drawn] {tag}.pdf: no proposed_bsz_<n> arm in results/campaign/SC7")
        path = os.path.join(OUT_DIR, f"{tag}.pdf")
        if os.path.exists(path):
            os.remove(path)
        return
    default = int(bs.loc[bs["default"], "block_size"].iloc[0])
    styles = {"SIGN": dict(linestyle="-", marker="o"), "C8T1S5I8N5K": dict(linestyle=(0, (4, 1.5)), marker="s")}
    panels = [("sequences_read", "Sequences read", "Sequences read", 1.0),
              ("direct_ms", "Direct updates", "Time (ms)", 1.0),
              ("total_s", "Whole stream", "Time (s)", 1.0)]
    # The block sizes are a few discrete settings, drawn at even spacing in increasing order.
    ticks = sorted(set(bs["block_size"]))
    pos = {b: k for k, b in enumerate(ticks)}
    fig, axes = panel_grid(len(panels))
    for i, (ax, (col, name, ylab, scale)) in enumerate(zip(axes, panels)):
        for k, d in enumerate([x for x in ALL_DATASETS if x in set(bs["dataset"])]):
            g = bs[bs["dataset"] == d].sort_values("block_size")
            st = styles.get(d, dict(linestyle=":", marker="^"))
            ax.plot([pos[b] for b in g["block_size"]], g[col] * scale, **series_style(k, label=d, **st))
        ax.axvline(pos[default], color="0.5", lw=0.6, linestyle=":")
        # A tick at every block size measured (1, 5, 10, 50, 100, 200); labels on 1, 10 and 100
        # only, flat: six labels at 9 pt do not fit in a third of the column.
        shown = {1, 10, 100}
        ax.set_xticks(range(len(ticks)))
        ax.set_xticklabels([str(t) if t in shown else "" for t in ticks])
        if col == "sequences_read":
            ax.set_yscale("log")
        else:
            ax.set_ylim(bottom=0)
        ax.set_ylabel(ylab)
        panel_xlabel(ax, i, name, "$B_{\\mathrm{sz}}$")
    fig.tight_layout(w_pad=0.8)
    place_legend(fig, axes, tag, ncol=2)
    save(fig, f"{tag}.pdf")


# Variant names as the SC7 table prints them; the CSV and the number keys keep the short names.
SC7_PRINTED = {"-IUB": "No node check", "-IPS": "No item pruning"}


def export_ablation_table():
    """SC7 as a table. Static: full, IUB off (C3), item removal off (IPS) and Pre-HUSPM on
    one mine of the whole database - runtime, explored nodes, IUB cuts. Incremental: C5 and C4 arms
    summed over the 100 increments - runtime, rescans, block reads."""
    df = load_mean("SC7")
    if df is None:
        return
    static = {"proposed_full": "Full", "proposed_no_IUB": "-IUB", "proposed_no_IPS": "-IPS",
              "Pre-HUSPM": "Pre-HUSPM"}
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
                             runtime_s_std=r["runtime_ms_std"] / 1000 if r["finished"] else np.nan,
                             runs=int(r["runs"]),
                             runtime_s_min=r["runtime_ms_min"] / 1000 if r["finished"] else np.nan,
                             runtime_s_max=r["runtime_ms_max"] / 1000 if r["finished"] else np.nan,
                             explored=r["explored_nodes"] if r["finished"] else np.nan,
                             iub_cuts=r["pruned_iub"] if r["finished"] else np.nan))
    tab = pd.DataFrame(rows)
    # explored_bound: as the baselines count, a node only when it passes the bound. The proposal's
    # arms also count the root items stopped at once (analysis/root_items.py); Pre-HUSPM counts
    # this way already.
    import scenario_tables
    stop = scenario_tables.root_stopped()
    ips_of = {"Full": "once", "-IUB": "once", "-IPS": "off"}
    def bound(r):
        if pd.isna(r["explored"]) or r["variant"] not in ips_of:
            return r["explored"]
        k = ("SC7", r["dataset"], round(float(DATASETS[r["dataset"]]["default_uut"]), 9), ips_of[r["variant"]])
        if k not in stop:
            sys.exit(f"table_SC7_static: no root count for {k}; run analysis/root_items.py")
        return r["explored"] - stop[k]
    tab["explored_bound"] = tab.apply(bound, axis=1) if len(tab) else []
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
            # blocks_read counts sequences read on a direct update and is carried unchanged
            # on a row that rescanned, so only the rows without a rescan are summed.
            direct = ok[ok["cub_triggered"].astype(str).str.lower() == "false"]
            rows.append(dict(dataset=ds, variant=label, increments_done=len(ok),
                             runtime_s=ms_to_s(ok["runtime_ms"]).sum(),
                             rescans=ok["rescan_count"].iloc[-1] if len(ok) else np.nan,
                             sequences_read=direct["blocks_read"].sum(),
                             mean_prelarge=ok["prelarge_count"].mean(),
                             final_patterns=ok["total_patterns_found"].iloc[-1] if len(ok) else np.nan))
    write_csv(pd.DataFrame(rows), os.path.join(OUT_DIR, "table_SC7_incremental.csv"))

    lines = ["\\begin{table}[!htbp]", "\\centering",
             "\\caption{Ablation of the miner on the whole database; runtime as mean and standard deviation "
             "of three runs; bold, per database, the lowest runtime and every runtime whose three runs overlap "
             "its runs, and the fewest explored nodes}",
             "\\label{tab:sc7_ablation}",
             "\\begin{tabular*}{\\textwidth}{@{\\extracolsep\\fill}llrr}", "\\toprule",
             "Database & Variant & Runtime (s) & Explored \\\\", "\\midrule"]
    # the lowest runtime and the fewest explored nodes of each database in bold, compared as
    # printed; a variant that did not finish takes no part
    bold = {}
    for ds, g in tab.groupby("dataset", sort=False):
        rt = [f"{v:.2f}" if pd.notna(v) else None for v in g["runtime_s"]]
        rg = [(a, b, n) if pd.notna(a) else None for a, b, n in zip(g["runtime_s_min"], g["runtime_s_max"], g["runs"])]
        ex = [f"{int(v)}" if pd.notna(v) else None for v in g["explored_bound"]]
        for i, a, b in zip(g.index, lowest(rt, rg), lowest(ex)):
            bold[i] = (a, b)
    prev = None
    for i, r in tab.iterrows():
        ds = r["dataset"] if r["dataset"] != prev else ""
        prev = r["dataset"]
        rt = (pm(dict(n=r["runs"], mean=r["runtime_s"], std=r["runtime_s_std"]), digits=2, bold=bold[i][0])
              if pd.notna(r["runtime_s"]) else r["status"])
        ex = f"{int(r['explored_bound']):,}" if pd.notna(r["explored_bound"]) else r["status"]
        ex = f"\\textbf{{{ex}}}" if bold[i][1] else ex
        lines.append(f"{ds} & {SC7_PRINTED.get(r['variant'], r['variant'])} & {rt} & {ex} \\\\")
    lines += ["\\bottomrule", "\\end{tabular*}", "\\end{table}", ""]
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
    pair = [("Pre-HUSPM", "////", "Pre-HUSPM", ALGO_COLOR["Pre-HUSPM"]),
            ("Adaptive", "", PROPOSED_LABEL, ALGO_COLOR[PROPOSED])]
    spec = {"prelarge_count": ("Pre-large set size", lambda x: np.asarray(x, float)),
            "runtime_ms": ("Runtime (s)", ms_to_s)}
    for metric in metrics:
        ylab, conv = spec[metric]
        fig, axes = panel_grid(len(datasets))
        for i, (ax, ds) in enumerate(zip(axes, datasets)):
            sub = df[df["dataset"] == ds]
            sls = sorted(sub["sl"].dropna().unique())
            x = np.arange(len(sls)); w = 0.38
            for j, (base, hatch, lbl, color) in enumerate(pair):
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
                ax.bar(pos, np.nan_to_num(vals), width=w, **bar_style(hatch, lbl, color))
                for pp, st in zip(pos, stats):
                    if st:
                        annotate_fail(ax, pp, st)
            ax.set_xticks(x); ax.set_xticklabels([f"{v:g}" for v in sls])
            panel_xlabel(ax, i, ds, r"$r_\ell$")
            safe_log(ax)
            if i == 0:
                ax.set_ylabel(ylab)
        fig.tight_layout(w_pad=0.6)
        place_legend(fig, axes, f"{tag}_{metric}", ncol=2)
        save(fig, f"{tag}_{metric}.pdf")


STRAT_STYLE = {"EVEN": ("-", "o"), "INCREASING": ((0, (4, 1.5)), "s"),
               "DECREASING": ((0, (1, 1.5)), "^"), "BURST": ((0, (6, 1.5, 1, 1.5)), "D")}


def figset_batch_ratio(tag="fig_batch_ratio"):
    """SC5: the proposal's time over remine-from-scratch's, summed over the increments,
    against K, one line per strategy. Below one, the proposal is faster."""
    df = load_mean("SC5")
    if df is None:
        return
    df = parse_batch(df)
    inc = df[(df["phase"] == "INC") & df["finished"]]
    datasets = [d for d in FIG_DATASETS if d in set(df["dataset"])]
    fig, axes = panel_grid(len(datasets))
    for i, (ax, ds) in enumerate(zip(axes, datasets)):
        for si, strat in enumerate(STRATS):
            ys = []
            for k in KS:
                cell = inc[(inc["dataset"] == ds) & (inc["K"] == k) & (inc["strat"] == strat)]
                p = cell[cell["algorithm"] == PROPOSED]["runtime_ms"].sum()
                q = cell[cell["algorithm"] == "Remine-from-scratch"]["runtime_ms"].sum()
                ys.append(100.0 * p / q if p > 0 and q > 0 else np.nan)
            ls, mk = STRAT_STYLE[strat]
            ax.plot(KS, ys, **series_style(si, linestyle=ls, marker=mk, label=strat.capitalize()))
        ax.axhline(100.0, color="black", lw=0.5)
        ax.set_xticks(KS)
        panel_xlabel(ax, i, ds, "Increments $K$")
        if i == 0:
            ax.set_ylabel("Time relative to Remine (%)")
    fig.tight_layout(w_pad=0.6)
    place_legend(fig, axes, tag, ncol=2)
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
        ax.bar(pos, np.nan_to_num(vals), width=w, **bar_style(ALGO_HATCH[algo], ALGO_LABEL[algo], ALGO_COLOR[algo]))
        for pp, st in zip(pos, stats):
            if st:
                annotate_fail(ax, pp, st)
    ax.set_xticks(x); ax.set_xticklabels(datasets, rotation=30, ha="right")
    ax.set_ylabel("Live set (MB)")
    safe_log(ax)
    fig.tight_layout()
    place_legend(fig, [ax], tag, ncol=3)
    save(fig, f"{tag}.pdf")


def figset_memory_incremental(tag="fig_memory_incremental", datasets=None):
    """SC2 memory: the state each algorithm holds after every increment
    (mem_retained_mb against a baseline fixed before the arm's algorithm exists)."""
    df = load_scenario("SC2", tree=MEMORY_TREE)
    if df is None:
        return
    datasets = [d for d in (datasets or SC2_DATASETS) if d in set(df["dataset"])]
    fig, axes = panel_grid(len(datasets))
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
        ax.set_xticks([0, 50, 100])
        panel_xlabel(ax, i, ds, "Increment (0 = $D_{\\mathrm{init}}$)")
        safe_log(ax)
        if i % PANEL_COLS == 0:
            ax.set_ylabel("State held (MB)")
    fig.tight_layout(w_pad=0.6)
    place_legend(fig, axes, tag, spare=[a for a in fig.axes if not a.get_visible()], ncol=2)
    absent_footnote(fig, absent)
    save(fig, f"{tag}.pdf")


# %% ------------------------------------------------------------------ Cell 8
#    TABLES: SC1 at the default threshold, status summary
def export_summary_tables():
    """SC1 at each database's default threshold: runtime, explored nodes, patterns
    and status per algorithm, the proposal's speedup over each, and live set from
    the memory campaign. CSV + LaTeX."""
    df = load_mean("SC1")
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
    tab = memory_ratios()
    if tab is None:
        return
    path = os.path.join(OUT_DIR, "table_SC1_memory_ratios.csv")
    write_csv(tab, path)
    print(f"[saved] {path}")


def memory_ratios():
    """SC1 live set, each baseline over the proposal, over every (database, threshold)
    cell where both completed: geometric mean, cells used, cells left out and why (the
    outcome of whichever did not finish), and the cells where the baseline holds less.
    Leaving out a baseline's out-of-memory cell lowers the ratio, so it does not favour
    the proposal. Two scopes: all four thresholds, and the default threshold alone."""
    df = load_scenario("SC1", tree=MEMORY_TREE)
    if df is None:
        return None
    df = parse_su(df)
    # the second measurement of the live set, where there is one: (database, threshold,
    # algorithm) -> its value
    again = {}
    for rd in sorted(glob.glob(os.path.join(DATA_DIR, "campaign-mem-replicates", "s*r*"))):
        r = load_scenario("SC1", tree=os.path.relpath(rd, DATA_DIR))
        if r is None:
            continue
        for x in parse_su(r).itertuples():
            if x.finished:
                again.setdefault((x.dataset, round(x.su, 9), x.algorithm), []).append(float(x.mem_live_mb))

    def runs(row):
        return [float(row["mem_live_mb"])] + again.get((row["dataset"], round(row["su"], 9), row["algorithm"]), [])

    rows = []
    for base in [a for a in STATIC_ORDER if a != PROPOSED]:
        for scope in ("all four thresholds", "default threshold"):
            ratios, left_out, cells = [], [], 0
            # each cell used: the baseline below or above the proposal, and whether every
            # measurement of one side lies beyond every measurement of the other
            state = {"less separated": [], "less overlapping": [], "more separated": [],
                     "more overlapping": [], "equal": [], "less one run": [], "more one run": []}
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
                    if float(b["mem_live_mb"]) <= 0 or float(p["mem_live_mb"]) <= 0:
                        # A live set read as 0 MB is below the 1 MB resolution of the column, or
                        # a run that ended before its first sample; it has no ratio.
                        who = base if float(b["mem_live_mb"]) <= 0 else PROPOSED
                        left_out.append(f"{ds}@{su:g}:{who} 0 MB"); continue
                    ratios.append(float(b["mem_live_mb"]) / float(p["mem_live_mb"]))
                    rb, rp = runs(b), runs(p)
                    side = ("less" if ratios[-1] < 1 else "more" if ratios[-1] > 1 else "equal")
                    if side == "equal":
                        how = "equal"
                    elif len(rb) < 2 or len(rp) < 2:
                        how = f"{side} one run"
                    elif max(rb) < min(rp) or min(rb) > max(rp):
                        how = f"{side} separated"
                    else:
                        how = f"{side} overlapping"
                    state[how].append(f"{ds}@{su:g}")
            r = np.asarray(ratios, float)
            rows.append(dict(baseline=base, scope=scope, cells=cells, cells_used=len(r),
                             geomean_ratio=float(np.exp(np.mean(np.log(r)))) if len(r) else np.nan,
                             baseline_holds_less=int(np.sum(r < 1)),
                             **{k.replace(" ", "_"): len(v) for k, v in state.items()},
                             overlapping_cells="; ".join(state["less overlapping"] + state["more overlapping"]),
                             left_out="; ".join(left_out)))
    return pd.DataFrame(rows)


def spread(values):
    """n, mean, sample standard deviation (NaN for one value), min, max."""
    v = np.asarray(values, float)
    n = len(v)
    return dict(n=n, mean=float(np.mean(v)) if n else np.nan,
                std=float(np.std(v, ddof=1)) if n > 1 else np.nan,
                min=float(np.min(v)) if n else np.nan, max=float(np.max(v)) if n else np.nan)


def pm_digits(mean):
    """Decimals for a mean: none from 100, one from 10, two below."""
    return 0 if mean >= 100 else (1 if mean >= 10 else 2)


def lowest(printed, ranges=None):
    """Which of a row's printed values are its lowest, compared as printed: two values that
    print alike are tied and both marked. None stands for a cell that takes no part (a run
    that did not finish, a missing cell). With ranges, one (min, max, runs) per cell, a cell
    measured at least twice whose runs overlap those of a lowest cell measured at least
    twice is a tie and is marked too: a difference within the spread of the runs is not a
    win."""
    vals = [float(v.replace(",", "")) if v is not None else None for v in printed]
    finite = [v for v in vals if v is not None]
    best = min(finite) if finite else None
    mark = [v is not None and v == best for v in vals]
    if ranges:
        heads = [ranges[i] for i, m in enumerate(mark) if m and ranges[i] and ranges[i][2] >= 2]
        for i, v in enumerate(vals):
            r = ranges[i]
            if v is None or mark[i] or not r or r[2] < 2:
                continue
            mark[i] = any(r[0] <= h[1] and h[0] <= r[1] for h in heads)
    return mark


def pm(st, digits=None, bold=False, inline=True):
    """'mean +- std' for LaTeX, or the mean alone when there is one replicate. Decimals follow
    the size of the mean - none from 100, one from 10, two below. bold sets the mean in bold,
    for the lowest value of a row. inline puts the deviation after the mean; otherwise it goes
    below it in a smaller size, for a table whose columns would not fit side by side."""
    if not np.isfinite(st["mean"]):
        return "--"
    if digits is None:
        digits = pm_digits(st["mean"])
    if st["n"] > 1 and np.isfinite(st["std"]):
        # a deviation below the printed resolution reads as no spread at all; say so. The
        # "<" goes in math mode: in the text font of the class it prints as an inverted "!"
        dev = (f"{st['std']:.{digits}f}" if round(st["std"], digits) > 0 or st["std"] == 0
               else f"$<${10 ** -digits:.{digits}f}")
        mean = f"{st['mean']:,.{digits}f}"
        mean = f"\\textbf{{{mean}}}" if bold else mean
        if inline:
            return f"{mean} $\\pm$ {dev}"
        return (f"\\begin{{tabular}}[t]{{@{{}}r@{{}}}}{mean}\\\\"
                f"{{\\scriptsize $\\pm${dev}}}\\end{{tabular}}")
    mean = f"{st['mean']:,.{digits}f}"
    return f"\\textbf{{{mean}}}" if bold else mean


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
        lines = ["\\begin{table}[!htbp]", "\\centering",
                 "\\caption{Runtime (s) of SC1 at the default threshold}",
                 "\\label{tab:sc1_runtime}",
                 "\\begin{tabular*}{\\textwidth}{@{\\extracolsep\\fill}l" + "r" * len(cols) + "}", "\\toprule",
                 "Database & " + " & ".join(stacked_header(ALGO_LABEL[c]) for c in cols) + " \\\\",
                 "\\midrule"]
        for ds in [d for d in ALL_DATASETS if d in set(tab["dataset"])]:
            stats = {}
            for c in cols:
                r = tab[(tab["dataset"] == ds) & (tab["algorithm"] == c)]
                if not r.empty and r.iloc[0]["n"] > 0:
                    r = r.iloc[0]
                    stats[c] = dict(n=r["n"], mean=r["runtime_s_mean"], std=r["runtime_s_std"],
                                    min=r["runtime_s_min"], max=r["runtime_s_max"])
            # the lowest mean of the row in bold, compared as printed; a run that did not
            # finish takes no part
            low = dict(zip(cols, lowest([f"{stats[c]['mean']:.{pm_digits(stats[c]['mean'])}f}"
                                         if c in stats else None for c in cols],
                                        [(stats[c]["min"], stats[c]["max"], stats[c]["n"]) if c in stats else None
                                         for c in cols])))
            cells = []
            for c in cols:
                r = tab[(tab["dataset"] == ds) & (tab["algorithm"] == c)]
                if r.empty:
                    cells.append("--"); continue
                # six algorithms side by side do not fit with the deviation inline
                cells.append(pm(stats[c], bold=low[c], inline=False) if c in stats else r.iloc[0]["status"])
            lines.append(f"{ds} & " + " & ".join(cells) + " \\\\")
        lines += ["\\bottomrule", "\\end{tabular*}", "\\end{table}", ""]
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
    """The sentence describing the measurement platform, generated from the machine
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
    # The JDK is named by the environment lock, the one place it is pinned.
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
                f"{jvm}, with \\texttt{{{rec.get('jvm_flags')}}} and one Java virtual machine per run.")
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
#    DRIVER - the main figures and tables
def main():
    print(f"DATA_DIR = {DATA_DIR}\nOUT_DIR  = {OUT_DIR}")
    print(f"databases from common.Datasets: {', '.join(DATASETS) or 'none found'}\n")

    figset_scalability("SC3", "fig_scalability")       # runtime + explored vs su, whole DB
    figset_incremental()                               # SC2 cumulative runtime, 7 databases
    figset_c5_ablation()                               # SC7 C5 on / off
    figset_block_size()                                # SC7 block size, three panels
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
    import scenario_tables                             # summary CSVs, paper_numbers.csv, SC2 LaTeX tables
    scenario_tables.export_all()
    print("\nDONE.")


def main_all():
    """Everything above plus supplementary figures."""
    main()
    figset_scalability("SC1", "fig_scalability", datasets=ALL_DATASETS)
    figset_sl_sensitivity(metrics=("prelarge_count", "runtime_ms"))


if __name__ == "__main__":
    main()
