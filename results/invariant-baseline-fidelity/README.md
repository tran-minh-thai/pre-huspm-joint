# The baselines return the exact HUSPs

Kind: invariant (pattern sets and utilities; machine-independent). Each subdirectory holds
`run_meta.json` (command, commit, source digest, JVM, machine) and `console.txt`.

## Pre-HUSPM after every insertion batch - `prehuspm-random/`, `prehuspm-real-<DATABASE>/`

`verify.PreHuspmVerifier` compares the HUSP set and utilities of both Pre-HUSPM arms with the
exact answer after the initial mine and after every batch, and counts how many batches were
handled by a safe update rather than a rescan, so that zero mismatches is not read over a path
that did not run. Random streams: 300 trials per seed, ten batches each; seeds 4 to 7 insert
batches of 2 to 6 copies of one sequence. Real databases: `D_init` and the default threshold
of `common.Datasets`, the rest in 100 equal batches in file order.

| stream | arm | comparisons | mismatches | safe updates | mismatches after a safe update | rescans |
|---|---|---:|---:|---:|---:|---:|
| seed 1 (random) | Pre-HUSPM-SWUmax | 3300 | 0 | 1233 | 0 | 1767 |
| seed 1 (random) | Pre-HUSPM-TSU | 3300 | 0 | 1191 | 0 | 1809 |
| seed 2 (random) | Pre-HUSPM-SWUmax | 3300 | 0 | 1213 | 0 | 1787 |
| seed 2 (random) | Pre-HUSPM-TSU | 3300 | 0 | 1168 | 0 | 1832 |
| seed 3 (random) | Pre-HUSPM-SWUmax | 3300 | 0 | 1235 | 0 | 1765 |
| seed 3 (random) | Pre-HUSPM-TSU | 3300 | 0 | 1196 | 0 | 1804 |
| seed 4 (repeated sequence) | Pre-HUSPM-SWUmax | 3300 | 0 | 914 | 0 | 2086 |
| seed 4 (repeated sequence) | Pre-HUSPM-TSU | 3300 | 0 | 914 | 0 | 2086 |
| seed 5 (repeated sequence) | Pre-HUSPM-SWUmax | 3300 | 0 | 921 | 0 | 2079 |
| seed 5 (repeated sequence) | Pre-HUSPM-TSU | 3300 | 0 | 921 | 0 | 2079 |
| seed 6 (repeated sequence) | Pre-HUSPM-SWUmax | 3300 | 0 | 909 | 0 | 2091 |
| seed 6 (repeated sequence) | Pre-HUSPM-TSU | 3300 | 0 | 909 | 0 | 2091 |
| seed 7 (repeated sequence) | Pre-HUSPM-SWUmax | 3300 | 0 | 867 | 0 | 2133 |
| seed 7 (repeated sequence) | Pre-HUSPM-TSU | 3300 | 0 | 867 | 0 | 2133 |
| C8T1S5I8N5K (real) | Pre-HUSPM-SWUmax | 101 | 0 | 20 | 0 | 80 |
| C8T1S5I8N5K (real) | Pre-HUSPM-TSU | 101 | 0 | 0 | 0 | 100 |
| LEVIATHAN (real) | Pre-HUSPM-SWUmax | 101 | 0 | 2 | 0 | 98 |
| LEVIATHAN (real) | Pre-HUSPM-TSU | 101 | 0 | 0 | 0 | 100 |
| SIGN (real) | Pre-HUSPM-SWUmax | 101 | 0 | 41 | 0 | 59 |
| SIGN (real) | Pre-HUSPM-TSU | 101 | 0 | 41 | 0 | 59 |
| TAFENG (real) | Pre-HUSPM-SWUmax | 101 | 0 | 44 | 0 | 56 |
| TAFENG (real) | Pre-HUSPM-TSU | 101 | 0 | 0 | 0 | 100 |

On C8T1S5I8N5K, LEVIATHAN and TAFENG the TSU test rescans at every batch, so its safe update is
checked on the random streams and on SIGN only.

## Static agreement at the default thresholds - `agreement-all/`

`verify.BaselineAgreement ALL` compares the HUSP set of every miner with USpan's on `D_init`.
This run, on the development machine with `-Xmx20g`, compared every miner on SIGN, LEVIATHAN,
BIBLE and BMS1 and the three static miners on FIFA, all equal to USpan; IncUSP-Miner+
exhausted the heap on FIFA, which stopped the run before TAFENG, C8T1S5I8N5K and KOSARAK.
`.\campaign.ps1 -Calibrate` runs the same check with `-Xmx48g` on the measurement machine and
writes it to `results/calibration/agreement.log`.
