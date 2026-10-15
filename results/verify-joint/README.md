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
| rescan rule acceptance (RescanRuleAcceptanceTest) | finished | 23 |  |  |  |  | 0 |  |
| item pruning against exhaustive enumeration (ItemRemovalCheck random) | finished | 1,444 | 1,951 |  |  |  | 0 |  |
| item pruning on and off on real databases, init (ItemRemovalCheck real) | finished | 8 |  |  |  |  | 0 |  |
| item pruning on and off on real databases, whole (ItemRemovalCheck real) | finished | 7 |  |  |  |  | 0 |  |
| fault injection, random streams and hand-built cases | finished | 2 |  |  |  |  |  | 2 |
| fault injection, adversarial runs on real databases | finished | 2 |  |  |  |  |  | 2 |
| fault injection into the item pruning | finished | 4 |  |  |  |  |  | 4 |
| direct path on real databases, adversarial operations | finished | 128 | 108 | 30 | 49 | 30 | 0 |  |
| direct path on real databases, adversarial-tafeng operations | finished | 16 | 0 | 4 | 8 | 4 | 0 |  |
| direct path on real databases, adversarial-tafeng-probe0.7 operations | finished | 16 | 12 | 4 | 6 | 4 | 0 |  |
| direct path on real databases, floor-selection-adversarial operations | finished | 128 | 108 | 30 | 49 | 30 | 0 |  |
| direct path on real databases, floor-selection-random operations | finished | 128 | 0 | 30 | 58 | 28 | 0 |  |
| direct path on real databases, peu-tracking-adversarial operations | finished | 128 | 110 | 0 | 108 | 0 | 0 |  |
| direct path on real databases, peu-tracking-random operations | finished | 128 | 0 | 0 | 117 | 0 | 0 |  |
| direct path on real databases, pre-tests-adversarial operations | finished | 256 | 224 | 0 | 210 | 0 | 0 |  |
| direct path on real databases, pre-tests-random operations | finished | 256 | 0 | 0 | 218 | 0 | 0 |  |
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

### peu-tracking-adversarial

| database | operations | with a target | insert direct | insert rescan | delete direct | delete rescan | modify direct | modify rescan | mismatches |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| SIGN | 16 | 16 | 13 | 3 | 0 | 0 | 0 | 0 | 0 |
| LEVIATHAN | 16 | 16 | 13 | 3 | 0 | 0 | 0 | 0 | 0 |
| BIBLE | 16 | 16 | 14 | 2 | 0 | 0 | 0 | 0 | 0 |
| BMS1 | 16 | 16 | 13 | 3 | 0 | 0 | 0 | 0 | 0 |
| FIFA | 16 | 16 | 14 | 2 | 0 | 0 | 0 | 0 | 0 |
| KOSARAK | 16 | 16 | 14 | 2 | 0 | 0 | 0 | 0 | 0 |
| C8T1S5I8N5K | 16 | 14 | 11 | 5 | 0 | 0 | 0 | 0 | 0 |
| TAFENG | 16 | 0 | 16 | 0 | 0 | 0 | 0 | 0 | 0 |

### peu-tracking-random

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
    # adversarial-tafeng: exit 0, commit f47b234c, 2026-10-12T13:10:29
    java -Xmx48g -Xss64m -cp build verify.DirectPathVerifier TAFENG --adversarial --ops 16 --out results/verify-joint/adversarial-tafeng
    # adversarial-tafeng-probe0.7: exit 0, commit 8c427953, 2026-10-12T14:07:38
    java -Dverify.probeRl=0.7 -Xmx24g -Xss64m -cp build-vj verify.DirectPathVerifier TAFENG --adversarial --ops 16 --out results/verify-joint/adversarial-tafeng-probe0.7
    # fault-injection: exit 0, commit b1ea30af, 2026-10-12T08:12:02
    python3 analysis/fault_injection.py --trials 2000 --seed 7
    # fault-injection-ips: exit 0, commit 41f3af39, 2026-10-12T15:08:58
    python3 analysis/fault_injection.py --item-removal --trials 2000 --seed 1
    # fault-injection-real: exit 0, commit e954773c, 2026-10-12T10:38:57
    python3 analysis/fault_injection.py --real SIGN,LEVIATHAN,BIBLE,C8T1S5I8N5K,TAFENG
    # floor-selection-acceptance: exit 0, commit 8dfc5516, 2026-10-15T07:17:13
    java -cp build-vj verify.RescanRuleAcceptanceTest
    # floor-selection-adversarial: exit 0, commit 8dfc5516, 2026-10-15T08:30:20
    java -Dadapt.floorWindow=1 -Xmx24g -Xss64m -cp build-vj verify.DirectPathVerifier ALL --adversarial --ops 16 --out results/verify-joint/floor-selection-adversarial
    # floor-selection-fuzz: exit 0, commit 8dfc5516, 2026-10-15T07:18:23
    bash -c rc=0; for w in 1 2 5; do for s in "2000 7" "5000 11" "5000 23" "5000 101"; do set -- $s; java -Dadapt.floorWindow=$w -cp build-vj verify.MixedBudgetFuzz joint $1 $2 || rc=1; done; done; exit $rc
    # floor-selection-random: exit 0, commit 8dfc5516, 2026-10-15T08:12:38
    java -Dadapt.floorWindow=1 -Xmx24g -Xss64m -cp build-vj verify.DirectPathVerifier ALL --ops 16 --out results/verify-joint/floor-selection-random
    # fuzz: exit 0, commit b1ea30af, 2026-10-12T08:11:48
    bash -c for s in "2000 7" "5000 11" "5000 23" "5000 101"; do set -- $s; for r in joint iaumax; do java -cp build-vj verify.MixedBudgetFuzz $r $1 $2; done; done; java -cp build-vj verify.MixedBudgetProbe joint; java -cp build-vj verify.MixedBudgetProbe iaumax; true
    # husps-floor-selection: exit 0, commit 8dfc5516, 2026-10-15T08:09:33
    bash -c rc=0; for d in SIGN TAFENG C8T1S5I8N5K LEVIATHAN BIBLE KOSARAK FIFA; do java -Xmx20g -Xss64m -cp build-vj -Dverify.husps=true -Drepeats=1 -Ddataset.only=$d -Dalgo.only=Adaptive-Full,Adaptive-FloorSelect -Dresults.dir=results/verify-joint/husps-floor-selection/ AutomatedExperimentRunner SC2 || rc=1; done; java -Xmx16g -Xss64m -cp build-vj -Dverify.husps=true -Dseeds=5 -Drepeats=1 -Ddataset.only=SIGN,LEVIATHAN,C8T1S5I8N5K -Dalgo.only=Adaptive-Full,Adaptive-FloorSelect -Dresults.dir=results/verify-joint/husps-floor-selection/ AutomatedExperimentRunner SC6 SC6D SC6B || rc=1; exit $rc
    # husps-mixed: exit 0, commit 35fe25c3, 2026-10-14T10:12:13
    java -Xmx16g -Xss64m -cp build-vh -Dverify.husps=true -Dseeds=5 -Drepeats=1 -Ddataset.only=SIGN,LEVIATHAN,C8T1S5I8N5K -Dalgo.only=Adaptive-Full,Remine-from-scratch,Remine-HUSP-ULL,Remine-HUSP-SP -Dresults.dir=results/verify-joint/husps-mixed/ AutomatedExperimentRunner SC6 SC6D SC6B
    # husps-sc2: exit 0, commit 35fe25c3, 2026-10-14T14:21:40
    bash -c rc=0; for d in SIGN TAFENG C8T1S5I8N5K LEVIATHAN BIBLE KOSARAK FIFA; do java -Xmx20g -Xss64m -cp build-vh -Dverify.husps=true -Drepeats=1 -Ddataset.only=$d -Dalgo.only=Adaptive-Full,Remine-HUSP-ULL,Remine-HUSP-SP,Joint-miner+SWUmax,Joint-miner+TSU,Joint-rule+SWU-tracking -Dresults.dir=results/verify-joint/husps-sc2/ AutomatedExperimentRunner SC2 || rc=1; done; exit $rc
    # ips-random: exit 0, commit 41f3af39, 2026-10-12T14:58:58
    java -Xmx8g -Xss64m -cp build-vj verify.ItemRemovalCheck random 2000 1
    # ips-real: exit 0, commit 41f3af39, 2026-10-12T15:04:42
    bash -c rc=0; for d in SIGN LEVIATHAN FIFA BIBLE BMS1 KOSARAK C8T1S5I8N5K TAFENG; do java -Xmx24g -Xss64m -cp build-vj verify.ItemRemovalCheck real $d init floor || rc=1; done; for d in SIGN LEVIATHAN FIFA BIBLE KOSARAK C8T1S5I8N5K TAFENG; do java -Xmx24g -Xss64m -cp build-vj verify.ItemRemovalCheck real $d whole floor || rc=1; done; exit $rc
    # peu-tracking-acceptance: exit 0, commit 6ad44c1f, 2026-10-14T09:18:14
    java -cp build-vj verify.RescanRuleAcceptanceTest
    # peu-tracking-adversarial: exit 0, commit 6ad44c1f, 2026-10-14T11:21:25
    java -Dadapt.tracking=peu -Xmx24g -Xss64m -cp build-vj verify.DirectPathVerifier ALL --adversarial --ops 16 --rule joint --insert-only --out results/verify-joint/peu-tracking-adversarial
    # peu-tracking-fuzz: exit 0, commit 35fe25c3, 2026-10-14T09:33:57
    bash -c rc=0; for s in "2000 7" "5000 11" "5000 23" "5000 101"; do set -- $s; java -Dadapt.tracking=peu -cp build-vj verify.MixedBudgetFuzz joint $1 $2 insert-only || rc=1; done; exit $rc
    # peu-tracking-random: exit 0, commit 6ad44c1f, 2026-10-14T09:31:35
    java -Dadapt.tracking=peu -Xmx24g -Xss64m -cp build-vj verify.DirectPathVerifier ALL --ops 16 --rule joint --insert-only --out results/verify-joint/peu-tracking-random
    # pre-tests-acceptance: exit 0, commit 7bfddd24, 2026-10-14T08:47:18
    java -cp build-vj verify.RescanRuleAcceptanceTest
    # pre-tests-adversarial: exit 0, commit 7bfddd24, 2026-10-14T11:40:21
    bash -c rc=0; for r in pre_swumax pre_tsu; do java -Xmx24g -Xss64m -cp build-vj verify.DirectPathVerifier ALL --adversarial --ops 16 --rule $r --insert-only --out results/verify-joint/pre-tests-adversarial || rc=1; done; exit $rc
    # pre-tests-fuzz: exit 0, commit 7bfddd24, 2026-10-14T08:49:51
    bash -c rc=0; for s in "2000 7" "5000 11" "5000 23" "5000 101"; do set -- $s; for r in pre_swumax pre_tsu joint; do java -cp build-vj verify.MixedBudgetFuzz $r $1 $2 insert-only || rc=1; done; done; exit $rc
    # pre-tests-random: exit 0, commit 7bfddd24, 2026-10-14T09:03:29
    bash -c rc=0; for r in pre_swumax pre_tsu; do java -Xmx24g -Xss64m -cp build-vj verify.DirectPathVerifier ALL --ops 16 --rule $r --insert-only --out results/verify-joint/pre-tests-random || rc=1; done; exit $rc
    # random: exit 0, commit b1ea30af, 2026-10-12T08:19:08
    java -Xmx24g -Xss64m -cp build-vj verify.DirectPathVerifier ALL --ops 16 --out results/verify-joint/random
