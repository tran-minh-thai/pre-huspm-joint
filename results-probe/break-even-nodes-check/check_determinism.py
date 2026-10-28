"""Two runs of the node-based method and of the time-based arm on each stream of this check:

    python results-probe/break-even-nodes-check/check_determinism.py

The node-based runs must agree row by row on the decisions (rescans, rescans at UUT) and on the
logged rho, delta and absorption; the time-based runs are the control, whose logged rho must
differ (it is a ratio of measured times), so the comparison can see a difference. Every run must
report the same HUSP count as the fixed lower threshold measured on PC-THAI at every operation."""
import glob
import json
import pathlib
import sys

import pandas as pd

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parents[1]
COLS = ["rescan_count", "rescans_at_uut", "floor_rho", "floor_delta", "floor_absorption"]
bad = 0


def rows(name):
    o = HERE / name
    if json.load(open(o / "run_meta.json"))["exit_code"] != 0:
        raise SystemExit(f"{name}: the run did not exit 0")
    return pd.read_csv(glob.glob(str(o / "*_*.csv"))[0]).sort_values("iteration").reset_index(drop=True)


def same(a, b):
    x, y = a[COLS].fillna(-1.0), b[COLS].fillna(-1.0)
    return len(x) == len(y) and bool((x.values == y.values).all())


for stream, ref in (("SC2-TAFENG", "campaign/SC2/TAFENG"), ("SC6D-LEVIATHAN-seed0", "campaign/SC6D/LEVIATHAN")):
    m1, m2 = rows(f"{stream}-method-run1"), rows(f"{stream}-method-run2")
    t1, t2 = rows(f"{stream}-time-run1"), rows(f"{stream}-time-run2")
    ok_nodes, ok_ctrl = same(m1, m2), not same(t1, t2)
    fx = pd.read_csv(glob.glob(str(ROOT / "results" / ref / "Pre-HUSPM-Joint-fixed-threshold" / "*.csv"))[0])
    fx = fx.set_index("iteration")["total_patterns_found"]
    husp = []
    for r in (m1, m2, t1, t2):
        x = r.set_index("iteration")["total_patterns_found"]
        husp.append(int((x == fx.reindex(x.index)).sum()))
    ok_husp = all(h == len(m1) for h in husp)
    bad += not (ok_nodes and ok_ctrl and ok_husp)
    print(f"{stream}: node-based runs agree on every row: {ok_nodes} ({len(m1)} rows); "
          f"time-based control differs: {ok_ctrl}; HUSP counts equal to the fixed arm: {husp} of {len(m1)}; "
          f"rescans (at UUT): nodes {int(m1.rescan_count.max())} ({int(m1.rescans_at_uut.max())}), "
          f"time {int(t1.rescan_count.max())} ({int(t1.rescans_at_uut.max())}) and {int(t2.rescan_count.max())} ({int(t2.rescans_at_uut.max())})")
sys.exit(1 if bad else 0)
