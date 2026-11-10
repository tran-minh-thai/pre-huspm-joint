#!/usr/bin/env python3
"""Which timing cells of one database pair the method with an arm measured in another session?

    python3 analysis/session_cells.py --db DUNNHUMBY --drift 1.664
    python3 analysis/session_cells.py --db DUNNHUMBY --drift 1.664 --plan-out plan.tsv [--remove]
    python3 analysis/session_cells.py --self-test

On a database whose times move between sessions, the time of the method over an arm's measures the
sessions unless both ran in one. Two arms of a (tree, scenario) ran in one session when their DONE
files were written at most SESSION_HOURS apart (the arms of one campaign step run back to back).
Any larger gap counts here as another session: the plan measures again whatever is not known to
share the method's session, so a gap that analysis/long_streams.py would call undecided (up to
OTHER_SESSION_HOURS) is measured again too; the report counts those cells.

Reads the timing trees (results/campaign and results/campaign-replicates/s<k>r<n>) of the
scenarios whose tables pool the runs of a tree (the long streams are left out: there
analysis/long_streams.py takes the ratio inside the same-session runs). Every finished arm of the
database, beside a finished run of the method, is one cell of kind:

    method   the method's run of that (tree, scenario)
    same     an arm measured in the method's session
    near     an arm measured in another session whose time, in at least one tree, is within a
             factor --drift of the method's (the largest change of a time between two sessions
             measured on that database, analysis/session_drift.py): its comparison could change sides
    far      an arm measured in another session and outside that factor in every tree: the side
             cannot change, only the value is approximate

The plan measures again, in one session per (tree, scenario), every cell of a (tree, scenario)
that holds a near cell: the method, the near arms and the same-session arms (measuring the method
again would otherwise put them in another session). A (tree, scenario) without a near cell is left
alone, and so are the far cells. Prints the cells by scenario and kind with the hours their DONE
files give (an estimate of the next run, not a measurement), and how many cells it could compare.

--plan-out writes tree, replicate, scenario, database, arm, kind, seconds of the planned cells.
--remove deletes the run directories of the planned cells (campaign.ps1 measures a cell again when
its directory has no DONE); write the plan first, the estimates come from the DONE files.
Exit 0; 1 when no cell could be compared.
"""
import argparse
import collections
import pathlib
import re
import shutil
import sys
import tempfile

import pandas as pd

ROOT = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "analysis"))
import long_streams as ls  # noqa: E402

METHOD = ls.METHOD
SESSION_HOURS, OTHER_SESSION_HOURS = ls.SESSION_HOURS, ls.OTHER_SESSION_HOURS


def done(d):
    """(finish time, seconds) of an arm directory, or None without a DONE file that gives both."""
    try:
        t = (d / "DONE").read_text(encoding="utf-8")
    except OSError:
        return None
    m, s = ls.FINISHED.search(t), re.search(r"seconds=(\d+)", t)
    return (pd.Timestamp(m.group(1)), int(s.group(1))) if m and s else None


def stream_seconds(d):
    """Summed runtime of the completed rows (after the initial mine where the arm has updates)."""
    x = pd.concat(pd.read_csv(f, low_memory=False) for f in sorted(d.glob("*.csv")))
    x.columns = [c.strip() for c in x.columns]
    x = x[x["runtime_ms"] > 0]
    if len(x) and x["iteration"].max() > 0:
        x = x[x["iteration"] > 0]
    return float(x["runtime_ms"].sum()) / 1000.0


def trees(results):
    out = [("campaign", "", results / "campaign")]
    rep = results / "campaign-replicates"
    if rep.is_dir():
        out += [("campaign-replicates", p.name, p) for p in sorted(rep.iterdir())
                if p.is_dir() and re.fullmatch(r"s\d+r\d+", p.name)]
    return out


def cells(results, db, drift):
    """One dict per finished arm of db beside a finished method run; kinds as in the module text."""
    rows = []
    for tree, sub, base in trees(results):
        for mdir in sorted(base.glob(f"*/{db}/{METHOD}")):
            sc = mdir.parts[-3]
            dm = done(mdir)
            if dm is None or sc in ls.SCENARIOS:
                continue
            tm = stream_seconds(mdir)
            rows.append(dict(tree=tree, replicate=sub, scenario=sc, database=db, arm=METHOD, kind="method",
                             seconds=dm[1], gap_h=0.0, ratio=1.0, path=mdir))
            for a in sorted(p for p in mdir.parent.iterdir() if p.is_dir() and p.name != METHOD):
                da = done(a)
                if da is None:
                    continue
                gap = abs((dm[0] - da[0]).total_seconds()) / 3600.0
                ta = stream_seconds(a)
                rows.append(dict(tree=tree, replicate=sub, scenario=sc, database=db, arm=a.name,
                                 kind="same" if gap <= SESSION_HOURS else "cross", seconds=da[1], gap_h=gap,
                                 ratio=tm / ta if ta > 0 else float("nan"), path=a))
    # an arm measured in another session is near when its ratio is within the drift in any tree
    near = {(r["scenario"], r["arm"]) for r in rows
            if r["kind"] == "cross" and 1 / drift <= r["ratio"] <= drift}
    for r in rows:
        if r["kind"] == "cross":
            r["kind"] = "near" if (r["scenario"], r["arm"]) in near else "far"
    return rows


def plan(rows):
    """Every method, near and same cell of a (tree, replicate, scenario) that holds a near cell."""
    hit = {(r["tree"], r["replicate"], r["scenario"]) for r in rows if r["kind"] == "near"}
    return [r for r in rows if (r["tree"], r["replicate"], r["scenario"]) in hit and r["kind"] != "far"]


def report(results, db, drift, plan_out=None, remove=False):
    rows = cells(results, db, drift)
    compared = [r for r in rows if r["kind"] != "method"]
    print(f"{db}: {len(compared)} arms compared with a run of the method in {len(rows) - len(compared)} "
          f"(tree, scenario) pairs; drift factor {drift:g}; gaps between {SESSION_HOURS:g} and "
          f"{OTHER_SESSION_HOURS:g} h, counted as another session: "
          f"{sum(SESSION_HOURS < r['gap_h'] < OTHER_SESSION_HOURS for r in rows)}")
    if not compared:
        print("nothing to compare: the question is unanswered")
        return 1
    by = collections.Counter((r["scenario"], r["kind"]) for r in rows)
    hours = collections.Counter()
    for r in rows:
        hours[(r["scenario"], r["kind"])] += r["seconds"] / 3600.0
    for (sc, kind), n in sorted(by.items()):
        print(f"  {sc:5s} {kind:6s} cells {n:3d}  hours {hours[(sc, kind)]:6.2f}")
    for sc, arm in sorted({(r["scenario"], r["arm"]) for r in rows if r["kind"] in ("near", "far")}):
        q = [r for r in rows if r["scenario"] == sc and r["arm"] == arm and r["kind"] in ("near", "far")]
        print(f"  {sc:5s} {q[0]['kind']:4s} {arm:40s} cells {len(q)}  method over arm "
              f"{min(r['ratio'] for r in q):.3f} to {max(r['ratio'] for r in q):.3f}  "
              f"smallest gap {min(r['gap_h'] for r in q):.1f} h")
    p = plan(rows)
    steps = {(r["tree"], r["replicate"], r["scenario"]) for r in p}
    kinds = collections.Counter(r["kind"] for r in p)
    print(f"plan: {len(p)} cells in {len(steps)} (tree, scenario) pairs: method {kinds['method']}, near {kinds['near']}, "
          f"same {kinds['same']}; {sum(r['seconds'] for r in p) / 3600.0:.2f} hours by their DONE files; "
          f"left as measured: far {sum(r['kind'] == 'far' for r in rows)}, "
          f"cells of pairs without a near arm {sum(r['kind'] != 'far' for r in rows) - len(p)}")
    if plan_out:
        with open(plan_out, "w", encoding="utf-8", newline="\n") as fh:
            fh.write("tree\treplicate\tscenario\tdatabase\tarm\tkind\tseconds\n")
            for r in p:
                fh.write("\t".join(str(r[k]) for k in ("tree", "replicate", "scenario", "database", "arm", "kind",
                                                       "seconds")) + "\n")
        print(f"plan written: {plan_out}")
    if remove:
        for r in p:
            shutil.rmtree(r["path"])
        print(f"removed {len(p)} run directories")
    return 0


def self_test():
    """Synthetic trees: known kinds and plan, an undecided gap, and nothing to compare."""
    def arm(base, sc, name, when, ms, db="D"):
        d = base / sc / db / name
        d.mkdir(parents=True)
        pd.DataFrame({"iteration": [0, 1, 2], "runtime_ms": [5000, ms // 2, ms - ms // 2]}).to_csv(d / "x.csv", index=False)
        (d / "DONE").write_text(f"host=X finished={when} seconds={ms // 1000 + 5} rc=0 rows=3\n")

    def build(t, gap_b="2026-01-04 10:00:00"):
        c, r1 = t / "campaign", t / "campaign-replicates" / "s0r1"
        for base, day in ((c, "01"), (r1, "02")):
            arm(base, "SC2", METHOD, f"2026-01-{day} 10:00:00", 100000)
            arm(base, "SC2", "A-same", f"2026-01-{day} 11:00:00", 110000)       # one session
            arm(base, "SC2", "C-far", "2026-01-09 10:00:00", 900000)            # another session, 9 times slower
        arm(c, "SC2", "B-near", gap_b, 120000)                                  # another session, within the drift
        arm(r1, "SC2", "B-near", "2026-01-02 10:30:00", 120000)                 # this run of it: one session
        arm(c, "SC7", METHOD, "2026-01-01 12:00:00", 50000)                     # a pair without a near arm
        arm(c, "SC7", "E-same", "2026-01-01 12:30:00", 50000)
        arm(c, "SC7", "C-far", "2026-01-09 10:00:00", 900000)
        arm(c, "SC9", METHOD, "2026-01-01 13:00:00", 50000)                     # a long stream: left out
        arm(c, "SC9", "B-near", "2026-01-05 13:00:00", 50000)

    fails = 0

    def check(name, cond, detail=""):
        nonlocal fails
        fails += not cond
        print(("PASS " if cond else "FAIL ") + name + (f": {detail}" if detail else ""))

    with tempfile.TemporaryDirectory() as tmp:
        t = pathlib.Path(tmp) / "ok"
        build(t)
        rows = cells(t, "D", 1.6)
        kinds = collections.Counter((r["tree"], r["scenario"], r["arm"], r["kind"]) for r in rows)
        want = {("campaign", "SC2", METHOD, "method"), ("campaign", "SC2", "A-same", "same"),
                ("campaign", "SC2", "B-near", "near"), ("campaign", "SC2", "C-far", "far"),
                ("campaign-replicates", "SC2", METHOD, "method"), ("campaign-replicates", "SC2", "A-same", "same"),
                ("campaign-replicates", "SC2", "B-near", "same"), ("campaign-replicates", "SC2", "C-far", "far"),
                ("campaign", "SC7", METHOD, "method"), ("campaign", "SC7", "E-same", "same"),
                ("campaign", "SC7", "C-far", "far")}
        check("kinds of the cells", set(kinds) == want and all(v == 1 for v in kinds.values()),
              str(sorted(set(kinds) ^ want)))
        p = {(r["tree"], r["scenario"], r["arm"]) for r in plan(rows)}
        check("plan: method, near and same of the pair with a near arm, nothing else",
              p == {("campaign", "SC2", METHOD), ("campaign", "SC2", "A-same"), ("campaign", "SC2", "B-near")}, str(sorted(p)))
        out = pathlib.Path(tmp) / "plan.tsv"
        rc = report(t, "D", 1.6, plan_out=str(out), remove=True)
        left = sorted(d.relative_to(t).as_posix() for d in t.glob("*/SC2/D/*")) + \
            sorted(d.relative_to(t).as_posix() for d in t.glob("*/*/SC2/D/*"))
        check("--remove deletes the planned cells only",
              rc == 0 and left == ["campaign/SC2/D/C-far", "campaign-replicates/s0r1/SC2/D/A-same",
                                   "campaign-replicates/s0r1/SC2/D/B-near", "campaign-replicates/s0r1/SC2/D/C-far",
                                   f"campaign-replicates/s0r1/SC2/D/{METHOD}"], str(left))
        check("plan file has the three cells", len(out.read_text().splitlines()) == 4)
        t2 = pathlib.Path(tmp) / "twelve"
        build(t2, gap_b="2026-01-01 22:00:00")
        k2 = {(r["tree"], r["scenario"], r["arm"]): r["kind"] for r in cells(t2, "D", 1.6)}
        check("a gap of twelve hours is another session", k2[("campaign", "SC2", "B-near")] == "near",
              k2[("campaign", "SC2", "B-near")])
        t4 = pathlib.Path(tmp) / "five"
        build(t4, gap_b="2026-01-01 15:00:00")
        k4 = {(r["tree"], r["scenario"], r["arm"]): r["kind"] for r in cells(t4, "D", 1.6)}
        check("a gap of five hours is one session", k4[("campaign", "SC2", "B-near")] == "same" and plan(cells(t4, "D", 1.6)) == [],
              k4[("campaign", "SC2", "B-near")])
        check("another database: nothing to compare, exit 1", report(t, "OTHER", 1.6) == 1)
        # with a narrow drift the near arm is far and nothing is planned
        t3 = pathlib.Path(tmp) / "narrow"
        build(t3)
        check("drift 1.1: no near arm, empty plan", plan(cells(t3, "D", 1.1)) == [])
    print(f"self-test failed: {fails}")
    return 1 if fails else 0


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--db")
    ap.add_argument("--drift", type=float)
    ap.add_argument("--plan-out")
    ap.add_argument("--remove", action="store_true")
    ap.add_argument("--results", default=str(ROOT / "results"))
    ap.add_argument("--self-test", action="store_true")
    a = ap.parse_args()
    if a.self_test:
        return self_test()
    if not a.db or not a.drift or a.drift <= 1:
        ap.error("give --db and --drift (a factor above 1), or --self-test")
    return report(pathlib.Path(a.results), a.db, a.drift, a.plan_out, a.remove)


if __name__ == "__main__":
    sys.exit(main())
