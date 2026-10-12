#!/usr/bin/env python3
"""Regenerate every table, figure and number from the raw results and compare them with a
directory that claims to hold them, such as the copy a manuscript includes.

    python3 analysis/check_generated.py DIR

The raw results are the per-arm CSV files the Java runner writes under results/ during a
measurement, and the counted artifacts under results/invariant-*; analysis/make_figures.py and
analysis/scenario_tables.py read only those. They are run here into a fresh directory, and
each generated file is compared with the file of the same name in DIR: CSV and LaTeX byte for
byte, PDF on its decompressed content streams (a PDF also records the time it was written).

Exits with code 1 when a file differs or is missing from DIR, and lists both.
"""
import os
import pathlib
import re
import subprocess
import sys
import tempfile
import zlib

ROOT = pathlib.Path(__file__).resolve().parent.parent


def pdf_streams(path):
    data = path.read_bytes()
    out = []
    for m in re.finditer(rb"stream\r?\n(.*?)endstream", data, re.S):
        try:
            out.append(zlib.decompress(m.group(1)))
        except zlib.error:
            out.append(m.group(1))
    return out


def same(a, b):
    if a.suffix == ".pdf":
        return pdf_streams(a) == pdf_streams(b)
    return a.read_bytes() == b.read_bytes()


def main(argv):
    if len(argv) != 1:
        sys.exit(__doc__)
    target = pathlib.Path(argv[0])
    if not target.is_dir():
        sys.exit(f"{target} is not a directory")
    with tempfile.TemporaryDirectory() as tmp:
        env = dict(os.environ, HUSP_OUT_DIR=tmp, PYTHONDONTWRITEBYTECODE="1")
        code = ("import sys; sys.path.insert(0, 'analysis'); import make_figures as m; m.main_all(); "
                "import scenario_tables as s; s.export_all()")
        run = subprocess.run([sys.executable, "-c", code], cwd=ROOT, env=env,
                             capture_output=True, text=True)
        if run.returncode != 0:
            print(run.stdout[-2000:], run.stderr[-2000:])
            sys.exit(f"regeneration failed with exit code {run.returncode}")
        gen = sorted(p for p in pathlib.Path(tmp).iterdir() if p.suffix in (".csv", ".tex", ".pdf"))
        differ = [p.name for p in gen if (target / p.name).exists() and not same(p, target / p.name)]
        missing = [p.name for p in gen if not (target / p.name).exists()]
    print(f"{len(gen)} generated files; {len(gen) - len(differ) - len(missing)} identical in {target}, "
          f"{len(differ)} differ, {len(missing)} missing")
    for n in differ:
        print(f"  differs: {n}")
    for n in missing:
        print(f"  missing: {n}")
    return 1 if differ or missing else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
