package algorithms;

import common.*;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

/**
 * Pre-HUSPM-Joint: incremental high-utility sequential pattern mining with pre-large
 * maintenance. Components:
 * - aggregated PEU value bound applied before child occurrence lists are built
 *   (survey, filter, then materialize);
 * - adaptive pre-large floor LUT_dyn (not used under the JOINT trigger rule);
 * - IUB branch bound summed over the per-sequence PEU of a node;
 * - block-level inverted index for the direct-update branch;
 * - accumulated insertion/deletion counters that decide when to rescan.
 */
public class PreHUSPMAdaptiveAlgorithm {

    /**
     * Cooperative cancellation signal raised when the runner's timeout fires.
     * Built without a stack trace, so throwing it is cheap.
     */
    public static final class CancellationSignal extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public CancellationSignal() { super("Algorithm cancelled by timeout", null, false, false); }
    }

    /**
     * Raised when a mining pass exceeds its candidate or wall-clock budget, so the run
     * stops cleanly instead of running out of memory. The runner records it as BUDGET.
     * Built without a stack trace.
     */
    public static final class PatternBudgetExceeded extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public PatternBudgetExceeded(long n) {
            super("Pattern budget exceeded (" + n + " patterns) - threshold too low for this dataset", null, false, false);
        }
    }


    // ========================================================================
    // BLOCK-LEVEL INVERTED INDEX
    // ========================================================================
    static class BlockSummary {
        int blockId, startSeqId, endSeqId;
        double minSeqUtility = Double.MAX_VALUE;
        double maxSeqUtility = Double.MIN_VALUE;
        // No per-block item set is kept: itemBlockBitmap already answers "which blocks
        // hold item i", and a per-block set as long as the largest item id costs memory
        // for no use.

        public BlockSummary(int id, int start, int end) {
            this.blockId = id; this.startSeqId = start; this.endSeqId = end;
        }

        public void addSequenceInfo(Sequence seq) {
            if (seq.sequenceUtility < minSeqUtility) minSeqUtility = seq.sequenceUtility;
            if (seq.sequenceUtility > maxSeqUtility) maxSeqUtility = seq.sequenceUtility;
        }
    }

    /**
     * Block-level inverted index stored as one bitmap per item.
     *
     *  - itemBlockBitmap[itemId].get(blockId) is true iff the item occurs in that block.
     *  - Lookup is a bit test; memory is one bit per (item, block) pair, with no boxing.
     *  - Appending a block only sets bits at the highest block id.
     *
     * seqIdToBlock is an int[] that grows by doubling.
     */
    static class BlockIndex {
        int blockSize = 100;
        List<BlockSummary> blocks = new ArrayList<>();

        // itemBlockBitmap[itemId] = set of block ids that contain the item.
        BitSet[] itemBlockBitmap = new BitSet[256];
        int maxItemSeen = -1;

        // seqId -> blockId, grown on demand.
        int[] seqIdToBlock = new int[1024];
        int maxSeqIdSeen = -1;

        BlockIndex() { Arrays.fill(seqIdToBlock, -1); }

        private void ensureSeqCapacity(int needed) {
            if (needed < seqIdToBlock.length) return;
            int newSize = Math.max(needed + 1, seqIdToBlock.length * 2);
            int[] grown = new int[newSize];
            Arrays.fill(grown, -1);
            System.arraycopy(seqIdToBlock, 0, grown, 0, seqIdToBlock.length);
            seqIdToBlock = grown;
        }

        private void ensureItemCapacity(int item) {
            if (item < itemBlockBitmap.length) return;
            int newSize = Math.max(item + 1, itemBlockBitmap.length * 2);
            itemBlockBitmap = Arrays.copyOf(itemBlockBitmap, newSize);
        }

        public void buildFromBatch(List<Sequence> batch) {
            if (batch.isEmpty()) return;
            int currentBlockId = blocks.size();
            BlockSummary currentBlock = new BlockSummary(currentBlockId, batch.get(0).id, -1);

            for (int i = 0; i < batch.size(); i++) {
                Sequence seq = batch.get(i);
                ensureSeqCapacity(seq.id);
                seqIdToBlock[seq.id] = currentBlockId;
                if (seq.id > maxSeqIdSeen) maxSeqIdSeen = seq.id;
                currentBlock.addSequenceInfo(seq);

                for (QItemset itemset : seq.itemsets) {
                    for (QItem item : itemset.items) {
                        int id = item.id;
                        ensureItemCapacity(id);
                        BitSet bs = itemBlockBitmap[id];
                        if (bs == null) {
                            bs = new BitSet();
                            itemBlockBitmap[id] = bs;
                        }
                        bs.set(currentBlockId);
                        if (id > maxItemSeen) maxItemSeen = id;
                    }
                }

                if ((i + 1) % blockSize == 0 || i == batch.size() - 1) {
                    currentBlock.endSeqId = seq.id;
                    blocks.add(currentBlock);
                    if (i < batch.size() - 1) {
                        currentBlockId++;
                        currentBlock = new BlockSummary(currentBlockId, batch.get(i + 1).id, -1);
                    }
                }
            }
        }

        /** Block id of a sequence, or -1 if unknown. */
        public int blockOf(int seqId) {
            if (seqId < 0 || seqId >= seqIdToBlock.length) return -1;
            return seqIdToBlock[seqId];
        }

        /** Bitmap of the blocks that contain the item; null if the item has never occurred. */
        public BitSet bitsetFor(int itemId) {
            if (itemId < 0 || itemId >= itemBlockBitmap.length) return null;
            return itemBlockBitmap[itemId];
        }
    }

    // ========================================================================
    // OCCURRENCE TRACKERS
    //
    // A Tracker holds the occurrences of one pattern in one sequence as two parallel
    // primitive arrays (flat position, accumulated utility). Arrays grow by doubling;
    // TrackerList.trimToSize() trims large slack before a list is passed down the
    // recursion.
    // ========================================================================

    /**
     * Pattern stored as a flat int[]: itemsets separated by -1.
     *
     * Pattern <code>&lt;{a,b},{c}&gt;</code> → {@code int[] = {a, b, -1, c}}.
     * Pattern <code>&lt;{a}&gt;</code>       → {@code int[] = {a}}.
     *
     * Much smaller than nested lists of boxed integers. The hash is computed once in the
     * constructor; equals compares the arrays.
     *
     * Used as the key of largeMap, prelargeMap and the mining results. Converted to
     * Pattern only for export and for re-evaluation in the update branches.
     */
    static final class CompactPattern {
        final int[] flat;
        final int cachedHash;

        CompactPattern(int[] flat) {
            this.flat = flat;
            this.cachedHash = Arrays.hashCode(flat);
        }

        /** First item (flat[0] is always an item, never the -1 separator). */
        int firstItem() { return flat[0]; }

        /** Builds from the mutable Pattern used in expandNodeWithPruning. */
        static CompactPattern fromMutable(Pattern src) {
            int isCount = src.elements.size();
            int total = 0;
            for (int i = 0; i < isCount; i++) total += src.elements.get(i).size();
            int[] flat = new int[total + isCount - 1]; // separators = isCount - 1
            int pos = 0;
            for (int i = 0; i < isCount; i++) {
                if (i > 0) flat[pos++] = -1;
                List<Integer> itemset = src.elements.get(i);
                for (int j = 0, n = itemset.size(); j < n; j++) {
                    flat[pos++] = itemset.get(j);
                }
            }
            return new CompactPattern(flat);
        }

        /** Converts back to a Pattern (export and evaluation paths, not hot). */
        Pattern toPattern() {
            Pattern p = new Pattern();
            ArrayList<Integer> current = new ArrayList<>();
            for (int v : flat) {
                if (v == -1) {
                    p.elements.add(current);
                    current = new ArrayList<>();
                } else {
                    current.add(v);
                }
            }
            p.elements.add(current);
            return p;
        }

        @Override
        public int hashCode() { return cachedHash; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof CompactPattern)) return false;
            CompactPattern cp = (CompactPattern) o;
            return cachedHash == cp.cachedHash && Arrays.equals(flat, cp.flat);
        }
    }

    /**
     * Occurrences of one pattern in one sequence:
     *   int[] flatIndices  - flat positions in the sequence where the pattern ends
     *   double[] utility   - pattern utility at that position
     *
     * Itemset boundaries and rest utility are read from the sequence's flat arrays.
     *
     * reset() keeps the arrays, so a pooled tracker is reused without allocation.
     */
    static class Tracker {
        int seqId;
        int[] flatIndices;
        double[] utility;
        int size = 0;
        double exactUtility = 0, peu = 0;

        public Tracker(int id) {
            this.seqId = id;
            this.flatIndices = new int[4];
            this.utility = new double[4];
        }

        /** Resets for pool reuse; keeps the internal arrays. */
        public void reset(int newSeqId) {
            this.seqId = newSeqId;
            this.size = 0;
            this.exactUtility = 0;
            this.peu = 0;
        }

        /**
         * Appends without deduplication. The caller guarantees flatIdx is unique,
         * which the scratch buffer merge ensures.
         */
        public void append(int flatIdx, double u, Sequence seq) {
            if (size == flatIndices.length) grow();
            flatIndices[size] = flatIdx;
            utility[size] = u;
            size++;
            if (u > exactUtility) exactUtility = u;
            double currentPeu = u + seq.getRestUtilityFlat(flatIdx);
            if (currentPeu > peu) peu = currentPeu;
        }

        private void grow() {
            int newCap = flatIndices.length << 1;
            flatIndices = Arrays.copyOf(flatIndices, newCap);
            utility = Arrays.copyOf(utility, newCap);
        }

        public void trimToSize() {
            if (size < flatIndices.length) {
                flatIndices = Arrays.copyOf(flatIndices, size);
                utility = Arrays.copyOf(utility, size);
            }
        }
    }

    /**
     * Trackers of one pattern, ordered by increasing seqId (callers iterate the parent
     * trackers in that order).
     *
     * Trackers are borrowed and released through the pool helpers of PreHUSPMAdaptiveAlgo.
     */
    static class TrackerList {
        final ArrayList<Tracker> trackers = new ArrayList<>();

        public boolean isEmpty() { return trackers.isEmpty(); }
        public int size() { return trackers.size(); }

        /**
         * Trims the arrays of trackers with large slack (at least 4 slots and 25%).
         * Small slack is left alone to avoid a burst of copies.
         */
        public void trimToSize() {
            for (int i = 0, n = trackers.size(); i < n; i++) {
                Tracker t = trackers.get(i);
                int cap = t.flatIndices.length;
                int wasted = cap - t.size;
                if (wasted >= 4 && wasted * 4 >= cap) {
                    t.trimToSize();
                }
            }
        }
    }

    // ========================================================================
    // MINER AND INCREMENTAL MAINTENANCE
    // ========================================================================
    public static class PreHUSPMAdaptiveAlgo {
        public enum AblationMode { FULL, VARIANCE_ONLY, RHO_ONLY }

        // kappa weights the dynamic term of the adaptive band.
        public double Su, kappa, delta0, Sl_min;
        AblationMode mode;
        boolean writeToFile;
        // No depth cap by default: a pattern is never longer than a sequence that contains it, so
        // the data bounds the recursion, and a cap would drop patterns silently.
        public int maxDepth = Integer.MAX_VALUE;
        /** Block index ablation: if false, the direct update reads every sequence of the batch. */
        public boolean useBlockIndex = true;

        /**
         * Candidate budget for one mining pass. Off by default (Long.MAX_VALUE).
         *
         * Valid runs may generate billions of candidates (candidateCount counts every
         * generated item, before pruning), so no finite value is safe for all of them.
         * Runaway runs are stopped by the runner's timeout through cooperative
         * cancellation. Set a finite value only to stop an ablation early, and keep it
         * above any valid run. Only this algorithm has the budget; the baselines do not.
         */
        public long maxCandidatesBudget = Long.MAX_VALUE;

        // ---- Work-based adaptation (optional, off by default) ----
        //
        // The other control signals here (cubValue, swuMax, iauMax) measure utility and
        // serve correctness. None of them predicts how much work the next mining pass
        // costs, and that cost is what explodes.
        //
        // The two mechanisms below never change the rescan trigger or the floor below
        // LUT_dyn. They only raise Sl_min, which narrows the pre-large band; the safety
        // argument holds for every floor ratio in (0,1).
        //
        // Off by default so that existing results reproduce; flags enable each one.

        /** Configured floor ratio, kept to report how far the effective one moved. */
        public double slMinRatioCfg;
        /** Floor ratio in use. Only increases. */
        public double slMinRatioEff;
        /** Repetition of the database: occurrences per distinct item. 0 until measured. */
        public double repetition = 0;
        /** Number of times the floor was raised because a mining pass exceeded its budget. */
        public int rescanRetries = 0;
        /**
         * Trigger quantity at the rescan decision, recorded before resetIau() clears the
         * counters. Reading iauMax after a rescanning batch would always give 0, so this
         * field is what the CSV reports.
         */
        public double iauMaxAtDecision = 0;

        /** Choose the starting floor from the repetition of the database. -Dadapt.densityFloor=true */
        public boolean useDensityFloor = Boolean.getBoolean("adapt.densityFloor");
        /**
         * Candidate cap for one mining pass during a rescan. 0 = off.
         * When exceeded, the floor is raised and the pass retried, instead of running out
         * of memory or time.
         * -Dadapt.rescanBudget=3000000000
         */
        public long rescanCandidateBudget = Long.getLong("adapt.rescanBudget", 0L);

        /**
         * Wall-clock safety net for one mining pass. 0 = off.
         *
         * The candidate cap is the primary limit because it is deterministic: a pass is
         * cut at the same point on every machine. It only catches cost proportional to the
         * number of candidates, however; a pass whose time per candidate is very high
         * passes under it. This net catches that case.
         *
         * -Dadapt.rescanMillis=600000
         */
        public long rescanMillisBudget = Long.getLong("adapt.rescanMillis", 0L);
        /** Absolute deadline of the current mining pass; 0 = none. */
        private long mineDeadlineNanos = 0;
        /** Sampling counter, so nanoTime() is not called on every candidate. */
        private int deadlineTick = 0;

        private static final double SL_RATIO_CAP = 0.98;   // above this the pre-large band is effectively empty
        private static final double SL_RATIO_STEP = 0.05;
        private static final int    SL_MAX_RETRIES = 6;

        /**
         * Whether the safe (no-rescan) branch also mines each batch for new patterns.
         *   - false (default): the safe branch only updates tracked patterns; new patterns
         *     are found by rescans, which the trigger rule schedules.
         *   - true: mines deltaD on every batch. Slower, and may explode on batches with
         *     very dense sequences. For comparison only.
         */
        public boolean mineNewPatternsPerBatch = false;

        /**
         * Adaptive pre-large floor, for ablation:
         *   - true (default): the band [lutDynValue, uut) follows δ_D.
         *   - false: fixed band [lut_min, uut), as in Pre-HUSPM.
         * Has no effect under the JOINT trigger rule, which uses a per-pattern floor.
         */
        public boolean useAdaptiveBand = true;

        /** IUB branch pruning ablation: if false, branches are never cut by IUB. */
        public boolean useIub = true;

        /**
         * Irrelevant-item removal in the static miner (initial mine and every rescan).
         *
         * This is the irrelevant item pruning strategy (IPS) of HUSP-ULL (Gan et al., IEEE
         * Transactions on Cybernetics 2021, Theorem 9), which HUSP-SP also adopts as IIP, applied
         * with the mining cutoff as the threshold. The sequences in which i occurs after the first
         * occurrence end of t are exactly those holding the I- or S-extension of t by i, so B^R
         * below is the sum of their IPS.
         *
         * Rule. For a node t with occurrence lists and a set R of removed items,
         *   PEU^R(t,s) = max over the occurrences (k,w) of t in s of w + ru^R_k,
         * where ru^R_k is the utility of the items after position k that are not in R. For an
         * item i not in R,
         *   B^R(i,t) = sum, over the sequences s in which i occurs after the first occurrence
         *              end of t, of PEU^R(t,s).
         * If B^R(i,t) < theta, no pattern below t whose extension part contains i reaches theta
         * (its utility in s is at most PEU^R(t,s), and only those sequences can contain it), so
         * i is removed for the whole subtree of t: it is never an extension candidate there and
         * its utility is excluded from ru for every bound computed in that subtree. At the root
         * (empty pattern) B^R(i) is the sequence-weighted utility of i over the non-removed
         * utility. Applying the test once per node or repeating it to a fixpoint are both exact.
         * theta is the mining cutoff passed to the miner.
         *
         *   OFF       the miner as before, bit for bit
         *   ONCE      one pass at the root and one pass per node (default)
         *   FIXPOINT  repeat each pass until no item is removed
         *
         * -Dadapt.iip=off|once|fixpoint
         */
        public enum IipMode { OFF, ONCE, FIXPOINT }
        /** The campaign's value: "once", chosen by the calibration (results/calibration/decision.txt). */
        public static final String IIP_DEFAULT = "once";
        public IipMode iipMode = configuredIipMode();

        /** The mode a new miner takes: -Dadapt.iip, else IIP_DEFAULT. */
        public static IipMode configuredIipMode() {
            return parseIipMode(System.getProperty("adapt.iip", IIP_DEFAULT));
        }

        public static IipMode parseIipMode(String s) {
            switch (s.trim().toLowerCase(java.util.Locale.ROOT)) {
                case "off":      return IipMode.OFF;
                case "once":     return IipMode.ONCE;
                case "fixpoint": return IipMode.FIXPOINT;
                default: throw new IllegalArgumentException(
                        "adapt.iip must be off|once|fixpoint, got: " + s);
            }
        }

        /** Adaptive rescan ablation: if false, every batch triggers a rescan. */
        public boolean useAdaptiveRescan = true;

        /**
         * true (default): a modification charges u(old) + u(new) to the budget, a valid
         * upper bound. false: charges only |u(new) - u(old)|, which is NOT sound.
         */
        public boolean strictModificationBudget =
                !"false".equalsIgnoreCase(System.getProperty("adapt.strictModify", "true"));

        /**
         * Block size of the block-level inverted index. Set with -Dblock.size=N; the value
         * in use is reported in the CSV column block_size.
         */
        public int blockSize = Integer.getInteger("block.size", 100);

        // --- Cooperative cancellation for the runner's timeout ---
        // volatile so that a flag set by the runner thread is seen by the mining thread.
        // Checked on entry to expandNodeWithPruning, so the miner stops within microseconds.
        private volatile boolean cancelled = false;
        public void cancel() { this.cancelled = true; }
        public boolean isCancelled() { return cancelled; }

        // --- Metrics exported to the result CSV ---
        public int rescanCount = 0, patternCount = 0;
        public long candidateCount = 0, blocksReadCount = 0, numBlocksSkipped = 0;
        public long exploredNodes = 0; // nodes actually expanded; the PEU bound is applied before materialization, so this is far below candidate_count
        // prunedValueBound = items removed by the aggregated PEU value bound.
        public long prunedValueBound = 0, prunedIub = 0;
        // removedItems = items removed by irrelevant-item removal, summed over the root and every
        // node (an item removed at a node stays removed below it and is not counted again there).
        public long removedItems = 0;
        // removedItemsRoot = the part of removedItems removed at the root (sequence-weighted utility).
        public long removedItemsRoot = 0;

        // --- Counters and adaptive band ---
        public double lutDynValue, deltaDValue, sigmaDSquared, rhoD;
        public double cubValue = 0, sftValue = 0;

        // ====================================================================
        // RESCAN TRIGGER RULES
        // ====================================================================
        /**
         * Rescan trigger rules, selected by flag.
         *
         * SWUMAX  Comparison rule: rescan iff max_{s in d} u(s) > SFT, the utility of the
         *         largest inserted sequence. This is not the SWUmax of Yan et al., which is the
         *         largest sequence-weighted utility of an item and gives a sound test.
         *         NOT sound: u(s) is the utility of ONE sequence, while the gain of a
         *         pattern is the sum over every inserted sequence that contains it.
         *         Example: UUT=0.25, LUT=0.20, TSU=100, u(X)=19; inserting two sequences
         *         that contain X with u=6 gives SWUmax=6 <= 6.67, so no rescan, yet after
         *         the update u(X)=31 > 0.25*112=28 and X is a missed HUSP.
         *         Kept for comparison only.
         *
         * CUB     Raw accumulation: rescan iff sum of u(s) over sequences inserted since the
         *         last rescan > SFT. Sound, but loose: it assumes all inserted utility may
         *         go to one pattern.
         *
         * IAUMAX  rescan iff max_i A_i > SFT, with
         *         A_i = sum of u(s) over sequences inserted since the last rescan with i in s.
         *         Never looser than CUB. If X is in s then every item of X is in s, so the
         *         gain of X is bounded by min_{i in X} A_i <= max_i A_i. Reduces to CUB when
         *         one item occurs in every inserted sequence.
         *
         * JOINT   Default. See below.
         *
         * PRE_SWUMAX, PRE_TSU  The two rescan tests of Pre-HUSPM (Yan et al., Theorems 2 and 1),
         *         as algorithms.PreHUSPMAlgorithm applies them, put in front of this miner and
         *         of JOINT's tracking, to separate the effect of the rule from the effect of the
         *         miner. With q the largest sequence-weighted utility of an item in the batch
         *         (PRE_SWUMAX) or the batch's total utility (PRE_TSU), a batch is absorbed when
         *         buf + q <= f and then buf += q; otherwise the database is rescanned and buf = 0;
         *         f = (UUT - LUT_min)/(1 - UUT) * TSU_r. Everything else is JOINT's: A_i, the
         *         per-pattern drop floor L_r + min_{i in X} A_i, the rescan at L_r.
         *         Sound for insertions: buf >= max_i A_i and A_i <= I, so while the test holds
         *         max_i A_i - UUT*I <= (1 - UUT)*max_i A_i <= (1 - UUT)*f = (UUT - LUT_min)*TSU_r,
         *         which is JOINT's condition, and JOINT's argument applies. Pre-HUSPM defines
         *         both tests for insertions only, so deletions and modifications are refused.
         */
        public enum TriggerRule { SWUMAX, CUB, IAUMAX, JOINT, PRE_SWUMAX, PRE_TSU }

        /** JOINT and the two Pre-HUSPM tests in front of JOINT's tracking. */
        private boolean jointTracking() {
            return triggerRule == TriggerRule.JOINT || triggerRule == TriggerRule.PRE_SWUMAX
                    || triggerRule == TriggerRule.PRE_TSU;
        }

        /** Pre-HUSPM's buf: the q of every batch absorbed since the last rescan. */
        private double preBuf = 0;

        /**
         * What the tracked set holds.
         *
         * UTILITY  Default: a pattern is tracked while its exact utility stays at or above its
         *          floor (L_r after a rescan, JOINT's per-pattern floor after a direct update).
         * PEU      Tracked by an upper bound, as Pre-HUSPM's buffer is (its SWU_proj, summed per
         *          sequence as the largest prefix utility plus rest utility): a rescan tracks
         *          every node whose bound IUB reaches L_r, i.e. every node the miner expands,
         *          with B(X) = IUB(X) as the miner computes it; a direct insertion adds
         *          u(X, batch) to u(X) and PEU(X, batch) to B(X), and drops X only when
         *          B(X) < L_r + min_{i in X} A_i. The search is the same; only the recording and
         *          the drop test change, so the cost of tracking by a bound is measured apart
         *          from the miner. Sound under JOINT: B(X) >= u(X) always, so a dropped pattern
         *          satisfies JOINT's invariant u(X) < L_r + min_i A_i, and every pattern the
         *          UTILITY tracking would hold is held. Insertions under JOINT only; set with
         *          -Dadapt.tracking=peu.
         */
        public enum Tracking { UTILITY, PEU }

        public Tracking tracking = parseTracking(System.getProperty("adapt.tracking", "utility"));

        private static Tracking parseTracking(String s) {
            switch (s.trim().toLowerCase(java.util.Locale.ROOT)) {
                case "utility": return Tracking.UTILITY;
                case "peu":     return Tracking.PEU;
                default: throw new IllegalArgumentException("adapt.tracking must be utility|peu, got: " + s);
            }
        }

        /** A copy of the tracked set (large and pre-large patterns) with their utilities, for checks. */
        public Map<CompactPattern, Double> trackedUtilities() {
            Map<CompactPattern, Double> m = new HashMap<>(largeMap);
            m.putAll(prelargeMap);
            return m;
        }

        /** PEU tracking: B(X) of every tracked pattern. */
        public final Map<CompactPattern, Double> trackedBound = new HashMap<>();
        /** PEU tracking: bounds of the nodes recorded by the mine in progress; null otherwise. */
        private Map<CompactPattern, Double> minedBounds = null;
        /** PEU of the pattern in the sequence of the last calculateExactUtilityInSeq call. */
        private double lastSeqPeu = 0;

        private void refuseUnderPeuTracking(String op) {
            if (tracking == Tracking.PEU && triggerRule != TriggerRule.JOINT)
                throw new UnsupportedOperationException("PEU tracking is defined under the JOINT rule only, not "
                        + triggerRule);
            if (tracking == Tracking.PEU && op != null)
                throw new UnsupportedOperationException("PEU tracking is defined for insertions only; " + op
                        + " is refused");
        }

        private void refuseUnderPreTest(String op) {
            if (triggerRule == TriggerRule.PRE_SWUMAX || triggerRule == TriggerRule.PRE_TSU)
                throw new UnsupportedOperationException("the Pre-HUSPM rescan tests (" + triggerRule
                        + ") are defined for insertions only; " + op + " is refused");
        }

        /*
         * JOINT: one budget for insertions and deletions since the last rescan, with a
         * per-pattern drop floor in the direct-update branch.
         *   I = inserted utility, D = deleted utility, A_i = inserted utility of the
         *   sequences containing item i, all since the last rescan; L_r = LUT_min * TSU_r.
         *   Rescan iff  max_i A_i - UUT*I + UUT*D > (UUT - LUT_min) * TSU_r.
         *   A tracked pattern X is dropped only if u(X) < L_r + min_{i in X} A_i.
         * Every untracked X then satisfies u(X) < L_r + min_{i in X} A_i at all later times
         * (its gain is bounded by min_{i in X} A_i, deletions only lower it), and the rule
         * keeps L_r + max_i A_i <= UUT * (TSU_r + I - D) = UUT * TSU, so no untracked X is a
         * HUSP while the rule holds.
         */
        private double insertedSinceRescan = 0, deletedSinceRescan = 0;

        private double minIauOf(CompactPattern p) {
            double m = Double.POSITIVE_INFINITY;
            for (int item : p.flat) {
                if (item < 0) continue;
                double a = item < iau.length ? iau[item] : 0.0;
                if (a < m) m = a;
            }
            return m == Double.POSITIVE_INFINITY ? 0.0 : m;
        }

        private double jointTriggerQuantity() {
            return iauMax - Su * insertedSinceRescan + Su * deletedSinceRescan;
        }

        private double jointFloor(CompactPattern p) {
            return Sl_min * tsuAtLastRescan + minIauOf(p);
        }

        /** Trigger rule. Override with -Dadapt.trigger=swumax|cub|iaumax|joint. */
        public TriggerRule triggerRule = parseTriggerRule(
                System.getProperty("adapt.trigger", "joint"));

        private static TriggerRule parseTriggerRule(String s) {
            switch (s.trim().toLowerCase(java.util.Locale.ROOT)) {
                case "swumax": return TriggerRule.SWUMAX;
                case "cub":    return TriggerRule.CUB;
                case "iaumax": return TriggerRule.IAUMAX;
                case "joint":  return TriggerRule.JOINT;
                case "pre_swumax": return TriggerRule.PRE_SWUMAX;
                case "pre_tsu":    return TriggerRule.PRE_TSU;
                default: throw new IllegalArgumentException(
                        "adapt.trigger must be swumax|cub|iaumax|joint|pre_swumax|pre_tsu, got: " + s);
            }
        }

        /**
         * A_i: inserted utility accumulated per item since the last rescan.
         * Indexed by itemId and grown on demand. Updated once per loaded batch at cost
         * O(sum of |s| over the batch).
         */
        private double[] iau = new double[256];
        /** max_i A_i, maintained incrementally. */
        public double iauMax = 0;
        /** CUB / iauMax for the latest batch (exported to CSV). */
        public double iauTightness = 1.0;
        // TSU at the last rescan (or initial mining): reference for the safety bounds and
        // for the JOINT drop floor.
        public double tsuAtLastRescan = 0;
        public boolean cubTriggeredRescan = false;

        // --- Pre-large count and absolute HUSP threshold (exported to CSV) ---
        public int prelargeCount = 0;   // |PL| = prelargeMap.size()
        public double uutValue = 0;     // uut_abs = TSU * Su

        BlockIndex globalBlockIndex = new BlockIndex();
        // Result maps are keyed by CompactPattern; conversion to Pattern happens only on
        // export or re-evaluation.
        public Map<CompactPattern, Double> largeMap = new HashMap<>();

        /**
         * Snapshot of the large set as strings, so a verifier compares every pattern and
         * its utility against mining from scratch, not only the pattern count.
         */
        public java.util.Map<String, Double> exportLargeAsStrings() {
            java.util.Map<String, Double> out = new java.util.HashMap<>(largeMap.size() * 2);
            for (Map.Entry<CompactPattern, Double> e : largeMap.entrySet()) {
                out.put(e.getKey().toPattern().toIntuitiveString(), e.getValue());
            }
            return out;
        }

        /** Snapshot of the pre-large set, for diagnosis when large sets disagree. */
        public java.util.Map<String, Double> exportPrelargeAsStrings() {
            java.util.Map<String, Double> out = new java.util.HashMap<>(prelargeMap.size() * 2);
            for (Map.Entry<CompactPattern, Double> e : prelargeMap.entrySet()) {
                out.put(e.getKey().toPattern().toIntuitiveString(), e.getValue());
            }
            return out;
        }
        Map<CompactPattern, Double> prelargeMap = new HashMap<>();
        // Sequence lookup is on the hot path of every tracker iteration. Sequence ids
        // from DatasetLoader are dense [0..N), so a direct array is used; sparse ids only
        // leave null slots.
        private Sequence[] dbLookupArr = new Sequence[1024];

        private void dbPut(int seqId, Sequence seq) {
            if (seqId >= dbLookupArr.length) {
                int newCap = Math.max(seqId + 1, dbLookupArr.length * 2);
                dbLookupArr = Arrays.copyOf(dbLookupArr, newCap);
            }
            dbLookupArr[seqId] = seq;
        }

        private Sequence dbGet(int seqId) {
            if (seqId < 0 || seqId >= dbLookupArr.length) return null;
            return dbLookupArr[seqId];
        }

        private void dbRemove(int seqId) {
            if (seqId >= 0 && seqId < dbLookupArr.length) dbLookupArr[seqId] = null;
        }

        private void dbClear() {
            Arrays.fill(dbLookupArr, null);
        }

        // Largest item id in the database; sizes the scratch arrays below.
        private int maxItemId = -1;
        // Pass-1 scratch arrays indexed by item id, reused across trackers.
        private transient double[] scratchLocalI, scratchLocalS, scratchAggI, scratchAggS;
        private transient int[] scratchUsedI, scratchUsedS;
        private transient int scratchUsedICount, scratchUsedSCount;
        // "Seen" flags, reset selectively.
        private transient boolean[] scratchLocalSeenI, scratchLocalSeenS;
        private transient boolean[] scratchAggSeenI, scratchAggSeenS;
        private transient int[] scratchLocalUsedI, scratchLocalUsedS;
        private transient int scratchLocalUsedICount, scratchLocalUsedSCount;
        // Survivor flags, held as fields to avoid allocation per call.
        private transient boolean[] scratchSurvivingI, scratchSurvivingS;

        // ====================================================================
        //  Survivor pool (depth-local snapshot arrays)
        //  scratchUsedI/S and scratchSurvivingI/S are fields that the recursion
        //  overwrites, so the surviving items of a node must be copied into a
        //  depth-local array before the Pass-2 loop.
        // ====================================================================
        private transient List<int[]> iSurvivorPool = new ArrayList<>();
        private transient List<int[]> sSurvivorPool = new ArrayList<>();

        /** Borrows the survivor array for a depth, reallocating it if too small. */
        private int[] borrowSurvivorArray(List<int[]> pool, int depth, int sz) {
            while (pool.size() <= depth) pool.add(null);
            int[] arr = pool.get(depth);
            if (arr == null || arr.length < sz) {
                arr = new int[sz];
                pool.set(depth, arr);
            }
            return arr;
        }

        // ===== Single-pass Pass 2: collect every child in one scan of the parent =====
        // iSlot/sSlot[itemId] = child TrackerList (allocated in allocateScratch). Used
        // within one node, then moved into a depth-local array and cleared before recursion.
        private transient TrackerList[] iSlot, sSlot;
        private transient long[] spSurvGenI, spSurvGenS; // generation marker: survivor of the current node
        private long spNodeGen = 0;
        private transient List<TrackerList[]> iTLPool = new ArrayList<>();
        private transient List<TrackerList[]> sTLPool = new ArrayList<>();
        private TrackerList[] borrowTLArray(List<TrackerList[]> pool, int depth, int sz) {
            while (pool.size() <= depth) pool.add(null);
            TrackerList[] arr = pool.get(depth);
            if (arr == null || arr.length < sz) { arr = new TrackerList[sz]; pool.set(depth, arr); }
            return arr;
        }
        // Materialization strategy threshold: nodes with at most this many parent trackers
        // use single-pass; wider nodes use the lazy path, which builds one child at a time.
        private static final int SINGLE_PASS_PT_CAP = Integer.getInteger("adapt.singlePassCap", 100_000);

        // ====================================================================
        //  OBJECT POOLS - TrackerPool and TrackerListPool
        //
        //  Fixed arrays with a top pointer: no iterator allocation, LIFO reuse.
        //  Warm-up pre-allocates trackers and lists before mining. A release into a
        //  full pool drops the object; a borrow from an empty pool allocates a new one.
        // ====================================================================
        private static final int POOL_MAX_SIZE = 1 << 16;       // 65K cap
        private static final int POOL_TRACKER_ARRAY_CAP = 512;  // shrink Tracker arrays >512 before recycle
        /**
         * A TrackerList that held more trackers than this is not pooled again.
         *
         * clear() empties an ArrayList but keeps its backing array at the largest size it
         * ever reached, so a pooled list keeps the footprint of the widest node it served.
         * A rescan over a grown database serves wider nodes than the one before, and an
         * uncapped pool would retain all of them. Dropping the wide lists keeps the pool
         * for the many narrow nodes it is meant for.
         */
        private static final int POOL_LIST_CAP = 1024;
        private static final int POOL_WARMUP_TRACKERS     = 4096; // pre-allocated before mining
        private static final int POOL_WARMUP_TRACKER_LISTS = 256;

        /**
         * TrackerPool: stack over a fixed array.
         *  - pool[0..top-1] holds trackers waiting for reuse.
         *  - borrow() takes from the top (LIFO, cache-warm).
         *  - release(t) pushes on top; drops t if the pool is full.
         *  - warmup(n) pre-allocates n trackers.
         */
        static final class TrackerPool {
            final Tracker[] pool = new Tracker[POOL_MAX_SIZE];
            int top = 0;

            Tracker borrow(int seqId) {
                if (top == 0) {
                    // Pool empty: allocate.
                    return new Tracker(seqId);
                }
                Tracker t = pool[--top];
                pool[top] = null;       // help GC
                t.reset(seqId);
                return t;
            }

            void release(Tracker t) {
                // Shrink oversized arrays before reuse to bound pool memory.
                if (t.flatIndices.length > POOL_TRACKER_ARRAY_CAP) {
                    t.flatIndices = new int[4];
                    t.utility = new double[4];
                }
                if (top < POOL_MAX_SIZE) pool[top++] = t;
                // else: dropped, left to GC
            }

            /** Pre-allocates up to n trackers; called before mining, not during recursion. */
            void warmup(int n) {
                while (top < n && top < POOL_MAX_SIZE) {
                    pool[top++] = new Tracker(-1);
                }
            }
        }

        /**
         * TrackerListPool: same scheme for TrackerList. release() returns the nested
         * trackers to the TrackerPool and clears the list.
         */
        static final class TrackerListPool {
            final TrackerList[] pool = new TrackerList[POOL_MAX_SIZE];
            int top = 0;
            private final TrackerPool trackerPool; // receives the nested trackers

            TrackerListPool(TrackerPool tp) { this.trackerPool = tp; }

            TrackerList borrow() {
                if (top == 0) {
                    return new TrackerList(); // pool empty: allocate
                }
                TrackerList tl = pool[--top];
                pool[top] = null;
                // trackers is already empty (cleared on release)
                return tl;
            }

            void release(TrackerList tl) {
                // Return the nested trackers before reusing the list.
                ArrayList<Tracker> ts = tl.trackers;
                final int n = ts.size();
                for (int i = 0; i < n; i++) trackerPool.release(ts.get(i));
                ts.clear();
                if (n > POOL_LIST_CAP) return;   // drop: its backing array stays at its peak size
                if (top < POOL_MAX_SIZE) pool[top++] = tl;
            }

            void warmup(int n) {
                while (top < n && top < POOL_MAX_SIZE) {
                    pool[top++] = new TrackerList();
                }
            }
        }

        private transient final TrackerPool trackerPool = new TrackerPool();
        private transient final TrackerListPool trackerListPool = new TrackerListPool(trackerPool);

        /** Pool helpers used on the hot path. */
        private Tracker borrowTracker(int seqId) { return trackerPool.borrow(seqId); }
        private void releaseTracker(Tracker t)   { trackerPool.release(t); }
        private TrackerList borrowTrackerList()  { return trackerListPool.borrow(); }
        private void releaseTrackerList(TrackerList tl) { trackerListPool.release(tl); }

        // ====================================================================
        //  SCRATCH BUFFER MERGE OF OCCURRENCES, indexed by flat position
        //
        //    scratchMaxUtil[flatIdx]  = largest utility reaching flatIdx
        //    scratchPresent[flatIdx]  = flatIdx has been touched
        //    scratchUsedFlat[i]       = touched positions, for selective reset
        //
        //  For each parent sequence: every match at position q either marks q present
        //  and records its utility, or raises scratchMaxUtil[q]. The touched positions
        //  are then appended to the child tracker and only scratchPresent is reset.
        //
        //  A separate present flag is used instead of utility 0 as a sentinel, so a
        //  zero-utility occurrence is not mistaken for an absent one.
        // ====================================================================
        private transient double[] scratchMaxUtil;
        private transient boolean[] scratchPresent;
        private transient int[] scratchUsedFlat;
        private int maxFlatLen = 0; // largest flat length seen across sequences

        private void ensureScratchCapacity(int needed) {
            if (scratchMaxUtil == null || scratchMaxUtil.length < needed) {
                int newCap = Math.max(needed, 256);
                scratchMaxUtil = new double[newCap];
                scratchPresent = new boolean[newCap];
                scratchUsedFlat = new int[newCap];
            }
        }

        public PreHUSPMAdaptiveAlgo(double Su, double kappa, double delta0, double SlMinRatio, AblationMode mode, boolean writeToFile) {
            this.Su = Su; this.kappa = kappa; this.delta0 = delta0; this.Sl_min = SlMinRatio * Su;
            this.slMinRatioCfg = SlMinRatio; this.slMinRatioEff = SlMinRatio;
            this.mode = mode; this.writeToFile = writeToFile;
        }

        // ================= PHASE 1: INITIAL MINING =================
        public void runInitialMining(List<Sequence> dInit, double currentTotalUtility) {
            // Reset the whole state, including the cancellation flag, so an instance
            // cancelled in an earlier run may be reused (the runner also refuses
            // poisoned instances).
            cancelled = false;
            rescanCount = 0; patternCount = 0;
            candidateCount = 0; exploredNodes = 0; blocksReadCount = 0; numBlocksSkipped = 0;
            prunedValueBound = 0; prunedIub = 0; removedItems = 0; removedItemsRoot = 0;
            cubValue = 0; cubTriggeredRescan = false;
            resetIau();
            insertedSinceRescan = 0; deletedSinceRescan = 0; preBuf = 0;
            largeMap.clear(); prelargeMap.clear();
            dbClear();
            globalBlockIndex = new BlockIndex();
            if (blockSize < 1) throw new IllegalArgumentException("block.size must be >= 1");
            globalBlockIndex.blockSize = blockSize;

            globalBlockIndex.buildFromBatch(dInit);
            for (Sequence s : dInit) {
                s.calculateRemainingUtilities();
                s.buildFlatRepresentation();    // flat 1D arrays
                if (s.flatLen > maxFlatLen) maxFlatLen = s.flatLen;
                dbPut(s.id, s);
                for (QItemset is : s.itemsets) for (QItem it : is.items)
                    if (it.id > maxItemId) maxItemId = it.id;
            }
            allocateScratch(maxItemId + 1);
            ensureScratchCapacity(maxFlatLen);
            // Pool warm-up before the recursion starts.
            trackerPool.warmup(POOL_WARMUP_TRACKERS);
            trackerListPool.warmup(POOL_WARMUP_TRACKER_LISTS);

            // Density-based starting floor: measure the repetition of the database and
            // choose the starting floor. It only raises the floor, so a sparse database
            // keeps the configured ratio.
            this.repetition = common.Density.repetition(dInit);
            if (useDensityFloor) {
                double suggested = common.Density.suggestFloor(slMinRatioCfg, repetition);
                if (suggested > slMinRatioEff) { slMinRatioEff = suggested; Sl_min = slMinRatioEff * Su; }
            }

            // Initial LUT_dyn and SFT.
            double uut_abs = currentTotalUtility * Su;
            this.uutValue = uut_abs; // absolute HUSP threshold (exported to CSV)
            this.lutDynValue = currentTotalUtility * Sl_min; // starts at the fixed floor
            // SFT is the absolute utility gap between the large threshold (uut_abs) and the
            // pre-large floor (lutDynValue); same unit as cubValue.
            this.sftValue = uut_abs - this.lutDynValue;

            mineWithBudget(dInit, uut_abs, currentTotalUtility);
            this.tsuAtLastRescan = currentTotalUtility; // TSU reference for the safety bounds
        }

        // ================= PHASE 2: INSERTION =================
        public void runIncrementalMining(List<Sequence> deltaD, List<Sequence> dSoFar, double newTotalUtility) {
            refuseUnderPeuTracking(null);
            cancelled = false; // clear a cancellation left by an earlier measurement
            // candidateCount is reset every batch: the candidate budget applies per batch,
            // and candidate_count reports the cost of each batch.
            candidateCount = 0; exploredNodes = 0; removedItems = 0; removedItemsRoot = 0;
            globalBlockIndex.buildFromBatch(deltaD);
            double batchTotalUtil = 0;
            double swuMax = 0;

            for (Sequence s : deltaD) {
                s.calculateRemainingUtilities();
                s.buildFlatRepresentation();
                if (s.flatLen > maxFlatLen) maxFlatLen = s.flatLen;
                dbPut(s.id, s);
                batchTotalUtil += s.sequenceUtility;
                if (s.sequenceUtility > swuMax) swuMax = s.sequenceUtility;
                accumulateIau(s);          // A_i += u(s) for every item i in s
                for (QItemset is : s.itemsets) for (QItem it : is.items)
                    if (it.id > maxItemId) maxItemId = it.id;
            }
            allocateScratch(maxItemId + 1);
            ensureScratchCapacity(maxFlatLen);
            // Warm up again: the pools may have been drained.
            trackerPool.warmup(POOL_WARMUP_TRACKERS);
            trackerListPool.warmup(POOL_WARMUP_TRACKER_LISTS);

            double uut_abs = newTotalUtility * Su;
            this.uutValue = uut_abs; // absolute HUSP threshold (exported to CSV)
            double lut_min_abs = newTotalUtility * Sl_min;

            // Required order within a batch:
            //   load batch -> update counters (CUB, A_i) -> test the trigger ->
            //   on rescan, reset the counters -> only then compute Delta and LUT_dyn.
            // Computing Delta before the reset would mine the rescan with a floor inflated
            // by counters that the rescan clears.

            // --- Step 1: update the accumulated counters ---
            this.cubValue += batchTotalUtil;       // perturbation accumulated since the last rescan
            this.insertedSinceRescan += batchTotalUtil;
            this.iauTightness = (iauMax <= 0) ? 1.0 : (cubValue / iauMax);
            boolean joint = jointTracking();
            // The batch statistics feed LUT_dyn only, and JOINT has no LUT_dyn.
            this.sigmaDSquared = joint ? 0 : calculateVariance(deltaD);
            this.rhoD = (joint || batchTotalUtil == 0) ? 0 : (swuMax / batchTotalUtil);

            // --- Step 2: test the trigger ---
            // Insertion safety bound of Hong et al.: (UUT - LUT_min)/(1 - UUT) * TSU_ref.
            double safetyBoundF = ((Su - Sl_min) / (1.0 - Su)) * tsuAtLastRescan;
            this.sftValue = safetyBoundF;
            double triggerQuantity;
            switch (this.triggerRule) {
                case SWUMAX: triggerQuantity = swuMax;   break;  // not sound
                case CUB:    triggerQuantity = cubValue; break;
                case JOINT:  triggerQuantity = jointTriggerQuantity(); break;
                case PRE_SWUMAX: triggerQuantity = preBuf + algorithms.PreHUSPMAlgorithm.PreHUSPMAlgo.maxItemSwu(deltaD); break;
                case PRE_TSU:    triggerQuantity = preBuf + batchTotalUtil; break;
                case IAUMAX: default: triggerQuantity = iauMax; break;
            }
            if (this.triggerRule == TriggerRule.JOINT) {
                safetyBoundF = (Su - Sl_min) * tsuAtLastRescan;
                this.sftValue = safetyBoundF;
            }
            // Adaptive rescan ablation: useAdaptiveRescan=false rescans every batch.
            this.cubTriggeredRescan = useAdaptiveRescan ? (triggerQuantity > safetyBoundF) : true;
            this.iauMaxAtDecision = triggerQuantity;   // recorded before resetIau() below

            // --- Step 3: on rescan, reset the counters before computing Delta ---
            if (cubTriggeredRescan) {
                rescanCount++;
                this.cubValue = 0;
                resetIau();                             // reset A_i
                insertedSinceRescan = 0; deletedSinceRescan = 0; preBuf = 0;
                this.tsuAtLastRescan = newTotalUtility; // new TSU reference
            } else if (this.triggerRule == TriggerRule.PRE_SWUMAX || this.triggerRule == TriggerRule.PRE_TSU) {
                preBuf = triggerQuantity;               // buf += q
            }

            if (joint) {
                // JOINT keeps no global band: the drop floor is per pattern (jointFloor), and
                // a rescan mines at L_r = LUT_min * TSU_ref. lut_dyn reports L_r.
                this.lutDynValue = Sl_min * tsuAtLastRescan;
                this.deltaDValue = uut_abs - this.lutDynValue;
            } else {
                // --- Step 4: adaptive width Delta, with a lower bound ---
                // Required lower bound: a pattern dropped at LUT_dyn must not become a HUSP
                // before the next rescan. The remaining insertion budget is
                // A_max = SFT_ins - (current counter). For every A_future <= A_max we need
                // u(X,D') + A_future < UUT*(TSU' + A_future); the difference of the two sides
                // grows with factor (1 - UUT) > 0, so the worst case is A_future = A_max,
                // giving u(X,D') < UUT*TSU' - (1-UUT)*A_max, i.e.
                //     Delta >= (1 - UUT)*A_max = (UUT - LUT_min)*TSU_ref - (1 - UUT)*(counter).
                // With the counter at 0 (right after a rescan) this equals the fixed band, so
                // LUT_dyn falls back to LUT_min*TSU_ref.
                // The final cap is UUT*TSU' rather than (UUT - LUT_min)*TSU' so that the lower
                // bound is never cut; LUT_dyn >= 0 always holds.
                double consumedIns = cubTriggeredRescan ? 0.0
                        : (this.triggerRule == TriggerRule.SWUMAX ? cubValue : triggerQuantity);
                double deltaMinIns = Math.max(0.0,
                        (Su - Sl_min) * tsuAtLastRescan - (1.0 - Su) * consumedIns);

                double dynamicTerm = 0;
                switch (this.mode) {
                    case FULL: dynamicTerm = kappa * sigmaDSquared * rhoD; break;
                    case VARIANCE_ONLY: dynamicTerm = kappa * sigmaDSquared; break;
                    case RHO_ONLY: dynamicTerm = kappa * rhoD; break;
                }

                // The HEURISTIC term stays capped at (UUT - LUT_min)*TSU'. Without that cap the
                // +CUB term would pull LUT_dyn to 0 and make every pattern pre-large. The safe
                // lower bound deltaMin is applied AFTER the cap and may exceed it (only in the
                // deletion phase, when TSU' < TSU_ref); the final clamp at UUT*TSU' keeps
                // LUT_dyn >= 0.
                //
                // Correctness rests on deltaMin, not on the +CUB term: the cap may cut that
                // term, so it does not cover the largest possible gain of a pattern.
                double heuristicIns = Math.min(delta0 * newTotalUtility + dynamicTerm + cubValue,
                                               uut_abs - lut_min_abs);
                this.deltaDValue = Math.min(Math.max(heuristicIns, deltaMinIns), uut_abs);
                this.lutDynValue = Math.max(0.0, uut_abs - deltaDValue); // adaptive band floor
                if (!useAdaptiveBand) this.lutDynValue = lut_min_abs; // ablation: fixed band at Sl_min
            }

            if (cubTriggeredRescan) {
                // Safety bound exceeded: mine the whole current database again.
                largeMap.clear(); prelargeMap.clear();
                mineWithBudget(dSoFar, uut_abs, newTotalUtility);

            } else {
                // Safe: update the large and pre-large patterns directly.
                List<CompactPattern> allTrackedPatterns = new ArrayList<>();
                allTrackedPatterns.addAll(largeMap.keySet());
                allTrackedPatterns.addAll(prelargeMap.keySet());

                Map<CompactPattern, Double> oldUtilities = new HashMap<>(largeMap);
                oldUtilities.putAll(prelargeMap);
                largeMap.clear(); prelargeMap.clear();
                blocksReadCount = 0;

                final boolean byBound = tracking == Tracking.PEU;
                final double[] peuGain = new double[1];
                Map<CompactPattern, Double> newBounds = byBound ? new HashMap<>() : null;
                for (CompactPattern p : allTrackedPatterns) {
                    peuGain[0] = 0;
                    double updatedUtility = oldUtilities.get(p)
                            + evaluatePatternUtilityWithIndex(p, deltaD, byBound ? peuGain : null);
                    if (byBound) {
                        // B(X) >= u(X): the tracked bound decides whether X stays tracked.
                        double bound = trackedBound.get(p) + peuGain[0];
                        if (updatedUtility >= uut_abs) largeMap.put(p, updatedUtility);
                        else if (bound >= jointFloor(p)) prelargeMap.put(p, updatedUtility);
                        else continue;
                        newBounds.put(p, bound);
                        continue;
                    }
                    if (updatedUtility >= uut_abs) largeMap.put(p, updatedUtility);
                    // Pre-large floor: the per-pattern floor under JOINT, the adaptive
                    // lutDynValue otherwise.
                    else if (updatedUtility >= (jointTracking() ? jointFloor(p) : lutDynValue))
                        prelargeMap.put(p, updatedUtility);
                }
                if (byBound) { trackedBound.clear(); trackedBound.putAll(newBounds); }

                // Mine the new batch for new large and pre-large patterns.
                // Uses the global lutDynValue, not a batch-local threshold: a lower threshold
                // would feed globally small patterns into prelargeMap through the merge below.
                if (mineNewPatternsPerBatch) {
                    Map<CompactPattern, Double> newMinedPatterns = mineBatchWithIUB(deltaD, lutDynValue);
                    for (Map.Entry<CompactPattern, Double> entry : newMinedPatterns.entrySet()) {
                        if (!oldUtilities.containsKey(entry.getKey())) {
                            if (entry.getValue() >= uut_abs) largeMap.put(entry.getKey(), entry.getValue());
                            else prelargeMap.put(entry.getKey(), entry.getValue());
                        }
                    }
                }
            }
            this.patternCount = largeMap.size(); this.prelargeCount = prelargeMap.size();
        }

        // ================= PHASE 2b: DELETION =================
        /**
         * Removes deletedSeqs from the database. dAfter is the database after deletion and
         * newTotalUtility = TSU(dAfter). CUB adds u(s) for every deleted sequence.
         */
        public void runDeletion(List<Sequence> deletedSeqs, List<Sequence> dAfter, double newTotalUtility) {
            refuseUnderPreTest("a deletion");
            refuseUnderPeuTracking("a deletion");
            cancelled = false; // clear a cancellation left by an earlier measurement
            if (deletedSeqs == null || deletedSeqs.isEmpty()) { this.patternCount = largeMap.size(); this.prelargeCount = prelargeMap.size(); return; }
            // calculateExactUtilityInSeq and Pass 2 use the scratch buffer arrays indexed by
            // flatIdx; make sure they are allocated (no-op if already large enough).
            allocateScratch(maxItemId + 1);
            ensureScratchCapacity(maxFlatLen);
            double deletedTotal = 0;
            for (Sequence s : deletedSeqs) deletedTotal += s.sequenceUtility;

            double uut_abs = newTotalUtility * Su;
            this.uutValue = uut_abs; // absolute HUSP threshold (exported to CSV)
            double lut_min_abs = newTotalUtility * Sl_min;

            // Update LUT_dyn with the same logic as insertion.
            boolean joint = this.triggerRule == TriggerRule.JOINT;
            this.sigmaDSquared = joint ? 0 : calculateVariance(deletedSeqs);
            double swuMaxDel = 0;
            for (Sequence s : deletedSeqs) if (s.sequenceUtility > swuMaxDel) swuMaxDel = s.sequenceUtility;
            this.rhoD = (joint || deletedTotal == 0) ? 0 : (swuMaxDel / deletedTotal);
            // Same required order as insertion:
            //   update counters -> test the trigger -> reset on rescan -> compute Delta.
            // The non-JOINT deletion trigger is cumulative (cubValue), hence sound.
            this.cubValue += deletedTotal;   // perturbation accumulated since the last rescan
            this.deletedSinceRescan += deletedTotal;

            // F_del = (Su - Sl_min)/Su * TSU_lastRescan (deletion bound of Hong et al.).
            double safetyBoundDel = ((Su - Sl_min) / Su) * tsuAtLastRescan;
            this.sftValue = safetyBoundDel;
            if (joint) {
                safetyBoundDel = (Su - Sl_min) * tsuAtLastRescan;
                this.sftValue = safetyBoundDel;
                this.iauMaxAtDecision = jointTriggerQuantity();
                this.cubTriggeredRescan = useAdaptiveRescan ? (iauMaxAtDecision > safetyBoundDel) : true;
            } else {
                this.cubTriggeredRescan = useAdaptiveRescan ? (cubValue > safetyBoundDel) : true;
            }

            if (cubTriggeredRescan) {
                rescanCount++;
                cubValue = 0;
                resetIau();
                insertedSinceRescan = 0; deletedSinceRescan = 0;
                this.tsuAtLastRescan = newTotalUtility; // new TSU reference
            }

            if (joint) {
                this.lutDynValue = Sl_min * tsuAtLastRescan;
                this.deltaDValue = uut_abs - this.lutDynValue;
            } else {
                // Lower bound on Delta, symmetric to the insertion phase.
                // Deletion lowers TSU, so a pattern may become a HUSP without gaining utility.
                // Remaining deletion budget A_max = SFT_del - CUB. Worst case: X loses nothing
                // while TSU drops by exactly A_max, which needs u(X,D') < UUT*(TSU' - A_max), i.e.
                //     Delta >= UUT*A_max = (UUT - LUT_min)*TSU_ref - UUT*CUB.
                double dynamicTerm = 0;
                switch (this.mode) {
                    case FULL: dynamicTerm = kappa * sigmaDSquared * rhoD; break;
                    case VARIANCE_ONLY: dynamicTerm = kappa * sigmaDSquared; break;
                    case RHO_ONLY: dynamicTerm = kappa * rhoD; break;
                }
                double deltaMinDel = Math.max(0.0,
                        (Su - Sl_min) * tsuAtLastRescan - Su * cubValue);
                double heuristicDel = Math.min(delta0 * newTotalUtility + dynamicTerm + cubValue,
                                               uut_abs - lut_min_abs);
                this.deltaDValue = Math.min(Math.max(heuristicDel, deltaMinDel), uut_abs);
                this.lutDynValue = Math.max(0.0, uut_abs - deltaDValue); // adaptive band floor
                if (!useAdaptiveBand) this.lutDynValue = lut_min_abs; // ablation: fixed band at Sl_min
            }

            if (cubTriggeredRescan) {
                largeMap.clear(); prelargeMap.clear();
                // Remove the deleted sequences from the lookup so mining never reads them.
                for (Sequence s : deletedSeqs) dbRemove(s.id);
                mineWithBudget(dAfter, uut_abs, newTotalUtility);
            } else {
                // Direct update: subtract the utility contributed by the deleted sequences.
                List<CompactPattern> tracked = new ArrayList<>();
                tracked.addAll(largeMap.keySet());
                tracked.addAll(prelargeMap.keySet());
                Map<CompactPattern, Double> oldUtil = new HashMap<>(largeMap);
                oldUtil.putAll(prelargeMap);
                largeMap.clear(); prelargeMap.clear();

                for (CompactPattern p : tracked) {
                    double removed = 0;
                    for (Sequence s : deletedSeqs) removed += calculateExactUtilityInSeq(p, s);
                    double updated = Math.max(0, oldUtil.get(p) - removed);
                    if (updated >= uut_abs) largeMap.put(p, updated);
                    // Same pre-large floor as the insertion phase.
                    else if (updated >= (triggerRule == TriggerRule.JOINT ? jointFloor(p) : lutDynValue))
                        prelargeMap.put(p, updated);
                }
                for (Sequence s : deletedSeqs) dbRemove(s.id);
            }
            this.patternCount = largeMap.size(); this.prelargeCount = prelargeMap.size();
        }

        // ================= PHASE 2c: MODIFICATION =================
        /**
         * Modifies sequences: deletion of oldSeqs followed by insertion of newSeqs.
         * The two phases charge u(old) and u(new) to the counters.
         */
        public void runModification(List<Sequence> oldSeqs, List<Sequence> newSeqs,
                                    List<Sequence> dAfter, double newTotalUtility) {
            refuseUnderPreTest("a modification");
            refuseUnderPeuTracking("a modification");
            // Modification = deletion + insertion.
            //
            // A modification both removes u(old) from the database and adds u(new), and either
            // may push a pattern across a threshold, so the valid charge is u(old) + u(new),
            // which the two phases add on their own. The net change |u(new) - u(old)| does not
            // bound the change of the pattern set: a modification that keeps the utility but
            // replaces every item has net change 0.
            // strictModificationBudget=false charges the net change instead (comparison only).
            double delta = 0;
            int n = Math.min(oldSeqs.size(), newSeqs.size());
            for (int i = 0; i < n; i++) delta += Math.abs(newSeqs.get(i).sequenceUtility - oldSeqs.get(i).sequenceUtility);
            double cubBefore = this.cubValue;
            runDeletion(oldSeqs, subtract(dAfter, newSeqs), totalUtilityOf(subtract(dAfter, newSeqs)));
            runIncrementalMining(newSeqs, dAfter, newTotalUtility);
            if (!strictModificationBudget) {
                this.cubValue = cubBefore + delta;   // net-change charge, NOT sound
            }
            // cubTriggeredRescan keeps the value set by the insertion phase.
        }

        private List<Sequence> subtract(List<Sequence> all, List<Sequence> remove) {
            Set<Integer> ids = new HashSet<>();
            for (Sequence s : remove) ids.add(s.id);
            List<Sequence> out = new ArrayList<>(all.size());
            for (Sequence s : all) if (!ids.contains(s.id)) out.add(s);
            return out;
        }

        private double totalUtilityOf(List<Sequence> seqs) {
            double t = 0; for (Sequence s : seqs) t += s.sequenceUtility; return t;
        }

        /** Clears every A_i (at initial mining and at each rescan). */
        private void resetIau() {
            java.util.Arrays.fill(iau, 0.0);
            iauMax = 0;
        }

        private void ensureIauCapacity(int item) {
            if (item < iau.length) return;
            iau = java.util.Arrays.copyOf(iau, Math.max(item + 1, iau.length * 2));
        }

        /**
         * Adds u(s) to A_i for every DISTINCT item i of s and updates iauMax.
         * scratchIauSeen prevents adding twice when an item repeats within the sequence
         * (adding twice would still be sound, but would loosen the rule for no reason).
         */
        private void accumulateIau(Sequence s) {
            final double u = s.sequenceUtility;
            if (u == 0) return;
            for (QItemset is : s.itemsets) {
                for (QItem it : is.items) {
                    int id = it.id;
                    ensureIauCapacity(id);
                    if (scratchIauSeen == null || scratchIauSeen.length <= id) {
                        scratchIauSeen = java.util.Arrays.copyOf(
                                scratchIauSeen == null ? new int[256] : scratchIauSeen,
                                Math.max(id + 1, (scratchIauSeen == null ? 256 : scratchIauSeen.length) * 2));
                    }
                    if (scratchIauSeen[id] == iauStamp) continue;   // already added for this sequence
                    scratchIauSeen[id] = iauStamp;
                    double v = iau[id] + u;
                    iau[id] = v;
                    if (v > iauMax) iauMax = v;
                }
            }
            iauStamp++;
        }

        private transient int[] scratchIauSeen = new int[256];
        private int iauStamp = 1;

        /** Allocates the item-indexed scratch arrays for Pass 1 and Pass 2. */
        private void allocateScratch(int sz) {
            if (sz <= 0) sz = 16;
            if (scratchLocalI == null || scratchLocalI.length < sz) {
                scratchLocalI = new double[sz];
                scratchLocalS = new double[sz];
                scratchAggI   = new double[sz];
                scratchAggS   = new double[sz];
                scratchUsedI  = new int[sz];
                scratchUsedS  = new int[sz];
                scratchLocalSeenI = new boolean[sz];
                scratchLocalSeenS = new boolean[sz];
                scratchAggSeenI   = new boolean[sz];
                scratchAggSeenS   = new boolean[sz];
                scratchLocalUsedI = new int[sz];
                scratchLocalUsedS = new int[sz];
                scratchSurvivingI = new boolean[sz];
                scratchSurvivingS = new boolean[sz];
                // Single-pass Pass 2: child TrackerList slot per item id
                iSlot = new TrackerList[sz];
                sSlot = new TrackerList[sz];
                spSurvGenI = new long[sz];
                spSurvGenS = new long[sz];
            }
        }

        private double calculateVariance(List<Sequence> batch) {
            if (batch.isEmpty()) return 0;
            double mean = 0;
            for (Sequence s : batch) mean += s.sequenceUtility;
            mean /= batch.size();
            double var = 0;
            for (Sequence s : batch) var += Math.pow(s.sequenceUtility - mean, 2);
            return var / batch.size();
        }

        private double evaluatePatternUtilityWithIndex(CompactPattern p, List<Sequence> deltaD) {
            return evaluatePatternUtilityWithIndex(p, deltaD, null);
        }

        /** As above; with peuOut, also adds PEU(p, deltaD) to peuOut[0]. */
        private double evaluatePatternUtilityWithIndex(CompactPattern p, List<Sequence> deltaD, double[] peuOut) {
            // Intersect the block bitmaps of ALL items of the pattern, not only the first:
            // on sparse data a single item may occur in almost every block, which would
            // make the index useless.
            // A sequence contains p only if it contains every item of p, so its block lies
            // in the intersection: the filter is tighter and misses nothing.
            // Skipped under the block index ablation, which reads every sequence.
            final int blkUnit = Math.max(1, globalBlockIndex.blockSize);
            BitSet relevantBlocks = null;
            if (useBlockIndex) {
                final int[] flat = p.flat;
                for (int idx = 0; idx < flat.length; idx++) {
                    int item = flat[idx];
                    if (item < 0) continue; // skip the -1 itemset separator
                    BitSet bs = globalBlockIndex.bitsetFor(item);
                    if (bs == null || bs.isEmpty()) {     // item in no block: pattern absent
                        numBlocksSkipped += (deltaD.size() / blkUnit) + 1;
                        return 0.0;
                    }
                    if (relevantBlocks == null) {
                        relevantBlocks = (BitSet) bs.clone(); // clone: never mutate the index bitmap
                    } else {
                        relevantBlocks.and(bs);
                        if (relevantBlocks.isEmpty()) {   // empty intersection: no block holds all items
                            numBlocksSkipped += (deltaD.size() / blkUnit) + 1;
                            return 0.0;
                        }
                    }
                }
                if (relevantBlocks == null || relevantBlocks.isEmpty()) {
                    numBlocksSkipped += (deltaD.size() / blkUnit) + 1;
                    return 0.0;
                }
            }

            double totalUtil = 0;
            for (Sequence seq : deltaD) {
                // Block index ablation: read every sequence, skip nothing.
                boolean shouldRead;
                if (!useBlockIndex) {
                    shouldRead = true;
                } else {
                    int bid = globalBlockIndex.blockOf(seq.id);
                    shouldRead = (bid >= 0 && relevantBlocks.get(bid));
                }
                if (shouldRead) {
                    blocksReadCount++;
                    totalUtil += calculateExactUtilityInSeq(p, seq);
                    if (peuOut != null) peuOut[0] += lastSeqPeu;
                } else {
                    numBlocksSkipped++;
                }
            }
            return totalUtil;
        }

        private double calculateExactUtilityInSeq(CompactPattern p, Sequence seq) {
            // Walks the pattern one item at a time. Each step merges matches per flat
            // position in the scratch buffer (keeping the largest utility), then appends them
            // to the next tracker; no deduplication is needed on append.
            if (seq.flatItems == null) seq.buildFlatRepresentation();
            final int[] flatItems = seq.flatItems;
            final double[] flatUtils = seq.flatUtilities;
            final boolean[] isItemsetEnd = seq.isItemsetEnd;
            final int flatLen = seq.flatLen;
            final int[] pFlat = p.flat;
            final int pLen = pFlat.length;

            int firstItem = pFlat[0];

            // === Step 0: tracker for the first item ===
            // Each flat position is unique, so append directly.
            Tracker tracker = borrowTracker(seq.id);
            for (int k = 0; k < flatLen; k++) {
                if (flatItems[k] == firstItem) {
                    tracker.append(k, flatUtils[k], seq);
                }
            }
            lastSeqPeu = 0;
            if (tracker.size == 0) {
                releaseTracker(tracker);
                return 0.0;
            }

            Tracker current = tracker;
            boolean nextIsSExt = false;

            // === Steps 1..pLen-1: extend the pattern ===
            for (int idx = 1; idx < pLen; idx++) {
                int v = pFlat[idx];
                if (v == -1) {
                    nextIsSExt = true;
                    continue;
                }
                int itemToFind = v;
                boolean isIExt = !nextIsSExt;
                nextIsSExt = false;

                // === Scratch buffer accumulate ===
                int localUsedCount = 0;

                final int curSize = current.size;
                final int[] curFlat = current.flatIndices;
                final double[] curUtil = current.utility;

                for (int k = 0; k < curSize; k++) {
                    int parentFlatIdx = curFlat[k];
                    double instUtility = curUtil[k];
                    if (isIExt) {
                        if (isItemsetEnd[parentFlatIdx]) continue;
                        // I-extension scan
                        for (int q = parentFlatIdx + 1; q < flatLen; q++) {
                            boolean endOfItemset = isItemsetEnd[q];
                            if (flatItems[q] == itemToFind) {
                                double newU = instUtility + flatUtils[q];
                                if (!scratchPresent[q]) {
                                    scratchPresent[q] = true;
                                    scratchUsedFlat[localUsedCount++] = q;
                                    scratchMaxUtil[q] = newU;
                                } else if (newU > scratchMaxUtil[q]) {
                                    scratchMaxUtil[q] = newU;
                                }
                            }
                            if (endOfItemset) break;
                        }
                    } else {
                        // S-extension scan
                        int boundary = parentFlatIdx;
                        while (boundary < flatLen && !isItemsetEnd[boundary]) boundary++;
                        int sExtStart = boundary + 1;
                        for (int q = sExtStart; q < flatLen; q++) {
                            if (flatItems[q] == itemToFind) {
                                double newU = instUtility + flatUtils[q];
                                if (!scratchPresent[q]) {
                                    scratchPresent[q] = true;
                                    scratchUsedFlat[localUsedCount++] = q;
                                    scratchMaxUtil[q] = newU;
                                } else if (newU > scratchMaxUtil[q]) {
                                    scratchMaxUtil[q] = newU;
                                }
                            }
                        }
                    }
                }

                // === Flush into the next tracker and reset the touched positions ===
                releaseTracker(current);
                if (localUsedCount == 0) return 0.0;

                Tracker nextTracker = borrowTracker(seq.id);
                for (int x = 0; x < localUsedCount; x++) {
                    int q = scratchUsedFlat[x];
                    nextTracker.append(q, scratchMaxUtil[q], seq);
                    scratchPresent[q] = false;
                }
                current = nextTracker;
            }

            double result = current.exactUtility;
            lastSeqPeu = current.peu;           // largest prefix utility plus rest utility
            releaseTracker(current);
            return result;
        }

        // ====================================================================
        //  Irrelevant-item removal state (see IipMode for the rule)
        //
        //  iipRemoved[item] marks the items removed on the current path; iipStack records them
        //  in removal order, and a node unmarks what it pushed when it returns (also on an
        //  exception), so sibling subtrees see only their ancestors' removals. ru under the
        //  removals in force is recomputed per sequence into iipRu (one sequence at a time),
        //  so no per-node or per-sequence copy exists. Sequence objects are never written.
        // ====================================================================
        private transient boolean[] iipRemoved;
        private transient int[] iipStack;
        private int iipTop = 0;
        private transient double[] iipRu;        // flat-indexed, one sequence
        private transient int[] iipLocal;        // flat-indexed: distinct items of one sequence
        private transient double[] iipAcc;       // item-indexed: B per item
        private transient long[] iipAccStamp;    // item-indexed: iipAcc[item] valid iff == iipAccGen
        private transient long[] iipSeqStamp;    // item-indexed: item seen in the current sequence
        private transient int[] iipAccUsed;      // items with a valid iipAcc entry
        private int iipAccUsedCount = 0;
        private long iipAccGen = 0, iipSeqGen = 0;

        private void ensureIipCapacity(int items, int flat) {
            items = Math.max(1, items); flat = Math.max(1, flat);
            if (iipRemoved == null || iipRemoved.length < items) {
                // Only grown between mining passes, when no item is marked.
                iipRemoved = new boolean[items]; iipStack = new int[items];
                iipAcc = new double[items]; iipAccStamp = new long[items];
                iipSeqStamp = new long[items]; iipAccUsed = new int[items];
                iipTop = 0;
            }
            if (iipRu == null || iipRu.length < flat) { iipRu = new double[flat]; iipLocal = new int[flat]; }
        }

        private void iipUndo(int mark) {
            while (iipTop > mark) iipRemoved[iipStack[--iipTop]] = false;
        }

        private void iipAccBegin() { iipAccGen++; iipAccUsedCount = 0; }

        private void iipAccAdd(int item, double v) {
            if (iipAccStamp[item] != iipAccGen) {
                iipAccStamp[item] = iipAccGen; iipAcc[item] = 0; iipAccUsed[iipAccUsedCount++] = item;
            }
            iipAcc[item] += v;
        }

        /** Removes every accumulated item whose bound is below theta; returns how many. */
        private int iipRemoveBelow(double theta) {
            int newly = 0;
            for (int u = 0; u < iipAccUsedCount; u++) {
                int it = iipAccUsed[u];
                if (iipAcc[it] < theta) { iipRemoved[it] = true; iipStack[iipTop++] = it; newly++; }
            }
            return newly;
        }

        /** iipRu[q] for from <= q < flatLen: utility of the non-removed items after q. */
        private void iipComputeRu(Sequence seq, int from) {
            final int[] items = seq.flatItems;
            final double[] utils = seq.flatUtilities;
            final boolean[] removed = iipRemoved;
            final double[] ru = iipRu;
            double running = 0;
            for (int q = seq.flatLen - 1; q >= from; q--) {
                ru[q] = running;
                if (!removed[items[q]]) running += utils[q];
            }
        }

        private static int firstEnd(Tracker t) {
            final int[] f = t.flatIndices;
            int m = f[0];
            for (int k = 1; k < t.size; k++) if (f[k] < m) m = f[k];
            return m;
        }

        /**
         * One backward pass per sequence: ru^R into iipRu, t.peu = PEU^R(t,s), and B^R(i,t)
         * accumulated for every non-removed item after the first occurrence end of t.
         * Returns the sum of PEU^R (the node's IUB under the removals in force).
         */
        private double iipPeuAndBound(ArrayList<Tracker> ptList) {
            iipAccBegin();
            final boolean[] removed = iipRemoved;
            final double[] ru = iipRu;
            final long[] seen = iipSeqStamp;
            final int[] local = iipLocal;
            double iub = 0;
            for (int pi = 0, n = ptList.size(); pi < n; pi++) {
                Tracker t = ptList.get(pi);
                Sequence seq = dbGet(t.seqId);
                if (seq == null) { iub += t.peu; continue; }
                final int[] items = seq.flatItems;
                final double[] utils = seq.flatUtilities;
                final int first = firstEnd(t);
                final long sg = ++iipSeqGen;
                int nLocal = 0;
                double running = 0;
                for (int q = seq.flatLen - 1; q > first; q--) {
                    ru[q] = running;
                    int it = items[q];
                    if (!removed[it]) {
                        running += utils[q];
                        if (seen[it] != sg) { seen[it] = sg; local[nLocal++] = it; }
                    }
                }
                ru[first] = running;
                final int[] tFlat = t.flatIndices;
                final double[] tUtil = t.utility;
                double best = 0;
                for (int k = 0; k < t.size; k++) {
                    double v = tUtil[k] + ru[tFlat[k]];
                    if (v > best) best = v;
                }
                t.peu = best;
                iub += best;
                for (int x = 0; x < nLocal; x++) iipAccAdd(local[x], best);
            }
            return iub;
        }

        private Map<CompactPattern, Double> mineBatchWithIUB(List<Sequence> batch, double minThreshold) {
            Map<CompactPattern, Double> results = new HashMap<>();

            // === 1-item trackers: TrackerList[] indexed by item id ===
            // Scans the flat arrays. Each flat position is unique, so Tracker.append()
            // is used directly. Lists and trackers come from the pools and are returned
            // after expansion.
            final int sz = Math.max(1, maxItemId + 1);
            final boolean iip = iipMode != IipMode.OFF;
            if (iip) {
                for (Sequence seq : batch) if (seq.flatItems == null) seq.buildFlatRepresentation();
                ensureIipCapacity(sz, maxFlatLen);
                iipUndo(0);
            }
            try {
            // === Root removal: B(i) = sum of the non-removed utility of the sequences containing i ===
            if (iip) {
                for (;;) {
                    iipAccBegin();
                    for (Sequence seq : batch) {
                        final int[] flatItems = seq.flatItems;
                        final double[] flatUtils = seq.flatUtilities;
                        final int flatLen = seq.flatLen;
                        double us = 0;
                        for (int k = 0; k < flatLen; k++) if (!iipRemoved[flatItems[k]]) us += flatUtils[k];
                        final long sg = ++iipSeqGen;
                        for (int k = 0; k < flatLen; k++) {
                            int it = flatItems[k];
                            if (iipRemoved[it] || iipSeqStamp[it] == sg) continue;
                            iipSeqStamp[it] = sg;
                            iipAccAdd(it, us);
                        }
                    }
                    int newly = iipRemoveBelow(minThreshold);
                    removedItems += newly; removedItemsRoot += newly;
                    if (newly == 0 || iipMode != IipMode.FIXPOINT) break;
                }
            }

            TrackerList[] l1Trackers = new TrackerList[sz];
            int[] l1Used = new int[sz];
            int l1UsedCount = 0;

            for (Sequence seq : batch) {
                if (seq.flatItems == null) seq.buildFlatRepresentation();
                final int seqId = seq.id;
                final int[] flatItems = seq.flatItems;
                final double[] flatUtils = seq.flatUtilities;
                final int flatLen = seq.flatLen;
                for (int k = 0; k < flatLen; k++) {
                    int idx = flatItems[k];
                    if (idx < 0 || idx >= sz) continue;
                    if (iip && iipRemoved[idx]) continue;   // removed at the root: not generated
                    TrackerList tl = l1Trackers[idx];
                    if (tl == null) {
                        tl = borrowTrackerList();
                        l1Trackers[idx] = tl;
                        l1Used[l1UsedCount++] = idx;
                    }
                    // Reuse the last tracker if it belongs to this sequence; else borrow one.
                    int trSize = tl.trackers.size();
                    Tracker last;
                    if (trSize > 0 && (last = tl.trackers.get(trSize - 1)).seqId == seqId) {
                        // reuse
                    } else {
                        last = borrowTracker(seqId);
                        tl.trackers.add(last);
                    }
                    // Each flat position is seen once per sequence: append directly.
                    last.append(k, flatUtils[k], seq);
                }
            }

            // Count every generated 1-item candidate, as the baselines do.
            candidateCount += l1UsedCount;

            // Expand each 1-item pattern; clear its slot before recursing and return the
            // list to the pool afterwards.
            for (int u = 0; u < l1UsedCount; u++) {
                int item = l1Used[u];
                TrackerList tl = l1Trackers[item];
                l1Trackers[item] = null;
                if (tl == null) continue;
                Pattern p = new Pattern(item);
                expandNodeWithPruning(p, tl, batch, minThreshold, results, 1);
                // Return tl and its trackers to the pool.
                releaseTrackerList(tl);
            }
            } finally {
                // Also on CancellationSignal / PatternBudgetExceeded: a retry mines the same
                // sequences with no item marked.
                if (iip) iipUndo(0);
            }
            return results;
        }

        /**
         * Bound-first expansion of one node: survey the child items with their PEU, filter
         * them by the aggregated PEU value bound, and build occurrence lists only for the
         * survivors. The IUB bound prunes the whole branch.
         */
        private void expandNodeWithPruning(Pattern currentPattern, TrackerList parentTrackers, List<Sequence> batch, double minThreshold, Map<CompactPattern, Double> results, int depth) {

            // === Cooperative cancellation check (runner timeout) ===
            // One volatile read per node.
            if (cancelled) throw new CancellationSignal();

            // Optional depth cap; off by default (see maxDepth).
            if (depth > maxDepth) return;
            if (parentTrackers == null || parentTrackers.isEmpty()) return;

            exploredNodes++; // this node passed the PEU bound and is expanded
            // candidateCount counts every item generated in the survey below, as the baselines do.
            final boolean iip = iipMode != IipMode.OFF;
            final int iipMark = iipTop;   // removals pushed by this node are undone on return
            try {
            double exactUtilityD = 0, IUB = 0;

            ArrayList<Tracker> ptList = parentTrackers.trackers;
            final int ptSize = ptList.size();
            if (!iip) {
                for (int pi = 0; pi < ptSize; pi++) {
                    Tracker t = ptList.get(pi);
                    exactUtilityD += t.exactUtility;
                    IUB += t.peu; // per-sequence PEU summed into IUB
                }
            } else {
                for (int pi = 0; pi < ptSize; pi++) exactUtilityD += ptList.get(pi).exactUtility;
                // PEU and IUB under the removals in force; B^R(i,t) accumulated in the same pass.
                IUB = iipPeuAndBound(ptList);
            }

            if (minedBounds != null ? IUB >= minThreshold : exactUtilityD >= minThreshold) {
                // Every pattern that reaches the cutoff is kept (under PEU tracking: every node
                // whose bound reaches it). A tracked set too large for the heap ends the run with
                // an out-of-memory status; dropping patterns instead would break exactness, since
                // an untracked pattern above the floor is never revisited.
                CompactPattern cp = CompactPattern.fromMutable(currentPattern);
                results.put(cp, exactUtilityD);
                if (minedBounds != null) minedBounds.put(cp, IUB);
            }

            // --- Branch pruning by IUB ---
            if (useIub && IUB < minThreshold) {   // IUB branch pruning ablation: useIub=false never cuts
                prunedIub++; // branch removed by the IUB bound
                return;
            }

            // --- Irrelevant-item removal at this node (removal threshold = the cutoff) ---
            if (iip) {
                int newly = iipRemoveBelow(minThreshold);
                removedItems += newly;
                while (newly > 0 && iipMode == IipMode.FIXPOINT) {
                    iipPeuAndBound(ptList);
                    newly = iipRemoveBelow(minThreshold);
                    removedItems += newly;
                }
            }
            // Removals in force on this path: the survey skips removed items and reads ru^R.
            final boolean reduced = iip && iipTop > 0;

            allocateScratch(maxItemId + 1);

            // ===== Pass 1: survey with primitive scratch arrays =====
            // Nothing is allocated per tracker. Only the slots used by the previous call
            // are reset (O(touched)).
            //
            // scratchSurvivingI/S need no bulk reset: an entry is read only at indices in
            // scratchUsedI/S, and each such entry is written by the filter step of the same
            // call before it is read. A bulk reset would cost O(maxItemId) per node.
            for (int u = 0; u < scratchUsedICount; u++) {
                int idx = scratchUsedI[u];
                scratchAggI[idx] = 0;
                scratchAggSeenI[idx] = false;
            }
            for (int u = 0; u < scratchUsedSCount; u++) {
                int idx = scratchUsedS[u];
                scratchAggS[idx] = 0;
                scratchAggSeenS[idx] = false;
            }
            scratchUsedICount = 0;
            scratchUsedSCount = 0;

            for (int pi = 0; pi < ptSize; pi++) {
                Tracker t = ptList.get(pi);
                Sequence seq = dbGet(t.seqId);
                if (seq == null) continue;

                // Reset the per-sequence flags of the previous tracker (selective).
                for (int u = 0; u < scratchLocalUsedICount; u++) {
                    int idx = scratchLocalUsedI[u];
                    scratchLocalI[idx] = 0;
                    scratchLocalSeenI[idx] = false;
                }
                for (int u = 0; u < scratchLocalUsedSCount; u++) {
                    int idx = scratchLocalUsedS[u];
                    scratchLocalS[idx] = 0;
                    scratchLocalSeenS[idx] = false;
                }
                scratchLocalUsedICount = 0;
                scratchLocalUsedSCount = 0;

                // Flat representation: scan forward from parentFlatIdx+1, in the
                // I-extension phase until isItemsetEnd[q] is true, then in the
                // S-extension phase for the rest of the sequence.
                final int tSize = t.size;
                final int[] tFlat = t.flatIndices;
                final double[] tUtil = t.utility;
                final int[] flatItems = seq.flatItems;
                final double[] flatUtils = seq.flatUtilities;
                final double[] flatRest = seq.flatRemainingUtility;
                final boolean[] isItemsetEnd = seq.isItemsetEnd;
                final int flatLen = seq.flatLen;

                if (reduced) {
                    // Same scan with removed items skipped and ru^R in place of the stored ru.
                    iipComputeRu(seq, firstEnd(t));
                    final double[] ru = iipRu;
                    final boolean[] removed = iipRemoved;
                    for (int k = 0; k < tSize; k++) {
                        int parentFlatIdx = tFlat[k];
                        double instUtility = tUtil[k];
                        int q = parentFlatIdx + 1;
                        if (!isItemsetEnd[parentFlatIdx]) {
                            while (q < flatLen) {
                                int idx = flatItems[q];
                                boolean endOfItemset = isItemsetEnd[q];
                                if (!removed[idx]) {
                                    double localPeu = instUtility + flatUtils[q] + ru[q];
                                    if (!scratchLocalSeenI[idx]) {
                                        scratchLocalSeenI[idx] = true;
                                        scratchLocalUsedI[scratchLocalUsedICount++] = idx;
                                        scratchLocalI[idx] = localPeu;
                                    } else if (scratchLocalI[idx] < localPeu) {
                                        scratchLocalI[idx] = localPeu;
                                    }
                                }
                                q++;
                                if (endOfItemset) break;
                            }
                        }
                        while (q < flatLen) {
                            int idx = flatItems[q];
                            if (!removed[idx]) {
                                double localPeu = instUtility + flatUtils[q] + ru[q];
                                if (!scratchLocalSeenS[idx]) {
                                    scratchLocalSeenS[idx] = true;
                                    scratchLocalUsedS[scratchLocalUsedSCount++] = idx;
                                    scratchLocalS[idx] = localPeu;
                                } else if (scratchLocalS[idx] < localPeu) {
                                    scratchLocalS[idx] = localPeu;
                                }
                            }
                            q++;
                        }
                    }
                } else
                for (int k = 0; k < tSize; k++) {
                    int parentFlatIdx = tFlat[k];
                    double instUtility = tUtil[k];

                    // ===== Integrated scan: I-extension phase, then S-extension phase =====
                    // If the parent is the LAST element of its itemset, there is no
                    // I-extension: q = parent+1 starts the next itemset.
                    int q = parentFlatIdx + 1;

                    // Phase 1: I-extension survey within the same itemset; collect each
                    // generated item once with its largest local PEU. Pruning happens in the
                    // filter step.
                    if (!isItemsetEnd[parentFlatIdx]) {
                        while (q < flatLen) {
                            int idx = flatItems[q];
                            boolean endOfItemset = isItemsetEnd[q];
                            double localPeu = instUtility + flatUtils[q] + flatRest[q];
                            if (!scratchLocalSeenI[idx]) {
                                scratchLocalSeenI[idx] = true;
                                scratchLocalUsedI[scratchLocalUsedICount++] = idx;
                                scratchLocalI[idx] = localPeu;
                            } else if (scratchLocalI[idx] < localPeu) {
                                scratchLocalI[idx] = localPeu;
                            }
                            q++;
                            if (endOfItemset) break; // continue with the S-extension phase
                        }
                    }

                    // Phase 2: S-extension survey from q (past the itemset boundary).
                    while (q < flatLen) {
                        int idx = flatItems[q];
                        double localPeu = instUtility + flatUtils[q] + flatRest[q];
                        if (!scratchLocalSeenS[idx]) {
                            scratchLocalSeenS[idx] = true;
                            scratchLocalUsedS[scratchLocalUsedSCount++] = idx;
                            scratchLocalS[idx] = localPeu;
                        } else if (scratchLocalS[idx] < localPeu) {
                            scratchLocalS[idx] = localPeu;
                        }
                        q++;
                    }
                }

                // Aggregate: add the per-sequence maxima and record used items for the reset.
                for (int u = 0; u < scratchLocalUsedICount; u++) {
                    int idx = scratchLocalUsedI[u];
                    scratchAggI[idx] += scratchLocalI[idx];
                    if (!scratchAggSeenI[idx]) {
                        scratchAggSeenI[idx] = true;
                        scratchUsedI[scratchUsedICount++] = idx;
                    }
                }
                for (int u = 0; u < scratchLocalUsedSCount; u++) {
                    int idx = scratchLocalUsedS[u];
                    scratchAggS[idx] += scratchLocalS[idx];
                    if (!scratchAggSeenS[idx]) {
                        scratchAggSeenS[idx] = true;
                        scratchUsedS[scratchUsedSCount++] = idx;
                    }
                }
            }

            // candidateCount adds every distinct item GENERATED at this node, before
            // pruning, as the baselines and Pre-HUSPM count.
            candidateCount += (scratchUsedICount + scratchUsedSCount);
            // Candidate budget: stop a runaway pass.
            if (candidateCount > maxCandidatesBudget) throw new PatternBudgetExceeded(candidateCount);
            // Wall-clock safety net, sampled every 4096 nodes because calling nanoTime() on
            // every node would itself be costly.
            if (mineDeadlineNanos != 0 && (++deadlineTick & 0xFFF) == 0
                    && System.nanoTime() > mineDeadlineNanos) {
                throw new PatternBudgetExceeded(candidateCount);
            }

            // Filter by the aggregated PEU value bound, before any child is materialized, so
            // only survivors get occurrence lists. prunedValueBound counts the items removed.
            // Generation marker: spSurvGen[idx] == spNodeGen iff idx survives at this node.
            spNodeGen++;
            boolean anyI = false, anyS = false;
            for (int u = 0; u < scratchUsedICount; u++) {
                int idx = scratchUsedI[u];
                if (scratchAggI[idx] >= minThreshold) { scratchSurvivingI[idx] = true; spSurvGenI[idx] = spNodeGen; anyI = true; }
                else { scratchSurvivingI[idx] = false; prunedValueBound++; }
            }
            for (int u = 0; u < scratchUsedSCount; u++) {
                int idx = scratchUsedS[u];
                if (scratchAggS[idx] >= minThreshold) { scratchSurvivingS[idx] = true; spSurvGenS[idx] = spNodeGen; anyS = true; }
                else { scratchSurvivingS[idx] = false; prunedValueBound++; }
            }

            // ===== Pass 2: build child occurrence lists =====
            // REQUIRED SNAPSHOT: scratchUsedI/S and scratchSurvivingI/S are fields that the
            // recursion overwrites. Copy the surviving items into depth-local arrays before
            // the loops below, or children of deeper nodes replace those of this node.
            int[] survI = (anyI && scratchUsedICount > 0)
                    ? borrowSurvivorArray(iSurvivorPool, depth, scratchUsedICount) : null;
            int survICount = 0;
            if (anyI) {
                for (int u = 0; u < scratchUsedICount; u++) {
                    int idx = scratchUsedI[u];
                    if (scratchSurvivingI[idx]) survI[survICount++] = idx;
                }
            }
            int[] survS = (anyS && scratchUsedSCount > 0)
                    ? borrowSurvivorArray(sSurvivorPool, depth, scratchUsedSCount) : null;
            int survSCount = 0;
            if (anyS) {
                for (int u = 0; u < scratchUsedSCount; u++) {
                    int idx = scratchUsedS[u];
                    if (scratchSurvivingS[idx]) survS[survSCount++] = idx;
                }
            }

            // ===== Materialization strategy =====
            // Narrow node (ptSize <= SINGLE_PASS_PT_CAP, most nodes): SINGLE-PASS, one scan of
            // the parent per extension kind, collecting every child at once. Wide node: LAZY,
            // one child at a time, so only one child list per depth is alive.
            // Both build IDENTICAL child trackers (same scratch buffer merge by position, same
            // order), so results and candidate counts do not depend on the choice.
            if ((long) ptSize <= SINGLE_PASS_PT_CAP) {
                // ---------- Single-pass: I-extensions (one scan) into iSlot ----------
                if (anyI) {
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
                        int localUsedCount = 0;
                        for (int k = 0; k < tSize; k++) {
                            int parentFlatIdx = tFlat[k];
                            if (isItemsetEnd[parentFlatIdx]) continue; // no I-extension
                            double instUtility = tUtil[k];
                            for (int q = parentFlatIdx + 1; q < flatLen; q++) {
                                boolean end = isItemsetEnd[q];
                                int idx = flatItems[q];
                                if (spSurvGenI[idx] == spNodeGen) {
                                    double nu = instUtility + flatUtils[q];
                                    if (!scratchPresent[q]) { scratchPresent[q] = true; scratchUsedFlat[localUsedCount++] = q; scratchMaxUtil[q] = nu; }
                                    else if (nu > scratchMaxUtil[q]) scratchMaxUtil[q] = nu;
                                }
                                if (end) break;
                            }
                        }
                        // Flush per sequence: group by item into iSlot (one tracker per sequence per item)
                        for (int x = 0; x < localUsedCount; x++) {
                            int q = scratchUsedFlat[x];
                            int idx = flatItems[q];
                            TrackerList tl = iSlot[idx];
                            if (tl == null) { tl = borrowTrackerList(); iSlot[idx] = tl; }
                            ArrayList<Tracker> ts = tl.trackers; int n = ts.size();
                            Tracker ct = (n > 0 && ts.get(n - 1).seqId == sid) ? ts.get(n - 1) : null;
                            if (ct == null) { ct = borrowTracker(sid); ts.add(ct); }
                            ct.append(q, scratchMaxUtil[q], seq);
                            scratchPresent[q] = false; // selective reset
                        }
                    }
                }
                // ---------- Single-pass: S-extensions (one scan) into sSlot ----------
                if (anyS) {
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
                        int localUsedCount = 0;
                        for (int k = 0; k < tSize; k++) {
                            int parentFlatIdx = tFlat[k];
                            double instUtility = tUtil[k];
                            int boundary = parentFlatIdx;
                            while (boundary < flatLen && !isItemsetEnd[boundary]) boundary++;
                            for (int q = boundary + 1; q < flatLen; q++) {
                                int idx = flatItems[q];
                                if (spSurvGenS[idx] == spNodeGen) {
                                    double nu = instUtility + flatUtils[q];
                                    if (!scratchPresent[q]) { scratchPresent[q] = true; scratchUsedFlat[localUsedCount++] = q; scratchMaxUtil[q] = nu; }
                                    else if (nu > scratchMaxUtil[q]) scratchMaxUtil[q] = nu;
                                }
                            }
                        }
                        for (int x = 0; x < localUsedCount; x++) {
                            int q = scratchUsedFlat[x];
                            int idx = flatItems[q];
                            TrackerList tl = sSlot[idx];
                            if (tl == null) { tl = borrowTrackerList(); sSlot[idx] = tl; }
                            ArrayList<Tracker> ts = tl.trackers; int n = ts.size();
                            Tracker ct = (n > 0 && ts.get(n - 1).seqId == sid) ? ts.get(n - 1) : null;
                            if (ct == null) { ct = borrowTracker(sid); ts.add(ct); }
                            ct.append(q, scratchMaxUtil[q], seq);
                            scratchPresent[q] = false;
                        }
                    }
                }
                // ---------- Move children into depth-local arrays and CLEAR the slots before recursion ----------
                // (iSlot/sSlot are fields, so the recursion would overwrite them)
                TrackerList[] tlsI = (anyI && survICount > 0) ? borrowTLArray(iTLPool, depth, survICount) : null;
                for (int u = 0; u < survICount; u++) { int idx = survI[u]; tlsI[u] = iSlot[idx]; iSlot[idx] = null; }
                TrackerList[] tlsS = (anyS && survSCount > 0) ? borrowTLArray(sTLPool, depth, survSCount) : null;
                for (int u = 0; u < survSCount; u++) { int idx = survS[u]; tlsS[u] = sSlot[idx]; sSlot[idx] = null; }
                // ---------- Recurse into I- then S-extensions, releasing each child afterwards ----------
                for (int u = 0; u < survICount; u++) {
                    TrackerList tl = tlsI[u];
                    if (tl == null) continue;
                    if (!tl.isEmpty()) {
                        tl.trimToSize();
                        currentPattern.addIExtension(survI[u]);
                        expandNodeWithPruning(currentPattern, tl, batch, minThreshold, results, depth + 1);
                        currentPattern.removeLastIExtension();
                    }
                    releaseTrackerList(tl);
                }
                for (int u = 0; u < survSCount; u++) {
                    TrackerList tl = tlsS[u];
                    if (tl == null) continue;
                    if (!tl.isEmpty()) {
                        tl.trimToSize();
                        currentPattern.addSExtension(survS[u]);
                        expandNodeWithPruning(currentPattern, tl, batch, minThreshold, results, depth + 1);
                        currentPattern.removeLastSExtension();
                    }
                    releaseTrackerList(tl);
                }
            } else {
            // ===== Lazy Pass 2 (wide nodes) =====
            //
            // For each surviving item: borrow a TrackerList, scan the parent trackers,
            // merge occurrences per sequence in the scratch buffer by flat position, flush into
            // a pooled tracker, recurse, release. Only one child list is alive per depth.

            if (anyI) {
                for (int u = 0; u < survICount; u++) {
                    final int itemToFind = survI[u];
                    TrackerList tl = borrowTrackerList();

                    for (int pi = 0; pi < ptSize; pi++) {
                        Tracker t = ptList.get(pi);
                        Sequence seq = dbGet(t.seqId);
                        if (seq == null) continue;

                        final int tSize = t.size;
                        final int[] tFlat = t.flatIndices;
                        final double[] tUtil = t.utility;
                        final int[] flatItems = seq.flatItems;
                        final double[] flatUtils = seq.flatUtilities;
                        final boolean[] isItemsetEnd = seq.isItemsetEnd;
                        final int flatLen = seq.flatLen;

                        // Per-sequence scratch buffer: start empty.
                        int localUsedCount = 0;

                        for (int k = 0; k < tSize; k++) {
                            int parentFlatIdx = tFlat[k];
                            double instUtility = tUtil[k];

                            // A parent at the end of its itemset has no I-extension.
                            if (isItemsetEnd[parentFlatIdx]) continue;

                            // I-extension scan: forward until the itemset ends.
                            for (int q = parentFlatIdx + 1; q < flatLen; q++) {
                                boolean endOfItemset = isItemsetEnd[q];
                                if (flatItems[q] == itemToFind) {
                                    double newU = instUtility + flatUtils[q];
                                    if (!scratchPresent[q]) {
                                        scratchPresent[q] = true;
                                        scratchUsedFlat[localUsedCount++] = q;
                                        scratchMaxUtil[q] = newU;
                                    } else if (newU > scratchMaxUtil[q]) {
                                        scratchMaxUtil[q] = newU;
                                    }
                                }
                                if (endOfItemset) break; // crossed itemset boundary
                            }
                        }

                        // Flush into a pooled tracker and reset the touched positions.
                        if (localUsedCount > 0) {
                            Tracker child = borrowTracker(t.seqId);
                            for (int x = 0; x < localUsedCount; x++) {
                                int idx = scratchUsedFlat[x];
                                child.append(idx, scratchMaxUtil[idx], seq);
                                scratchPresent[idx] = false; // reset present flag only
                            }
                            tl.trackers.add(child);
                        }
                    }

                    if (!tl.isEmpty()) {
                        tl.trimToSize();
                        currentPattern.addIExtension(itemToFind);
                        expandNodeWithPruning(currentPattern, tl, batch, minThreshold, results, depth + 1);
                        currentPattern.removeLastIExtension();
                    }
                    releaseTrackerList(tl); // return the list and its trackers to the pool
                }
            }

            if (anyS) {
                for (int u = 0; u < survSCount; u++) {
                    final int itemToFind = survS[u];
                    TrackerList tl = borrowTrackerList();

                    for (int pi = 0; pi < ptSize; pi++) {
                        Tracker t = ptList.get(pi);
                        Sequence seq = dbGet(t.seqId);
                        if (seq == null) continue;

                        final int tSize = t.size;
                        final int[] tFlat = t.flatIndices;
                        final double[] tUtil = t.utility;
                        final int[] flatItems = seq.flatItems;
                        final double[] flatUtils = seq.flatUtilities;
                        final boolean[] isItemsetEnd = seq.isItemsetEnd;
                        final int flatLen = seq.flatLen;

                        int localUsedCount = 0;

                        for (int k = 0; k < tSize; k++) {
                            int parentFlatIdx = tFlat[k];
                            double instUtility = tUtil[k];

                            // sExtStart = end of the parent's itemset + 1.
                            // If the parent already ends its itemset, sExtStart = parent+1.
                            int boundary = parentFlatIdx;
                            while (boundary < flatLen && !isItemsetEnd[boundary]) boundary++;
                            int sExtStart = boundary + 1;

                            // S-extension scan: from sExtStart to the end of the sequence.
                            for (int q = sExtStart; q < flatLen; q++) {
                                if (flatItems[q] == itemToFind) {
                                    double newU = instUtility + flatUtils[q];
                                    if (!scratchPresent[q]) {
                                        scratchPresent[q] = true;
                                        scratchUsedFlat[localUsedCount++] = q;
                                        scratchMaxUtil[q] = newU;
                                    } else if (newU > scratchMaxUtil[q]) {
                                        scratchMaxUtil[q] = newU;
                                    }
                                }
                            }
                        }

                        if (localUsedCount > 0) {
                            Tracker child = borrowTracker(t.seqId);
                            for (int x = 0; x < localUsedCount; x++) {
                                int idx = scratchUsedFlat[x];
                                child.append(idx, scratchMaxUtil[idx], seq);
                                scratchPresent[idx] = false;
                            }
                            tl.trackers.add(child);
                        }
                    }

                    if (!tl.isEmpty()) {
                        tl.trimToSize();
                        currentPattern.addSExtension(itemToFind);
                        expandNodeWithPruning(currentPattern, tl, batch, minThreshold, results, depth + 1);
                        currentPattern.removeLastSExtension();
                    }
                    releaseTrackerList(tl);
                }
            }
            } // end else (lazy Pass 2)
            } finally {
                if (iipTop > iipMark) iipUndo(iipMark);
            }
        }

        /**
         * Mines once under a work budget. When the budget is exceeded, raises Sl_min and
         * retries on the same data.
         *
         * The budget reacts instead of predicting: cost is the number of candidates, and no
         * structural quantity measured before mining predicts it reliably enough to set a
         * constant. The candidate cap measures exactly the quantity that needs bounding.
         *
         * When no retry is left, the exception is rethrown instead of running without a
         * cap: a dataset with no feasible operating point reports that, rather than running
         * until it is out of memory with no stated reason.
         */
        private void mineWithBudget(List<Sequence> db, double uut, double tsuRef) {
            if (rescanCandidateBudget <= 0 && rescanMillisBudget <= 0) {
                mineAndClassify(db, uut, lutDynValue); return;
            }
            long candBudget = rescanCandidateBudget > 0 ? rescanCandidateBudget : Long.MAX_VALUE;
            long saved = maxCandidatesBudget;
            try {
                for (int attempt = 0; ; attempt++) {
                    // The budget HALVES on each retry. A failed attempt discards all its work
                    // (mining restarts from scratch on the same data), so a fixed budget would
                    // make the total cost a multiple of the retry count; halving keeps the
                    // total below twice the initial budget.
                    maxCandidatesBudget = candBudget == Long.MAX_VALUE
                            ? Long.MAX_VALUE : Math.max(1L, candBudget >> attempt);
                    mineDeadlineNanos = (rescanMillisBudget > 0)
                            ? System.nanoTime() + (rescanMillisBudget >> attempt) * 1_000_000L : 0;
                    try {
                        mineAndClassify(db, uut, lutDynValue);
                        return;
                    } catch (PatternBudgetExceeded e) {
                        if (slMinRatioEff >= SL_RATIO_CAP || attempt >= SL_MAX_RETRIES) throw e;
                        slMinRatioEff = Math.min(SL_RATIO_CAP, slMinRatioEff + SL_RATIO_STEP);
                        Sl_min = slMinRatioEff * Su;
                        lutDynValue = Math.max(lutDynValue, tsuRef * Sl_min);
                        rescanRetries++;
                        largeMap.clear(); prelargeMap.clear();   // discard partial results
                        candidateCount = 0;
                    }
                }
            } finally { maxCandidatesBudget = saved; mineDeadlineNanos = 0; }
        }

        private void mineAndClassify(List<Sequence> db, double uut, double lut) {
            minedBounds = (tracking == Tracking.PEU) ? new HashMap<>() : null;
            Map<CompactPattern, Double> all;
            try {
                all = mineBatchWithIUB(db, lut);
                if (minedBounds != null) { trackedBound.clear(); trackedBound.putAll(minedBounds); }
            } finally {
                minedBounds = null;
            }
            for (Map.Entry<CompactPattern, Double> e : all.entrySet()) {
                if (e.getValue() >= uut) largeMap.put(e.getKey(), e.getValue());
                else prelargeMap.put(e.getKey(), e.getValue());
            }
            this.patternCount = largeMap.size(); this.prelargeCount = prelargeMap.size();
        }

        public void exportLargePatterns(String outputPath) {
            if (!writeToFile || outputPath == null) return;
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputPath))) {
                List<Map.Entry<CompactPattern, Double>> sortedEntries = new ArrayList<>(largeMap.entrySet());
                sortedEntries.sort((e1, e2) -> Double.compare(e2.getValue(), e1.getValue()));

                for (Map.Entry<CompactPattern, Double> entry : sortedEntries) {
                    // CompactPattern is converted to Pattern only here, off the hot path.
                    writer.write(entry.getKey().toPattern().toIntuitiveString()
                            + " #UTIL: " + entry.getValue());
                    writer.newLine();
                }
            } catch (IOException e) {
                System.err.println("Error writing Adaptive output file: " + e.getMessage());
            }
        }
    }
}
