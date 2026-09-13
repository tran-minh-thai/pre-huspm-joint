# -*- coding: utf-8 -*-
"""Merge the Adaptive-only re-run (results_adaptive/) into the main results/.

Rationale: after disabling EUCS by default, ONLY the Adaptive variants' runtime
(and memory) change; baselines + all non-runtime Adaptive metrics are byte-identical.
So we re-ran ONLY Adaptive (adaptive.only=true) and now splice those fresh rows back
into the full CSVs, keeping the unchanged baseline rows.

Rule per scenario CSV:
    new = [old rows whose algorithm is a BASELINE]  +  [ALL rows from results_adaptive/]
where "Adaptive family" = algorithm starts with "Adaptive" or "proposed".
This also handles SC7's changed variant set (old proposed_no_EUCS dropped; new
proposed_full(no-EUCS)/proposed_with_EUCS added) automatically.

Usage:  python3 analysis/merge_adaptive.py            # writes into results/ (backs up first)
        python3 analysis/merge_adaptive.py --dry-run  # report only
"""
import os, sys, glob, shutil, time
import pandas as pd

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
MAIN = os.path.join(ROOT, "results")
ADPT = os.path.join(ROOT, "results_adaptive")
DRY  = "--dry-run" in sys.argv


def is_adaptive(algo):
    a = str(algo)
    return a.startswith("Adaptive") or a.startswith("proposed")


def main():
    if not os.path.isdir(ADPT):
        sys.exit(f"[abort] {ADPT} not found - run the adaptive.only re-run first")
    if not DRY:
        bk = os.path.join(ROOT, f"results_backup_{int(time.time())}")
        shutil.copytree(MAIN, bk)
        print(f"[backup] {MAIN} -> {bk}")

    for newf in sorted(glob.glob(os.path.join(ADPT, "SC*.csv"))):
        name = os.path.basename(newf)
        oldf = os.path.join(MAIN, name)
        new = pd.read_csv(newf); new.columns = [c.strip() for c in new.columns]
        # EUCS đã gỡ hoàn toàn → biến thể overhead-demo "proposed_with_EUCS" không còn ý nghĩa
        n_weucs = (new["algorithm"] == "proposed_with_EUCS").sum()
        if n_weucs:
            new = new[new["algorithm"] != "proposed_with_EUCS"].reset_index(drop=True)
            print(f"  [drop] {n_weucs} proposed_with_EUCS row(s) (EUCS removed)")
        if not os.path.exists(oldf):
            print(f"[warn] {name}: no old file - copying adaptive rows as-is")
            if not DRY: new.to_csv(oldf, index=False)
            continue
        old = pd.read_csv(oldf); old.columns = [c.strip() for c in old.columns]

        old_base = old[~old["algorithm"].map(is_adaptive)]          # keep baselines
        old_adpt_n = old["algorithm"].map(is_adaptive).sum()
        merged = pd.concat([old_base, new], ignore_index=True)
        print(f"{name:26s} old={len(old):4d} (adaptive {old_adpt_n:3d} dropped) "
              f"+ new_adaptive={len(new):4d}  -> {len(merged):4d}")
        if not DRY:
            merged.to_csv(oldf, index=False)
    print("DONE." + ("  (dry-run, nothing written)" if DRY else ""))


if __name__ == "__main__":
    main()
