#!/usr/bin/env python3
"""Run one command and keep what is needed to trust its output later.

    python3 analysis/record_run.py --out results/verify-joint/random -- \
        java -Xmx16g -Xss64m -cp build verify.DirectPathVerifier ALL --out results/verify-joint/random

Refuses to run from a working tree with uncommitted changes to src/: the commit hash written
next to the output would then describe code that did not run. Compiles src/ into build/
first (or into --build), so the classes that run are the classes of that commit.

Writes into --out:
    console.txt     everything the command printed
    run_meta.json   command, commit, source digest, JVM, OS, machine, start, end, exit code
"""
import argparse
import datetime
import hashlib
import json
import pathlib
import platform
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent


def git(*args):
    return subprocess.check_output(["git", *args], cwd=ROOT, text=True).strip()


def source_digest():
    h = hashlib.sha256()
    files = sorted(p for p in (ROOT / "src").rglob("*.java"))
    for p in files:
        h.update(p.relative_to(ROOT).as_posix().encode())
        h.update(b"\0")
        h.update(p.read_bytes())
    return h.hexdigest(), len(files)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    ap.add_argument("--build", default="build",
                    help="directory to compile into; give each concurrent run its own")
    ap.add_argument("cmd", nargs=argparse.REMAINDER)
    a = ap.parse_args()
    cmd = a.cmd[1:] if a.cmd and a.cmd[0] == "--" else a.cmd
    if not cmd:
        sys.exit("no command given")

    dirty = git("status", "--porcelain", "--", "src")
    if dirty:
        sys.exit("refusing to run: src/ has uncommitted changes\n" + dirty)

    build = ROOT / a.build
    build.mkdir(exist_ok=True)
    sources = [str(p) for p in sorted((ROOT / "src").rglob("*.java"))]
    subprocess.check_call(["javac", "-encoding", "UTF-8", "-nowarn", "-d", str(build), *sources],
                          cwd=ROOT, stderr=subprocess.DEVNULL)

    out = ROOT / a.out
    out.mkdir(parents=True, exist_ok=True)
    digest, nfiles = source_digest()
    java = subprocess.run(["java", "-version"], capture_output=True, text=True).stderr.splitlines()
    meta = {
        "command": " ".join(cmd),
        "build_dir": a.build,
        "git_commit": git("rev-parse", "HEAD"),
        "tree": "clean" if not git("status", "--porcelain") else "src clean, other files modified",
        "source_sha256": digest,
        "source_files": nfiles,
        "java": java[0] if java else "",
        "os": f"{platform.system()} {platform.release()} ({platform.platform()})",
        "machine": platform.node(),
        "role": "development machine: counters only, no timings",
        "start": datetime.datetime.now().isoformat(timespec="seconds"),
    }
    with open(out / "console.txt", "w", encoding="utf-8", newline="\n") as f:
        p = subprocess.run(cmd, cwd=ROOT, stdout=f, stderr=subprocess.STDOUT)
    meta["end"] = datetime.datetime.now().isoformat(timespec="seconds")
    meta["exit_code"] = p.returncode
    with open(out / "run_meta.json", "w", encoding="utf-8", newline="\n") as f:
        json.dump(meta, f, indent=2)
        f.write("\n")
    print(f"exit {p.returncode}; {out.relative_to(ROOT).as_posix()}/console.txt")
    sys.exit(p.returncode)


if __name__ == "__main__":
    main()
