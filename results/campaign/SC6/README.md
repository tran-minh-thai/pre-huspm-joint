# SC6, insertion, deletion and modification mixed

Twenty operations per database and seed - exactly 14 insertions, 3 deletions and 3 modifications
in an order drawn from `Random(seed + name.hashCode())`, each a hundredth of the incremental
stream - replayed by the proposal and by remining. SIGN, LEVIATHAN and C8T1S5I8N5K, five seeds
(42, 1337, 2024, 31415, 271828); seed 42 is in the main tree, the others in
`results/campaign-replicates/s1r0` to `s4r0`, each in a fresh JVM. Measured on `PC-THAI` (see `results/machine/PC-THAI.json` and `environment.lock.json`), `-Xmx48g`, one JVM per arm. Written by `analysis/scenario_readmes.py` from the arms' CSVs; rerun it rather than editing numbers here.

## Every operation agrees

At every one of the 300 operations the two arms report the same HUSP count:
yes.

## Per database, over the five seeds

The proposal's time over remining's, summed over the twenty operations of a seed; the two arms
replay the same plan at a seed, so the ratio is paired by seed.

| database | seeds | ratio, mean ± sd | min | max |
|---|---:|---:|---:|---:|
| C8T1S5I8N5K | 5 | 0.686 ± 0.009 | 0.674 | 0.696 |
| LEVIATHAN | 5 | 0.833 ± 0.035 | 0.775 | 0.870 |
| SIGN | 5 | 0.773 ± 0.026 | 0.728 | 0.793 |

## By kind of operation, all seeds together

| database | kind | rescans | proposal | remine | remine / proposal |
|---|---|---:|---:|---:|---:|
| C8T1S5I8N5K | insertion | 57 / 70 | 35.08 s | 45.43 s | 1.3 |
| C8T1S5I8N5K | deletion | 1 / 15 | 1.15 s | 9.67 s | 8.4 |
| C8T1S5I8N5K | modification | 12 / 15 | 8.19 s | 9.71 s | 1.2 |
| LEVIATHAN | insertion | 70 / 70 | 41.38 s | 42.29 s | 1.0 |
| LEVIATHAN | deletion | 0 / 15 | 0.03 s | 9.15 s | 277.2 |
| LEVIATHAN | modification | 15 / 15 | 8.92 s | 9.08 s | 1.0 |
| SIGN | insertion | 62 / 70 | 16.06 s | 18.59 s | 1.2 |
| SIGN | deletion | 0 / 15 | 0.04 s | 3.91 s | 93.1 |
| SIGN | modification | 15 / 15 | 4.32 s | 3.96 s | 0.9 |
