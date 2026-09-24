# SC3, static mining on the whole database

SC1 mines `D_init`; SC3 mines the whole database, at the same four thresholds per database.
SUBSET_3: SIGN, LEVIATHAN, C8T1S5I8N5K. Six algorithms, 72 runs, `PC-THAI`, JDK 25,
`-Xmx48g`, one JVM per arm, one repeat. About 17 minutes of machine time.

## Every run completed and every answer agrees

All 72 runs finished; none ran out of memory. For each of the 12 (database, threshold) pairs,
all six algorithms report the same HUSP count.

Pre-HUSPM completes on LEVIATHAN here at all four thresholds, although on `D_init` in SC1 it
exhausts 48 GB at three of them. The threshold is a ratio of total utility, so the same ratio
over a different scope selects a different set of patterns, and on LEVIATHAN the `D_init` scope
is the harder one for Pre-HUSPM.

## Runtime

Seconds.

| database | threshold | HUSPs | USpan | HUSP-ULL | HUSP-SP | IncUSP-Miner+ | Pre-HUSPM | Adaptive |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| SIGN | 0.049134 | 793 | 11.8 | 4.8 | 5.8 | 6.9 | 2.9 | **1.1** |
| SIGN | 0.065911 | 141 | 6.2 | 2.5 | 3.2 | 3.8 | 1.4 | **0.4** |
| SIGN | 0.083371 | 31 | 3.7 | 1.5 | 1.9 | 2.3 | 0.9 | **0.2** |
| SIGN | 0.091052 | 16 | 3.0 | 1.3 | 1.6 | 1.9 | 0.7 | **0.2** |
| LEVIATHAN | 0.014644 | 173 | 18.4 | 36.8 | 7.9 | 50.4 | 18.1 | **2.0** |
| LEVIATHAN | 0.015530 | 135 | 16.7 | 35.0 | 7.2 | 46.5 | 14.5 | **1.4** |
| LEVIATHAN | 0.016470 | 116 | 15.4 | 32.4 | 6.6 | 43.5 | 13.4 | **1.3** |
| LEVIATHAN | 0.017987 | 81 | 13.8 | 29.1 | 5.9 | 39.1 | 11.7 | **1.2** |
| C8T1S5I8N5K | 0.001099 | 4256 | 37.5 | 36.2 | 6.7 | 6.0 | 3.9 | **2.4** |
| C8T1S5I8N5K | 0.001132 | 3438 | 33.0 | 26.5 | 5.4 | 4.8 | 3.5 | **1.3** |
| C8T1S5I8N5K | 0.001200 | 2110 | 28.5 | 22.9 | 4.4 | 3.9 | 2.9 | **1.1** |
| C8T1S5I8N5K | 0.001236 | 1484 | 25.0 | 20.1 | 4.0 | 3.4 | 2.6 | **1.0** |

The proposal is the fastest in all 12 cells: against the fastest baseline in each, 3.88 times
faster at the median, 1.63 at worst - C8T1S5I8N5K's lowest threshold, against Pre-HUSPM - and
4.99 at best.

## Consistent with SC1

Geometric mean speedup over each baseline, here and on `D_init`:

| baseline | SC3, whole database | SC1, `D_init` |
|---|---:|---:|
| USpan | 15.3 | 16.3 |
| HUSP-ULL | 13.7 | 14.0 |
| IncUSP-Miner+ | 9.6 | 8.4 |
| HUSP-SP | 5.0 | 4.9 |
| Pre-HUSPM | 4.3 | 3.8 |

The ordering of the baselines is the same on both scopes, and so is the size of the margin over
each; HUSP-SP and Pre-HUSPM remain the closest. SC3 has no cell the proposal loses, where SC1
had two, both on TAFENG, which is not in SUBSET_3.

Memory is not reported here for the reason given in `results/campaign/SC1/README.md`:
`peak_heap_mb` counts uncollected garbage, and the live-set campaign is what measures memory.
