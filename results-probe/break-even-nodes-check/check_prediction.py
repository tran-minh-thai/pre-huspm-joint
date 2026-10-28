"""The node-based method's decisions measured on the development machine (runs of this check)
against the decisions the simulator predicts for the rule by explored nodes, from remining's node
counts on PC-THAI and the initial mine's:

    python results-probe/break-even-nodes-check/check_prediction.py

Every operation must agree. As a control, the time-based arm's run must not agree everywhere."""
import argparse
import glob
import os
import pathlib
import sys

import pandas as pd

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parents[1]
sys.path.insert(0, str(ROOT / "analysis"))
import floor_policy_probe as fp  # noqa: E402
import make_figures as mf  # noqa: E402

PLANS = "results-probe/floor-policy/mixed-plans/mixed_plans.tsv"
bad = 0
for label, db, sc, sc6, seed in (("SC2-TAFENG", "TAFENG", "SC2", False, None),
                                 ("SC6D-LEVIATHAN-seed0", "LEVIATHAN", "SC6D", True, 42)):
    a = argparse.Namespace(db=[db], sc5=False, sc6=sc6, plans=PLANS)
    stream = next(s for s in fp.streams(a) if s.label == (db if not sc6 else f"{sc} {db} seed {seed}"))
    for arm, expect_all in (("method", True), ("time", False)):
        r = pd.read_csv(glob.glob(str(HERE / f"{label}-{arm}-run1" / "*_*.csv"))[0])
        r = r.assign(algorithm=mf.PROPOSED, replicate="check")
        others = stream.rows[stream.rows["algorithm"] != mf.PROPOSED]
        s = fp.Stream(stream.label, stream.db, stream.init, stream.phases, stream.op_of, stream.kind_of,
                      pd.concat([others, r[r["iteration"] > 0]], ignore_index=True), stream.n_ops,
                      pd.concat([stream.init_rows[stream.init_rows["algorithm"] != mf.PROPOSED], r[r["iteration"] == 0]],
                                ignore_index=True))
        with open(os.devnull, "w") as quiet:
            old, sys.stdout = sys.stdout, quiet
            try:
                row = fp.evaluate(s, mf.DATASETS[db]["default_uut"], 0.8)
            finally:
                sys.stdout = old
        got = row.get("nodes_rule_agrees_with_measured", "missing")
        k, m = (int(x) for x in got.split("/")) if "/" in got else (0, 1)
        ok = (k == m) if expect_all else (k < m)
        bad += not ok
        print(f"{label} {arm}: predicted node-rule decisions agree on {got} operations "
              f"({'expected all' if expect_all else 'control, expected a disagreement'}) -> {'ok' if ok else 'FAILED'}")
sys.exit(1 if bad else 0)
