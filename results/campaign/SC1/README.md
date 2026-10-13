# SC1, static mining on `D_init`

Eight databases, six algorithms (USpan, HUSP-ULL, HUSP-SP, IncUSP-Miner+, Pre-HUSPM and Pre-HUSPM-Joint), four thresholds per database from `common.Datasets`. One JVM per arm; the four thresholds run lowest first in the same JVM. Measured on `PC-THAI` (see `results/machine/PC-THAI.json` and `environment.lock.json`), `-Xmx48g`, one JVM per arm. Written by `analysis/scenario_readmes.py` from the arms' CSVs; rerun it rather than editing numbers here.

## Answers

For 32 of the 32 (database, threshold) pairs, every algorithm that completed reports the same HUSP count. Counts alone do not prove the sets equal; `verify.BaselineAgreement` compares the sets (`results/calibration/agreement.log`).

The proposal is the fastest in 1 of 32 cells on the mean of the runs; every run of it beats every run of the fastest baseline in 1, and in 2 its runs overlap those of the fastest baseline (BMS1 at 0.015293 (HUSP-ULL); KOSARAK at 0.028117 (HUSP-SP)). The fastest baseline's runtime over the proposal's, per cell: median 0.73, largest 1.04, smallest 0.45; above one, the proposal is faster.

Every run completed.

## Runtime at the default threshold

Seconds; the fastest in bold.

| database | threshold | HUSPs | USpan | HUSP-ULL | HUSP-SP | IncUSP-Miner+ | Pre-HUSPM | Pre-HUSPM-Joint |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| SIGN | 0.065911 | 583 | 2.46 | **0.11** | 0.11 | 1.63 | 0.74 | 0.14 |
| LEVIATHAN | 0.01553 | 107 | 5.07 | **0.24** | 0.26 | 3.16 | 0.79 | 0.28 |
| FIFA | 0.059637 | 18 | 671 | 4.13 | **3.19** | 352 | 127 | 4.45 |
| BIBLE | 0.022895 | 192 | 9.88 | **0.59** | 0.60 | 4.49 | 1.12 | 0.75 |
| BMS1 | 0.013597 | 21 | 0.61 | **0.04** | 0.08 | 0.30 | 0.30 | 0.07 |
| KOSARAK | 0.025 | 10 | 35.5 | 0.61 | **0.38** | 8.13 | 0.77 | 0.42 |
| C8T1S5I8N5K | 0.001132 | 5067 | 1.27 | **0.11** | 0.13 | 2.52 | 0.72 | 0.21 |
| TAFENG | 0.003557 | 6 | 0.39 | 0.15 | 0.26 | 0.52 | **0.12** | 0.23 |

## Against each baseline, all cells

Geometric mean of the baseline's runtime over the proposal's, on the cells where both completed.

| baseline | baseline / proposal | cells |
|---|---:|---:|
| USpan | 16.32 | 32 |
| IncUSP-Miner+ | 10.15 | 32 |
| Pre-HUSPM | 2.88 | 32 |
| HUSP-SP | 0.91 | 32 |
| HUSP-ULL | 0.82 | 32 |

`peak_heap_mb` in these rows is used heap, garbage included, and is not the memory result; the
live set is in `results/campaign-mem-sampled/SC1`.
