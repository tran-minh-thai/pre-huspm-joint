package algorithms;

import common.*;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

/**
 * Baseline: HUSP-ULL (Gan, Lin, Zhang, Fournier-Viger, Chao, Yu, "Fast Utility Mining on
 * Complex Sequences", IEEE Transactions on Cybernetics, 2021), following Algorithms 1-3 of
 * the paper with both pruning strategies:
 * - IPS (irrelevant item pruning, Theorem 9; Algorithm 2, line 1): an item i of I(t)_rest is
 *   irrelevant when sum{PEU(t,s) | <t+i>_I in s or <t+i>_S in s} < threshold. It is removed
 *   from the UL-lists of t and of every super-sequence of t (the whole subtree of t), and the
 *   remaining utilities of t's projected sequences are recalculated.
 * - LAS (look-ahead strategy, Theorem 8; Algorithm 2, lines 2 and 6): an I- (S-) candidate i
 *   is discarded when sum{PEU(t,s) | <t+i>_I (resp. _S) in s} < threshold, using the PEU
 *   values after IPS. This is tested before the UL-list of <t+i> is built.
 * - Judge (Algorithm 3): builds PD(t'), outputs t' when u(t') >= threshold, and recurses
 *   when PEU(t') >= threshold.
 *
 * Data layout (Section 4.2): one copy of the database as UL-lists (item, utility, remaining
 * utility, next position of the same item) plus a header table of first occurrences per
 * sequence. A projected database stores positions only.
 *
 * Projected entries: the paper is ambiguous on whether a projected sequence holds one entry
 * per match or one per concatenation point. This implementation keeps ONE entry per
 * concatenation point holding the maximum utility of t ending there. u(t,s), PEU(t,s) and
 * every extension's utilities are maxima over matches, and the maximum over matches equals
 * the maximum over these per-position maxima, so the result is unchanged.
 *
 * Counters:
 * - candidateCount: every 1-item (before the SWU test) plus, at every PGrowth call, the
 *   distinct I-candidate items plus the distinct S-candidate items of PD(t), counted before
 *   this call's IPS and LAS. Items removed by IPS at an ancestor are no longer in the
 *   UL-lists and are not generated.
 * - exploredNodes: PGrowth calls (Algorithm 1, line 10 and Algorithm 3, line 7).
 * - prunedSWU: 1-items with SWU below the threshold (Algorithm 1, line 6).
 * - prunedIIP: items removed by IPS, summed over PGrowth calls.
 * - prunedLAR: candidate items (I and S counted separately) discarded by LAS.
 * - prunedPEU: extensions whose PD was built by Judge but whose PEU is below the threshold.
 * - listsBuilt: projected databases built by Judge (Algorithm 3, line 1).
 * - listEntries: projected entries created, including the lists of the 1-sequences.
 */
public class HUSPULLAlgorithm {

    // ========================================================================
    // 1. PROJECTED SEQUENCE: concatenation points of t in one sequence
    // ========================================================================
    static final class Proj {
        final int sid;
        final int[] pos;     // concatenation points, ascending flat positions
        final double[] u;    // maximum utility of t ending at each point
        double[] rest;       // remaining utility per position under the current IPS removals

        Proj(int sid, int[] pos, double[] u, double[] rest) {
            this.sid = sid;
            this.pos = pos;
            this.u = u;
            this.rest = rest;
        }
    }

    // ========================================================================
    // 2. RESULT AND MEASUREMENT COUNTERS
    // ========================================================================
    public double minUtility;
    public int patternCount = 0;

    public long candidateCount = 0;
    public long exploredNodes = 0;
    public long prunedSWU = 0;
    public long prunedLAR = 0;
    public long prunedIIP = 0;
    public long prunedPEU = 0;
    public long listsBuilt = 0;
    public long listEntries = 0;

    public Map<Pattern, Double> largeMap = new HashMap<>();

    // ========================================================================
    // 3. UL-LISTS OF THE DATABASE (released at the end of run)
    // ========================================================================
    private int[][] item;        // item id per flat position
    private double[][] util;     // utility per flat position
    private int[][] isEnd;       // last flat position of the itemset holding each position
    private int[][] next;        // next position of the same item, -1 if none
    private int[][] hdrItem;     // header table: distinct items, ascending
    private int[][] hdrFirst;    // header table: first occurrence of each distinct item
    private double[][] baseRest; // remaining utility per position with no item removed

    private boolean[] removed;    // items removed by IPS on the current search path
    private boolean[] removedNow; // items removed by IPS at the current PGrowth call

    // Scratch arrays indexed by item id, used only inside one candidate scan
    private double[] sumI, sumS, sumU;
    private int[] stampI, stampS, stampU;
    private boolean[] inI, inS, inU;
    private int[] listI, listS, listU;
    private int nListI, nListS, nListU;
    private int stamp = 0;

    // Scratch buffers indexed by entry, used only inside one Judge call
    private int[] posBuf;
    private double[] uBuf;

    public HUSPULLAlgorithm(double absoluteMinUtility) {
        this.minUtility = absoluteMinUtility;
    }

    // ========================================================================
    // 4. ALGORITHM 1: HUSP-ULL
    // ========================================================================
    public void run(List<Sequence> database) {
        largeMap.clear();
        patternCount = 0;
        candidateCount = 0;
        exploredNodes = 0;
        prunedSWU = 0;
        prunedLAR = 0;
        prunedIIP = 0;
        prunedPEU = 0;
        listsBuilt = 0;
        listEntries = 0;
        try {
            mine(database);
        } finally {
            releaseState();
        }
    }

    private void mine(List<Sequence> database) {
        // Line 1: compute u(s) and build the UL-list of each sequence
        int n = database.size();
        item = new int[n][];
        util = new double[n][];
        isEnd = new int[n][];
        next = new int[n][];
        hdrItem = new int[n][];
        hdrFirst = new int[n][];
        baseRest = new double[n][];
        double[] seqUtil = new double[n];
        int maxItem = 0, maxLen = 1;
        for (int s = 0; s < n; s++) {
            Sequence q = database.get(s);
            int len = 0;
            for (QItemset is : q.itemsets) len += is.items.size();
            int[] it = new int[len];
            double[] ut = new double[len];
            int[] end = new int[len];
            int k = 0;
            for (QItemset is : q.itemsets) {
                int start = k;
                for (QItem qi : is.items) {
                    it[k] = qi.id;
                    ut[k] = qi.utility;
                    if (qi.id > maxItem) maxItem = qi.id;
                    k++;
                }
                for (int z = start; z < k; z++) end[z] = k - 1;
            }
            double[] rest = new double[len];
            double run = 0;
            for (int z = len - 1; z >= 0; z--) {
                rest[z] = run;
                run += ut[z];
            }
            item[s] = it;
            util[s] = ut;
            isEnd[s] = end;
            baseRest[s] = rest;
            seqUtil[s] = run;
            if (len > maxLen) maxLen = len;
        }
        for (int s = 0; s < n; s++) buildHeaderAndNext(s);

        int sz = maxItem + 1;
        removed = new boolean[sz];
        removedNow = new boolean[sz];
        sumI = new double[sz]; sumS = new double[sz]; sumU = new double[sz];
        stampI = new int[sz]; stampS = new int[sz]; stampU = new int[sz];
        inI = new boolean[sz]; inS = new boolean[sz]; inU = new boolean[sz];
        listI = new int[sz]; listS = new int[sz]; listU = new int[sz];
        stamp = 0;
        posBuf = new int[maxLen];
        uBuf = new double[maxLen];

        // Line 5: SWU of every 1-sequence, and the sequences that contain each item
        double[] swu = new double[sz];
        int[] seqCount = new int[sz];
        for (int s = 0; s < n; s++) {
            for (int it : hdrItem[s]) {
                swu[it] += seqUtil[s];
                seqCount[it]++;
            }
        }
        int distinct = 0;
        for (int it = 0; it < sz; it++) if (seqCount[it] > 0) distinct++;
        // Every 1-item is a generated candidate, counted before the SWU test
        candidateCount += distinct;

        int[][] seqsOf = new int[sz][];
        for (int it = 0; it < sz; it++) if (seqCount[it] > 0) seqsOf[it] = new int[seqCount[it]];
        int[] fill = new int[sz];
        for (int s = 0; s < n; s++) {
            for (int it : hdrItem[s]) seqsOf[it][fill[it]++] = s;
        }

        // Lines 3-12, items in ascending (lexicographic) order
        for (int it = 0; it < sz; it++) {
            if (seqCount[it] == 0) continue;
            if (swu[it] < minUtility) { prunedSWU++; continue; } // line 6
            if (Thread.currentThread().isInterrupted()) return;
            // Line 4: PD(<i>), one entry per occurrence of i
            int[] sids = seqsOf[it];
            Proj[] pd = new Proj[sids.length];
            double u = 0;
            for (int x = 0; x < sids.length; x++) {
                int s = sids[x];
                int c = 0;
                double mx = 0;
                for (int k = firstOccurrence(s, it); k >= 0; k = next[s][k]) {
                    posBuf[c] = k;
                    uBuf[c] = util[s][k];
                    if (uBuf[c] > mx) mx = uBuf[c];
                    c++;
                }
                pd[x] = new Proj(s, Arrays.copyOf(posBuf, c), Arrays.copyOf(uBuf, c), baseRest[s]);
                listEntries += c;
                u += mx;
            }
            seqsOf[it] = null;
            Pattern pattern = new Pattern(it);
            if (u >= minUtility) savePattern(pattern, u); // lines 7-8
            pGrowth(pattern, pd, 1);                        // line 10
        }
    }

    private void buildHeaderAndNext(int s) {
        int[] it = item[s];
        int len = it.length;
        int[] nx = new int[len];
        Arrays.fill(nx, -1);
        // Last seen position of each item, scanned right to left
        Map<Integer, Integer> lastSeen = new HashMap<>();
        for (int k = len - 1; k >= 0; k--) {
            Integer later = lastSeen.put(it[k], k);
            if (later != null) nx[k] = later;
        }
        int[] keys = new int[lastSeen.size()];
        int c = 0;
        for (int key : lastSeen.keySet()) keys[c++] = key;
        Arrays.sort(keys);
        int[] first = new int[keys.length];
        for (int z = 0; z < keys.length; z++) first[z] = lastSeen.get(keys[z]);
        next[s] = nx;
        hdrItem[s] = keys;
        hdrFirst[s] = first;
    }

    /** Header table lookup: first position of the item in sequence s, or -1. */
    private int firstOccurrence(int s, int it) {
        int z = Arrays.binarySearch(hdrItem[s], it);
        return z >= 0 ? hdrFirst[s][z] : -1;
    }

    private void savePattern(Pattern pattern, double utility) {
        largeMap.put(pattern, utility);
        patternCount = largeMap.size();
    }

    // ========================================================================
    // 5. ALGORITHM 2: PGrowth
    // ========================================================================
    private void pGrowth(Pattern prefix, Proj[] pd, int depth) {
        // Depth cap: guards against StackOverflowError on very long sequences. It is a
        // safety net set well above the recursion depth reached on the evaluated datasets.
        if (depth > 2000) return;
        // Stop as soon as the runner cancels the run (timeout). This only fires after a
        // timeout, when the result is discarded, so mining logic is unchanged.
        if (Thread.currentThread().isInterrupted()) return;
        exploredNodes++;

        int m = pd.length;
        double[] peu = new double[m];
        for (int j = 0; j < m; j++) peu[j] = peuOf(pd[j]);

        // Candidate items of PD(prefix) with their PEU sums: per I side, per S side, and
        // for the union used by IPS.
        scanCandidates(pd, peu, true);
        candidateCount += nListI + nListS;

        // Line 1 (IPS): remove irrelevant items for the subtree of prefix
        int[] removedHere = null;
        int nRemovedHere = 0;
        for (int x = 0; x < nListU; x++) {
            int it = listU[x];
            if (sumU[it] < minUtility) {
                if (removedHere == null) removedHere = new int[nListU];
                removedHere[nRemovedHere++] = it;
            }
        }
        if (nRemovedHere > 0) {
            clearScan();
            for (int x = 0; x < nRemovedHere; x++) {
                removed[removedHere[x]] = true;
                removedNow[removedHere[x]] = true;
            }
            prunedIIP += nRemovedHere;
            // Recalculate the remaining utilities, then PEU(prefix, s)
            for (int j = 0; j < m; j++) {
                recalculateRest(pd[j]);
                peu[j] = peuOf(pd[j]);
            }
            for (int x = 0; x < nRemovedHere; x++) removedNow[removedHere[x]] = false;
            scanCandidates(pd, peu, false);
        }

        // Lines 2 and 6 (LAS): keep the candidates whose PEU sum reaches the threshold
        int[] cI = new int[nListI];
        int nI = 0;
        for (int x = 0; x < nListI; x++) {
            int it = listI[x];
            if (sumI[it] >= minUtility) cI[nI++] = it; else prunedLAR++;
        }
        int[] cS = new int[nListS];
        int nS = 0;
        for (int x = 0; x < nListS; x++) {
            int it = listS[x];
            if (sumS[it] >= minUtility) cS[nS++] = it; else prunedLAR++;
        }
        clearScan();
        Arrays.sort(cI, 0, nI);
        Arrays.sort(cS, 0, nS);

        // Lines 3-5 and 7-9: Judge every remaining candidate, I before S
        for (int x = 0; x < nI; x++) judge(prefix, pd, cI[x], true, depth);
        for (int x = 0; x < nS; x++) judge(prefix, pd, cS[x], false, depth);

        // Removals made here apply to the subtree of prefix only
        for (int x = 0; x < nRemovedHere; x++) removed[removedHere[x]] = false;
    }

    /** PEU(t, s): maximum over concatenation points of u + remaining utility. */
    private static double peuOf(Proj p) {
        double best = 0;
        double[] r = p.rest;
        for (int a = 0; a < p.pos.length; a++) {
            double v = p.u[a] + r[p.pos[a]];
            if (v > best) best = v;
        }
        return best;
    }

    /**
     * Scans PD(prefix) for candidate items not removed by IPS. I-candidates: items after a
     * concatenation point in the same itemset. S-candidates: items in itemsets after the
     * itemset of the start point (the first concatenation point). Each item adds PEU(t,s)
     * once per sequence to its I sum, its S sum and, when withUnion, its I-or-S sum.
     */
    private void scanCandidates(Proj[] pd, double[] peu, boolean withUnion) {
        for (int j = 0; j < pd.length; j++) {
            Proj p = pd[j];
            int[] it = item[p.sid];
            int[] end = isEnd[p.sid];
            double v = peu[j];
            if (++stamp == Integer.MAX_VALUE) resetStamps();
            int st = stamp;
            for (int a = 0; a < p.pos.length; a++) {
                int last = end[p.pos[a]];
                for (int k = p.pos[a] + 1; k <= last; k++) {
                    int x = it[k];
                    if (removed[x]) continue;
                    if (stampI[x] != st) {
                        stampI[x] = st;
                        if (!inI[x]) { inI[x] = true; listI[nListI++] = x; }
                        sumI[x] += v;
                    }
                    if (withUnion && stampU[x] != st) {
                        stampU[x] = st;
                        if (!inU[x]) { inU[x] = true; listU[nListU++] = x; }
                        sumU[x] += v;
                    }
                }
            }
            for (int k = end[p.pos[0]] + 1; k < it.length; k++) {
                int x = it[k];
                if (removed[x]) continue;
                if (stampS[x] != st) {
                    stampS[x] = st;
                    if (!inS[x]) { inS[x] = true; listS[nListS++] = x; }
                    sumS[x] += v;
                }
                if (withUnion && stampU[x] != st) {
                    stampU[x] = st;
                    if (!inU[x]) { inU[x] = true; listU[nListU++] = x; }
                    sumU[x] += v;
                }
            }
        }
    }

    private void clearScan() {
        for (int x = 0; x < nListI; x++) { int it = listI[x]; inI[it] = false; sumI[it] = 0; }
        for (int x = 0; x < nListS; x++) { int it = listS[x]; inS[it] = false; sumS[it] = 0; }
        for (int x = 0; x < nListU; x++) { int it = listU[x]; inU[it] = false; sumU[it] = 0; }
        nListI = 0;
        nListS = 0;
        nListU = 0;
    }

    private void resetStamps() {
        Arrays.fill(stampI, 0);
        Arrays.fill(stampS, 0);
        Arrays.fill(stampU, 0);
        stamp = 1;
    }

    /**
     * Recalculates the remaining utilities of one projected sequence over the items not
     * removed by IPS. Only positions from the start point on are read by t and its subtree.
     * The parent's array is kept when no item removed at this call occurs after the start point.
     */
    private void recalculateRest(Proj p) {
        int[] it = item[p.sid];
        double[] ut = util[p.sid];
        int from = p.pos[0];
        boolean affected = false;
        for (int k = from + 1; k < it.length; k++) {
            if (removedNow[it[k]]) { affected = true; break; }
        }
        if (!affected) return;
        double[] r = new double[it.length];
        double run = 0;
        for (int k = it.length - 1; k >= from; k--) {
            r[k] = run;
            if (!removed[it[k]]) run += ut[k];
        }
        p.rest = r;
    }

    // ========================================================================
    // 6. ALGORITHM 3: Judge
    // ========================================================================
    private void judge(Pattern prefix, Proj[] pd, int it, boolean iExt, int depth) {
        if (Thread.currentThread().isInterrupted()) return;
        // Line 1: PD(prefix') from PD(prefix), one entry per concatenation point
        listsBuilt++;
        Proj[] child = new Proj[pd.length];
        int nc = 0;
        double u = 0, peuT = 0;
        for (int j = 0; j < pd.length; j++) {
            Proj p = pd[j];
            int s = p.sid;
            int[] end = isEnd[s];
            double[] ut = util[s];
            int[] nx = next[s];
            int[] pos = p.pos;
            int np = pos.length;
            // Occurrences of it after the start point (I) or after its itemset (S)
            int bound = iExt ? pos[0] : end[pos[0]];
            int k = firstOccurrence(s, it);
            while (k >= 0 && k <= bound) k = nx[k];
            if (k < 0) continue;
            int c = 0;
            int a = 0;
            double runMax = Double.NEGATIVE_INFINITY;
            int runEnd = -1;
            for (; k >= 0; k = nx[k]) {
                if (iExt) {
                    // Best point in the same itemset as k and before k
                    while (a < np && pos[a] < k) {
                        if (end[pos[a]] != runEnd) { runEnd = end[pos[a]]; runMax = Double.NEGATIVE_INFINITY; }
                        if (p.u[a] > runMax) runMax = p.u[a];
                        a++;
                    }
                    if (runEnd != end[k]) continue;
                } else {
                    // Best point in an itemset strictly before the itemset of k
                    while (a < np && end[pos[a]] < k) {
                        if (p.u[a] > runMax) runMax = p.u[a];
                        a++;
                    }
                }
                posBuf[c] = k;
                uBuf[c] = runMax + ut[k];
                c++;
            }
            if (c == 0) continue;
            double mu = 0, mp = 0;
            double[] r = p.rest;
            for (int z = 0; z < c; z++) {
                if (uBuf[z] > mu) mu = uBuf[z];
                double v = uBuf[z] + r[posBuf[z]];
                if (v > mp) mp = v;
            }
            child[nc++] = new Proj(s, Arrays.copyOf(posBuf, c), Arrays.copyOf(uBuf, c), r);
            listEntries += c;
            u += mu;
            peuT += mp;
        }
        // Lines 2-8
        if (peuT >= minUtility) {
            Pattern np2 = iExt ? prefix.iConcatenate(it) : prefix.sConcatenate(it);
            if (u >= minUtility) savePattern(np2, u);
            pGrowth(np2, nc == child.length ? child : Arrays.copyOf(child, nc), depth + 1);
        } else {
            prunedPEU++;
        }
    }

    /** Drops the UL-lists and scratch arrays; the result map and counters are kept. */
    public void releaseState() {
        item = null; util = null; isEnd = null; next = null;
        hdrItem = null; hdrFirst = null; baseRest = null;
        removed = null;
        removedNow = null;
        sumI = null; sumS = null; sumU = null;
        stampI = null; stampS = null; stampU = null;
        inI = null; inS = null; inU = null;
        listI = null; listS = null; listU = null;
        nListI = 0; nListS = 0; nListU = 0;
        posBuf = null; uBuf = null;
    }

    public void exportLargePatterns(String outputPath) {
        if (outputPath == null) return;
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputPath))) {
            List<Map.Entry<Pattern, Double>> sortedEntries = new ArrayList<>(largeMap.entrySet());
            sortedEntries.sort((e1, e2) -> Double.compare(e2.getValue(), e1.getValue()));

            for (Map.Entry<Pattern, Double> entry : sortedEntries) {
                writer.write(entry.getKey().toIntuitiveString() + " #UTIL: " + entry.getValue());
                writer.newLine();
            }
        } catch (IOException e) {
            System.err.println("Error writing HUSP-ULL output file: " + e.getMessage());
        }
    }
}
