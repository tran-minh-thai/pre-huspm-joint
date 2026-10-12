# SC3, static mining on the whole database

The six algorithms of SC1 on the whole database at the same four thresholds, on SIGN, LEVIATHAN and C8T1S5I8N5K. Measured on `PC-THAI` (see `results/machine/PC-THAI.json` and `environment.lock.json`), `-Xmx48g`, one JVM per arm. Written by `analysis/scenario_readmes.py` from the arms' CSVs; rerun it rather than editing numbers here.

## Answers

For 12 of the 12 (database, threshold) pairs, every algorithm that completed reports the same HUSP count. Counts alone do not prove the sets equal; `verify.BaselineAgreement` compares the sets (`results/calibration/agreement.log`).

The proposal is the fastest in 1 of 12 cells on the mean of the runs; every run of it beats every run of the fastest baseline in 0, and in 3 its runs overlap those of the fastest baseline (SIGN at 0.065911 (HUSP-ULL); SIGN at 0.083371 (HUSP-SP); SIGN at 0.091052 (HUSP-ULL)). The fastest baseline's runtime over the proposal's, per cell: median 0.82, largest 1.06, smallest 0.31; above one, the proposal is faster.

Every run completed.

## Runtime

Seconds; the fastest in bold.

| database | threshold | HUSPs | USpan | HUSP-ULL | HUSP-SP | IncUSP-Miner+ | Pre-HUSPM | Pre-HUSPM-Joint |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| SIGN | 0.049134 | 793 | 11.0 | **0.53** | 0.56 | 5.88 | 2.52 | 0.67 |
| SIGN | 0.065911 | 141 | 5.72 | **0.25** | 0.27 | 3.12 | 1.21 | 0.28 |
| SIGN | 0.083371 | 31 | 3.43 | 0.15 | **0.14** | 1.85 | 0.70 | 0.15 |
| SIGN | 0.091052 | 16 | 2.81 | 0.13 | 0.13 | 1.49 | 0.56 | **0.12** |
| LEVIATHAN | 0.014644 | 173 | 13.5 | **0.62** | 0.66 | 7.40 | 1.90 | 0.89 |
| LEVIATHAN | 0.01553 | 135 | 12.0 | 0.53 | **0.51** | 6.82 | 1.71 | 0.57 |
| LEVIATHAN | 0.01647 | 116 | 11.1 | **0.48** | 0.48 | 6.25 | 1.54 | 0.50 |
| LEVIATHAN | 0.017987 | 81 | 9.80 | 0.41 | **0.39** | 5.55 | 1.33 | 0.46 |
| C8T1S5I8N5K | 0.001099 | 4256 | 5.00 | **0.26** | 0.31 | 4.58 | 1.09 | 0.85 |
| C8T1S5I8N5K | 0.001132 | 3438 | 1.99 | 0.18 | **0.17** | 3.98 | 0.82 | 0.30 |
| C8T1S5I8N5K | 0.0012 | 2110 | 1.60 | **0.15** | 0.21 | 3.20 | 0.46 | 0.31 |
| C8T1S5I8N5K | 0.001236 | 1484 | 1.40 | **0.12** | 0.23 | 2.93 | 0.40 | 0.16 |

## Against each baseline, here and on `D_init`

Geometric mean of the baseline's runtime over the proposal's, on the cells where both completed.

| baseline | SC3, whole database | cells | SC1, `D_init` | cells |
|---|---:|---:|---:|---:|
| USpan | 13.77 | 12 | 16.32 | 32 |
| IncUSP-Miner+ | 10.95 | 12 | 10.15 | 32 |
| Pre-HUSPM | 2.82 | 12 | 2.88 | 32 |
| HUSP-SP | 0.81 | 12 | 0.91 | 32 |
| HUSP-ULL | 0.74 | 12 | 0.82 | 32 |
