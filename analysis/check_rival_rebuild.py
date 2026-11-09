#!/usr/bin/env python3
"""Do the remining rivals that rebuild their data inside the clock do the same work as the arms
they replace?

    python3 analysis/check_rival_rebuild.py              # every pair under results/campaign*
    python3 analysis/check_rival_rebuild.py --self-test  # injected faults must be caught

The rivals Remine-from-scratch and Remine-own-UUT are read, in every table and figure, from the
arms Remine-from-scratch-rebuild and Remine-own-UUT-rebuild (make_figures.RIVAL_SOURCE). The two
arms of a pair differ only in what is inside the timed region, so every counter of the search must
be equal row by row: candidates, explored nodes, patterns, every pruning counter. This walks every
tree results/campaign* (timing, memory, replicates), pairs each finished arm directory with its
rebuild directory in the same parent, joins the rows on (database, operation, iteration, seed,
repeat, repetition) and compares the counters exactly.

Prints, per tree, the pairs compared and the rows compared, then every mismatch. Exit 0 when every
pair agrees on every row, 1 on a mismatch, a row in one arm only, or an arm without its rebuild
partner (that rival would vanish from a table), 2 when nothing could be compared.
"""
import argparse
import collections
import pathlib
import shutil
import sys
import tempfile

import pandas as pd

ROOT = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "analysis"))
from make_figures import RIVAL_SOURCE as PAIRS  # noqa: E402
WORK = ["candidate_count", "explored_nodes", "total_patterns_found", "pruned_swu", "pruned_depth",
        "pruned_lar", "pruned_iip", "pruned_tsu", "pruned_peu", "pruned_value", "pruned_iub",
        "prelarge_count", "rescan_count", "rescans_at_uut"]
KEY = ["dataset", "op_type", "iteration", "seed", "repeat", "repetition"]


def read_arm(d):
    frames = [pd.read_csv(f, low_memory=False) for f in sorted(d.glob("*.csv"))]
    if not frames:
        return None
    x = pd.concat(frames, ignore_index=True)
    x.columns = [c.strip() for c in x.columns]
    return x


def compare(old_dir, new_dir):
    """(rows compared, list of problems) for one pair of arm directories."""
    a, b = read_arm(old_dir), read_arm(new_dir)
    if a is None or b is None:
        return 0, [f"no csv in {(old_dir if a is None else new_dir).as_posix()}"]
    key = [k for k in KEY if k in a.columns and k in b.columns]
    work = [c for c in WORK if c in a.columns and c in b.columns]
    for name, x in (("old", a), ("rebuild", b)):
        dup = x.duplicated(key, keep=False)
        if dup.any():
            return 0, [f"{int(dup.sum())} rows repeat the key {key} in the {name} arm"]
    m = a[key + work].merge(b[key + work], on=key, how="outer", suffixes=("_old", "_new"),
                            indicator=True)
    problems = []
    only = m[m["_merge"] != "both"]
    if len(only):
        problems.append(f"{len(only)} rows in one arm only ({only['_merge'].value_counts().to_dict()})")
    both = m[m["_merge"] == "both"]
    for c in work:
        o, n = both[c + "_old"], both[c + "_new"]
        bad = ~((o == n) | (o.isna() & n.isna()))
        if bad.any():
            r = both[bad].iloc[0]
            problems.append(f"{c} differs on {int(bad.sum())} rows, first at "
                            + ", ".join(f"{k}={r[k]}" for k in key) + f": {r[c + '_old']} vs {r[c + '_new']}")
    return len(both), problems


def trees(results):
    out = []
    for t in sorted(results.glob("campaign*")):
        if t.name.endswith("-replicates"):
            out += sorted(p for p in t.iterdir() if p.is_dir())
        elif t.is_dir():
            out.append(t)
    return out


def check(results):
    pairs = rows = 0
    failures = []
    per_tree = collections.Counter()
    for tree in trees(results):
        for old, new in PAIRS.items():
            for d in sorted(tree.glob(f"*/*/{old}")):
                if not (d / "DONE").exists():
                    continue
                n = d.with_name(new)
                where = d.relative_to(results).as_posix()
                if not (n / "DONE").exists():
                    failures.append(f"{where}: no finished {new} beside it")
                    continue
                k, problems = compare(d, n)
                pairs += 1
                rows += k
                per_tree[tree.relative_to(results).as_posix()] += 1
                failures += [f"{where}: {p}" for p in problems]
            for n in sorted(tree.glob(f"*/*/{new}")):
                if (n / "DONE").exists() and not (n.with_name(old) / "DONE").exists():
                    failures.append(f"{n.relative_to(results).as_posix()}: no finished {old} beside it")
    for t, c in sorted(per_tree.items()):
        print(f"  {t}: {c} pairs")
    print(f"pairs compared: {pairs}, rows compared: {rows}, problems: {len(failures)}")
    for f in failures:
        print("  " + f)
    if pairs == 0:
        return 2
    return 1 if failures else 0


def self_test():
    src = ROOT / "results" / "campaign" / "SC8" / "DUNNHUMBY"
    cases = [("clean", None, 0)]

    def change(col):
        def f(t):
            p = next((t / "campaign/SC8/DUNNHUMBY/Remine-own-UUT-rebuild").glob("*.csv"))
            x = pd.read_csv(p, low_memory=False)
            x.loc[x.index[10], col] += 1
            x.to_csv(p, index=False)
        return f

    def drop_row(t):
        p = next((t / "campaign/SC8/DUNNHUMBY/Remine-from-scratch-rebuild").glob("*.csv"))
        x = pd.read_csv(p, low_memory=False)
        x.drop(index=x.index[5]).to_csv(p, index=False)

    def no_partner(t):
        (t / "campaign/SC8/DUNNHUMBY/Remine-own-UUT-rebuild/DONE").unlink()

    def no_old(t):
        (t / "campaign/SC8/DUNNHUMBY/Remine-from-scratch/DONE").unlink()

    def empty(t):
        shutil.rmtree(t / "campaign")
        (t / "campaign").mkdir()

    cases += [("explored nodes changed", change("explored_nodes"), 1),
              ("patterns changed", change("total_patterns_found"), 1),
              ("row missing", drop_row, 1), ("rebuild arm unfinished", no_partner, 1),
              ("old arm unfinished", no_old, 1), ("nothing to compare", empty, 2)]
    fails = 0
    for name, fault, expect in cases:
        with tempfile.TemporaryDirectory() as tmp:
            t = pathlib.Path(tmp)
            for arm in list(PAIRS) + list(PAIRS.values()):
                shutil.copytree(src / arm, t / "campaign/SC8/DUNNHUMBY" / arm)
            if fault:
                fault(t)
            print(f"--- {name}")
            got = check(t)
            ok = got == expect
            fails += not ok
            print(f"SELF-TEST {name}: exit {got}, expected {expect} -> {'ok' if ok else 'FAILED'}")
    print(f"self-test: {len(cases) - fails}/{len(cases)} cases as expected")
    return 1 if fails else 0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--self-test", action="store_true")
    ap.add_argument("--results", default=str(ROOT / "results"))
    a = ap.parse_args()
    sys.exit(self_test() if a.self_test else check(pathlib.Path(a.results)))


if __name__ == "__main__":
    main()
