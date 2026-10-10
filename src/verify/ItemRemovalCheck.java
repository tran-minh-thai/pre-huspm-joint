package verify;

import algorithms.PreHUSPMAdaptiveAlgorithm.PreHUSPMAdaptiveAlgo;
import algorithms.PreHUSPMAdaptiveAlgorithm.PreHUSPMAdaptiveAlgo.AblationMode;
import algorithms.PreHUSPMAdaptiveAlgorithm.PreHUSPMAdaptiveAlgo.IipMode;
import common.DatasetLoader;
import common.Datasets;
import common.QItem;
import common.QItemset;
import common.Sequence;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

/**
 * Exactness check for irrelevant-item removal in the proposal's static miner (adapt.iip).
 *
 *   random TRIALS SEED
 *       Small random databases (frequent and rare items, several items per itemset, repeated
 *       items). Each database is mined with removal off, once and fixpoint through
 *       runInitialMining, and both the large set (cutoff UUT) and the full mined set (cutoff =
 *       floor) are compared with the brute-force enumerator. Also checks that mining leaves the
 *       sequences' flat arrays consistent with their itemsets.
 *
 *   real DATABASE [init|whole] [uut|floor]      (defaults: init, floor)
 *       Mines D_init (or the whole database) at the UUT or at the floor with off, once and
 *       fixpoint; the pattern sets and every utility must be identical. Prints explored nodes,
 *       candidates and removed items for each mode. Reads datasets from -Ddatasets.dir
 *       (default "datasets").
 *
 * Run -Dadapt.singlePassCap=0 to force the lazy child-building strategy on every node.
 * Exits 1 on any mismatch.
 */
public final class ItemRemovalCheck {

    private ItemRemovalCheck() {}

    static final IipMode[] MODES = {IipMode.OFF, IipMode.ONCE, IipMode.FIXPOINT};

    static final class Run {
        Map<String, Double> large, all;
        long explored, candidates, prunedIub, prunedValue, removed, removedRoot, ms;
    }

    /** Mines db with large cutoff tsu*su and mining cutoff tsu*(ratio*su), via the production entry. */
    static Run mine(List<Sequence> db, double tsu, double su, double ratio, IipMode mode) {
        PreHUSPMAdaptiveAlgo g = new PreHUSPMAdaptiveAlgo(su, 0.5, 1e-4, ratio, AblationMode.FULL, false);
        g.iipMode = mode;
        long t0 = System.nanoTime();
        g.runInitialMining(new ArrayList<>(db), tsu);
        Run r = new Run();
        r.ms = (System.nanoTime() - t0) / 1_000_000;
        r.large = g.exportLargeAsStrings();
        r.all = new HashMap<>(r.large);
        r.all.putAll(g.exportPrelargeAsStrings());
        r.explored = g.exploredNodes; r.candidates = g.candidateCount;
        r.prunedIub = g.prunedIub; r.prunedValue = g.prunedValueBound;
        r.removed = g.removedItems; r.removedRoot = g.removedItemsRoot;
        return r;
    }

    public static void main(String[] a) throws Exception {
        if (a.length == 0) { usage(); return; }
        int bad;
        switch (a[0]) {
            case "random": bad = random(Integer.parseInt(a[1]), Long.parseLong(a[2])); break;
            case "real": bad = real(a); break;
            default: usage(); bad = 1;
        }
        System.exit(bad > 0 ? 1 : 0);
    }

    static void usage() {
        System.out.println("usage: ItemRemovalCheck random TRIALS SEED | real DATABASE [init|whole] [uut|floor]");
    }

    // ------------------------------------------------------------------ random databases

    static final double[] SUS = {0.03, 0.05, 0.08, 0.12, 0.2, 0.3, 0.45};
    static final double[] RATIOS = {1.0, 0.8, 0.5};

    /**
     * 1-5 itemsets of 1-4 distinct items, at most 11 items per sequence, utilities 0..10 (0 with
     * probability 1/11). With probability 3/4 an item is one of five frequent items 1..5 (so items
     * repeat across itemsets), otherwise one of seven rare items 6..12 (so some items fall below
     * the cutoff at the root).
     */
    static String randSeq(Random r) {
        StringBuilder b = new StringBuilder();
        int nIs = 1 + r.nextInt(5), total = 0;
        for (int k = 0; k < nIs && total < 11; k++) {
            int nIt = Math.min(1 + r.nextInt(4), 11 - total);
            TreeSet<Integer> items = new TreeSet<>();
            while (items.size() < nIt) items.add(r.nextInt(4) < 3 ? 1 + r.nextInt(5) : 6 + r.nextInt(7));
            total += nIt;
            if (k > 0) b.append(" | ");
            boolean first = true;
            for (int it : items) {
                if (!first) b.append(' ');
                first = false;
                b.append(it).append('[').append(r.nextInt(11)).append(']');
            }
        }
        return b.toString();
    }

    /** Number of sequences whose flat arrays no longer match their itemsets. */
    static int corruptSequences(List<Sequence> db) {
        int bad = 0;
        for (Sequence s : db) {
            int pos = 0;
            boolean ok = s.flatItems != null;
            if (ok) {
                for (QItemset is : s.itemsets) for (int j = 0; j < is.items.size(); j++) {
                    QItem q = is.items.get(j);
                    if (pos >= s.flatLen || s.flatItems[pos] != q.id || s.flatUtilities[pos] != q.utility
                            || s.isItemsetEnd[pos] != (j == is.items.size() - 1)) ok = false;
                    pos++;
                }
                if (pos != s.flatLen) ok = false;
                double running = 0;
                for (int k = s.flatLen - 1; ok && k >= 0; k--) {
                    if (s.flatRemainingUtility[k] != running) ok = false;
                    running += s.flatUtilities[k];
                }
            }
            if (!ok) bad++;
        }
        return bad;
    }

    static int random(int trials, long seed) {
        Random r = new Random(seed);
        int m = MODES.length;
        int[] mism = new int[m];
        long[] missing = new long[m], extra = new long[m], explored = new long[m], cands = new long[m];
        long[] dbsRoot = new long[m], dbsNode = new long[m], rootRemoved = new long[m], nodeRemoved = new long[m];
        long[] dbsExploredBelowOff = new long[m];
        String[] firstBad = new String[m];
        long refAll = 0, refLarge = 0, dbsNonEmpty = 0, sumSeqs = 0, itemsets = 0, multi = 0, repSeqs = 0;
        long floorBelowUut = 0, corrupt = 0, offCounterDiff = 0;
        for (int t = 0; t < trials; t++) {
            SyntheticDb.resetIds();
            double su = SUS[r.nextInt(SUS.length)];
            double ratio = RATIOS[r.nextInt(RATIOS.length)];
            int n = 8 + r.nextInt(13);
            List<String> specs = new ArrayList<>();
            List<Sequence> db = new ArrayList<>();
            for (int i = 0; i < n; i++) { String sp = randSeq(r); specs.add(sp); db.add(SyntheticDb.seq(sp)); }
            sumSeqs += n;
            for (Sequence s : db) {
                Set<Integer> seen = new HashSet<>();
                boolean rep = false;
                for (QItemset is : s.itemsets) {
                    itemsets++;
                    if (is.items.size() > 1) multi++;
                    for (QItem q : is.items) if (!seen.add(q.id)) rep = true;
                }
                if (rep) repSeqs++;
            }
            if (ratio < 1.0) floorBelowUut++;
            double tsu = SyntheticDb.tsu(db);
            // Same products as the miner: tsu*su for the large set, tsu*(ratio*su) for the cutoff.
            Map<String, Double> bfLarge = IncUspVerifier.bruteForceHusps(db, su);
            Map<String, Double> bfAll = ratio == 1.0 ? bfLarge : IncUspVerifier.bruteForceHusps(db, ratio * su);
            refAll += bfAll.size(); refLarge += bfLarge.size();
            if (!bfAll.isEmpty()) dbsNonEmpty++;
            Run off = null;
            for (int c = 0; c < m; c++) {
                Run run = mine(db, tsu, su, ratio, MODES[c]);
                corrupt += corruptSequences(db);
                PatternSetDiff dAll = PatternSetDiff.of(bfAll, run.all);
                PatternSetDiff dLarge = PatternSetDiff.of(bfLarge, run.large);
                if (!dAll.isClean() || !dLarge.isClean()) {
                    mism[c]++; missing[c] += dAll.missing; extra[c] += dAll.extra;
                    if (firstBad[c] == null) firstBad[c] = "trial " + t + " su=" + su + " ratio=" + ratio
                            + " all: " + dAll + " large: " + dLarge + "\n      db: " + String.join(" ; ", specs);
                }
                explored[c] += run.explored; cands[c] += run.candidates;
                if (run.removedRoot > 0) dbsRoot[c]++;
                if (run.removed - run.removedRoot > 0) dbsNode[c]++;
                rootRemoved[c] += run.removedRoot; nodeRemoved[c] += run.removed - run.removedRoot;
                if (MODES[c] == IipMode.OFF) {
                    off = run;
                    if (run.removed != 0) offCounterDiff++;
                } else if (run.explored < off.explored) dbsExploredBelowOff[c]++;
            }
        }
        System.out.printf("random trials=%d seed=%d sequences=%d itemsets=%d (multi-item %d) sequences-with-a-repeated-item=%d "
                        + "databases-with-floor-below-UUT=%d reference-patterns: at-cutoff=%d at-UUT=%d databases-with->=1-pattern=%d%n",
                trials, seed, sumSeqs, itemsets, multi, repSeqs, floorBelowUut, refAll, refLarge, dbsNonEmpty);
        int bad = 0;
        for (int c = 0; c < m; c++) {
            System.out.printf("  %-8s mismatching-databases=%d missing=%d extra=%d | dbs-with-root-removal=%d root-removed=%d "
                            + "dbs-with-node-removal=%d node-removed=%d | explored=%d candidates=%d dbs-with-fewer-explored-than-off=%d%n",
                    MODES[c].name().toLowerCase(), mism[c], missing[c], extra[c], dbsRoot[c], rootRemoved[c],
                    dbsNode[c], nodeRemoved[c], explored[c], cands[c], dbsExploredBelowOff[c]);
            if (firstBad[c] != null) System.out.println("    first mismatch: " + firstBad[c]);
            bad += mism[c];
        }
        System.out.println("  sequences with flat arrays inconsistent with their itemsets after mining: " + corrupt);
        System.out.println("  off runs with a non-zero removal counter: " + offCounterDiff);
        bad += (int) Math.min(Integer.MAX_VALUE, corrupt + offCounterDiff);
        System.out.println("RESULT " + (bad == 0 ? "every mode matches the brute force on every database" : "MISMATCH"));
        return bad;
    }

    // ------------------------------------------------------------------ real databases

    static int real(String[] a) throws Exception {
        String name = a[1], scope = "init", level = "floor";
        for (int i = 2; i < a.length; i++) {
            switch (a[i]) {
                case "init": case "whole": scope = a[i]; break;
                case "uut": case "floor": level = a[i]; break;
                default: throw new IllegalArgumentException("unknown argument: " + a[i]);
            }
        }
        Datasets.Entry e = Datasets.byName(name);
        if (e == null) throw new IllegalArgumentException("no database named " + name + "; choose from " + Datasets.names());
        String dir = System.getProperty("datasets.dir", "datasets");
        List<Sequence> all = DatasetLoader.loadDataset(Paths.get(dir, name + "_seq.txt").toString(),
                Paths.get(dir, name + "_eui.txt").toString());
        List<Sequence> db = scope.equals("whole") ? all : new ArrayList<>(all.subList(0, Datasets.dInitCount(name, all.size())));
        double tsu = SyntheticDb.tsu(db);
        double su = e.defaultUut;
        double ratio = level.equals("floor") ? e.slMinRatio : 1.0;
        System.out.printf("real %s scope=%s |D|=%d TU=%.4f level=%s UUT=%s ratio=%s cutoff=%.4f singlePassCap=%s%n",
                name, scope, db.size(), tsu, level, su, ratio, tsu * (ratio * su),
                System.getProperty("adapt.singlePassCap", "default"));
        Run off = null;
        int bad = 0;
        for (IipMode mode : MODES) {
            Run r = mine(db, tsu, su, ratio, mode);
            String cmp;
            if (off == null) { off = r; cmp = "reference"; }
            else {
                PatternSetDiff d = PatternSetDiff.of(off.all, r.all);
                PatternSetDiff dl = PatternSetDiff.of(off.large, r.large);
                boolean exact = d.missing == 0 && d.extra == 0 && d.maxUtilityGap == 0.0
                        && dl.missing == 0 && dl.extra == 0;
                cmp = (exact ? "identical" : "DIFFERS") + " (missing=" + d.missing + " extra=" + d.extra
                        + " maxGap=" + d.maxUtilityGap + ")";
                if (!exact) bad++;
            }
            System.out.printf("  %-8s patterns=%d large=%d explored=%d candidates=%d prunedIub=%d prunedValue=%d "
                            + "removed=%d removedRoot=%d removedNode=%d | %s | development-machine probe time %d ms%n",
                    mode.name().toLowerCase(), r.all.size(), r.large.size(), r.explored, r.candidates, r.prunedIub,
                    r.prunedValue, r.removed, r.removedRoot, r.removed - r.removedRoot, cmp, r.ms);
        }
        System.out.println("RESULT " + (bad == 0 ? "every mode returns the identical pattern set" : "MISMATCH"));
        return bad;
    }
}
