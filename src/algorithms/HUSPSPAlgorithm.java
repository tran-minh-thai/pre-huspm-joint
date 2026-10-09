package algorithms;

import common.Pattern;
import common.QItem;
import common.QItemset;
import common.Sequence;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * Baseline: HUSP-SP (Zhang, Yang, Du, Gan, Yu; ACM TKDD 18(1), Article 5, 2024).
 *
 * ---------------------------------------------------------------------------
 * PROVENANCE
 *
 * Independent reimplementation from the pseudocode and definitions of Zhang et al.
 * (Definitions 4.5, 4.6, 4.7, 4.9, 4.11; Theorems 4.8, 4.10; Algorithms 1, 2, 3; the
 * IIP and EP strategies and the complexity analysis of Section 4.5). The authors' code
 * at github.com/DSI-Lab1/HUSPM has no license file, so it is not copied here; no line of
 * this file is taken from it.
 * ---------------------------------------------------------------------------
 *
 * WHAT IS IMPLEMENTED AS PUBLISHED
 *
 *  - seq-array (Definition 4.5): item, utility, remaining-utility and element-index
 *    arrays plus an item-indices table, one per q-sequence.
 *  - seqPro (Definition 4.6): a pointer to the seq-array plus the extension-list, one
 *    entry per extension position with its accumulated utility (acu).
 *  - Algorithm 1 and the root of the running example: items whose SWU is below the
 *    threshold are deleted from the database before the seq-arrays are built, so every
 *    remaining utility excludes them; the 1-item candidates then go through EP.
 *  - Algorithm 2 line 1 (IIP): RSU (Definition 4.9) of every extension item; items below
 *    the threshold are marked in one global array and their utility is removed from the
 *    remaining-utility array of the node's sequences. The subtree inherits the reduced
 *    array; it is restored when the node returns.
 *  - Algorithm 2 line 2 (EP): one scan of the reduced seqPro gives the I- and S-extension
 *    items and their look-ahead bound; candidates below the threshold are discarded.
 *  - Algorithm 3: utility and PEU (Equations (4)-(6), with u(S/I(t,p)) >= 0, so every
 *    extension position counts); the pattern is recorded BEFORE the PEU test.
 *
 * ONE DEPARTURE: the EP bound. Definition 4.11's TRSU is not an upper bound (see
 * trsuScan), so EP uses TRSU*(t+i, S) = max over prefix instances j and valid positions
 * q of i of acu_j + u(S/(q-1)). It never exceeds PEU(t, S) and never falls below
 * u(t+i+w, S) for any w. It equals the PEU of t+i, so with it the PEU test of Algorithm 3
 * line 6 does not prune beyond EP (prunedPEU stays at 0 up to rounding).
 *
 * Assumes every itemset is sorted by item id with no repeated id, and utilities are
 * already the product of quantity and external utility.
 *
 * ---------------------------------------------------------------------------
 * COUNTERS (each has one meaning)
 *
 *   candidateCount        every candidate generated, before any filter: at the root,
 *                         every distinct item of the database; at a node, the distinct
 *                         I-extension items plus the distinct S-extension items of its
 *                         seqPro (as reduced by its ancestors), before IIP and EP.
 *   prunedSWU             root items deleted because SWU < threshold.
 *   prunedIIP             node candidates (I or S) removed because RSU < threshold.
 *   prunedEP              candidates (root 1-items and node I/S candidates) discarded
 *                         because TRSU* < threshold.
 *   utilityCalculations   calls of Algorithm 3 (candidates that passed every filter).
 *   prunedPEU             calls of Algorithm 3 whose PEU < threshold (no PatternGrowth).
 *   exploredNodes         calls of Algorithm 2 below the root.
 *
 *   Identities: candidateCount = prunedSWU + prunedIIP + prunedEP + utilityCalculations,
 *               exploredNodes  = utilityCalculations - prunedPEU.
 *
 * Static algorithm with no incremental update; used only in the static scenarios.
 */
public class HUSPSPAlgorithm {

    // =====================================================================
    // 1. seq-array (Definition 4.5)
    // =====================================================================
    static final class SeqArray {
        final int[] item;          // dense item code at each flat position
        final double[] util;       // utility of the item at that position
        final double[] remUtil;    // utility of the non-deleted items AFTER that position
        final int[] elemStart;     // index of the first item of the itemset containing that position
        final int len;
        final double seqUtility;   // utility of the sequence after the SWU deletion
        final int[] keys;          // item-indices table: sorted distinct item codes...
        final int[][] positions;   // ...and their ascending positions

        SeqArray(int[] item, double[] util, int[] elemStart) {
            this.item = item;
            this.util = util;
            this.elemStart = elemStart;
            this.len = item.length;
            this.remUtil = new double[len];
            double acc = 0;
            for (int i = len - 1; i >= 0; i--) {
                remUtil[i] = acc;
                acc += util[i];
            }
            this.seqUtility = acc;

            int[] sorted = item.clone();
            Arrays.sort(sorted);
            int d = 0;
            for (int i = 0; i < sorted.length; i++) if (i == 0 || sorted[i] != sorted[i - 1]) sorted[d++] = sorted[i];
            this.keys = Arrays.copyOf(sorted, d);
            int[] count = new int[d];
            for (int x : item) count[Arrays.binarySearch(keys, x)]++;
            this.positions = new int[d][];
            for (int k = 0; k < d; k++) positions[k] = new int[count[k]];
            Arrays.fill(count, 0);
            for (int i = 0; i < len; i++) {
                int k = Arrays.binarySearch(keys, item[i]);
                positions[k][count[k]++] = i;
            }
        }

        int[] positionsOf(int code) {
            int k = Arrays.binarySearch(keys, code);
            return k < 0 ? null : positions[k];
        }

        /** u(S/i) for i >= from, on the current set of deleted items. */
        void recomputeRemaining(int from, boolean[] deleted) {
            double acc = 0;
            for (int i = len - 1; i >= from; i--) {
                remUtil[i] = acc;
                if (!deleted[item[i]]) acc += util[i];
            }
        }
    }

    // =====================================================================
    // 2. seqPro (Definition 4.6)
    // =====================================================================
    static final class SeqPro {
        final SeqArray sa;
        final int[] ex;            // extension positions, ascending (at most one per itemset)
        final double[] acu;        // utility of the best instance ending at each position
        final int size;
        double peu;                // PEU(t, S), set by utilityCalculation

        SeqPro(SeqArray sa, int[] ex, double[] acu, int size) {
            this.sa = sa; this.ex = ex; this.acu = acu; this.size = size;
        }
    }

    /** Growable int list used for the scratch arrays' touched entries. */
    private static final class IntList {
        int[] a = new int[16];
        int n = 0;
        void add(int x) { if (n == a.length) a = Arrays.copyOf(a, n * 2); a[n++] = x; }
    }

    private static final double NONE = Double.NEGATIVE_INFINITY;

    // =====================================================================
    // 3. Measurement counters (definitions in the class comment)
    // =====================================================================
    public double minUtility;
    public int patternCount = 0;
    public long candidateCount = 0;
    public long exploredNodes = 0;
    public long prunedSWU = 0;
    public long prunedIIP = 0;
    public long prunedEP = 0;
    public long prunedPEU = 0;
    public long utilityCalculations = 0;

    public Map<Pattern, Double> largeMap = new HashMap<>();

    /** Optional trace of intermediate values (SWU, RSU, TRSU*, u, PEU); null = off. */
    public Consumer<String> trace = null;

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

    // Item codes are dense ranks of the item ids (order preserved); codeToId maps back.
    private int[] codeToId;
    private List<SeqArray> db;
    // The global array of deleted items (Section 4.5), and per-item scratch arrays.
    private boolean[] deleted;
    private boolean[] seqSeen, nodeOn, candI, candS;
    private double[] rsu, seqMaxI, seqMaxS, trsuI, trsuS;
    private final IntList seqTouched = new IntList();
    private final IntList nodeTouched = new IntList();

    // =====================================================================
    // Algorithm 1 of Zhang et al., with the root as in the running example
    // =====================================================================
    public void run(List<Sequence> database) {
        cancelled = false;
        patternCount = 0; candidateCount = 0; exploredNodes = 0; utilityCalculations = 0;
        prunedSWU = 0; prunedIIP = 0; prunedEP = 0; prunedPEU = 0;
        largeMap.clear();

        // Dense item codes.
        Map<Integer, Integer> idToCode = new HashMap<>();
        for (Sequence s : database)
            for (QItemset is : s.itemsets)
                for (QItem it : is.items) idToCode.putIfAbsent(it.id, 0);
        int m = idToCode.size();
        codeToId = new int[m];
        int c = 0;
        for (int id : idToCode.keySet()) codeToId[c++] = id;
        Arrays.sort(codeToId);
        for (int k = 0; k < m; k++) idToCode.put(codeToId[k], k);
        allocateScratch(m);

        // Every 1-item is a candidate before any filter.
        candidateCount += m;

        // SWU of each item (= RSU of the 1-items, note after Definition 4.9).
        double[] swu = new double[m];
        for (Sequence s : database) {
            double su = 0;
            for (QItemset is : s.itemsets) for (QItem it : is.items) su += it.utility;
            for (QItemset is : s.itemsets)
                for (QItem it : is.items) {
                    int code = idToCode.get(it.id);
                    if (!seqSeen[code]) { seqSeen[code] = true; seqTouched.add(code); swu[code] += su; }
                }
            clearSeqSeen();
        }
        boolean[] keep = new boolean[m];
        for (int k = 0; k < m; k++) {
            keep[k] = swu[k] >= minUtility;
            if (!keep[k]) prunedSWU++;
        }
        if (trace != null) {
            trace.accept("SWU " + byId(swu, null) + "  deleted by SWU " + deletedIds(keep));
        }

        // Line 1: seq-arrays built without the low-SWU items (permanent deletion), so the
        // remaining utilities and u(S) exclude them.
        db = new ArrayList<>(database.size());
        for (Sequence s : database) {
            SeqArray sa = buildSeqArray(s, idToCode, keep);
            if (sa != null) db.add(sa);
        }

        // Root EP (Algorithm 2 line 2 on the empty prefix): TRSU* of a 1-item in S is the
        // utility of S from the item's first occurrence onwards.
        double[] rootTrsu = new double[m];
        for (SeqArray sa : db) {
            for (int k = 0; k < sa.keys.length; k++) {
                int q1 = sa.positions[k][0];
                rootTrsu[sa.keys[k]] += (q1 == 0) ? sa.seqUtility : sa.remUtil[q1 - 1];
            }
        }
        if (trace != null) trace.accept("root TRSU* " + byId(rootTrsu, keep));

        for (int code = 0; code < m; code++) {
            if (!keep[code]) continue;
            checkCancelled();
            if (rootTrsu[code] < minUtility) { prunedEP++; continue; }
            utilityCalculation(new Pattern(codeToId[code]), projectSingleItem(code));
        }
    }

    private void allocateScratch(int m) {
        deleted = new boolean[m];
        seqSeen = new boolean[m]; nodeOn = new boolean[m];
        candI = new boolean[m]; candS = new boolean[m];
        rsu = new double[m]; trsuI = new double[m]; trsuS = new double[m];
        seqMaxI = new double[m]; seqMaxS = new double[m];
        Arrays.fill(seqMaxI, NONE);
        Arrays.fill(seqMaxS, NONE);
        seqTouched.n = 0; nodeTouched.n = 0;
    }

    private static SeqArray buildSeqArray(Sequence s, Map<Integer, Integer> idToCode, boolean[] keep) {
        int n = 0;
        for (QItemset is : s.itemsets)
            for (QItem it : is.items) if (keep[idToCode.get(it.id)]) n++;
        if (n == 0) return null;
        int[] item = new int[n];
        double[] util = new double[n];
        int[] elemStart = new int[n];
        int k = 0;
        for (QItemset is : s.itemsets) {
            int start = k;
            for (QItem it : is.items) {
                int code = idToCode.get(it.id);
                if (!keep[code]) continue;
                item[k] = code; util[k] = it.utility; elemStart[k] = start; k++;
            }
        }
        return new SeqArray(item, util, elemStart);
    }

    /** seqPro of a 1-item pattern: each occurrence is one instance. */
    private List<SeqPro> projectSingleItem(int code) {
        List<SeqPro> out = new ArrayList<>();
        for (SeqArray sa : db) {
            int[] pos = sa.positionsOf(code);
            if (pos == null) continue;
            double[] acu = new double[pos.length];
            for (int j = 0; j < pos.length; j++) acu[j] = sa.util[pos[j]];
            out.add(new SeqPro(sa, pos, acu, pos.length));
        }
        return out;
    }

    // =====================================================================
    // Algorithm 3 of Zhang et al. - UtilityCalculation
    // =====================================================================
    private void utilityCalculation(Pattern p, List<SeqPro> proj) {
        utilityCalculations++;
        double util = 0, peu = 0;
        for (SeqPro sp : proj) {
            double best = 0, bestPeu = 0;
            for (int j = 0; j < sp.size; j++) {
                if (sp.acu[j] > best) best = sp.acu[j];
                // Equation (4): u(t,p,S) + u(S/I(t,p)), u(S/I(t,p)) >= 0 always holds.
                double v = sp.acu[j] + sp.sa.remUtil[sp.ex[j]];
                if (v > bestPeu) bestPeu = v;
            }
            sp.peu = bestPeu;
            util += best;
            peu += bestPeu;
        }
        if (trace != null) trace.accept("UC " + p.toIntuitiveString() + " u=" + util + " PEU=" + peu);

        // Record the result BEFORE the PEU test (lines 3-5, then 6-8).
        if (util >= minUtility) {
            largeMap.put(p, util);
            patternCount = largeMap.size();
        }
        if (peu >= minUtility) patternGrowth(p, proj);
        else prunedPEU++;
    }

    // =====================================================================
    // Algorithm 2 of Zhang et al. - PatternGrowth
    // =====================================================================
    private void patternGrowth(Pattern prefix, List<SeqPro> proj) {
        checkCancelled();
        exploredNodes++;

        // ---- Line 1, first scan: RSU of every extension item, and the raw candidates.
        // An item at a non-deleted position after the first extension position p1 is an
        // I-extension item if an instance lies earlier in its itemset, and an S-extension
        // item if its itemset is after that of p1. RSU(t+i, S) = PEU(t, S) (Definition 4.9).
        int nI = 0, nS = 0;
        for (SeqPro sp : proj) {
            SeqArray sa = sp.sa;
            int p1 = sp.ex[0], e1 = sa.elemStart[p1];
            int ptr = 0, lastElem = -1;
            for (int q = p1 + 1; q < sa.len; q++) {
                int it = sa.item[q];
                if (deleted[it]) continue;
                while (ptr < sp.size && sp.ex[ptr] < q) lastElem = sa.elemStart[sp.ex[ptr++]];
                if (!seqSeen[it]) {
                    seqSeen[it] = true; seqTouched.add(it);
                    if (!nodeOn[it]) { nodeOn[it] = true; rsu[it] = 0; nodeTouched.add(it); }
                    rsu[it] += sp.peu;
                }
                if (lastElem == sa.elemStart[q] && !candI[it]) { candI[it] = true; nI++; }
                if (sa.elemStart[q] > e1 && !candS[it]) { candS[it] = true; nS++; }
            }
            clearSeqSeen();
        }
        candidateCount += nI + nS;

        // ---- Line 1, IIP: mark the items with RSU < threshold as deleted for this subtree.
        int[] removed = new int[nodeTouched.n];
        int nRemoved = 0;
        TreeMap<Integer, Double> rsuTrace = (trace != null) ? new TreeMap<>() : null;
        for (int k = 0; k < nodeTouched.n; k++) {
            int it = nodeTouched.a[k];
            if (rsuTrace != null) rsuTrace.put(codeToId[it], rsu[it]);
            if (rsu[it] < minUtility) {
                deleted[it] = true;
                removed[nRemoved++] = it;
                prunedIIP += (candI[it] ? 1 : 0) + (candS[it] ? 1 : 0);
            }
            nodeOn[it] = false; candI[it] = false; candS[it] = false;
        }
        nodeTouched.n = 0;
        if (trace != null) {
            List<Integer> r = new ArrayList<>();
            for (int k = 0; k < nRemoved; k++) r.add(codeToId[removed[k]]);
            r.sort(null);
            trace.accept("PG " + prefix.toIntuitiveString() + " RSU " + rsuTrace + " IIP-deleted " + r);
        }

        // ---- Line 1, second scan: delete their utility from the remaining-utility array.
        if (nRemoved > 0) for (SeqPro sp : proj) sp.sa.recomputeRemaining(sp.ex[0], deleted);

        // ---- Line 2: scan the reduced seqPro for the I/S-extension items and apply EP.
        for (SeqPro sp : proj) trsuScan(sp);
        int[] iList = collect(trsuI, true);
        int[] sList = collect(trsuS, false);

        // ---- Lines 3-8: I-extensions, then S-extensions.
        for (int it : iList) {
            checkCancelled();
            utilityCalculation(prefix.iConcatenate(codeToId[it]), project(proj, it, true));
        }
        for (int it : sList) {
            checkCancelled();
            utilityCalculation(prefix.sConcatenate(codeToId[it]), project(proj, it, false));
        }

        // Restore the items and remaining utilities of the parent.
        if (nRemoved > 0) {
            for (int k = 0; k < nRemoved; k++) deleted[removed[k]] = false;
            for (SeqPro sp : proj) sp.sa.recomputeRemaining(sp.ex[0], deleted);
        }
    }

    /**
     * EP bound of every extension item in one sequence, in one left-to-right scan.
     *
     * Replaces TRSU (Definition 4.11) by TRSU*(t+i, S) = max over prefix instances j and
     * valid positions q of i of acu_j + u(S/(q-1)), using a running max of acu. The
     * published TRSU is not an upper bound (Theorem 4.12 fails): for
     * S = <{a}:1,{b}:1,{a}:50,{e}:1,{b}:1,{e}:1> and t = <a,b>, TRSU(<a,b,e>) = 55 - 50 = 5,
     * yet u(<a,b,e>) = 52 (instance a@3, b@5, e@6).
     */
    private void trsuScan(SeqPro sp) {
        SeqArray sa = sp.sa;
        int p1 = sp.ex[0];
        int ptr = 0;
        double runMaxS = NONE;            // max acu over instances in earlier itemsets
        double pendAcu = NONE;            // the latest instance before q...
        int pendElem = -1;                // ...and its itemset
        for (int q = p1 + 1; q < sa.len; q++) {
            int it = sa.item[q];
            if (deleted[it]) continue;
            while (ptr < sp.size && sp.ex[ptr] < q) {
                if (pendAcu > runMaxS) runMaxS = pendAcu;
                pendAcu = sp.acu[ptr];
                pendElem = sa.elemStart[sp.ex[ptr]];
                ptr++;
            }
            double bestS = runMaxS, bestI = NONE;
            if (pendElem == sa.elemStart[q]) bestI = pendAcu;
            else if (pendAcu > bestS) bestS = pendAcu;
            double tail = sa.remUtil[q - 1];  // u(S/(q-1)): utility from q onwards
            if (bestI != NONE || bestS != NONE) {
                if (seqMaxI[it] == NONE && seqMaxS[it] == NONE) seqTouched.add(it);
                if (bestI != NONE && bestI + tail > seqMaxI[it]) seqMaxI[it] = bestI + tail;
                if (bestS != NONE && bestS + tail > seqMaxS[it]) seqMaxS[it] = bestS + tail;
            }
        }
        for (int k = 0; k < seqTouched.n; k++) {
            int it = seqTouched.a[k];
            if (!nodeOn[it]) { nodeOn[it] = true; trsuI[it] = NONE; trsuS[it] = NONE; nodeTouched.add(it); }
            if (seqMaxI[it] != NONE) trsuI[it] = (trsuI[it] == NONE ? 0 : trsuI[it]) + seqMaxI[it];
            if (seqMaxS[it] != NONE) trsuS[it] = (trsuS[it] == NONE ? 0 : trsuS[it]) + seqMaxS[it];
            seqMaxI[it] = NONE; seqMaxS[it] = NONE;
        }
        seqTouched.n = 0;
    }

    /**
     * EP on one side: returns the candidates with TRSU* >= threshold in ascending item
     * order and counts the others. The S side runs second and clears the node scratch.
     */
    private int[] collect(double[] trsu, boolean iSide) {
        int[] keep = new int[nodeTouched.n];
        int n = 0;
        TreeMap<Integer, Double> tr = (trace != null) ? new TreeMap<>() : null;
        for (int k = 0; k < nodeTouched.n; k++) {
            int it = nodeTouched.a[k];
            if (trsu[it] == NONE) continue;
            if (tr != null) tr.put(codeToId[it], trsu[it]);
            if (trsu[it] >= minUtility) keep[n++] = it;
            else prunedEP++;
        }
        if (tr != null) trace.accept("   TRSU*-" + (iSide ? "I " : "S ") + tr);
        if (!iSide) {
            for (int k = 0; k < nodeTouched.n; k++) nodeOn[nodeTouched.a[k]] = false;
            nodeTouched.n = 0;
        }
        int[] out = Arrays.copyOf(keep, n);
        Arrays.sort(out);
        return out;
    }

    /**
     * seqPro of prefix+item (Algorithm 3 lines 1-2): for each position q of the item, the
     * best acu among the prefix instances it can extend, by a two-pointer merge of the
     * item-indices table with the extension-list.
     */
    private List<SeqPro> project(List<SeqPro> proj, int code, boolean iExtension) {
        List<SeqPro> out = new ArrayList<>();
        for (SeqPro sp : proj) {
            SeqArray sa = sp.sa;
            int[] pos = sa.positionsOf(code);
            if (pos == null) continue;
            int p1 = sp.ex[0];
            int[] ex = null;
            double[] acu = null;
            int n = 0, ptr = 0, pendElem = -1;
            double runMaxS = NONE, pendAcu = NONE;
            for (int q : pos) {
                if (q <= p1) continue;
                while (ptr < sp.size && sp.ex[ptr] < q) {
                    if (pendAcu > runMaxS) runMaxS = pendAcu;
                    pendAcu = sp.acu[ptr];
                    pendElem = sa.elemStart[sp.ex[ptr]];
                    ptr++;
                }
                double best;
                if (iExtension) best = (pendElem == sa.elemStart[q]) ? pendAcu : NONE;
                else best = (pendElem == sa.elemStart[q] || pendAcu < runMaxS) ? runMaxS : pendAcu;
                if (best == NONE) continue;
                if (ex == null) { ex = new int[pos.length]; acu = new double[pos.length]; }
                ex[n] = q; acu[n] = best + sa.util[q]; n++;
            }
            if (n > 0) out.add(new SeqPro(sa, ex, acu, n));
        }
        return out;
    }

    private void clearSeqSeen() {
        for (int k = 0; k < seqTouched.n; k++) seqSeen[seqTouched.a[k]] = false;
        seqTouched.n = 0;
    }

    private String byId(double[] v, boolean[] only) {
        TreeMap<Integer, Double> m = new TreeMap<>();
        for (int k = 0; k < v.length; k++) if (only == null || only[k]) m.put(codeToId[k], v[k]);
        return m.toString();
    }

    private String deletedIds(boolean[] keep) {
        List<Integer> r = new ArrayList<>();
        for (int k = 0; k < keep.length; k++) if (!keep[k]) r.add(codeToId[k]);
        return r.toString();
    }

    private void checkCancelled() {
        if (cancelled) throw new CancellationSignal();
    }
}
