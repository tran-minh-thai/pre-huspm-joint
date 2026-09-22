# What the unsound trigger costs

The same comparison as `results/verify`, run with `--rule swumax`: the trigger that fires on
`swuMax(d)`, the largest single sequence in an increment, rather than on accumulated
perturbation. It is kept selectable so the claim about it can be measured instead of argued.

    java -Xmx48g -Xss64m -cp build verify.CorrectnessVerifier <DATABASE> --rule swumax `
        --out results/verify-swumax

Seven databases, five insertions each. BMS1 is absent: it cannot be mined on the whole
database, so it stops part way under any rule (see `results/verify/README.md`).

## Result

| database | rescans | patterns missing, by operation |
|---|---:|---|
| LEVIATHAN | 0 | 9, 28, 27, 39, 35 |
| C8T1S5I8N5K | 1 | 0, 0, 11, 0, 19 |
| SIGN, BIBLE, KOSARAK, FIFA, TAFENG | 0 to 4 | none |

On LEVIATHAN the trigger never fires at all and the answer loses up to 39 patterns of 137,
28.5 percent, at every operation. Under `iaumax` the same database, the same threshold and
the same increments lose nothing and rescan once per operation.

Five databases lose nothing. That is what an unsound bound means: it permits a loss, it does
not force one. A pattern is lost only when it sits below the pre-large floor and the
accumulated insertions carry it over the threshold before anything rescans, and whether that
happens is a property of the data. Reporting the five as evidence that the bound is safe
would be reading absence of a loss as proof of its impossibility.

## Why `swuMax` does not fire

The safety budget for an insertion is `SFT_ins = ((UUT - LUT_min)/(1 - UUT)) * TSU_ref`,
about 1.4 percent of total utility at these parameters. `swuMax` is the utility of one
sequence. On SIGN, with 230 sequences in `D_init`, one sequence is about 0.4 percent, so an
increment carrying a fifth of the database compares a quantity that cannot reach the bound.
The comparison is not between the perturbation and the budget; it is between one sequence of
the perturbation and the budget.

`iaumax` compares `max_i A_i`, the accumulated utility of inserted sequences containing item
`i`, which is an upper bound on how much any pattern's utility can have grown since the last
rescan, and is what the counterexamples in `verify.WP1AcceptanceTest` are built from.
