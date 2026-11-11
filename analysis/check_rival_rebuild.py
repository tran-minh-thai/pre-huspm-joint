#!/usr/bin/env python3
"""Do the remining rivals that rebuild their data inside the clock do the same work as the arms
they replace?

    python3 analysis/check_rival_rebuild.py              # every pair under results/campaign*
    python3 analysis/check_rival_rebuild.py --direction  # is a rebuild arm ever faster than the arm it replaces?
    python3 analysis/check_rival_rebuild.py --self-test  # injected faults must be caught

The rivals Remine-from-scratch and Remine-own-UUT are read, in every table and figure, from the
arms Remine-from-scratch-rebuild and Remine-own-UUT-rebuild (make_figures.RIVAL_SOURCE). The two
arms of a pair differ only in what is inside the timed region, so every counter of the search must
be equal row by row: candidates, explored nodes, patterns, every pruning counter. This walks every
tree results/campaign* (timing, memory, replicates), pairs each finished arm directory with its
rebuild directory in the same parent, joins the rows on (database, operation, iteration, seed,
repeat, repetition) and compares the counters exactly.

A rebuild arm measured without the superseded arm beside it (a replicate that times the rival
alone) is compared, in a replicate tree of seed index 0 (s0r<n>), with the superseded arm of the
main tree, the same stream, on the same key without `repeat`. In any other tree it is counted as
not compared and named; it is not a failure, since the rival is read from the rebuild arm.

--direction checks the one thing the code decides about the two arms' times: a rebuild arm does
the work of the arm it replaces plus the rebuilding, so it cannot be the faster one. Per pair of
timing arm directories it takes the time of the rebuild arm over that of the superseded arm, summed
over the operations both completed (the initial mine, the same call in both, is left out), and per
(scenario, database, arm) the geometric mean over the pairs. A group fails when that mean is below
the lower end of the tie band (key jit_tie_band of figures/campaign/paper_numbers.csv, the largest
time ratio measured between two arms that make the same search). Two arms are in one session when
their DONE files are at most SESSION_HOURS apart; pairs measured in two sessions are judged too,
except on a database of UNSTABLE, where times drift between sessions by more than the band and such
pairs are counted and left out. Exit 0 when no group fails, 1 when one does, 2 when no pair could
be judged.

Prints, per tree, the pairs compared and the rows compared, then every mismatch. Exit 0 when every
pair agrees on every row, 1 on a mismatch, a row in one arm only, or a superseded arm without its
rebuild partner (that rival would vanish from a table), 2 when nothing could be compared.
"""
import argparse
import collections
import csv
import math
import pathlib
import re
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
SESSION_HOURS = 6.0
# Databases whose times drift between sessions by more than the tie band: the same arm, seed and
# commit on two days differ by up to this factor (results-invariant/session-drift/2026-11-06).
UNSTABLE = {"DUNNHUMBY": 1.664}
FINISHED = re.compile(r"finished=(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2})")


def read_arm(d):
    frames = [pd.read_csv(f, low_memory=False) for f in sorted(d.glob("*.csv"))]
    if not frames:
        return None
    x = pd.concat(frames, ignore_index=True)
    x.columns = [c.strip() for c in x.columns]
    return x


def compare(old_dir, new_dir, skip=()):
    """(rows compared, list of problems) for one pair of arm directories; skip: key columns left out."""
    a, b = read_arm(old_dir), read_arm(new_dir)
    if a is None or b is None:
        return 0, [f"no csv in {(old_dir if a is None else new_dir).as_posix()}"]
    key = [k for k in KEY if k in a.columns and k in b.columns and k not in skip]
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
    failures, alone = [], []
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
                if not (n / "DONE").exists() or (n.with_name(old) / "DONE").exists():
                    continue
                where = n.relative_to(results).as_posix()
                main = results / "campaign" / n.parts[-3] / n.parts[-2] / old
                if re.fullmatch(r"s0r\d+", tree.name) and (main / "DONE").exists():
                    k, problems = compare(main, n, skip=("repeat",))
                    pairs += 1
                    rows += k
                    per_tree[tree.relative_to(results).as_posix() + " (against the main tree)"] += 1
                    failures += [f"{where}: {p}" for p in problems]
                else:
                    alone.append(where)
    for t, c in sorted(per_tree.items()):
        print(f"  {t}: {c} pairs")
    print(f"pairs compared: {pairs}, rows compared: {rows}, problems: {len(failures)}, "
          f"rebuild arms not compared (no superseded arm for that stream): {len(alone)}")
    for w in alone:
        print("  not compared: " + w)
    for f in failures:
        print("  " + f)
    if pairs == 0:
        return 2
    return 1 if failures else 0


def band_low(keys_path):
    """The lower end of the tie band, from key jit_tie_band ("0.920 to 1.087 (...)")."""
    with open(keys_path, newline="", encoding="utf-8") as fh:
        for r in csv.DictReader(fh):
            if r["key"] == "jit_tie_band":
                return float(r["value"].split(" to ")[0])
    raise SystemExit(f"{keys_path}: no key jit_tie_band")


def finished(d):
    m = FINISHED.search((d / "DONE").read_text(encoding="utf-8"))
    return pd.Timestamp(m.group(1)) if m else None


def pair_ratio(old_dir, new_dir):
    """(time of the rebuild arm over the superseded arm on the operations both completed, rows)."""
    a, b = read_arm(old_dir), read_arm(new_dir)
    if a is None or b is None:
        return None, 0
    key = [k for k in KEY if k in a.columns and k in b.columns]
    m = a[key + ["runtime_ms"]].merge(b[key + ["runtime_ms"]], on=key, suffixes=("_old", "_new"))
    m = m[(m["iteration"] > 0) & (m["runtime_ms_old"] > 0) & (m["runtime_ms_new"] > 0)]
    if not len(m):
        return None, 0
    return float(m["runtime_ms_new"].sum()) / float(m["runtime_ms_old"].sum()), len(m)


def direction(results, low):
    groups = collections.defaultdict(list)
    left_out = collections.Counter()
    undated = 0
    for tree in trees(results):
        if "-mem" in tree.relative_to(results).as_posix():
            continue   # the memory runs' times are void
        for old, new in PAIRS.items():
            for d in sorted(tree.glob(f"*/*/{old}")):
                n = d.with_name(new)
                if not (d / "DONE").exists() or not (n / "DONE").exists():
                    continue
                r, rows = pair_ratio(d, n)
                if r is None:
                    continue
                ta, tb = finished(d), finished(n)
                if ta is None or tb is None:
                    undated += 1
                    continue
                db = d.parts[-2]
                same = abs((ta - tb).total_seconds()) <= SESSION_HOURS * 3600
                if not same and db in UNSTABLE:
                    left_out[db] += 1
                    continue
                groups[(d.parts[-3], db, old)].append((r, same))
    fails = []
    pairs = sum(len(v) for v in groups.values())
    for k in sorted(groups):
        v = [r for r, _ in groups[k]]
        g = math.exp(sum(math.log(x) for x in v) / len(v))
        if g < low:
            fails.append(f"{k[0]} {k[1]} {k[2]}: rebuild over superseded {g:.3f} over {len(v)} pair(s) "
                         f"({min(v):.3f} to {max(v):.3f}), below {low:.3f}")
    one = sum(s for v in groups.values() for _, s in v)
    print(f"direction: {len(groups)} groups, {pairs} pairs judged ({one} in one session, {pairs - one} in two), "
          f"lower end of the tie band {low:.3f}; groups below it: {len(fails)}")
    if groups:
        means = sorted((math.exp(sum(math.log(r) for r, _ in v) / len(v)), k) for k, v in groups.items())
        g, k = means[0]
        print(f"  smallest group mean: {g:.3f} ({k[0]} {k[1]} {k[2]}); largest: {means[-1][0]:.3f} "
              f"({means[-1][1][0]} {means[-1][1][1]} {means[-1][1][2]})")
    for db, c in sorted(left_out.items()):
        print(f"  not judged: {c} pair(s) on {db} measured in two sessions (drift factor {UNSTABLE[db]})")
    if undated:
        print(f"  not judged: {undated} pair(s) whose DONE file has no finish time")
    for f in fails:
        print("  " + f)
    if not pairs:
        return 2
    return 1 if fails else 0


def direction_self_test():
    src = ROOT / "results" / "campaign" / "SC6" / "DUNNHUMBY"
    arms = ("Remine-from-scratch", "Remine-from-scratch-rebuild")

    def set_ratio(f):
        """Every operation of the rebuild arm takes f times the superseded arm's time."""
        def go(t):
            o = pd.read_csv(next((t / "campaign/SC6/DUNNHUMBY" / arms[0]).glob("*.csv")), low_memory=False)
            p = next((t / "campaign/SC6/DUNNHUMBY" / arms[1]).glob("*.csv"))
            x = pd.read_csv(p, low_memory=False)
            assert list(o["iteration"]) == list(x["iteration"])
            x["runtime_ms"] = (o["runtime_ms"] * f).round().astype(int).values
            x.to_csv(p, index=False)
        return go

    def other_session(t):
        p = t / "campaign/SC6/DUNNHUMBY/Remine-from-scratch-rebuild/DONE"
        p.write_text(FINISHED.sub("finished=2026-01-01 00:00:00", p.read_text(encoding="utf-8")), encoding="utf-8")

    def stable_other_session(t):
        (t / "campaign/SC6/DUNNHUMBY").rename(t / "campaign/SC6/SIGN")
        p = t / "campaign/SC6/SIGN/Remine-from-scratch-rebuild/DONE"
        p.write_text(FINISHED.sub("finished=2026-01-01 00:00:00", p.read_text(encoding="utf-8")), encoding="utf-8")
        for arm in arms:
            q = next((t / "campaign/SC6/SIGN" / arm).glob("*.csv"))
            x = pd.read_csv(q, low_memory=False)
            x["dataset"] = "SIGN"
            if arm.endswith("-rebuild"):
                x["runtime_ms"] = (base * 0.5).round().astype(int).values
            else:
                base = x["runtime_ms"]
            x.to_csv(q, index=False)

    def no_time(t):
        p = t / "campaign/SC6/DUNNHUMBY/Remine-from-scratch/DONE"
        p.write_text("host=X rc=0 rows=1\n", encoding="utf-8")

    cases = [("clean pair of one session", None, 0),
             ("the rebuild arm twice as fast", set_ratio(0.5), 1),
             ("the rebuild arm 5 percent faster: inside the band", set_ratio(0.95), 0),
             ("the rebuild arm 10 percent faster: below the band", set_ratio(0.90), 1),
             ("the rebuild arm slower", set_ratio(1.5), 0),
             ("two sessions on an unstable database: not judged", other_session, 2),
             ("two sessions on a stable database, the rebuild arm twice as fast", stable_other_session, 1),
             ("no finish time: not judged", no_time, 2)]
    fails = 0
    for name, fault, expect in cases:
        with tempfile.TemporaryDirectory() as tmp:
            t = pathlib.Path(tmp)
            for arm in arms:
                shutil.copytree(src / arm, t / "campaign/SC6/DUNNHUMBY" / arm)
            if fault:
                fault(t)
            print(f"--- {name}")
            got = direction(t, 0.920)
            ok = got == expect
            fails += not ok
            print(f"SELF-TEST {name}: exit {got}, expected {expect} -> {'ok' if ok else 'FAILED'}")
    print(f"direction self-test: {len(cases) - fails}/{len(cases)} cases as expected")
    return 1 if fails else 0


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

    def replicate_alone(col):
        def f(t):
            d = t / "campaign-replicates/s0r3/SC8/DUNNHUMBY/Remine-from-scratch-rebuild"
            shutil.copytree(src / "Remine-from-scratch-rebuild", d)
            q = next(d.glob("*.csv"))
            x = pd.read_csv(q, low_memory=False)
            x["repeat"] = 3
            if col:
                x.loc[x.index[10], col] += 1
            x.to_csv(q, index=False)
        return f

    def empty(t):
        shutil.rmtree(t / "campaign")
        (t / "campaign").mkdir()

    cases += [("explored nodes changed", change("explored_nodes"), 1),
              ("patterns changed", change("total_patterns_found"), 1),
              ("row missing", drop_row, 1), ("rebuild arm unfinished", no_partner, 1),
              ("superseded arm unfinished: not compared, no failure", no_old, 0),
              ("replicate with the rebuild arm alone, against the main tree", replicate_alone(None), 0),
              ("replicate with the rebuild arm alone, nodes changed", replicate_alone("explored_nodes"), 1),
              ("nothing to compare", empty, 2)]
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
    ap.add_argument("--direction", action="store_true")
    ap.add_argument("--results", default=str(ROOT / "results"))
    ap.add_argument("--keys", default=str(ROOT / "figures" / "campaign" / "paper_numbers.csv"))
    a = ap.parse_args()
    if a.self_test:
        sys.exit(max(self_test(), direction_self_test()))
    if a.direction:
        sys.exit(direction(pathlib.Path(a.results), band_low(a.keys)))
    sys.exit(check(pathlib.Path(a.results)))


if __name__ == "__main__":
    main()
