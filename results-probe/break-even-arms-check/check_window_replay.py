"""Replays the decisions of a break-even sensitivity arm measured on SC2 TAFENG from the rho and
delta it logged, at its own absorption window and, as a control, at the method's window 5:

    python results-probe/break-even-arms-check/check_window_replay.py

The run at its own window must agree on every operation, and the control must disagree somewhere,
which shows the arm averaged the number of absorptions it is named for."""
import argparse
import os
import pathlib
import sys

import pandas as pd

ROOT = pathlib.Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "analysis"))
import floor_policy_probe as fp  # noqa: E402
import make_figures as mf  # noqa: E402

HERE = pathlib.Path(__file__).resolve().parent
bad = 0
for n in (2, 20):
    arm = f"{mf.PROPOSED}-be{n}"
    a = argparse.Namespace(db=["TAFENG"], sc5=False, sc6=False, plans="")
    stream = next(fp.streams(a))
    rows = pd.read_csv(HERE / f"SC2-TAFENG-{arm}" / "SC2_incremental.csv")
    rows = rows[rows["iteration"] > 0].assign(algorithm=mf.PROPOSED, replicate="check")
    others = stream.rows[stream.rows["algorithm"] != mf.PROPOSED]
    stream.rows = pd.concat([others, rows], ignore_index=True)
    for w in (n, 5):
        with open(os.devnull, "w") as quiet:
            old, sys.stdout = sys.stdout, quiet
            try:
                row = fp.evaluate(stream, mf.DATASETS["TAFENG"]["default_uut"], 0.8, be_window=w)
            finally:
                sys.stdout = old
        got = row["replay_break_even_logged"]
        k, m = (int(x) for x in got.split("/"))
        expect_all = w == n
        ok = (k == m) if expect_all else (k < m)
        bad += not ok
        print(f"{arm}: replay at window {w}: {got} operations agree "
              f"({'expected all' if expect_all else 'control, expected a disagreement'}) -> {'ok' if ok else 'FAILED'}")
sys.exit(1 if bad else 0)
