# PROBE: run-to-run spread of DUNNHUMBY SC2 under three JVM settings

Development machine (Apple M-series, macOS), not the measurement machine. These runtimes are not
results; the probe only asks which setting narrows the spread between JVMs that run identical code
on identical data with the same seed.

Pre-HUSPM-Joint, SC2, DUNNHUMBY, 6 rounds, settings interleaved A B C in each round, one JVM per run
(`run_probe.sh`, heap ceiling 16 GB):

| setting | JVM options added | totals of the 100 updates (s) | max/min |
|---|---|---|---|
| A | none | 222.5, 218.1, 224.4, 231.6, 216.5, 245.8 | 1.135 |
| B | `-Xbatch` | 219.1, 238.8, 217.3, 239.1, 215.6, 231.6 | 1.109 |
| C | `-Xms16g -XX:+AlwaysPreTouch` | 239.2, 251.8, 210.6, 235.6, 238.3, 221.1 | 1.195 |

Every run returned 1,630 patterns and visited 1,463,534 nodes over 100 updates.

Slowest over fastest run, per update (quantiles 10/50/90%): A 1.083/1.148/1.190, B 0.970/1.016/1.299,
C 1.116/1.185/1.292. In A and C the slow run is slower at almost every update, as on the measurement
machine; in B the difference is concentrated in some updates.

Neither synchronous compilation (B) nor a fixed, pre-touched heap (C) halves the spread of A, so
neither a race with the background compiler nor heap growth explains it. The machine was not
otherwise idle: a few short analysis commands ran during the probe.
