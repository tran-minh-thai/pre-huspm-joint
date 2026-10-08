# Two builds of JDK 25 on the measurement machine

Every arm ran on `PC-THAI`. The arms measured up to 2026-10-05 ran on Microsoft OpenJDK
25.0.4, the build `setup-may-do.ps1` installs; the arms measured from 2026-10-08 ran on Eclipse
Temurin 25.0.4.1, as their logs record. The earlier logs record no JVM, so the first attribution
rests on the setup script. `campaign.ps1` now refuses any build but the pinned one.

`analysis/jdk_comparison.py` compares configurations measured on both builds, cell by cell,
and reports the geometric mean of old/new with a bootstrap 95% interval, beside the same
statistic between two runs on the new build.

## Runtime - `runtime/`

SC1, all algorithms, the cells of at least one second: the first run (old build) against the
two repeats (new build).

| comparison | cells | factor | 95% interval |
|---|---:|---:|---|
| old build / new build | 130 | 0.9947 | 0.9901 - 0.9997 |
| reference: new run 2 / new run 3, one build | 130 | 1.0068 | 0.9988 - 1.0147 |

Per algorithm the old/new factor runs from 0.985 to 1.005, in both directions (`runtime/`).
The difference between the builds is no larger than the difference between two sessions on
one build, so it cannot be attributed to the build, and the measured runtimes are used as
they are, without correction. Counters are identical: 0 of 748 differ across the three runs.

## Memory - `memory/`

Pending: the live set is compared once the memory arms re-measured on the new build are in
(`results/campaign-mem-replicates/s0r1`: SC1 on SIGN, TAFENG, C8T1S5I8N5K, BMS1 and KOSARAK,
all six algorithms; SC2 baselines on SIGN and TAFENG).
