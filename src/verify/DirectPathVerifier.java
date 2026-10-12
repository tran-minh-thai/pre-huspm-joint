package verify;

import algorithms.PreHUSPMAdaptiveAlgorithm.PreHUSPMAdaptiveAlgo;
import algorithms.PreHUSPMAdaptiveAlgorithm.PreHUSPMAdaptiveAlgo.AblationMode;
import common.DatasetLoader;
import common.Sequence;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

/**
 * Set-level correctness of the direct-update path on the real databases.
 *
 * CorrectnessVerifier grows the database by a fifth of the stream per insertion, which is
 * far beyond the safety budget, so every insertion rescans and the direct update is never
 * exercised on an insertion. This verifier sizes every operation from the budget instead:
 *
 *     B = (UUT - LUT_min) * TSU_ref,   c = B / SHARE
 *
 * and each operation is built to consume about c of it under the JOINT rule:
 *   insert  - the next stream sequences until (1 - UUT) * sum u >= c
 *             (an item present in every inserted sequence makes max_i A_i equal sum u),
 *   delete  - random sequences of the current database until UUT * sum u >= c,
 *   modify  - pairs (random current sequence, next stream sequence) until
 *             UUT * sum u_old + (1 - UUT) * sum u_new >= c.
 * Operations cycle delete, insert, modify, insert, so an insertion follows every deletion
 * and every modification, and a rescan comes every few operations when the budget runs out.
 *
 * After every operation the pattern set and every pattern's utility are compared with
 * remining from scratch. Each row records which path the operation took and which path the
 * operation before it took, so a clean file can be read as covering the direct insertion
 * after a direct deletion only if such rows exist; the summary counts them.
 *
 * --adversarial shapes the operations like the counterexamples instead of drawing them at
 * random. Random deletions lower every pattern's utility roughly in proportion to TSU, so
 * no pattern is pushed across the threshold and a faulty rule goes unseen (measured: the
 * unsound iaumax rule and two injected faults all pass the random stream on SIGN and
 * C8T1S5I8N5K). In adversarial mode a target X is chosen after the initial mine and after
 * every rescan: the untracked pattern of highest utility, found by a reference mine at the
 * lower floor PROBE_RL. Deletions then take only sequences missing one of X's items, which
 * cannot contain X, and insertions (and the new side of a modification) take only sequences
 * holding all of them -
 * from the stream first, otherwise copies, under fresh ids, of such sequences of the
 * database. X keeps its utility while TSU falls, and gains while it rises.
 *
 *   java -cp build verify.DirectPathVerifier <DATABASE|ALL> [--ops N] [--share S]
 *        [--rule joint|iaumax|cub|swumax] [--seed N] [--adversarial] [--out DIR]
 */
public final class DirectPathVerifier {

    private static final String DATASET_DIR = "datasets";
    // The operating point of results/verify: D_init is the first half, r_l = 0.8 everywhere.
    private static final double RL = 0.8, KAPPA = 0.5, DELTA0 = 1e-4;
    // Floor of the reference mine that finds the adversarial target, as a ratio of UUT, and
    // its candidate budget. Above the budget the miner raises its floor and starts again,
    // which keeps BMS1 - where a floor of 0.6 * UUT does not finish - from stalling the run;
    // whatever floor it settles on, the target is still the untracked pattern of highest
    // utility below the HUSP threshold among those it found.
    private static final double PROBE_RL = 0.6;
    private static final long PROBE_CANDIDATE_BUDGET = 500_000_000L;

    public static void main(String[] args) throws IOException {
        if (args.length == 0) {
            System.out.println("usage: verify.DirectPathVerifier <DATABASE|ALL> [--ops N] [--share S] "
                    + "[--rule joint|iaumax|cub|swumax] [--seed N] [--adversarial] [--out DIR]");
            System.out.println("databases: " + common.Datasets.names());
            return;
        }
        String target = args[0];
        int ops = 16;
        double share = 6.0;
        long seed = 42;
        String rule = null;
        boolean adversarial = false;
        Path outDir = Paths.get("results", "verify-joint");
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--ops":   ops = Integer.parseInt(args[++i]); break;
                case "--share": share = Double.parseDouble(args[++i]); break;
                case "--seed":  seed = Long.parseLong(args[++i]); break;
                case "--rule":  rule = args[++i]; break;
                case "--out":   outDir = Paths.get(args[++i]); break;
                case "--adversarial": adversarial = true; break;
                default: throw new IllegalArgumentException("unknown argument: " + args[i]);
            }
        }
        Files.createDirectories(outDir);
        Path csv = outDir.resolve("verification.csv");
        boolean fresh = !Files.exists(csv);

        Map<String, Integer> counts = new TreeMap<>();
        int violations = 0;
        boolean ran = false;
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(csv, StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND))) {
            if (fresh) {
                w.println("dataset,uut,r_l,trigger_rule,seed,mode,target,target_utility,op_index,op_type,n_sequences,op_utility,"
                        + "budget,trigger_quantity,path,previous_path,ref_husp,got_husp,missing,extra,"
                        + "max_utility_gap,rescans,clean");
            }
            for (common.Datasets.Entry e : common.Datasets.all()) {
                if (!"ALL".equalsIgnoreCase(target) && !e.name.equalsIgnoreCase(target)) continue;
                violations += verifyDataset(e.name, e.defaultUut, rule, ops, share, seed, adversarial, w, counts);
                ran = true;
            }
        }
        if (!ran) {
            System.out.println("no database named " + target + "; choose from " + common.Datasets.names());
            System.exit(2);
        }
        System.out.println();
        System.out.println("operations by kind/path: " + counts);
        System.out.println("mismatching operations: " + violations);
        System.out.println("verification.csv -> " + csv);
        System.exit(violations == 0 ? 0 : 1);
    }

    private static PreHUSPMAdaptiveAlgo newAlgo(double uut, String rule) {
        PreHUSPMAdaptiveAlgo a = new PreHUSPMAdaptiveAlgo(uut, KAPPA, DELTA0, RL, AblationMode.FULL, false);
        if (rule != null) a.triggerRule = PreHUSPMAdaptiveAlgo.TriggerRule.valueOf(rule.toUpperCase());
        return a;
    }

    private static int verifyDataset(String name, double uut, String rule, int ops, double share,
                                     long seed, boolean adversarial, PrintWriter w,
                                     Map<String, Integer> counts) throws IOException {
        List<Sequence> all = DatasetLoader.loadDataset(
                Paths.get(DATASET_DIR, name + "_seq.txt").toString(),
                Paths.get(DATASET_DIR, name + "_eui.txt").toString());
        int half = all.size() / 2;
        List<Sequence> current = new ArrayList<>(all.subList(0, half));
        List<Sequence> stream = new ArrayList<>(all.subList(half, all.size()));
        int next = 0;
        Random rnd = new Random(seed);
        int nextId = 0;
        for (Sequence q : all) nextId = Math.max(nextId, q.id + 1);

        PreHUSPMAdaptiveAlgo algo = newAlgo(uut, rule);
        algo.runInitialMining(new ArrayList<>(current), tsu(current));
        System.out.printf("%n=== %s | UUT=%.6f | r_l=%.2f | rule=%s | %d operations | budget share 1/%.0f ===%n",
                name, uut, RL, algo.triggerRule, ops, share);

        String[] cycle = {"delete", "insert", "modify", "insert"};
        String previous = "init";
        int violations = 0;
        String target = "";
        double targetUtil = 0;
        java.util.Set<Integer> targetItems = java.util.Collections.emptySet();
        int lastRescans = -1;
        for (int o = 0; o < ops; o++) {
            String op = cycle[o % cycle.length];
            if (adversarial && algo.rescanCount != lastRescans) {
                lastRescans = algo.rescanCount;
                Map.Entry<String, Double> t = pickTarget(algo, current, uut, rule);
                target = t == null ? "" : t.getKey();
                targetUtil = t == null ? 0 : t.getValue();
                targetItems = itemsOf(target);
                System.out.printf("  target after %d rescans: <%s> u=%.1f (L_r=%.1f)%n",
                        lastRescans, target, targetUtil, RL * uut * algo.tsuAtLastRescan);
            }
            double budget = (uut - RL * uut) * algo.tsuAtLastRescan;
            double c = budget / share;
            int before = algo.rescanCount;
            int n = 0;
            double opUtil = 0;
            if (op.equals("insert")) {
                List<Sequence> ins = new ArrayList<>();
                double s = 0;
                while (ins.isEmpty() || (1 - uut) * s < c) {
                    Sequence q = (adversarial && !targetItems.isEmpty()) ? holding(targetItems, stream, current, rnd, nextId++)
                                             : (next < stream.size() ? stream.get(next++) : null);
                    if (q == null) break;
                    if (adversarial && !targetItems.isEmpty() && q.id < nextId - 1) stream.remove(q);
                    ins.add(q); s += q.sequenceUtility;
                }
                if (ins.isEmpty()) { System.out.println("  stream exhausted"); break; }
                current.addAll(ins);
                algo.runIncrementalMining(ins, new ArrayList<>(current), tsu(current));
                n = ins.size(); opUtil = s;
            } else if (op.equals("delete")) {
                List<Sequence> pool = new ArrayList<>(current);
                java.util.Collections.shuffle(pool, rnd);
                List<Sequence> victims = new ArrayList<>();
                double s = 0;
                for (Sequence q : pool) {
                    if (!victims.isEmpty() && uut * s >= c) break;
                    if (adversarial && !targetItems.isEmpty() && holdsAll(q, targetItems)) continue;
                    victims.add(q); s += q.sequenceUtility;
                }
                current.removeAll(victims);
                algo.runDeletion(victims, new ArrayList<>(current), tsu(current));
                n = victims.size(); opUtil = s;
            } else {
                List<Sequence> pool = new ArrayList<>(current);
                java.util.Collections.shuffle(pool, rnd);
                List<Sequence> olds = new ArrayList<>(), news = new ArrayList<>();
                double so = 0, sn = 0;
                for (Sequence q : pool) {
                    if (!olds.isEmpty() && uut * so + (1 - uut) * sn >= c) break;
                    if (adversarial && !targetItems.isEmpty() && holdsAll(q, targetItems)) continue;
                    Sequence r = (adversarial && !targetItems.isEmpty()) ? holding(targetItems, stream, current, rnd, nextId++)
                                             : (next < stream.size() ? stream.get(next++) : null);
                    if (r == null) break;
                    if (adversarial && !targetItems.isEmpty() && r.id < nextId - 1) stream.remove(r);
                    olds.add(q); news.add(r); so += q.sequenceUtility; sn += r.sequenceUtility;
                }
                if (olds.isEmpty()) { System.out.println("  stream exhausted"); break; }
                current.removeAll(olds);
                current.addAll(news);
                algo.runModification(olds, news, new ArrayList<>(current), tsu(current));
                n = olds.size(); opUtil = so + sn;
            }
            String path = (algo.rescanCount == before) ? "direct" : "rescan";

            PreHUSPMAdaptiveAlgo ref = newAlgo(uut, rule);
            ref.runInitialMining(new ArrayList<>(current), tsu(current));
            PatternSetDiff diff = PatternSetDiff.of(ref.exportLargeAsStrings(), algo.exportLargeAsStrings());
            if (!diff.isClean()) violations++;
            counts.merge(op + "/" + path, 1, Integer::sum);
            if (op.equals("insert") && path.equals("direct") && previous.endsWith("direct")
                    && !previous.startsWith("insert")) {
                counts.merge("insert/direct after direct " + previous.substring(0, previous.indexOf('/')), 1, Integer::sum);
            }

            w.printf("%s,%.6f,%.2f,%s,%d,%s,%s,%.3f,%d,%s,%d,%.3f,%.3f,%.3f,%s,%s,%d,%d,%d,%d,%.9f,%d,%s%n",
                    name, uut, RL, algo.triggerRule.name().toLowerCase(), seed,
                    adversarial ? "adversarial" : "random", "\"" + target + "\"", targetUtil,
                    o + 1, op, n, opUtil,
                    budget, algo.iauMaxAtDecision, path, previous, diff.refSize, diff.gotSize,
                    diff.missing, diff.extra, diff.maxUtilityGap, algo.rescanCount, diff.isClean());
            w.flush();
            System.out.printf("  op %2d %-6s n=%-5d path=%-6s after=%-13s %s  %s%n",
                    o + 1, op, n, path, previous, diff, diff.isClean() ? "OK" : "<<< MISMATCH");
            previous = op + "/" + path;
        }
        return violations;
    }

    /**
     * The untracked pattern of highest utility, from a reference mine at PROBE_RL * UUT; null
     * when the mine does not finish or the heap cannot hold it and its pattern set. The mine only
     * chooses the target: the instance under test shares no state with it.
     */
    private static Map.Entry<String, Double> pickTarget(PreHUSPMAdaptiveAlgo algo, List<Sequence> current,
                                                        double uut, String rule) {
        try {
            return pickTargetOrThrow(algo, current, uut, rule);
        } catch (OutOfMemoryError e) {
            System.gc();
            System.out.println("  no target: the reference mine ran out of heap");
            return null;
        }
    }

    private static Map.Entry<String, Double> pickTargetOrThrow(PreHUSPMAdaptiveAlgo algo, List<Sequence> current,
                                                               double uut, String rule) {
        PreHUSPMAdaptiveAlgo probe = new PreHUSPMAdaptiveAlgo(uut, KAPPA, DELTA0, PROBE_RL, AblationMode.FULL, false);
        if (rule != null) probe.triggerRule = PreHUSPMAdaptiveAlgo.TriggerRule.valueOf(rule.toUpperCase());
        probe.rescanCandidateBudget = PROBE_CANDIDATE_BUDGET;
        try {
            probe.runInitialMining(new ArrayList<>(current), tsu(current));
        } catch (RuntimeException e) {
            System.out.println("  no target: the reference mine did not finish (" + e.getClass().getSimpleName() + ")");
            return null;
        }
        if (probe.rescanRetries > 0) {
            System.out.printf("  reference mine raised its floor %d times, to %.2f * UUT%n",
                    probe.rescanRetries, probe.slMinRatioEff);
        }
        Map<String, Double> cand = new java.util.HashMap<>(probe.exportPrelargeAsStrings());
        cand.putAll(probe.exportLargeAsStrings());
        java.util.Set<String> tracked = new java.util.HashSet<>(algo.exportLargeAsStrings().keySet());
        tracked.addAll(algo.exportPrelargeAsStrings().keySet());
        double huspFloor = uut * tsu(current);
        Map.Entry<String, Double> best = null;
        for (Map.Entry<String, Double> e : cand.entrySet()) {
            if (tracked.contains(e.getKey()) || e.getValue() >= huspFloor) continue;
            if (best == null || e.getValue() > best.getValue()) best = e;
        }
        return best;
    }

    private static java.util.Set<Integer> itemsOf(String pattern) {
        java.util.Set<Integer> out = new java.util.HashSet<>();
        for (String tok : pattern.replace("(", " ").replace(")", " ").trim().split("\\s+")) {
            if (!tok.isEmpty()) out.add(Integer.parseInt(tok));
        }
        return out;
    }

    private static boolean holdsAll(Sequence s, java.util.Set<Integer> items) {
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (common.QItemset is : s.itemsets) for (common.QItem it : is.items) if (items.contains(it.id)) seen.add(it.id);
        return seen.size() == items.size();
    }

    /**
     * A sequence holding every item of the target: the next one in the stream, otherwise a
     * copy under {@code freshId} of a random one in the database. Returns null if none exists.
     */
    private static Sequence holding(java.util.Set<Integer> items, List<Sequence> stream,
                                    List<Sequence> current, Random rnd, int freshId) {
        if (items.isEmpty()) return null;
        for (Sequence q : stream) if (holdsAll(q, items)) return q;
        List<Sequence> hits = new ArrayList<>();
        for (Sequence q : current) if (holdsAll(q, items)) hits.add(q);
        if (hits.isEmpty()) return null;
        Sequence src = hits.get(rnd.nextInt(hits.size()));
        Sequence copy = new Sequence(freshId);
        for (common.QItemset is : src.itemsets) {
            common.QItemset c = new common.QItemset();
            for (common.QItem it : is.items) c.items.add(new common.QItem(it.id, it.utility));
            copy.itemsets.add(c);
        }
        copy.calculateRemainingUtilities();
        return copy;
    }

    private static double tsu(List<Sequence> seqs) {
        double t = 0;
        for (Sequence s : seqs) t += s.sequenceUtility;
        return t;
    }
}
