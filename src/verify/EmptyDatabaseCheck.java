package verify;

import algorithms.PreHUSPMAdaptiveAlgorithm.PreHUSPMAdaptiveAlgo;
import algorithms.PreHUSPMAdaptiveAlgorithm.PreHUSPMAdaptiveAlgo.AblationMode;
import common.Sequence;

import java.util.*;

/**
 * Streams that pass through an empty database: an initial mine of an empty D_init, and deletions
 * that remove every sequence. After every operation the reported patterns and utilities are
 * compared with remining from scratch.
 *
 * A rescan of an empty database sets the reference total utility TU_ref to 0, so the budget
 * (UUT - LUT_min) * TU_ref is 0 and L_ref = 0: a pattern that does not occur has utility 0 = L_ref
 * and is not tracked. The joint rule alone would then let the first insertion through when
 * max_i A_i - UUT * I + UUT * D <= 0, for instance UUT = 0.5 and two sequences <(a)> and <(b)> of
 * utility 1: 1 - 0.5 * 2 = 0, while <(a)> has utility 1 >= 0.5 * 2 and is a HUSP. The rule therefore
 * rescans at the first insertion after a rescan of an empty database.
 *
 * args: trials seed     (defaults 3000 7)
 * Configurations: the fixed lower threshold, and the break-even selection by nodes and by time.
 * Prints, per configuration, the operations, the insertions made when the reference total utility
 * was 0, and the mismatching operations; exits 1 on any mismatch.
 */
public class EmptyDatabaseCheck {
    static final double RL = 0.8, KAPPA = 0.5, DELTA0 = 1e-4;

    static double tsu(List<Sequence> s) { double t = 0; for (Sequence q : s) t += q.sequenceUtility; return t; }

    static boolean same(PreHUSPMAdaptiveAlgo algo, List<Sequence> cur, double su) {
        PreHUSPMAdaptiveAlgo ref = new PreHUSPMAdaptiveAlgo(su, KAPPA, DELTA0, RL, AblationMode.FULL, false);
        ref.runInitialMining(new ArrayList<>(cur), tsu(cur));
        Map<String, Double> rs = ref.exportLargeAsStrings(), gs = algo.exportLargeAsStrings();
        if (!rs.keySet().equals(gs.keySet())) return false;
        for (String k : rs.keySet()) if (Math.abs(rs.get(k) - gs.get(k)) > 1e-9) return false;
        return true;
    }

    static PreHUSPMAdaptiveAlgo make(double su, String config) {
        PreHUSPMAdaptiveAlgo a = new PreHUSPMAdaptiveAlgo(su, KAPPA, DELTA0, RL, AblationMode.FULL, false);
        if (!config.equals("fixed")) {
            a.floorRule = PreHUSPMAdaptiveAlgo.FloorRule.BREAK_EVEN;
            a.breakEvenCost = config.equals("nodes") ? PreHUSPMAdaptiveAlgo.BreakEvenCost.NODES
                    : PreHUSPMAdaptiveAlgo.BreakEvenCost.TIME;
        }
        return a;
    }

    public static void main(String[] args) {
        int trials = args.length > 0 ? Integer.parseInt(args[0]) : 3000;
        long seed = args.length > 1 ? Long.parseLong(args[1]) : 7;
        boolean bad = false;

        // The counterexample: an empty D_init, then <(1)> and <(2)> of utility 1 at UUT = 0.5.
        for (String config : new String[]{"fixed", "nodes", "time"}) {
            SyntheticDb.resetIds();
            PreHUSPMAdaptiveAlgo a = make(0.5, config);
            List<Sequence> cur = new ArrayList<>();
            a.runInitialMining(new ArrayList<>(cur), 0.0);
            List<Sequence> ins = SyntheticDb.db("1[1]", "2[1]");
            cur.addAll(ins);
            a.runIncrementalMining(ins, new ArrayList<>(cur), tsu(cur));
            boolean ok = same(a, cur, 0.5);
            bad |= !ok;
            System.out.printf("counterexample config=%s rescans=%d %s%n", config, a.rescanCount, ok ? "match" : "MISMATCH");
        }

        // Random streams that empty the database by deletions, then insert again.
        for (String config : new String[]{"fixed", "nodes", "time"}) {
            Random r = new Random(seed);
            SyntheticDb.resetIds();
            int ops = 0, mism = 0, emptyRefInserts = 0, emptied = 0;
            for (int t = 0; t < trials; t++) {
                double su = new double[]{0.08, 0.12, 0.2, 0.3, 0.5}[r.nextInt(5)];
                List<Sequence> cur = new ArrayList<>();
                int n0 = r.nextInt(4);   // D_init may be empty
                for (int i = 0; i < n0; i++) cur.add(SyntheticDb.seq(MixedBudgetFuzz.randSeq(r)));
                PreHUSPMAdaptiveAlgo a = make(su, config);
                a.runInitialMining(new ArrayList<>(cur), tsu(cur));
                boolean refZero = cur.isEmpty();
                for (int o = 0; o < 12; o++) {
                    int kind = r.nextInt(3);
                    int before = a.rescanCount;
                    if (kind == 0 && !cur.isEmpty()) {
                        // delete every sequence, or a random part
                        List<Sequence> v = new ArrayList<>(r.nextBoolean() ? cur : cur.subList(0, 1 + r.nextInt(cur.size())));
                        cur.removeAll(v);
                        a.runDeletion(v, new ArrayList<>(cur), tsu(cur));
                        if (cur.isEmpty()) emptied++;
                        if (a.rescanCount > before) refZero = cur.isEmpty();
                    } else {
                        List<Sequence> ins = new ArrayList<>();
                        int k = 1 + r.nextInt(2);
                        for (int i = 0; i < k; i++) ins.add(SyntheticDb.seq(MixedBudgetFuzz.randSeq(r)));
                        if (refZero) emptyRefInserts++;
                        cur.addAll(ins);
                        a.runIncrementalMining(ins, new ArrayList<>(cur), tsu(cur));
                        if (a.rescanCount > before) refZero = false;
                    }
                    ops++;
                    if (!same(a, cur, su)) mism++;
                }
            }
            bad |= mism > 0;
            System.out.printf("random config=%s trials=%d seed=%d ops=%d emptied=%d inserts-with-reference-0=%d mismatching-ops=%d%n",
                    config, trials, seed, ops, emptied, emptyRefInserts, mism);
        }
        System.exit(bad ? 1 : 0);
    }
}
