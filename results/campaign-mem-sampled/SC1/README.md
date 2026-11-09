# SC1 memory: the live set of a static mine on `D_init`

The runs of SC1 measured for memory instead of time: at every sample the runner forces a full collection and reads the heap right after, so runtimes are void. The wait between samples is the larger of 20 ms and four
times the last collection. A mine that ends before the sampler has taken 5 readings
while it runs is run again, each time as a fresh instance, until it has (`live_repeats` on the row:
the mines the reading rests on; the cap is 120 s). Over 249 completed cells the fewest
readings taken while a mine runs is 5; 171 cells were run more
than once, at most 44 times. Measured on `PC-THAI` (see `results/machine/PC-THAI.json` and `environment.lock.json`), `-Xmx48g`, one JVM per arm. Written by `analysis/scenario_readmes.py` from the arms' CSVs; rerun it rather than editing numbers here.

Runs that did not complete:

| database | algorithm | outcome |
|---|---|---|
| DUNNHUMBY | IncUSP-Miner+ | out of memory at 1 of the four thresholds |
| DUNNHUMBY | Pre-HUSPM | out of memory at 1 of the four thresholds |
| DUNNHUMBY | USpan | over the time limit at 1 of the four thresholds |

## Live set at the default threshold

Megabytes. A dagger marks a run with fewer than 5 readings taken while it ran (0 cells);
a live set below 1 MB reads as 0.

| database | USpan | HUSP-ULL | HUSP-SP | IncUSP-Miner+ | Pre-HUSPM | Pre-HUSPM-Joint |
|---|---:|---:|---:|---:|---:|---:|
| SIGN | 8 | 5 | 1 | 273 | 38 | 4 |
| LEVIATHAN | 24 | 12 | 6 | 503 | 45 | 15 |
| FIFA | 71 | 34 | 22 | 34,325 | 300 | 56 |
| BIBLE | 68 | 37 | 27 | 886 | 143 | 65 |
| BMS1 | 24 | 25 | 12 | 48 | 38 | 33 |
| KOSARAK | 106 | 70 | 56 | 361 | 221 | 93 |
| C8T1S5I8N5K | 39 | 39 | 25 | 819 | 95 | 54 |
| TAFENG | 68 | 38 | 31 | 69 | 109 | 31 |
| DUNNHUMBY | 182 | 68 | 62 | 9,261 | 7,972 | 169 |

## Against each baseline

Geometric mean of the baseline's live set over the proposal's, on the cells where both completed
and both read at least 1 MB; above one, the proposal holds less. Cells left out and why:
`figures/campaign/table_SC1_memory_ratios.csv`.

| baseline | all four thresholds | cells used | cells where the baseline holds less | default threshold |
|---|---:|---:|---:|---:|
| USpan | 1.15 | 35 of 36 | 8 | 1.22 |
| HUSP-ULL | 0.73 | 36 of 36 | 30 | 0.74 |
| HUSP-SP | 0.43 | 36 of 36 | 35 | 0.44 |
| IncUSP-Miner+ | 13.50 | 35 of 36 | 0 | 18.01 |
| Pre-HUSPM | 2.88 | 35 of 36 | 1 | 4.01 |
