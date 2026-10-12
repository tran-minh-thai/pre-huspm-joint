#!/usr/bin/env python3
"""Check that the correctness tests can fail: inject a fault into a copy of the joint
rule, compile the copy, and run the tests on it.

    python3 analysis/fault_injection.py [--trials N] [--seed S] [--real DB[,DB...]]

Each fault is one textual substitution in src/algorithms/PreHUSPMAdaptiveAlgorithm.java.
The substitution must match exactly once, and the faulty text must be present in the
copy afterwards; otherwise the script stops, since a fault that did not land would make
a passing test look like a blind one. The copy is compiled into a temporary directory;
src/ is never touched.

For each fault it runs verify.MixedBudgetFuzz (random streams on small databases) and
verify.MixedBudgetProbe (the hand-built cases) and prints their verdicts; with --real it also
runs verify.DirectPathVerifier --adversarial on the named databases of datasets/. Exit status is
0 when every fault is caught by at least one of them, 1 otherwise, and 2 when a run on a
database did not finish (it is named, and counts as catching nothing).

    python3 analysis/fault_injection.py --item-removal [--trials N] [--seed S]

injects faults into irrelevant-item removal instead and runs verify.ItemRemovalCheck random
(brute-force reference) on each, with both child-building strategies; the unmodified copy is
run first and must pass, so a check that fails on everything is not read as catching.
"""
import argparse
import pathlib
import shutil
import subprocess
import sys
import tempfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
TARGET = "src/algorithms/PreHUSPMAdaptiveAlgorithm.java"

FAULTS = [
    ("trigger without the deletion term",
     "return iauMax - Su * insertedSinceRescan + Su * deletedSinceRescan;",
     "return iauMax - Su * insertedSinceRescan;"),
    ("drop floor with the largest counter instead of the pattern's smallest",
     "return Sl_min * tsuAtLastRescan + minIauOf(p);",
     "return Sl_min * tsuAtLastRescan + iauMax;"),
]

FAULTS_IIP = [
    ("removal threshold raised by 30 percent",
     "if (iipAcc[it] < theta) {",
     "if (iipAcc[it] < theta * 1.3) {"),
    ("removals of a node not undone on return",
     "while (iipTop > mark) iipRemoved[iipStack[--iipTop]] = false;",
     "if (mark == 0) while (iipTop > mark) iipRemoved[iipStack[--iipTop]] = false;"),
    ("bound PEU^R with half the remaining utility",
     "double v = tUtil[k] + ru[tFlat[k]];",
     "double v = tUtil[k] + 0.5 * ru[tFlat[k]];"),
    ("bound summed over the sequences of the first occurrence only",
     "for (int x = 0; x < nLocal; x++) iipAccAdd(local[x], best);",
     "for (int x = 0; x < nLocal; x++) iipAccAdd(local[x], tUtil[0] + ru[tFlat[0]]);"),
]


def copy_build(tmp, old=None, new=None, name="unmodified"):
    shutil.copytree(ROOT / "src", tmp / "src")
    if old is not None:
        f = tmp / TARGET
        text = f.read_text(encoding="utf-8")
        n = text.count(old)
        if n != 1:
            sys.exit(f"fault '{name}': the original text occurs {n} times, expected 1")
        text = text.replace(old, new)
        if text.count(new) != 1:
            sys.exit(f"fault '{name}': the faulty text is not in the copy")
        f.write_text(text, encoding="utf-8")
    sources = [str(p) for p in sorted((tmp / "src").rglob("*.java"))]
    rc, out = run(["javac", "-encoding", "UTF-8", "-nowarn", "-d", str(tmp / "build"), *sources], tmp)
    if rc != 0:
        sys.exit(f"fault '{name}': the copy does not compile\n{out}")


def item_removal(trials, seed):
    caught_all = True
    for name, old, new in [("none (control)", None, None)] + FAULTS_IIP:
        with tempfile.TemporaryDirectory() as tmp:
            tmp = pathlib.Path(tmp)
            copy_build(tmp, old, new, name)
            print(f"fault: {name}")
            if old is not None:
                print(f"  replaced: {old}")
                print(f"      with: {new}")
            failed = False
            for cap in ("default", "0"):
                cmd = ["java", "-Xmx4g", "-cp", "build"]
                if cap == "0":
                    cmd.append("-Dadapt.singlePassCap=0")
                rc, out = run(cmd + ["verify.ItemRemovalCheck", "random", str(trials), str(seed)], tmp)
                failed |= rc != 0
                for l in out.splitlines():
                    if l.startswith("  once") or l.startswith("  fixpoint") or l.startswith("RESULT"):
                        print(f"  singlePassCap={cap}: {l.strip()[:150]}")
            if old is None:
                print(f"  control passes: {not failed}")
                caught_all &= not failed
            else:
                print(f"  caught: {failed}")
                caught_all &= failed
    sys.exit(0 if caught_all else 1)


def run(cmd, cwd):
    p = subprocess.run(cmd, cwd=cwd, capture_output=True, text=True)
    return p.returncode, p.stdout + p.stderr


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--trials", type=int, default=2000)
    ap.add_argument("--seed", type=int, default=7)
    ap.add_argument("--real", default="", help="comma-separated databases for the adversarial run")
    ap.add_argument("--item-removal", action="store_true")
    a = ap.parse_args()
    if a.item_removal:
        item_removal(a.trials, a.seed)

    caught_all = True
    unfinished = []
    for name, old, new in FAULTS:
        with tempfile.TemporaryDirectory() as tmp:
            tmp = pathlib.Path(tmp)
            shutil.copytree(ROOT / "src", tmp / "src")
            f = tmp / TARGET
            text = f.read_text(encoding="utf-8")
            n = text.count(old)
            if n != 1:
                sys.exit(f"fault '{name}': the original text occurs {n} times, expected 1")
            text = text.replace(old, new)
            if text.count(new) != 1:
                sys.exit(f"fault '{name}': the faulty text is not in the copy")
            f.write_text(text, encoding="utf-8")
            sources = [str(p) for p in sorted((tmp / "src").rglob("*.java"))]
            rc, out = run(["javac", "-encoding", "UTF-8", "-nowarn", "-d", str(tmp / "build"), *sources], tmp)
            if rc != 0:
                sys.exit(f"fault '{name}': the copy does not compile\n{out}")
            rc_f, out_f = run(["java", "-cp", "build", "verify.MixedBudgetFuzz", "joint",
                               str(a.trials), str(a.seed)], tmp)
            rc_p, out_p = run(["java", "-cp", "build", "verify.MixedBudgetProbe", "joint"], tmp)
            real_lines = []
            real_caught = False
            if a.real:
                (tmp / "datasets").symlink_to(ROOT / "datasets")
                for db in a.real.split(","):
                    rc_r, out_r = run(["java", "-Xmx12g", "-Xss64m", "-cp", "build",
                                       "verify.DirectPathVerifier", db, "--adversarial", "--ops", "16",
                                       "--out", str(tmp / "real" / db)], tmp)
                    mm = [l for l in out_r.splitlines() if l.startswith("mismatching operations")]
                    if not mm:
                        # A run that did not finish has caught nothing; it is named, not counted.
                        unfinished.append(f"{name} / {db}")
                        real_lines.append(f"{db}: did not finish (exit {rc_r}): {out_r.strip()[-200:]}")
                        continue
                    real_lines.append(f"{db}: {mm[0]}")
                    real_caught |= int(mm[0].split(":")[1]) > 0
            fuzz_line = [l for l in out_f.splitlines() if l.startswith("rule=")]
            probe_line = [l for l in out_p.splitlines() if "cases with a mismatch" in l]
            caught = rc_f != 0 or rc_p != 0 or real_caught
            caught_all &= caught
            print(f"fault: {name}")
            print(f"  replaced: {old}")
            print(f"      with: {new}")
            print(f"  fuzz : {fuzz_line[0] if fuzz_line else out_f.strip()[-300:]}")
            print(f"  probe: {probe_line[0] if probe_line else out_p.strip()[-300:]}")
            for line in real_lines:
                print(f"  real : {line}")
            by = [k for k, v in (("fuzz", rc_f != 0), ("probe", rc_p != 0), ("real", real_caught)) if v]
            print(f"  caught: {caught}" + (f" (by {', '.join(by)})" if by else ""))
    if unfinished:
        print("runs that did not finish: " + "; ".join(unfinished))
        sys.exit(2)
    sys.exit(0 if caught_all else 1)


if __name__ == "__main__":
    main()
