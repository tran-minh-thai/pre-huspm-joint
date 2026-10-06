# SC6, insertion, deletion and modification mixed

Twenty operations on every database, exactly 14 insertions, 3 deletions and 3 modifications,
in an order shuffled by the seed; each operation is one hundredth of the incremental stream,
the size of an SC2 increment: 5 sequences on SIGN, 30 on LEVIATHAN, 199 on C8T1S5I8N5K. The plan
is built once and both arms replay it. SUBSET_3, `D_init` per `common.Datasets`, one seed, one
repeat, `PC-THAI`, JDK 25, `-Xmx48g`.

The scenario first ran with independent draws at 70:15:15, which gave each database a
different mix and made the totals incomparable; that run is kept in `results/sc6-random-draw`.

## Every operation agrees

All 60 operations complete, and at every one the two arms report the same HUSP count.

## Per database

| database | proposal | remine | ratio |
|---|---:|---:|---:|
| C8T1S5I8N5K | 8458 ms | 12548 ms | **0.67** |
| SIGN | 3769 ms | 5071 ms | **0.74** |
| LEVIATHAN | 9706 ms | 11352 ms | **0.86** |

The three now face the same operations, so the ratios are comparable.

## By kind of operation

| database | kind | rescans | proposal | remine | remine / proposal |
|---|---|---:|---:|---:|---:|
| SIGN | insertion | 12 / 14 | 3012 ms | 3572 ms | 1.2 |
| SIGN | deletion | 0 / 3 | 10 ms | 739 ms | 73.9 |
| SIGN | modification | 3 / 3 | 747 ms | 760 ms | 1.0 |
| LEVIATHAN | insertion | 14 / 14 | 7906 ms | 7901 ms | 1.0 |
| LEVIATHAN | deletion | 0 / 3 | 8 ms | 1686 ms | 210.8 |
| LEVIATHAN | modification | 3 / 3 | 1792 ms | 1765 ms | 1.0 |
| C8T1S5I8N5K | insertion | 12 / 14 | 7000 ms | 8812 ms | 1.3 |
| C8T1S5I8N5K | deletion | 0 / 3 | 118 ms | 1823 ms | 15.4 |
| C8T1S5I8N5K | modification | 2 / 3 | 1340 ms | 1913 ms | 1.4 |

No deletion rescans, on any database. The deletion budget is `(UUT - LUT_min) / UUT * TSU_ref`,
a fifth of total utility at `r_l = 0.8` and about fourteen times the insertion budget, so a
deletion of one hundredth of the stream sits well inside it and costs milliseconds.

Insertions and modifications mostly rescan. On LEVIATHAN every one does, and the proposal is
then level with remining, as in SC2. On C8T1S5I8N5K a rescan is cheaper than
a remine, which is where its lead on insertions comes from.

## Why insertions rescan more here than in SC2

At the same size, SC2's insertions on SIGN rescan 59 times in 100 and SC6's 12 in 14. The
difference is where in the stream they fall. The insertion budget is proportional to the total
utility at the last rescan, so it widens as the database grows while an increment stays the same
size. SC2 shows the rescan rate falling along the stream:

| database | increments 1-14 | 15-50 | 51-100 |
|---|---:|---:|---:|
| SIGN | 13 / 14 | 22 / 36 | 24 / 50 |
| LEVIATHAN | 14 / 14 | 36 / 36 | 48 / 50 |
| C8T1S5I8N5K | 12 / 14 | 28 / 36 | 40 / 50 |

SC6 has fourteen insertions, so it lies entirely in the first band, and there it matches SC2
almost exactly: 12, 14 and 12 rescans against 13, 14 and 12. The proposal's advantage grows the
longer the stream runs, and SC6 measures its start.
