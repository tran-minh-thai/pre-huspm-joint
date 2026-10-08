package algorithms;

import common.*;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

/**
 * Baseline: Pre-HUSPM (Yan et al., J. Supercomputing 2025), zero-allocation implementation.
 *
 * ───────────────────────────────────────────────────────────────────────────
 * PRUNING BOUND = projected SWU (the SWU computed on the projected database):
 *   For a prefix X in one sequence the quantity is max over occurrences of
 *   (prefixUtil + restUtil); summed over sequences,
 *   SWU_proj(X) = Σ_{s⊇X} max_occ(prefixUtil + restUtil) = PEU(X).
 *   The global SWU of Yan et al., Definition 8 (Σ SU(s)), is not used: it is a much
 *   looser bound and makes the candidate set explode.
 *   A pattern is pruned when SWU_proj(X) < Sl·TSU (downward closure: no extension of X
 *   can be pre-large).
 *   * HUSP (Yan et al., Definition 10):           su(X) ≥ Su·TSU   (su = actual utility).
 *   * Pre-large PSWU (Yan et al., Definition 12): Sl·TSU ≤ SWU_proj(X) < Su·TSU.
 *   * Buffer = LSWU ∪ PSWU = every candidate with SWU_proj ≥ Sl·TSU, stored as (SWU_proj, su).
 *
 * INCREMENTAL RESCAN TEST - two variants, both as stated by Yan et al.:
 *
 *   Common budget: f = ((Su-Sl)/(1-Su))·TSU_lastRescan.
 *
 *   Variant.TSU (Yan et al., Theorem 1), arm "Pre-HUSPM-TSU":
 *     the quantity compared with f is TSU(d) = Σ_{s∈d} u(s), the total utility of the batch.
 *     SOUND. The utility gained by a pattern X over a batch is the sum of the
 *     contributions of every inserted sequence containing X, and that sum is bounded
 *     by TSU(d).
 *
 *   Variant.SWUMAX (Yan et al., Theorem 2), arm "Pre-HUSPM":
 *     the quantity compared with f is SWUmax(d) = max_{s∈d} u(s), the utility of ONE sequence.
 *     NOT SOUND. The proof of Theorem 2 relies on Eq. (14) of Yan et al.,
 *         TSU_d ≥ SWUmax ≥ SWU_d(X),
 *     but SWUmax is the utility of a single sequence while SWU_d(X) sums over EVERY
 *     sequence of d that contains X, so the second inequality does not hold.
 *     Counterexample: Su=0.25, Sl=0.20, TSU(D)=100, u(X,D)=19. Inserting two sequences
 *     that both contain X with u=6 gives SWUmax=6 ≤ f=6.67, so no rescan is made, yet
 *     u(X,D')=31 > 0.25·112=28, i.e. X has become a HUSP.
 *
 *   Both variants use the cumulative form (buf, accumulated since the last rescan),
 *   which is stricter than the published test that looks at each batch in isolation.
 *
 * ───────────────────────────────────────────────────────────────────────────
 * ZERO-ALLOCATION (memory layout only; it does not change which patterns are mined):
 *   * Tracker uses primitive arrays (int[] flatIndices, double[] utility); no
 *     List<Instance> or Instance objects inside the recursion.
 *   * Object pools (TrackerPool + TrackerListPool) with warmup, so the recursion only
 *     borrows and releases.
 *   * O(1) scratch buffer merge by flatIdx; two passes (pass 1 collects the distinct
 *     extension items; pass 2 recurses into each child and releases it), so memory
 *     grows with O(depth).
 *   * Uses the flat representation of Sequence (flatItems/flatUtilities/isItemsetEnd/
 *     flatRemainingUtility).
 * ───────────────────────────────────────────────────────────────────────────
 */
public class PreHUSPMAlgorithm {

    // ========================================================================
    // 1. TRACKER - primitive parallel arrays (no Instance objects, no List)
    // ========================================================================
    static final class Tracker {
        int seqId;
        int[] flatIndices;
        double[] utility;
        int size = 0;
        double exactUtility = 0;   // local su = max prefix utility within one sequence
        double peu = 0;            // local SWU_proj = max(prefixUtil + restUtil) within one sequence

        Tracker(int id) {
            this.seqId = id;
            this.flatIndices = new int[4];
            this.utility = new double[4];
        }

        void reset(int newSeqId) {
            this.seqId = newSeqId; this.size = 0; this.exactUtility = 0; this.peu = 0;
        }

        /** Appends one position; the caller guarantees flatIdx is unique within the sequence. */
        void append(int flatIdx, double u, Sequence seq) {
            if (size == flatIndices.length) grow();
            flatIndices[size] = flatIdx;
            utility[size] = u;
            size++;
            if (u > exactUtility) exactUtility = u;
            double p = u + seq.getRestUtilityFlat(flatIdx);
            if (p > peu) peu = p;
        }

        private void grow() {
            int nc = flatIndices.length << 1;
            flatIndices = Arrays.copyOf(flatIndices, nc);
            utility = Arrays.copyOf(utility, nc);
        }
    }

    static final class TrackerList {
        final ArrayList<Tracker> trackers = new ArrayList<>();
        boolean isEmpty() { return trackers.isEmpty(); }
    }

    // ========================================================================
    // 2. PRE-HUSPM CORE
    // ========================================================================
    public static class PreHUSPMAlgo {

        /** The two rescan tests of Yan et al. See the class comment. */
        public enum Variant {
            /** Theorem 1: compares TSU(d). Sound. */
            TSU,
            /** Theorem 2: compares SWUmax(d). Not sound; kept as the published baseline. */
            SWUMAX
        }

        public double Su, Sl;
        /** Default: SWUMAX, the test of Theorem 2 used by the "Pre-HUSPM" arm. */
        public Variant variant = Variant.SWUMAX;
        public double safetyBoundF = 0;
        /** Quantity compared with f in the latest batch (written to the CSV for diagnosis). */
        public double lastTriggerQuantity = 0;
        /** buf after the update of the latest batch. */
        public double bufValue = 0;
        public int maxDepth = 1000;

        public Map<Pattern, Double> largeMap = new HashMap<>();      // HUSP: su ≥ Su·TSU
        public Map<Pattern, Double> prelargeMap = new HashMap<>();   // PSWU: Sl·TSU ≤ SWU_proj < Su·TSU
        private Map<Pattern, double[]> swuBuffer = new HashMap<>();  // LSWU∪PSWU → {SWU_proj, su}
        private double buf = 0.0;                                    // trigger quantity accumulated since the last rescan

        public int rescanCount = 0, patternCount = 0;
        public int prelargeCount = 0;
        public long candidateCount = 0;
        public long exploredNodes = 0; // nodes entered by expandNode, including those then pruned by the bound
        public long prunedPEU = 0;

        private boolean writeToFile;

        public PreHUSPMAlgo(double Su, double SlRatio, boolean writeToFile) {
            this(Su, SlRatio, writeToFile, Variant.SWUMAX);
        }

        public PreHUSPMAlgo(double Su, double SlRatio, boolean writeToFile, Variant variant) {
            this.variant = variant;
            this.Su = Su;
            this.Sl = SlRatio * Su;
            this.writeToFile = writeToFile;
        }

        // ---- Sequence lookup by seqId through an array (no Integer boxing) ----
        private Sequence[] dbLookupArr = new Sequence[16];
        private void dbPut(Sequence seq) {
            int sid = seq.id;
            if (sid >= dbLookupArr.length)
                dbLookupArr = Arrays.copyOf(dbLookupArr, Math.max(sid + 1, dbLookupArr.length * 2));
            dbLookupArr[sid] = seq;
        }
        private Sequence dbGet(int seqId) {
            return (seqId < 0 || seqId >= dbLookupArr.length) ? null : dbLookupArr[seqId];
        }
        private void dbClear() { Arrays.fill(dbLookupArr, null); }

        // ---- Object pools ----
        private static final int POOL_MAX_SIZE = 1 << 16;
        private static final int POOL_TRACKER_ARRAY_CAP = 512;
        private static final int POOL_WARMUP_TRACKERS = 4096;

        static final class TrackerPool {
            final Tracker[] pool = new Tracker[POOL_MAX_SIZE];
            int top = 0;
            Tracker borrow(int seqId) {
                if (top == 0) return new Tracker(seqId);
                Tracker t = pool[--top]; pool[top] = null; t.reset(seqId); return t;
            }
            void release(Tracker t) {
                if (t.flatIndices.length > POOL_TRACKER_ARRAY_CAP) { t.flatIndices = new int[4]; t.utility = new double[4]; }
                if (top < POOL_MAX_SIZE) pool[top++] = t;
            }
            void warmup(int n) { while (top < n && top < POOL_MAX_SIZE) pool[top++] = new Tracker(-1); }
        }
        static final class TrackerListPool {
            final TrackerList[] pool = new TrackerList[POOL_MAX_SIZE];
            int top = 0;
            private final TrackerPool trackerPool;
            TrackerListPool(TrackerPool tp) { this.trackerPool = tp; }
            TrackerList borrow() { return top == 0 ? new TrackerList() : pop(); }
            private TrackerList pop() { TrackerList tl = pool[--top]; pool[top] = null; return tl; }
            void release(TrackerList tl) {
                ArrayList<Tracker> ts = tl.trackers;
                for (int i = 0, n = ts.size(); i < n; i++) trackerPool.release(ts.get(i));
                ts.clear();
                if (top < POOL_MAX_SIZE) pool[top++] = tl;
            }
            void warmup(int n) { while (top < n && top < POOL_MAX_SIZE) pool[top++] = new TrackerList(); }
        }
        private transient final TrackerPool trackerPool = new TrackerPool();
        private transient final TrackerListPool trackerListPool = new TrackerListPool(trackerPool);
        private Tracker borrowTracker(int seqId) { return trackerPool.borrow(seqId); }
        private TrackerList borrowTrackerList() { return trackerListPool.borrow(); }
        private void releaseTrackerList(TrackerList tl) { trackerListPool.release(tl); }

        // ---- Slots by itemId: children are collected directly in a single pass over
        //      the parent, as in the published algorithm ----
        private transient TrackerList[] iSlot, sSlot;   // iSlot[itemId] = TrackerList of the I-extension child
        private transient int[] usedItemI, usedItemS;   // itemIds touched in this pass (for selective reset)
        private int maxItemId = -1;
        private int maxFlatLen = 0;
        private void ensureItemScratch(int sz) {
            if (iSlot == null || iSlot.length < sz) {
                iSlot = new TrackerList[sz]; sSlot = new TrackerList[sz];
                usedItemI = new int[sz]; usedItemS = new int[sz];
            }
        }

        // ---- Per-depth arrays: children are copied out before recursing so slot[] is not overwritten ----
        private transient List<int[]> iItemPool = new ArrayList<>();
        private transient List<int[]> sItemPool = new ArrayList<>();
        private transient List<TrackerList[]> iTLPool = new ArrayList<>();
        private transient List<TrackerList[]> sTLPool = new ArrayList<>();
        private int[] borrowIntArr(List<int[]> pool, int depth, int sz) {
            while (pool.size() <= depth) pool.add(null);
            int[] a = pool.get(depth);
            if (a == null || a.length < sz) { a = new int[sz]; pool.set(depth, a); }
            return a;
        }
        private TrackerList[] borrowTLArr(List<TrackerList[]> pool, int depth, int sz) {
            while (pool.size() <= depth) pool.add(null);
            TrackerList[] a = pool.get(depth);
            if (a == null || a.length < sz) { a = new TrackerList[sz]; pool.set(depth, a); }
            return a;
        }

        // ====================================================================
        // PHASE 1: INITIAL MINING
        // ====================================================================
        public void runInitialMining(List<Sequence> dInit, double currentTotalUtility) {
            rescanCount = 0; patternCount = 0; prelargeCount = 0;
            candidateCount = 0; exploredNodes = 0; prunedPEU = 0; buf = 0.0;
            largeMap.clear(); prelargeMap.clear(); swuBuffer.clear();
            executeRescanAndRebuild(dInit, currentTotalUtility, true);
        }

        // ====================================================================
        // PHASE 2: INCREMENTAL MINING (rescan test of the selected variant)
        // ====================================================================
        public void runIncrementalMining(List<Sequence> deltaD, List<Sequence> dSoFar, double newTotalUtility) {
            // Reset per batch (as in the adaptive algorithm) so the counters describe this batch only:
            // a safe-update batch mines nothing and reports 0; a rescan batch reports that re-mine.
            candidateCount = 0; exploredNodes = 0; prunedPEU = 0;

            // The quantity compared with f depends on the variant (see the class comment).
            //   TSU    : TSU(d) = Σ_{s∈d} u(s)      - Theorem 1, sound
            //   SWUMAX : max_{s∈d} u(s)             - Theorem 2, not sound
            double swuMax = 0, tsuD = 0;
            for (Sequence seq : deltaD) {
                tsuD += seq.sequenceUtility;
                if (seq.sequenceUtility > swuMax) swuMax = seq.sequenceUtility;
            }
            double trigger = (variant == Variant.TSU) ? tsuD : swuMax;
            this.lastTriggerQuantity = trigger;

            if (buf + trigger > safetyBoundF) {
                executeRescanAndRebuild(dSoFar, newTotalUtility, false);
                buf = 0.0;
            } else {
                double newAbsSu = newTotalUtility * Su;
                double newAbsSl = newTotalUtility * Sl;

                Map<Pattern, double[]> updated = new HashMap<>();
                for (Map.Entry<Pattern, double[]> e : swuBuffer.entrySet()) {
                    Pattern p = e.getKey();
                    double projSwu = e.getValue()[0];
                    double su      = e.getValue()[1];
                    for (Sequence seq : deltaD) {
                        double[] c = evaluatePatternUtility(p, seq); // {su, SWU_proj}
                        su      += c[0];
                        projSwu += c[1];
                    }
                    if (projSwu >= newAbsSl) updated.put(p, new double[]{projSwu, su});
                }
                swuBuffer = updated;
                deriveMaps(newAbsSu, newAbsSl);
                buf += trigger;
            }
            this.bufValue = buf;
            this.patternCount = largeMap.size();
            this.prelargeCount = prelargeMap.size();
        }

        private void updateSafetyBound(double totalUtilityD) {
            this.safetyBoundF = ((Su - Sl) / (1.0 - Su)) * totalUtilityD;
        }

        private void executeRescanAndRebuild(List<Sequence> currentFullDB, double newTotalUtility, boolean isInitial) {
            if (!isInitial) rescanCount++;
            updateSafetyBound(newTotalUtility);
            double absSu = newTotalUtility * Su;
            double absSl = newTotalUtility * Sl;
            swuBuffer = mineFromScratch(currentFullDB, absSl);
            deriveMaps(absSu, absSl);
        }

        /** Derives largeMap (HUSP, Definition 10) and prelargeMap (PSWU, Definition 12) from the buffer. */
        private void deriveMaps(double absSu, double absSl) {
            largeMap.clear(); prelargeMap.clear();
            for (Map.Entry<Pattern, double[]> e : swuBuffer.entrySet()) {
                double projSwu = e.getValue()[0], su = e.getValue()[1];
                if (su >= absSu) largeMap.put(e.getKey(), su);
                else if (projSwu >= absSl && projSwu < absSu) prelargeMap.put(e.getKey(), su);
            }
            patternCount = largeMap.size();
            prelargeCount = prelargeMap.size();
        }

        // ====================================================================
        // MINING FROM SCRATCH - zero-allocation
        // ====================================================================
        private Map<Pattern, double[]> mineFromScratch(List<Sequence> db, double minSl) {
            dbClear();
            maxItemId = -1; maxFlatLen = 0;
            for (Sequence s : db) {
                if (s.flatItems == null) s.buildFlatRepresentation();
                dbPut(s);
                if (s.flatLen > maxFlatLen) maxFlatLen = s.flatLen;
                int[] fi = s.flatItems;
                for (int k = 0; k < s.flatLen; k++) if (fi[k] > maxItemId) maxItemId = fi[k];
            }
            ensureItemScratch(maxItemId + 2);
            trackerPool.warmup(POOL_WARMUP_TRACKERS);
            trackerListPool.warmup(1024);

            Map<Pattern, double[]> results = new HashMap<>();

            // ---- L1: group by item in one scan (flat Tracker, no Instance) ----
            Map<Integer, TrackerList> l1 = new HashMap<>();
            for (Sequence seq : db) {
                int sid = seq.id, flatLen = seq.flatLen;
                int[] fi = seq.flatItems; double[] fu = seq.flatUtilities;
                for (int k = 0; k < flatLen; k++) {
                    int item = fi[k];
                    TrackerList tl = l1.get(item);
                    if (tl == null) { tl = new TrackerList(); l1.put(item, tl); }
                    Tracker t;
                    int n = tl.trackers.size();
                    if (n > 0 && tl.trackers.get(n - 1).seqId == sid) t = tl.trackers.get(n - 1);
                    else { t = new Tracker(sid); tl.trackers.add(t); }
                    t.append(k, fu[k], seq);
                }
            }

            for (Map.Entry<Integer, TrackerList> e : l1.entrySet()) {
                Pattern p = new Pattern(e.getKey());
                expandNode(p, e.getValue(), minSl, results, 1);
            }
            return results;
        }

        /**
         * Two-pass zero-allocation recursion with the SWU_proj (= PEU) bound.
         *  Pass 1: scans the parent exactly once and collects children directly into
         *          iSlot/sSlot[itemId] (Tracker/TrackerList borrowed from the pools;
         *          one Tracker per seqId per item).
         *  Pass 2: copies the children into per-depth arrays (so slot[] is not
         *          overwritten), recurses into each child, then releases it to the pool.
         *  All extensions are collected in one pass, as in the published algorithm.
         */
        private void expandNode(Pattern currentPattern, TrackerList parentTrackers, double minSl,
                                Map<Pattern, double[]> results, int depth) {
            if (depth > maxDepth) return;
            if (Thread.currentThread().isInterrupted()) return;
            if (parentTrackers == null || parentTrackers.isEmpty()) return;

            exploredNodes++;  // counted on entry, before the SWU_proj check below
            candidateCount++; // one generated extension = one candidate. Pre-HUSPM materializes EVERY
                              //   extension, so candidate_count = explored_nodes (the adaptive algorithm
                              //   filters with EUCS before materializing)

            final ArrayList<Tracker> ptList = parentTrackers.trackers;
            final int ptSize = ptList.size();

            double projSwuD = 0, suD = 0;
            for (int i = 0; i < ptSize; i++) {
                Tracker t = ptList.get(i);
                suD += t.exactUtility;
                projSwuD += t.peu;
            }

            if (projSwuD < minSl) { prunedPEU++; return; }
            results.put(currentPattern.clonePattern(), new double[]{projSwuD, suD});

            // ===== PASS 1: single pass - collect I-ext and S-ext children directly into slot[] =====
            int usedICount = 0, usedSCount = 0;
            for (int pi = 0; pi < ptSize; pi++) {
                Tracker t = ptList.get(pi);
                final int sid = t.seqId;
                Sequence seq = dbGet(sid);
                if (seq == null) continue;
                final int[] flatItems = seq.flatItems;
                final double[] flatUtils = seq.flatUtilities;
                final boolean[] isItemsetEnd = seq.isItemsetEnd;
                final int flatLen = seq.flatLen;
                final int tSize = t.size; final int[] tFlat = t.flatIndices; final double[] tUtil = t.utility;
                for (int k = 0; k < tSize; k++) {
                    int p = tFlat[k];
                    double iu = tUtil[k];
                    // I-ext: same itemset, after p (up to isItemsetEnd)
                    if (!isItemsetEnd[p]) {
                        for (int q = p + 1; q < flatLen; q++) {
                            int item = flatItems[q];
                            TrackerList tl = iSlot[item];
                            if (tl == null) { tl = borrowTrackerList(); iSlot[item] = tl; usedItemI[usedICount++] = item; }
                            ArrayList<Tracker> ts = tl.trackers; int n = ts.size();
                            Tracker ct = (n > 0 && ts.get(n - 1).seqId == sid) ? ts.get(n - 1) : null;
                            if (ct == null) { ct = borrowTracker(sid); ts.add(ct); }
                            ct.append(q, iu + flatUtils[q], seq);
                            if (isItemsetEnd[q]) break;
                        }
                    }
                    // S-ext: itemsets after the itemset of p
                    int boundary = p; while (boundary < flatLen && !isItemsetEnd[boundary]) boundary++;
                    for (int q = boundary + 1; q < flatLen; q++) {
                        int item = flatItems[q];
                        TrackerList tl = sSlot[item];
                        if (tl == null) { tl = borrowTrackerList(); sSlot[item] = tl; usedItemS[usedSCount++] = item; }
                        ArrayList<Tracker> ts = tl.trackers; int n = ts.size();
                        Tracker ct = (n > 0 && ts.get(n - 1).seqId == sid) ? ts.get(n - 1) : null;
                        if (ct == null) { ct = borrowTracker(sid); ts.add(ct); }
                        ct.append(q, iu + flatUtils[q], seq);
                    }
                }
            }

            // ===== Copy children into per-depth arrays and clear slot[] (so it is not overwritten) =====
            int[] itemsI = null; TrackerList[] tlsI = null;
            if (usedICount > 0) {
                itemsI = borrowIntArr(iItemPool, depth, usedICount);
                tlsI = borrowTLArr(iTLPool, depth, usedICount);
                for (int u = 0; u < usedICount; u++) { int item = usedItemI[u]; itemsI[u] = item; tlsI[u] = iSlot[item]; iSlot[item] = null; }
            }
            int[] itemsS = null; TrackerList[] tlsS = null;
            if (usedSCount > 0) {
                itemsS = borrowIntArr(sItemPool, depth, usedSCount);
                tlsS = borrowTLArr(sTLPool, depth, usedSCount);
                for (int u = 0; u < usedSCount; u++) { int item = usedItemS[u]; itemsS[u] = item; tlsS[u] = sSlot[item]; sSlot[item] = null; }
            }

            // ===== PASS 2: recurse into each child, then release it to the pool =====
            for (int u = 0; u < usedICount; u++) {
                currentPattern.addIExtension(itemsI[u]);
                expandNode(currentPattern, tlsI[u], minSl, results, depth + 1);
                currentPattern.removeLastIExtension();
                releaseTrackerList(tlsI[u]);
            }
            for (int u = 0; u < usedSCount; u++) {
                currentPattern.addSExtension(itemsS[u]);
                expandNode(currentPattern, tlsS[u], minSl, results, depth + 1);
                currentPattern.removeLastSExtension();
                releaseTrackerList(tlsS[u]);
            }
        }

        // ====================================================================
        // evaluatePatternUtility - {su, SWU_proj} of p in one sequence (the frontier
        // is kept in reused parallel arrays; no List or Instance).
        // ====================================================================
        private int[] evCurIs = new int[16], evCurIi = new int[16];
        private double[] evCurU = new double[16];
        private int[] evNxtIs = new int[16], evNxtIi = new int[16];
        private double[] evNxtU = new double[16];
        private int evCurN = 0, evNxtN = 0;
        private final double[] evalOut = new double[2];

        private void evPushCur(int is, int ii, double u) {
            if (evCurN == evCurIs.length) {
                int nc = evCurN << 1;
                evCurIs = Arrays.copyOf(evCurIs, nc); evCurIi = Arrays.copyOf(evCurIi, nc); evCurU = Arrays.copyOf(evCurU, nc);
            }
            evCurIs[evCurN] = is; evCurIi[evCurN] = ii; evCurU[evCurN] = u; evCurN++;
        }
        private void evPushNxt(int is, int ii, double u) {
            if (evNxtN == evNxtIs.length) {
                int nc = evNxtN << 1;
                evNxtIs = Arrays.copyOf(evNxtIs, nc); evNxtIi = Arrays.copyOf(evNxtIi, nc); evNxtU = Arrays.copyOf(evNxtU, nc);
            }
            evNxtIs[evNxtN] = is; evNxtIi[evNxtN] = ii; evNxtU[evNxtN] = u; evNxtN++;
        }

        private double[] evaluatePatternUtility(Pattern p, Sequence seq) {
            int firstItem = p.elements.get(0).get(0);
            evCurN = 0;
            List<QItemset> isets = seq.itemsets;
            for (int is = 0; is < isets.size(); is++) {
                QItemset it = isets.get(is);
                for (int ii = 0; ii < it.items.size(); ii++)
                    if (it.items.get(ii).id == firstItem) evPushCur(is, ii, it.items.get(ii).utility);
            }
            if (evCurN == 0) { evalOut[0] = 0; evalOut[1] = 0; return evalOut; }

            for (int i = 0; i < p.elements.size(); i++) {
                List<Integer> itemset = p.elements.get(i);
                for (int j = 0; j < itemset.size(); j++) {
                    if (i == 0 && j == 0) continue;
                    int itemToFind = itemset.get(j);
                    boolean isIExt = (j > 0);
                    evNxtN = 0;
                    for (int e = 0; e < evCurN; e++) {
                        int cis = evCurIs[e], cii = evCurIi[e]; double cu = evCurU[e];
                        if (isIExt) {
                            QItemset cur = isets.get(cis);
                            for (int k = cii + 1; k < cur.items.size(); k++)
                                if (cur.items.get(k).id == itemToFind) evPushNxt(cis, k, cu + cur.items.get(k).utility);
                        } else {
                            for (int ns = cis + 1; ns < isets.size(); ns++) {
                                QItemset nxt = isets.get(ns);
                                for (int k = 0; k < nxt.items.size(); k++)
                                    if (nxt.items.get(k).id == itemToFind) evPushNxt(ns, k, cu + nxt.items.get(k).utility);
                            }
                        }
                    }
                    if (evNxtN == 0) { evalOut[0] = 0; evalOut[1] = 0; return evalOut; }
                    // swap cur <-> nxt (references only, no allocation)
                    int[] ti = evCurIs; evCurIs = evNxtIs; evNxtIs = ti;
                    ti = evCurIi; evCurIi = evNxtIi; evNxtIi = ti;
                    double[] td = evCurU; evCurU = evNxtU; evNxtU = td;
                    evCurN = evNxtN;
                }
            }
            double su = 0, peu = 0;
            for (int e = 0; e < evCurN; e++) {
                if (evCurU[e] > su) su = evCurU[e];
                double pp = evCurU[e] + seq.getRestUtility(evCurIs[e], evCurIi[e]);
                if (pp > peu) peu = pp;
            }
            evalOut[0] = su; evalOut[1] = peu;
            return evalOut;
        }

        public void exportLargePatterns(String outputPath) {
            if (!writeToFile || outputPath == null) return;
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputPath))) {
                List<Map.Entry<Pattern, Double>> sorted = new ArrayList<>(largeMap.entrySet());
                sorted.sort((e1, e2) -> Double.compare(e2.getValue(), e1.getValue()));
                for (Map.Entry<Pattern, Double> entry : sorted) {
                    writer.write(entry.getKey().toIntuitiveString() + " #UTIL: " + entry.getValue());
                    writer.newLine();
                }
            } catch (IOException e) {
                System.err.println("Error writing Pre-HUSPM output file: " + e.getMessage());
            }
        }
    }
}
