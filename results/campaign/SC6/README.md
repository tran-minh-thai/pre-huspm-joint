# SC6, insertion, deletion and modification mixed

Ten operations drawn at 70:15:15 insert:delete:modify, each touching one twentieth of the
incremental stream (`opSize = rest / 20`): 25 sequences on SIGN, 150 on LEVIATHAN, 999 on
C8T1S5I8N5K. The plan is built once and both arms replay it, the proposal maintaining its
pattern set and remine-from-scratch mining again after every operation. SUBSET_3, `D_init` per
`common.Datasets`, one seed, one repeat, `PC-THAI`, JDK 25, `-Xmx48g`.

## Every operation agrees

For all 30 operations, the two arms report the same HUSP count. The pattern sets and utilities
of mixed insertion and deletion against remine are checked separately in `results/verify`.

## By kind of operation

| kind | occurrences | rescans | remine's time / the proposal's |
|---|---:|---:|---:|
| insertion | 24 | 24 | 1.0 to 1.2 |
| deletion | 4 | 0 | 3.8 to 34.7 |
| modification | 2 | 2 | 1.0 |

An insertion of a twentieth of the stream always exceeds the insertion budget, so every one
rescans and the proposal costs what remining costs. SC5 found the same at `K = 25`, where 96 to
99 percent of increments rescan; a twentieth is coarser still.

A deletion never rescans. The deletion budget is `(UUT - LUT_min) / UUT * TSU_ref`, which at
`r_l = 0.8` is a fifth of total utility, about fourteen times the insertion budget, and a
twentieth of the stream fits inside it. On SIGN a deletion takes 3 to 14 ms against remine's
roughly 240 ms.

## Per database

| database | realised plan | proposal | remine | ratio |
|---|---|---:|---:|---:|
| SIGN | 7 insert, 3 delete | 1834 ms | 2611 ms | 0.70 |
| C8T1S5I8N5K | 9 insert, 1 delete | 6347 ms | 8101 ms | 0.78 |
| LEVIATHAN | 8 insert, 2 modify | 6916 ms | 6871 ms | 1.01 |

These totals should not be read as a property of the databases. The ratio follows the number of
deletions the draw happened to produce, and the draw differs by database: 70:15:15 is the
probability of each operation, and ten draws from one seed land far from it. No database got
both a deletion and a modification. What the table measures is the realised plan.

## What would make SC6 comparable across databases

An exact schedule instead of independent draws - for instance 20 operations as 14 insertions,
3 deletions and 3 modifications, in an order shuffled by the seed - gives every database the
same mix. Separately, `opSize` could be tied to the same granularity as SC2's hundred
increments, one hundredth of the stream, so that insertions sit where SC2 and SC5 put them
rather than in the regime where every one rescans. Both are design choices, not corrections,
and the scenario costs about a minute of machine time to run again.
