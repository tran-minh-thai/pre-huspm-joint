# Probe: is the direct-update path sound?

Kind: **probe** on the development machine. Every number below is a counter (operations,
mismatching patterns), not a timing, so it does not depend on the machine.

`results/verify` and `results/verify-mixed` check the incremental answer against remining, but
neither exercises the path that the insertion rule exists for:

| file | operations | took the direct update |
|---|---:|---|
| `results/verify` | 38 insertions | 0 insertions (every insertion rescanned) |
| `results/verify-mixed` | 24 insertions, 8 deletions, 8 modifications | 0 insertions, 8 deletions, 8 modifications; no direct insertion follows a direct deletion |

This probe exercises it, on the default rule (`iaumax`) and on `-Dadapt.trigger=joint`.

## Commands

    javac -encoding UTF-8 -d build $(find src -name "*.java")
    java -cp build verify.MixedBudgetProbe iaumax|joint
    java -cp build verify.MixedBudgetFuzz iaumax|joint <trials> <seed>

`run_meta.json` holds the commit, the tool hashes and every command; `output.txt` holds the
full output. Parameters: `r_l = 0.8`, `kappa = 0.5`, `delta0 = 1e-4` (those of
`verify.CorrectnessVerifier`); the fuzzer draws `UUT` from {0.08, 0.12, 0.2, 0.3}.

## Hand-built cases (`MixedBudgetProbe`)

`UUT = 0.25`, `LUT_min = 0.20`, `TSU_ref = 100`, so `SFT_ins = 6.667` and `SFT_del = 20`.

| case | stream | remine | `iaumax` | `joint` |
|---|---|---|---|---|
| A | `<1>` at 19 untracked; delete 18 of filler; insert `<1[6]>` | `<1>` = 25 >= 22 | misses `<1>`, no rescan | rescans, finds `<1>` |
| B | same, as one modification | `<1>` = 25 | misses `<1>` | rescans, finds `<1>` |
| C | control: insert `<1[6]>` with no deletion | no HUSP | no HUSP | no HUSP |
| D | `<1>` at 22.7 pre-large; insert `<50[3]>`, then `<1[6]>` | `<1>` = 28.7 >= 27.25 | drops `<1>` at the first insertion, misses it after the second | keeps `<1>`, finds it with no rescan |

Case D has insertions only. After `<50[3]>`, `deltaMin = (UUT - LUT_min) TSU_ref - (1 - UUT) iauMax`
subtracts the counter of item 50 from the budget left to item 1, whose counter is still 0.

## Random streams (`MixedBudgetFuzz`)

15 to 29 random sequences over 7 items, then 10 operations, each an insertion of 1-2
sequences, a deletion of 1-2, or a modification of one; pattern set and utilities compared with
remining after every operation.

| seed | operations | rule | direct updates (ins / del / mod) | mismatching operations |
|---:|---:|---|---|---:|
| 7 | 20,000 | iaumax | 1,938 / 5,727 / 2,558 | 21 |
| 7 | 20,000 | joint | 1,804 / 5,573 / 1,694 | 0 |
| 11 | 50,000 | iaumax | 4,952 / 14,434 / 6,281 | 74 |
| 11 | 50,000 | joint | 4,643 / 14,052 / 4,251 | 0 |
| 23 | 50,000 | iaumax | 4,832 / 14,435 / 6,377 | 65 |
| 23 | 50,000 | joint | 4,528 / 14,002 / 4,389 | 0 |
| 101 | 50,000 | iaumax | 4,847 / 14,348 / 6,327 | 66 |
| 101 | 50,000 | joint | 4,506 / 13,960 / 4,292 | 0 |

Under `iaumax` the mismatches fall on direct deletions, direct modifications and, on three of
the four seeds, direct insertions.

## The fuzzer can fail `joint`

Two faults were injected into a scratch copy of this commit and run with seed 7:

| fault | mismatching operations |
|---|---:|
| trigger without the `+ UUT*D` term | 519 |
| drop floor with `iauMax` in place of `min_{i in X} A_i` | 6 |

## The rule

With `I`, `D` the utility inserted and deleted since the last rescan, `A_i` the inserted utility
of the sequences containing item `i`, and `L_r = LUT_min * TSU_ref`:

    rescan  iff  max_i A_i - UUT*I + UUT*D > (UUT - LUT_min) * TSU_ref
    drop X  iff  u(X) < L_r + min_{i in X} A_i

An untracked `X` gains at most `min_{i in X} A_i` and deletions only lower it, so
`u(X) < L_r + max_i A_i`; while the rule holds, `L_r + max_i A_i <= UUT*(TSU_ref + I - D) = UUT*TSU`.
With `D = 0` the trigger is looser than `iauMax > SFT_ins`, since `I >= max_i A_i`.
