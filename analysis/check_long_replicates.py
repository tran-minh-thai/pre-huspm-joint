#!/usr/bin/env python3
"""Self-test of the replicate pooling in analysis/long_streams.py on synthetic replicate trees built
from the SC9/DUNNHUMBY rows (method and fixed lower threshold): known answers, four faults that must
stop the script, a replicate without DONE, and a main run that did not finish.

    python3 analysis/check_long_replicates.py      # exits 1 on any failed check
"""
import os, shutil, subprocess, sys, tempfile, pathlib
import pandas as pd
REPO = pathlib.Path(__file__).resolve().parent.parent; PY = sys.executable
ARMS = ["Pre-HUSPM-Joint", "Pre-HUSPM-Joint-fixed-threshold"]

def build(tmp, factors, mutate=None):
    data = tmp / "results"
    for arm in ARMS:
        src = REPO / "results/campaign/SC9/DUNNHUMBY" / arm
        dst = data / "campaign/SC9/DUNNHUMBY" / arm; dst.mkdir(parents=True)
        shutil.copy(src / "SC9_long.csv", dst); (dst / "DONE").write_text("")
        for n, f in enumerate(factors, start=1):
            d = data / f"campaign-replicates/s0r{n}/SC9/DUNNHUMBY" / arm; d.mkdir(parents=True)
            x = pd.read_csv(src / "SC9_long.csv", low_memory=False)
            x["runtime_ms"] = (x["runtime_ms"] * f).round().astype(int)
            if "runtime_us" in x: x["runtime_us"] = (x["runtime_us"] * f).round()
            x.to_csv(d / "SC9_long.csv", index=False); (d / "DONE").write_text("")
    if mutate: mutate(data)
    return data

def run(data, out):
    env = dict(os.environ, HUSP_DATA_DIR=str(data), PYTHONDONTWRITEBYTECODE="1")
    return subprocess.run([PY, str(REPO / "analysis/long_streams.py"), "--out", str(out)], env=env,
                          capture_output=True, text=True)

fails = 0
def check(name, cond, detail=""):
    global fails
    fails += not cond
    print(("PASS " if cond else "FAIL ") + name + (f": {detail}" if detail else ""))

with tempfile.TemporaryDirectory() as t:
    t = pathlib.Path(t)
    base = run(build(t / "one", []), t / "o1")
    check("single run exits 0", base.returncode == 0, base.stderr[-300:])
    r = run(build(t / "three", [1.1, 0.9]), t / "o3")
    check("three runs exit 0", r.returncode == 0, r.stderr[-300:])
    a = pd.read_csv(t / "o1/table_long_streams.csv"); b = pd.read_csv(t / "o3/table_long_streams.csv")
    a, b = a[a.dataset == "DUNNHUMBY"].set_index("arm"), b[b.dataset == "DUNNHUMBY"].set_index("arm")
    check("runs = 3 for both arms", list(b["runs"]) == [3, 3], str(list(b["runs"])))
    rel = (b["stream_s_mean"] / a["stream_s_mean"] - 1).abs().max()
    check("mean of x1.1, x1.0, x0.9 equals the single run (rounding of ms only)", rel < 2e-3, f"{rel:.2e}")
    tot = {arm: a.loc[arm, "stream_s_mean"] * a.loc[arm, "seeds"] for arm in ARMS}
    lo = (b["run_total_s_min"] / pd.Series(tot) / 0.9 - 1).abs().max(); hi = (b["run_total_s_max"] / pd.Series(tot) / 1.1 - 1).abs().max()
    check("run totals are 0.9 and 1.1 of the single run", lo < 2e-3 and hi < 2e-3, f"{lo:.2e} {hi:.2e}")
    ratio = abs(b.loc[ARMS[1], "method_over_arm_pooled"] / a.loc[ARMS[1], "method_over_arm_pooled"] - 1)
    check("pooled ratio unchanged", ratio < 2e-3, f"{ratio:.2e}")
    for c in ["rescans", "rescans_at_uut", "deletion_rescans", "insertion_rescans"]:
        check(f"counter {c} unchanged", (a[c] == b[c]).all())
    k = pd.read_csv(t / "o3/paper_numbers_long.csv").set_index("key")["value"]
    check("keys _runs and _stream_s_runs written", "LONG_SC9_DUNNHUMBY_Method_runs" in k and
          "LONG_SC9_DUNNHUMBY_FixedFloor_stream_s_runs" in k)

    def csv(data, n, arm): return data / f"campaign-replicates/s0r{n}/SC9/DUNNHUMBY/{arm}/SC9_long.csv"
    def edit(n, arm, fn):
        def m(data):
            p = csv(data, n, arm); x = pd.read_csv(p, low_memory=False); y = fn(x)
            changed = len(y) != len(x) or not y.reset_index(drop=True).equals(x.reset_index(drop=True))
            assert changed, "the fault did not change the file"
            y.to_csv(p, index=False)
        return m
    def bump(x):
        x = x.copy(); x.loc[x.index[200], "rescan_count"] += 1; return x
    faults = [
        ("counter differs", edit(1, ARMS[0], bump), "rescan_count differs"),
        ("operation missing", edit(2, ARMS[1], lambda x: x.drop(x.index[300])), "operations differ"),
        ("duplicate row", edit(1, ARMS[1], lambda x: pd.concat([x, x.iloc[[10]]])), "repeat an"),
        ("replicate without main run", lambda d: (lambda q: (q.mkdir(parents=True), shutil.copy(csv(d, 1, ARMS[0]), q / "SC9_long.csv"),
                                                   (q / "DONE").write_text("")))(d / "campaign-replicates/s0r1/SC9/DUNNHUMBY/Pre-HUSPM-Joint-w5"),
         None),
    ]
    for name, mut, expect in faults:
        if name == "replicate without main run":
            # the arm's rows say Pre-HUSPM-Joint (algorithm column), so rename them to an arm without a main run
            def mut2(d, mut=mut):
                mut(d); p = d / "campaign-replicates/s0r1/SC9/DUNNHUMBY/Pre-HUSPM-Joint-w5/SC9_long.csv"
                x = pd.read_csv(p, low_memory=False); x["algorithm"] = "Pre-HUSPM-Joint-w5"; x.to_csv(p, index=False)
            mut, expect = mut2, "has no main run"
        r = run(build(t / name.replace(" ", "_"), [1.1, 0.9], mut), t / ("o_" + name.replace(" ", "_")))
        check(f"fault '{name}' stops the script", r.returncode != 0 and expect in (r.stdout + r.stderr),
              (r.stdout + r.stderr).strip().splitlines()[-1] if (r.stdout + r.stderr).strip() else "no output")
    # a replicate without DONE is left out: two runs remain
    r = run(build(t / "nodone", [1.1, 0.9], lambda d: os.remove(d / "campaign-replicates/s0r2/SC9/DUNNHUMBY/Pre-HUSPM-Joint/DONE")), t / "o_nd")
    x = pd.read_csv(t / "o_nd/table_long_streams.csv"); x = x[x.dataset == "DUNNHUMBY"].set_index("arm")
    check("replicate without DONE left out (runs 2 and 3)", r.returncode == 0 and list(x["runs"]) == [2, 3], str(list(x["runs"])))
    # the main run unfinished at one operation, the replicates finished: the seed counts, from the replicates
    def unfinished(d):
        p = d / f"campaign/SC9/DUNNHUMBY/{ARMS[0]}/SC9_long.csv"; x = pd.read_csv(p, low_memory=False)
        i = x.index[(x.seed == 42) & (x.iteration == 100)][0]; x.loc[i, "runtime_ms"] = -1; x.to_csv(p, index=False)
    r = run(build(t / "unf", [1.1, 0.9], unfinished), t / "o_unf")
    x = pd.read_csv(t / "o_unf/table_long_streams.csv"); x = x[x.dataset == "DUNNHUMBY"].set_index("arm")
    check("main run unfinished, replicates finished: 3 seeds, 2 runs", r.returncode == 0 and x.loc[ARMS[0], "seeds"] == 3
          and x.loc[ARMS[0], "runs"] == 2, f"seeds {x.loc[ARMS[0], 'seeds']}, runs {x.loc[ARMS[0], 'runs']}")
print(f"failed: {fails}")
sys.exit(1 if fails else 0)
