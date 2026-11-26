#!/usr/bin/env python3
"""Run the correctness records again, with their own commands, on the code of HEAD.

    python3 analysis/rerun_records.py --tree results/verify-joint/exact-arithmetic-2026-11-26 \
        [--max-hours 1.0 | --min-hours 1.0] [--only NAME,...] [--list]

The records are those of the "Records read" table of results/verify-joint/README.md, the ones
the paper's numbers are read from. For each, the command stored in its run_meta.json is run again
through analysis/record_run.py, with three changes and no other:
    * every path results/verify-joint/<record> of a record in the table becomes <tree>/<record>,
      so outputs, and the inputs of the check-* records, are the records of the new tree;
    * every class path (-cp DIR) becomes the build directory of this run, compiled from HEAD;
    * a leading ~ becomes the home directory.
The rewritten command is printed before it runs, and record_run stores it in the new record.

--max-hours / --min-hours select by the duration of the old record, so that the short ones can
run on one machine and the long ones on another. A record that already has a run_meta.json with
exit code 0 and the source digest of the working tree is skipped (the same src/, whatever the
commit), so that running the same command again continues, on this machine or another one.
A check-* record runs after the records it reads, and only when they are in the new tree;
--exclude-checks leaves them out (they read the other records and run in seconds).

Portability: a command that started with python3 or with a virtual environment's python runs with
the interpreter of this script; a bash -c command runs with the bash of Git for Windows on Windows
(found from git --exec-path), so that the WSL launcher is never taken for it.
"""
import argparse
import datetime
import json
import os
import pathlib
import re
import shlex
import shutil
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
VERIFY = "results/verify-joint"


def records():
    text = (ROOT / VERIFY / "README.md").read_text(encoding="utf-8").split("## Records read", 1)[1]
    out = []
    for line in text.splitlines():
        if line.startswith("|") and "---" not in line:
            cell = line.split("|")[2].strip()
            if cell and cell != "record read":
                out.append(cell)
    if not out:
        sys.exit("no record found in the Records read table of " + VERIFY + "/README.md")
    return out


def meta(rec):
    return json.loads((ROOT / VERIFY / rec / "run_meta.json").read_text(encoding="utf-8"))


def hours(m):
    return (datetime.datetime.fromisoformat(m["end"]) - datetime.datetime.fromisoformat(m["start"])).total_seconds() / 3600


def rewrite(command, recs, tree, build):
    c = command
    for r in sorted(recs, key=len, reverse=True):
        c = re.sub(re.escape(f"{VERIFY}/{r}") + r"(?=[/\s]|$)", f"{tree}/{r}", c)
    # a path to a record outside the table whose name is the last part of a record in the table (the
    # check-* records read husps-mixed-break-even-nodes, the table holds initial-cycle-.../husps-mixed-...)
    by_leaf = {}
    for r in recs:
        by_leaf.setdefault(os.path.basename(r), []).append(r)
    def leaf(m):
        x = m.group(1)
        if x in recs or len(by_leaf.get(x, [])) != 1:
            return m.group(0)
        return f"{tree}/{by_leaf[x][0]}"
    c = re.sub(re.escape(VERIFY) + r"/([^\s/]+)(?=[/\s]|$)", leaf, c)
    c = re.sub(r"-cp \S+", f"-cp {build}", c)
    if c.startswith("bash -c "):
        return c, [bash(), "-c", c[len("bash -c "):]]
    argv = shlex.split(c)
    if argv[0] == "python3" or re.search(r"[/\\]\.?venvs?[/\\].*python[0-9.]*$", argv[0]):
        argv[0] = sys.executable
        c = " ".join(["python", *argv[1:]])
    return c, argv


def bash():
    if os.name == "nt":
        core = pathlib.Path(subprocess.check_output(["git", "--exec-path"], text=True).strip())
        for b in (core.parents[2] / "bin" / "bash.exe", core.parents[1] / "bin" / "bash.exe"):
            if b.exists():
                return str(b)
        sys.exit(f"no bash of Git for Windows next to {core}")
    b = shutil.which("bash")
    if not b:
        sys.exit("bash not found")
    return b


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--tree", required=True)
    ap.add_argument("--max-hours", type=float)
    ap.add_argument("--min-hours", type=float)
    ap.add_argument("--only", help="comma-separated record names")
    ap.add_argument("--build", default=None, help="build directory (default: build-rerun)")
    ap.add_argument("--list", action="store_true", help="print the selection and the commands, run nothing")
    ap.add_argument("--exclude-checks", action="store_true", help="leave out the check-* records")
    a = ap.parse_args()
    tree = a.tree.rstrip("/")
    recs = records()
    sys.path.insert(0, str(ROOT / "analysis"))
    import record_run
    digest = record_run.source_digest()[0]
    build = a.build or "build-rerun"

    chosen = []
    for r in recs:
        m = meta(r)
        h = hours(m)
        if a.only and r not in a.only.split(","):
            continue
        if a.exclude_checks and os.path.basename(r).startswith("check-"):
            continue
        if a.max_hours is not None and h > a.max_hours:
            continue
        if a.min_hours is not None and h <= a.min_hours:
            continue
        chosen.append((r, h, m["command"]))
    # check-* records read other records: run them last, and only when what they read is in the tree
    chosen.sort(key=lambda x: os.path.basename(x[0]).startswith("check-"))

    done = failed = skipped = 0
    for r, h, command in chosen:
        new, argv = rewrite(command, recs, tree, build)
        out = ROOT / tree / r
        reads = [x for x in recs if x != r and re.search(re.escape(f"{tree}/{x}") + r"(?=[/\s]|$)", new)]
        print(f"== {r}  (old record {h:.2f} h)\n   {new}", flush=True)
        if a.list:
            continue
        mf = out / "run_meta.json"
        if mf.exists():
            mm = json.loads(mf.read_text(encoding="utf-8"))
            if mm.get("exit_code") == 0 and mm.get("source_sha256") == digest:
                print("   already recorded on this src/ with exit 0: skipped", flush=True)
                skipped += 1
                continue
        missing = [x for x in reads if not (ROOT / tree / x / "run_meta.json").exists()]
        if missing:
            print(f"   reads {missing}, not in the tree yet: skipped", flush=True)
            skipped += 1
            continue
        p = subprocess.run([sys.executable, "analysis/record_run.py", "--out", f"{tree}/{r}", "--build", build,
                            "--", *argv], cwd=ROOT)
        if p.returncode == 0:
            done += 1
        else:
            failed += 1
            print(f"   exit {p.returncode}", flush=True)
    print(f"selected {len(chosen)} of {len(recs)} records; ran {done} with exit 0, {failed} with another exit, "
          f"{skipped} skipped")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
