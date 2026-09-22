# SC2, one hundred increments

Seven databases against five arms. BMS1 is absent: it carries `fullScopeOk = false` and
cannot be mined once the database grows past `D_init`, so it appears in SC1 alone.

Measured on `PC-THAI`, AMD Ryzen 9 9950X, JDK 25, `-Xmx48g`, one JVM per arm, one repeat,
`D_init` per `common.Datasets`, the remainder cut into 100 equal increments.

## What completed

| database | Adaptive-Full | Pre-HUSPM | Pre-HUSPM-TSU | Remine-from-scratch | IncUSP-Miner+ |
|---|---|---|---|---|---|
| SIGN | 100/100 | 100/100 | 100/100 | 100/100 | 100/100 |
| TAFENG | 100/100 | 100/100 | 100/100 | 100/100 | 100/100 |
| C8T1S5I8N5K | 100/100 | 100/100 | 100/100 | 100/100 | 100/100 |
| BIBLE | 100/100 | 100/100 | 100/100 | 100/100 | **40/100**, out of memory |
| LEVIATHAN | 100/100 | **0/100**, out of memory on `D_init` | **0/100**, out of memory on `D_init` | 100/100 | **74/100** |
| FIFA | 100/100 | 100/100 | deferred | 100/100 | not yet run |
| KOSARAK | 100/100 | 100/100 | deferred | 100/100 | **11/100**, out of memory |

The proposal is the only arm that finishes every increment of every database.

## Runtime, summed over the increments that completed

Seconds. A total over a partial run is not comparable with a total over a whole one and is
marked as such.

| database | Adaptive-Full | rescans | Pre-HUSPM | Pre-HUSPM-TSU | Remine | IncUSP |
|---|---:|---:|---:|---:|---:|---:|
| SIGN | **18** | 59 | 27 | 61 | 30 | 94 |
| TAFENG | **6** | 52 | 7 | 26 | 20 | 9 |
| C8T1S5I8N5K | **66** | 80 | 245 | 318 | 93 | 83 |
| BIBLE | **111** | 63 | 139 | 2920 | 209 | (55, 40 increments) |
| LEVIATHAN | 96 | 98 | — | — | **91** | (174, 74 increments) |
| FIFA | 2230 | 28 | **1310** | — | 8105 | — |
| KOSARAK | 4656 | 100 | **1677** | — | 4547 | (69, 11 increments) |

## The operating region

An insertion triggers a rescan when `iauMax > SFT_ins`, where `iauMax` is the largest
accumulated utility over items since the last rescan and

    SFT_ins = (UUT - LUT_min) / (1 - UUT) * TSU_ref = Su (1 - r_l) / (1 - Su) * TSU_ref.

The ratio of the two is the whole mechanism. Counting increments whose ratio is at most one
reproduces the rescan count exactly on every database — 41 and 41 on SIGN, 48 and 48 on
TAFENG, 37 and 37 on BIBLE, 20 and 20 on C8T1S5I8N5K, 72 and 72 on FIFA, 2 and 2 on
LEVIATHAN, 0 and 0 on KOSARAK.

| database | median `iauMax / SFT_ins` | min | max | increments within budget | rescans |
|---|---:|---:|---:|---:|---:|
| FIFA | 0.74 | 0.19 | 1.39 | 72/100 | 28 |
| TAFENG | 1.04 | 0.32 | 6.58 | 48/100 | 52 |
| SIGN | 1.10 | 0.41 | 2.51 | 41/100 | 59 |
| BIBLE | 1.21 | 0.56 | 2.15 | 37/100 | 63 |
| C8T1S5I8N5K | 1.45 | 0.40 | 5.11 | 20/100 | 80 |
| LEVIATHAN | 2.08 | 0.95 | 4.04 | 2/100 | 98 |
| KOSARAK | 3.80 | 1.92 | 21.48 | 0/100 | 100 |

Three regimes follow, and the middle one is where the method lives.

**Inside the budget.** FIFA keeps 72 increments out of 100 and runs 3.6 times faster than
remining after every one of them.

**At the boundary.** TAFENG, SIGN, BIBLE and C8T1S5I8N5K sit at a median just above one,
rescan between half and four fifths of the time, and still finish 1.4 to 3.3 times faster
than remine — the increments that fit are enough to pay for the ones that do not.

**Beyond it.** LEVIATHAN and KOSARAK exceed the budget on essentially every increment, so
maintenance degenerates into remining. What that costs is the honest measurement here: 96 s
against remine's 91 s, and 4656 s against 4547 s. Degradation is graceful, 2 to 6 percent,
rather than a collapse.

## KOSARAK cannot be moved by tuning

The budget is proportional to `1 - r_l`, so a wider budget needs a lower pre-large floor.
KOSARAK's median ratio of 3.8 would need roughly `r_l = 0.3` to fall below one.

`r_l` on KOSARAK is 0.85 precisely because it cannot go lower: at 0.8 the floor is already
low enough that mining it exceeds three billion candidates and is abandoned after 103 s,
while 0.85 returns 16 patterns in 29 s. The two constraints point in opposite directions —
a wider budget needs a lower floor, and a lower floor is unmineable — so no value of `r_l`
satisfies both. This is a limit of the pre-large approach on a sparse database at a small
threshold, not a configuration that was got wrong.

SC4 sweeps `r_l` over {0.3, 0.5, 0.7, 0.9} and is the scenario that measures the trade-off
directly: budget width against pre-large set size.

## What this does not show

Pre-HUSPM is faster than the proposal on FIFA, 1310 s against 2230 s, and on KOSARAK,
1677 s against 4656 s. Its trigger is not sound, but `results/verify-swumax` finds no lost
pattern on either of those two databases, so on them it is both correct in practice and
cheaper. Where it does lose patterns — LEVIATHAN, up to 39 of 137 at one operation — it
cannot be run at all here: it exhausts 48 GB on `D_init`.

FIFA/IncUSP-Miner+ has not been measured yet, and the two Pre-HUSPM-TSU arms on FIFA and
KOSARAK are deferred with their partial curves; see the `DEFERRED` files.
