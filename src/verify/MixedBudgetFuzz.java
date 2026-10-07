package verify;

import algorithms.PreHUSPMAdaptiveAlgorithm.PreHUSPMAdaptiveAlgo;
import algorithms.PreHUSPMAdaptiveAlgorithm.PreHUSPMAdaptiveAlgo.AblationMode;
import common.Sequence;

import java.util.*;

/**
 * Random insert/delete/modify streams on small databases; after every operation the
 * incremental state is compared (pattern set and utility) with remine-from-scratch.
 * Reports how many operations took the direct-update path, per operation kind, so that
 * "0 mismatches" is never read over zero direct updates.
 *
 * args: rule trials seed
 */
public class MixedBudgetFuzz {
    static final double[] SUS = {0.08, 0.12, 0.2, 0.3};
    static final double RL = 0.8, KAPPA = 0.5, DELTA0 = 1e-4;

    static double tsu(List<Sequence> s) { double t = 0; for (Sequence q : s) t += q.sequenceUtility; return t; }

    static String randSeq(Random r) {
        StringBuilder b = new StringBuilder();
        int nIs = 1 + r.nextInt(3);
        for (int k = 0; k < nIs; k++) {
            if (k > 0) b.append(" | ");
            int nIt = 1 + r.nextInt(2);
            int first = 1 + r.nextInt(6);
            for (int j = 0; j < nIt; j++) {
                if (j > 0) b.append(' ');
                b.append(Math.min(first + j, 7)).append('[').append(1 + r.nextInt(10)).append(']');
            }
        }
        return b.toString();
    }

    public static void main(String[] a) {
        String rule = a[0];
        int trials = Integer.parseInt(a[1]);
        long seed = Long.parseLong(a[2]);
        Random r = new Random(seed);
        SyntheticDb.resetIds();
        int ops = 0, bad = 0, badTrials = 0;
        Map<String, Integer> direct = new TreeMap<>(), total = new TreeMap<>(), badBy = new TreeMap<>();
        for (int t = 0; t < trials; t++) {
            double su = SUS[r.nextInt(SUS.length)];
            List<Sequence> cur = new ArrayList<>();
            int n0 = 15 + r.nextInt(15);
            for (int i = 0; i < n0; i++) cur.add(SyntheticDb.seq(randSeq(r)));
            PreHUSPMAdaptiveAlgo algo = new PreHUSPMAdaptiveAlgo(su, KAPPA, DELTA0, RL, AblationMode.FULL, false);
            algo.triggerRule = PreHUSPMAdaptiveAlgo.TriggerRule.valueOf(rule.toUpperCase());
            algo.runInitialMining(new ArrayList<>(cur), tsu(cur));
            boolean trialBad = false;
            for (int o = 0; o < 10; o++) {
                int kind = r.nextInt(3);
                String op;
                int before = algo.rescanCount;
                if (kind == 0 || cur.size() < 6) {
                    op = "insert";
                    List<Sequence> ins = new ArrayList<>();
                    int k = 1 + r.nextInt(2);
                    for (int i = 0; i < k; i++) ins.add(SyntheticDb.seq(randSeq(r)));
                    cur.addAll(ins);
                    algo.runIncrementalMining(ins, new ArrayList<>(cur), tsu(cur));
                } else if (kind == 1) {
                    op = "delete";
                    List<Sequence> v = new ArrayList<>();
                    int k = 1 + r.nextInt(2);
                    List<Sequence> pool = new ArrayList<>(cur);
                    Collections.shuffle(pool, r);
                    v.addAll(pool.subList(0, k));
                    cur.removeAll(v);
                    algo.runDeletion(v, new ArrayList<>(cur), tsu(cur));
                } else {
                    op = "modify";
                    List<Sequence> pool = new ArrayList<>(cur);
                    Collections.shuffle(pool, r);
                    List<Sequence> olds = new ArrayList<>(pool.subList(0, 1));
                    List<Sequence> news = new ArrayList<>(List.of(SyntheticDb.seq(randSeq(r))));
                    cur.removeAll(olds); cur.addAll(news);
                    algo.runModification(olds, news, new ArrayList<>(cur), tsu(cur));
                }
                ops++;
                total.merge(op, 1, Integer::sum);
                if (algo.rescanCount == before) direct.merge(op, 1, Integer::sum);

                PreHUSPMAdaptiveAlgo ref = new PreHUSPMAdaptiveAlgo(su, KAPPA, DELTA0, RL, AblationMode.FULL, false);
                ref.runInitialMining(new ArrayList<>(cur), tsu(cur));
                Map<String, Double> rs = ref.exportLargeAsStrings(), gs = algo.exportLargeAsStrings();
                boolean ok = rs.keySet().equals(gs.keySet());
                if (ok) for (String k : rs.keySet()) if (Math.abs(rs.get(k) - gs.get(k)) > 1e-9) { ok = false; break; }
                if (!ok) { bad++; trialBad = true; badBy.merge(op + (algo.rescanCount == before ? "/direct" : "/rescan"), 1, Integer::sum); }
            }
            if (trialBad) badTrials++;
        }
        System.out.printf("rule=%s trials=%d seed=%d ops=%d per-kind=%s direct-update=%s mismatching-ops=%d %s trials-with-mismatch=%d%n",
                rule, trials, seed, ops, total, direct, bad, badBy, badTrials);
        System.exit(bad > 0 ? 1 : 0);
    }
}
