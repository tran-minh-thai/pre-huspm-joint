#!/usr/bin/env python3
"""The rule that picks the window w of the rescan floor selection, and its leave-one-out use.

Rule (stated after w = 5 had been chosen by inspection of the same counts; see
docs/experiments.md): on the SC2 streams, take the smallest w whose selection adds at most T
rescans to the fixed floor on every stream, T = 3. The counts are the rescans of the joint rule
at the fixed floor and of the selection at each w, simulated from the data files with no
timing (analysis/insertion_alpha.py --floor-windows), so the rule reads counters only.

    python3 analysis/window_rule.py [floor_selection.csv] [--extra 3]

prints the window chosen on all streams and, for each stream, the window chosen on the others
(leave one out), for T = 3 and for every T at which the choice on all streams is unchanged.
"""
import argparse
import csv
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_TABLE = os.path.join(ROOT, "results", "invariant-insertion-alpha", "floor-selection-windows",
                             "floor_selection.csv")
EXTRA = 3


def load(path=DEFAULT_TABLE):
    """{stream: {w: rescans added by the selection over the fixed floor}}."""
    out = {}
    with open(path, newline="", encoding="utf-8") as fh:
        for r in csv.DictReader(fh):
            out.setdefault(r["dataset"], {})[int(r["window"])] = \
                int(r["rescans_selection"]) - int(r["rescans_joint"])
    return out


def choose(added, streams, extra=EXTRA):
    """The smallest w with at most `extra` added rescans on every stream of `streams`, or None.
    Raises when the streams do not share one set of windows."""
    windows = {frozenset(added[s]) for s in streams}
    if len(windows) != 1:
        raise ValueError(f"the streams were simulated at different windows: {sorted(map(sorted, windows))}")
    for w in sorted(next(iter(windows))):
        if max(added[s][w] for s in streams) <= extra:
            return w
    return None


def leave_one_out(added, extra=EXTRA):
    """{stream: window chosen on the other streams}."""
    return {d: choose(added, [s for s in added if s != d], extra) for d in added}


def self_test(added):
    """Faults the rule must react to: each changes the choice it is meant to change."""
    streams = list(added)
    base = choose(added, streams)
    worst = max(streams, key=lambda s: added[s][base])
    # 1. one more rescan at the chosen window on the stream that binds it moves the choice up
    bumped = {s: dict(v) for s, v in added.items()}
    bumped[worst][base] += max(0, EXTRA - added[worst][base]) + 1
    assert choose(bumped, streams) != base, "fault 1 not caught"
    # 2. a smaller window within the bound on every stream is chosen instead
    smaller = min(added[streams[0]])
    eased = {s: dict(v) for s, v in added.items()}
    for s in streams:
        eased[s][smaller] = 0
    assert smaller == base or choose(eased, streams) == smaller, "fault 2 not caught"
    # 3. streams simulated at different windows are refused
    odd = {s: dict(v) for s, v in added.items()}
    odd[worst].pop(max(odd[worst]))
    try:
        choose(odd, streams)
        raise AssertionError("fault 3 not caught")
    except ValueError:
        pass
    return 3


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("table", nargs="?", default=DEFAULT_TABLE)
    ap.add_argument("--extra", type=int, default=EXTRA)
    a = ap.parse_args()
    added = load(a.table)
    streams = list(added)
    windows = sorted(next(iter(added.values())))
    print(f"{len(streams)} streams, windows {windows}")
    print("rescans added over the fixed floor:")
    for s in streams:
        print(f"  {s:12s} " + "  ".join(f"w={w}: {added[s][w]}" for w in windows))
    print(f"self-test: {self_test(added)} of 3 faults caught")
    full = choose(added, streams, a.extra)
    print(f"T = {a.extra}: all streams -> w = {full}")
    for d, w in leave_one_out(added, a.extra).items():
        print(f"  without {d:12s} -> w = {w}")
    same = [t for t in range(0, 101) if choose(added, streams, t) == full]
    print(f"T giving w = {full} on all streams: {min(same)} to {max(same)}")
    for t in same:
        loo = leave_one_out(added, t)
        print(f"  T = {t}: " + ", ".join(f"{d} {w}" for d, w in loo.items()))


if __name__ == "__main__":
    main()
