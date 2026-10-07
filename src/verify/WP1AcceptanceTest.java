package verify;

import algorithms.PreHUSPMAdaptiveAlgorithm.PreHUSPMAdaptiveAlgo;
import algorithms.PreHUSPMAdaptiveAlgorithm.PreHUSPMAdaptiveAlgo.TriggerRule;
import common.Sequence;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Nghiem thu WP1 - luat kich hoat rescan va nguong dong.
 *
 * Chay:  java -cp build verify.WP1AcceptanceTest
 * Thoat 0 neu moi test dat, 1 neu co test hong.
 *
 * Bon test tuong ung bon lo hong da sua:
 *   T1  Phan vi du cua Reviewer #1 (comment 1) - mot lo.
 *   T2  Nhieu lo lien tiep, moi lo duoi nguong nhung tong vuot (comment 2 / R4-2).
 *   T3  Kiem tra bien: ngay sau rescan, LUT_dyn phai bang LUT_min * TSU_ref (R4-3).
 *   T4  Thao tac sua doi giu nguyen utility nhung doi toan bo item - lo hong thu tu,
 *       phan bien chua neu.
 *   T6  The direct update under the default joint rule: a deletion followed by an
 *       insertion, one modification, and insertions alone where a dropped pattern's items
 *       have smaller counters than the largest. The superseded iaumax rule misses a HUSP
 *       in all three; joint must not.
 *
 * T1, T2 and T4 check the default rule, joint, and keep iaumax beside it where it passes.
 */
public final class WP1AcceptanceTest {

    private static int failures = 0;
    private static int passed = 0;

    // Tham so dung chung: UUT = 0.25, r_l = 0.8 => LUT_min = 0.20, dung nhu phan vi du.
    private static final double UUT = 0.25;
    private static final double RL = 0.8;
    private static final double KAPPA = 0.5;
    private static final double DELTA0 = 1e-4;

    public static void main(String[] args) {
        testReviewerCounterexample();
        testCumulativeAcrossBatches();
        testFloorAfterRescan();
        testModificationBudget();
        testPreHuspmVariants();
        testDirectUpdateCounterexamples();

        System.out.println();
        System.out.printf("=== WP1: %d passed, %d failed ===%n", passed, failures);
        if (failures > 0) System.exit(1);
    }

    private static PreHUSPMAdaptiveAlgo newAlgo(TriggerRule rule) {
        PreHUSPMAdaptiveAlgo a = new PreHUSPMAdaptiveAlgo(
                UUT, KAPPA, DELTA0, RL, PreHUSPMAdaptiveAlgo.AblationMode.FULL, false);
        a.triggerRule = rule;
        return a;
    }

    /** Mine lai tu dau tren toan bo CSDL - moc tham chieu cho moi phep so sanh. */
    private static Map<String, Double> remineFromScratch(List<Sequence> all) {
        PreHUSPMAdaptiveAlgo ref = newAlgo(TriggerRule.JOINT);   // a single mine: the rule plays no part
        ref.runInitialMining(copyOf(all), SyntheticDb.tsu(all));
        return ref.exportLargeAsStrings();
    }

    /**
     * Thuat toan ghi de truong noi bo cua Sequence (flat representation, remaining
     * utilities) nen hai instance khong duoc dung chung doi tuong. Moi lan chay lap
     * lai chuoi tu dau.
     */
    private static List<Sequence> copyOf(List<Sequence> src) {
        return new ArrayList<>(src);
    }

    // ================================================================
    // T1 - Phan vi du cua Reviewer #1
    // ================================================================
    /**
     * UUT = 0.25, LUT = 0.20, TSU(D) = 100, mau X = <1> co u(X,D) = 19 (nho: 19 < 20
     * nen KHONG duoc theo doi). Chen hai chuoi cung chua X voi utility 6.
     *
     * Luat cu:  SWUmax(d) = 6 <= SFT_ins = (0.25-0.20)/(1-0.25) * 100 = 6.667
     *           => khong rescan => X bi bo sot.
     * Luat moi: A_1 = 6 + 6 = 12 > 6.667 => rescan => X duoc tim thay.
     * Thuc te:  TSU(D') = 112, u(X,D') = 31 > 0.25 * 112 = 28 => X DA la HUSP.
     */
    private static void testReviewerCounterexample() {
        System.out.println("T1 - phan vi du cua Reviewer #1 (comment 1)");

        // Luat cu phai BO SOT
        Map<String, Double> gotOld = runInsertScenario(TriggerRule.SWUMAX);
        Map<String, Double> reference = referenceForInsertScenario();
        PatternSetDiff dOld = PatternSetDiff.of(reference, gotOld);
        check("  luat SWUMAX (cu) bo sot it nhat mot HUSP", dOld.missing > 0, dOld.toString());
        check("  mau bi bo sot dung la X = <1>", gotOld.get("1") == null,
                "X " + (gotOld.containsKey("1") ? "co" : "khong") + " trong ket qua");

        // Luat moi phai DAY DU
        Map<String, Double> gotNew = runInsertScenario(TriggerRule.IAUMAX);
        PatternSetDiff dNew = PatternSetDiff.of(reference, gotNew);
        check("  luat IAUMAX (moi) khong bo sot", dNew.isClean(), dNew.toString());
        check("  X = <1> duoc tra ve la HUSP", gotNew.containsKey("1"),
                "cac mau: " + gotNew.keySet());

        Map<String, Double> gotJoint = runInsertScenario(TriggerRule.JOINT);
        check("  joint rule (default) misses nothing", PatternSetDiff.of(reference, gotJoint).isClean(),
                PatternSetDiff.of(reference, gotJoint).toString());

        // Luat CUB tho cung phai day du
        Map<String, Double> gotCub = runInsertScenario(TriggerRule.CUB);
        check("  luat CUB (tich luy tho) khong bo sot",
                PatternSetDiff.of(reference, gotCub).isClean(),
                PatternSetDiff.of(reference, gotCub).toString());
    }

    private static List<Sequence> insertInit() {
        SyntheticDb.resetIds();
        // u(seq1) = 19 + 31 = 50, u(seq2) = 50  => TSU(D) = 100, u(<1>, D) = 19
        return SyntheticDb.db("1[19] | 2[31]", "2[50]");
    }

    private static List<Sequence> insertBatch(List<Sequence> init) {
        // hai chuoi cung chua item 1, moi chuoi utility 6
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
    // T2 - Nhieu lo lien tiep, moi lo duoi nguong nhung tong vuot
    // ================================================================
    /**
     * Dung phan vi du cua Reviewer #1 comment 2: voi SFT_ins = 6.667, hai lo lien tiep
     * moi lo co SWUmax = 4 thi luat cu khong bao gio kich hoat, trong khi perturbation
     * tich luy dat 8 va da vuot ngan sach.
     */
    private static void testCumulativeAcrossBatches() {
        System.out.println("T2 - nhieu lo lien tiep duoi nguong, tong vuot (comment 2 / R4-2)");

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
                check("  luat SWUMAX (cu) bo sot sau hai lo", d.missing > 0, d.toString());
            } else {
                check("  rule " + rule + " matches remine-from-scratch", d.isClean(), d.toString());
            }
        }
    }

    // ================================================================
    // T3 - Kiem tra bien cua can duoi Delta (R4-3)
    // ================================================================
    /**
     * Ngay sau mot rescan, bo dem ve 0 nen can duoi cua Delta bang dung
     * (UUT - LUT_min) * TSU_ref, keo LUT_dyn ve san cung LUT_min * TSU_ref. Day la
     * dieu kien khep kin khoang ho [LUT_min*TSU', LUT_dyn) ma Reviewer #4 chi ra.
     */
    private static void testFloorAfterRescan() {
        System.out.println("T3 - LUT_dyn ve san cung ngay sau rescan (R4-3)");

        SyntheticDb.resetIds();
        List<Sequence> init = SyntheticDb.db("1[19] | 2[31]", "2[50]");
        // lo du lon de chac chan kich hoat rescan
        List<Sequence> big = SyntheticDb.db("1[40]", "3[40]");
        List<Sequence> all = SyntheticDb.concat(init, big);

        PreHUSPMAdaptiveAlgo algo = newAlgo(TriggerRule.JOINT);
        algo.runInitialMining(copyOf(init), SyntheticDb.tsu(init));
        algo.runIncrementalMining(copyOf(big), copyOf(all), SyntheticDb.tsu(all));

        check("  lo lon co kich hoat rescan", algo.cubTriggeredRescan,
                "cubTriggeredRescan=" + algo.cubTriggeredRescan);

        double tsuAfter = SyntheticDb.tsu(all);
        double expectedFloor = RL * UUT * tsuAfter;   // LUT_min * TSU'
        check("  LUT_dyn == LUT_min * TSU_ref sau rescan",
                Math.abs(algo.lutDynValue - expectedFloor) < 1e-6 * Math.max(1, expectedFloor),
                String.format("LUT_dyn=%.6f, ky vong=%.6f", algo.lutDynValue, expectedFloor));

        // Va LUT_dyn khong bao gio duoc VUOT san cung khi bo dem con trong
        check("  LUT_dyn khong vuot san cung", algo.lutDynValue <= expectedFloor + 1e-6,
                String.format("LUT_dyn=%.6f > san=%.6f", algo.lutDynValue, expectedFloor));
    }

    // ================================================================
    // T4 - Ngan sach cho thao tac sua doi
    // ================================================================
    /**
     * Thao tac sua giu NGUYEN utility cua chuoi nhung doi toan bo item. Cong thuc cu
     * |u(new) - u(old)| = 0 nen khong tieu ngan sach nao, du tap mau doi hoan toan.
     */
    private static void testModificationBudget() {
        System.out.println("T4 - thao tac sua giu nguyen utility, doi toan bo item");

        SyntheticDb.resetIds();
        List<Sequence> init = SyntheticDb.db("1[19] | 2[31]", "2[50]");
        Sequence oldSeq = init.get(1);                 // 2[50]
        List<Sequence> oldList = new ArrayList<>();
        oldList.add(oldSeq);
        List<Sequence> newList = SyntheticDb.db("1[50]");   // cung utility 50, doi item
        List<Sequence> after = new ArrayList<>();
        after.add(init.get(0));
        after.addAll(newList);

        PreHUSPMAdaptiveAlgo algo = newAlgo(TriggerRule.JOINT);
        algo.runInitialMining(copyOf(init), SyntheticDb.tsu(init));
        double cubBefore = algo.cubValue;
        algo.runModification(oldList, newList, copyOf(after), SyntheticDb.tsu(after));

        check("  thao tac sua co tieu ngan sach (CUB tang)",
                algo.cubValue > cubBefore || algo.cubTriggeredRescan,
                String.format("CUB %.3f -> %.3f, rescan=%b", cubBefore, algo.cubValue, algo.cubTriggeredRescan));

        PatternSetDiff d = PatternSetDiff.of(remineFromScratch(after), algo.exportLargeAsStrings());
        check("  ket qua khop remine-from-scratch", d.isClean(), d.toString());
    }

    // ================================================================
    // T5 - Hai bien the cua BASELINE Pre-HUSPM
    // ================================================================
    /**
     * Chung minh loi nam o BAI GOC chu khong phai o phan chuyen the cua ta.
     *
     * Phan vi du o T1 khong lam lo duoc loi tren baseline Pre-HUSPM, vi bo dem cua ho
     * dung PEU (projected SWU) chu khong dung utility thuc: voi chuoi "1[19] | 2[31]",
     * PEU(<1>) = 19 + 31 = 50, vuot San Sl*TSU = 20, nen X van nam trong bo dem va van
     * duoc cap nhat du khong rescan. Bo dem rong che mat luat kich hoat hong.
     *
     * De lo ra thi mau phai co PEU duoi san. Dat X = <1> nam mot minh trong chuoi
     * "1[19]" (PEU = 19 < 20), phan utility con lai cua CSDL nam o chuoi khong chua
     * item 1. Khi do:
     *   SWUMAX: SWUmax(d) = 6 <= f = 6.667  -> khong rescan -> X bi bo sot
     *   TSU   : TSU(d)    = 12 >  f         -> rescan       -> X duoc tim thay
     */
    private static void testPreHuspmVariants() {
        System.out.println("T5 - hai bien the cua baseline Pre-HUSPM (Dinh ly 1 vs Dinh ly 2)");

        java.util.Map<String, Double> ref = preHuspmScenario(null);           // remine tu dau
        java.util.Map<String, Double> swu = preHuspmScenario(
                algorithms.PreHUSPMAlgorithm.PreHUSPMAlgo.Variant.SWUMAX);
        java.util.Map<String, Double> tsu = preHuspmScenario(
                algorithms.PreHUSPMAlgorithm.PreHUSPMAlgo.Variant.TSU);

        check("  moc tham chieu co chua X = <1>", ref.containsKey("1"), "cac mau: " + ref.keySet());

        PatternSetDiff dSwu = PatternSetDiff.of(ref, swu);
        PatternSetDiff dTsu = PatternSetDiff.of(ref, tsu);
        check("  Pre-HUSPM-SWUmax (Dinh ly 2) bo sot", dSwu.missing > 0, dSwu.toString());
        check("  Pre-HUSPM-TSU (Dinh ly 1) khong bo sot", dTsu.isClean(), dTsu.toString());
    }

    /** variant == null nghia la mine lai tu dau tren CSDL sau cap nhat. */
    private static java.util.Map<String, Double> preHuspmScenario(
            algorithms.PreHUSPMAlgorithm.PreHUSPMAlgo.Variant variant) {
        SyntheticDb.resetIds();
        // PEU(<1>) = 19 (khong co item nao sau no trong chuoi) < Sl*TSU = 20
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
                    check("  iaumax (superseded) misses <1> in case " + names[i], miss[i], "no pattern missed");
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
            System.out.println("    [DAT ] " + name);
        } else {
            failures++;
            System.out.println("    [HONG] " + name + "  ->  " + detail);
        }
    }
}
