# SC3, static mining on the whole database

The six algorithms of SC1 on the whole database at the same four thresholds, on SIGN, LEVIATHAN and C8T1S5I8N5K. Measured on `PC-THAI` (see `results/machine/PC-THAI.json` and `environment.lock.json`), `-Xmx48g`, one JVM per arm. Written by `analysis/scenario_readmes.py` from the arms' CSVs; rerun it rather than editing numbers here.

## Answers

For 12 of the 12 (database, threshold) pairs, every algorithm that completed reports the same HUSP count. Counts alone do not prove the sets equal; `verify.BaselineAgreement` compares the sets (`results/calibration/agreement.log`).

The proposal is the fastest in 0 of 12 cells. The fastest baseline's runtime over the proposal's, per cell: median 0.84, largest 1.00, smallest 0.40; above one, the proposal is faster.

Every run completed.

## Runtime

Seconds; the fastest in bold.

| database | threshold | HUSPs | USpan | HUSP-ULL | HUSP-SP | IncUSP-Miner+ | Pre-HUSPM | Pre-HUSPM-Joint |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| SIGN | 0.049134 | 793 | 10.9 | **0.54** | 0.58 | 5.88 | 2.55 | 0.68 |
| SIGN | 0.065911 | 141 | 5.72 | **0.25** | 0.26 | 3.14 | 1.21 | 0.25 |
| SIGN | 0.083371 | 31 | 3.40 | **0.15** | 0.15 | 1.85 | 0.71 | 0.15 |
| SIGN | 0.091052 | 16 | 2.79 | **0.12** | 0.12 | 1.49 | 0.57 | 0.12 |
| LEVIATHAN | 0.014644 | 173 | 13.6 | **0.63** | 0.67 | 7.44 | 1.87 | 1.02 |
| LEVIATHAN | 0.01553 | 135 | 12.3 | 0.52 | **0.50** | 6.86 | 1.70 | 0.56 |
| LEVIATHAN | 0.01647 | 116 | 11.2 | 0.47 | **0.46** | 6.29 | 1.53 | 0.50 |
| LEVIATHAN | 0.017987 | 81 | 9.93 | 0.40 | **0.40** | 5.59 | 1.31 | 0.44 |
| C8T1S5I8N5K | 0.001099 | 4256 | 4.37 | **0.27** | 0.29 | 4.48 | 1.09 | 0.67 |
| C8T1S5I8N5K | 0.001132 | 3438 | 1.93 | **0.15** | 0.18 | 3.87 | 0.80 | 0.33 |
| C8T1S5I8N5K | 0.0012 | 2110 | 1.53 | **0.13** | 0.14 | 3.09 | 0.45 | 0.31 |
| C8T1S5I8N5K | 0.001236 | 1484 | 1.40 | 0.12 | **0.11** | 2.84 | 0.39 | 0.17 |

## Against each baseline, here and on `D_init`

Geometric mean of the baseline's runtime over the proposal's, on the cells where both completed.

| baseline | SC3, whole database | cells | SC1, `D_init` | cells |
|---|---:|---:|---:|---:|
| USpan | 13.70 | 12 | 16.31 | 32 |
| IncUSP-Miner+ | 10.98 | 12 | 10.24 | 32 |
| Pre-HUSPM | 2.83 | 12 | 2.90 | 32 |
| HUSP-SP | 0.74 | 12 | 0.93 | 32 |
| HUSP-ULL | 0.72 | 12 | 0.84 | 32 |
