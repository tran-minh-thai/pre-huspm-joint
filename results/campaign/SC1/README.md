# SC1, static mining on `D_init`

Eight databases, six algorithms (USpan, HUSP-ULL, HUSP-SP, IncUSP-Miner+, Pre-HUSPM and Pre-HUSPM-Joint), four thresholds per database from `common.Datasets`. One JVM per arm; the four thresholds run lowest first in the same JVM. Measured on `PC-THAI` (see `results/machine/PC-THAI.json` and `environment.lock.json`), `-Xmx48g`, one JVM per arm. Written by `analysis/scenario_readmes.py` from the arms' CSVs; rerun it rather than editing numbers here.

## Answers

For 32 of the 32 (database, threshold) pairs, every algorithm that completed reports the same HUSP count. Counts alone do not prove the sets equal; `verify.BaselineAgreement` compares the sets (`results/calibration/agreement.log`).

The proposal is the fastest in 5 of 32 cells on the mean of the runs; every run of it beats every run of the fastest baseline in 3, and in 6 its runs overlap those of the fastest baseline (BMS1 at 0.015293 (HUSP-ULL); BMS1 at 0.016218 (HUSP-ULL); C8T1S5I8N5K at 0.001132 (HUSP-ULL); LEVIATHAN at 0.014644 (HUSP-SP); LEVIATHAN at 0.01553 (HUSP-ULL); LEVIATHAN at 0.01647 (HUSP-ULL)). The fastest baseline's runtime over the proposal's, per cell: median 0.83, largest 1.11, smallest 0.38; above one, the proposal is faster.

Every run completed.

## Runtime at the default threshold

Seconds; the fastest in bold.

| database | threshold | HUSPs | USpan | HUSP-ULL | HUSP-SP | IncUSP-Miner+ | Pre-HUSPM | Pre-HUSPM-Joint |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| SIGN | 0.065911 | 583 | 2.47 | **0.10** | 0.11 | 1.60 | 0.74 | 0.12 |
| LEVIATHAN | 0.01553 | 107 | 5.05 | **0.23** | 0.23 | 3.13 | 0.78 | 0.23 |
| FIFA | 0.059637 | 18 | 583 | 4.07 | **3.20** | 374 | 126 | 4.50 |
| BIBLE | 0.022895 | 192 | 9.73 | **0.58** | 0.58 | 4.45 | 1.11 | 0.71 |
| BMS1 | 0.013597 | 21 | 0.61 | 0.03 | 0.10 | 0.27 | 0.29 | **0.03** |
| KOSARAK | 0.025 | 10 | 36.1 | 0.58 | **0.36** | 8.55 | 0.73 | 0.39 |
| C8T1S5I8N5K | 0.001132 | 5067 | 1.19 | **0.10** | 0.11 | 2.47 | 0.69 | 0.19 |
| TAFENG | 0.003557 | 6 | 0.35 | 0.15 | 0.24 | 0.54 | **0.10** | 0.16 |

## Against each baseline, all cells

Geometric mean of the baseline's runtime over the proposal's, on the cells where both completed.

| baseline | baseline / proposal | cells |
|---|---:|---:|
| USpan | 19.08 | 32 |
| IncUSP-Miner+ | 12.62 | 32 |
| Pre-HUSPM | 3.51 | 32 |
| HUSP-SP | 1.02 | 32 |
| HUSP-ULL | 0.92 | 32 |

`peak_heap_mb` in these rows is used heap, garbage included, and is not the memory result; the
live set is in `results/campaign-mem-sampled/SC1`.
