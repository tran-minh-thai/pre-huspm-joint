# SC7, what each component contributes

Four components are switched off one at a time. C3, the IUB bound, acts inside the miner, so
it is ablated on one mine of the whole database, on all seven databases, with Pre-HUSPM beside
it for reference. C2, the adaptive pre-large band, C4, the block index, and C5, the adaptive
rescan, act only while the database grows, so they are ablated on `D_init` and 100 increments,
one row per increment, on SIGN and C8T1S5I8N5K. C1, the value bound applied before a pattern is materialised, is inherent to the
miner and has no switch. `PC-THAI`, JDK 25, `-Xmx48g`, one JVM per arm, one repeat.

All 29 arms completed. Switching any component off changes no answer: the HUSP count agrees
on all seven static mines and on every one of the 101 rows of each incremental arm, and with C4
off every rescan decision is the same as well.

## C3, the IUB bound

| database | HUSPs | full | IUB off | Pre-HUSPM | explored, full | explored, IUB off | IUB cuts |
|---|---:|---:|---:|---:|---:|---:|---:|
| SIGN | 141 | 682 ms | 524 ms | 1570 ms | 38,372 | 38,372 | 152 |
| LEVIATHAN | 135 | 1817 ms | 1868 ms | 15447 ms | 39,313 | 39,313 | 8,830 |
| TAFENG | 9 | 479 ms | 540 ms | 383 ms | 23,847 | 23,847 | 22,911 |
| BIBLE | 129 | 2325 ms | 2258 ms | 31761 ms | 18,939 | 18,939 | 13,805 |
| C8T1S5I8N5K | 3438 | 1960 ms | 2159 ms | 3970 ms | 383,747 | 383,747 | 66,925 |
| KOSARAK | 9 | 81.3 s | 80.8 s | 391.2 s | 84,913 | 84,913 | 40,948 |
| FIFA | 17 | 101.4 s | 99.0 s | 630.4 s | 473,004 | 473,004 | 2,889 |

The IUB bound does not shrink the search on any database: the explored count is identical with
it and without it, on all seven, although the bound reports cutting between 152 and 66,925
branches. Every branch it cuts is cut by another bound as well. Whatever it contributes, it
contributes by cutting earlier, not by cutting more.

Nor does it change the runtime measurably. Without it the seven runs take between 0.77 and
1.13 of the full proposal's time, with a geometric mean of 0.99. Most of these runs last a few
seconds and were measured once, so that spread is noise; the two long enough to read, KOSARAK
and FIFA, differ by under 2.5 percent.

Pre-HUSPM is 2.0 to 13.7 times slower than the full proposal on six of the seven, and faster on
TAFENG, 383 ms against 479 ms, as it is in SC1.

## C5, the adaptive rescan

| database | C5 on | C5 off (rescan every increment) | saving |
|---|---|---|---:|
| SIGN | 17.4 s, 59 rescans | 29.8 s, 100 rescans | 1.71 |
| C8T1S5I8N5K | 60.4 s, 80 rescans | 74.4 s, 100 rescans | 1.23 |

This is the component that carries the incremental gain. Without it every increment is a
remine; with it the increments that fit inside the insertion budget are updated instead.

## C2, the adaptive pre-large band

With C2 off the band is fixed at the hard floor, `LUT_min = r_l * UUT`, as Pre-HUSPM fixes it.

| database | C2 on | C2 off | rescans, on / off | mean pre-large set, on / off |
|---|---:|---:|---:|---:|
| SIGN | 17.4 s | 17.6 s | 59 / 59 | 764 / 765 |
| C8T1S5I8N5K | 60.4 s | 60.2 s | 80 / 80 | 9,985 / 9,985 |

Switching C2 off changes nothing: the same increments rescan, the pre-large set is the same
size, the answer is the same at every increment, and the runtimes differ by less than one
percent. The reason is in the rows. `lut_dyn` with C2 on equals the hard floor on 199 of the
200 increments, and on the remaining one, SIGN's 39th, lies 0.4 percent above it. The adaptive
term `kappa * sigma^2 * rho` is computed in squared utility units - `sigma2` runs from 2.6e4 to
1.6e6 on SIGN - so at `kappa = 0.5` it exceeds the cap `(UUT - LUT_min) * TSU'` at once, and the
cap puts the band back on the floor. At this setting C2 is the fixed band of Pre-HUSPM.

## C4, the block index

The index is read only on an increment that does not rescan, so its effect is measured there.

| database | increments without a rescan | with the index | without it | block reads with | block reads without |
|---|---:|---:|---:|---:|---:|
| SIGN | 41 | 52 ms | 48 ms | 148,060 | 183,715 |
| C8T1S5I8N5K | 20 | 229 ms | 654 ms | 5,890,200 | 56,054,600 |

Where it acts, it works on C8T1S5I8N5K: the updates run 2.9 times faster and read 9.5 times fewer
blocks. On SIGN it reads a fifth fewer blocks and the time is the same, 52 ms against 48 ms, too
short to separate. Those updates are a small share of the whole, 229 ms of 60 s on
C8T1S5I8N5K, because nearly all of the time goes to rescans, and over the 100 increments the
two arms differ by less than the run-to-run spread of the rescans: 17.4 s against 17.5 s on
SIGN, 60.4 s against 57.8 s on C8T1S5I8N5K. The index has no measurable effect on the total.

The per-pattern evaluation still walks every sequence of the increment and uses the index only
to skip the utility computation. A map from block to sequences would cut the walk as well, but
it would speed up the same small share of the runtime.

## What the ablation does not isolate

The static speedups of SC1 and SC3, 4.0 to 17 times over the baselines, do not come from C3,
which leaves the search unchanged. They come from what the ablation cannot switch off: C1, the
value bound applied before materialisation, which is part of the miner itself. `pruned_value`
and `explored_nodes` record its work in every row, but there is no arm without it.
