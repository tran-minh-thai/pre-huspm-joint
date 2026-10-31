"""Every pattern of the one-sequence database counterexample.txt with its utility, by enumeration of the
subsequences (each element holds one item); prints the HUSPs at a threshold and compares them with the
patterns the published HUSP-SP code counted (the output of WatchHusps)."""
import re
import sys
from itertools import combinations

seq = [("1", 1), ("2", 1), ("1", 50), ("3", 1), ("2", 1), ("3", 1)]   # a=1, b=2, g=3
util = {}
for k in range(1, len(seq) + 1):
    for idx in combinations(range(len(seq)), k):
        p = tuple(seq[i][0] for i in idx)
        util[p] = max(util.get(p, 0), sum(seq[i][1] for i in idx))
threshold, watched = int(sys.argv[1]), sys.argv[2]
got = set()
for line in open(watched):
    m = re.match(r'line \d+ prefix "\[(.*)\]" addItem (\d+)', line)
    if m:
        got.add(tuple(x.strip() for x in m.group(1).split(",") if x.strip() not in ("-1", "")) + (m.group(2),))
expected = {p for p, u in util.items() if u >= threshold}
fmt = lambda p: "<" + "".join(f"({x})" for x in p) + ">"
print(f"threshold {threshold}: correct HUSPs {len(expected)}, counted by the published code {len(got)}")
print("missed: " + ", ".join(f"{fmt(p)} utility {util[p]}" for p in sorted(expected - got)) if expected - got else "missed: none")
print("counted but not HUSPs: " + (", ".join(map(fmt, sorted(got - expected))) or "none"))
