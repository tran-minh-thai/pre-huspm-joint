# SC5, how the stream is cut

> **Being re-measured.** The proposal's arms here are queued again under the `joint` rescan
> rule (commit d5b47ab and later). Every figure for the proposal below - Adaptive-Full and the
> proposed_* ablations of the incremental part - comes from the superseded `iaumax` rule at
> commit 4e3f256 and is not to be cited. The baselines' figures stand.

The same incremental stream cut four ways - EVEN, INCREASING, DECREASING, BURST - at four
increment counts, `K` in {100, 67, 50, 25}, holding the total incremental data fixed, as in
Yan et al. Figs. 5-7. SUBSET_3 only: SIGN, LEVIATHAN, C8T1S5I8N5K. Five arms, `D_init` per
`common.Datasets`, one repeat, `PC-THAI`, JDK 25, `-Xmx48g`. 9.14 hours of machine time, most
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

The proposal is the fastest in 46 of 48 cells, 1.18 times faster than the fastest baseline at
the median and 1.82 at best. The two cells it does not win are SIGN at `K = 25`, EVEN and
BURST, both against remine-from-scratch and both within 1.5 percent: at a coarse cut every
increment rescans and the proposal is a remine.

Geometric mean speedup, on the cells where both completed:

| baseline | speedup | cells |
|---|---:|---:|
| Pre-HUSPM-TSU | 3.76 | 32 |
| Pre-HUSPM | 2.89 | 32 |
| IncUSP-Miner+ | 2.64 | 32 |
| Remine-from-scratch | 1.28 | 48 |

Remine is the closest by design: it runs the proposal's own static miner after every increment,
so what separates them is only the maintenance, never a faster miner.

## The finer the cut, the wider the gap

Averaged over the four strategies:

| database | K | increments that rescan | proposal's time / remine's |
|---|---:|---:|---:|
| SIGN | 25 | 96 % | 0.97 |
| SIGN | 50 | 82 % | 0.83 |
| SIGN | 67 | 72 % | 0.72 |
| SIGN | 100 | 52 % | **0.52** |
| C8T1S5I8N5K | 25 | 97 % | 0.89 |
| C8T1S5I8N5K | 50 | 88 % | 0.81 |
| C8T1S5I8N5K | 67 | 81 % | 0.74 |
| C8T1S5I8N5K | 100 | 68 % | **0.64** |
| LEVIATHAN | 25 | 99 % | 0.92 |
| LEVIATHAN | 50 | 96 % | 0.90 |
| LEVIATHAN | 67 | 94 % | 0.88 |
| LEVIATHAN | 100 | 85 % | **0.78** |

An increment that fits inside the insertion safety budget needs no rescan, and a finer cut puts
more increments inside it. On every database the share that rescans falls with `K` and the
proposal's time falls with it, monotonically. At `K = 25` almost every increment rescans and
the proposal is only 3 to 11 percent faster than remining.

## More rescans than Pre-HUSPM, and still faster

The proposal rescans more often than Pre-HUSPM at every `K`: on SIGN 52 against 19 at
`K = 100`, on C8T1S5I8N5K 68 against 38. Pre-HUSPM's SWUmax trigger under-counts the
perturbation, which is the unsoundness `results/verify-swumax` measures - on C8T1S5I8N5K it
loses up to 19 patterns. The proposal is nonetheless 2.89 times faster by geometric mean,
because each of its rescans costs less.

## How the stream is cut

At `K = 100`:

| database | strategy | proposal | remine | ratio | rescans |
|---|---|---:|---:|---:|---:|
| SIGN | EVEN | 17.4 s | 29.7 s | 0.59 | 59 |
| SIGN | INCREASING | 15.4 s | 28.5 s | 0.54 | 53 |
| SIGN | DECREASING | 15.5 s | 30.8 s | 0.50 | 52 |
| SIGN | BURST | 13.4 s | 29.7 s | 0.45 | 45 |
| LEVIATHAN | EVEN | 86.2 s | 95.6 s | 0.90 | 98 |
| LEVIATHAN | INCREASING | 73.2 s | 83.8 s | 0.87 | 91 |
| LEVIATHAN | DECREASING | 71.6 s | 108.3 s | 0.66 | 76 |
| LEVIATHAN | BURST | 66.1 s | 97.6 s | 0.68 | 76 |
| C8T1S5I8N5K | EVEN | 61.1 s | 81.6 s | 0.75 | 80 |
| C8T1S5I8N5K | INCREASING | 51.0 s | 75.4 s | 0.68 | 70 |
| C8T1S5I8N5K | DECREASING | 52.5 s | 88.7 s | 0.59 | 67 |
| C8T1S5I8N5K | BURST | 42.9 s | 81.7 s | 0.53 | 56 |

EVEN is the least favourable cut for the proposal on all three databases. With uneven
increments the small ones fall inside the budget and skip the rescan while the large ones would
rescan under any cut; with even increments all of them sit on the same side of the budget. SC2
measures at EVEN, so its figures are the conservative ones.

Remine's own time also depends on the cut. DECREASING puts the large increments first, the
database reaches its full size sooner, and every later remine works on more data.
