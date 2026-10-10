# alpha on the SC2 insertion streams

Kind: invariant (a property of the data and of the joint rule's decisions, computed from the
data files; machine-independent). Written by `python3 analysis/insertion_alpha.py`; `run/`
holds its `run_meta.json` and console.

alpha = max_i A_i / U+, with U+ the utility inserted since the last rescan and A_i the part of
it in the sequences holding item i. Under the joint rule an insertion-only stream is never
rescanned while alpha <= UUT, and otherwise rescanned exactly when
(alpha - UUT) * U+ > (UUT - LUT_min) * TSU_ref.

- `alpha_stream.csv`: per database, alpha over the whole insertion stream, its median at the
  100 decisions, and `joint_over_tsu` = (1 - UUT) / (alpha - UUT), the inserted utility the
  joint rule absorbs before a rescan relative to a test on the total inserted utility.
- `alpha_batches.csv`: per database and increment, alpha at the decision and the decision.
- `sim_rescans_*` in `alpha_stream.csv`: rescans over the 100 increments of the joint rule and of
  Pre-HUSPM's two tests, simulated from the data. On SIGN, LEVIATHAN, TAFENG and C8T1S5I8N5K the
  simulated Pre-HUSPM counts equal those of the implementation in
  `results/invariant-baseline-fidelity/prehuspm-real-*` (8 of 8).

On all seven databases alpha exceeds UUT at every one of the 700 decisions, so the branch
alpha <= UUT never applies here; the rescans follow the second condition. `make_figures.py`
reads these files for `table_SC2_operating_region.csv` and checks the campaign's recorded
alpha (1 / `iau_tightness`) and decisions against `alpha_batches.csv`.
