# Correctness evidence: insertion, deletion and modification

`verification.csv` compares the pattern set and the per-pattern utility of the incremental
algorithm against remine-from-scratch after every operation, as `results/verify` does, but on
a stream that mixes the three kinds of operation.

    java -Xmx48g -Xss64m -cp build verify.CorrectnessVerifier <DATABASE> --rule iaumax --mixed `
         --out results/verify-mixed

Acceptance is `missing = extra = 0` and `max_utility_gap = 0` on every row, and that is what the
file holds: 40 rows, all `clean = true`, on all eight databases.

## The operations

Five per database, in the order insert, insert, delete, modify, insert, on the verifier's
operating point (`results/verify`: `D_init` the first half of the database, `r_l` = 0.8,
the default threshold of `common.Datasets`).

| operation | what it does | size |
|---|---|---|
| insert | appends the next fifth of the remaining stream | a fifth of the stream |
| delete | removes sequences drawn without replacement from the current database | a twentieth of it |
| modify | replaces sequences of the current database by sequences of the stream, as SC6 does | a five-hundredth of it |

The last insertion follows a deletion and a modification, so it is checked against the state
those two left behind.

## Which path each operation took

`rescan_count` rises by one at every insertion and stays unchanged at every deletion and every
modification, on all eight databases. So the two insertions before the deletion and the one
after it were handled by a rescan, while all 8 deletions and all 8 modifications were handled by
the direct update - each tracked pattern given exactly the utility the operation removes or
adds, with no mining - and still matched remining from scratch exactly.

That is the path this file exists to check. A rescan is a remine of the whole database, so a
rescan that agrees with remine-from-scratch says little; a direct update that agrees says the
deletion and modification budgets admit no pattern they should not. The modification is kept
small for that reason: at a twentieth of the database every modification exceeded the
insertion budget and rescanned.

## BMS1

`results/verify` stops BMS1 after its third insertion, because the database grows towards its
whole and no threshold mines the whole of BMS1. Here the deletion and the modification keep it
smaller, and all five operations complete in 11 s.
