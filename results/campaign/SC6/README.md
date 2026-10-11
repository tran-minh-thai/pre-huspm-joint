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
| C8T1S5I8N5K | 5 | 0.657 ± 0.039 | 0.607 | 0.715 |
| LEVIATHAN | 5 | 0.832 ± 0.018 | 0.810 | 0.852 |
| SIGN | 5 | 0.774 ± 0.032 | 0.718 | 0.798 |

## By kind of operation, all seeds together

| database | kind | rescans | proposal | remine | remine / proposal |
|---|---|---:|---:|---:|---:|
| C8T1S5I8N5K | insertion | 57 / 70 | 6.28 s | 9.55 s | 1.5 |
| C8T1S5I8N5K | deletion | 1 / 15 | 0.67 s | 2.05 s | 3.0 |
| C8T1S5I8N5K | modification | 12 / 15 | 1.95 s | 1.98 s | 1.0 |
| LEVIATHAN | insertion | 70 / 70 | 16.86 s | 17.31 s | 1.0 |
| LEVIATHAN | deletion | 0 / 15 | 0.03 s | 3.68 s | 126.8 |
| LEVIATHAN | modification | 15 / 15 | 3.61 s | 3.66 s | 1.0 |
| SIGN | insertion | 62 / 70 | 8.12 s | 9.45 s | 1.2 |
| SIGN | deletion | 0 / 15 | 0.04 s | 1.95 s | 46.4 |
| SIGN | modification | 15 / 15 | 2.18 s | 1.99 s | 0.9 |
