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
| rescan rule acceptance, merge-acceptance | finished | 49 |  |  |  |  | 0 |  |
| rescan rule acceptance, min-floor-acceptance | finished | 49 |  |  |  |  | 0 |  |
| rescan rule acceptance, plain-floor-acceptance | finished | 49 |  |  |  |  | 0 |  |
| rescan rule acceptance, pre-tests-acceptance | finished | 49 |  |  |  |  | 0 |  |
| rescan rule acceptance, prehuspm-tracking-acceptance | finished | 49 |  |  |  |  | 0 |  |
| random streams (MixedBudgetFuzz), break-even-fuzz, rule joint | finished | 170,000 |  | 23,761 | 8,805 | 8,317 | 0 |  |
| random streams (MixedBudgetFuzz), break-even-nodes-fuzz, rule joint | finished | 170,000 |  | 29,570 | 11,560 | 10,813 | 0 |  |
| random streams (MixedBudgetFuzz), floor-selection-fuzz, rule joint | finished | 510,000 |  | 121,917 | 42,439 | 40,097 | 0 |  |
| random streams (MixedBudgetFuzz), merge-fuzz, rule joint | finished | 170,000 |  | 47,844 | 15,636 | 14,821 | 0 |  |
| random streams (MixedBudgetFuzz), min-floor-fuzz, rule joint | finished | 170,000 |  | 47,844 | 15,636 | 14,821 | 0 |  |
| random streams (MixedBudgetFuzz), plain-floor-fuzz, rule joint | finished | 170,000 |  | 47,844 | 15,636 | 14,821 | 0 |  |
| random streams (MixedBudgetFuzz), pre-tests-fuzz, rule joint | finished | 170,000 |  | 0 | 63,216 | 0 | 0 |  |
| random streams (MixedBudgetFuzz), pre-tests-fuzz, rule pre_swumax | finished | 170,000 |  | 0 | 56,718 | 0 | 0 |  |
| random streams (MixedBudgetFuzz), pre-tests-fuzz, rule pre_tsu | finished | 170,000 |  | 0 | 53,371 | 0 | 0 |  |
| random streams (MixedBudgetFuzz), prehuspm-tracking-fuzz, rule joint | finished | 170,000 |  | 0 | 63,216 | 0 | 0 |  |
| random streams (MixedBudgetFuzz), prehuspm-tracking-fuzz, rule pre_swumax | finished | 170,000 |  | 0 | 56,718 | 0 | 0 |  |
| bound invariant B(X) >= u(X), prehuspm-tracking-fuzz | finished | 7,748,983 |  |  |  |  | 0 |  |
| patterns against the proposal after every operation, husps-dunnhumby-mixed-all-arms | finished | 2,205 |  |  |  |  | 0 |  |
| patterns against the proposal after every operation, husps-long-sc10 | finished | 24,048 |  |  |  |  | 0 |  |
| patterns against the proposal after every operation, husps-long-sc10-dunnhumby | finished | 6,012 |  |  |  |  | 0 |  |
| patterns against the proposal after every operation, husps-long-sc10-method | finished | 6,012 |  |  |  |  | 0 |  |
| patterns against the proposal after every operation, husps-long-sc10h | exit 1 | 15,920 |  |  |  |  | 571 |  |
| patterns against the proposal after every operation, husps-long-sc10h-dunnhumby | finished | 6,012 |  |  |  |  | 0 |  |
| patterns against the proposal after every operation, husps-long-sc10h-method | finished | 4,509 |  |  |  |  | 0 |  |
| patterns against the proposal after every operation, husps-long-sc10h-sequences-c8t1s5i8n5k | finished | 6,012 |  |  |  |  | 0 |  |
| patterns against the proposal after every operation, husps-long-sc10h-sequences-leviathan | finished | 6,012 |  |  |  |  | 0 |  |
| patterns against the proposal after every operation, husps-long-sc10h-sequences-tafeng | finished | 6,012 |  |  |  |  | 0 |  |
| patterns against the proposal after every operation, husps-long-sc8 | finished | 24,048 |  |  |  |  | 0 |  |
| patterns against the proposal after every operation, husps-long-sc8-dunnhumby | finished | 6,012 |  |  |  |  | 0 |  |
| patterns against the proposal after every operation, husps-long-sc8-method | finished | 6,012 |  |  |  |  | 0 |  |
| patterns against the proposal after every operation, husps-long-sc8p | finished | 24,048 |  |  |  |  | 0 |  |
| patterns against the proposal after every operation, husps-long-sc8p-dunnhumby | finished | 6,012 |  |  |  |  | 0 |  |
| patterns against the proposal after every operation, husps-long-sc8p-method | finished | 6,012 |  |  |  |  | 0 |  |
| patterns against the proposal after every operation, husps-long-sc9 | finished | 30,060 |  |  |  |  | 0 |  |
| patterns against the proposal after every operation, husps-long-sc9-dunnhumby | finished | 7,515 |  |  |  |  | 0 |  |
| patterns against the proposal after every operation, husps-long-sc9-index-rebuild | finished | 6,012 |  |  |  |  | 0 |  |
| patterns against the proposal after every operation, husps-long-sc9-method | finished | 6,012 |  |  |  |  | 0 |  |
| patterns against the proposal after every operation, husps-mixed | finished | 8,820 |  |  |  |  | 0 |  |
| patterns against the proposal after every operation, husps-mixed-break-even | finished | 5,040 |  |  |  |  | 0 |  |
| patterns against the proposal after every operation, husps-mixed-break-even-nodes | finished | 1,575 |  |  |  |  | 0 |  |
| patterns against the proposal after every operation, husps-mixed-remine | finished | 2,520 |  |  |  |  | 0 |  |
| patterns against the proposal after every operation, husps-sc2 | finished | 7,070 |  |  |  |  | 0 |  |
| patterns against the proposal after every operation, husps-sc2-break-even | finished | 1,414 |  |  |  |  | 0 |  |
| patterns against the proposal after every operation, husps-sc2-break-even-nodes | finished | 808 |  |  |  |  | 0 |  |
| patterns against the proposal after every operation, husps-sc2-remine | finished | 1,414 |  |  |  |  | 0 |  |
| item pruning against exhaustive enumeration (ItemRemovalCheck random) | finished | 1,444 | 1,951 |  |  |  | 0 |  |
| item pruning on and off on real databases, init (ItemRemovalCheck real) | finished | 8 |  |  |  |  | 0 |  |
| item pruning on and off on real databases, whole (ItemRemovalCheck real) | finished | 7 |  |  |  |  | 0 |  |
| Pre-HUSPM's tracking against Pre-HUSPM's buffer (PreHuspmTrackingCheck) | finished |  |  |  |  |  | 0 |  |
| running example of the manuscript (RunningExampleCheck) | finished | 49 |  |  |  |  | 0 |  |
| fault injection, random streams and hand-built cases | finished | 4 |  |  |  |  |  | 4 |
| fault injection, adversarial runs on real databases | finished | 4 |  |  |  |  |  | 4 |
| fault injection into the item pruning | finished | 4 |  |  |  |  |  | 4 |
| fault injection into the rescan floor selection | finished | 2 |  |  |  |  |  | 2 |
| direct path on real databases, adversarial operations | finished | 128 | 108 | 30 | 49 | 30 | 0 |  |
| direct path on real databases, adversarial-tafeng operations | finished | 16 | 0 | 4 | 8 | 4 | 0 |  |
| direct path on real databases, adversarial-tafeng-probe0.7 operations | finished | 16 | 12 | 4 | 6 | 4 | 0 |  |
| direct path on real databases, break-even-adversarial operations | finished | 128 | 108 | 25 | 46 | 25 | 0 |  |
| direct path on real databases, break-even-nodes-adversarial operations | finished | 128 | 102 | 25 | 49 | 26 | 0 |  |
| direct path on real databases, break-even-nodes-random operations | finished | 128 | 0 | 29 | 57 | 24 | 0 |  |
| direct path on real databases, break-even-nodes-switching-adversarial operations | finished | 166 | 128 | 19 | 42 | 19 | 0 |  |
| direct path on real databases, break-even-nodes-switching-random operations | finished | 192 | 0 | 31 | 60 | 27 | 0 |  |
| direct path on real databases, break-even-random operations | finished | 128 | 0 | 24 | 46 | 20 | 0 |  |
| direct path on real databases, break-even-switching-adversarial operations | finished | 150 | 123 | 21 | 40 | 14 | 0 |  |
| direct path on real databases, break-even-switching-random operations | finished | 184 | 0 | 26 | 53 | 23 | 0 |  |
| direct path on real databases, floor-selection-adversarial operations | finished | 128 | 108 | 30 | 49 | 30 | 0 |  |
| direct path on real databases, floor-selection-random operations | finished | 128 | 0 | 30 | 58 | 28 | 0 |  |
| direct path on real databases, floor-selection-random-floors operations | finished | 128 | 0 | 30 | 58 | 28 | 0 |  |
| direct path on real databases, floor-selection-switching-adversarial operations | finished | 168 | 141 | 18 | 33 | 17 | 0 |  |
| direct path on real databases, floor-selection-switching-random operations | finished | 176 | 0 | 19 | 42 | 22 | 0 |  |
| direct path on real databases, merge-adversarial operations | finished | 128 | 108 | 30 | 49 | 30 | 0 |  |
| direct path on real databases, merge-random operations | finished | 128 | 0 | 30 | 58 | 28 | 0 |  |
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

### break-even-adversarial

| database | operations | with a target | insert direct | insert rescan | delete direct | delete rescan | modify direct | modify rescan | mismatches |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| SIGN | 16 | 16 | 5 | 3 | 2 | 2 | 3 | 1 | 0 |
| LEVIATHAN | 16 | 16 | 6 | 2 | 4 | 0 | 3 | 1 | 0 |
| BIBLE | 16 | 16 | 7 | 1 | 3 | 1 | 3 | 1 | 0 |
| BMS1 | 16 | 16 | 5 | 3 | 3 | 1 | 4 | 0 | 0 |
| FIFA | 16 | 16 | 7 | 1 | 3 | 1 | 3 | 1 | 0 |
| KOSARAK | 16 | 16 | 4 | 4 | 3 | 1 | 2 | 2 | 0 |
| C8T1S5I8N5K | 16 | 12 | 4 | 4 | 3 | 1 | 3 | 1 | 0 |
| TAFENG | 16 | 0 | 8 | 0 | 4 | 0 | 4 | 0 | 0 |

### break-even-nodes-adversarial

| database | operations | with a target | insert direct | insert rescan | delete direct | delete rescan | modify direct | modify rescan | mismatches |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| SIGN | 16 | 16 | 6 | 2 | 2 | 2 | 4 | 0 | 0 |
| LEVIATHAN | 16 | 16 | 6 | 2 | 4 | 0 | 3 | 1 | 0 |
| BIBLE | 16 | 16 | 7 | 1 | 3 | 1 | 3 | 1 | 0 |
| BMS1 | 16 | 16 | 3 | 5 | 2 | 2 | 3 | 1 | 0 |
| FIFA | 16 | 16 | 7 | 1 | 3 | 1 | 3 | 1 | 0 |
| KOSARAK | 16 | 16 | 7 | 1 | 3 | 1 | 3 | 1 | 0 |
| C8T1S5I8N5K | 16 | 6 | 5 | 3 | 4 | 0 | 3 | 1 | 0 |
| TAFENG | 16 | 0 | 8 | 0 | 4 | 0 | 4 | 0 | 0 |

### break-even-nodes-random

| database | operations | with a target | insert direct | insert rescan | delete direct | delete rescan | modify direct | modify rescan | mismatches |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| SIGN | 16 | 0 | 6 | 2 | 3 | 1 | 3 | 1 | 0 |
| LEVIATHAN | 16 | 0 | 7 | 1 | 3 | 1 | 2 | 2 | 0 |
| BIBLE | 16 | 0 | 7 | 1 | 4 | 0 | 2 | 2 | 0 |
| BMS1 | 16 | 0 | 8 | 0 | 4 | 0 | 4 | 0 | 0 |
| FIFA | 16 | 0 | 7 | 1 | 3 | 1 | 4 | 0 | 0 |
| KOSARAK | 16 | 0 | 7 | 1 | 4 | 0 | 2 | 2 | 0 |
| C8T1S5I8N5K | 16 | 0 | 7 | 1 | 4 | 0 | 3 | 1 | 0 |
| TAFENG | 16 | 0 | 8 | 0 | 4 | 0 | 4 | 0 | 0 |

### break-even-nodes-switching-adversarial

| database | operations | with a target | insert direct | insert rescan | delete direct | delete rescan | modify direct | modify rescan | mismatches |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| SIGN | 24 | 24 | 7 | 5 | 2 | 4 | 3 | 3 | 0 |
| LEVIATHAN | 6 | 6 | 2 | 1 | 1 | 1 | 0 | 1 | 0 |
| BIBLE | 24 | 24 | 8 | 4 | 2 | 4 | 3 | 3 | 0 |
| BMS1 | 16 | 16 | 0 | 8 | 1 | 3 | 1 | 3 | 0 |
| FIFA | 24 | 24 | 4 | 8 | 2 | 4 | 2 | 4 | 0 |
| KOSARAK | 24 | 24 | 8 | 4 | 3 | 3 | 3 | 3 | 0 |
| C8T1S5I8N5K | 16 | 5 | 4 | 4 | 2 | 2 | 1 | 3 | 0 |
| TAFENG | 16 | 0 | 5 | 3 | 3 | 1 | 4 | 0 | 0 |
| DUNNHUMBY | 16 | 5 | 4 | 4 | 3 | 1 | 2 | 2 | 0 |

### break-even-nodes-switching-random

| database | operations | with a target | insert direct | insert rescan | delete direct | delete rescan | modify direct | modify rescan | mismatches |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| SIGN | 24 | 0 | 7 | 5 | 4 | 2 | 2 | 4 | 0 |
| LEVIATHAN | 24 | 0 | 8 | 4 | 4 | 2 | 3 | 3 | 0 |
| BIBLE | 24 | 0 | 8 | 4 | 4 | 2 | 3 | 3 | 0 |
| BMS1 | 24 | 0 | 10 | 2 | 4 | 2 | 5 | 1 | 0 |
| FIFA | 24 | 0 | 4 | 8 | 3 | 3 | 2 | 4 | 0 |
| KOSARAK | 24 | 0 | 8 | 4 | 4 | 2 | 3 | 3 | 0 |
| C8T1S5I8N5K | 16 | 0 | 5 | 3 | 2 | 2 | 3 | 1 | 0 |
| TAFENG | 16 | 0 | 5 | 3 | 3 | 1 | 4 | 0 | 0 |
| DUNNHUMBY | 16 | 0 | 5 | 3 | 3 | 1 | 2 | 2 | 0 |

### break-even-random

| database | operations | with a target | insert direct | insert rescan | delete direct | delete rescan | modify direct | modify rescan | mismatches |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| SIGN | 16 | 0 | 5 | 3 | 2 | 2 | 2 | 2 | 0 |
| LEVIATHAN | 16 | 0 | 7 | 1 | 3 | 1 | 2 | 2 | 0 |
| BIBLE | 16 | 0 | 7 | 1 | 4 | 0 | 2 | 2 | 0 |
| BMS1 | 16 | 0 | 8 | 0 | 4 | 0 | 4 | 0 | 0 |
| FIFA | 16 | 0 | 3 | 5 | 2 | 2 | 2 | 2 | 0 |
| KOSARAK | 16 | 0 | 4 | 4 | 2 | 2 | 2 | 2 | 0 |
| C8T1S5I8N5K | 16 | 0 | 4 | 4 | 3 | 1 | 2 | 2 | 0 |
| TAFENG | 16 | 0 | 8 | 0 | 4 | 0 | 4 | 0 | 0 |

### break-even-switching-adversarial

| database | operations | with a target | insert direct | insert rescan | delete direct | delete rescan | modify direct | modify rescan | mismatches |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| SIGN | 24 | 24 | 7 | 5 | 4 | 2 | 2 | 4 | 0 |
| LEVIATHAN | 6 | 6 | 2 | 1 | 1 | 1 | 0 | 1 | 0 |
| BIBLE | 24 | 24 | 8 | 4 | 4 | 2 | 3 | 3 | 0 |
| BMS1 | 16 | 16 | 5 | 3 | 3 | 1 | 2 | 2 | 0 |
| FIFA | 24 | 24 | 6 | 6 | 2 | 4 | 2 | 4 | 0 |
| KOSARAK | 24 | 24 | 6 | 6 | 4 | 2 | 2 | 4 | 0 |
| C8T1S5I8N5K | 16 | 5 | 2 | 6 | 2 | 2 | 1 | 3 | 0 |
| TAFENG | 16 | 0 | 4 | 4 | 1 | 3 | 2 | 2 | 0 |

### break-even-switching-random

| database | operations | with a target | insert direct | insert rescan | delete direct | delete rescan | modify direct | modify rescan | mismatches |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| SIGN | 24 | 0 | 7 | 5 | 4 | 2 | 2 | 4 | 0 |
| LEVIATHAN | 24 | 0 | 8 | 4 | 4 | 2 | 3 | 3 | 0 |
| BIBLE | 24 | 0 | 8 | 4 | 4 | 2 | 3 | 3 | 0 |
| BMS1 | 24 | 0 | 10 | 2 | 4 | 2 | 5 | 1 | 0 |
| FIFA | 24 | 0 | 7 | 5 | 4 | 2 | 3 | 3 | 0 |
| KOSARAK | 24 | 0 | 7 | 5 | 4 | 2 | 3 | 3 | 0 |
| C8T1S5I8N5K | 24 | 0 | 2 | 10 | 1 | 5 | 2 | 4 | 0 |
| TAFENG | 16 | 0 | 4 | 4 | 1 | 3 | 2 | 2 | 0 |

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

### merge-adversarial

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

### merge-random

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

## Records read

A check that was run again after the code it exercises changed keeps its earlier record; the
rerun lies in a subdirectory with a `generation.json`, and the newest finished record of a check
is the one read above (`analysis/verify_records.py`):

* `measured-code-2026-11-11` (2026-11-11, commit d89b094b8, or a later commit with the same source digest): The checks that the code changes since their last record make necessary, and the checks on small databases as a control. Since the long-stream checks of 31 October the algorithm changed in two places only: the floor selection records the cycle of the initial mine (d6197a7ed), which concerns the method's arm alone, and a method was added for the rebuild arms. So the method's arm is checked again on every long stream (husps-long-<scenario>-method), the three DUNNHUMBY long streams that had no check get one, and the switching streams of the method's rule, recorded before d6197a7ed, are run again. The random streams and acceptance tests of every rule and arm, the item pruning, the stream plans and three fault injections were run again as a control: fourteen of nineteen repeat the old result lines, the others differ only in configuration. A record named *-modification-twice runs with -Dadapt.mergeModification=false, as the records it replaces did before that switch existed. The index-rebuild arm of SC9, which the paper cites, is checked on the four databases where it had no pattern check. 31 of 31 of its records finished.
* `initial-cycle-2026-11-02` (2026-11-02, commit d6197a7ed (b54bfc310 for the two pattern checks)): The floor selection records the cycle of the initial mine from d6197a7ed on; the checks of the rule were run again. 6 of 6 of its records finished.

| check | record read | commit | ended | exit code |
|---|---:|---:|---:|---:|
| acceptance | measured-code-2026-11-11/acceptance-modification-twice | f30fe6b41 | 2026-11-11 | 0 |
| adversarial | adversarial | e84b5a89a | 2026-10-17 | 0 |
| adversarial-tafeng | adversarial-tafeng | e84b5a89a | 2026-10-17 | 0 |
| adversarial-tafeng-probe0.7 | adversarial-tafeng-probe0.7 | e84b5a89a | 2026-10-17 | 0 |
| break-even-adversarial | break-even-adversarial | 7f226af39 | 2026-10-27 | 0 |
| break-even-fuzz | measured-code-2026-11-11/break-even-fuzz | f30fe6b41 | 2026-11-11 | 0 |
| break-even-nodes-adversarial | initial-cycle-2026-11-02/break-even-nodes-adversarial | d6197a7ed | 2026-11-02 | 0 |
| break-even-nodes-fuzz | initial-cycle-2026-11-02/break-even-nodes-fuzz | d6197a7ed | 2026-11-02 | 0 |
| break-even-nodes-random | initial-cycle-2026-11-02/break-even-nodes-random | d6197a7ed | 2026-11-02 | 0 |
| break-even-nodes-switching-adversarial | measured-code-2026-11-11/break-even-nodes-switching-adversarial | 73487f9f8 | 2026-11-11 | 0 |
| break-even-nodes-switching-random | measured-code-2026-11-11/break-even-nodes-switching-random | 73487f9f8 | 2026-11-11 | 0 |
| break-even-random | break-even-random | c0180ac5c | 2026-10-27 | 0 |
| break-even-switching-adversarial | break-even-switching-adversarial | 8e44d99da | 2026-10-27 | 0 |
| break-even-switching-random | break-even-switching-random | 7f226af39 | 2026-10-27 | 0 |
| check-break-even-mixed | check-break-even-mixed | 7f226af39 | 2026-10-27 | 0 |
| check-break-even-nodes-mixed | check-break-even-nodes-mixed | 9a3843be4 | 2026-10-30 | 0 |
| check-break-even-nodes-sc2 | check-break-even-nodes-sc2 | 9a3843be4 | 2026-10-30 | 0 |
| check-break-even-sc2 | check-break-even-sc2 | 7f226af39 | 2026-10-27 | 0 |
| fault-injection | measured-code-2026-11-11/fault-injection | f30fe6b41 | 2026-11-11 | 0 |
| fault-injection-floor-selection | measured-code-2026-11-11/fault-injection-floor-selection | f30fe6b41 | 2026-11-11 | 0 |
| fault-injection-ips | measured-code-2026-11-11/fault-injection-ips | f30fe6b41 | 2026-11-11 | 0 |
| fault-injection-real | fault-injection-real | e84b5a89a | 2026-10-18 | 0 |
| floor-selection-acceptance | measured-code-2026-11-11/floor-selection-acceptance | f30fe6b41 | 2026-11-11 | 0 |
| floor-selection-adversarial | floor-selection-adversarial | e84b5a89a | 2026-10-17 | 0 |
| floor-selection-fuzz | measured-code-2026-11-11/floor-selection-fuzz | f30fe6b41 | 2026-11-11 | 0 |
| floor-selection-random | floor-selection-random | e84b5a89a | 2026-10-17 | 0 |
| floor-selection-random-floors | floor-selection-random-floors | e84b5a89a | 2026-10-17 | 0 |
| floor-selection-switching-adversarial | floor-selection-switching-adversarial | e84b5a89a | 2026-10-17 | 0 |
| floor-selection-switching-random | floor-selection-switching-random | e84b5a89a | 2026-10-17 | 0 |
| fuzz | measured-code-2026-11-11/fuzz-modification-twice | f30fe6b41 | 2026-11-11 | 0 |
| husps-dunnhumby-mixed-all-arms | husps-dunnhumby-mixed-all-arms | b54bfc310 | 2026-11-02 | 0 |
| husps-long-sc10 | husps-long-sc10 | 2ecbd83be | 2026-10-31 | 0 |
| husps-long-sc10-dunnhumby | measured-code-2026-11-11/husps-long-sc10-dunnhumby | 73487f9f8 | 2026-11-11 | 0 |
| husps-long-sc10-method | measured-code-2026-11-11/husps-long-sc10-method | 84e0bbbe5 | 2026-11-11 | 0 |
| husps-long-sc10h | husps-long-sc10h | 2ecbd83be | 2026-10-31 | 1 |
| husps-long-sc10h-dunnhumby | measured-code-2026-11-11/husps-long-sc10h-dunnhumby | 73487f9f8 | 2026-11-11 | 0 |
| husps-long-sc10h-method | measured-code-2026-11-11/husps-long-sc10h-method | 84e0bbbe5 | 2026-11-11 | 0 |
| husps-long-sc10h-sequences-c8t1s5i8n5k | husps-long-sc10h-sequences-c8t1s5i8n5k | 5a2d99ce1 | 2026-10-31 | 0 |
| husps-long-sc10h-sequences-leviathan | husps-long-sc10h-sequences-leviathan | 5a2d99ce1 | 2026-10-31 | 0 |
| husps-long-sc10h-sequences-tafeng | husps-long-sc10h-sequences-tafeng | 5a2d99ce1 | 2026-10-31 | 0 |
| husps-long-sc8 | husps-long-sc8 | 2ecbd83be | 2026-10-31 | 0 |
| husps-long-sc8-dunnhumby | husps-long-sc8-dunnhumby | d3358b01b | 2026-11-02 | 0 |
| husps-long-sc8-method | measured-code-2026-11-11/husps-long-sc8-method | 84e0bbbe5 | 2026-11-11 | 0 |
| husps-long-sc8p | husps-long-sc8p | 2ecbd83be | 2026-10-31 | 0 |
| husps-long-sc8p-dunnhumby | measured-code-2026-11-11/husps-long-sc8p-dunnhumby | 73487f9f8 | 2026-11-11 | 0 |
| husps-long-sc8p-method | measured-code-2026-11-11/husps-long-sc8p-method | 84e0bbbe5 | 2026-11-11 | 0 |
| husps-long-sc9 | husps-long-sc9 | 2ecbd83be | 2026-10-31 | 0 |
| husps-long-sc9-dunnhumby | husps-long-sc9-dunnhumby | 4aa1ce88c | 2026-11-02 | 0 |
| husps-long-sc9-index-rebuild | measured-code-2026-11-11/husps-long-sc9-index-rebuild | 73487f9f8 | 2026-11-11 | 0 |
| husps-long-sc9-method | measured-code-2026-11-11/husps-long-sc9-method | 84e0bbbe5 | 2026-11-11 | 0 |
| husps-mixed | husps-mixed | e84b5a89a | 2026-10-18 | 0 |
| husps-mixed-break-even | husps-mixed-break-even | 7f226af39 | 2026-10-27 | 0 |
| husps-mixed-break-even-nodes | initial-cycle-2026-11-02/husps-mixed-break-even-nodes | b54bfc310 | 2026-11-02 | 0 |
| husps-mixed-remine | husps-mixed-remine | e66ccb6f0 | 2026-10-26 | 0 |
| husps-sc2 | husps-sc2 | e84b5a89a | 2026-10-18 | 0 |
| husps-sc2-break-even | husps-sc2-break-even | 7f226af39 | 2026-10-27 | 0 |
| husps-sc2-break-even-nodes | initial-cycle-2026-11-02/husps-sc2-break-even-nodes | b54bfc310 | 2026-11-02 | 0 |
| husps-sc2-remine | husps-sc2-remine | e66ccb6f0 | 2026-10-26 | 0 |
| ips-random | measured-code-2026-11-11/ips-random | f30fe6b41 | 2026-11-11 | 0 |
| ips-real | ips-real | 41f3af392 | 2026-10-12 | 0 |
| merge-acceptance | initial-cycle-2026-11-02/acceptance | d6197a7ed | 2026-11-02 | 0 |
| merge-adversarial | merge-adversarial | 7f226af39 | 2026-10-27 | 0 |
| merge-fuzz | measured-code-2026-11-11/merge-fuzz | f30fe6b41 | 2026-11-11 | 0 |
| merge-random | merge-random | 80ac64471 | 2026-10-27 | 0 |
| min-floor-acceptance | measured-code-2026-11-11/min-floor-acceptance | f30fe6b41 | 2026-11-11 | 0 |
| min-floor-adversarial | min-floor-adversarial | e84b5a89a | 2026-10-17 | 0 |
| min-floor-fuzz | measured-code-2026-11-11/min-floor-fuzz | f30fe6b41 | 2026-11-11 | 0 |
| min-floor-random | min-floor-random | e84b5a89a | 2026-10-17 | 0 |
| plain-floor-acceptance | measured-code-2026-11-11/plain-floor-acceptance | f30fe6b41 | 2026-11-11 | 0 |
| plain-floor-adversarial | plain-floor-adversarial | e84b5a89a | 2026-10-19 | 0 |
| plain-floor-fuzz | measured-code-2026-11-11/plain-floor-fuzz | f30fe6b41 | 2026-11-11 | 0 |
| plain-floor-random | plain-floor-random | e84b5a89a | 2026-10-19 | 0 |
| pre-tests-acceptance | measured-code-2026-11-11/pre-tests-acceptance | f30fe6b41 | 2026-11-11 | 0 |
| pre-tests-adversarial | pre-tests-adversarial | e84b5a89a | 2026-10-17 | 0 |
| pre-tests-fuzz | measured-code-2026-11-11/pre-tests-fuzz | f30fe6b41 | 2026-11-11 | 0 |
| pre-tests-random | pre-tests-random | e84b5a89a | 2026-10-17 | 0 |
| prehuspm-tracking-acceptance | measured-code-2026-11-11/prehuspm-tracking-acceptance | f30fe6b41 | 2026-11-11 | 0 |
| prehuspm-tracking-adversarial | prehuspm-tracking-adversarial | e84b5a89a | 2026-10-17 | 0 |
| prehuspm-tracking-equivalence | prehuspm-tracking-equivalence | e84b5a89a | 2026-10-17 | 0 |
| prehuspm-tracking-fuzz | measured-code-2026-11-11/prehuspm-tracking-fuzz | f30fe6b41 | 2026-11-11 | 0 |
| prehuspm-tracking-random | prehuspm-tracking-random | e84b5a89a | 2026-10-17 | 0 |
| random | random | e84b5a89a | 2026-10-17 | 0 |
| running-example | running-example | d15b36a24 | 2026-10-25 | 0 |
| running-example-break-even-nodes | measured-code-2026-11-11/running-example | 84e0bbbe5 | 2026-11-11 | 0 |
| static-joint-miner-uut | static-joint-miner-uut | 906222294 | 2026-10-16 | 0 |
| stream-plans | measured-code-2026-11-11/stream-plans | f30fe6b41 | 2026-11-11 | 0 |

## Commands

    # measured-code-2026-11-11/acceptance-modification-twice: exit 0, commit f30fe6b4, 2026-11-11T14:51:01
    java -Dadapt.mergeModification=false -cp build-vq2-light verify.RescanRuleAcceptanceTest
    # adversarial: exit 0, commit e84b5a89, 2026-10-17T15:07:10
    java -Xmx24g -Xss64m -cp build-v31 verify.DirectPathVerifier ALL --adversarial --ops 16 --out results/verify-joint/adversarial
    # adversarial-tafeng: exit 0, commit e84b5a89, 2026-10-17T15:12:41
    java -Xmx24g -Xss64m -cp build-v31 verify.DirectPathVerifier TAFENG --adversarial --ops 16 --out results/verify-joint/adversarial-tafeng
    # adversarial-tafeng-probe0.7: exit 0, commit e84b5a89, 2026-10-17T15:19:25
    java -Dverify.probeRl=0.7 -Xmx24g -Xss64m -cp build-v31 verify.DirectPathVerifier TAFENG --adversarial --ops 16 --out results/verify-joint/adversarial-tafeng-probe0.7
    # break-even-adversarial: exit 0, commit 7f226af3, 2026-10-27T14:52:00
    java -Dadapt.floorRule=breakEven -Xmx24g -Xss64m -cp build-be verify.DirectPathVerifier ALL --adversarial --ops 16 --out results/verify-joint/break-even-adversarial
    # measured-code-2026-11-11/break-even-fuzz: exit 0, commit f30fe6b4, 2026-11-11T15:06:16
    bash -c rc=0; for s in "2000 7" "5000 11" "5000 23" "5000 101"; do set -- $s; for r in joint; do java -Dadapt.floorRule=breakEven -cp build-vq2-light verify.MixedBudgetFuzz $r $1 $2  || rc=1; done; done; exit $rc
    # initial-cycle-2026-11-02/break-even-nodes-adversarial: exit 0, commit d6197a7e, 2026-11-02T08:46:55
    java -Dadapt.floorRule=breakEven -Dadapt.breakEvenCost=nodes -Xmx16g -Xss64m -cp build verify.DirectPathVerifier ALL --adversarial --ops 16 --out results/verify-joint/initial-cycle-2026-11-02/break-even-nodes-adversarial
    # initial-cycle-2026-11-02/break-even-nodes-fuzz: exit 0, commit d6197a7e, 2026-11-02T08:17:52
    bash -c rc=0; for s in '2000 7' '5000 11' '5000 23' '5000 101'; do set -- $s; java -Dadapt.floorRule=breakEven -Dadapt.breakEvenCost=nodes -cp build verify.MixedBudgetFuzz joint $1 $2 || rc=1; done; exit $rc
    # initial-cycle-2026-11-02/break-even-nodes-random: exit 0, commit d6197a7e, 2026-11-02T08:21:07
    java -Dadapt.floorRule=breakEven -Dadapt.breakEvenCost=nodes -Xmx16g -Xss64m -cp build verify.DirectPathVerifier ALL --ops 16 --out results/verify-joint/initial-cycle-2026-11-02/break-even-nodes-random
    # measured-code-2026-11-11/break-even-nodes-switching-adversarial: exit 0, commit 73487f9f, 2026-11-11T18:32:48
    java -Dadapt.floorRule=breakEven -Dadapt.breakEvenCost=nodes -Xmx40g -Xss64m -cp build-pc-break-even-nodes-switching-adversarial verify.DirectPathVerifier ALL --adversarial --ops 24 --share-alt 0.5 --out results/verify-joint/measured-code-2026-11-11/break-even-nodes-switching-adversarial
    # measured-code-2026-11-11/break-even-nodes-switching-random: exit 0, commit 73487f9f, 2026-11-11T20:14:22
    java -Dadapt.floorRule=breakEven -Dadapt.breakEvenCost=nodes -Xmx40g -Xss64m -cp build-pc-break-even-nodes-switching-random verify.DirectPathVerifier ALL --ops 24 --share-alt 0.5 --out results/verify-joint/measured-code-2026-11-11/break-even-nodes-switching-random
    # break-even-random: exit 0, commit c0180ac5, 2026-10-27T10:41:20
    java -Dadapt.floorRule=breakEven -Xmx24g -Xss64m -cp build-be verify.DirectPathVerifier ALL --ops 16 --out results/verify-joint/break-even-random
    # break-even-switching-adversarial: exit 0, commit 8e44d99d, 2026-10-27T22:11:36
    java -Dadapt.floorRule=breakEven -Xmx24g -Xss64m -cp build-be verify.DirectPathVerifier ALL --adversarial --ops 24 --share-alt 0.5 --out results/verify-joint/break-even-switching-adversarial
    # break-even-switching-random: exit 0, commit 7f226af3, 2026-10-27T11:18:00
    java -Dadapt.floorRule=breakEven -Xmx24g -Xss64m -cp build-be verify.DirectPathVerifier ALL --ops 24 --share-alt 0.5 --out results/verify-joint/break-even-switching-random
    # check-break-even-mixed: exit 0, commit 7f226af3, 2026-10-27T13:01:07
    ~/.venvs/pre-huspm-adaptive/bin/python analysis/check_break_even.py --results results/verify-joint/husps-mixed-break-even --plans results-probe/floor-policy/mixed-plans/mixed_plans.tsv
    # check-break-even-nodes-mixed: exit 0, commit 9a3843be, 2026-10-30T09:45:26
    ~/.venvs/pre-huspm-adaptive/bin/python analysis/check_break_even.py --results results/verify-joint/husps-mixed-break-even-nodes --plans results-probe/floor-policy/mixed-plans/mixed_plans.tsv
    # check-break-even-nodes-sc2: exit 0, commit 9a3843be, 2026-10-30T08:55:17
    ~/.venvs/pre-huspm-adaptive/bin/python analysis/check_break_even.py --results results/verify-joint/husps-sc2-break-even-nodes
    # check-break-even-sc2: exit 0, commit 7f226af3, 2026-10-27T16:09:21
    ~/.venvs/pre-huspm-adaptive/bin/python analysis/check_break_even.py --results results/verify-joint/husps-sc2-break-even
    # measured-code-2026-11-11/fault-injection: exit 0, commit f30fe6b4, 2026-11-11T15:09:44
    python3 analysis/fault_injection.py --trials 2000 --seed 7
    # measured-code-2026-11-11/fault-injection-floor-selection: exit 0, commit f30fe6b4, 2026-11-11T15:10:43
    python3 analysis/fault_injection.py --floor-selection --trials 2000 --seed 7
    # measured-code-2026-11-11/fault-injection-ips: exit 0, commit f30fe6b4, 2026-11-11T15:23:39
    python3 analysis/fault_injection.py --item-removal --trials 2000 --seed 1
    # fault-injection-real: exit 0, commit e84b5a89, 2026-10-18T00:07:46
    python3 analysis/fault_injection.py --real SIGN,LEVIATHAN,BIBLE,C8T1S5I8N5K,TAFENG
    # measured-code-2026-11-11/floor-selection-acceptance: exit 0, commit f30fe6b4, 2026-11-11T14:51:05
    java -cp build-vq2-light verify.RescanRuleAcceptanceTest
    # floor-selection-adversarial: exit 0, commit e84b5a89, 2026-10-17T17:05:33
    java -Dadapt.floorWindow=1 -Xmx24g -Xss64m -cp build-v31 verify.DirectPathVerifier ALL --adversarial --ops 16 --out results/verify-joint/floor-selection-adversarial
    # measured-code-2026-11-11/floor-selection-fuzz: exit 0, commit f30fe6b4, 2026-11-11T14:57:46
    bash -c rc=0; for w in 1 2 5; do for s in "2000 7" "5000 11" "5000 23" "5000 101"; do set -- $s; java -Dadapt.floorWindow=$w -cp build-vq2-light verify.MixedBudgetFuzz joint $1 $2 || rc=1; done; done; exit $rc
    # floor-selection-random: exit 0, commit e84b5a89, 2026-10-17T16:11:51
    java -Dadapt.floorWindow=1 -Xmx24g -Xss64m -cp build-v31 verify.DirectPathVerifier ALL --ops 16 --out results/verify-joint/floor-selection-random
    # floor-selection-random-floors: exit 0, commit e84b5a89, 2026-10-17T17:12:00
    java -Dadapt.floorWindow=1 -Xmx24g -Xss64m -cp build-v31 verify.DirectPathVerifier ALL --ops 16 --out results/verify-joint/floor-selection-random-floors
    # floor-selection-switching-adversarial: exit 0, commit e84b5a89, 2026-10-17T21:32:26
    java -Dadapt.floorWindow=1 -Xmx24g -Xss64m -cp build-v31 verify.DirectPathVerifier ALL --adversarial --ops 24 --share-alt 0.5 --out results/verify-joint/floor-selection-switching-adversarial
    # floor-selection-switching-random: exit 0, commit e84b5a89, 2026-10-17T17:44:06
    java -Dadapt.floorWindow=1 -Xmx24g -Xss64m -cp build-v31 verify.DirectPathVerifier ALL --ops 24 --share-alt 0.5 --out results/verify-joint/floor-selection-switching-random
    # measured-code-2026-11-11/fuzz-modification-twice: exit 0, commit f30fe6b4, 2026-11-11T14:49:16
    bash -c rc=0; for s in "2000 7" "5000 11" "5000 23" "5000 101"; do set -- $s; for r in joint iaumax; do java -Dadapt.mergeModification=false -cp build-vq2-light verify.MixedBudgetFuzz $r $1 $2 || { [ $r = joint ] && rc=1; }; done; done; java -Dadapt.mergeModification=false -cp build-vq2-light verify.MixedBudgetProbe joint || rc=1; java -Dadapt.mergeModification=false -cp build-vq2-light verify.MixedBudgetProbe iaumax; exit $rc
    # husps-dunnhumby-mixed-all-arms: exit 0, commit b54bfc31, 2026-11-02T10:44:48
    java -Xmx16g -Xss64m -cp build-reg -Dverify.husps=true -Dseeds=5 -Drepeats=1 -Ddataset.only=DUNNHUMBY -Dresults.dir=results/verify-joint/husps-dunnhumby-mixed-all-arms/ AutomatedExperimentRunner SC6 SC6D SC6B
    # husps-long-sc10: exit 0, commit 2ecbd83b, 2026-10-31T23:06:48
    java -Xmx5g -Xss64m -cp build-long-sc10 -Dverify.husps=true -Drepeats=1 -Ddataset.only=SIGN,LEVIATHAN,C8T1S5I8N5K,TAFENG -Dalgo.only=Pre-HUSPM-Joint-fixed-threshold,Remine-from-scratch,Pre-HUSPM-Joint,Remine-own-UUT,Pre-HUSPM-Joint-w5 -Dresults.dir=results/verify-joint/husps-long-sc10/ AutomatedExperimentRunner SC10
    # measured-code-2026-11-11/husps-long-sc10-dunnhumby: exit 0, commit 73487f9f, 2026-11-11T19:48:37
    java -Xmx6g -Xss64m -cp build-pc-husps-long-sc10-dunnhumby -Dverify.husps=true -Drepeats=1 -Ddataset.only=DUNNHUMBY -Dalgo.only=Pre-HUSPM-Joint-fixed-threshold,Pre-HUSPM-Joint,Pre-HUSPM-Joint-w5,Remine-from-scratch,Remine-own-UUT -Dresults.dir=results/verify-joint/measured-code-2026-11-11/husps-long-sc10-dunnhumby/ AutomatedExperimentRunner SC10
    # measured-code-2026-11-11/husps-long-sc10-method: exit 0, commit 84e0bbbe, 2026-11-11T17:53:59
    java -Xmx5g -Xss64m -cp build-vq3-sc10 -Dverify.husps=true -Drepeats=1 -Ddataset.only=SIGN,LEVIATHAN,C8T1S5I8N5K,TAFENG -Dalgo.only=Pre-HUSPM-Joint-fixed-threshold,Pre-HUSPM-Joint -Dresults.dir=results/verify-joint/measured-code-2026-11-11/husps-long-sc10-method/ AutomatedExperimentRunner SC10
    # husps-long-sc10h: exit 1, commit 2ecbd83b, 2026-10-31T21:24:20
    java -Xmx5g -Xss64m -cp build-long-sc10h -Dverify.husps=true -Drepeats=1 -Ddataset.only=SIGN,LEVIATHAN,C8T1S5I8N5K,TAFENG -Dalgo.only=Pre-HUSPM-Joint-fixed-threshold,Remine-from-scratch,Pre-HUSPM-Joint,Remine-own-UUT,Pre-HUSPM-Joint-w5 -Dresults.dir=results/verify-joint/husps-long-sc10h/ AutomatedExperimentRunner SC10H
    # measured-code-2026-11-11/husps-long-sc10h-dunnhumby: exit 0, commit 73487f9f, 2026-11-11T20:05:24
    java -Xmx6g -Xss64m -cp build-pc-husps-long-sc10h-dunnhumby -Dverify.husps=true -Drepeats=1 -Ddataset.only=DUNNHUMBY -Dalgo.only=Pre-HUSPM-Joint-fixed-threshold,Pre-HUSPM-Joint,Pre-HUSPM-Joint-w5,Remine-from-scratch,Remine-own-UUT -Dresults.dir=results/verify-joint/measured-code-2026-11-11/husps-long-sc10h-dunnhumby/ AutomatedExperimentRunner SC10H
    # measured-code-2026-11-11/husps-long-sc10h-method: exit 0, commit 84e0bbbe, 2026-11-11T17:14:28
    java -Xmx5g -Xss64m -cp build-vq3-sc10h -Dverify.husps=true -Drepeats=1 -Ddataset.only=SIGN,LEVIATHAN,C8T1S5I8N5K,TAFENG -Dalgo.only=Pre-HUSPM-Joint-fixed-threshold,Pre-HUSPM-Joint -Dresults.dir=results/verify-joint/measured-code-2026-11-11/husps-long-sc10h-method/ AutomatedExperimentRunner SC10H
    # husps-long-sc10h-sequences-c8t1s5i8n5k: exit 0, commit 5a2d99ce, 2026-10-31T23:40:28
    java -Xmx8g -Xss64m -cp build-h-c8t1s5i8n5k -Dverify.husps=true -Drepeats=1 -Ddataset.only=C8T1S5I8N5K -Dalgo.only=Pre-HUSPM-Joint-fixed-threshold,Remine-from-scratch,Pre-HUSPM-Joint,Remine-own-UUT,Pre-HUSPM-Joint-w5 -Dresults.dir=results/verify-joint/husps-long-sc10h-sequences-c8t1s5i8n5k/ AutomatedExperimentRunner SC10H
    # husps-long-sc10h-sequences-leviathan: exit 0, commit 5a2d99ce, 2026-10-31T23:44:53
    java -Xmx8g -Xss64m -cp build-h-leviathan -Dverify.husps=true -Drepeats=1 -Ddataset.only=LEVIATHAN -Dalgo.only=Pre-HUSPM-Joint-fixed-threshold,Remine-from-scratch,Pre-HUSPM-Joint,Remine-own-UUT,Pre-HUSPM-Joint-w5 -Dresults.dir=results/verify-joint/husps-long-sc10h-sequences-leviathan/ AutomatedExperimentRunner SC10H
    # husps-long-sc10h-sequences-tafeng: exit 0, commit 5a2d99ce, 2026-10-31T23:27:40
    java -Xmx8g -Xss64m -cp build-h-tafeng -Dverify.husps=true -Drepeats=1 -Ddataset.only=TAFENG -Dalgo.only=Pre-HUSPM-Joint-fixed-threshold,Remine-from-scratch,Pre-HUSPM-Joint,Remine-own-UUT,Pre-HUSPM-Joint-w5 -Dresults.dir=results/verify-joint/husps-long-sc10h-sequences-tafeng/ AutomatedExperimentRunner SC10H
    # husps-long-sc8: exit 0, commit 2ecbd83b, 2026-10-31T23:09:14
    java -Xmx5g -Xss64m -cp build-long-sc8 -Dverify.husps=true -Drepeats=1 -Ddataset.only=SIGN,LEVIATHAN,C8T1S5I8N5K,TAFENG -Dalgo.only=Pre-HUSPM-Joint-fixed-threshold,Remine-from-scratch,Pre-HUSPM-Joint,Remine-own-UUT,Pre-HUSPM-Joint-w5 -Dresults.dir=results/verify-joint/husps-long-sc8/ AutomatedExperimentRunner SC8
    # husps-long-sc8-dunnhumby: exit 0, commit d3358b01, 2026-11-02T16:57:22
    java -Xmx20g -Xss64m -cp build-reg -Dverify.husps=true -Drepeats=1 -Ddataset.only=DUNNHUMBY -Dresults.dir=results/verify-joint/husps-long-sc8-dunnhumby/ AutomatedExperimentRunner SC8
    # measured-code-2026-11-11/husps-long-sc8-method: exit 0, commit 84e0bbbe, 2026-11-11T18:58:54
    java -Xmx5g -Xss64m -cp build-vq3-sc8 -Dverify.husps=true -Drepeats=1 -Ddataset.only=SIGN,LEVIATHAN,C8T1S5I8N5K,TAFENG -Dalgo.only=Pre-HUSPM-Joint-fixed-threshold,Pre-HUSPM-Joint -Dresults.dir=results/verify-joint/measured-code-2026-11-11/husps-long-sc8-method/ AutomatedExperimentRunner SC8
    # husps-long-sc8p: exit 0, commit 2ecbd83b, 2026-10-31T22:46:40
    java -Xmx5g -Xss64m -cp build-long-sc8p -Dverify.husps=true -Drepeats=1 -Ddataset.only=SIGN,LEVIATHAN,C8T1S5I8N5K,TAFENG -Dalgo.only=Pre-HUSPM-Joint-fixed-threshold,Remine-from-scratch,Pre-HUSPM-Joint,Remine-own-UUT,Pre-HUSPM-Joint-w5 -Dresults.dir=results/verify-joint/husps-long-sc8p/ AutomatedExperimentRunner SC8P
    # measured-code-2026-11-11/husps-long-sc8p-dunnhumby: exit 0, commit 73487f9f, 2026-11-11T19:14:30
    java -Xmx6g -Xss64m -cp build-pc-husps-long-sc8p-dunnhumby -Dverify.husps=true -Drepeats=1 -Ddataset.only=DUNNHUMBY -Dalgo.only=Pre-HUSPM-Joint-fixed-threshold,Pre-HUSPM-Joint,Pre-HUSPM-Joint-w5,Remine-from-scratch,Remine-own-UUT -Dresults.dir=results/verify-joint/measured-code-2026-11-11/husps-long-sc8p-dunnhumby/ AutomatedExperimentRunner SC8P
    # measured-code-2026-11-11/husps-long-sc8p-method: exit 0, commit 84e0bbbe, 2026-11-11T17:42:38
    java -Xmx5g -Xss64m -cp build-vq3-sc8p -Dverify.husps=true -Drepeats=1 -Ddataset.only=SIGN,LEVIATHAN,C8T1S5I8N5K,TAFENG -Dalgo.only=Pre-HUSPM-Joint-fixed-threshold,Pre-HUSPM-Joint -Dresults.dir=results/verify-joint/measured-code-2026-11-11/husps-long-sc8p-method/ AutomatedExperimentRunner SC8P
    # husps-long-sc9: exit 0, commit 2ecbd83b, 2026-10-31T23:34:55
    java -Xmx5g -Xss64m -cp build-long-sc9 -Dverify.husps=true -Drepeats=1 -Ddataset.only=SIGN,LEVIATHAN,C8T1S5I8N5K,TAFENG -Dalgo.only=Pre-HUSPM-Joint-fixed-threshold,Remine-from-scratch,Pre-HUSPM-Joint,Remine-own-UUT,Pre-HUSPM-Joint-w5,Pre-HUSPM-Joint-index-rebuild -Dresults.dir=results/verify-joint/husps-long-sc9/ AutomatedExperimentRunner SC9
    # husps-long-sc9-dunnhumby: exit 0, commit 4aa1ce88, 2026-11-02T20:26:24
    java -Xmx20g -Xss64m -cp build-reg -Dverify.husps=true -Drepeats=1 -Ddataset.only=DUNNHUMBY -Dresults.dir=results/verify-joint/husps-long-sc9-dunnhumby/ AutomatedExperimentRunner SC9
    # measured-code-2026-11-11/husps-long-sc9-index-rebuild: exit 0, commit 73487f9f, 2026-11-11T19:08:09
    java -Xmx5g -Xss64m -cp build-vq4 -Dverify.husps=true -Drepeats=1 -Ddataset.only=SIGN,LEVIATHAN,C8T1S5I8N5K,TAFENG -Dalgo.only=Pre-HUSPM-Joint-fixed-threshold,Pre-HUSPM-Joint-index-rebuild -Dresults.dir=results/verify-joint/measured-code-2026-11-11/husps-long-sc9-index-rebuild/ AutomatedExperimentRunner SC9
    # measured-code-2026-11-11/husps-long-sc9-method: exit 0, commit 84e0bbbe, 2026-11-11T17:43:16
    java -Xmx5g -Xss64m -cp build-vq3-sc9 -Dverify.husps=true -Drepeats=1 -Ddataset.only=SIGN,LEVIATHAN,C8T1S5I8N5K,TAFENG -Dalgo.only=Pre-HUSPM-Joint-fixed-threshold,Pre-HUSPM-Joint -Dresults.dir=results/verify-joint/measured-code-2026-11-11/husps-long-sc9-method/ AutomatedExperimentRunner SC9
    # husps-mixed: exit 0, commit e84b5a89, 2026-10-18T01:32:25
    java -Xmx16g -Xss64m -cp build-v31 -Dverify.husps=true -Dseeds=5 -Drepeats=1 -Ddataset.only=SIGN,LEVIATHAN,C8T1S5I8N5K,TAFENG -Dalgo.only=Adaptive-Full,Remine-from-scratch,Remine-HUSP-ULL,Remine-HUSP-SP,Adaptive-FloorSelect,Remine-own-UUT,Joint-rule+plain-floor,Joint-rule+min-floor -Dresults.dir=results/verify-joint/husps-mixed/ AutomatedExperimentRunner SC6 SC6D SC6B
    # husps-mixed-break-even: exit 0, commit 7f226af3, 2026-10-27T13:00:07
    java -Xmx16g -Xss64m -cp build-be -Dverify.husps=true -Dseeds=5 -Drepeats=1 -Ddataset.only=SIGN,LEVIATHAN,C8T1S5I8N5K,TAFENG -Dalgo.only=Pre-HUSPM-Joint-fixed-threshold,Pre-HUSPM-Joint,Pre-HUSPM-Joint-w5,Joint-rule+plain-floor,Joint-rule+min-floor -Dresults.dir=results/verify-joint/husps-mixed-break-even/ AutomatedExperimentRunner SC6 SC6D SC6B
    # initial-cycle-2026-11-02/husps-mixed-break-even-nodes: exit 0, commit b54bfc31, 2026-11-02T09:39:51
    java -Xmx16g -Xss64m -cp build-reg -Dverify.husps=true -Dseeds=5 -Drepeats=1 -Ddataset.only=SIGN,LEVIATHAN,C8T1S5I8N5K,TAFENG,DUNNHUMBY -Dalgo.only=Pre-HUSPM-Joint-fixed-threshold,Pre-HUSPM-Joint -Dresults.dir=results/verify-joint/initial-cycle-2026-11-02/husps-mixed-break-even-nodes/ AutomatedExperimentRunner SC6 SC6D SC6B
    # husps-mixed-remine: exit 0, commit e66ccb6f, 2026-10-26T07:22:49
    java -Xmx16g -Xss64m -cp build-remine -Dverify.husps=true -Dseeds=5 -Drepeats=1 -Ddataset.only=SIGN,LEVIATHAN,C8T1S5I8N5K,TAFENG -Dalgo.only=Pre-HUSPM-Joint-fixed-threshold,Remine-from-scratch,Remine-own-UUT -Dresults.dir=results/verify-joint/husps-mixed-remine/ AutomatedExperimentRunner SC6 SC6D SC6B
    # husps-sc2: exit 0, commit e84b5a89, 2026-10-18T06:32:48
    bash -c rc=0; for d in SIGN TAFENG C8T1S5I8N5K LEVIATHAN BIBLE KOSARAK FIFA; do java -Xmx20g -Xss64m -cp build-v31 -Dverify.husps=true -Drepeats=1 -Ddataset.only=$d -Dalgo.only=Adaptive-Full,Remine-HUSP-ULL,Remine-HUSP-SP,Remine-own-UUT,Adaptive-FloorSelect,Joint-miner+SWUmax,Joint-miner+TSU,Joint-rule+PreHUSPM-tracking,Joint-miner+SWUmax+PreHUSPM-tracking,Joint-rule+plain-floor,Joint-rule+min-floor -Dresults.dir=results/verify-joint/husps-sc2/ AutomatedExperimentRunner SC2 || rc=1; done; exit $rc
    # husps-sc2-break-even: exit 0, commit 7f226af3, 2026-10-27T16:09:08
    bash -c rc=0; for d in SIGN TAFENG C8T1S5I8N5K LEVIATHAN BIBLE KOSARAK FIFA; do java -Xmx20g -Xss64m -cp build-be -Dverify.husps=true -Drepeats=1 -Ddataset.only=$d -Dalgo.only=Pre-HUSPM-Joint-fixed-threshold,Pre-HUSPM-Joint,Pre-HUSPM-Joint-w5 -Dresults.dir=results/verify-joint/husps-sc2-break-even/ AutomatedExperimentRunner SC2 || rc=1; done; exit $rc
    # initial-cycle-2026-11-02/husps-sc2-break-even-nodes: exit 0, commit b54bfc31, 2026-11-02T11:27:13
    bash -c rc=0; for d in DUNNHUMBY SIGN TAFENG C8T1S5I8N5K LEVIATHAN BIBLE KOSARAK FIFA; do java -Xmx20g -Xss64m -cp build-reg -Dverify.husps=true -Drepeats=1 -Ddataset.only=$d -Dalgo.only=Pre-HUSPM-Joint-fixed-threshold,Pre-HUSPM-Joint -Dresults.dir=results/verify-joint/initial-cycle-2026-11-02/husps-sc2-break-even-nodes/ AutomatedExperimentRunner SC2 || rc=1; done; exit $rc
    # husps-sc2-remine: exit 0, commit e66ccb6f, 2026-10-26T09:49:13
    bash -c rc=0; for d in SIGN TAFENG C8T1S5I8N5K LEVIATHAN BIBLE KOSARAK FIFA; do java -Xmx20g -Xss64m -cp build-remine -Dverify.husps=true -Drepeats=1 -Ddataset.only=$d -Dalgo.only=Pre-HUSPM-Joint-fixed-threshold,Remine-from-scratch,Remine-own-UUT -Dresults.dir=results/verify-joint/husps-sc2-remine/ AutomatedExperimentRunner SC2 || rc=1; done; exit $rc
    # measured-code-2026-11-11/ips-random: exit 0, commit f30fe6b4, 2026-11-11T15:07:44
    java -Xmx8g -Xss64m -cp build-vq2-light verify.ItemRemovalCheck random 2000 1
    # ips-real: exit 0, commit 41f3af39, 2026-10-12T15:04:42
    bash -c rc=0; for d in SIGN LEVIATHAN FIFA BIBLE BMS1 KOSARAK C8T1S5I8N5K TAFENG; do java -Xmx24g -Xss64m -cp build-vj verify.ItemRemovalCheck real $d init floor || rc=1; done; for d in SIGN LEVIATHAN FIFA BIBLE KOSARAK C8T1S5I8N5K TAFENG; do java -Xmx24g -Xss64m -cp build-vj verify.ItemRemovalCheck real $d whole floor || rc=1; done; exit $rc
    # initial-cycle-2026-11-02/acceptance: exit 0, commit d6197a7e, 2026-11-02T08:16:49
    java -cp build verify.RescanRuleAcceptanceTest
    # merge-adversarial: exit 0, commit 7f226af3, 2026-10-27T13:53:24
    java -Xmx24g -Xss64m -cp build-be verify.DirectPathVerifier ALL --adversarial --ops 16 --out results/verify-joint/merge-adversarial
    # measured-code-2026-11-11/merge-fuzz: exit 0, commit f30fe6b4, 2026-11-11T14:50:58
    bash -c rc=0; for s in "2000 7" "5000 11" "5000 23" "5000 101"; do set -- $s; for r in joint; do java  -cp build-vq2-light verify.MixedBudgetFuzz $r $1 $2  || rc=1; done; done; exit $rc
    # merge-random: exit 0, commit 80ac6447, 2026-10-27T10:37:37
    java -Xmx24g -Xss64m -cp build-be verify.DirectPathVerifier ALL --ops 16 --out results/verify-joint/merge-random
    # measured-code-2026-11-11/min-floor-acceptance: exit 0, commit f30fe6b4, 2026-11-11T14:51:18
    java -Dadapt.floor=min -cp build-vq2-light verify.RescanRuleAcceptanceTest
    # min-floor-adversarial: exit 0, commit e84b5a89, 2026-10-17T16:05:48
    java -Dadapt.floor=min -Xmx24g -Xss64m -cp build-v31 verify.DirectPathVerifier ALL --adversarial --ops 16 --out results/verify-joint/min-floor-adversarial
    # measured-code-2026-11-11/min-floor-fuzz: exit 0, commit f30fe6b4, 2026-11-11T14:52:50
    bash -c rc=0; for s in "2000 7" "5000 11" "5000 23" "5000 101"; do set -- $s; for r in joint; do java -Dadapt.floor=min -cp build-vq2-light verify.MixedBudgetFuzz $r $1 $2  || rc=1; done; done; exit $rc
    # min-floor-random: exit 0, commit e84b5a89, 2026-10-17T15:25:18
    java -Dadapt.floor=min -Xmx24g -Xss64m -cp build-v31 verify.DirectPathVerifier ALL --ops 16 --out results/verify-joint/min-floor-random
    # measured-code-2026-11-11/plain-floor-acceptance: exit 0, commit f30fe6b4, 2026-11-11T14:51:22
    java -Dadapt.floor=plain -cp build-vq2-light verify.RescanRuleAcceptanceTest
    # plain-floor-adversarial: exit 0, commit e84b5a89, 2026-10-19T16:56:01
    java -Dadapt.floor=plain -Xmx24g -Xss64m -cp build-v31 verify.DirectPathVerifier ALL --adversarial --ops 16 --out results/verify-joint/plain-floor-adversarial
    # measured-code-2026-11-11/plain-floor-fuzz: exit 0, commit f30fe6b4, 2026-11-11T14:54:06
    bash -c rc=0; for s in "2000 7" "5000 11" "5000 23" "5000 101"; do set -- $s; for r in joint; do java -Dadapt.floor=plain -cp build-vq2-light verify.MixedBudgetFuzz $r $1 $2  || rc=1; done; done; exit $rc
    # plain-floor-random: exit 0, commit e84b5a89, 2026-10-19T15:51:39
    java -Dadapt.floor=plain -Xmx24g -Xss64m -cp build-v31 verify.DirectPathVerifier ALL --ops 16 --out results/verify-joint/plain-floor-random
    # measured-code-2026-11-11/pre-tests-acceptance: exit 0, commit f30fe6b4, 2026-11-11T14:51:09
    java -cp build-vq2-light verify.RescanRuleAcceptanceTest
    # pre-tests-adversarial: exit 0, commit e84b5a89, 2026-10-17T22:59:08
    bash -c rc=0; for r in pre_swumax pre_tsu; do java -Xmx24g -Xss64m -cp build-v31 verify.DirectPathVerifier ALL --adversarial --ops 16 --rule $r --insert-only --out results/verify-joint/pre-tests-adversarial || rc=1; done; exit $rc
    # measured-code-2026-11-11/pre-tests-fuzz: exit 0, commit f30fe6b4, 2026-11-11T15:01:50
    bash -c rc=0; for s in "2000 7" "5000 11" "5000 23" "5000 101"; do set -- $s; for r in pre_swumax pre_tsu joint; do java  -cp build-vq2-light verify.MixedBudgetFuzz $r $1 $2 insert-only || rc=1; done; done; exit $rc
    # pre-tests-random: exit 0, commit e84b5a89, 2026-10-17T21:40:54
    bash -c rc=0; for r in pre_swumax pre_tsu; do java -Xmx24g -Xss64m -cp build-v31 verify.DirectPathVerifier ALL --ops 16 --rule $r --insert-only --out results/verify-joint/pre-tests-random || rc=1; done; exit $rc
    # measured-code-2026-11-11/prehuspm-tracking-acceptance: exit 0, commit f30fe6b4, 2026-11-11T14:51:13
    java -cp build-vq2-light verify.RescanRuleAcceptanceTest
    # prehuspm-tracking-adversarial: exit 0, commit e84b5a89, 2026-10-17T23:52:45
    java -Dadapt.tracking=prehuspm -Xmx24g -Xss64m -cp build-v31 verify.DirectPathVerifier ALL --insert-only --adversarial --ops 16 --out results/verify-joint/prehuspm-tracking-adversarial
    # prehuspm-tracking-equivalence: exit 0, commit e84b5a89, 2026-10-17T14:29:53
    java -Xmx24g -Xss64m -cp build-v31 verify.PreHuspmTrackingCheck
    # measured-code-2026-11-11/prehuspm-tracking-fuzz: exit 0, commit f30fe6b4, 2026-11-11T15:04:52
    bash -c rc=0; for s in "2000 7" "5000 11" "5000 23" "5000 101"; do set -- $s; for r in joint pre_swumax; do java -Dadapt.tracking=prehuspm -cp build-vq2-light verify.MixedBudgetFuzz $r $1 $2 insert-only || rc=1; done; done; exit $rc
    # prehuspm-tracking-random: exit 0, commit e84b5a89, 2026-10-17T23:15:21
    java -Dadapt.tracking=prehuspm -Xmx24g -Xss64m -cp build-v31 verify.DirectPathVerifier ALL --insert-only --ops 16 --out results/verify-joint/prehuspm-tracking-random
    # random: exit 0, commit e84b5a89, 2026-10-17T14:33:05
    java -Xmx24g -Xss64m -cp build-v31 verify.DirectPathVerifier ALL --ops 16 --out results/verify-joint/random
    # running-example: exit 0, commit d15b36a2, 2026-10-25T13:06:18
    java -cp build-rex verify.RunningExampleCheck
    # measured-code-2026-11-11/running-example: exit 0, commit 84e0bbbe, 2026-11-11T15:42:05
    java -cp build-vq3-rex verify.RunningExampleCheck
    # static-joint-miner-uut: exit 0, commit 90622229, 2026-10-16T21:29:19
    java -Xmx24g -Xss64m -cp build-v30 -Drepeats=1 -Dalgo.only=HUSP-ULL,Joint-miner@UUT -Dresults.dir=results/verify-joint/static-joint-miner-uut/ AutomatedExperimentRunner SC1 SC3
    # measured-code-2026-11-11/stream-plans: exit 0, commit f30fe6b4, 2026-11-11T15:08:52
    java -Xmx8g -cp build-vq2-light verify.StreamPlansCheck
