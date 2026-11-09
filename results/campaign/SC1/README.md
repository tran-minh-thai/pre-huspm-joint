# SC1, static mining on `D_init`

Eight databases, six algorithms (USpan, HUSP-ULL, HUSP-SP, IncUSP-Miner+, Pre-HUSPM and Pre-HUSPM-Joint), four thresholds per database from `common.Datasets`. One JVM per arm; the four thresholds run lowest first in the same JVM. Measured on `PC-THAI` (see `results/machine/PC-THAI.json` and `environment.lock.json`), `-Xmx48g`, one JVM per arm. Written by `analysis/scenario_readmes.py` from the arms' CSVs; rerun it rather than editing numbers here.

## Answers

For 36 of the 36 (database, threshold) pairs, every algorithm that completed reports the same HUSP count. Counts alone do not prove the sets equal; `verify.BaselineAgreement` compares the sets (`results/calibration/agreement.log`).

The proposal is the fastest in 9 of 36 cells on the mean of the runs; every run of it beats every run of the fastest baseline in 7, and in 7 its runs overlap those of the fastest baseline (BMS1 at 0.015293 (HUSP-ULL); BMS1 at 0.016218 (HUSP-ULL); C8T1S5I8N5K at 0.001132 (HUSP-ULL); LEVIATHAN at 0.014644 (HUSP-SP); LEVIATHAN at 0.01553 (HUSP-ULL); LEVIATHAN at 0.01647 (HUSP-ULL); TAFENG at 0.002812 (HUSP-ULL)). The fastest baseline's runtime over the proposal's, per cell: median 0.90, largest 2.02, smallest 0.38; above one, the proposal is faster.

Runs that did not complete:

| database | algorithm | outcome |
|---|---|---|
| DUNNHUMBY | IncUSP-Miner+ | out of memory at 1 of the four thresholds |
| DUNNHUMBY | Pre-HUSPM | out of memory at 1 of the four thresholds |
| DUNNHUMBY | USpan | over the time limit at 1 of the four thresholds |

## Runtime at the default threshold

Seconds; the fastest in bold.

| database | threshold | HUSPs | USpan | HUSP-ULL | HUSP-SP | IncUSP-Miner+ | Pre-HUSPM | Pre-HUSPM-Joint |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| SIGN | 0.065911 | 583 | 2.46 | **0.10** | 0.11 | 1.61 | 0.75 | 0.12 |
| LEVIATHAN | 0.01553 | 107 | 4.90 | **0.23** | 0.23 | 3.14 | 0.79 | 0.23 |
| FIFA | 0.059637 | 18 | 675 | 4.07 | **3.20** | 353 | 127 | 4.50 |
| BIBLE | 0.022895 | 192 | 9.56 | **0.58** | 0.58 | 4.53 | 1.18 | 0.71 |
| BMS1 | 0.013597 | 21 | 0.62 | 0.03 | 0.10 | 0.29 | 0.32 | **0.03** |
| KOSARAK | 0.025 | 10 | 34.7 | 0.58 | **0.36** | 8.12 | 0.95 | 0.39 |
| C8T1S5I8N5K | 0.001132 | 5067 | 1.20 | **0.10** | 0.11 | 2.58 | 0.71 | 0.19 |
| TAFENG | 0.003557 | 6 | 0.39 | 0.15 | 0.24 | 0.58 | **0.12** | 0.16 |
| DUNNHUMBY | 0.001464 | 17 | 205 | 2.00 | 5.82 | 220 | 805 | **1.11** |

## Against each baseline, all cells

Geometric mean of the baseline's runtime over the proposal's, on the cells where both completed.

| baseline | baseline / proposal | cells |
|---|---:|---:|
| USpan | 21.13 | 35 |
| IncUSP-Miner+ | 14.43 | 35 |
| Pre-HUSPM | 4.61 | 35 |
| HUSP-SP | 1.20 | 36 |
| HUSP-ULL | 0.98 | 36 |

`peak_heap_mb` in these rows is used heap, garbage included, and is not the memory result; the
live set is in `results/campaign-mem-sampled/SC1`.
