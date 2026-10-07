# Correctness of the joint rule on the direct update

Kind: counters measured on the development machine (pattern sets, utilities, operation
counts). No timing in this tree. Each subdirectory holds `run_meta.json` - command, commit,
source digest, JVM, operating system - and `console.txt`, the full output.

The rule under test is the default, `joint`. With `I` and `D` the utility inserted and deleted
since the last rescan, `A_i` the inserted utility of the sequences containing item `i`, and
`L_r = LUT_min * TSU_ref`:

    rescan  iff  max_i A_i - UUT*I + UUT*D > (UUT - LUT_min) * TSU_ref
    drop X  iff  u(X) < L_r + min_{i in X} A_i

An untracked `X` gains at most `min_{i in X} A_i` since the last rescan and deletions only lower
it, so `u(X) < L_r + max_i A_i`; while the rule holds, `L_r + max_i A_i <= UUT * TSU`.

Every check compares the pattern set and every pattern's utility with remining from scratch
after every operation. Remining is the proposal's own static miner, so these checks verify the
incremental maintenance, not the miner; the miner is checked against USpan, HUSP-ULL and
HUSP-SP by `verify.BaselineAgreement`.

## 1. Real databases, operations sized to take the direct update

`verify.DirectPathVerifier`. The first half of each database is `D_init`, `r_l = 0.8` on all
eight, the threshold of `common.Datasets`. Each operation is sized to spend about a sixth of the
budget `(UUT - LUT_min) * TSU_ref`, and operations cycle delete, insert, modify, insert, so an
insertion follows every deletion and every modification and a rescan comes when the budget runs
out. Sixteen operations per database, seed 42.

### 1a. Random operations - `random/`

    java -Xmx12g -Xss64m -cp build verify.DirectPathVerifier ALL --ops 16 --out results/verify-joint/random

<!-- generated: python3 analysis/summarize_verification.py results/verify-joint/random/verification.csv -->
| database | operations | insert direct | insert rescan | delete direct | delete rescan | modify direct | modify rescan | direct insert after direct delete | direct insert after direct modify | mismatches |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| SIGN | 16 | 7 | 1 | 3 | 1 | 3 | 1 | 2 | 3 | 0 |
| LEVIATHAN | 16 | 7 | 1 | 3 | 1 | 3 | 1 | 2 | 3 | 0 |
| BIBLE | 16 | 7 | 1 | 4 | 0 | 3 | 1 | 3 | 3 | 0 |
| BMS1 | 16 | 8 | 0 | 4 | 0 | 4 | 0 | 4 | 4 | 0 |
| FIFA | 16 | 7 | 1 | 4 | 0 | 4 | 0 | 4 | 3 | 0 |
| KOSARAK | 16 | 7 | 1 | 4 | 0 | 3 | 1 | 3 | 3 | 0 |
| C8T1S5I8N5K | 16 | 7 | 1 | 4 | 0 | 4 | 0 | 3 | 4 | 0 |
| TAFENG | 16 | 8 | 0 | 4 | 0 | 4 | 0 | 4 | 4 | 0 |
| all | **128** | **58** | **6** | **30** | **2** | **28** | **4** | **25** | **27** | **0** |

Random operations cannot fail a faulty rule on these databases, so this table is coverage, not
evidence of soundness: a random deletion lowers every pattern's utility roughly in proportion to
TSU, and no pattern crosses the threshold. Measured: the superseded `iaumax` rule and both faults
of section 3 pass the random stream on SIGN and C8T1S5I8N5K.

### 1b. Adversarial operations - `adversarial/`

    java -Xmx12g -Xss64m -cp build verify.DirectPathVerifier ALL --adversarial --ops 16 --out results/verify-joint/adversarial

After the initial mine and after every rescan a target `X` is chosen: the untracked pattern of
highest utility below the HUSP threshold, from a reference mine at `0.6 * UUT` (candidate budget
5e8, above which the miner raises its floor). Deletions take only sequences missing one of
`X`'s items, which cannot contain `X`; insertions and the new side of a modification take only
sequences holding all of them, from the stream or as copies, under fresh ids, of database
sequences. `X` keeps its utility while TSU falls and gains while it rises - the shape of the
counterexamples. Where no target exists the operations fall back to the random stream; the
table counts the operations that had one.

<!-- generated: python3 analysis/summarize_verification.py results/verify-joint/adversarial/verification.csv -->
| database | operations | with a target | insert direct | insert rescan | delete direct | delete rescan | modify direct | modify rescan | direct insert after direct delete | direct insert after direct modify | mismatches |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| SIGN | 16 | 16 | 7 | 1 | 2 | 2 | 4 | 0 | 2 | 3 | 0 |
| LEVIATHAN | 16 | 16 | 6 | 2 | 4 | 0 | 3 | 1 | 3 | 2 | 0 |
| BIBLE | 16 | 16 | 6 | 2 | 4 | 0 | 4 | 0 | 3 | 3 | 0 |
| BMS1 | 16 | 0 | 8 | 0 | 4 | 0 | 4 | 0 | 4 | 4 | 0 |
| FIFA | 16 | 0 | 7 | 1 | 4 | 0 | 4 | 0 | 4 | 3 | 0 |
| KOSARAK | 16 | 0 | 7 | 1 | 4 | 0 | 3 | 1 | 3 | 3 | 0 |
| C8T1S5I8N5K | 16 | 0 | 7 | 1 | 4 | 0 | 4 | 0 | 3 | 4 | 0 |
| TAFENG | 16 | 16 | 6 | 2 | 4 | 0 | 4 | 0 | 3 | 3 | 0 |
| all | **128** | **64** | **54** | **10** | **30** | **2** | **30** | **2** | **25** | **25** | **0** |

A target was found on SIGN, LEVIATHAN, BIBLE and TAFENG, after the initial mine and after every
rescan. On BMS1, FIFA, KOSARAK and C8T1S5I8N5K the reference mine exceeded its candidate budget
at every floor it tried, so their operations are the random stream; the console says so at each
attempt.

## 2. Small random databases - `fuzz/`

`verify.MixedBudgetFuzz`: 15 to 29 random sequences over 7 items, then 10 random operations
(insert 1-2 sequences, delete 1-2, modify one), `UUT` drawn from {0.08, 0.12, 0.2, 0.3}. Same
streams for both rules at a seed. Direct updates are listed insert / delete / modify.

| seed | operations | rule | direct updates | mismatching operations |
|---:|---:|---|---|---:|
| 7 | 20000 | joint | 1804 / 5573 / 1694 | 0 |
| 7 | 20000 | iaumax | 1938 / 5727 / 2558 | 21 |
| 11 | 50000 | joint | 4643 / 14052 / 4251 | 0 |
| 11 | 50000 | iaumax | 4952 / 14434 / 6281 | 74 |
| 23 | 50000 | joint | 4528 / 14002 / 4389 | 0 |
| 23 | 50000 | iaumax | 4832 / 14435 / 6377 | 65 |
| 101 | 50000 | joint | 4506 / 13960 / 4292 | 0 |
| 101 | 50000 | iaumax | 4847 / 14348 / 6327 | 66 |

The same run holds `verify.MixedBudgetProbe`, the three hand-built counterexamples
(`UUT = 0.25`, `LUT_min = 0.20`, `TSU_ref = 100`): A, a deletion of 18 then `<1[6]>` inserted
with `<1>` at 19; B, the same as one modification; D, insertions alone, `<1>` at 22.7 pre-large,
`<50[3]>` then `<1[6]>`. `iaumax` misses `<1>` in all three; `joint` returns it in all three,
in D without a rescan.

## 3. The checks can fail the joint rule - `fault-injection/`

`analysis/fault_injection.py` injects a fault into a compiled copy of the source - checking the
substitution matches exactly once and lands - and runs the fuzzer (seed 7, 2000 trials) and the
hand-built cases on it.

| fault | fuzzer: mismatching operations | hand-built cases failing |
|---|---:|---|
| trigger without the `+ UUT*D` term | 519 | A, B |
| drop floor with `max_i A_i` instead of `min_{i in X} A_i` | 6 | D |

`verify.WP1AcceptanceTest` (23 checks) fails on each fault as well: case D on the second, A and
B on the first.

`fault-injection-real/` runs the same two faults through the adversarial stream of section 1b
on the five databases where it can find a target or a fast reference mine (SIGN, LEVIATHAN,
BIBLE, C8T1S5I8N5K, TAFENG):

| fault | mismatching operations on the real databases |
|---|---|
| trigger without the `+ UUT*D` term | SIGN 1, BIBLE 1; LEVIATHAN, C8T1S5I8N5K, TAFENG 0 |
| drop floor with `max_i A_i` | 0 on all five |

So the real-database streams can fail a faulty deletion budget but have not failed a faulty
drop floor: for the second fault, the evidence is the small databases (6 mismatches), the
hand-built case D and the acceptance test, not the real databases.
