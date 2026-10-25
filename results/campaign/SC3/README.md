# SC3, static mining on the whole database

The six algorithms of SC1 on the whole database at the same four thresholds, on SIGN, LEVIATHAN and C8T1S5I8N5K. Measured on `PC-THAI` (see `results/machine/PC-THAI.json` and `environment.lock.json`), `-Xmx48g`, one JVM per arm. Written by `analysis/scenario_readmes.py` from the arms' CSVs; rerun it rather than editing numbers here.

## Answers

For 12 of the 12 (database, threshold) pairs, every algorithm that completed reports the same HUSP count. Counts alone do not prove the sets equal; `verify.BaselineAgreement` compares the sets (`results/calibration/agreement.log`).

The proposal is the fastest in 2 of 12 cells on the mean of the runs; every run of it beats every run of the fastest baseline in 1, and in 3 its runs overlap those of the fastest baseline (SIGN at 0.049134 (HUSP-ULL); SIGN at 0.083371 (HUSP-SP); SIGN at 0.091052 (HUSP-SP)). The fastest baseline's runtime over the proposal's, per cell: median 0.90, largest 1.06, smallest 0.61; above one, the proposal is faster.

Every run completed.

## Runtime

Seconds; the fastest in bold.

| database | threshold | HUSPs | USpan | HUSP-ULL | HUSP-SP | IncUSP-Miner+ | Pre-HUSPM | Pre-HUSPM-Joint |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| SIGN | 0.049134 | 793 | 10.6 | **0.46** | 0.48 | 5.80 | 2.42 | 0.47 |
| SIGN | 0.065911 | 141 | 5.74 | 0.25 | 0.28 | 3.09 | 1.28 | **0.24** |
| SIGN | 0.083371 | 31 | 3.43 | 0.15 | 0.15 | 1.85 | 0.70 | **0.14** |
| SIGN | 0.091052 | 16 | 2.82 | 0.12 | **0.12** | 1.49 | 0.56 | 0.12 |
| LEVIATHAN | 0.014644 | 173 | 12.9 | 0.56 | **0.54** | 7.42 | 1.91 | 0.60 |
| LEVIATHAN | 0.01553 | 135 | 11.8 | 0.51 | **0.49** | 6.84 | 1.74 | 0.54 |
| LEVIATHAN | 0.01647 | 116 | 11.0 | **0.46** | 0.48 | 6.30 | 1.55 | 0.50 |
| LEVIATHAN | 0.017987 | 81 | 9.74 | 0.40 | **0.39** | 5.61 | 1.34 | 0.44 |
| C8T1S5I8N5K | 0.001099 | 4256 | 2.14 | **0.16** | 0.22 | 4.32 | 0.99 | 0.25 |
| C8T1S5I8N5K | 0.001132 | 3438 | 1.89 | **0.14** | 0.15 | 3.93 | 0.78 | 0.21 |
| C8T1S5I8N5K | 0.0012 | 2110 | 1.53 | 0.13 | **0.12** | 3.21 | 0.47 | 0.18 |
| C8T1S5I8N5K | 0.001236 | 1484 | 1.37 | 0.12 | **0.11** | 2.93 | 0.40 | 0.17 |

## Against each baseline, here and on `D_init`

Geometric mean of the baseline's runtime over the proposal's, on the cells where both completed.

| baseline | SC3, whole database | cells | SC1, `D_init` | cells |
|---|---:|---:|---:|---:|
| USpan | 16.35 | 12 | 19.08 | 32 |
| IncUSP-Miner+ | 14.13 | 12 | 12.62 | 32 |
| Pre-HUSPM | 3.64 | 12 | 3.51 | 32 |
| HUSP-SP | 0.89 | 12 | 1.02 | 32 |
| HUSP-ULL | 0.86 | 12 | 0.92 | 32 |
