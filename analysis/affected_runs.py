#!/usr/bin/env python3
"""List the measured runs that executed the changed discard floor, and print the campaign commands
that measure the same configurations again.

    python3 analysis/affected_runs.py [--remove] [--extra-arm NAME:LIKE]

Compiled without debug information, the floor change leaves 86 of 89 classes identical; in the
proposal's class it changes the floor (jointFloor) and one field assignment in the constructor,
and in the runner one assignment of the floor per arm. A run therefore executed changed code in a
loop only if it evaluated the floor, which happens on a direct update (a row with cub_triggered
false after the first mine) of an arm in FLOOR_ARMS: the proposal's incremental arms, and
Joint-rule+plain-floor, whose floor value is unchanged but is now chosen by a switch inside the
direct-update loop. The floor is never evaluated by a static mine (SC1, SC3, SC4, SC7's static
ablations), by remining (Remine-*, a new instance mined from scratch at every operation), by
proposed_C5_off (a rescan at every increment), by Pre-HUSPM, IncUSP-Miner+, HUSP-ULL, HUSP-SP or
USpan (classes unchanged), nor by a run of a FLOOR_ARMS arm that rescanned at every operation: those
runs are kept, and counted. --include-no-direct lists the last kind too.

--remove deletes the listed run directories (and their DONE files) so that campaign.ps1 measures
them again. --extra-arm Joint-rule+min-floor:Joint-rule+plain-floor adds, for a new arm, every
configuration measured for an existing one. Commands are printed for PowerShell 5.1, one per tree
and scenario, with the databases measured there.
"""
import argparse
import csv
import pathlib
import shutil
import sys
from collections import defaultdict

ROOT = pathlib.Path(__file__).resolve().parent.parent
RESULTS = ROOT / "results"
INCREMENTAL = {"SC2", "SC5", "SC6", "SC6D", "SC6B", "SC7"}
FLOOR_ARMS = {"Adaptive-Full", "Adaptive-FloorSelect", "Adaptive-FloorSelect-w1", "Adaptive-FloorSelect-w2",
              "Adaptive-FloorSelect-w3", "Adaptive-FloorSelect-w10", "Joint-miner+SWUmax", "Joint-miner+TSU",
              "Joint-rule+PreHUSPM-tracking", "Joint-miner+SWUmax+PreHUSPM-tracking", "Joint-rule+min-floor",
              "Joint-rule+plain-floor",
              "proposed_C5_on", "proposed_no_block_index", "proposed_bsz_1", "proposed_bsz_5",
              "proposed_bsz_10", "proposed_bsz_50", "proposed_bsz_200"}
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
    a = ap.parse_args()
    all_runs = list(runs())
    listed = [r for r in all_runs if r[2] in INCREMENTAL and r[4] in FLOOR_ARMS]
    extra = []
    for spec in a.extra_arm:
        new, like = spec.split(":")
        have = {(t, s, sc, ds) for t, s, sc, ds, arm, _ in all_runs if arm == new}
        extra += [(t, s, sc, ds, new, None) for t, s, sc, ds, arm, _ in all_runs
                  if arm == like and (t, s, sc, ds) not in have]
    no_direct = [r for r in listed if not has_direct_update(r[5])]
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
