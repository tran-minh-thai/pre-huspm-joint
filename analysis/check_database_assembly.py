#!/usr/bin/env python3
"""Assemble the seven SPMF databases from scratch and compare them with the files the results
were measured on.

    python3 analysis/check_database_assembly.py --out results/invariant-dataset-assembly/<date>

In a temporary directory: downloads release v2.0.0-utilities of huspm-datasets (the generated
quantities and profits only) and checks it against the checksum pinned in fetch-datasets.sh,
downloads the SPMF sequence files named in its SOURCES.tsv, and assembles the databases with
the release's tools/assemble.py. Each assembled file is then compared with two references:
datasets.sha256 in this repository, and the MANIFEST.sha256 of release v1.2.1-measured-quantity,
the release the measurements read their files from.

Writes assembly.csv (one row per file) and prints one summary line per reference. Exits with
code 1 when any file is missing or differs. --base replaces the release address (for a mirror).
"""
import argparse
import csv
import hashlib
import os
import pathlib
import re
import subprocess
import sys
import tarfile
import tempfile
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent.parent
REPO = "https://github.com/tran-minh-thai/huspm-datasets/releases/download"
RELEASE = "v2.0.0-utilities"
MEASURED = "v1.2.1-measured-quantity"
AGENT = {"User-Agent": "pre-huspm-joint-check/1.0"}


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def fetch(url, path):
    with urllib.request.urlopen(urllib.request.Request(url, headers=AGENT), timeout=120) as r, open(path, "wb") as f:
        f.write(r.read())


def sums(text):
    return {m.group(2): m.group(1) for m in re.finditer(r"^([0-9a-f]{64})\s+(\S+)\s*$", text, flags=re.M)}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    ap.add_argument("--base", default=f"{REPO}/{RELEASE}")
    a = ap.parse_args()
    out = ROOT / a.out
    out.mkdir(parents=True, exist_ok=True)
    pinned_archive = re.search(r'^ARCHIVE_SHA256="([0-9a-f]{64})"', (ROOT / "fetch-datasets.sh").read_text(), flags=re.M).group(1)
    pinned = sums((ROOT / "datasets.sha256").read_text())
    with tempfile.TemporaryDirectory() as tmp:
        tmp = pathlib.Path(tmp)
        archive = tmp / f"huspm-datasets-{RELEASE}.tar.gz"
        fetch(f"{a.base}/{archive.name}", archive)
        have = sha256(archive)
        print(f"release archive {archive.name}: SHA-256 {'matches' if have == pinned_archive else 'DIFFERS from'} "
              f"the checksum pinned in fetch-datasets.sh")
        if have != pinned_archive:
            return 1
        with tarfile.open(archive) as t:
            t.extractall(tmp, filter="data")
        rel = tmp / f"huspm-datasets-{RELEASE}"
        p = subprocess.run([sys.executable, str(rel / "tools" / "assemble.py"), "--release", str(rel),
                            "--spmf", str(tmp / "spmf"), "--out", str(tmp / "db"), "--download"],
                           capture_output=True, text=True)
        print((p.stdout + p.stderr).replace(str(tmp), "<tmp>").rstrip())
        measured_manifest = tmp / "MANIFEST.sha256"
        fetch(f"{REPO}/{MEASURED}/MANIFEST.sha256", measured_manifest)
        measured = sums(measured_manifest.read_text())
        spmf = {r["name"]: r for r in csv.DictReader(open(rel / "SOURCES.tsv", encoding="utf-8"), delimiter="\t")}
        rows = []
        for name in sorted(pinned):
            path = tmp / "db" / name
            got = sha256(path) if path.exists() else ""
            rows.append(dict(file=name, assembled_sha256=got, pinned_sha256=pinned[name],
                             measured_release_sha256=measured.get(name, ""),
                             identical_to_pinned=got == pinned[name],
                             identical_to_measured_release=got == measured.get(name)))
        spmf_rows = [dict(file=r["name"], spmf_url=r["url"], spmf_sha256=sha256(tmp / "spmf" / f"{r['name']}.txt")
                          if (tmp / "spmf" / f"{r['name']}.txt").exists() else "",
                          spmf_sha256_in_release=r["spmf_sha256"]) for r in spmf.values()]
    with open(out / "assembly.csv", "w", encoding="utf-8", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0]))
        w.writeheader()
        w.writerows(rows)
    with open(out / "spmf_files.csv", "w", encoding="utf-8", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(spmf_rows[0]))
        w.writeheader()
        w.writerows(spmf_rows)
    n = len(rows)
    a_pin = sum(r["identical_to_pinned"] for r in rows)
    a_meas = sum(r["identical_to_measured_release"] for r in rows)
    s_ok = sum(r["spmf_sha256"] == r["spmf_sha256_in_release"] for r in spmf_rows)
    print(f"SPMF files matching the release checksums: {s_ok} of {len(spmf_rows)}")
    print(f"assembled files identical to datasets.sha256: {a_pin} of {n}")
    print(f"assembled files identical to release {MEASURED}: {a_meas} of {n}")
    return 0 if p.returncode == 0 and a_pin == n and a_meas == n and s_ok == len(spmf_rows) else 1


if __name__ == "__main__":
    sys.exit(main())
