package verify;

import algorithms.PreHUSPMAlgorithm.PreHUSPMAlgo;
import algorithms.PreHUSPMAlgorithm.PreHUSPMAlgo.Variant;
import common.*;

import java.util.*;

/**
 * Checks both Pre-HUSPM arms against the exact answer after the initial mine and after every
 * insertion batch: the set of HUSPs and the utility of each.
 *
 *   java -cp build verify.PreHuspmVerifier random TRIALS SEED [dupbatch] [N0MIN [N0SPAN]]
 *   java -Xss64m -cp build verify.PreHuspmVerifier real DATABASE [BATCHES] [DATASET_DIR]
 *
 * Arms, constructed as AutomatedExperimentRunner constructs them: "Pre-HUSPM-SWUmax" (Variant.SWUMAX,
 * Theorem 2) and "Pre-HUSPM-TSU" (Variant.TSU, Theorem 1); each batch is passed as
 * runIncrementalMining(batch, database so far, previous TSU + TSU(batch)).
 *
 * random: N0MIN..N0MIN+N0SPAN-1 initial sequences (default 15..29) of up to four itemsets over
 * eight items, up to three items per itemset; ten insertion batches of one to three random
 * sequences, or with "dupbatch" of two to six copies of one random sequence (a pattern then
 * occurs in several inserted sequences, the case of Eq. (14)); Su from {0.08, 0.12, 0.2, 0.3},
 * Sl/Su from {0.5, 0.8}. References: remining with the proposal's static miner and a brute-force
 * enumeration (u(t,s) from its definition). Also checks the buffer: the su of every buffered
 * pattern equals its brute-force utility, SWU_proj >= su, and the buffer is prefix-closed.
 *
 * real: the database of common.Datasets with its D_init, default threshold and Sl/Su, the rest
 * in BATCHES equal batches in file order (default 100); reference: static remining.
 *
 * Both modes check the counter identities (mining: candidates = explored + pruned and explored
 * = buffer size; safe update: buffer before = explored + pruned, explored = buffer after), and
 * print, per arm, comparisons, mismatches, rescans and comparisons after a safe update, so that
 * zero mismatches is never read over a path that did not run. Exits 1 on any mismatch.
 */
public final class PreHuspmVerifier {

    private PreHuspmVerifier() {}

    static final double[] SUS = {0.08, 0.12, 0.2, 0.3};
    static final double[] RLS = {0.5, 0.8};
    static final Variant[] VARIANTS = {Variant.SWUMAX, Variant.TSU};
    static final double TOL = PatternSetDiff.UTILITY_TOLERANCE;

    static String label(Variant v) { return v == Variant.TSU ? "Pre-HUSPM-TSU" : "Pre-HUSPM-SWUmax"; }

    /** Per-arm totals. */
    static final class Tally {
        final String name;
        long comps, bad, safeComps, safeBad, bruteBad, missing, extra;
        long bufferChecked, bufferBad, counterBad, safeCandidates, safeExplored, subProcedureCalls, entries;
        int rescans;
        double maxGap;
        String first;
        Tally(String name) { this.name = name; }

        void print() {
            System.out.printf("  %-16s comparisons=%d mismatches=%d (missing=%d extra=%d max-utility-gap=%.6f) "
                            + "safe-updates=%d mismatches-after-safe-update=%d rescans=%d%n",
                    name, comps, bad, missing, extra, maxGap, safeComps, safeBad, rescans);
            System.out.printf("  %-16s buffer-patterns-checked=%d buffer-violations=%d counter-violations=%d "
                            + "brute-force-mismatches=%d occurrence-entries=%d safe-update work: candidates=%d "
                            + "kept=%d sub-procedure-calls=%d%n",
                    "", bufferChecked, bufferBad, counterBad, bruteBad, entries, safeCandidates, safeExplored,
                    subProcedureCalls);
            if (first != null) System.out.println("    first problem: " + first);
        }

        long failures() { return bad + bruteBad + bufferBad + counterBad; }
    }

    public static void main(String[] a) throws Exception {
        if (a.length < 2) {
            System.err.println("usage: random TRIALS SEED [dupbatch] [N0MIN [N0SPAN]] | real DATABASE [BATCHES] [DATASET_DIR]");
            System.exit(2);
        }
        long bad;
        if (a[0].equals("random")) {
            int i = 3;
            boolean dup = a.length > i && a[i].equals("dupbatch");
            if (dup) i++;
            int n0min = a.length > i ? Integer.parseInt(a[i]) : 15;
            int n0span = a.length > i + 1 ? Integer.parseInt(a[i + 1]) : 15;
            bad = random(Integer.parseInt(a[1]), Long.parseLong(a[2]), dup, n0min, n0span);
        } else if (a[0].equals("real")) {
            bad = real(a[1], a.length > 2 ? Integer.parseInt(a[2]) : 100, a.length > 3 ? a[3] : "datasets/");
        } else {
            System.err.println("unknown mode " + a[0]);
            System.exit(2);
            return;
        }
        System.out.println(bad == 0 ? "RESULT: no mismatch" : "RESULT: " + bad + " failing checks");
        System.exit(bad > 0 ? 1 : 0);
    }

    // ------------------------------------------------------------------ random streams

    static long random(int trials, long seed, boolean dup, int n0min, int n0span) {
        Random r = new Random(seed);
        SyntheticDb.resetIds();
        Map<Variant, Tally> tally = new EnumMap<>(Variant.class);
        for (Variant v : VARIANTS) tally.put(v, new Tally(label(v)));
        int steps = 0, staticVsBrute = 0;
        for (int t = 0; t < trials; t++) {
            double su = SUS[r.nextInt(SUS.length)], rl = RLS[r.nextInt(RLS.length)];
            List<Sequence> cur = new ArrayList<>();
            int n0 = n0min + r.nextInt(n0span);
            for (int i = 0; i < n0; i++) cur.add(SyntheticDb.seq(IncUspVerifier.randSeq(r)));
            double tsu = SyntheticDb.tsu(cur);
            Map<Variant, PreHUSPMAlgo> arms = new EnumMap<>(Variant.class);
            for (Variant v : VARIANTS) {
                PreHUSPMAlgo x = new PreHUSPMAlgo(su, rl, false, v);
                x.runInitialMining(new ArrayList<>(cur), tsu);
                arms.put(v, x);
            }
            for (int step = 0; step <= 10; step++) {
                Map<Variant, Boolean> safe = new EnumMap<>(Variant.class);
                Map<Variant, Integer> before = new EnumMap<>(Variant.class);
                if (step > 0) {
                    List<Sequence> ins = new ArrayList<>();
                    if (dup) {
                        int k = 2 + r.nextInt(5);
                        String spec = IncUspVerifier.randSeq(r);
                        for (int i = 0; i < k; i++) ins.add(SyntheticDb.seq(spec));
                    } else {
                        int k = 1 + r.nextInt(3);
                        for (int i = 0; i < k; i++) ins.add(SyntheticDb.seq(IncUspVerifier.randSeq(r)));
                    }
                    List<Sequence> snap = new ArrayList<>(cur);
                    snap.addAll(ins);
                    double nt = tsu + SyntheticDb.tsu(ins);
                    for (Variant v : VARIANTS) {
                        PreHUSPMAlgo x = arms.get(v);
                        int rc = x.rescanCount;
                        before.put(v, x.bufferSize());
                        x.runIncrementalMining(ins, new ArrayList<>(snap), nt);
                        safe.put(v, x.rescanCount == rc);
                    }
                    cur = snap;
                    tsu = nt;
                }
                steps++;
                Map<String, Double> all = bruteForceUtilities(cur);
                Map<String, Double> bf = threshold(all, su * SyntheticDb.tsu(cur));
                Map<String, Double> st = IncUspVerifier.staticHusps(cur, su);
                if (!PatternSetDiff.of(bf, st).isClean()) staticVsBrute++;
                String where = "trial " + t + " step " + step + " su=" + su + " rl=" + rl;
                for (Variant v : VARIANTS) {
                    PreHUSPMAlgo x = arms.get(v);
                    Tally ta = tally.get(v);
                    boolean isSafe = step > 0 && safe.get(v);
                    record(ta, x, st, isSafe, where);
                    PatternSetDiff db = PatternSetDiff.of(bf, IncUspVerifier.asStrings(x.largeMap));
                    if (!db.isClean()) { ta.bruteBad++; note(ta, where + " vs brute force: " + db); }
                    checkBuffer(ta, x, all, where);
                    checkCounters(ta, x, step == 0 || !isSafe, step > 0 && isSafe ? before.get(v) : -1, where);
                }
            }
            for (Variant v : VARIANTS) tally.get(v).rescans += arms.get(v).rescanCount;
        }
        System.out.printf("random trials=%d seed=%d batches=%s n0=[%d,%d) comparisons/arm=%d static-vs-brute-force-mismatches=%d%n",
                trials, seed, dup ? "dupbatch(2-6 copies of one sequence)" : "1-3 random sequences",
                n0min, n0min + n0span, steps, staticVsBrute);
        long bad = staticVsBrute;
        for (Variant v : VARIANTS) { tally.get(v).print(); bad += tally.get(v).failures(); }
        return bad;
    }

    // ------------------------------------------------------------------ real databases

    static long real(String name, int batches, String dir) throws Exception {
        Datasets.Entry e = Datasets.byName(name);
        if (e == null) throw new IllegalArgumentException("no database named " + name);
        List<Sequence> all = DatasetLoader.loadDataset(dir + name + "_seq.txt", dir + name + "_eui.txt");
        int n0 = e.dInitCount;
        List<Sequence> cur = new ArrayList<>(all.subList(0, n0));
        List<Sequence> rest = all.subList(n0, all.size());
        double su = e.defaultUut, rl = Datasets.slMinRatio(name);
        long t0 = System.nanoTime();
        double tsu = SyntheticDb.tsu(cur);
        Map<Variant, PreHUSPMAlgo> arms = new EnumMap<>(Variant.class);
        Map<Variant, Tally> tally = new EnumMap<>(Variant.class);
        for (Variant v : VARIANTS) {
            PreHUSPMAlgo x = new PreHUSPMAlgo(su, rl, false, v);
            x.runInitialMining(new ArrayList<>(cur), tsu);
            arms.put(v, x);
            tally.put(v, new Tally(label(v)));
        }
        Map<String, Double> st = IncUspVerifier.staticHusps(cur, su);
        for (Variant v : VARIANTS) {
            record(tally.get(v), arms.get(v), st, false, name + " batch 0");
            checkCounters(tally.get(v), arms.get(v), true, -1, name + " batch 0");
            System.out.printf("  %s initial mine: HUSP=%d (static %d) buffer=%d prelarge=%d candidates=%d explored=%d "
                            + "pruned=%d removed-items=%d occurrence-entries=%d%n",
                    label(v), arms.get(v).largeMap.size(), st.size(), arms.get(v).bufferSize(), arms.get(v).prelargeCount,
                    arms.get(v).candidateCount, arms.get(v).exploredNodes, arms.get(v).prunedPEU,
                    arms.get(v).removedItems, arms.get(v).occurrenceEntries);
        }
        int steps = 1;
        for (int b = 0; b < batches; b++) {
            int lo = (int) ((long) rest.size() * b / batches), hi = (int) ((long) rest.size() * (b + 1) / batches);
            if (lo == hi) continue;
            List<Sequence> ins = new ArrayList<>(rest.subList(lo, hi));
            List<Sequence> snap = new ArrayList<>(cur);
            snap.addAll(ins);
            double nt = tsu + SyntheticDb.tsu(ins);
            Map<Variant, Boolean> safe = new EnumMap<>(Variant.class);
            Map<Variant, Integer> before = new EnumMap<>(Variant.class);
            for (Variant v : VARIANTS) {
                PreHUSPMAlgo x = arms.get(v);
                int rc = x.rescanCount;
                before.put(v, x.bufferSize());
                x.runIncrementalMining(ins, new ArrayList<>(snap), nt);
                safe.put(v, x.rescanCount == rc);
            }
            cur = snap;
            tsu = nt;
            st = IncUspVerifier.staticHusps(cur, su);
            steps++;
            for (Variant v : VARIANTS) {
                boolean s = safe.get(v);
                record(tally.get(v), arms.get(v), st, s, name + " batch " + (b + 1));
                checkCounters(tally.get(v), arms.get(v), !s, s ? before.get(v) : -1, name + " batch " + (b + 1));
            }
        }
        for (Variant v : VARIANTS) tally.get(v).rescans = arms.get(v).rescanCount;
        System.out.printf("real %s uut=%s Sl/Su=%s D_init=%d batches=%d comparisons/arm=%d (%.0f s)%n",
                name, su, rl, n0, batches, steps, (System.nanoTime() - t0) / 1e9);
        long bad = 0;
        for (Variant v : VARIANTS) { tally.get(v).print(); bad += tally.get(v).failures(); }
        return bad;
    }

    // ------------------------------------------------------------------ checks

    static void record(Tally ta, PreHUSPMAlgo x, Map<String, Double> ref, boolean safe, String where) {
        ta.comps++;
        if (safe) ta.safeComps++;
        ta.entries += x.occurrenceEntries;
        if (safe) { ta.safeCandidates += x.candidateCount; ta.safeExplored += x.exploredNodes; ta.subProcedureCalls += x.subProcedureCalls; }
        PatternSetDiff d = PatternSetDiff.of(ref, IncUspVerifier.asStrings(x.largeMap));
        if (d.isClean()) return;
        ta.bad++;
        if (safe) ta.safeBad++;
        ta.missing += d.missing;
        ta.extra += d.extra;
        ta.maxGap = Math.max(ta.maxGap, d.maxUtilityGap);
        note(ta, where + (safe ? " (safe update)" : " (mining)") + ": " + d);
    }

    /** Buffered su equals the exact utility; SWU_proj >= su; every prefix is buffered. */
    static void checkBuffer(Tally ta, PreHUSPMAlgo x, Map<String, Double> exact, String where) {
        Map<Pattern, double[]> buf = x.bufferSnapshot();
        Set<String> keys = new HashSet<>();
        for (Pattern p : buf.keySet()) keys.add(p.toIntuitiveString());
        for (Map.Entry<Pattern, double[]> e : buf.entrySet()) {
            ta.bufferChecked++;
            String k = e.getKey().toIntuitiveString();
            double swu = e.getValue()[0], su = e.getValue()[1];
            Double ex = exact.get(k);
            String why = null;
            if (ex == null || Math.abs(ex - su) > TOL) why = "su=" + su + " exact=" + ex;
            else if (swu + TOL < su) why = "SWU_proj=" + swu + " < su=" + su;
            else {
                Pattern pre = prefix(e.getKey());
                if (pre != null && !keys.contains(pre.toIntuitiveString())) why = "prefix <" + pre.toIntuitiveString() + "> not buffered";
            }
            if (why != null) { ta.bufferBad++; note(ta, where + " buffer <" + k + ">: " + why); }
        }
        if (buf.size() != x.bufferSize()) { ta.bufferBad++; note(ta, where + " bufferSize()=" + x.bufferSize() + " snapshot=" + buf.size()); }
    }

    /** mined: after a mine; bufferBefore: buffer size before a safe update (-1 otherwise). */
    static void checkCounters(Tally ta, PreHUSPMAlgo x, boolean mined, int bufferBefore, String where) {
        String why = null;
        if (mined) {
            if (x.candidateCount != x.exploredNodes + x.prunedPEU) why = "candidates " + x.candidateCount + " != explored " + x.exploredNodes + " + pruned " + x.prunedPEU;
            else if (x.exploredNodes != x.bufferSize()) why = "explored " + x.exploredNodes + " != buffer " + x.bufferSize();
        } else if (bufferBefore >= 0) {
            if (bufferBefore != x.exploredNodes + x.prunedPEU) why = "buffer before " + bufferBefore + " != kept " + x.exploredNodes + " + discarded " + x.prunedPEU;
            else if (x.exploredNodes != x.bufferSize()) why = "kept " + x.exploredNodes + " != buffer " + x.bufferSize();
            else if (x.subProcedureCalls > bufferBefore) why = "sub-procedure calls " + x.subProcedureCalls + " > buffer " + bufferBefore;
        }
        if (why != null) { ta.counterBad++; note(ta, where + " counters: " + why); }
    }

    static void note(Tally ta, String s) { if (ta.first == null) ta.first = s; }

    /** The pattern without its last item, or null for a 1-sequence. */
    static Pattern prefix(Pattern p) {
        Pattern q = p.clonePattern();
        List<Integer> last = q.elements.get(q.elements.size() - 1);
        if (last.size() > 1) q.removeLastIExtension(); else q.removeLastSExtension();
        return q.elements.isEmpty() ? null : q;
    }

    static Map<String, Double> threshold(Map<String, Double> all, double min) {
        Map<String, Double> out = new HashMap<>();
        for (Map.Entry<String, Double> e : all.entrySet()) if (e.getValue() >= min) out.put(e.getKey(), e.getValue());
        return out;
    }

    /** Utility of every pattern occurring in db, u(t,s) taken from its definition. */
    static Map<String, Double> bruteForceUtilities(List<Sequence> db) {
        Map<String, Double> total = new HashMap<>();
        for (Sequence s : db) {
            Map<String, Double> best = new HashMap<>();
            enumerate(s, 0, new ArrayList<>(), 0, best);
            for (Map.Entry<String, Double> e : best.entrySet()) total.merge(e.getKey(), e.getValue(), Double::sum);
        }
        return total;
    }

    private static void enumerate(Sequence s, int from, List<List<Integer>> pat, double u, Map<String, Double> best) {
        for (int j = from; j < s.itemsets.size(); j++) {
            List<QItem> items = s.itemsets.get(j).items;
            int m = items.size();
            for (int mask = 1; mask < (1 << m); mask++) {
                List<Integer> el = new ArrayList<>();
                double add = 0;
                for (int k = 0; k < m; k++) if ((mask & (1 << k)) != 0) { el.add(items.get(k).id); add += items.get(k).utility; }
                Collections.sort(el);
                pat.add(el);
                Pattern p = new Pattern();
                for (List<Integer> e2 : pat) { p.addSExtension(e2.get(0)); for (int k = 1; k < e2.size(); k++) p.addIExtension(e2.get(k)); }
                String key = p.toIntuitiveString();
                double v = u + add;
                Double old = best.get(key);
                if (old == null || v > old) best.put(key, v);
                enumerate(s, j + 1, pat, v, best);
                pat.remove(pat.size() - 1);
            }
        }
    }
}
