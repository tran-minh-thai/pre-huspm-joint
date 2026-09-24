# SC5, how the stream is cut

The same incremental stream cut four ways - EVEN, INCREASING, DECREASING, BURST - at four
increment counts, `K` in {100, 67, 50, 25}, holding the total incremental data fixed, as in
Yan et al. Figs. 5-7. SUBSET_3 only: SIGN, LEVIATHAN, C8T1S5I8N5K. Five arms, `D_init` per
`common.Datasets`, one repeat, `PC-THAI`, JDK 25, `-Xmx48g`. 8.97 hours of machine time, most
of it IncUSP-Miner+.

Every arm holds 984 rows, which is what 4 strategies x (101 + 68 + 51 + 26) comes to, and every
(database, K, strategy) cell of the proposal has exactly K increments. That depends on commit
873a196: before it, DECREASING left up to ten increments empty on SIGN and the arms skipped
them without a word.

## What completed

Pre-HUSPM and Pre-HUSPM-TSU exhaust 48 GB on LEVIATHAN's `D_init`, as in SC2, so all 968 of
their increments there fail. IncUSP-Miner+ fails 279 of 968 on LEVIATHAN. Everything else
completes.

## Against each baseline

The proposal is the fastest in 41 of 48 cells, 1.18 times faster than the fastest baseline at
the median and 1.79 at best. The seven cells it does not win are all against
remine-from-scratch, all at `K` of 25 or 50, and all within two percent: at a coarse cut every
increment rescans and the proposal is a remine.

Geometric mean speedup, on the cells where both completed:

| baseline | speedup | cells |
|---|---:|---:|
| Pre-HUSPM-TSU | 3.80 | 32 |
| Pre-HUSPM | 2.79 | 32 |
| IncUSP-Miner+ | 2.52 | 32 |
| Remine-from-scratch | 1.29 | 48 |

Remine is the closest by design: it runs the proposal's own static miner after every increment,
so what separates them is only the maintenance, never a faster miner.

## The finer the cut, the wider the gap

Averaged over the four strategies:

| database | K | increments that rescan | proposal's time / remine's |
|---|---:|---:|---:|
| SIGN | 25 | 96 % | 0.97 |
| SIGN | 50 | 82 % | 0.79 |
| SIGN | 67 | 72 % | 0.69 |
| SIGN | 100 | 52 % | **0.50** |
| C8T1S5I8N5K | 25 | 97 % | 0.84 |
| C8T1S5I8N5K | 50 | 88 % | 0.77 |
| C8T1S5I8N5K | 67 | 81 % | 0.70 |
| C8T1S5I8N5K | 100 | 68 % | **0.58** |
| LEVIATHAN | 25 | 99 % | 0.99 |
| LEVIATHAN | 50 | 96 % | 0.96 |
| LEVIATHAN | 67 | 94 % | 0.94 |
| LEVIATHAN | 100 | 85 % | **0.84** |

An increment that fits inside the insertion safety budget needs no rescan, and a finer cut puts
more increments inside it. On every database the share that rescans falls with `K` and the
proposal's time falls with it, monotonically. At `K = 25` it has converged to remining.

## More rescans than Pre-HUSPM, and still faster

The proposal rescans more often than Pre-HUSPM at every `K`: on SIGN 52 against 19 at
`K = 100`, on C8T1S5I8N5K 68 against 38. Pre-HUSPM's SWUmax trigger under-counts the
perturbation, which is the unsoundness `results/verify-swumax` measures - on C8T1S5I8N5K it
loses up to 19 patterns. The proposal is nonetheless 2.79 times faster by geometric mean,
because each of its rescans costs less.

## How the stream is cut

At `K = 100`:

| database | strategy | proposal | remine | ratio | rescans |
|---|---|---:|---:|---:|---:|
| SIGN | EVEN | 17.4 s | 30.8 s | 0.56 | 59 |
| SIGN | INCREASING | 15.3 s | 29.5 s | 0.52 | 53 |
| SIGN | DECREASING | 15.4 s | 32.1 s | 0.48 | 52 |
| SIGN | BURST | 13.3 s | 30.8 s | 0.43 | 45 |
| LEVIATHAN | EVEN | 94.1 s | 96.2 s | 0.98 | 98 |
| LEVIATHAN | INCREASING | 79.1 s | 84.5 s | 0.94 | 91 |
| LEVIATHAN | DECREASING | 77.4 s | 108.9 s | 0.71 | 76 |
| LEVIATHAN | BURST | 71.5 s | 98.3 s | 0.73 | 76 |
| C8T1S5I8N5K | EVEN | 63.0 s | 93.5 s | 0.67 | 80 |
| C8T1S5I8N5K | INCREASING | 53.2 s | 87.0 s | 0.61 | 70 |
| C8T1S5I8N5K | DECREASING | 54.3 s | 101.8 s | 0.53 | 67 |
| C8T1S5I8N5K | BURST | 44.6 s | 90.3 s | 0.49 | 56 |

EVEN is the least favourable cut for the proposal on all three databases. With uneven
increments the small ones fall inside the budget and skip the rescan while the large ones would
rescan under any cut; with even increments all of them sit on the same side of the budget. SC2
measures at EVEN, so its figures are the conservative ones.

Remine's own time also depends on the cut. DECREASING puts the large increments first, the
database reaches its full size sooner, and every later remine works on more data.
