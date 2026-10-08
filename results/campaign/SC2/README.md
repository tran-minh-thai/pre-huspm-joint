# SC2, one hundred increments

Seven databases against five arms; `D_init` per `common.Datasets`, the remainder cut into 100
equal increments in file order. BMS1 is absent: no threshold mines it once the database grows
past `D_init`, so it appears in SC1 alone. Measured on `PC-THAI` (see `results/machine/PC-THAI.json` and `environment.lock.json`), `-Xmx48g`, one JVM per arm. Written by `analysis/scenario_readmes.py` from the arms' CSVs; rerun it rather than editing numbers here.

Pre-HUSPM-SWUmax is the arm `Pre-HUSPM`: Yan et al.'s Theorem 2 test, which compares the largest
inserted sequence utility with the safety bound and is not sound. Pre-HUSPM-TSU is their Theorem 1
test, which is. Both accumulate since the last rescan.

## What completed

| database | Pre-HUSPM-Adaptive | Pre-HUSPM-SWUmax | Pre-HUSPM-TSU | Remine | IncUSP-Miner+ |
|---|---|---|---|---|---|
| SIGN | 100/100 | 100/100 | 100/100 | 100/100 | 0/100, not run |
| LEVIATHAN | 100/100 | 0/100, out of memory on `D_init` | 0/100, out of memory on `D_init` | 100/100 | 0/100, not run |
| FIFA | 100/100 | 100/100 | 0/100, deferred, not finished within 8 h | 100/100 | 0/100, not run |
| BIBLE | 100/100 | 100/100 | 100/100 | 100/100 | 0/100, not run |
| KOSARAK | 100/100 | 100/100 | 0/100, deferred, not finished within 8 h | 100/100 | 0/100, not run |
| C8T1S5I8N5K | 100/100 | 100/100 | 100/100 | 100/100 | 0/100, not run |
| TAFENG | 100/100 | 100/100 | 100/100 | 100/100 | 0/100, not run |

## Runtime, summed over the increments that completed

Seconds over the increments alone; the initial mine of `D_init` is excluded. A total over a
partial run is in parentheses: it is not comparable with a total over the whole stream. The
fastest arm that completed the whole stream is in bold. One run per arm; the spread of the
proposal and Remine over three runs is in the next table.

| database | Pre-HUSPM-Adaptive | rescans | Pre-HUSPM-SWUmax | Pre-HUSPM-TSU | Remine | IncUSP-Miner+ |
|---|---:|---:|---:|---:|---:|---:|
| SIGN | **17.5** | 59 | 26.5 | 61.0 | 30.0 | - |
| LEVIATHAN | 95.2 | 98 | - | - | **89.9** | - |
| FIFA | 2,095 | 27 | **1,310** | - | 7,726 | - |
| BIBLE | **109** | 63 | 139 | 2,920 | 181 | - |
| KOSARAK | 4,673 | 100 | **1,677** | - | 4,563 | - |
| C8T1S5I8N5K | **55.7** | 77 | 230 | 309 | 81.0 | - |
| TAFENG | **5.8** | 52 | 7.0 | 26.4 | 18.8 | - |

### Three runs

On SIGN, TAFENG, BIBLE and C8T1S5I8N5K the proposal and Remine were each measured three times, each
run in a fresh JVM (`results/campaign-replicates/s0r1`, `s0r2`). Seconds summed over the
increments, mean ± sample standard deviation.

| database | arm | runs | mean ± sd | min | max |
|---|---|---:|---:|---:|---:|
| SIGN | Pre-HUSPM-Adaptive | 3 | 17.53 ± 0.16 | 17.38 | 17.70 |
| SIGN | Remine | 3 | 30.57 ± 0.51 | 29.99 | 30.96 |
| BIBLE | Pre-HUSPM-Adaptive | 3 | 110.56 ± 1.19 | 109.46 | 111.83 |
| BIBLE | Remine | 3 | 184.86 ± 2.99 | 181.48 | 187.18 |
| C8T1S5I8N5K | Pre-HUSPM-Adaptive | 3 | 56.55 ± 1.37 | 55.70 | 58.14 |
| C8T1S5I8N5K | Remine | 3 | 79.24 ± 1.60 | 77.86 | 81.00 |
| TAFENG | Pre-HUSPM-Adaptive | 3 | 5.85 ± 0.05 | 5.81 | 5.91 |
| TAFENG | Remine | 3 | 19.04 ± 0.40 | 18.78 | 19.50 |

## The operating region

The rescan test is the joint rule: rescan when
`max_i A_i - UUT*I + UUT*D > (UUT - LUT_min) * TSU_ref`. The ratio of the left side to the right
is the whole mechanism; an increment rescans exactly when it exceeds one, and `check` confirms
that the increments above one, the triggered flags and the rescan counter agree.

| database | median ratio | min | max | increments within budget | rescans | check |
|---|---:|---:|---:|---:|---:|---|
| FIFA | 0.76 | 0.18 | 1.51 | 73/100 | 27 | ok |
| TAFENG | 1.01 | 0.31 | 6.52 | 48/100 | 52 | ok |
| SIGN | 1.10 | 0.41 | 2.51 | 41/100 | 59 | ok |
| BIBLE | 1.20 | 0.56 | 2.14 | 37/100 | 63 | ok |
| C8T1S5I8N5K | 1.44 | 0.38 | 5.05 | 23/100 | 77 | ok |
| LEVIATHAN | 2.08 | 0.94 | 4.04 | 2/100 | 98 | ok |
| KOSARAK | 3.77 | 1.92 | 21.39 | 0/100 | 100 | ok |

**Inside the budget** (median ratio at most one): FIFA, 3.69 times faster than remining.

**At the boundary**: SIGN (1.71x), BIBLE (1.66x), C8T1S5I8N5K (1.45x), TAFENG (3.23x). They rescan on
52 to 77
of 100 increments and still finish faster than remining.

**Beyond it**: LEVIATHAN, 2 of 100 within budget, KOSARAK, 0 of 100 within budget.
Maintenance degenerates into remining: LEVIATHAN 95.2 s against remining's 89.9 s (+5.8 %); KOSARAK 4,673 s against remining's 4,563 s (+2.4 %),
one run each. Two runs of one SC1 configuration on the same JDK differ by a factor between
0.83 and 1.17 (109 configurations of at least one second), so differences
of a few percent between single runs are not resolved here.

## KOSARAK cannot be moved by tuning

The bound is proportional to `1 - r_l` and the test quantity does not depend on `r_l`, so the
median ratio of 3.77 at `r_l = 0.85` would come down to one only at
`r_l = 0.43`, a lower pre-large floor. A lower floor
costs more to mine, and on KOSARAK it is already the binding limit: `r_l` is 0.85 there, not 0.8,
because at 0.8 the lowest threshold of the sweep exceeds three billion candidates
(`docs/campaign-parameters.md`). SC4 measures the trade-off between the floor and the pre-large
set directly.

## What this does not show

Pre-HUSPM-SWUmax is faster than the proposal on FIFA (1,310 s against 2,095 s, 1 rescan against 27), KOSARAK (1,677 s against 4,673 s, 8 rescans against 100).
Its test rescans rarely because it under-counts what an increment can add to a pattern; it is not
sound, and its sound counterpart, Pre-HUSPM-TSU, does not finish these two streams within the
eight-hour cap.
