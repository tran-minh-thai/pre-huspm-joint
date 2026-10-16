#!/usr/bin/env python3
"""Convert SPMF's high-utility sequence files (item[utility] tokens, -1 closing an element, -2 the
sequence, then `SUtility:<total>`) to this repository's format: NAME_seq.txt with
item[quantity] tokens and NAME_eui.txt with item:profit lines. Every profit is 1 and every
quantity is the occurrence's utility, so each occurrence keeps its utility exactly.

    python3 analysis/convert_spmf_husp.py --src datasets/spmf-husp --out datasets/spmf-husp/converted

Each line's utilities must add up to its SUtility; a line where they do not is reported and the
conversion fails. Files are named by the database names of Yan et al. (2025): SIGN, FIFA, BIBLE,
KOSARAK10K, BMS.
"""
import argparse
import os
import re
import sys

FILES = {"SIGN": "SIGN_sequence_utility.txt", "FIFA": "FIFA_sequence_utility.txt",
         "BIBLE": "BIBLE_sequence_utility.txt", "KOSARAK10K": "kosarak10k_sequence_utility.txt",
         "BMS": "BMS_sequence_utility.txt"}
TOKEN = re.compile(r"^(\d+)\[(\d+(?:\.\d+)?)\]$")


def convert_line(line):
    """(tokens in the repository format, total utility, SUtility) of one SPMF line."""
    body, _, tail = line.partition("SUtility:")
    if not tail:
        raise ValueError("no SUtility on the line")
    out, total = [], 0.0
    for tok in body.split():
        if tok in ("-1", "-2"):
            out.append(tok)
            continue
        m = TOKEN.match(tok)
        if not m:
            raise ValueError(f"unreadable token {tok!r}")
        u = float(m.group(2))
        total += u
        out.append(f"{m.group(1)}[{m.group(2)}]")
    return out, total, float(tail.strip())


def self_test():
    toks, total, su = convert_line(" 5[45] -1 15[3] 7[2] -1 -2  SUtility:50")
    assert toks == ["5[45]", "-1", "15[3]", "7[2]", "-1", "-2"] and total == 50 and su == 50
    try:
        convert_line("5[x] -1 -2 SUtility:1")
        raise AssertionError("an unreadable token was accepted")
    except ValueError:
        pass
    return 2


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--src", required=True)
    ap.add_argument("--out", required=True)
    a = ap.parse_args(argv)
    print(f"self-test: {self_test()} checks passed")
    os.makedirs(a.out, exist_ok=True)
    bad_total = 0
    for name, fname in FILES.items():
        items, n, mism, tu = set(), 0, 0, 0.0
        with open(os.path.join(a.src, fname), encoding="utf-8") as fh, \
                open(os.path.join(a.out, f"{name}_seq.txt"), "w", encoding="utf-8", newline="\n") as out:
            for line in fh:
                if not line.strip():
                    continue
                toks, total, su = convert_line(line)
                if abs(total - su) > 1e-9:
                    mism += 1
                items.update(int(t.split("[")[0]) for t in toks if "[" in t)
                out.write(" ".join(toks) + "\n")
                n += 1
                tu += total
        with open(os.path.join(a.out, f"{name}_eui.txt"), "w", encoding="utf-8", newline="\n") as fh:
            fh.write("# itemID:profit, 1 for every item: the quantities are the occurrence utilities of "
                     + fname + "\n")
            for i in sorted(items):
                fh.write(f"{i}:1\n")
        bad_total += mism
        print(f"{name}: {n} sequences, {len(items)} items, total utility {tu:.0f}, "
              f"lines whose utilities differ from SUtility: {mism}")
    return 1 if bad_total else 0


if __name__ == "__main__":
    sys.exit(main())
