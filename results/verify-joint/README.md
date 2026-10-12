# Correctness of the joint rule

Counters measured on the development machine; no timing in this tree. Each subdirectory holds
`console.txt`, the full output, and `run_meta.json` (command, commit, source digest, JVM, exit code).

Every check compares the pattern set and the utility of every pattern with remining from
scratch after every operation, so it verifies the incremental maintenance; the miner itself is
checked against USpan, HUSP-ULL and HUSP-SP by `verify.BaselineAgreement`.

* `fuzz`: random insertion, deletion and modification streams on small generated databases
  (`verify.MixedBudgetFuzz`), for the joint rule and, as a control, for the `iaumax` rule, which
  is unsound; and hand-built cases (`verify.MixedBudgetProbe`).
* `acceptance`: the rescan rule on hand-built cases (`verify.RescanRuleAcceptanceTest`).
* `fault-injection`, `fault-injection-real`: each fault is written into a copy of the source and
  the checks are run on it; a fault counts as caught when a check reports a mismatch. A run that
  does not finish catches nothing.
* `random`, `adversarial`, `adversarial-tafeng`: `verify.DirectPathVerifier` on the real
  databases, the first half as `D_init`, 16 operations sized to spend a sixth of the rescan
  budget. In adversarial mode each operation is shaped around a target, the untracked pattern of
  highest utility from a reference mine at 0.6 UUT: deletions take only sequences that miss one
  of its items, insertions only sequences that hold all of them. Where the reference mine does not
  finish or runs out of heap the operation has no target and is drawn at random; the
  `with a target` column counts the operations that had one.

## Summary

| check | status | operations | with a target | direct deletions | direct insertions | direct modifications | mismatches | faults caught |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| random streams (MixedBudgetFuzz), rule joint | finished | 170,000 |  | 47,587 | 15,481 | 14,626 | 0 |  |
| random streams (MixedBudgetFuzz), rule iaumax | finished | 170,000 |  | 48,944 | 16,569 | 21,543 | 226 |  |
| hand-built cases (MixedBudgetProbe), rule joint | finished | 8 |  |  |  |  | 0 |  |
| hand-built cases (MixedBudgetProbe), rule iaumax | finished | 8 |  |  |  |  | 3 |  |
| rescan rule acceptance (RescanRuleAcceptanceTest) | finished | 23 |  |  |  |  | 0 |  |
| fault injection, random streams and hand-built cases | finished | 2 |  |  |  |  |  | 2 |
| fault injection, adversarial runs on real databases | finished | 2 |  |  |  |  |  | 2 |
| direct path on real databases, adversarial operations | finished | 128 | 108 | 30 | 49 | 30 | 0 |  |
| direct path on real databases, random operations | finished | 128 | 0 | 30 | 58 | 28 | 0 |  |

## Direct path on the real databases, per database

### adversarial

| database | operations | with a target | insert direct | insert rescan | delete direct | delete rescan | modify direct | modify rescan | mismatches |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| SIGN | 16 | 16 | 7 | 1 | 2 | 2 | 4 | 0 | 0 |
| LEVIATHAN | 16 | 16 | 6 | 2 | 4 | 0 | 3 | 1 | 0 |
| BIBLE | 16 | 16 | 6 | 2 | 4 | 0 | 4 | 0 | 0 |
| BMS1 | 16 | 16 | 5 | 3 | 4 | 0 | 4 | 0 | 0 |
| FIFA | 16 | 16 | 6 | 2 | 4 | 0 | 4 | 0 | 0 |
| KOSARAK | 16 | 16 | 6 | 2 | 4 | 0 | 4 | 0 | 0 |
| C8T1S5I8N5K | 16 | 12 | 5 | 3 | 4 | 0 | 3 | 1 | 0 |
| TAFENG | 16 | 0 | 8 | 0 | 4 | 0 | 4 | 0 | 0 |

### random

| database | operations | with a target | insert direct | insert rescan | delete direct | delete rescan | modify direct | modify rescan | mismatches |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| SIGN | 16 | 0 | 7 | 1 | 3 | 1 | 3 | 1 | 0 |
| LEVIATHAN | 16 | 0 | 7 | 1 | 3 | 1 | 3 | 1 | 0 |
| BIBLE | 16 | 0 | 7 | 1 | 4 | 0 | 3 | 1 | 0 |
| BMS1 | 16 | 0 | 8 | 0 | 4 | 0 | 4 | 0 | 0 |
| FIFA | 16 | 0 | 7 | 1 | 4 | 0 | 4 | 0 | 0 |
| KOSARAK | 16 | 0 | 7 | 1 | 4 | 0 | 3 | 1 | 0 |
| C8T1S5I8N5K | 16 | 0 | 7 | 1 | 4 | 0 | 4 | 0 | 0 |
| TAFENG | 16 | 0 | 8 | 0 | 4 | 0 | 4 | 0 | 0 |

## Commands

    # acceptance: exit 0, commit b1ea30af, 2026-10-12T08:11:50
    java -cp build-vj verify.RescanRuleAcceptanceTest
    # adversarial: exit 0, commit d7e8bf8e, 2026-10-12T10:17:56
    java -Xmx24g -Xss64m -cp build-vj verify.DirectPathVerifier ALL --adversarial --ops 16 --out results/verify-joint/adversarial
    # fault-injection: exit 0, commit b1ea30af, 2026-10-12T08:12:02
    python3 analysis/fault_injection.py --trials 2000 --seed 7
    # fault-injection-real: exit 0, commit e954773c, 2026-10-12T10:38:57
    python3 analysis/fault_injection.py --real SIGN,LEVIATHAN,BIBLE,C8T1S5I8N5K,TAFENG
    # fuzz: exit 0, commit b1ea30af, 2026-10-12T08:11:48
    bash -c for s in "2000 7" "5000 11" "5000 23" "5000 101"; do set -- $s; for r in joint iaumax; do java -cp build-vj verify.MixedBudgetFuzz $r $1 $2; done; done; java -cp build-vj verify.MixedBudgetProbe joint; java -cp build-vj verify.MixedBudgetProbe iaumax; true
    # random: exit 0, commit b1ea30af, 2026-10-12T08:19:08
    java -Xmx24g -Xss64m -cp build-vj verify.DirectPathVerifier ALL --ops 16 --out results/verify-joint/random
