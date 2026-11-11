#!/usr/bin/env python3
"""List the measured runs that executed the changed discard floor, and print the campaign commands
that measure the same configurations again.

    python3 analysis/affected_runs.py [--remove] [--extra-arm NAME:LIKE] [--static-warmup | --remine]

Compiled without debug information, the floor change leaves 86 of 89 classes identical; in the
proposal's class it changes the floor (jointFloor) and one field assignment in the constructor,
and in the runner one assignment of the floor per arm. A run therefore executed changed code in a
loop only if it evaluated the floor, which happens on a direct update (a row with cub_triggered
false after the first mine) of an arm in FLOOR_ARMS: the proposal's incremental arms, and
Joint-rule+plain-floor, whose floor value is unchanged but is now chosen by a switch inside the
direct-update loop. The floor is never evaluated by a static mine (SC1, SC3, SC4, SC7's static
ablations), by remining (Remine-*, a new instance mined from scratch at every operation), by
Pre-HUSPM-Joint-rescan-every-batch (a rescan at every increment), by Pre-HUSPM, IncUSP-Miner+, HUSP-ULL, HUSP-SP or
USpan (classes unchanged), nor by a run of a FLOOR_ARMS arm that rescanned at every operation: those
runs are kept, and counted. --include-no-direct lists the last kind too.

--remove deletes the listed run directories (and their DONE files) so that campaign.ps1 measures
them again. --extra-arm Joint-rule+min-floor:Joint-rule+plain-floor adds, for a new arm, every
configuration measured for an existing one. Commands are printed for PowerShell 5.1, one per tree
and scenario, with the databases measured there.

--static-warmup lists instead every static timing run (SC1, SC3 and SC7's arms that mine once),
for the re-measurement under the static warm-up.

--mixed-family lists instead every run of the proposal's family (the method, the fixed lower threshold,
the two floors and the window arms) in SC6, SC6D and SC6B: a modification now mines at most once
(PreHUSPMAdaptiveAlgo.mergeModification), which changes all of them.

--remine lists instead every run of the two remining arms with the proposal's miner
(Remine-from-scratch, Remine-own-UUT) in the incremental scenarios, timing and memory trees alike:
they now call runRemine, which does not rebuild what depends on the sequences alone.

--fair-clock lists the runs the fair timed region changes (AutomatedExperimentRunner.freshSequences):
the static timing runs of USpan, IncUSP-Miner+ and Pre-HUSPM (SC1, SC3, SC4, SC7), which read
remaining utilities or flat arrays built before their clock started, and Pre-HUSPM's sequence
utility from the loader; their SC5 streams, where one JVM runs many grid cells; and the sampled
SC1 live sets of the arms that build flat arrays (the method, Joint-miner@UUT, Pre-HUSPM,
IncUSP-Miner+), which were not counted after the warm-up. Only arm directories with a DONE file
are listed. With --extra-arm it is used with Remine-from-scratch-rebuild:Remine-from-scratch and
Remine-own-UUT-rebuild:Remine-own-UUT. It also lists the DUNNHUMBY replicates set aside in DEFERRED
files that a statement of the paper depends on (UNDEFER), and --remove deletes those DEFERRED files;
--annotate-deferred appends the measured reason to every other DEFERRED file.
"""
import argparse
import csv
import pathlib
import re
import shutil
import sys
from collections import defaultdict

ROOT = pathlib.Path(__file__).resolve().parent.parent
RESULTS = ROOT / "results"
INCREMENTAL = {"SC2", "SC5", "SC6", "SC6D", "SC6B", "SC7"}
FLOOR_ARMS = {"Pre-HUSPM-Joint-fixed-threshold", "Pre-HUSPM-Joint", "Pre-HUSPM-Joint-w1", "Pre-HUSPM-Joint-w2",
              "Pre-HUSPM-Joint-w3", "Pre-HUSPM-Joint-w5", "Pre-HUSPM-Joint-w10", "Joint-miner+SWUmax", "Joint-miner+TSU",
              "Joint-rule+PreHUSPM-tracking", "Joint-miner+SWUmax+PreHUSPM-tracking", "Joint-rule+min-floor",
              "Joint-rule+plain-floor",
              "Pre-HUSPM-Joint-rescan-rule", "Pre-HUSPM-Joint-no-block-index", "Pre-HUSPM-Joint-bsz-1",
              "Pre-HUSPM-Joint-bsz-5", "Pre-HUSPM-Joint-bsz-10", "Pre-HUSPM-Joint-bsz-50", "Pre-HUSPM-Joint-bsz-200"}
MIXED = {"SC6", "SC6D", "SC6B"}
FAMILY = {"Pre-HUSPM-Joint", "Pre-HUSPM-Joint-fixed-threshold", "Joint-rule+plain-floor", "Joint-rule+min-floor"}
WINDOW = re.compile(r"Pre-HUSPM-Joint-w\d+$")
# the remining arms that run the proposal's miner (runRemine)
REMINE_ARMS = {"Remine-from-scratch", "Remine-own-UUT"}
# SC7's arms that mine once (logAblation and the Pre-HUSPM reference)
SC7_STATIC = {"Pre-HUSPM-Joint", "Pre-HUSPM-Joint-no-IUB", "Pre-HUSPM-Joint-no-IPS", "Pre-HUSPM"}
# fair clock: arms that build per-sequence data only when it is missing, and the scenarios
FAIR_STATIC_ARMS = {"USpan", "IncUSP-Miner+", "Pre-HUSPM"}
FAIR_STATIC_SC = {"SC1", "SC3", "SC4", "SC7"}
FAIR_SC5_ARMS = {"IncUSP-Miner+", "Pre-HUSPM-SWUmax", "Pre-HUSPM-TSU"}
FAIR_MEM_ARMS = {"Pre-HUSPM-Joint", "Joint-miner@UUT", "Pre-HUSPM", "IncUSP-Miner+"}
# DUNNHUMBY replicates that a statement depends on (decided 2026-11-05): the two tracking arms tie
# with each other and give the largest factor of tracking by projected SWU; Pre-HUSPM-SWUmax gives
# the upper end of its ratio to the method; USpan's live set is within 5% of the method's at the
# lowest threshold; SC7's Pre-HUSPM replicates take about a minute.
UNDEFER = {("campaign-replicates", f"s0r{n}", "SC2", "DUNNHUMBY", arm) for n in range(1, 5)
           for arm in ("Joint-rule+PreHUSPM-tracking", "Joint-miner+SWUmax+PreHUSPM-tracking", "Pre-HUSPM-SWUmax")}
UNDEFER |= {("campaign-mem-sampled-replicates", "s0r1", "SC1", "DUNNHUMBY", "USpan")}
UNDEFER |= {("campaign-replicates", f"s0r{n}", "SC7", "DUNNHUMBY", "Pre-HUSPM") for n in (1, 2)}
TREES = ["campaign", "campaign-replicates", "campaign-mem", "campaign-mem-replicates",
         "campaign-mem-sampled", "campaign-mem-sampled-replicates"]


def flags(tree, sub):
    """campaign.ps1 parameters that write into results/<tree>[/<sub>]."""
    f = []
    if tree.startswith("campaign-mem"):
        f.append("-MemMode live")
        if "sampled" in tree:
            f.append("-MemGeneration sampled")
    if sub:
        k, n = sub[1:].split("r")
        if int(k):
            f.append(f"-SeedIndex {k}")
        if int(n):
            f.append(f"-Repeat {n}")
    return " ".join(f)


def runs():
    """(tree, sub, scenario, dataset, arm, path) of every arm directory under the campaign trees."""
    for tree in TREES:
        base = RESULTS / tree
        if not base.is_dir():
            continue
        subs = [d for d in base.iterdir() if d.is_dir() and d.name.startswith("s") and "r" in d.name] \
            if tree.endswith("replicates") else [base]
        for sb in subs:
            for sc in sorted(p for p in sb.iterdir() if p.is_dir() and p.name.startswith("SC")):
                for ds in sorted(p for p in sc.iterdir() if p.is_dir()):
                    for arm in sorted(p for p in ds.iterdir() if p.is_dir()):
                        yield tree, ("" if sb == base else sb.name), sc.name, ds.name, arm.name, arm


def _arm_total(tree, sc, ds, arm, metric):
    """Per threshold (SC1) or whole-stream value of one arm's single run in a main tree: summed
    runtime of the completed rows, or the largest live set. None if the arm has no rows."""
    files = list((RESULTS / tree / sc / ds / arm).glob("*.csv"))
    if not files:
        return None
    out = defaultdict(float)
    for f in files:
        with open(f, newline="", encoding="utf-8") as fh:
            for r in csv.DictReader(fh):
                key = re.search(r"su=([\d.]+)", r["run_id"]).group(1) if sc == "SC1" else "stream"
                if metric == "runtime_ms":
                    v = float(r["runtime_ms"])
                    if v < 0:
                        out[key] = float("nan")
                    elif out[key] == out[key]:
                        out[key] += v
                else:
                    out[key] = max(out[key], float(r[metric] or 0))
    return dict(out)


def deferred_note(r):
    """The measured reason a DUNNHUMBY replicate stays set aside: the smallest ratio of the
    baseline's single run to the method's over the thresholds (SC1) or the stream."""
    tree, sub, sc, ds, arm, _ = r
    main = tree.replace("-replicates", "")
    head = "\nEVALUATED 2026-11-11\n"
    if main == "campaign-mem" and sc == "SC1":
        return head + ("  Not replicated: results/campaign-mem/SC1 is the first SC1 memory measurement, which the\n"
                       "  paper does not use (results/campaign-mem/SC1/README.md).\n")
    metric = "mem_live_mb" if main.startswith("campaign-mem") else "runtime_ms"
    base = _arm_total(main, sc, ds, arm, metric)
    ref = _arm_total(main, sc, ds, "Pre-HUSPM-Joint", metric)
    ratios = [base[k] / ref[k] for k in base if k in ref and ref[k] > 0 and base[k] == base[k] and ref[k] == ref[k]]
    if not ratios:
        return head + "  No threshold where both the arm and the method completed; kept set aside.\n"
    lo = min(ratios)
    what = "time" if metric == "runtime_ms" else "live set"
    text = (f"  Not replicated: the paper compares this arm with the method only, and its single run takes {lo:.2f}\n"
            f"  times the method's {what} or more ({len(ratios)} comparisons, {main}/{sc}), outside the run-to-run\n"
            "  spread of either arm.\n")
    text += (f"  Code of the two runs (commit of their DONE files): this arm {_done_field(main, sc, ds, arm, 'commit')}, "
             f"the method {_done_field(main, sc, ds, 'Pre-HUSPM-Joint', 'commit')}.\n")
    if metric == "runtime_ms" and ds in SESSION_DRIFT:
        f = SESSION_DRIFT[ds]
        a, b = _done_field(main, sc, ds, arm, "finished"), _done_field(main, sc, ds, "Pre-HUSPM-Joint", "finished")
        text += (f"  The two runs finished {a} and {b}. On {ds} the same arm measured in two sessions differs by up\n"
                 f"  to a factor {f} (results-invariant/session-drift/2026-11-06); the ratio is "
                 + ("above it, so the\n  direction holds, and the value is good to that factor only.\n" if lo > f else
                    "NOT above it: measure this\n  cell in the method's session before any ranking is stated.\n"))
    return head + text


# The largest ratio between two sessions of the same arm, seed and commit, per database measured as
# unstable between sessions (results-invariant/session-drift/2026-11-06).
SESSION_DRIFT = {"DUNNHUMBY": 1.664}


def _done_field(tree, sc, ds, arm, field):
    """A field of an arm's DONE file (commit, or the finish time), or "unknown"."""
    f = RESULTS / tree / sc / ds / arm / "DONE"
    if not f.is_file():
        return "unknown"
    m = re.search(field + r"=(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}|\S+)", f.read_text(encoding="utf-8"))
    return m.group(1) if m else "unknown"


def has_direct_update(path):
    for f in path.glob("*.csv"):
        with open(f, newline="", encoding="utf-8") as fh:
            for r in csv.DictReader(fh):
                if r.get("cub_triggered") == "false" and r.get("iteration") not in ("0", None):
                    return True
    return False


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--remove", action="store_true")
    ap.add_argument("--include-no-direct", action="store_true")
    ap.add_argument("--extra-arm", action="append", default=[])
    ap.add_argument("--mixed-family", action="store_true",
                    help="list the runs of the proposal's family in SC6, SC6D and SC6B instead")
    ap.add_argument("--remine", action="store_true",
                    help="list the runs of Remine-from-scratch and Remine-own-UUT instead")
    ap.add_argument("--static-warmup", action="store_true",
                    help="list the static mines of the timing trees instead (SC1, SC3, SC7's static arms)")
    ap.add_argument("--fair-clock", action="store_true",
                    help="list the runs the fair timed region changes, and the DUNNHUMBY replicates to undefer")
    ap.add_argument("--plan-out", help="write the listed cells as TSV: tree, replicate, scenario, database, arm, kind")
    ap.add_argument("--annotate-deferred", action="store_true",
                    help="with --fair-clock: append the measured reason to the DEFERRED files kept")
    a = ap.parse_args()
    all_runs = list(runs())
    undefer = []
    if a.fair_clock:
        done = [r for r in all_runs if (r[5] / "DONE").is_file()]
        listed = [r for r in done if not r[0].startswith("campaign-mem")
                  and ((r[2] in FAIR_STATIC_SC and r[4] in FAIR_STATIC_ARMS) or (r[2] == "SC5" and r[4] in FAIR_SC5_ARMS))]
        listed += [r for r in done if r[0].startswith("campaign-mem-sampled") and r[2] == "SC1" and r[4] in FAIR_MEM_ARMS]
        deferred = [r for r in all_runs if (r[5] / "DEFERRED").is_file()]
        undefer = [r for r in deferred if r[:5] in UNDEFER]
        missing = UNDEFER - {r[:5] for r in undefer}
        if missing:
            # already undeferred (measured or queued); an error only when asked to remove
            print(f"UNDEFER names {len(missing)} cells with no DEFERRED file (already undeferred)", file=sys.stderr)
            if a.remove:
                return 1
        if a.annotate_deferred:
            kept = [r for r in deferred if r[:5] not in UNDEFER]
            for r in kept:
                note = deferred_note(r)
                f = r[5] / "DEFERRED"
                body = re.split(r"\nEVALUATED \d{4}-\d{2}-\d{2}\n", f.read_text(encoding="utf-8"))[0]
                with open(f, "w", encoding="utf-8", newline="\n") as fh:
                    fh.write(body + note)
            print(f"annotated {len(kept)} DEFERRED files")
    elif a.static_warmup:
        # The static warm-up (AutomatedExperimentRunner.STATIC_WARMUP) changes when a static mine
        # is timed, not what it computes: every static timing run is measured again; memory trees
        # (live mode, no warm-up) and incremental arms are kept.
        listed = [r for r in all_runs if not r[0].startswith("campaign-mem")
                  and (r[2] in ("SC1", "SC3") or (r[2] == "SC7" and r[4] in SC7_STATIC))]
    elif a.mixed_family:
        listed = [r for r in all_runs if r[2] in MIXED and (r[4] in FAMILY or WINDOW.match(r[4]))]
    elif a.remine:
        listed = [r for r in all_runs if r[2] in INCREMENTAL and r[4] in REMINE_ARMS]
    else:
        listed = [r for r in all_runs if r[2] in INCREMENTAL and r[4] in FLOOR_ARMS]
    extra = []
    for spec in a.extra_arm:
        new, like = spec.split(":")
        have = {(t, s, sc, ds) for t, s, sc, ds, arm, _ in all_runs if arm == new}
        extra += [(t, s, sc, ds, new, None) for t, s, sc, ds, arm, p in all_runs
                  if arm == like and (t, s, sc, ds) not in have and (p / "DONE").is_file()]
    no_direct = [] if a.static_warmup or a.remine or a.mixed_family or a.fair_clock else [r for r in listed if not has_direct_update(r[5])]
    if not a.include_no_direct:
        listed = [r for r in listed if r not in no_direct]
    groups = defaultdict(lambda: defaultdict(set))
    for t, s, sc, ds, arm, _ in listed + extra + [r for r in undefer]:
        groups[(t, s, sc)][arm].add(ds)
    print(f"runs of the campaign trees: {len(all_runs)}; to measure again: {len(listed)}, of which without "
          f"a direct update: {len(no_direct) if a.include_no_direct else 0}; kept without a direct update: "
          f"{0 if a.include_no_direct else len(no_direct)}; configurations added for new arms: {len(extra)}"
          + (f"; DUNNHUMBY replicates undeferred: {len(undefer)}" if a.fair_clock else ""))
    for (t, s, sc), arms in sorted(groups.items()):
        # one command per set of databases, so no configuration that was not measured is added
        by_sets = defaultdict(list)
        for arm, dss in arms.items():
            by_sets[tuple(sorted(dss))].append(arm)
        for dss, arm_list in sorted(by_sets.items()):
            algos = ",".join(f"'{x}'" for x in sorted(arm_list))
            sets = ",".join(f"'{x}'" for x in dss)
            print(f".\\campaign.ps1 -Scenario {sc} {flags(t, s)} -Algorithms {algos} -Datasets {sets}".replace("  ", " "))
    if a.plan_out:
        with open(a.plan_out, "w", encoding="utf-8", newline="\n") as fh:
            fh.write("tree\treplicate\tscenario\tdatabase\tarm\tkind\n")
            for kind, rows in (("again", listed), ("new", extra), ("undeferred", undefer)):
                for t, s_, sc, ds, arm, _ in rows:
                    fh.write(f"{t}\t{s_}\t{sc}\t{ds}\t{arm}\t{kind}\n")
    if a.remove:
        for r in listed:
            shutil.rmtree(r[5])
        for r in undefer:
            (r[5] / "DEFERRED").unlink()
        print(f"removed {len(listed)} run directories and {len(undefer)} DEFERRED files")
    return 0


if __name__ == "__main__":
    sys.exit(main())
