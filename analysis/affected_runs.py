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
              "Pre-HUSPM-Joint-w3", "Pre-HUSPM-Joint-w10", "Joint-miner+SWUmax", "Joint-miner+TSU",
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
    a = ap.parse_args()
    all_runs = list(runs())
    if a.static_warmup:
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
        extra += [(t, s, sc, ds, new, None) for t, s, sc, ds, arm, _ in all_runs
                  if arm == like and (t, s, sc, ds) not in have]
    no_direct = [] if a.static_warmup or a.remine or a.mixed_family else [r for r in listed if not has_direct_update(r[5])]
    if not a.include_no_direct:
        listed = [r for r in listed if r not in no_direct]
    groups = defaultdict(lambda: defaultdict(set))
    for t, s, sc, ds, arm, _ in listed + extra:
        groups[(t, s, sc)][arm].add(ds)
    print(f"runs of the campaign trees: {len(all_runs)}; to measure again: {len(listed)}, of which without "
          f"a direct update: {len(no_direct) if a.include_no_direct else 0}; kept without a direct update: "
          f"{0 if a.include_no_direct else len(no_direct)}; configurations added for new arms: {len(extra)}")
    for (t, s, sc), arms in sorted(groups.items()):
        # one command per set of databases, so no configuration that was not measured is added
        by_sets = defaultdict(list)
        for arm, dss in arms.items():
            by_sets[tuple(sorted(dss))].append(arm)
        for dss, arm_list in sorted(by_sets.items()):
            algos = ",".join(f"'{x}'" for x in sorted(arm_list))
            sets = ",".join(f"'{x}'" for x in dss)
            print(f".\\campaign.ps1 -Scenario {sc} {flags(t, s)} -Algorithms {algos} -Datasets {sets}".replace("  ", " "))
    if a.remove:
        for r in listed:
            shutil.rmtree(r[5])
        print(f"removed {len(listed)} run directories")
    return 0


if __name__ == "__main__":
    sys.exit(main())
