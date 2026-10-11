# SC1, static mining on `D_init`

Eight databases, six algorithms (USpan, HUSP-ULL, HUSP-SP, IncUSP-Miner+, Pre-HUSPM and Pre-HUSPM-Joint), four thresholds per database from `common.Datasets`. One JVM per arm; the four thresholds run lowest first in the same JVM. Measured on `PC-THAI` (see `results/machine/PC-THAI.json` and `environment.lock.json`), `-Xmx48g`, one JVM per arm. Written by `analysis/scenario_readmes.py` from the arms' CSVs; rerun it rather than editing numbers here.

## Answers

For 32 of the 32 (database, threshold) pairs, every algorithm that completed reports the same HUSP count. Counts alone do not prove the sets equal; `verify.BaselineAgreement` compares the sets (`results/calibration/agreement.log`).

The proposal is the fastest in 3 of 32 cells. The fastest baseline's runtime over the proposal's, per cell: median 0.74, largest 1.07, smallest 0.46; above one, the proposal is faster.

Every run completed.

## Runtime at the default threshold

Seconds; the fastest in bold.

| database | threshold | HUSPs | USpan | HUSP-ULL | HUSP-SP | IncUSP-Miner+ | Pre-HUSPM | Pre-HUSPM-Joint |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| SIGN | 0.065911 | 583 | 2.48 | **0.11** | 0.11 | 1.61 | 0.73 | 0.12 |
| LEVIATHAN | 0.01553 | 107 | 4.93 | **0.24** | 0.26 | 3.15 | 0.79 | 0.31 |
| FIFA | 0.059637 | 18 | 597 | 4.22 | **3.20** | 350 | 127 | 4.46 |
| BIBLE | 0.022895 | 192 | 10.2 | **0.60** | 0.63 | 4.47 | 1.11 | 0.72 |
| BMS1 | 0.013597 | 21 | 0.61 | **0.04** | 0.07 | 0.30 | 0.30 | 0.07 |
| KOSARAK | 0.025 | 10 | 35.0 | 0.58 | **0.35** | 7.95 | 0.74 | 0.43 |
| C8T1S5I8N5K | 0.001132 | 5067 | 1.26 | **0.12** | 0.12 | 2.48 | 0.72 | 0.21 |
| TAFENG | 0.003557 | 6 | 0.37 | 0.16 | 0.26 | 0.54 | **0.13** | 0.25 |

## Against each baseline, all cells

Geometric mean of the baseline's runtime over the proposal's, on the cells where both completed.

| baseline | baseline / proposal | cells |
|---|---:|---:|
| USpan | 16.31 | 32 |
| IncUSP-Miner+ | 10.24 | 32 |
| Pre-HUSPM | 2.90 | 32 |
| HUSP-SP | 0.93 | 32 |
| HUSP-ULL | 0.84 | 32 |

`peak_heap_mb` in these rows is used heap, garbage included, and is not the memory result; the
live set is in `results/campaign-mem/SC1`.
