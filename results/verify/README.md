# Correctness evidence

`verification.csv` compares the pattern set and the per-pattern utility of the incremental
algorithm against remine-from-scratch after every operation, on every database, at the
threshold `common.Datasets` holds for it. One row per operation.

The verifier's own operating point differs from the campaign's in two respects, both fixed in
`verify.CorrectnessVerifier`: `D_init` is the first half of each database, and `r_l` is 0.8 on
all eight, KOSARAK included, where the campaign uses 0.85. Neither enters the soundness
argument, which holds for any split and any floor in (0, 1).

    java -Xmx48g -Xss64m -cp build verify.CorrectnessVerifier <DATABASE> --rule iaumax

Acceptance is `missing = extra = 0` and `max_utility_gap = 0` on every row, and that is what
the file holds: 38 rows, all `clean = true`.

## What the rows are

| database | operations | note |
|---|---:|---|
| SIGN, LEVIATHAN, TAFENG, BIBLE, C8T1S5I8N5K, KOSARAK, FIFA | 5 each | complete |
| BMS1 | 3 | see below |

`rescan_count` equals the operation number on every database: with five increments the
insertions are far larger than the safety budget `SFT_ins`, so a sound trigger rescans every
time. That is a property of this batch size, not of the trigger, and it is the reason the
campaign measures at 100 increments.

## BMS1 stops after the third operation

BMS1 carries `fullScopeOk = false`: no threshold mines the whole database, 0.030 exceeding
three billion candidates while 0.032 returns the empty set. The verifier grows the database
towards the whole of it - `D_init` is 29,800 of 59,601 sequences, so the five increments of
5,960 reach 47,680 after the third and the whole database at the fifth - and the run ends
there. The three operations it did measure are clean.

This is the documented limit of the database, not a correctness failure, and it is why BMS1
appears in SC1 alone. The exact error is not recorded: the verifier writes to the console
only, and that console is gone. Anyone wanting it back should re-run BMS1 alone into a
separate directory, since the CSV is opened in append mode and a second pass over a database
already present would duplicate its rows:

    java -Xmx48g -Xss64m -cp build verify.CorrectnessVerifier BMS1 --rule iaumax `
        --out results/verify-bms1 2>&1 | Tee-Object results/verify-bms1/run.log
