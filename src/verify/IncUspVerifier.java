package verify;

import algorithms.IncUSPMinerPlusAlgorithm;
import algorithms.PreHUSPMAdaptiveAlgorithm.PreHUSPMAdaptiveAlgo;
import algorithms.PreHUSPMAdaptiveAlgorithm.PreHUSPMAdaptiveAlgo.AblationMode;
import common.*;

import java.util.*;

/**
 * Checks IncUSP-Miner+ against the exact answer after the initial mine and after every
 * insertion batch: the set of HUSPs and the utility of each.
 *
 *   java -cp build verify.IncUspVerifier random TRIALS SEED
 *   java -cp build verify.IncUspVerifier real DATABASE [BATCHES] [DATASET_DIR]
 *
 * random: small random databases (15-29 sequences of up to four itemsets over eight items,
 * up to three items per itemset), ten insertion batches of one to three sequences, threshold
 * ratio drawn from {0.08, 0.12, 0.2, 0.3}. Two references, computed independently of each
 * other: remining with the proposal's static miner, and a brute-force enumeration that takes
 * u(t,s) from its definition (the largest utility over all instances of t in s) on the
 * itemsets, without the flat representation IncUSP-Miner+ reads.
 *
 * real: the database of common.Datasets, its D_init and default threshold, the rest of the
 * database in BATCHES equal insertion batches in file order (default 100); reference is
 * remining with the static miner after every batch.
 *
 * Prints the number of comparisons, the mismatches, and how often IncUSP-Miner+ took the
 * paths that only an incremental update exercises (NDB rescan, HandleCase-2, USP-Miner rerun),
 * so that zero mismatches is never read over paths that did not run. Exits 1 on a mismatch.
 */
public final class IncUspVerifier {

    private IncUspVerifier() {}

    static final double[] SUS = {0.08, 0.12, 0.2, 0.3};

    public static void main(String[] a) throws Exception {
        if (a.length < 2) {
            System.err.println("usage: random TRIALS SEED | real DATABASE [BATCHES] [DATASET_DIR]");
            System.exit(2);
        }
        int bad = a[0].equals("random")
                ? random(Integer.parseInt(a[1]), Long.parseLong(a[2]))
                : real(a[1], a.length > 2 ? Integer.parseInt(a[2]) : 100, a.length > 3 ? a[3] : "datasets/");
        System.exit(bad > 0 ? 1 : 0);
    }

    // ------------------------------------------------------------------ random streams

    static String randSeq(Random r) {
        StringBuilder b = new StringBuilder();
        int nIs = 1 + r.nextInt(4);
        for (int k = 0; k < nIs; k++) {
            if (k > 0) b.append(" | ");
            TreeSet<Integer> items = new TreeSet<>();
            int nIt = 1 + r.nextInt(3);
            while (items.size() < nIt) items.add(1 + r.nextInt(8));
            boolean first = true;
            for (int it : items) {
                if (!first) b.append(' ');
                first = false;
                b.append(it).append('[').append(1 + r.nextInt(10)).append(']');
            }
        }
        return b.toString();
    }

    static int random(int trials, long seed) {
        Random r = new Random(seed);
        SyntheticDb.resetIds();
        int steps = 0, badStatic = 0, badBrute = 0, staticVsBrute = 0;
        long ndb = 0, case2 = 0, rebuild = 0;
        String example = null;
        for (int t = 0; t < trials; t++) {
            double su = SUS[r.nextInt(SUS.length)];
            List<Sequence> cur = new ArrayList<>();
            int n0 = 15 + r.nextInt(15);
            for (int i = 0; i < n0; i++) cur.add(SyntheticDb.seq(randSeq(r)));
            IncUSPMinerPlusAlgorithm inc = new IncUSPMinerPlusAlgorithm(su);
            inc.runInitialMining(new ArrayList<>(cur), SyntheticDb.tsu(cur));
            for (int step = 0; step <= 10; step++) {
                if (step > 0) {
                    List<Sequence> ins = new ArrayList<>();
                    int k = 1 + r.nextInt(3);
                    for (int i = 0; i < k; i++) ins.add(SyntheticDb.seq(randSeq(r)));
                    cur.addAll(ins);
                    inc.runIncrementalMining(ins, new ArrayList<>(cur), SyntheticDb.tsu(cur));
                }
                steps++;
                Map<String, Double> got = asStrings(inc.husps());
                Map<String, Double> st = staticHusps(cur, su);
                Map<String, Double> bf = bruteForceHusps(cur, su);
                PatternSetDiff d1 = PatternSetDiff.of(st, got), d2 = PatternSetDiff.of(bf, got),
                               d3 = PatternSetDiff.of(bf, st);
                if (!d1.isClean()) { badStatic++; if (example == null) example = "trial " + t + " step " + step + ": " + d1; }
                if (!d2.isClean()) badBrute++;
                if (!d3.isClean()) staticVsBrute++;
            }
            ndb += inc.ndbScans; case2 += inc.case2Builds; rebuild += inc.rebuilds;
        }
        System.out.printf("random trials=%d seed=%d comparisons=%d mismatches-vs-static=%d mismatches-vs-brute-force=%d "
                        + "static-vs-brute-force=%d paths: ndb-rescans=%d case2-builds=%d usp-reruns=%d%n",
                trials, seed, steps, badStatic, badBrute, staticVsBrute, ndb, case2, rebuild);
        if (example != null) System.out.println("first mismatch: " + example);
        return badStatic + badBrute + staticVsBrute;
    }

    // ------------------------------------------------------------------ real databases

    static int real(String name, int batches, String dir) throws Exception {
        Datasets.Entry e = null;
        for (Datasets.Entry x : Datasets.all()) if (x.name.equals(name)) e = x;
        if (e == null) throw new IllegalArgumentException("no database named " + name);
        List<Sequence> all = DatasetLoader.loadDataset(dir + name + "_seq.txt", dir + name + "_eui.txt");
        int n0 = e.dInitCount;
        List<Sequence> cur = new ArrayList<>(all.subList(0, n0));
        List<Sequence> rest = all.subList(n0, all.size());
        double su = e.defaultUut;
        IncUSPMinerPlusAlgorithm inc = new IncUSPMinerPlusAlgorithm(su);
        long t0 = System.nanoTime();
        inc.runInitialMining(new ArrayList<>(cur), SyntheticDb.tsu(cur));
        int bad = 0, steps = 0;
        bad += compareReal(name, 0, inc, cur, su);
        steps++;
        for (int b = 0; b < batches; b++) {
            int lo = (int) ((long) rest.size() * b / batches), hi = (int) ((long) rest.size() * (b + 1) / batches);
            if (lo == hi) continue;
            List<Sequence> ins = new ArrayList<>(rest.subList(lo, hi));
            cur.addAll(ins);
            inc.runIncrementalMining(ins, new ArrayList<>(cur), SyntheticDb.tsu(cur));
            bad += compareReal(name, b + 1, inc, cur, su);
            steps++;
        }
        System.out.printf("real %s uut=%s D_init=%d batches=%d comparisons=%d mismatches=%d "
                        + "paths: ndb-rescans=%d case2-builds=%d usp-reruns=%d (%.0f s)%n",
                name, su, n0, batches, steps, bad, inc.ndbScans, inc.case2Builds, inc.rebuilds,
                (System.nanoTime() - t0) / 1e9);
        return bad;
    }

    static int compareReal(String name, int batch, IncUSPMinerPlusAlgorithm inc, List<Sequence> cur, double su) {
        Map<String, Double> got = asStrings(inc.husps()), ref = staticHusps(cur, su);
        PatternSetDiff d = PatternSetDiff.of(ref, got);
        if (d.isClean()) return 0;
        System.out.printf("  %s batch %d: %s%n", name, batch, d);
        return 1;
    }

    // ------------------------------------------------------------------ references

    static Map<String, Double> asStrings(Map<Pattern, Double> m) {
        Map<String, Double> out = new HashMap<>(m.size() * 2);
        for (Map.Entry<Pattern, Double> e : m.entrySet()) out.put(e.getKey().toIntuitiveString(), e.getValue());
        return out;
    }

    static Map<String, Double> staticHusps(List<Sequence> db, double su) {
        PreHUSPMAdaptiveAlgo ref = new PreHUSPMAdaptiveAlgo(su, 0.5, 1e-4, 0.8, AblationMode.FULL, false);
        ref.runInitialMining(new ArrayList<>(db), SyntheticDb.tsu(db));
        return ref.exportLargeAsStrings();
    }

    /** Every pattern of every sequence with u(t,s) from its definition, summed over the database. */
    static Map<String, Double> bruteForceHusps(List<Sequence> db, double su) {
        Map<String, Double> total = new HashMap<>();
        double tu = 0;
        for (Sequence s : db) {
            tu += s.sequenceUtility;
            Map<String, Double> best = new HashMap<>();
            enumerate(s, 0, new ArrayList<>(), 0, best);
            for (Map.Entry<String, Double> e : best.entrySet()) total.merge(e.getKey(), e.getValue(), Double::sum);
        }
        double min = su * tu;
        Map<String, Double> out = new HashMap<>();
        for (Map.Entry<String, Double> e : total.entrySet()) if (e.getValue() >= min) out.put(e.getKey(), e.getValue());
        return out;
    }

    /** Extends the instance built so far with a non-empty item subset of an itemset after it. */
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
                String key = key(pat);
                double v = u + add;
                Double old = best.get(key);
                if (old == null || v > old) best.put(key, v);
                enumerate(s, j + 1, pat, v, best);
                pat.remove(pat.size() - 1);
            }
        }
    }

    private static String key(List<List<Integer>> pat) {
        Pattern p = new Pattern();
        for (List<Integer> el : pat) {
            p.addSExtension(el.get(0));
            for (int k = 1; k < el.size(); k++) p.addIExtension(el.get(k));
        }
        return p.toIntuitiveString();
    }
}
