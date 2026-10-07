package verify;

import algorithms.PreHUSPMAdaptiveAlgorithm.PreHUSPMAdaptiveAlgo;
import algorithms.PreHUSPMAdaptiveAlgorithm.PreHUSPMAdaptiveAlgo.AblationMode;
import common.Sequence;

import java.util.*;

/**
 * Probe: does a direct-update deletion followed by a direct-update insertion
 * (or one modification) admit a missed HUSP?
 *
 * Su = 0.25, r_l = 0.8 -> LUT_min = 0.20. TSU_ref = 100.
 * SFT_del = (Su - Sl)/Su * TSU_ref = 20 ; SFT_ins = (Su - Sl)/(1 - Su) * TSU_ref = 6.667.
 * X = <1> with u = 19 < LUT_min * TSU = 20, so X is not tracked after the initial mine.
 * Delete two fillers (18 <= 20): no rescan, TSU = 82, X = 19 < 20.5.
 * Insert <1[6]> (A_1 = 6 <= 6.667): no rescan, TSU = 88, X = 25 >= 22 -> HUSP.
 * Each step is checked against remine-from-scratch.
 */
public class MixedBudgetProbe {
    static final double SU = 0.25, RL = 0.8, KAPPA = 0.5, DELTA0 = 1e-4;

    static double tsu(List<Sequence> s) { double t = 0; for (Sequence q : s) t += q.sequenceUtility; return t; }

    /** null keeps the algorithm's default rule. */
    static String RULE = null;

    static PreHUSPMAdaptiveAlgo fresh() {
        PreHUSPMAdaptiveAlgo a = new PreHUSPMAdaptiveAlgo(SU, KAPPA, DELTA0, RL, AblationMode.FULL, false);
        if (RULE != null) a.triggerRule = PreHUSPMAdaptiveAlgo.TriggerRule.valueOf(RULE.toUpperCase());
        return a;
    }

    static int check(String label, PreHUSPMAdaptiveAlgo algo, List<Sequence> current) {
        PreHUSPMAdaptiveAlgo ref = fresh();
        ref.runInitialMining(new ArrayList<>(current), tsu(current));
        Map<String, Double> r = ref.exportLargeAsStrings(), g = algo.exportLargeAsStrings();
        Set<String> missing = new TreeSet<>(r.keySet()); missing.removeAll(g.keySet());
        Set<String> extra = new TreeSet<>(g.keySet()); extra.removeAll(r.keySet());
        System.out.printf("  %-28s TSU=%6.1f uut=%6.2f rescans=%d trig=%.3f sft=%.3f lutDyn=%.3f | ref=%s got=%s missing=%s extra=%s%n",
                label, tsu(current), SU * tsu(current), algo.rescanCount, algo.iauMaxAtDecision,
                algo.sftValue, algo.lutDynValue, r, g, missing, extra);
        return missing.size() + extra.size();
    }

    static List<Sequence> initDb() {
        SyntheticDb.resetIds();
        List<Sequence> db = new ArrayList<>();
        db.add(SyntheticDb.seq("1[19]"));
        for (int i = 0; i < 9; i++) db.add(SyntheticDb.seq((10 + i) + "[9]"));
        return db;
    }

    public static void main(String[] a) {
        if (a.length > 0) RULE = a[0];
        System.out.println("trigger rule = " + fresh().triggerRule);
        int bad = 0;

        System.out.println("Case A: delete two fillers, then insert <1[6]>");
        List<Sequence> cur = initDb();
        PreHUSPMAdaptiveAlgo algo = fresh();
        algo.runInitialMining(new ArrayList<>(cur), tsu(cur));
        bad += check("after init", algo, cur);
        List<Sequence> victims = new ArrayList<>(cur.subList(1, 3));
        cur.removeAll(victims);
        algo.runDeletion(victims, new ArrayList<>(cur), tsu(cur));
        bad += check("after delete(18)", algo, cur);
        List<Sequence> ins = List.of(SyntheticDb.seq("1[6]"));
        cur.addAll(ins);
        algo.runIncrementalMining(ins, new ArrayList<>(cur), tsu(cur));
        int caseA = check("after insert(6)", algo, cur);
        bad += caseA;

        System.out.println("Case B: one modification, two fillers (18) -> <1[6]>");
        cur = initDb();
        algo = fresh();
        algo.runInitialMining(new ArrayList<>(cur), tsu(cur));
        List<Sequence> olds = new ArrayList<>(cur.subList(1, 3));
        List<Sequence> news = new ArrayList<>(List.of(SyntheticDb.seq("1[6]")));
        cur.removeAll(olds); cur.addAll(news);
        algo.runModification(olds, news, new ArrayList<>(cur), tsu(cur));
        int caseB = check("after modify", algo, cur);
        bad += caseB;

        System.out.println("Control C: insert <1[6]> without the deletion (X = 25 < 26.5)");
        cur = initDb();
        algo = fresh();
        algo.runInitialMining(new ArrayList<>(cur), tsu(cur));
        ins = List.of(SyntheticDb.seq("1[6]"));
        cur.addAll(ins);
        algo.runIncrementalMining(ins, new ArrayList<>(cur), tsu(cur));
        bad += check("after insert(6)", algo, cur);

        System.out.println("Case D (insertion only): X = <1[22.7]> pre-large; insert <50[3]>, then <1[6]>");
        SyntheticDb.resetIds();
        cur = new ArrayList<>();
        cur.add(SyntheticDb.seq("1[22.7]"));
        for (int i = 0; i < 8; i++) cur.add(SyntheticDb.seq((10 + i) + "[9]"));
        cur.add(SyntheticDb.seq("30[5.3]"));
        algo = fresh();
        algo.runInitialMining(new ArrayList<>(cur), tsu(cur));
        bad += check("after init", algo, cur);
        System.out.println("    prelarge after init: " + algo.exportPrelargeAsStrings());
        ins = List.of(SyntheticDb.seq("50[3]"));
        cur.addAll(ins);
        algo.runIncrementalMining(ins, new ArrayList<>(cur), tsu(cur));
        bad += check("after insert <50[3]>", algo, cur);
        System.out.println("    prelarge: " + algo.exportPrelargeAsStrings() + " deltaD=" + algo.deltaDValue);
        ins = List.of(SyntheticDb.seq("1[6]"));
        cur.addAll(ins);
        algo.runIncrementalMining(ins, new ArrayList<>(cur), tsu(cur));
        int caseD = check("after insert <1[6]>", algo, cur);
        bad += caseD;

        System.out.printf("caseD mismatch=%s ; ", caseD > 0);
        System.out.printf("cases with a mismatch: A=%s B=%s ; total mismatching patterns over 7 checks = %d%n",
                caseA > 0, caseB > 0, bad);
        System.exit(bad > 0 ? 1 : 0);
    }
}
