# Does the result-map cap change what the miner returns?

Kind: invariant (pattern counts; machine-independent). Not a performance measurement.

At the commit below, `PreHUSPMAdaptiveAlgo.maxResultPatterns` (default 1,500,000) made the
miner drop the weaker half of its result map whenever the map reached that size. The
mechanism has since been removed: every pattern reaching the cutoff is kept. This check mines
C8T1S5I8N5K once with the default and once with the cap disabled, at the floor ratios of
the floor-sensitivity scenario.

Code: commit 325a058 (working tree as of 2026-10-08T14:15Z), compiled with
`javac` from `src/` plus `FlushProbe.java`; run with `java -Xmx24g -cp out FlushProbe C8T1S5I8N5K <r_l> default|nocap datasets`.
JVM: openjdk version "26.0.1" 2026-04-21. Data: datasets release pinned in environment.lock.json.

| r_l | cap | large | pre-large |
|---|---|---|---|
| 0.7 | default | 3438 | 20694 |
| 0.5 | default | 3438 | 1017337 |
| 0.5 | disabled | 3438 | 31767337 |
| 0.3 | disabled | - | out of memory at `-Xmx24g` on the development machine |

The default rows reproduce results/campaign/SC4/C8T1S5I8N5K/Adaptive-Full exactly, so the
check measures the configuration that produced those rows. With the cap the miner returned
1,017,337 of 31,767,337 pre-large patterns. At r_l 0.3 without the cap the mine does not fit in a
24 GB heap; whether it fits in the 48 GB of the measurement machine is decided by the re-measurement. probe.log holds the raw output.

Affected rows: of 17,980 rows of the proposal and its variants, 2 reach 750,000 tracked
patterns, the floor that any call left after a drop: SC4 C8T1S5I8N5K at r_l 0.3 and 0.5.
