# SC1 memory: the live set of a static mine on `D_init`

The runs of SC1 measured for memory instead of time: at every sample the runner forces a full collection and reads the heap right after, so runtimes are void. The wait between samples is the larger of 20 ms and four
times the last collection. Measured on `PC-THAI` (see `results/machine/PC-THAI.json` and `environment.lock.json`), `-Xmx48g`, one JVM per arm. Written by `analysis/scenario_readmes.py` from the arms' CSVs; rerun it rather than editing numbers here.

Every run completed.

## Live set at the default threshold

Megabytes. A dagger marks a run read at its start and end only (14 cells), because it ended
before the first sample; a live set below 1 MB reads as 0.

| database | USpan | HUSP-ULL | HUSP-SP | IncUSP-Miner+ | Pre-HUSPM | Pre-HUSPM-Joint |
|---|---:|---:|---:|---:|---:|---:|
| SIGN | 7 | 1 | 1 | 283 | 41 | 4 |
| LEVIATHAN | 22 | 5 | 6 | 518 | 45 | 13 |
| FIFA | 72 | 36 | 23 | 34,329 | 282 | 45 |
| BIBLE | 63 | 38 | 22 | 852 | 135 | 40 |
| BMS1 | 24 | 0 † | 12 † | 41 | 23 | 16 † |
| KOSARAK | 106 | 0 † | 56 † | 353 | 32 † | 17 † |
| C8T1S5I8N5K | 38 | 2 † | 13 † | 812 | 82 | 26 † |
| TAFENG | 19 | 0 † | 25 † | 48 | 14 † | 17 † |

## Against each baseline

Geometric mean of the baseline's live set over the proposal's, on the cells where both completed
and both read at least 1 MB; above one, the proposal holds less. Cells left out and why:
`figures/campaign/table_SC1_memory_ratios.csv`.

| baseline | all four thresholds | cells used | cells where the baseline holds less | default threshold |
|---|---:|---:|---:|---:|
| USpan | 1.51 | 32 of 32 | 2 | 1.81 |
| HUSP-ULL | 0.38 | 22 of 32 | 20 | 0.35 |
| HUSP-SP | 0.61 | 31 of 32 | 24 | 0.70 |
| IncUSP-Miner+ | 20.93 | 32 of 32 | 0 | 26.09 |
| Pre-HUSPM | 2.61 | 32 of 32 | 7 | 2.92 |
