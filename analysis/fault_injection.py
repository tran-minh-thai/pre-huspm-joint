#!/usr/bin/env python3
"""Check that the correctness tests can fail: inject a fault into a copy of the joint
rule, compile the copy, and run the tests on it.

    python3 analysis/fault_injection.py [--trials N] [--seed S]

Each fault is one textual substitution in src/algorithms/PreHUSPMAdaptiveAlgorithm.java.
The substitution must match exactly once, and the faulty text must be present in the
copy afterwards; otherwise the script stops, since a fault that did not land would make
a passing test look like a blind one. The copy is compiled into a temporary directory;
src/ is never touched.

For each fault it runs verify.MixedBudgetFuzz (random streams on small databases) and
verify.MixedBudgetProbe (the hand-built cases) and prints their verdicts. Exit status is
0 when every fault is caught by at least one of the two, 1 otherwise.
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


def run(cmd, cwd):
    p = subprocess.run(cmd, cwd=cwd, capture_output=True, text=True)
    return p.returncode, p.stdout + p.stderr


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--trials", type=int, default=2000)
    ap.add_argument("--seed", type=int, default=7)
    a = ap.parse_args()

    caught_all = True
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
            fuzz_line = [l for l in out_f.splitlines() if l.startswith("rule=")]
            probe_line = [l for l in out_p.splitlines() if "cases with a mismatch" in l]
            caught = rc_f != 0 or rc_p != 0
            caught_all &= caught
            print(f"fault: {name}")
            print(f"  replaced: {old}")
            print(f"      with: {new}")
            print(f"  fuzz : {fuzz_line[0] if fuzz_line else out_f.strip()[-300:]}")
            print(f"  probe: {probe_line[0] if probe_line else out_p.strip()[-300:]}")
            print(f"  caught: {caught}")
    sys.exit(0 if caught_all else 1)


if __name__ == "__main__":
    main()
