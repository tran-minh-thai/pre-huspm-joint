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
| C8T1S5I8N5K | 5 | 0.668 ± 0.037 | 0.631 | 0.724 |
| LEVIATHAN | 5 | 0.860 ± 0.011 | 0.849 | 0.873 |
| SIGN | 5 | 0.757 ± 0.036 | 0.722 | 0.807 |

## By kind of operation, all seeds together

| database | kind | rescans | proposal | remine | remine / proposal |
|---|---|---:|---:|---:|---:|
| C8T1S5I8N5K | insertion | 57 / 70 | 6.27 s | 9.55 s | 1.5 |
| C8T1S5I8N5K | deletion | 1 / 15 | 0.81 s | 2.05 s | 2.5 |
| C8T1S5I8N5K | modification | 12 / 15 | 1.99 s | 1.98 s | 1.0 |
| LEVIATHAN | insertion | 70 / 70 | 16.79 s | 17.31 s | 1.0 |
| LEVIATHAN | deletion | 3 / 15 | 0.74 s | 3.68 s | 4.9 |
| LEVIATHAN | modification | 15 / 15 | 3.67 s | 3.66 s | 1.0 |
| SIGN | insertion | 62 / 70 | 7.50 s | 9.45 s | 1.3 |
| SIGN | deletion | 2 / 15 | 0.28 s | 1.95 s | 6.9 |
| SIGN | modification | 15 / 15 | 2.34 s | 1.99 s | 0.8 |
