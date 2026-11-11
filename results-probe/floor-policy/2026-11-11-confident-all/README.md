# Probe: a rule that takes the threshold only on confident evidence, in explored nodes

Kind: probe on the development machine. Explored nodes are counts and do not depend on the machine;
nothing here is a time. It answers one question: is a different estimate of the absorption worth
building into the method? It is not a measurement of the method.

Why. On the long streams of DUNNHUMBY the method is slower than its fixed-threshold version
(`figures/campaign/table_floor_gap.csv`): a rescan at the lower threshold absorbs 0.39 to 1.06
operations on average, next to the break-even of the rule (about 0.30), 66 to 71 percent of the
cycles absorb nothing and consecutive cycles are uncorrelated
(`figures/campaign/table_floor_absorption.csv`). The mean of the last five cycles falls below the
break-even in 38 to 47 percent of the windows, and the method then mines at the threshold and loses
the direct update of the next operation.

Rules replayed (`analysis/floor_policy_probe.py --nodes`, `break_even(window=, confidence=)`), all with
the method's ratio rho from the node counts and its first rescan at the threshold:

* memory: the mean absorption over the last W cycles, or over all of them, in place of the last 5;
* confident: the threshold is taken only when mean + z * standard error of the absorptions is below the
  break-even; otherwise the lower threshold, whose loss per rescan is bounded by rho - 1.

The replay of the method makes the measured rescans on every operation of every stream:
15 of 15 (DUNNHUMBY long streams), 48 of 48 (long streams of the other four databases),
131 of 131 (SC2, SC5 and the mixed streams).

## DUNNHUMBY long streams: nodes over the fixed lower threshold, three seeds summed

`2026-11-11-confident-dunnhumby-long` (and `2026-11-11-memory-dunnhumby-long`, the memory rules alone)

| rule | SC10 | SC10H | SC8 | SC8P | SC9 |
|---|---:|---:|---:|---:|---:|
| the method (last 5 cycles) | 1.0358 | 1.0322 | 1.0508 | 1.0142 | 1.0023 |
| last 50 cycles | 1.0062 | 1.0085 | 1.0184 | 1.0183 | 1.0023 |
| all cycles | 1.0177 | 1.0115 | 1.0133 | 1.0345 | 1.0023 |
| last 20 cycles, z = 2 | 1.0024 | 1.0026 | 0.9997 | 1.0024 | 1.0023 |
| all cycles, z = 1 | 1.0039 | 1.0024 | 1.0017 | 1.0003 | 1.0023 |
| all cycles, z = 2 | 0.9997 | 0.9997 | 0.9997 | 1.0000 | 1.0023 |
| fixed lower threshold | 1.0000 | 1.0000 | 1.0000 | 1.0000 | 1.0000 |
| always the threshold | 1.0602 | 1.1415 | 1.0931 | 1.5933 | 1.7266 |
| offline optimum | 0.8318 | 0.8434 | 0.8357 | 0.8460 | 1.0000 |

## Long streams of SIGN, LEVIATHAN, C8T1S5I8N5K and TAFENG

`2026-11-11-confident-long-others`, 48 streams (SC8, SC8P, SC9, SC10). Every rule above makes the method's choices on
every stream:

| rule | streams above the method | worst | streams below | best | equal |
|---|---:|---:|---:|---:|---:|
| last 50 cycles | 0 | 1.0000 | 0 | 1.0000 | 48 |
| all cycles | 0 | 1.0000 | 0 | 1.0000 | 48 |
| last 20 cycles, z = 2 | 0 | 1.0000 | 0 | 1.0000 | 48 |
| all cycles, z = 1 | 0 | 1.0000 | 0 | 1.0000 | 48 |
| all cycles, z = 2 | 0 | 1.0000 | 0 | 1.0000 | 48 |

## SC2, SC5 and the mixed streams: nodes over the method's, summed per scenario

`2026-11-11-confident-all`, 131 streams

| rule | SC2 | SC5 | SC6 | SC6D | SC6B |
|---|---:|---:|---:|---:|---:|
| the method (last 5 cycles) | 1.0000 | 1.0000 | 1.0000 | 1.0000 | 1.0000 |
| last 50 cycles | 1.0010 | 1.0207 | 0.9827 | 0.9795 | 0.9889 |
| all cycles | 1.0010 | 1.0207 | 0.9827 | 0.9795 | 0.9889 |
| last 20 cycles, z = 2 | 0.9999 | 1.0675 | 1.0456 | 0.9396 | 1.0410 |
| all cycles, z = 1 | 1.0003 | 1.0372 | 1.0220 | 0.9589 | 1.0098 |
| all cycles, z = 2 | 1.0008 | 1.0916 | 1.0456 | 0.9396 | 1.0410 |
| fixed lower threshold | 1.4785 | 1.8507 | 1.3414 | 0.9342 | 1.5182 |
| always the threshold | 1.0497 | 1.0028 | 0.9818 | 1.0300 | 0.9861 |
| offline optimum | 0.9894 | 0.9631 | 0.9030 | 0.7519 | 0.9435 |

Per stream, against the method:

| rule | streams above the method | worst | streams below | best | equal |
|---|---:|---:|---:|---:|---:|
| last 50 cycles | 15 | 1.1733 | 27 | 0.8745 | 89 |
| all cycles | 15 | 1.1733 | 27 | 0.8745 | 89 |
| last 20 cycles, z = 2 | 47 | 1.3457 | 25 | 0.8183 | 59 |
| all cycles, z = 1 | 33 | 1.2350 | 27 | 0.8164 | 71 |
| all cycles, z = 2 | 46 | 1.4537 | 26 | 0.8183 | 59 |

## Reading

No rule dominates. Requiring confident evidence brings the method level with its fixed-threshold
version on the DUNNHUMBY long streams and gains on SC6D, and loses on SC5 and on SC6 and SC6B, where
the threshold is the right floor and the rule waits longer before taking it. A longer memory changes
less in both directions. The choice is a trade between two kinds of error, not a correction.
