# SC1 memory: the live set of a static mine on `D_init`

The runs of SC1 measured for memory instead of time: at every sample the runner forces a full collection and reads the heap right after, so runtimes are void. The wait between samples is the larger of 20 ms and four
times the last collection. A mine that ends before the sampler has taken 5 readings
while it runs is run again, each time as a fresh instance, until it has (`live_repeats` on the row:
the mines the reading rests on; the cap is 120 s). Over 192 completed cells the fewest
readings taken while a mine runs is 5; 129 cells were run more
than once, at most 44 times. Measured on `PC-THAI` (see `results/machine/PC-THAI.json` and `environment.lock.json`), `-Xmx48g`, one JVM per arm. Written by `analysis/scenario_readmes.py` from the arms' CSVs; rerun it rather than editing numbers here.

Every run completed.

## Live set at the default threshold

Megabytes. A dagger marks a run with fewer than 5 readings taken while it ran (0 cells);
a live set below 1 MB reads as 0.

| database | USpan | HUSP-ULL | HUSP-SP | IncUSP-Miner+ | Pre-HUSPM | Pre-HUSPM-Joint |
|---|---:|---:|---:|---:|---:|---:|
| SIGN | 8 | 5 | 1 | 272 | 38 | 4 |
| LEVIATHAN | 24 | 12 | 6 | 501 | 40 | 13 |
| FIFA | 71 | 34 | 22 | 34,311 | 286 | 47 |
| BIBLE | 68 | 37 | 27 | 876 | 132 | 49 |
| BMS1 | 24 | 25 | 12 | 41 | 34 | 23 |
| KOSARAK | 106 | 70 | 56 | 340 | 211 | 63 |
| C8T1S5I8N5K | 39 | 39 | 25 | 763 | 82 | 48 |
| TAFENG | 68 | 38 | 31 | 85 | 116 | 30 |

## Against each baseline

Geometric mean of the baseline's live set over the proposal's, on the cells where both completed
and both read at least 1 MB; above one, the proposal holds less. Cells left out and why:
`figures/campaign/table_SC1_memory_ratios.csv`.

| baseline | all four thresholds | cells used | cells where the baseline holds less | default threshold |
|---|---:|---:|---:|---:|
| USpan | 1.37 | 32 of 32 | 5 | 1.50 |
| HUSP-ULL | 0.93 | 32 of 32 | 20 | 0.97 |
| HUSP-SP | 0.52 | 32 of 32 | 30 | 0.54 |
| IncUSP-Miner+ | 16.09 | 32 of 32 | 0 | 18.68 |
| Pre-HUSPM | 3.05 | 32 of 32 | 0 | 3.34 |
