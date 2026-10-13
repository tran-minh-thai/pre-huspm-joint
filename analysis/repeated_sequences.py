#!/usr/bin/env python3
"""Long sequences that occur many times, nearly unchanged, and the share of the total utility
they carry, from the data files.

    python3 analysis/repeated_sequences.py [--datasets DIR] [--out DIR] [--min-length N] DB [D_INIT]

Groups the sequences of at least --min-length items (default 100) by their item sequence
(quantities ignored), then takes the most repeated group and finds every long sequence that
contains its item sequence as a subsequence. Reports, for each group, its length, copies, how
many lie in D_init (the first D_INIT sequences), its utility as a share of TU(D), and whether it
contains the most repeated item sequence; and the total utility of all sequences that contain
it. A pattern drawn from that item sequence is supported by every one of them, so its utility
can reach that total. Utilities are computed as common.DatasetLoader computes them.

Writes <out>/<DB>_repeated.csv and prints the same.
"""
import argparse
import collections
import csv
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent


def load(db, data_dir):
    eui = {}
    for line in (data_dir / f"{db}_eui.txt").read_text(encoding="utf-8").splitlines():
        t = line.strip()
        if t and not t.startswith(("#", "%")) and t.count(":") == 1:
            a, b = t.split(":")
            eui[int(a)] = float(b)
    seqs = []
    for line in (data_dir / f"{db}_seq.txt").read_text(encoding="utf-8").splitlines():
        t = line.strip()
        if not t or t.startswith(("#", "%", "@")):
            continue
        toks = [x for x in t.split() if "[" in x and "]" in x]
        if not toks:
            continue
        items = tuple(int(x[:x.index("[")]) for x in toks)
        util = sum(float(x[x.index("[") + 1:x.index("]")]) * eui.get(int(x[:x.index("[")]), 0.0) for x in toks)
        seqs.append((items, util))
    return seqs


def contains(seq, sub):
    """Whether sub is a subsequence of seq."""
    it = iter(seq)
    return all(x in it for x in sub)


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--datasets", default=str(ROOT / "datasets"))
    ap.add_argument("--out", default=str(ROOT / "results" / "invariant-repeated-sequences"))
    ap.add_argument("--min-length", type=int, default=100)
    ap.add_argument("db")
    ap.add_argument("d_init", type=int, nargs="?", default=0)
    a = ap.parse_args(argv)
    seqs = load(a.db, pathlib.Path(a.datasets))
    tu = sum(u for _, u in seqs)
    groups = collections.defaultdict(list)
    for pos, (items, u) in enumerate(seqs):
        if len(items) >= a.min_length:
            groups[items].append((pos, u))
    if not groups:
        print(f"{a.db}: no sequence of at least {a.min_length} items")
        return 0
    core = max(groups, key=lambda k: (len(groups[k]), sum(u for _, u in groups[k])))
    rows = []
    for items, mem in sorted(groups.items(), key=lambda kv: (-len(kv[1]), -len(kv[0]))):
        rows.append(dict(length=len(items), copies=len(mem),
                         in_d_init=sum(1 for p, _ in mem if p < a.d_init),
                         first=min(p for p, _ in mem), last=max(p for p, _ in mem),
                         utility=round(sum(u for _, u in mem), 3),
                         utility_pct=round(100 * sum(u for _, u in mem) / tu, 3),
                         contains_core=contains(items, core)))
    out = pathlib.Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    with open(out / f"{a.db}_repeated.csv", "w", newline="", encoding="utf-8") as fh:
        w = csv.DictWriter(fh, fieldnames=list(rows[0]), lineterminator="\n")
        w.writeheader()
        w.writerows(rows)
    print(",".join(rows[0]))
    for r in rows:
        print(",".join(str(v) for v in r.values()))
    holders = [u for items, u in seqs if contains(items, core)]
    print(f"{a.db}: TU(D) {tu:.0f}; the most repeated long item sequence has {len(core)} items, occurs "
          f"{len(groups[core])} times and is contained in {len(holders)} sequences "
          f"({sum(1 for pos, (items, _) in enumerate(seqs) if pos < a.d_init and contains(items, core))} in D_init) "
          f"holding {sum(holders):.0f} = {100 * sum(holders) / tu:.2f}% of TU(D)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
