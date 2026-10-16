package algorithms;

import common.*;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

/**
 * Baseline: Pre-HUSPM (Yan et al., J. Supercomputing 2025, 81:81).
 *
 * ---------------------------------------------------------------------------
 * PROVENANCE
 *
 * Independent reimplementation from Definitions 8 and 12, Theorems 1 and 2 and Algorithms 1
 * to 4 of Yan et al. No code of the authors or of the SPMF library is used.
 * ---------------------------------------------------------------------------
 *
 * ───────────────────────────────────────────────────────────────────────────
 * BUFFER = LSWU ∪ PSWU: every pattern X with SWU_proj(X) >= Sl·TSU, stored as (SWU_proj, su).
 *   SWU_proj is the SWU computed on the projected database, as in Yan et al.'s worked example
 *   (Tables 7, 8, 11, 12): per sequence, the largest (prefix utility + rest utility) over the
 *   occurrences of X, summed over the sequences (= PEU). Definition 8 (Σ SU(s)) is a looser
 *   bound with the same HUSPs and a larger buffer.
 *   * HUSP (Algorithm 1 line 25): su(X) >= Su·TSU.
 *   * PSWU (Definition 12):       Sl·TSU <= SWU_proj(X) < Su·TSU.
 *   The buffer is held as a prefix tree. It is prefix-closed: SWU_proj does not increase under
 *   extension, so a pattern is never kept when its prefix is not.
 *
 * RESCAN TEST (Algorithm 1 lines 5, 19, 22): with q the quantity of the variant, a batch d is
 *   absorbed by the safe update when buf + q <= f, and then buf += q; otherwise the database is
 *   rescanned and buf = 0. f = ((Su-Sl)/(1-Su))·TSU at the last rescan.
 *   * Variant.TSU    (Theorem 1), arm "Pre-HUSPM-TSU":    q = TSU_d = Σ_{s∈d} SU(s).
 *   * Variant.SWUMAX (Theorem 2), arm "Pre-HUSPM-SWUmax": q = SWUmax, the largest
 *     sequence-weighted utility (Definition 8) of a pattern in d. SWU is anti-monotone, so the
 *     largest value is reached by one item: SWUmax = max_i Σ_{s∈d, i∈s} SU(s). Algorithm 1 is
 *     written with TSU_d only; Yan et al. propose SWUmax as an alternative to TSU_d, so here it
 *     takes the place of TSU_d in lines 5 and 19 (buf accumulates SWUmax).
 *   Static scenarios use only runInitialMining, where the two variants are identical.
 *   Both tests are sound: Eq. (14) gives SWU_d(X) <= SWUmax <= TSU_d for every X, so over the
 *   safe batches since the last rescan no pattern gains more than buf <= f, and the argument
 *   of Theorem 1 applies.
 *
 * RESCAN (Algorithm 3): the items with Definition 8 SWU(i) = Σ_{s∋i} SU(s) < Sl·TSU are removed
 *   from the database (line 13) and the reduced database is mined (Algorithm 4) with the
 *   SWU_proj >= Sl·TSU bound; TSU stays that of the whole database (line 5). Removal by
 *   Definition 8 is safe: every pattern holding such an item has utility below Sl·TSU. Removal
 *   by the projected value of <i> would not be: a pattern <x, i> can have more utility than
 *   SWU_proj(<i>), which excludes the items before i.
 *
 * SAFE UPDATE (Algorithm 1 lines 6-18 and Algorithm 2): the inserted sequences are mined
 *   from their 1-sequences, extending only the patterns that remain in LSWU_U ∪ PSWU_U. Every
 *   buffered pattern gets SWU_proj += SWU_proj,d and su += su_d (0 when it does not occur in d)
 *   and is discarded, with its extensions, when SWU_proj < Sl·TSU_U. A pattern that is not in
 *   the buffer is never added (line 13). The walk is depth-first over the prefix tree; each
 *   pattern receives the same values and decision as in the level-wise order of Algorithm 1.
 *
 * OCCURRENCE LISTS hold one entry per (sequence, position): the largest utility of the
 *   pattern ending at that position, as a projected database holds each position once.
 *
 * COUNTERS, reset by every call of runInitialMining and runIncrementalMining:
 *   candidateCount     patterns generated. Mining: the 1-sequences and every distinct extension
 *                      of an expanded pattern. Safe update: the 1-sequences of d and the
 *                      extensions in d of every kept pattern (Algorithm 1 lines 8, 16).
 *   exploredNodes      Mining: patterns that passed the SWU_proj >= Sl·TSU bound and were
 *                      expanded (= buffer size). Safe update: buffered patterns kept by the
 *                      Sub-Procedure.
 *   prunedPEU          Mining: generated patterns cut by the bound (candidateCount -
 *                      exploredNodes). Safe update: buffered patterns discarded, those under a
 *                      discarded prefix included (buffer before = exploredNodes + prunedPEU).
 *   subProcedureCalls  Safe update: buffered patterns re-evaluated against d (Algorithm 2).
 *   occurrenceEntries  entries written into occurrence lists (mining and safe update).
 *   removedItems       Rescan: items removed by Algorithm 3 line 13.
 * ───────────────────────────────────────────────────────────────────────────
 */
public class PreHUSPMAlgorithm {

    // ========================================================================
    // 1. OCCURRENCE LISTS - primitive parallel arrays
    // ========================================================================
    static final class Tracker {
        int seqId;
        int[] flatIndices;
        double[] utility;
        int size = 0;
        double exactUtility = 0;   // local su = max utility over the entries
        double peu = 0;            // local SWU_proj = max(utility + restUtil) over the entries

        Tracker(int id) {
            this.seqId = id;
            this.flatIndices = new int[4];
            this.utility = new double[4];
        }

        void reset(int newSeqId) {
            this.seqId = newSeqId; this.size = 0; this.exactUtility = 0; this.peu = 0;
        }

        /** Appends one position; the caller guarantees that flatIdx is not yet in this tracker. */
        void append(int flatIdx, double u, Sequence seq) {
            if (size == flatIndices.length) grow();
            flatIndices[size] = flatIdx;
            utility[size] = u;
            size++;
            if (u > exactUtility) exactUtility = u;
            double p = u + seq.getRestUtilityFlat(flatIdx);
            if (p > peu) peu = p;
        }

        /** Raises the utility of entry i to u (u larger than the stored value). */
        void raise(int i, double u, Sequence seq) {
            utility[i] = u;
            if (u > exactUtility) exactUtility = u;
            double p = u + seq.getRestUtilityFlat(flatIndices[i]);
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

    /** A buffered pattern: one node of the prefix tree of LSWU ∪ PSWU. */
    static final class Node {
        static final Node[] NO_KIDS = new Node[0];
        final Pattern pattern;   // null at the root
        final int item;          // last item
        final boolean iExt;      // true if the last item was added by an I-extension
        double swu, su;          // SWU_proj and su in the current database
        Node[] kids = NO_KIDS;
        int nKids = 0;

        Node(Pattern pattern, int item, boolean iExt, double swu, double su) {
            this.pattern = pattern; this.item = item; this.iExt = iExt; this.swu = swu; this.su = su;
        }

        void add(Node c) {
            if (nKids == kids.length) kids = Arrays.copyOf(kids, Math.max(2, nKids << 1));
            kids[nKids++] = c;
        }
    }

    // ========================================================================
    // 2. PRE-HUSPM CORE
    // ========================================================================
    public static class PreHUSPMAlgo {

        /** The two rescan tests of Yan et al. See the class comment. */
        public enum Variant {
            /** Theorem 1: compares TSU_d. */
            TSU,
            /** Theorem 2: compares SWUmax = max_i Σ_{s∈d, i∈s} SU(s). */
            SWUMAX
        }

        public double Su, Sl;
        /** Default: SWUMAX, the test of Theorem 2 (arm "Pre-HUSPM-SWUmax"). */
        public Variant variant = Variant.SWUMAX;
        public double safetyBoundF = 0;
        /** Quantity compared with f in the latest batch (written to the CSV for diagnosis). */
        public double lastTriggerQuantity = 0;
        /** buf after the update of the latest batch. */
        public double bufValue = 0;
        public int maxDepth = 1000;

        public Map<Pattern, Double> largeMap = new HashMap<>();      // HUSP: su >= Su·TSU
        public Map<Pattern, Double> prelargeMap = new HashMap<>();   // PSWU: Sl·TSU <= SWU_proj < Su·TSU
        private Node root = new Node(null, -1, false, 0, 0);         // LSWU ∪ PSWU as a prefix tree
        private int bufferSize = 0;
        private double buf = 0.0;                                    // Algorithm 1: q summed since the last rescan

        public int rescanCount = 0, patternCount = 0;
        public int prelargeCount = 0;
        public long candidateCount = 0;
        public long exploredNodes = 0;
        public long prunedPEU = 0;
        public long subProcedureCalls = 0;
        public long occurrenceEntries = 0;
        public int removedItems = 0;

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

        /** Number of buffered patterns (|LSWU ∪ PSWU|). */
        public int bufferSize() { return bufferSize; }

        /** Copy of the buffer: pattern -> {SWU_proj, su}. For checks; not used by the algorithm. */
        public Map<Pattern, double[]> bufferSnapshot() {
            Map<Pattern, double[]> out = new HashMap<>(bufferSize * 2 + 1);
            ArrayDeque<Node> st = new ArrayDeque<>();
            st.push(root);
            while (!st.isEmpty()) {
                Node n = st.pop();
                if (n.pattern != null) out.put(n.pattern.clonePattern(), new double[]{n.swu, n.su});
                for (int c = 0; c < n.nKids; c++) st.push(n.kids[c]);
            }
            return out;
        }

        // ---- Sequence lookup by seqId through an array (no Integer boxing) ----
        private Sequence[] dbLookupArr = new Sequence[16];
        private void dbPut(Sequence seq) {
            int sid = seq.id;
            if (sid >= dbLookupArr.length)
                dbLookupArr = Arrays.copyOf(dbLookupArr, Math.max(sid + 1, dbLookupArr.length * 2));
            dbLookupArr[sid] = seq;
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

        // ---- Child occurrence lists by item, filled in one pass over the parent ----
        private transient TrackerList[] iSlot, sSlot;   // iSlot[itemId] = occurrence list of the I-extension
        private transient int[] usedItemI, usedItemS;   // items touched in this pass (for selective reset)
        private int usedICount = 0, usedSCount = 0;
        private void ensureItemScratch(int sz) {
            if (iSlot == null || iSlot.length < sz) {
                iSlot = new TrackerList[sz]; sSlot = new TrackerList[sz];
                usedItemI = new int[sz]; usedItemS = new int[sz];
            }
        }

        // ---- De-duplication: entry index of position q in the current child tracker ----
        private int[] stampI = new int[0], stampS = new int[0], entryI = new int[0], entryS = new int[0];
        private int stamp = 0;
        private void ensureStamp(int flatLen) {
            if (stampI.length < flatLen + 1) {
                int n = flatLen + 1;
                stampI = new int[n]; stampS = new int[n]; entryI = new int[n]; entryS = new int[n];
                stamp = 0;
            }
        }
        private void nextStamp() {
            if (++stamp == Integer.MAX_VALUE) {
                Arrays.fill(stampI, 0); Arrays.fill(stampS, 0);
                stamp = 1;
            }
        }

        // ---- Per-depth arrays: children are copied out before recursing so slot[] is not overwritten ----
        private transient List<int[]> iItemPool = new ArrayList<>();
        private transient List<int[]> sItemPool = new ArrayList<>();
        private transient List<TrackerList[]> iTLPool = new ArrayList<>();
        private transient List<TrackerList[]> sTLPool = new ArrayList<>();
        private transient List<TrackerList[]> walkTLPool = new ArrayList<>();
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

        private void resetCounters() {
            candidateCount = 0; exploredNodes = 0; prunedPEU = 0;
            subProcedureCalls = 0; occurrenceEntries = 0; removedItems = 0;
        }

        // ====================================================================
        // PHASE 1: INITIAL MINING
        // ====================================================================
        public void runInitialMining(List<Sequence> dInit, double currentTotalUtility) {
            rescanCount = 0; patternCount = 0; prelargeCount = 0; buf = 0.0; bufValue = 0.0;
            resetCounters();
            largeMap.clear(); prelargeMap.clear();
            executeRescanAndRebuild(dInit, currentTotalUtility, true);
        }

        // ====================================================================
        // PHASE 2: INCREMENTAL MINING (Algorithm 1)
        // ====================================================================
        public void runIncrementalMining(List<Sequence> deltaD, List<Sequence> dSoFar, double newTotalUtility) {
            // Counters describe this batch only: the rescan or the safe update it performed.
            resetCounters();

            double tsuD = 0;
            for (Sequence seq : deltaD) tsuD += seq.sequenceUtility;
            double trigger = (variant == Variant.TSU) ? tsuD : maxItemSwu(deltaD);
            this.lastTriggerQuantity = trigger;

            if (buf + trigger > safetyBoundF) {          // Algorithm 1 lines 20-22
                executeRescanAndRebuild(dSoFar, newTotalUtility, false);
                buf = 0.0;
            } else {                                     // Algorithm 1 lines 6-19
                safeUpdate(deltaD, newTotalUtility * Su, newTotalUtility * Sl);
                buf += trigger;
            }
            this.bufValue = buf;
            this.patternCount = largeMap.size();
            this.prelargeCount = prelargeMap.size();
        }

        /** SWUmax of Theorem 2: max over items i of Σ SU(s) over the sequences s of d holding i. */
        public static double maxItemSwu(List<Sequence> d) {
            int maxItem = -1;
            for (Sequence s : d)
                for (QItemset is : s.itemsets) for (QItem q : is.items) if (q.id > maxItem) maxItem = q.id;
            if (maxItem < 0) return 0;
            double[] swu = new double[maxItem + 1];
            int[] seen = new int[maxItem + 1];
            double best = 0;
            for (int i = 0; i < d.size(); i++) {
                Sequence s = d.get(i);
                for (QItemset is : s.itemsets)
                    for (QItem q : is.items)
                        if (seen[q.id] != i + 1) {          // count each sequence once per item
                            seen[q.id] = i + 1;
                            swu[q.id] += s.sequenceUtility;
                            if (swu[q.id] > best) best = swu[q.id];
                        }
            }
            return best;
        }

        private void updateSafetyBound(double totalUtilityD) {
            this.safetyBoundF = ((Su - Sl) / (1.0 - Su)) * totalUtilityD;
        }

        /** Rescan-Mining (Algorithm 3) followed by Mining (Algorithm 4); sets f from the new TSU. */
        private void executeRescanAndRebuild(List<Sequence> currentFullDB, double newTotalUtility, boolean isInitial) {
            if (!isInitial) rescanCount++;
            updateSafetyBound(newTotalUtility);
            double absSu = newTotalUtility * Su;
            double absSl = newTotalUtility * Sl;
            List<Sequence> reduced = removeSmallItems(currentFullDB, absSl);
            mineFromScratch(reduced, absSl);
            deriveMaps(absSu, absSl);
        }

        /**
         * Algorithm 3 line 13: drops every item with Definition 8 SWU below absSl. Sequences that
         * hold no such item are reused; the others are copied without those items (the input is
         * not modified). Returns the input itself when no item is removed.
         */
        private List<Sequence> removeSmallItems(List<Sequence> db, double absSl) {
            int maxItem = -1;
            for (Sequence s : db) {
                if (s.flatItems == null) s.buildFlatRepresentation();
                for (int k = 0; k < s.flatLen; k++) if (s.flatItems[k] > maxItem) maxItem = s.flatItems[k];
            }
            if (maxItem < 0) return db;
            double[] swu = new double[maxItem + 1];
            int[] seen = new int[maxItem + 1];
            for (int i = 0; i < db.size(); i++) {
                Sequence s = db.get(i);
                int[] fi = s.flatItems;
                for (int k = 0; k < s.flatLen; k++) {
                    int it = fi[k];
                    if (seen[it] != i + 1) { seen[it] = i + 1; swu[it] += s.sequenceUtility; }
                }
            }
            boolean[] small = new boolean[maxItem + 1];
            int removed = 0;
            for (int it = 0; it <= maxItem; it++)
                if (seen[it] != 0 && swu[it] < absSl) { small[it] = true; removed++; }
            removedItems = removed;
            if (removed == 0) return db;

            List<Sequence> out = new ArrayList<>(db.size());
            for (Sequence s : db) {
                boolean touched = false;
                for (int k = 0; k < s.flatLen && !touched; k++) touched = small[s.flatItems[k]];
                if (!touched) { out.add(s); continue; }
                Sequence r = new Sequence(s.id);
                for (QItemset is : s.itemsets) {
                    QItemset ni = new QItemset();
                    for (QItem q : is.items) if (!small[q.id]) ni.items.add(q);
                    if (!ni.items.isEmpty()) r.itemsets.add(ni);
                }
                if (r.itemsets.isEmpty()) continue;
                r.buildFlatRepresentation();   // also sets SU of the reduced sequence
                out.add(r);
            }
            return out;
        }

        /**
         * The removal of removeSmallItems (Algorithm 3 line 13) for another miner: the database
         * without every item whose Definition 8 SWU is below absSl, sequences left empty dropped.
         * Used by the proposal's tracking in Pre-HUSPM's manner (PreHUSPMAdaptiveAlgo.Tracking.PREHUSPM);
         * verify.PreHuspmTrackingCheck checks that the two give the same buffer.
         */
        public static List<Sequence> withoutItemsBelowSwu(List<Sequence> db, double absSl) {
            int maxItem = -1;
            for (Sequence s : db) {
                if (s.flatItems == null) s.buildFlatRepresentation();
                for (int k = 0; k < s.flatLen; k++) if (s.flatItems[k] > maxItem) maxItem = s.flatItems[k];
            }
            if (maxItem < 0) return db;
            double[] swu = new double[maxItem + 1];
            int[] seen = new int[maxItem + 1];
            for (int i = 0; i < db.size(); i++) {
                Sequence s = db.get(i);
                int[] fi = s.flatItems;
                for (int k = 0; k < s.flatLen; k++) {
                    int it = fi[k];
                    if (seen[it] != i + 1) { seen[it] = i + 1; swu[it] += s.sequenceUtility; }
                }
            }
            boolean[] small = new boolean[maxItem + 1];
            int removed = 0;
            for (int it = 0; it <= maxItem; it++)
                if (seen[it] != 0 && swu[it] < absSl) { small[it] = true; removed++; }
            if (removed == 0) return db;
            List<Sequence> out = new ArrayList<>(db.size());
            for (Sequence s : db) {
                boolean touched = false;
                for (int k = 0; k < s.flatLen && !touched; k++) touched = small[s.flatItems[k]];
                if (!touched) { out.add(s); continue; }
                Sequence r = new Sequence(s.id);
                for (QItemset is : s.itemsets) {
                    QItemset ni = new QItemset();
                    for (QItem q : is.items) if (!small[q.id]) ni.items.add(q);
                    if (!ni.items.isEmpty()) r.itemsets.add(ni);
                }
                if (r.itemsets.isEmpty()) continue;
                r.buildFlatRepresentation();
                out.add(r);
            }
            return out;
        }

        /** Derives largeMap (HUSP) and prelargeMap (PSWU, Definition 12) from the buffer. */
        private void deriveMaps(double absSu, double absSl) {
            largeMap.clear(); prelargeMap.clear();
            ArrayDeque<Node> st = new ArrayDeque<>();
            st.push(root);
            while (!st.isEmpty()) {
                Node n = st.pop();
                if (n.pattern != null) {
                    if (n.su >= absSu) largeMap.put(n.pattern, n.su);
                    else if (n.swu >= absSl && n.swu < absSu) prelargeMap.put(n.pattern, n.su);
                }
                for (int c = 0; c < n.nKids; c++) st.push(n.kids[c]);
            }
            patternCount = largeMap.size();
            prelargeCount = prelargeMap.size();
        }

        // ====================================================================
        // MINING (Algorithm 4)
        // ====================================================================
        private void mineFromScratch(List<Sequence> db, double minSl) {
            dbClear();
            int maxItemId = -1, maxFlatLen = 0;
            for (Sequence s : db) {
                if (s.flatItems == null) s.buildFlatRepresentation();
                dbPut(s);
                if (s.flatLen > maxFlatLen) maxFlatLen = s.flatLen;
                int[] fi = s.flatItems;
                for (int k = 0; k < s.flatLen; k++) if (fi[k] > maxItemId) maxItemId = fi[k];
            }
            ensureItemScratch(maxItemId + 2);
            ensureStamp(maxFlatLen);
            trackerPool.warmup(POOL_WARMUP_TRACKERS);
            trackerListPool.warmup(1024);

            root = new Node(null, -1, false, 0, 0);
            bufferSize = 0;

            // ---- 1-sequences: one scan, one entry per position ----
            usedICount = 0; usedSCount = 0;
            for (Sequence seq : db) {
                nextStamp();
                for (int k = 0; k < seq.flatLen; k++) addChild(false, seq, seq.id, k, seq.flatUtilities[k]);
            }
            candidateCount += usedSCount;
            int n1 = usedSCount;
            int[] items = borrowIntArr(sItemPool, 0, n1);
            TrackerList[] tls = borrowTLArr(sTLPool, 0, n1);
            for (int u = 0; u < n1; u++) { int item = usedItemS[u]; items[u] = item; tls[u] = sSlot[item]; sSlot[item] = null; }
            for (int u = 0; u < n1; u++) {
                Pattern p = new Pattern(items[u]);
                expandNode(p, tls[u], minSl, root, false, 1);
                releaseTrackerList(tls[u]);
                tls[u] = null;
            }
            dbClear();
        }

        /**
         * Expands one generated pattern: applies the SWU_proj >= Sl·TSU bound, stores the
         * pattern in the buffer, collects all its I- and S-extensions in one pass over its
         * occurrence list, and recurses into each.
         */
        private void expandNode(Pattern currentPattern, TrackerList parentTrackers, double minSl,
                                Node parent, boolean iExt, int depth) {
            if (depth > maxDepth) return;
            if (Thread.currentThread().isInterrupted()) return;
            if (parentTrackers == null || parentTrackers.isEmpty()) return;

            final ArrayList<Tracker> ptList = parentTrackers.trackers;
            final int ptSize = ptList.size();
            double projSwuD = 0, suD = 0;
            for (int i = 0; i < ptSize; i++) {
                Tracker t = ptList.get(i);
                suD += t.exactUtility;
                projSwuD += t.peu;
            }
            if (projSwuD < minSl) { prunedPEU++; return; }
            exploredNodes++;   // passed the bound

            List<Integer> lastEl = currentPattern.elements.get(currentPattern.elements.size() - 1);
            Node node = new Node(currentPattern.clonePattern(), lastEl.get(lastEl.size() - 1), iExt, projSwuD, suD);
            parent.add(node);
            bufferSize++;

            collectExtensions(parentTrackers, dbLookupArr);
            final int usedI = usedICount, usedS = usedSCount;
            candidateCount += usedI + usedS;

            // Copy the children into per-depth arrays and clear slot[] (so it is not overwritten)
            int[] itemsI = null; TrackerList[] tlsI = null;
            if (usedI > 0) {
                itemsI = borrowIntArr(iItemPool, depth, usedI);
                tlsI = borrowTLArr(iTLPool, depth, usedI);
                for (int u = 0; u < usedI; u++) { int item = usedItemI[u]; itemsI[u] = item; tlsI[u] = iSlot[item]; iSlot[item] = null; }
            }
            int[] itemsS = null; TrackerList[] tlsS = null;
            if (usedS > 0) {
                itemsS = borrowIntArr(sItemPool, depth, usedS);
                tlsS = borrowTLArr(sTLPool, depth, usedS);
                for (int u = 0; u < usedS; u++) { int item = usedItemS[u]; itemsS[u] = item; tlsS[u] = sSlot[item]; sSlot[item] = null; }
            }

            for (int u = 0; u < usedI; u++) {
                currentPattern.addIExtension(itemsI[u]);
                expandNode(currentPattern, tlsI[u], minSl, node, true, depth + 1);
                currentPattern.removeLastIExtension();
                releaseTrackerList(tlsI[u]);
                tlsI[u] = null;
            }
            for (int u = 0; u < usedS; u++) {
                currentPattern.addSExtension(itemsS[u]);
                expandNode(currentPattern, tlsS[u], minSl, node, false, depth + 1);
                currentPattern.removeLastSExtension();
                releaseTrackerList(tlsS[u]);
                tlsS[u] = null;
            }
        }

        /**
         * One pass over an occurrence list: fills iSlot/sSlot[item] with the occurrence lists of
         * every I- and S-extension and sets usedICount/usedSCount. lookup[seqId] resolves the
         * sequences of the list.
         */
        private void collectExtensions(TrackerList parent, Sequence[] lookup) {
            usedICount = 0; usedSCount = 0;
            final ArrayList<Tracker> ptList = parent.trackers;
            for (int pi = 0, ptSize = ptList.size(); pi < ptSize; pi++) {
                Tracker t = ptList.get(pi);
                final int sid = t.seqId;
                Sequence seq = (sid >= 0 && sid < lookup.length) ? lookup[sid] : null;
                if (seq == null) continue;
                nextStamp();
                final double[] flatUtils = seq.flatUtilities;
                final boolean[] isItemsetEnd = seq.isItemsetEnd;
                final int flatLen = seq.flatLen;
                final int tSize = t.size; final int[] tFlat = t.flatIndices; final double[] tUtil = t.utility;
                for (int k = 0; k < tSize; k++) {
                    int p = tFlat[k];
                    double iu = tUtil[k];
                    // I-extensions: same itemset, after p
                    if (!isItemsetEnd[p]) {
                        for (int q = p + 1; q < flatLen; q++) {
                            addChild(true, seq, sid, q, iu + flatUtils[q]);
                            if (isItemsetEnd[q]) break;
                        }
                    }
                    // S-extensions: itemsets after the itemset of p
                    int boundary = p; while (boundary < flatLen && !isItemsetEnd[boundary]) boundary++;
                    for (int q = boundary + 1; q < flatLen; q++) addChild(false, seq, sid, q, iu + flatUtils[q]);
                }
            }
        }

        /** Adds position q with utility u to the extension's list; keeps one entry per position. */
        private void addChild(boolean isI, Sequence seq, int sid, int q, double u) {
            int item = seq.flatItems[q];
            TrackerList[] slot = isI ? iSlot : sSlot;
            TrackerList tl = slot[item];
            if (tl == null) {
                tl = borrowTrackerList(); slot[item] = tl;
                if (isI) usedItemI[usedICount++] = item; else usedItemS[usedSCount++] = item;
            }
            ArrayList<Tracker> ts = tl.trackers; int n = ts.size();
            Tracker ct = (n > 0 && ts.get(n - 1).seqId == sid) ? ts.get(n - 1) : null;
            if (ct == null) { ct = borrowTracker(sid); ts.add(ct); }
            int[] st = isI ? stampI : stampS, ix = isI ? entryI : entryS;
            if (st[q] == stamp) {                        // position already listed: keep the max
                int i = ix[q];
                if (u > ct.utility[i]) ct.raise(i, u, seq);
                return;
            }
            st[q] = stamp; ix[q] = ct.size;
            ct.append(q, u, seq);
            occurrenceEntries++;
        }

        // ====================================================================
        // SAFE UPDATE (Algorithm 1 lines 6-18, Sub-Procedure = Algorithm 2)
        // ====================================================================
        private void safeUpdate(List<Sequence> d, double absSu, double absSl) {
            final int n = d.size();
            Sequence[] look = new Sequence[n];
            int maxItem = -1, maxLen = 0;
            for (int i = 0; i < n; i++) {
                Sequence s = d.get(i);
                if (s.flatItems == null) s.buildFlatRepresentation();
                look[i] = s;
                if (s.flatLen > maxLen) maxLen = s.flatLen;
                for (int k = 0; k < s.flatLen; k++) if (s.flatItems[k] > maxItem) maxItem = s.flatItems[k];
            }
            ensureItemScratch(maxItem + 2);
            ensureStamp(maxLen);

            // C_1: the 1-sequences of d (line 8); tracker seqId = index in d
            usedICount = 0; usedSCount = 0;
            for (int i = 0; i < n; i++) {
                Sequence s = look[i];
                nextStamp();
                for (int k = 0; k < s.flatLen; k++) addChild(false, s, i, k, s.flatUtilities[k]);
            }
            candidateCount += usedSCount;
            updateChildren(root, look, absSl, 0);
            deriveMaps(absSu, absSl);
        }

        /**
         * Applies the Sub-Procedure to every child of parent. On entry iSlot/sSlot hold the
         * occurrence lists in d of the extensions of parent (usedICount/usedSCount of them;
         * none when parent does not occur in d). Kept children are extended in turn (line 16);
         * a discarded child is removed with its subtree.
         */
        private void updateChildren(Node parent, Sequence[] look, double absSl, int depth) {
            final int nk = parent.nKids;
            TrackerList[] occ = borrowTLArr(walkTLPool, depth, Math.max(1, nk));
            for (int c = 0; c < nk; c++) {
                Node k = parent.kids[c];
                TrackerList[] slot = k.iExt ? iSlot : sSlot;
                if (k.item < slot.length && slot[k.item] != null) { occ[c] = slot[k.item]; slot[k.item] = null; }
                else occ[c] = null;
            }
            // Extensions of d that are not buffered are not added (Algorithm 1 line 13)
            for (int u = 0; u < usedICount; u++) {
                int it = usedItemI[u];
                if (iSlot[it] != null) { releaseTrackerList(iSlot[it]); iSlot[it] = null; }
            }
            for (int u = 0; u < usedSCount; u++) {
                int it = usedItemS[u];
                if (sSlot[it] != null) { releaseTrackerList(sSlot[it]); sSlot[it] = null; }
            }
            usedICount = 0; usedSCount = 0;

            int w = 0;
            for (int c = 0; c < nk; c++) {
                Node k = parent.kids[c];
                TrackerList o = occ[c];
                occ[c] = null;
                double swuD = 0, suD = 0;
                if (o != null) {
                    ArrayList<Tracker> ts = o.trackers;
                    for (int i = 0, m = ts.size(); i < m; i++) { Tracker t = ts.get(i); swuD += t.peu; suD += t.exactUtility; }
                }
                k.swu += swuD;                           // Algorithm 2 line 1
                k.su += suD;                             // Algorithm 2 line 2
                subProcedureCalls++;
                if (k.swu < absSl) {                     // Algorithm 2: discarded
                    int gone = subtreeSize(k);
                    prunedPEU += gone;
                    bufferSize -= gone;
                    if (o != null) releaseTrackerList(o);
                    continue;
                }
                exploredNodes++;
                parent.kids[w++] = k;
                if (o != null) {
                    collectExtensions(o, look);
                    candidateCount += usedICount + usedSCount;
                }
                updateChildren(k, look, absSl, depth + 1);
                if (o != null) releaseTrackerList(o);
            }
            for (int c = w; c < nk; c++) parent.kids[c] = null;
            parent.nKids = w;
        }

        private static int subtreeSize(Node n) {
            int cnt = 0;
            ArrayDeque<Node> st = new ArrayDeque<>();
            st.push(n);
            while (!st.isEmpty()) {
                Node x = st.pop();
                cnt++;
                for (int c = 0; c < x.nKids; c++) st.push(x.kids[c]);
            }
            return cnt;
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
