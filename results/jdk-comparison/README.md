# Two builds of JDK 25 on the measurement machine

> The baseline arms compared here were measured with their previous implementations and have
> since been removed for re-measurement; the comparison stands for the code it was run on.

Every arm ran on `PC-THAI`. Part of the arms ran on Microsoft OpenJDK 25.0.4 and part on
Eclipse Temurin 25.0.4.1+1, the build pinned in `environment.lock.json`; each arm's `run.log`
names its JVM where it was recorded.

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

The memory campaign's arms re-measured on the new build (`results/campaign-mem-replicates/s0r1`,
38 arms, all on Temurin 25.0.4.1 as their logs record): SC1 on SIGN, TAFENG, C8T1S5I8N5K, BMS1
and KOSARAK, all six algorithms; SC2's baselines on SIGN and TAFENG.

| comparison | cells | factor | 95% interval |
|---|---:|---:|---|
| live set SC1: old build / new build | 120 | 0.9988 | 0.9652 - 1.0268 |
| peak live set SC2: old build / new build | 8 | 0.9768 | 0.9496 - 0.9982 |

SC1 has no difference: the per-cell ratio has median 1.000 and the per-algorithm medians run
from 1.000 to 1.012. In SC2 the median live set over the stream agrees within 0.989 - 1.016 for
all eight arms, and the largest, IncUSP-Miner+ on SIGN, within 0.04 percent; the factor below 1
comes from the peaks of small arms, which differ by a few MB (Remine-from-scratch on TAFENG,
69 against 77 MB). A peak is one sample, so it carries the sampling noise of the live-set
measurement. The measured live sets are used as they are. Counters: 0 of 480 differ.

A single cell's live set can move far between two measurements - 0.84 to 1.80 among the SC1
cells of at least 50 MB - so memory claims rest on geometric means over cells, not on one cell.
