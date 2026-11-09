# SC6, insertion, deletion and modification mixed

Twenty operations per database and seed - exactly 14 insertions, 3 deletions and 3 modifications
in an order drawn from `Random(seed + name.hashCode())`, each a hundredth of the incremental
stream - replayed by the proposal and by remining. SIGN, LEVIATHAN and C8T1S5I8N5K, five seeds
(42, 1337, 2024, 31415, 271828); seed 42 is in the main tree, the others in
`results/campaign-replicates/s1r0` to `s4r0`, each in a fresh JVM. Measured on `PC-THAI` (see `results/machine/PC-THAI.json` and `environment.lock.json`), `-Xmx48g`, one JVM per arm. Written by `analysis/scenario_readmes.py` from the arms' CSVs; rerun it rather than editing numbers here.

## Every operation agrees

At every one of the 500 operations the two arms report the same HUSP count:
yes.

## Per database, over the five seeds

The proposal's time over remining's, summed over the twenty operations of a seed; the two arms
replay the same plan at a seed, so the ratio is paired by seed.

| database | seeds | ratio, mean ± sd | min | max |
|---|---:|---:|---:|---:|
| C8T1S5I8N5K | 5 | 0.584 ± 0.023 | 0.546 | 0.605 |
| DUNNHUMBY | 5 | 0.870 ± 0.065 | 0.797 | 0.973 |
| LEVIATHAN | 5 | 0.714 ± 0.029 | 0.664 | 0.742 |
| SIGN | 5 | 0.523 ± 0.012 | 0.502 | 0.530 |
| TAFENG | 5 | 0.524 ± 0.022 | 0.504 | 0.562 |

## By kind of operation, all seeds together

| database | kind | rescans | proposal | remine | remine / proposal |
|---|---|---:|---:|---:|---:|
| C8T1S5I8N5K | insertion | 70 / 70 | 4.50 s | 8.00 s | 1.8 |
| C8T1S5I8N5K | deletion | 15 / 15 | 0.91 s | 1.57 s | 1.7 |
| C8T1S5I8N5K | modification | 15 / 15 | 1.14 s | 1.63 s | 1.4 |
| DUNNHUMBY | insertion | 70 / 70 | 63.32 s | 73.27 s | 1.2 |
| DUNNHUMBY | deletion | 15 / 15 | 12.83 s | 15.47 s | 1.2 |
| DUNNHUMBY | modification | 15 / 15 | 14.35 s | 15.55 s | 1.1 |
| LEVIATHAN | insertion | 70 / 70 | 12.42 s | 17.50 s | 1.4 |
| LEVIATHAN | deletion | 15 / 15 | 2.64 s | 3.70 s | 1.4 |
| LEVIATHAN | modification | 15 / 15 | 2.65 s | 3.67 s | 1.4 |
| SIGN | insertion | 70 / 70 | 5.01 s | 9.76 s | 1.9 |
| SIGN | deletion | 15 / 15 | 1.05 s | 1.95 s | 1.8 |
| SIGN | modification | 15 / 15 | 1.08 s | 1.97 s | 1.8 |
| TAFENG | insertion | 53 / 70 | 5.30 s | 9.44 s | 1.8 |
| TAFENG | deletion | 4 / 15 | 0.38 s | 1.92 s | 5.1 |
| TAFENG | modification | 13 / 15 | 1.27 s | 1.88 s | 1.5 |
