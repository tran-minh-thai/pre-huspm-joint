# SC6, insertion, deletion and modification mixed

Twenty operations per database and seed - exactly 14 insertions, 3 deletions and 3 modifications
in an order drawn from `Random(seed + name.hashCode())`, each a hundredth of the incremental
stream - replayed by the proposal and by remining. SIGN, LEVIATHAN and C8T1S5I8N5K, five seeds
(42, 1337, 2024, 31415, 271828); seed 42 is in the main tree, the others in
`results/campaign-replicates/s1r0` to `s4r0`, each in a fresh JVM. Measured on `PC-THAI` (see `results/machine/PC-THAI.json` and `environment.lock.json`), `-Xmx48g`, one JVM per arm. Written by `analysis/scenario_readmes.py` from the arms' CSVs; rerun it rather than editing numbers here.

## Every operation agrees

At every one of the 400 operations the two arms report the same HUSP count:
yes.

## Per database, over the five seeds

The proposal's time over remining's, summed over the twenty operations of a seed; the two arms
replay the same plan at a seed, so the ratio is paired by seed.

| database | seeds | ratio, mean ± sd | min | max |
|---|---:|---:|---:|---:|
| C8T1S5I8N5K | 5 | 0.659 ± 0.014 | 0.640 | 0.677 |
| LEVIATHAN | 5 | 0.846 ± 0.026 | 0.808 | 0.881 |
| SIGN | 5 | 0.752 ± 0.032 | 0.704 | 0.785 |
| TAFENG | 5 | 0.362 ± 0.012 | 0.352 | 0.382 |

## By kind of operation, all seeds together

| database | kind | rescans | proposal | remine | remine / proposal |
|---|---|---:|---:|---:|---:|
| C8T1S5I8N5K | insertion | 57 / 70 | 6.23 s | 9.55 s | 1.5 |
| C8T1S5I8N5K | deletion | 1 / 15 | 0.73 s | 2.05 s | 2.8 |
| C8T1S5I8N5K | modification | 12 / 15 | 1.99 s | 1.98 s | 1.0 |
| LEVIATHAN | insertion | 70 / 70 | 16.48 s | 17.31 s | 1.1 |
| LEVIATHAN | deletion | 3 / 15 | 0.75 s | 3.68 s | 4.9 |
| LEVIATHAN | modification | 15 / 15 | 3.62 s | 3.66 s | 1.0 |
| SIGN | insertion | 62 / 70 | 7.47 s | 9.45 s | 1.3 |
| SIGN | deletion | 2 / 15 | 0.29 s | 1.95 s | 6.8 |
| SIGN | modification | 15 / 15 | 2.29 s | 1.99 s | 0.9 |
| TAFENG | insertion | 46 / 70 | 4.87 s | 11.84 s | 2.4 |
| TAFENG | deletion | 0 / 15 | 0.01 s | 2.44 s | 187.9 |
| TAFENG | modification | 10 / 15 | 1.14 s | 2.38 s | 2.1 |
