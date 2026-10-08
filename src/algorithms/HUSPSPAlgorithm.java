package algorithms;

import common.Pattern;
import common.QItem;
import common.QItemset;
import common.Sequence;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Baseline: HUSP-SP (Zhang, Yang, Du, Gan, Yu; ACM TKDD 2023).
 *
 * ---------------------------------------------------------------------------
 * PROVENANCE
 *
 * Independent reimplementation from the pseudocode and definitions of Zhang et al.
 * (Definitions 4.5, 4.6, 4.7, 4.9, 4.11; Theorems 4.8, 4.10, 4.12; Algorithms 1, 2, 3).
 * The authors' code at github.com/DSI-Lab1/HUSPM has no license file, so it is not
 * copied here; no line of this file is taken from it.
 * ---------------------------------------------------------------------------
 *
 * THE THREE MAIN COMPONENTS OF HUSP-SP
 *
 * 1. seq-array (Definition 4.5). Each q-sequence is flattened into four parallel
 *    arrays of equal length: item name, item utility, utility of everything after
 *    that position, and the index of the first item of the itemset containing that
 *    position. An item-indices table maps each item to its list of positions.
 *
 * 2. seqPro (Definition 4.6). The projected database of a node: a pointer to the
 *    original seq-array plus an extension-list holding the instances of the current
 *    pattern, each with its accumulated utility (acu) and extension position (exIndex).
 *    Unlike the UL-list of HUSP-ULL, it does not store empty slots.
 *
 * 3. Three upper bounds, from loosest to tightest: PEU, RSU and TRSU.
 *    TRSU subtracts the utility of the IRRELEVANT segment between the first
 *    extension position of the prefix and the first occurrence of the new item.
 *    That segment contributes to PEU but cannot contribute to any extension of the
 *    new pattern, so the result is still a valid upper bound.
 *
 * Two pruning strategies: IIP (by RSU, inherited from HUSP-ULL) and EP (by TRSU).
 *
 * ---------------------------------------------------------------------------
 * ORDER IN ALGORITHM 3 (stated by Zhang et al.)
 *
 * The result must be RECORDED BEFORE the PEU pruning condition is checked. The
 * reverse order misses patterns: a pattern can be a HUSP while its PEU is too
 * small to extend it further.
 * ---------------------------------------------------------------------------
 *
 * COUNTERS FOR A FAIR COMPARISON WITH THE OTHER BASELINES
 *   candidateCount  counts the extensions generated, BEFORE any pruning, so it
 *                   measures the search frontier (at the 1-item level only the
 *                   items that pass the SWU filter are counted).
 *   exploredNodes   counts entries into the recursion, i.e. patterns actually
 *                   expanded after pruning. This measures pruning effectiveness.
 *
 * Static algorithm with no incremental update; used only in the static scenarios
 * SC1 and SC3.
 */
public class HUSPSPAlgorithm {

    // =====================================================================
    // 1. seq-array (Definition 4.5)
    // =====================================================================
    static final class SeqArray {
        final int seqId;
        final int[] item;          // item name at each flat position
        final double[] util;       // utility of the item at that position
        final double[] remUtil;    // utility of everything AFTER that position
        final int[] elemStart;     // index of the first item of the itemset containing that position
        final int len;
        /** item -> the flat positions where it occurs (ascending). */
        final Map<Integer, int[]> itemIndices;
        final double seqUtility;

        SeqArray(Sequence s) {
            this.seqId = s.id;
            int n = 0;
            for (QItemset is : s.itemsets) n += is.items.size();
            this.len = n;
            this.item = new int[n];
            this.util = new double[n];
            this.remUtil = new double[n];
            this.elemStart = new int[n];

            int k = 0;
            for (QItemset is : s.itemsets) {
                int start = k;
                for (QItem it : is.items) {
                    item[k] = it.id;
                    util[k] = it.utility;
                    elemStart[k] = start;
                    k++;
                }
            }
            // remUtil is a suffix sum: the utility of everything after position i.
            double acc = 0;
            for (int i = n - 1; i >= 0; i--) {
                remUtil[i] = acc;
                acc += util[i];
            }
            this.seqUtility = acc;

            Map<Integer, List<Integer>> tmp = new HashMap<>();
            for (int i = 0; i < n; i++) {
                tmp.computeIfAbsent(item[i], x -> new ArrayList<>()).add(i);
            }
            Map<Integer, int[]> idx = new HashMap<>(tmp.size() * 2);
            for (Map.Entry<Integer, List<Integer>> e : tmp.entrySet()) {
                List<Integer> v = e.getValue();
                int[] a = new int[v.size()];
                for (int i = 0; i < a.length; i++) a[i] = v.get(i);
                idx.put(e.getKey(), a);
            }
            this.itemIndices = idx;
        }

        /** true if positions a and b belong to the SAME itemset. */
        boolean sameElement(int a, int b) { return elemStart[a] == elemStart[b]; }
    }

    // =====================================================================
    // 2. seqPro (Definition 4.6)
    // =====================================================================
    /**
     * One instance of the pattern in a q-sequence: the utility accumulated up to the
     * extension position, and that extension position.
     */
    static final class Ins {
        final double acu;
        final int exIndex;
        Ins(double acu, int exIndex) { this.acu = acu; this.exIndex = exIndex; }
    }

    static final class SeqPro {
        final SeqArray sa;
        final List<Ins> exList;    // sorted by ascending exIndex
        SeqPro(SeqArray sa, List<Ins> exList) { this.sa = sa; this.exList = exList; }

        /** u(t, s): utility of the pattern in this sequence = max over its instances. */
        double patternUtility() {
            double best = 0;
            for (Ins in : exList) if (in.acu > best) best = in.acu;
            return best;
        }

        /**
         * PEU(t, s) as in Definition 4.7: max over the instances of
         * (acu + remaining utility), and 0 when nothing is left to extend.
         */
        double peu() {
            double best = 0;
            for (Ins in : exList) {
                double rem = sa.remUtil[in.exIndex];
                if (rem <= 0) continue;              // nothing left to extend
                double v = in.acu + rem;
                if (v > best) best = v;
            }
            return best;
        }

        /** FIRST extension position of the pattern in this sequence. */
        int firstExIndex() { return exList.isEmpty() ? -1 : exList.get(0).exIndex; }

        /**
         * Whether PEU is attained at the FIRST instance. This decides whether TRSU
         * uses the subtracting case or falls back to RSU (Definition 4.11).
         */
        boolean peuAttainedAtFirst() {
            if (exList.isEmpty()) return false;
            Ins f = exList.get(0);
            double rf = sa.remUtil[f.exIndex];
            if (rf <= 0) return false;
            double first = f.acu + rf;
            return first >= peu() - EPS;
        }
    }

    private static final double EPS = 1e-9;

    // =====================================================================
    // 3. Measurement counters
    // =====================================================================
    public double minUtility;
    public int patternCount = 0;
    public long candidateCount = 0;   // extensions generated, before pruning
    public long exploredNodes = 0;    // counted on entry to the recursion
    public long prunedSWU = 0;        // items dropped by SWU in the first scan
    public long prunedIIP = 0;        // candidates dropped by IIP (RSU)
    public long prunedEP = 0;         // candidates dropped by EP (TRSU)
    public long prunedPEU = 0;        // branches stopped by the PEU bound in Algorithm 3

    public Map<Pattern, Double> largeMap = new HashMap<>();

    /** Lets the runner stop the algorithm on timeout, like the other baselines. */
    private volatile boolean cancelled = false;
    public void cancel() { this.cancelled = true; }
    public boolean isCancelled() { return cancelled; }

    public static final class CancellationSignal extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public CancellationSignal() { super("HUSP-SP cancelled by timeout", null, false, false); }
    }

    public HUSPSPAlgorithm(double absoluteMinUtility) {
        this.minUtility = absoluteMinUtility;
    }

    private List<SeqArray> db;

    // =====================================================================
    // Algorithm 1 of Zhang et al.
    // =====================================================================
    public void run(List<Sequence> database) {
        cancelled = false;
        patternCount = 0; candidateCount = 0; exploredNodes = 0;
        prunedSWU = 0; prunedIIP = 0; prunedEP = 0; prunedPEU = 0;
        largeMap.clear();

        // Step 1: build a seq-array for each q-sequence.
        db = new ArrayList<>(database.size());
        for (Sequence s : database) {
            SeqArray sa = new SeqArray(s);
            if (sa.len > 0) db.add(sa);
        }

        // Step 2: items whose SWU is below the threshold are not used as 1-item
        // patterns. For a single-item pattern RSU equals SWU (note after Definition 4.9),
        // so this is IIP applied to the whole database, as in the running example of
        // Zhang et al. The items stay in the seq-arrays.
        Map<Integer, Double> swu = new HashMap<>();
        for (SeqArray sa : db) {
            for (Integer it : sa.itemIndices.keySet()) {
                swu.merge(it, sa.seqUtility, Double::sum);
            }
        }
        Set<Integer> promising = new HashSet<>();
        for (Map.Entry<Integer, Double> e : swu.entrySet()) {
            if (e.getValue() >= minUtility) promising.add(e.getKey());
            else prunedSWU++;
        }

        // Step 3: for each promising item, build its seqPro and call UtilityCalculation.
        // The empty pattern has no instances, so it is set up directly here; this is the
        // loop Algorithm 2 would run on the empty prefix.
        for (Integer it : promising) {
            checkCancelled();
            candidateCount++;
            List<SeqPro> proj = projectSingleItem(it);
            if (proj.isEmpty()) continue;
            Pattern p = new Pattern(it);
            utilityCalculation(p, proj);
        }
    }

    /** seqPro of a single-item pattern: each occurrence is one instance. */
    private List<SeqPro> projectSingleItem(int item) {
        List<SeqPro> out = new ArrayList<>();
        for (SeqArray sa : db) {
            int[] pos = sa.itemIndices.get(item);
            if (pos == null) continue;
            List<Ins> ex = new ArrayList<>(pos.length);
            for (int p : pos) ex.add(new Ins(sa.util[p], p));
            out.add(new SeqPro(sa, ex));
        }
        return out;
    }

    // =====================================================================
    // Algorithm 3 of Zhang et al. - UtilityCalculation
    // =====================================================================
    private void utilityCalculation(Pattern prefix, List<SeqPro> proj) {
        double util = 0, peu = 0;
        for (SeqPro sp : proj) {
            util += sp.patternUtility();
            peu += sp.peu();
        }

        // RECORD THE RESULT FIRST. Reversing the order misses patterns, because a
        // pattern can be a HUSP while its PEU is too small to go further.
        if (util >= minUtility) {
            largeMap.put(prefix.clonePattern(), util);
            patternCount = largeMap.size();
        }

        if (peu >= minUtility) {
            patternGrowth(prefix, proj);
        } else {
            prunedPEU++;
        }
    }

    // =====================================================================
    // Algorithm 2 of Zhang et al. - PatternGrowth
    // =====================================================================
    private void patternGrowth(Pattern prefix, List<SeqPro> proj) {
        checkCancelled();
        exploredNodes++;

        // --- IIP (by RSU) ---
        // RSU of an extension item = sum of PEU(prefix, s) over the sequences s that
        // still contain the item after an extension position. Items below the threshold
        // are skipped as extensions of this prefix; the seqPro itself is not modified.
        Map<Integer, Double> rsu = new HashMap<>();
        for (SeqPro sp : proj) {
            double peuS = sp.peu();
            if (peuS <= 0) continue;
            // items that still occur after the first extension position
            int from = sp.firstExIndex();
            Set<Integer> seen = new HashSet<>();
            for (int i = from + 1; i < sp.sa.len; i++) {
                if (seen.add(sp.sa.item[i])) rsu.merge(sp.sa.item[i], peuS, Double::sum);
            }
            // items in the same itemset as an extension position are I-extension candidates too
            for (Ins in : sp.exList) {
                int es = sp.sa.elemStart[in.exIndex];
                for (int i = es; i < sp.sa.len && sp.sa.elemStart[i] == es; i++) {
                    if (i > in.exIndex && seen.add(sp.sa.item[i])) {
                        rsu.merge(sp.sa.item[i], peuS, Double::sum);
                    }
                }
            }
        }
        // --- Collect I-extension and S-extension candidates, with their TRSU for EP ---
        // Enumerated BEFORE filtering. candidateCount counts the raw search frontier, as
        // for every other baseline; otherwise HUSP-SP would report a smaller number only
        // because it prunes earlier, which is not what the counter compares.
        Map<Integer, Double> trsuI = new HashMap<>();
        Map<Integer, Double> trsuS = new HashMap<>();
        for (SeqPro sp : proj) {
            accumulateTrsu(sp, null, trsuI, true);
            accumulateTrsu(sp, null, trsuS, false);
        }
        candidateCount += trsuI.size();
        candidateCount += trsuS.size();

        // --- IIP, then EP, in the order of Algorithm 2 ---
        expand(prefix, proj, trsuI, rsu, true);
        expand(prefix, proj, trsuS, rsu, false);
    }

    /**
     * Applies IIP and then EP to one extension side, then recurses.
     *
     * IIP prunes by RSU, EP by TRSU. Since TRSU <= RSU, EP alone would be as tight;
     * both are kept and counted separately to report the share of each strategy,
     * as Zhang et al. present them.
     */
    private void expand(Pattern prefix, List<SeqPro> proj, Map<Integer, Double> trsu,
                        Map<Integer, Double> rsu, boolean iExtension) {
        for (Map.Entry<Integer, Double> e : trsu.entrySet()) {
            checkCancelled();
            int item = e.getKey();
            Double r = rsu.get(item);
            if (r == null || r < minUtility) { prunedIIP++; continue; }   // IIP
            if (e.getValue() < minUtility)   { prunedEP++;  continue; }   // EP
            List<SeqPro> child = project(proj, item, iExtension);
            if (child.isEmpty()) continue;
            Pattern p = iExtension ? prefix.iConcatenate(item) : prefix.sConcatenate(item);
            utilityCalculation(p, child);
        }
    }

    /**
     * Accumulates TRSU (Definition 4.11) for every possible extension item in one sequence.
     *
     * When PEU is attained at the FIRST instance, the segment between the first
     * extension position of the PREFIX and the first occurrence of the NEW ITEM cannot
     * contribute to any extension of the new pattern, so subtracting it from PEU still
     * gives an upper bound:
     *
     *     TRSU = PEU(t,s) - [ u(s / p1) - u(s / (q1 - 1)) ]
     *
     * where p1 is the first extension position of the prefix and q1 the first valid
     * position of the new item. Otherwise TRSU falls back to RSU, i.e. PEU(t,s).
     */
    private void accumulateTrsu(SeqPro sp, Set<Integer> allowed,
                                Map<Integer, Double> out, boolean iExtension) {
        // allowed == null: enumerate every item without filtering. Used while counting
        // the raw search frontier, before IIP and EP are applied.
        double peuS = sp.peu();
        if (peuS <= 0) return;
        boolean tighten = sp.peuAttainedAtFirst();
        int p1 = sp.firstExIndex();
        double remP1 = sp.sa.remUtil[p1];

        // First valid position of each extension item in this sequence.
        Map<Integer, Integer> firstPos = new HashMap<>();
        for (Ins in : sp.exList) {
            if (iExtension) {
                // I-extension: the item must be in the SAME itemset as the extension
                // position, and after it.
                int es = sp.sa.elemStart[in.exIndex];
                for (int i = in.exIndex + 1; i < sp.sa.len && sp.sa.elemStart[i] == es; i++) {
                    int it = sp.sa.item[i];
                    if (allowed != null && !allowed.contains(it)) continue;
                    Integer cur = firstPos.get(it);
                    if (cur == null || i < cur) firstPos.put(it, i);
                }
            } else {
                // S-extension: the item must be in an itemset AFTER that of the extension position.
                int es = sp.sa.elemStart[in.exIndex];
                int i = in.exIndex + 1;
                while (i < sp.sa.len && sp.sa.elemStart[i] == es) i++;
                for (; i < sp.sa.len; i++) {
                    int it = sp.sa.item[i];
                    if (allowed != null && !allowed.contains(it)) continue;
                    Integer cur = firstPos.get(it);
                    if (cur == null || i < cur) firstPos.put(it, i);
                }
            }
        }

        for (Map.Entry<Integer, Integer> e : firstPos.entrySet()) {
            double contrib = peuS;
            if (tighten) {
                int q1 = e.getValue();
                double remBeforeQ1 = (q1 - 1 >= 0) ? sp.sa.remUtil[q1 - 1] : sp.sa.seqUtility;
                double cut = remP1 - remBeforeQ1;
                if (cut > 0) contrib = peuS - cut;
                if (contrib < 0) contrib = 0;
            }
            out.merge(e.getKey(), contrib, Double::sum);
        }
    }

    /**
     * Projects the seqPro of the prefix to the seqPro of the pattern extended by one item.
     * The utility of a pattern in a sequence is the MAXIMUM over its instances, but every
     * instance must be kept because any of them can lead to a better extension later.
     * That is why exList keeps all instances, not only the best one.
     */
    private List<SeqPro> project(List<SeqPro> proj, int item, boolean iExtension) {
        List<SeqPro> out = new ArrayList<>();
        for (SeqPro sp : proj) {
            int[] pos = sp.sa.itemIndices.get(item);
            if (pos == null) continue;

            // For each position of the item, keep the LARGEST acu among the prefix
            // instances that can be extended to that position.
            List<Ins> next = null;
            for (int q : pos) {
                double best = -1;
                for (Ins in : sp.exList) {
                    if (iExtension) {
                        if (q > in.exIndex && sp.sa.sameElement(q, in.exIndex)) {
                            if (in.acu > best) best = in.acu;
                        }
                    } else {
                        if (sp.sa.elemStart[q] > sp.sa.elemStart[in.exIndex]) {
                            if (in.acu > best) best = in.acu;
                        }
                    }
                }
                if (best >= 0) {
                    if (next == null) next = new ArrayList<>();
                    next.add(new Ins(best + sp.sa.util[q], q));
                }
            }
            if (next != null && !next.isEmpty()) out.add(new SeqPro(sp.sa, next));
        }
        return out;
    }

    private void checkCancelled() {
        if (cancelled) throw new CancellationSignal();
    }
}
