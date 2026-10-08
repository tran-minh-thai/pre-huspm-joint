package verify;

import algorithms.PreHUSPMAdaptiveAlgorithm.PreHUSPMAdaptiveAlgo;
import algorithms.PreHUSPMAdaptiveAlgorithm.PreHUSPMAdaptiveAlgo.TriggerRule;
import common.Sequence;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Acceptance test for the rescan trigger rules and the dynamic lower threshold.
 *
 * Run:  java -cp build verify.RescanRuleAcceptanceTest
 * Exits 0 if every check passes, 1 if any fails.
 *
 *   T1  Insertion counterexample: one batch that lifts an untracked pattern into a HUSP.
 *   T2  Several consecutive batches, each under the budget but together over it.
 *   T3  Boundary: right after a rescan, LUT_dyn equals LUT_min * TSU_ref.
 *   T4  A modification that keeps the sequence utility but changes every item must still
 *       consume budget.
 *   T5  The two variants of the Pre-HUSPM baseline: the SWUmax rule misses a HUSP, the TSU
 *       rule does not.
 *   T6  The direct update under the default joint rule: a deletion followed by an
 *       insertion, one modification, and insertions alone where a dropped pattern's items
 *       have smaller counters than the largest. The unsound iaumax rule misses a HUSP in
 *       all three; joint must not.
 *
 * T1 and T2 check the default joint rule beside iaumax (and CUB in T1); T3 and T4 check joint.
 */
public final class RescanRuleAcceptanceTest {

    private static int failures = 0;
    private static int passed = 0;

    // Shared parameters: UUT = 0.25, r_l = 0.8 => LUT_min = 0.20, as in the counterexample.
    private static final double UUT = 0.25;
    private static final double RL = 0.8;
    private static final double KAPPA = 0.5;
    private static final double DELTA0 = 1e-4;

    public static void main(String[] args) {
        testInsertionCounterexample();
        testCumulativeAcrossBatches();
        testFloorAfterRescan();
        testModificationBudget();
        testPreHuspmVariants();
        testDirectUpdateCounterexamples();

        System.out.println();
        System.out.printf("=== acceptance: %d passed, %d failed ===%n", passed, failures);
        if (failures > 0) System.exit(1);
    }

    private static PreHUSPMAdaptiveAlgo newAlgo(TriggerRule rule) {
        PreHUSPMAdaptiveAlgo a = new PreHUSPMAdaptiveAlgo(
                UUT, KAPPA, DELTA0, RL, PreHUSPMAdaptiveAlgo.AblationMode.FULL, false);
        a.triggerRule = rule;
        return a;
    }

    /** Remines from scratch on the whole database: the reference for every comparison. */
    private static Map<String, Double> remineFromScratch(List<Sequence> all) {
        PreHUSPMAdaptiveAlgo ref = newAlgo(TriggerRule.JOINT);   // a single mine: the rule plays no part
        ref.runInitialMining(copyOf(all), SyntheticDb.tsu(all));
        return ref.exportLargeAsStrings();
    }

    /**
     * The algorithm overwrites internal fields of Sequence (flat representation, remaining
     * utilities), so each run gets its own list, and each scenario rebuilds its sequences
     * from scratch.
     */
    private static List<Sequence> copyOf(List<Sequence> src) {
        return new ArrayList<>(src);
    }

    // ================================================================
    // T1 - insertion counterexample
    // ================================================================
    /**
     * UUT = 0.25, LUT = 0.20, TSU(D) = 100, pattern X = <1> with u(X,D) = 19 (19 < 20, so
     * it is NOT tracked). Two sequences are inserted, each holding X with utility 6.
     *
     * SWUmax rule: SWUmax(d) = 6 <= SFT_ins = (0.25-0.20)/(1-0.25) * 100 = 6.667
     *              => no rescan => X is missed.
     * IAU rule:    A_1 = 6 + 6 = 12 > 6.667 => rescan => X is found.
     * Truth:       TSU(D') = 112, u(X,D') = 31 > 0.25 * 112 = 28 => X IS a HUSP.
     */
    private static void testInsertionCounterexample() {
        System.out.println("T1 - insertion counterexample, one batch");

        // The SWUmax rule must MISS X
        Map<String, Double> gotOld = runInsertScenario(TriggerRule.SWUMAX);
        Map<String, Double> reference = referenceForInsertScenario();
        PatternSetDiff dOld = PatternSetDiff.of(reference, gotOld);
        check("  SWUMAX rule misses at least one HUSP", dOld.missing > 0, dOld.toString());
        check("  the missed pattern is X = <1>", gotOld.get("1") == null,
                "X " + (gotOld.containsKey("1") ? "is" : "is not") + " in the result");

        // The IAUMAX rule must be complete
        Map<String, Double> gotNew = runInsertScenario(TriggerRule.IAUMAX);
        PatternSetDiff dNew = PatternSetDiff.of(reference, gotNew);
        check("  IAUMAX rule misses nothing", dNew.isClean(), dNew.toString());
        check("  X = <1> is returned as a HUSP", gotNew.containsKey("1"),
                "patterns: " + gotNew.keySet());

        Map<String, Double> gotJoint = runInsertScenario(TriggerRule.JOINT);
        check("  joint rule (default) misses nothing", PatternSetDiff.of(reference, gotJoint).isClean(),
                PatternSetDiff.of(reference, gotJoint).toString());

        // The raw cumulative CUB rule must be complete too
        Map<String, Double> gotCub = runInsertScenario(TriggerRule.CUB);
        check("  CUB rule (raw cumulative) misses nothing",
                PatternSetDiff.of(reference, gotCub).isClean(),
                PatternSetDiff.of(reference, gotCub).toString());
    }

    private static List<Sequence> insertInit() {
        SyntheticDb.resetIds();
        // u(seq1) = 19 + 31 = 50, u(seq2) = 50  => TSU(D) = 100, u(<1>, D) = 19
        return SyntheticDb.db("1[19] | 2[31]", "2[50]");
    }

    private static List<Sequence> insertBatch(List<Sequence> init) {
        // two sequences holding item 1, each of utility 6
        return SyntheticDb.db("1[6]", "1[6]");
    }

    private static Map<String, Double> runInsertScenario(TriggerRule rule) {
        List<Sequence> init = insertInit();
        List<Sequence> batch = insertBatch(init);
        List<Sequence> all = SyntheticDb.concat(init, batch);

        PreHUSPMAdaptiveAlgo algo = newAlgo(rule);
        algo.runInitialMining(copyOf(init), SyntheticDb.tsu(init));
        algo.runIncrementalMining(copyOf(batch), copyOf(all), SyntheticDb.tsu(all));
        return algo.exportLargeAsStrings();
    }

    private static Map<String, Double> referenceForInsertScenario() {
        List<Sequence> init = insertInit();
        List<Sequence> batch = insertBatch(init);
        return remineFromScratch(SyntheticDb.concat(init, batch));
    }

    // ================================================================
    // T2 - consecutive batches, each under the budget, together over it
    // ================================================================
    /**
     * With SFT_ins = 6.667, two consecutive batches with SWUmax = 4 each never trigger the
     * SWUmax rule, while the cumulative perturbation reaches 8 and exceeds the budget.
     */
    private static void testCumulativeAcrossBatches() {
        System.out.println("T2 - consecutive batches under the budget, together over it");

        for (TriggerRule rule : new TriggerRule[]{TriggerRule.SWUMAX, TriggerRule.IAUMAX, TriggerRule.JOINT}) {
            SyntheticDb.resetIds();
            List<Sequence> init = SyntheticDb.db("1[19] | 2[31]", "2[50]");
            List<Sequence> b1 = SyntheticDb.db("1[4]");
            List<Sequence> b2 = SyntheticDb.db("1[4]");
            List<Sequence> after1 = SyntheticDb.concat(init, b1);
            List<Sequence> after2 = SyntheticDb.concat(after1, b2);

            PreHUSPMAdaptiveAlgo algo = newAlgo(rule);
            algo.runInitialMining(copyOf(init), SyntheticDb.tsu(init));
            algo.runIncrementalMining(copyOf(b1), copyOf(after1), SyntheticDb.tsu(after1));
            algo.runIncrementalMining(copyOf(b2), copyOf(after2), SyntheticDb.tsu(after2));

            PatternSetDiff d = PatternSetDiff.of(remineFromScratch(after2), algo.exportLargeAsStrings());
            if (rule == TriggerRule.SWUMAX) {
                check("  SWUMAX rule misses a HUSP after two batches", d.missing > 0, d.toString());
            } else {
                check("  rule " + rule + " matches remine-from-scratch", d.isClean(), d.toString());
            }
        }
    }

    // ================================================================
    // T3 - boundary of the lower bound on Delta
    // ================================================================
    /**
     * Right after a rescan the counters are zero, so the lower bound on Delta is exactly
     * (UUT - LUT_min) * TSU_ref, which pulls LUT_dyn down to the hard floor
     * LUT_min * TSU_ref. This closes the gap [LUT_min*TSU', LUT_dyn) in which a pattern
     * could otherwise go untracked.
     */
    private static void testFloorAfterRescan() {
        System.out.println("T3 - LUT_dyn returns to the hard floor right after a rescan");

        SyntheticDb.resetIds();
        List<Sequence> init = SyntheticDb.db("1[19] | 2[31]", "2[50]");
        // a batch large enough to be sure of a rescan
        List<Sequence> big = SyntheticDb.db("1[40]", "3[40]");
        List<Sequence> all = SyntheticDb.concat(init, big);

        PreHUSPMAdaptiveAlgo algo = newAlgo(TriggerRule.JOINT);
        algo.runInitialMining(copyOf(init), SyntheticDb.tsu(init));
        algo.runIncrementalMining(copyOf(big), copyOf(all), SyntheticDb.tsu(all));

        check("  the large batch triggers a rescan", algo.cubTriggeredRescan,
                "cubTriggeredRescan=" + algo.cubTriggeredRescan);

        double tsuAfter = SyntheticDb.tsu(all);
        double expectedFloor = RL * UUT * tsuAfter;   // LUT_min * TSU'
        check("  LUT_dyn == LUT_min * TSU_ref after the rescan",
                Math.abs(algo.lutDynValue - expectedFloor) < 1e-6 * Math.max(1, expectedFloor),
                String.format("LUT_dyn=%.6f, expected=%.6f", algo.lutDynValue, expectedFloor));

        // And LUT_dyn never EXCEEDS the hard floor while the counters are empty
        check("  LUT_dyn does not exceed the hard floor", algo.lutDynValue <= expectedFloor + 1e-6,
                String.format("LUT_dyn=%.6f > floor=%.6f", algo.lutDynValue, expectedFloor));
    }

    // ================================================================
    // T4 - budget for a modification
    // ================================================================
    /**
     * A modification that keeps the sequence utility UNCHANGED but replaces every item. A
     * budget charged as |u(new) - u(old)| would be 0 even though the pattern set changes
     * completely, so the modification must still consume budget.
     */
    private static void testModificationBudget() {
        System.out.println("T4 - modification keeps the utility, changes every item");

        SyntheticDb.resetIds();
        List<Sequence> init = SyntheticDb.db("1[19] | 2[31]", "2[50]");
        Sequence oldSeq = init.get(1);                 // 2[50]
        List<Sequence> oldList = new ArrayList<>();
        oldList.add(oldSeq);
        List<Sequence> newList = SyntheticDb.db("1[50]");   // same utility 50, different item
        List<Sequence> after = new ArrayList<>();
        after.add(init.get(0));
        after.addAll(newList);

        PreHUSPMAdaptiveAlgo algo = newAlgo(TriggerRule.JOINT);
        algo.runInitialMining(copyOf(init), SyntheticDb.tsu(init));
        double cubBefore = algo.cubValue;
        algo.runModification(oldList, newList, copyOf(after), SyntheticDb.tsu(after));

        check("  the modification consumes budget (CUB rises)",
                algo.cubValue > cubBefore || algo.cubTriggeredRescan,
                String.format("CUB %.3f -> %.3f, rescan=%b", cubBefore, algo.cubValue, algo.cubTriggeredRescan));

        PatternSetDiff d = PatternSetDiff.of(remineFromScratch(after), algo.exportLargeAsStrings());
        check("  result matches remine-from-scratch", d.isClean(), d.toString());
    }

    // ================================================================
    // T5 - the two variants of the Pre-HUSPM BASELINE
    // ================================================================
    /**
     * Shows that the error lies in the original baseline rule, not in this port of it.
     *
     * The T1 counterexample does not expose the error on the Pre-HUSPM baseline, because
     * its counters use PEU (projected SWU) rather than the actual utility: in the sequence
     * "1[19] | 2[31]", PEU(<1>) = 19 + 31 = 50, above the floor Sl*TSU = 20, so X stays
     * tracked and is updated even without a rescan. Loose counters hide the faulty trigger.
     *
     * To expose it, the pattern's PEU must be below the floor. X = <1> stands alone in the
     * sequence "1[19]" (PEU = 19 < 20) and the rest of the utility lies in a sequence
     * without item 1. Then:
     *   SWUMAX: SWUmax(d) = 6 <= f = 6.667  -> no rescan -> X is missed
     *   TSU   : TSU(d)    = 12 >  f         -> rescan    -> X is found
     */
    private static void testPreHuspmVariants() {
        System.out.println("T5 - the two variants of the Pre-HUSPM baseline (TSU rule vs SWUmax rule)");

        java.util.Map<String, Double> ref = preHuspmScenario(null);           // remine from scratch
        java.util.Map<String, Double> swu = preHuspmScenario(
                algorithms.PreHUSPMAlgorithm.PreHUSPMAlgo.Variant.SWUMAX);
        java.util.Map<String, Double> tsu = preHuspmScenario(
                algorithms.PreHUSPMAlgorithm.PreHUSPMAlgo.Variant.TSU);

        check("  the reference contains X = <1>", ref.containsKey("1"), "patterns: " + ref.keySet());

        PatternSetDiff dSwu = PatternSetDiff.of(ref, swu);
        PatternSetDiff dTsu = PatternSetDiff.of(ref, tsu);
        check("  Pre-HUSPM-SWUmax misses a HUSP", dSwu.missing > 0, dSwu.toString());
        check("  Pre-HUSPM-TSU misses nothing", dTsu.isClean(), dTsu.toString());
    }

    /** variant == null means remining from scratch on the updated database. */
    private static java.util.Map<String, Double> preHuspmScenario(
            algorithms.PreHUSPMAlgorithm.PreHUSPMAlgo.Variant variant) {
        SyntheticDb.resetIds();
        // PEU(<1>) = 19 (no item follows it in its sequence) < Sl*TSU = 20
        List<Sequence> init = SyntheticDb.db("1[19]", "2[81]");
        List<Sequence> batch = SyntheticDb.db("1[6]", "1[6]");
        List<Sequence> all = SyntheticDb.concat(init, batch);

        algorithms.PreHUSPMAlgorithm.PreHUSPMAlgo algo;
        if (variant == null) {
            algo = new algorithms.PreHUSPMAlgorithm.PreHUSPMAlgo(UUT, RL, false);
            algo.runInitialMining(copyOf(all), SyntheticDb.tsu(all));
        } else {
            algo = new algorithms.PreHUSPMAlgorithm.PreHUSPMAlgo(UUT, RL, false, variant);
            algo.runInitialMining(copyOf(init), SyntheticDb.tsu(init));
            algo.runIncrementalMining(copyOf(batch), copyOf(all), SyntheticDb.tsu(all));
        }

        java.util.Map<String, Double> out = new java.util.HashMap<>();
        for (java.util.Map.Entry<common.Pattern, Double> e : algo.largeMap.entrySet()) {
            out.put(e.getKey().toIntuitiveString(), e.getValue());
        }
        return out;
    }

    // ================================================================
    // T6 - the direct update under the joint rule
    // ================================================================
    /**
     * UUT = 0.25, LUT_min = 0.20, TSU_ref = 100, so the joint budget is 5.
     *   A: <1> at 19 is untracked; two fillers (18) are deleted, then <1[6]> is inserted.
     *      Remining returns <1> = 25 >= 0.25 * 88 = 22.
     *   B: the same as one modification.
     *   D: <1> at 22.7 is pre-large; <50[3]> is inserted, then <1[6]>. Remining returns
     *      <1> = 28.7 >= 0.25 * 109 = 27.25. Insertions only.
     * iaumax misses <1> in all three; joint must return it.
     */
    private static void testDirectUpdateCounterexamples() {
        System.out.println("T6 - direct update: delete then insert, modify, insertions alone");
        for (TriggerRule rule : new TriggerRule[]{TriggerRule.IAUMAX, TriggerRule.JOINT}) {
            boolean[] miss = {caseDeleteInsert(rule, false), caseDeleteInsert(rule, true), caseInsertionsOnly(rule)};
            String[] names = {"A delete then insert", "B one modification", "D insertions alone"};
            for (int i = 0; i < 3; i++) {
                if (rule == TriggerRule.IAUMAX) {
                    check("  iaumax (unsound) misses <1> in case " + names[i], miss[i], "no pattern missed");
                } else {
                    check("  joint returns <1> in case " + names[i], !miss[i], "<1> missed");
                }
            }
        }
    }

    private static List<Sequence> fillers(String first, int n, double u) {
        List<Sequence> db = new ArrayList<>();
        db.add(SyntheticDb.seq(first));
        for (int i = 0; i < n; i++) db.add(SyntheticDb.seq((10 + i) + "[" + u + "]"));
        return db;
    }

    /** True when the incremental answer misses a pattern that remining returns. */
    private static boolean caseDeleteInsert(TriggerRule rule, boolean asModification) {
        SyntheticDb.resetIds();
        List<Sequence> cur = fillers("1[19]", 9, 9);
        PreHUSPMAdaptiveAlgo algo = newAlgo(rule);
        algo.runInitialMining(copyOf(cur), SyntheticDb.tsu(cur));
        List<Sequence> victims = new ArrayList<>(cur.subList(1, 3));
        List<Sequence> ins = SyntheticDb.db("1[6]");
        if (asModification) {
            cur.removeAll(victims); cur.addAll(ins);
            algo.runModification(victims, ins, copyOf(cur), SyntheticDb.tsu(cur));
        } else {
            cur.removeAll(victims);
            algo.runDeletion(victims, copyOf(cur), SyntheticDb.tsu(cur));
            cur.addAll(ins);
            algo.runIncrementalMining(copyOf(ins), copyOf(cur), SyntheticDb.tsu(cur));
        }
        return PatternSetDiff.of(remineFromScratch(cur), algo.exportLargeAsStrings()).missing > 0;
    }

    private static boolean caseInsertionsOnly(TriggerRule rule) {
        SyntheticDb.resetIds();
        List<Sequence> cur = fillers("1[22.7]", 8, 9);
        cur.add(SyntheticDb.seq("30[5.3]"));
        PreHUSPMAdaptiveAlgo algo = newAlgo(rule);
        algo.runInitialMining(copyOf(cur), SyntheticDb.tsu(cur));
        for (String spec : new String[]{"50[3]", "1[6]"}) {
            List<Sequence> ins = SyntheticDb.db(spec);
            cur.addAll(ins);
            algo.runIncrementalMining(copyOf(ins), copyOf(cur), SyntheticDb.tsu(cur));
        }
        return PatternSetDiff.of(remineFromScratch(cur), algo.exportLargeAsStrings()).missing > 0;
    }

    // ================================================================
    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
            System.out.println("    [PASS] " + name);
        } else {
            failures++;
            System.out.println("    [FAIL] " + name + "  ->  " + detail);
        }
    }
}
