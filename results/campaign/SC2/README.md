# SC2, one hundred increments

Seven databases against five arms; `D_init` per `common.Datasets`, the remainder cut into 100
equal increments in file order. BMS1 is absent: none of its four thresholds can be mined once the
database grows past `D_init`, so it appears in SC1 alone. Measured on `PC-THAI` (see `results/machine/PC-THAI.json` and `environment.lock.json`), `-Xmx48g`, one JVM per arm. Written by `analysis/scenario_readmes.py` from the arms' CSVs; rerun it rather than editing numbers here.

Pre-HUSPM-SWUmax and Pre-HUSPM-TSU are the two rescan tests of Yan et al.: their Theorem 2, on the
largest sequence-weighted utility of an item in the inserted sequences, and their Theorem 1, on the
total utility of the inserted sequences. Both accumulate since the last rescan and both are sound.

## What completed

| database | Pre-HUSPM-Joint | Pre-HUSPM-SWUmax | Pre-HUSPM-TSU | Remine | IncUSP-Miner+ |
|---|---|---|---|---|---|
| SIGN | 100/100 | 100/100 | 100/100 | 100/100 | 100/100 |
| LEVIATHAN | 100/100 | 100/100 | 100/100 | 100/100 | 100/100 |
| FIFA | 100/100 | 100/100 | 100/100 | 100/100 | 25/100, stopped by the memory guard |
| BIBLE | 100/100 | 100/100 | 100/100 | 100/100 | 100/100 |
| KOSARAK | 100/100 | 100/100 | 100/100 | 100/100 | 100/100 |
| C8T1S5I8N5K | 100/100 | 100/100 | 100/100 | 100/100 | 100/100 |
| TAFENG | 100/100 | 100/100 | 100/100 | 100/100 | 100/100 |

## Runtime, summed over the increments that completed

Seconds over the increments alone; the initial mine of `D_init` is excluded. A total over a
partial run is in parentheses: it is not comparable with a total over the whole stream. The
fastest arm that completed the whole stream is in bold. One run per arm; the spread of the
proposal and Remine over three runs is in the next table.

| database | Pre-HUSPM-Joint | rescans | Pre-HUSPM-SWUmax | Pre-HUSPM-TSU | Remine | IncUSP-Miner+ |
|---|---:|---:|---:|---:|---:|---:|
| SIGN | **9.7** | 59 | 51.0 | 50.2 | 17.8 | 23.8 |
| LEVIATHAN | **35.3** | 98 | 112 | 114 | 37.2 | 71.6 |
| FIFA | **174** | 27 | 4,995 | 6,956 | 667 | (598, 25 increments) |
| BIBLE | **53.8** | 63 | 92.1 | 109 | 93.7 | 57.5 |
| KOSARAK | **627** | 100 | 695 | 711 | 685 | 1,594 |
| C8T1S5I8N5K | **9.1** | 77 | 51.2 | 63.2 | 15.5 | 18.5 |
| TAFENG | **5.9** | 52 | 7.3 | 13.0 | 18.9 | 13.8 |

### Three runs

On SIGN, TAFENG, BIBLE and C8T1S5I8N5K the proposal and Remine were each measured three times, each
run in a fresh JVM (`results/campaign-replicates/s0r1`, `s0r2`). Seconds summed over the
increments, mean ± sample standard deviation.

| database | arm | runs | mean ± sd | min | max |
|---|---|---:|---:|---:|---:|
| SIGN | Pre-HUSPM-Joint | 3 | 9.71 ± 0.12 | 9.61 | 9.85 |
| SIGN | Remine | 3 | 17.78 ± 0.58 | 17.17 | 18.31 |
| LEVIATHAN | Pre-HUSPM-Joint | 3 | 35.33 ± 0.33 | 35.12 | 35.71 |
| LEVIATHAN | Remine | 3 | 37.19 ± 0.17 | 37.06 | 37.39 |
| BIBLE | Pre-HUSPM-Joint | 3 | 53.77 ± 0.20 | 53.60 | 53.99 |
| BIBLE | Remine | 3 | 93.66 ± 0.54 | 93.14 | 94.22 |
| KOSARAK | Pre-HUSPM-Joint | 3 | 626.85 ± 6.17 | 620.40 | 632.71 |
| KOSARAK | Remine | 3 | 684.92 ± 0.46 | 684.61 | 685.45 |
| C8T1S5I8N5K | Pre-HUSPM-Joint | 3 | 9.06 ± 0.12 | 8.96 | 9.20 |
| C8T1S5I8N5K | Remine | 3 | 15.51 ± 0.16 | 15.34 | 15.67 |
| TAFENG | Pre-HUSPM-Joint | 3 | 5.91 ± 0.01 | 5.90 | 5.92 |
| TAFENG | Remine | 3 | 18.90 ± 0.07 | 18.86 | 18.98 |

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
| KOSARAK | 2.83 | 1.44 | 16.04 | 0/100 | 100 | ok |

The share alpha = max_i A_i / U+ of the inserted utility that one item reaches, at each decision
(U+ inserted since the last rescan): an insertion-only stream is never rescanned while
alpha <= UUT, and otherwise rescanned exactly when (alpha - UUT) * U+ > (UUT - LUT_min) * TSU_ref.
"stream" is alpha over the whole insertion stream; "check" compares the recorded alpha and
decisions with `analysis/insertion_alpha.py`, which computes both from the data files.

| database | UUT | median alpha | min | max | increments with alpha <= UUT | stream | check |
|---|---:|---:|---:|---:|---:|---:|---|
| FIFA | 0.059637 | 0.6233 | 0.5391 | 0.7278 | 0/100 | 0.6213 | 0 of 100 differ |
| TAFENG | 0.003557 | 0.1365 | 0.0748 | 0.4045 | 0/100 | 0.1472 | 0 of 100 differ |
| SIGN | 0.065911 | 1.0000 | 1.0000 | 1.0000 | 0/100 | 0.9175 | 0 of 100 differ |
| BIBLE | 0.022895 | 0.8458 | 0.7111 | 0.9547 | 0/100 | 0.8158 | 0 of 100 differ |
| C8T1S5I8N5K | 0.001132 | 0.0529 | 0.0212 | 0.1329 | 0/100 | 0.0588 | 0 of 100 differ |
| LEVIATHAN | 0.015530 | 0.9024 | 0.7267 | 1.0000 | 0/100 | 0.8861 | 0 of 100 differ |
| KOSARAK | 0.025000 | 0.8690 | 0.8363 | 0.8873 | 0/100 | 0.8694 | 0 of 100 differ |

## The prediction registered before the campaign

From the data alone, the joint rule absorbs (1 - UUT) / (alpha - UUT) times the inserted utility
that a test on the total inserted utility (Pre-HUSPM-TSU) absorbs before a rescan, with alpha
over the insertion stream. Registered before the campaign ran: the gap in rescans between
Pre-HUSPM-TSU and the proposal is largest on C8T1S5I8N5K and TAFENG and smallest on
SIGN, LEVIATHAN, KOSARAK. Gap = Pre-HUSPM-TSU's rescans minus the proposal's, where both
completed the 100 increments.

| database | alpha, stream | budget factor | rescans, proposal | Pre-HUSPM-TSU | Pre-HUSPM-SWUmax | gap |
|---|---:|---:|---:|---:|---:|---:|
| C8T1S5I8N5K | 0.0588 | 17.31 | 77 | 100 | 80 | 23 |
| TAFENG | 0.1472 | 6.94 | 52 | 100 | 56 | 48 |
| FIFA | 0.6213 | 1.67 | 27 | 41 | 29 | 14 |
| BIBLE | 0.8158 | 1.23 | 63 | 77 | 63 | 14 |
| KOSARAK | 0.8694 | 1.15 | 100 | 100 | 100 | 0 |
| LEVIATHAN | 0.8861 | 1.13 | 98 | 100 | 98 | 2 |
| SIGN | 0.9175 | 1.10 | 59 | 59 | 59 | 0 |

Verdict: **holds** (largest-gap part holds, smallest-gap part holds).

The joint rule never rescans more often than the SWUmax test on an insertion-only stream with the
same UUT and floor ratio (a proved property, checked here): the proposal rescans no more than
Pre-HUSPM-SWUmax on 7 of 7 databases where both completed.

**Inside the budget** (median ratio at most one): FIFA, 3.84 times faster than remining.

**At the boundary**: SIGN (1.83x), BIBLE (1.74x), C8T1S5I8N5K (1.71x), TAFENG (3.20x). They rescan on
52 to 77
of 100 increments and still finish faster than remining.

**Beyond it**: LEVIATHAN, 2 of 100 within budget, KOSARAK, 0 of 100 within budget.
Maintenance degenerates into remining: LEVIATHAN 35.3 s against remining's 37.2 s (-5.0 %); KOSARAK 627 s against remining's 685 s (-8.5 %),
one run each. Two runs of one SC1 configuration on the same JDK differ by a factor between
0.69 and 1.25 (66 configurations of at least one second), so differences
of a few percent between single runs are not resolved here.

## KOSARAK cannot be moved by tuning

The bound is proportional to `1 - r_l` and the test quantity does not depend on `r_l`, so the
median ratio of 2.83 at `r_l = 0.8` would come down to one only at
`r_l = 0.43`, a lower pre-large floor. A lower floor
costs more to mine, and on KOSARAK it is already the binding limit: `r_l` is 0.8 there, not 0.8,
because at 0.8 the lowest threshold of the sweep exceeds three billion candidates
(`docs/campaign-parameters.md`). SC4 measures the trade-off between the floor and the pre-large
set directly.

## What this does not show

Neither Pre-HUSPM variant is faster than the proposal on any database where both complete.
