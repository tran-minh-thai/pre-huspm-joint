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
* `ips-random`: the proposal's miner with item pruning off, once and to a fixpoint against
  exhaustive enumeration on small random databases (`verify.ItemRemovalCheck random`): pattern
  sets and utilities, at the cutoff and at UUT.
* `ips-real`: the same miner with the pruning off, once and to a fixpoint on the real databases,
  on `D_init` and on the whole database at the mining floor; the pattern sets and every utility
  must be identical (`verify.ItemRemovalCheck real`).
* `fault-injection-ips`: faults written into the pruning, each run through `ips-random`; a
  control without a fault must pass.
* `fault-injection`, `fault-injection-real`: each fault is written into a copy of the source and
  the checks are run on it; a fault counts as caught when a check reports a mismatch. A run that
  does not finish catches nothing.
* `random`, `adversarial`, `adversarial-tafeng`: `verify.DirectPathVerifier` on the real
  databases, the first half as `D_init`, 16 operations sized to spend a sixth of the rescan
  budget. In adversarial mode each operation is shaped around a target, the untracked pattern of
  highest utility from a reference mine at 0.6 UUT (`-Dverify.probeRl`, 0.7 in
  `adversarial-tafeng-probe0.7`): deletions take only sequences that miss one of its items,
  insertions only sequences that hold all of them. Where the reference mine does not finish or
  runs out of heap the operation has no target and is drawn at random; the `with a target`
  column counts the operations that had one.

## Summary

| check | status | operations | with a target | direct deletions | direct insertions | direct modifications | mismatches | faults caught |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| random streams (MixedBudgetFuzz), rule joint | finished | 170,000 |  | 47,587 | 15,481 | 14,626 | 0 |  |
| random streams (MixedBudgetFuzz), rule iaumax | finished | 170,000 |  | 48,944 | 16,569 | 21,543 | 226 |  |
| hand-built cases (MixedBudgetProbe), rule joint | finished | 8 |  |  |  |  | 0 |  |
| hand-built cases (MixedBudgetProbe), rule iaumax | finished | 8 |  |  |  |  | 3 |  |
| rescan rule acceptance (RescanRuleAcceptanceTest) | finished | 49 |  |  |  |  | 0 |  |
| rescan rule acceptance, floor-selection-acceptance | finished | 49 |  |  |  |  | 0 |  |
| rescan rule acceptance, min-floor-acceptance | finished | 49 |  |  |  |  | 0 |  |
| rescan rule acceptance, plain-floor-acceptance | finished | 49 |  |  |  |  | 0 |  |
| rescan rule acceptance, pre-tests-acceptance | finished | 49 |  |  |  |  | 0 |  |
| rescan rule acceptance, prehuspm-tracking-acceptance | finished | 49 |  |  |  |  | 0 |  |
| random streams (MixedBudgetFuzz), floor-selection-fuzz, rule joint | finished | 510,000 |  | 120,279 | 41,812 | 39,215 | 0 |  |
| random streams (MixedBudgetFuzz), min-floor-fuzz, rule joint | finished | 170,000 |  | 47,587 | 15,481 | 14,626 | 0 |  |
| random streams (MixedBudgetFuzz), plain-floor-fuzz, rule joint | finished | 170,000 |  | 47,587 | 15,481 | 14,626 | 0 |  |
| random streams (MixedBudgetFuzz), pre-tests-fuzz, rule joint | finished | 170,000 |  | 0 | 63,216 | 0 | 0 |  |
| random streams (MixedBudgetFuzz), pre-tests-fuzz, rule pre_swumax | finished | 170,000 |  | 0 | 56,718 | 0 | 0 |  |
| random streams (MixedBudgetFuzz), pre-tests-fuzz, rule pre_tsu | finished | 170,000 |  | 0 | 53,371 | 0 | 0 |  |
| random streams (MixedBudgetFuzz), prehuspm-tracking-fuzz, rule joint | finished | 170,000 |  | 0 | 63,216 | 0 | 0 |  |
| random streams (MixedBudgetFuzz), prehuspm-tracking-fuzz, rule pre_swumax | finished | 170,000 |  | 0 | 56,718 | 0 | 0 |  |
| bound invariant B(X) >= u(X), prehuspm-tracking-fuzz | finished | 7,748,983 |  |  |  |  | 0 |  |
| patterns against the proposal after every operation, husps-mixed | finished | 8,820 |  |  |  |  | 0 |  |
| patterns against the proposal after every operation, husps-sc2 | finished | 7,070 |  |  |  |  | 0 |  |
| item pruning against exhaustive enumeration (ItemRemovalCheck random) | finished | 1,444 | 1,951 |  |  |  | 0 |  |
| item pruning on and off on real databases, init (ItemRemovalCheck real) | finished | 8 |  |  |  |  | 0 |  |
| item pruning on and off on real databases, whole (ItemRemovalCheck real) | finished | 7 |  |  |  |  | 0 |  |
| fault injection, random streams and hand-built cases | finished | 4 |  |  |  |  |  | 4 |
| fault injection, adversarial runs on real databases | finished | 4 |  |  |  |  |  | 4 |
| fault injection into the item pruning | finished | 4 |  |  |  |  |  | 4 |
| fault injection into the rescan floor selection | finished | 2 |  |  |  |  |  | 2 |
| direct path on real databases, adversarial operations | finished | 128 | 108 | 30 | 49 | 30 | 0 |  |
| direct path on real databases, adversarial-tafeng operations | finished | 16 | 0 | 4 | 8 | 4 | 0 |  |
| direct path on real databases, adversarial-tafeng-probe0.7 operations | finished | 16 | 12 | 4 | 6 | 4 | 0 |  |
| direct path on real databases, floor-selection-adversarial operations | finished | 128 | 108 | 30 | 49 | 30 | 0 |  |
| direct path on real databases, floor-selection-random operations | finished | 128 | 0 | 30 | 58 | 28 | 0 |  |
| direct path on real databases, floor-selection-random-floors operations | finished | 128 | 0 | 30 | 58 | 28 | 0 |  |
| direct path on real databases, floor-selection-switching-adversarial operations | finished | 168 | 141 | 18 | 33 | 17 | 0 |  |
| direct path on real databases, floor-selection-switching-random operations | finished | 176 | 0 | 19 | 42 | 22 | 0 |  |
| direct path on real databases, min-floor-adversarial operations | finished | 128 | 108 | 30 | 49 | 30 | 0 |  |
| direct path on real databases, min-floor-random operations | finished | 128 | 0 | 30 | 58 | 28 | 0 |  |
| direct path on real databases, plain-floor-adversarial operations | finished | 128 | 108 | 30 | 49 | 30 | 0 |  |
| direct path on real databases, plain-floor-random operations | finished | 128 | 0 | 30 | 58 | 28 | 0 |  |
| direct path on real databases, pre-tests-adversarial operations | finished | 256 | 224 | 0 | 210 | 0 | 0 |  |
| direct path on real databases, pre-tests-random operations | finished | 256 | 0 | 0 | 218 | 0 | 0 |  |
| direct path on real databases, prehuspm-tracking-adversarial operations | finished | 128 | 96 | 0 | 112 | 0 | 0 |  |
| direct path on real databases, prehuspm-tracking-random operations | finished | 128 | 0 | 0 | 117 | 0 | 0 |  |
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

### adversarial-tafeng

| database | operations | with a target | insert direct | insert rescan | delete direct | delete rescan | modify direct | modify rescan | mismatches |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| TAFENG | 16 | 0 | 8 | 0 | 4 | 0 | 4 | 0 | 0 |

### adversarial-tafeng-probe0.7

| database | operations | with a target | insert direct | insert rescan | delete direct | delete rescan | modify direct | modify rescan | mismatches |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| TAFENG | 16 | 12 | 6 | 2 | 4 | 0 | 4 | 0 | 0 |

### floor-selection-adversarial

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

### floor-selection-random

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

### floor-selection-random-floors

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

### floor-selection-switching-adversarial

| database | operations | with a target | insert direct | insert rescan | delete direct | delete rescan | modify direct | modify rescan | mismatches |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| SIGN | 24 | 24 | 4 | 8 | 2 | 4 | 2 | 4 | 0 |
| LEVIATHAN | 24 | 24 | 4 | 8 | 2 | 4 | 2 | 4 | 0 |
| BIBLE | 24 | 24 | 4 | 8 | 2 | 4 | 2 | 4 | 0 |
| BMS1 | 16 | 16 | 3 | 5 | 2 | 2 | 1 | 3 | 0 |
| FIFA | 24 | 24 | 4 | 8 | 2 | 4 | 2 | 4 | 0 |
| KOSARAK | 24 | 24 | 4 | 8 | 2 | 4 | 2 | 4 | 0 |
| C8T1S5I8N5K | 16 | 5 | 5 | 3 | 3 | 1 | 2 | 2 | 0 |
| TAFENG | 16 | 0 | 5 | 3 | 3 | 1 | 4 | 0 | 0 |

### floor-selection-switching-random

| database | operations | with a target | insert direct | insert rescan | delete direct | delete rescan | modify direct | modify rescan | mismatches |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| SIGN | 24 | 0 | 4 | 8 | 2 | 4 | 2 | 4 | 0 |
| LEVIATHAN | 24 | 0 | 4 | 8 | 2 | 4 | 2 | 4 | 0 |
| BIBLE | 24 | 0 | 4 | 8 | 2 | 4 | 2 | 4 | 0 |
| BMS1 | 24 | 0 | 10 | 2 | 4 | 2 | 4 | 2 | 0 |
| FIFA | 24 | 0 | 4 | 8 | 2 | 4 | 2 | 4 | 0 |
| KOSARAK | 24 | 0 | 4 | 8 | 2 | 4 | 2 | 4 | 0 |
| C8T1S5I8N5K | 16 | 0 | 7 | 1 | 2 | 2 | 4 | 0 | 0 |
| TAFENG | 16 | 0 | 5 | 3 | 3 | 1 | 4 | 0 | 0 |

### min-floor-adversarial

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

### min-floor-random

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

### plain-floor-adversarial

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

### plain-floor-random

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

### pre-tests-adversarial

| database | operations | with a target | insert direct | insert rescan | delete direct | delete rescan | modify direct | modify rescan | mismatches |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| SIGN | 32 | 32 | 26 | 6 | 0 | 0 | 0 | 0 | 0 |
| LEVIATHAN | 32 | 32 | 26 | 6 | 0 | 0 | 0 | 0 | 0 |
| BIBLE | 32 | 32 | 28 | 4 | 0 | 0 | 0 | 0 | 0 |
| BMS1 | 32 | 32 | 26 | 6 | 0 | 0 | 0 | 0 | 0 |
| FIFA | 32 | 32 | 28 | 4 | 0 | 0 | 0 | 0 | 0 |
| KOSARAK | 32 | 32 | 28 | 4 | 0 | 0 | 0 | 0 | 0 |
| C8T1S5I8N5K | 32 | 32 | 20 | 12 | 0 | 0 | 0 | 0 | 0 |
| TAFENG | 32 | 0 | 28 | 4 | 0 | 0 | 0 | 0 | 0 |

### pre-tests-random

| database | operations | with a target | insert direct | insert rescan | delete direct | delete rescan | modify direct | modify rescan | mismatches |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| SIGN | 32 | 0 | 26 | 6 | 0 | 0 | 0 | 0 | 0 |
| LEVIATHAN | 32 | 0 | 26 | 6 | 0 | 0 | 0 | 0 | 0 |
| BIBLE | 32 | 0 | 28 | 4 | 0 | 0 | 0 | 0 | 0 |
| BMS1 | 32 | 0 | 29 | 3 | 0 | 0 | 0 | 0 | 0 |
| FIFA | 32 | 0 | 29 | 3 | 0 | 0 | 0 | 0 | 0 |
| KOSARAK | 32 | 0 | 28 | 4 | 0 | 0 | 0 | 0 | 0 |
| C8T1S5I8N5K | 32 | 0 | 24 | 8 | 0 | 0 | 0 | 0 | 0 |
| TAFENG | 32 | 0 | 28 | 4 | 0 | 0 | 0 | 0 | 0 |

### prehuspm-tracking-adversarial

| database | operations | with a target | insert direct | insert rescan | delete direct | delete rescan | modify direct | modify rescan | mismatches |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| SIGN | 16 | 16 | 13 | 3 | 0 | 0 | 0 | 0 | 0 |
| LEVIATHAN | 16 | 16 | 13 | 3 | 0 | 0 | 0 | 0 | 0 |
| BIBLE | 16 | 16 | 14 | 2 | 0 | 0 | 0 | 0 | 0 |
| BMS1 | 16 | 0 | 16 | 0 | 0 | 0 | 0 | 0 | 0 |
| FIFA | 16 | 16 | 14 | 2 | 0 | 0 | 0 | 0 | 0 |
| KOSARAK | 16 | 16 | 14 | 2 | 0 | 0 | 0 | 0 | 0 |
| C8T1S5I8N5K | 16 | 16 | 12 | 4 | 0 | 0 | 0 | 0 | 0 |
| TAFENG | 16 | 0 | 16 | 0 | 0 | 0 | 0 | 0 | 0 |

### prehuspm-tracking-random

| database | operations | with a target | insert direct | insert rescan | delete direct | delete rescan | modify direct | modify rescan | mismatches |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| SIGN | 16 | 0 | 13 | 3 | 0 | 0 | 0 | 0 | 0 |
| LEVIATHAN | 16 | 0 | 13 | 3 | 0 | 0 | 0 | 0 | 0 |
| BIBLE | 16 | 0 | 14 | 2 | 0 | 0 | 0 | 0 | 0 |
| BMS1 | 16 | 0 | 16 | 0 | 0 | 0 | 0 | 0 | 0 |
| FIFA | 16 | 0 | 15 | 1 | 0 | 0 | 0 | 0 | 0 |
| KOSARAK | 16 | 0 | 14 | 2 | 0 | 0 | 0 | 0 | 0 |
| C8T1S5I8N5K | 16 | 0 | 16 | 0 | 0 | 0 | 0 | 0 | 0 |
| TAFENG | 16 | 0 | 16 | 0 | 0 | 0 | 0 | 0 | 0 |

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

    # acceptance: exit 0, commit e84b5a89, 2026-10-17T13:40:18
    java -cp build-v31 verify.RescanRuleAcceptanceTest
    # adversarial: exit 0, commit e84b5a89, 2026-10-17T15:07:10
    java -Xmx24g -Xss64m -cp build-v31 verify.DirectPathVerifier ALL --adversarial --ops 16 --out results/verify-joint/adversarial
    # adversarial-tafeng: exit 0, commit e84b5a89, 2026-10-17T15:12:41
    java -Xmx24g -Xss64m -cp build-v31 verify.DirectPathVerifier TAFENG --adversarial --ops 16 --out results/verify-joint/adversarial-tafeng
    # adversarial-tafeng-probe0.7: exit 0, commit e84b5a89, 2026-10-17T15:19:25
    java -Dverify.probeRl=0.7 -Xmx24g -Xss64m -cp build-v31 verify.DirectPathVerifier TAFENG --adversarial --ops 16 --out results/verify-joint/adversarial-tafeng-probe0.7
    # fault-injection: exit 0, commit e84b5a89, 2026-10-17T13:41:33
    python3 analysis/fault_injection.py --trials 2000 --seed 7
    # fault-injection-floor-selection: exit 0, commit e84b5a89, 2026-10-17T13:41:48
    python3 analysis/fault_injection.py --floor-selection --trials 2000 --seed 7
    # fault-injection-ips: exit 0, commit 41f3af39, 2026-10-12T15:08:58
    python3 analysis/fault_injection.py --item-removal --trials 2000 --seed 1
    # fault-injection-real: exit 0, commit e84b5a89, 2026-10-18T00:07:46
    python3 analysis/fault_injection.py --real SIGN,LEVIATHAN,BIBLE,C8T1S5I8N5K,TAFENG
    # floor-selection-acceptance: exit 0, commit e84b5a89, 2026-10-17T13:41:49
    java -cp build-v31 verify.RescanRuleAcceptanceTest
    # floor-selection-adversarial: exit 0, commit e84b5a89, 2026-10-17T17:05:33
    java -Dadapt.floorWindow=1 -Xmx24g -Xss64m -cp build-v31 verify.DirectPathVerifier ALL --adversarial --ops 16 --out results/verify-joint/floor-selection-adversarial
    # floor-selection-fuzz: exit 0, commit e84b5a89, 2026-10-17T13:42:51
    bash -c rc=0; for w in 1 2 5; do for s in "2000 7" "5000 11" "5000 23" "5000 101"; do set -- $s; java -Dadapt.floorWindow=$w -cp build-v31 verify.MixedBudgetFuzz joint $1 $2 || rc=1; done; done; exit $rc
    # floor-selection-random: exit 0, commit e84b5a89, 2026-10-17T16:11:51
    java -Dadapt.floorWindow=1 -Xmx24g -Xss64m -cp build-v31 verify.DirectPathVerifier ALL --ops 16 --out results/verify-joint/floor-selection-random
    # floor-selection-random-floors: exit 0, commit e84b5a89, 2026-10-17T17:12:00
    java -Dadapt.floorWindow=1 -Xmx24g -Xss64m -cp build-v31 verify.DirectPathVerifier ALL --ops 16 --out results/verify-joint/floor-selection-random-floors
    # floor-selection-switching-adversarial: exit 0, commit e84b5a89, 2026-10-17T21:32:26
    java -Dadapt.floorWindow=1 -Xmx24g -Xss64m -cp build-v31 verify.DirectPathVerifier ALL --adversarial --ops 24 --share-alt 0.5 --out results/verify-joint/floor-selection-switching-adversarial
    # floor-selection-switching-random: exit 0, commit e84b5a89, 2026-10-17T17:44:06
    java -Dadapt.floorWindow=1 -Xmx24g -Xss64m -cp build-v31 verify.DirectPathVerifier ALL --ops 24 --share-alt 0.5 --out results/verify-joint/floor-selection-switching-random
    # fuzz: exit 0, commit e84b5a89, 2026-10-17T13:40:59
    bash -c rc=0; for s in "2000 7" "5000 11" "5000 23" "5000 101"; do set -- $s; for r in joint iaumax; do java -cp build-v31 verify.MixedBudgetFuzz $r $1 $2 || { [ $r = joint ] && rc=1; }; done; done; java -cp build-v31 verify.MixedBudgetProbe joint || rc=1; java -cp build-v31 verify.MixedBudgetProbe iaumax; exit $rc
    # husps-mixed: exit 0, commit e84b5a89, 2026-10-18T01:32:25
    java -Xmx16g -Xss64m -cp build-v31 -Dverify.husps=true -Dseeds=5 -Drepeats=1 -Ddataset.only=SIGN,LEVIATHAN,C8T1S5I8N5K,TAFENG -Dalgo.only=Adaptive-Full,Remine-from-scratch,Remine-HUSP-ULL,Remine-HUSP-SP,Adaptive-FloorSelect,Remine-own-UUT,Joint-rule+plain-floor,Joint-rule+min-floor -Dresults.dir=results/verify-joint/husps-mixed/ AutomatedExperimentRunner SC6 SC6D SC6B
    # husps-sc2: exit 0, commit e84b5a89, 2026-10-18T06:32:48
    bash -c rc=0; for d in SIGN TAFENG C8T1S5I8N5K LEVIATHAN BIBLE KOSARAK FIFA; do java -Xmx20g -Xss64m -cp build-v31 -Dverify.husps=true -Drepeats=1 -Ddataset.only=$d -Dalgo.only=Adaptive-Full,Remine-HUSP-ULL,Remine-HUSP-SP,Remine-own-UUT,Adaptive-FloorSelect,Joint-miner+SWUmax,Joint-miner+TSU,Joint-rule+PreHUSPM-tracking,Joint-miner+SWUmax+PreHUSPM-tracking,Joint-rule+plain-floor,Joint-rule+min-floor -Dresults.dir=results/verify-joint/husps-sc2/ AutomatedExperimentRunner SC2 || rc=1; done; exit $rc
    # ips-random: exit 0, commit 41f3af39, 2026-10-12T14:58:58
    java -Xmx8g -Xss64m -cp build-vj verify.ItemRemovalCheck random 2000 1
    # ips-real: exit 0, commit 41f3af39, 2026-10-12T15:04:42
    bash -c rc=0; for d in SIGN LEVIATHAN FIFA BIBLE BMS1 KOSARAK C8T1S5I8N5K TAFENG; do java -Xmx24g -Xss64m -cp build-vj verify.ItemRemovalCheck real $d init floor || rc=1; done; for d in SIGN LEVIATHAN FIFA BIBLE KOSARAK C8T1S5I8N5K TAFENG; do java -Xmx24g -Xss64m -cp build-vj verify.ItemRemovalCheck real $d whole floor || rc=1; done; exit $rc
    # min-floor-acceptance: exit 0, commit e84b5a89, 2026-10-17T13:41:21
    java -Dadapt.floor=min -cp build-v31 verify.RescanRuleAcceptanceTest
    # min-floor-adversarial: exit 0, commit e84b5a89, 2026-10-17T16:05:48
    java -Dadapt.floor=min -Xmx24g -Xss64m -cp build-v31 verify.DirectPathVerifier ALL --adversarial --ops 16 --out results/verify-joint/min-floor-adversarial
    # min-floor-fuzz: exit 0, commit e84b5a89, 2026-10-17T13:41:20
    bash -c rc=0; for s in "2000 7" "5000 11" "5000 23" "5000 101"; do set -- $s; java -Dadapt.floor=min -cp build-v31 verify.MixedBudgetFuzz joint $1 $2 || rc=1; done; exit $rc
    # min-floor-random: exit 0, commit e84b5a89, 2026-10-17T15:25:18
    java -Dadapt.floor=min -Xmx24g -Xss64m -cp build-v31 verify.DirectPathVerifier ALL --ops 16 --out results/verify-joint/min-floor-random
    # plain-floor-acceptance: exit 0, commit e84b5a89, 2026-10-19T15:44:39
    java -Dadapt.floor=plain -cp build-v31 verify.RescanRuleAcceptanceTest
    # plain-floor-adversarial: exit 0, commit e84b5a89, 2026-10-19T16:56:01
    java -Dadapt.floor=plain -Xmx24g -Xss64m -cp build-v31 verify.DirectPathVerifier ALL --adversarial --ops 16 --out results/verify-joint/plain-floor-adversarial
    # plain-floor-fuzz: exit 0, commit e84b5a89, 2026-10-19T15:45:25
    bash -c rc=0; for s in "2000 7" "5000 11" "5000 23" "5000 101"; do set -- $s; java -Dadapt.floor=plain -cp build-v31 verify.MixedBudgetFuzz joint $1 $2 || rc=1; done; exit $rc
    # plain-floor-random: exit 0, commit e84b5a89, 2026-10-19T15:51:39
    java -Dadapt.floor=plain -Xmx24g -Xss64m -cp build-v31 verify.DirectPathVerifier ALL --ops 16 --out results/verify-joint/plain-floor-random
    # pre-tests-acceptance: exit 0, commit e84b5a89, 2026-10-17T13:42:52
    java -cp build-v31 verify.RescanRuleAcceptanceTest
    # pre-tests-adversarial: exit 0, commit e84b5a89, 2026-10-17T22:59:08
    bash -c rc=0; for r in pre_swumax pre_tsu; do java -Xmx24g -Xss64m -cp build-v31 verify.DirectPathVerifier ALL --adversarial --ops 16 --rule $r --insert-only --out results/verify-joint/pre-tests-adversarial || rc=1; done; exit $rc
    # pre-tests-fuzz: exit 0, commit e84b5a89, 2026-10-17T13:43:58
    bash -c rc=0; for s in "2000 7" "5000 11" "5000 23" "5000 101"; do set -- $s; for r in pre_swumax pre_tsu joint; do java -cp build-v31 verify.MixedBudgetFuzz $r $1 $2 insert-only || rc=1; done; done; exit $rc
    # pre-tests-random: exit 0, commit e84b5a89, 2026-10-17T21:40:54
    bash -c rc=0; for r in pre_swumax pre_tsu; do java -Xmx24g -Xss64m -cp build-v31 verify.DirectPathVerifier ALL --ops 16 --rule $r --insert-only --out results/verify-joint/pre-tests-random || rc=1; done; exit $rc
    # prehuspm-tracking-acceptance: exit 0, commit e84b5a89, 2026-10-17T13:43:59
    java -cp build-v31 verify.RescanRuleAcceptanceTest
    # prehuspm-tracking-adversarial: exit 0, commit e84b5a89, 2026-10-17T23:52:45
    java -Dadapt.tracking=prehuspm -Xmx24g -Xss64m -cp build-v31 verify.DirectPathVerifier ALL --insert-only --adversarial --ops 16 --out results/verify-joint/prehuspm-tracking-adversarial
    # prehuspm-tracking-equivalence: exit 0, commit e84b5a89, 2026-10-17T14:29:53
    java -Xmx24g -Xss64m -cp build-v31 verify.PreHuspmTrackingCheck
    # prehuspm-tracking-fuzz: exit 0, commit e84b5a89, 2026-10-17T13:44:44
    bash -c rc=0; for s in "2000 7" "5000 11" "5000 23" "5000 101"; do set -- $s; for r in joint pre_swumax; do java -Dadapt.tracking=prehuspm -cp build-v31 verify.MixedBudgetFuzz $r $1 $2 insert-only || rc=1; done; done; exit $rc
    # prehuspm-tracking-random: exit 0, commit e84b5a89, 2026-10-17T23:15:21
    java -Dadapt.tracking=prehuspm -Xmx24g -Xss64m -cp build-v31 verify.DirectPathVerifier ALL --insert-only --ops 16 --out results/verify-joint/prehuspm-tracking-random
    # random: exit 0, commit e84b5a89, 2026-10-17T14:33:05
    java -Xmx24g -Xss64m -cp build-v31 verify.DirectPathVerifier ALL --ops 16 --out results/verify-joint/random
    # static-joint-miner-uut: exit 0, commit 90622229, 2026-10-16T21:29:19
    java -Xmx24g -Xss64m -cp build-v30 -Drepeats=1 -Dalgo.only=HUSP-ULL,Joint-miner@UUT -Dresults.dir=results/verify-joint/static-joint-miner-uut/ AutomatedExperimentRunner SC1 SC3
