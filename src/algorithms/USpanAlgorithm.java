package algorithms;

import common.*;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

/**
 * Baseline: USpan (Yin et al., KDD 2012).
 *
 * ---------------------------------------------------------------------------
 * PROVENANCE
 *
 * Independent reimplementation from the definitions, Theorems 1 and 2 and Algorithm 1 of
 * Yin et al. The depth-pruning bound of their Theorem 2 (Eq. (21)) is replaced by a sound
 * bound, as described at its use below. SPMF's AlgoUSpan.java (GPL-3.0) is not used; no line
 * of this file is taken from it (results/invariant-license-provenance compares the two).
 * ---------------------------------------------------------------------------
 *
 * Instrumented for measurement:
 * - candidateCount counts each extension when it is generated.
 * - prunedSWU counts candidates removed by the global and the local SWU bound.
 * - Width pruning (SWU) and depth pruning (prefix utility plus O(1) suffix-sum remaining utility).
 * Assumes every itemset is sorted by item id with no repeated id (so a pattern ends at most once
 * per itemset); instance lists are kept in increasing itemset order.
 */
public class USpanAlgorithm {

    // ========================================================================
    // 1. STATE TRACKING (PROJECTED DATABASE)
    // ========================================================================

    static class Instance {
        int itemsetIdx;
        int itemIdx;
        double utility;

        public Instance(int itemsetIdx, int itemIdx, double utility) {
            this.itemsetIdx = itemsetIdx;
            this.itemIdx = itemIdx;
            this.utility = utility;
        }
    }

    static class ProjectedSequence {
        Sequence seq;
        List<Instance> instances;

        public ProjectedSequence(Sequence seq, List<Instance> instances) {
            this.seq = seq;
            this.instances = instances;
        }
    }

    // ========================================================================
    // 2. MEASUREMENT COUNTERS
    // ========================================================================

    public double minUtility;
    public int patternCount = 0;

    // Counters written to the result log
    public long candidateCount = 0;
    public long exploredNodes = 0; // nodes expanded, i.e. entered and not cut by depth pruning
    public long prunedSWU = 0; // candidates pruned by the sequence-weighted utilization bound
    public long prunedDepth = 0; // branches cut by depth pruning

    // High-utility patterns found (for the output file)
    public Map<Pattern, Double> largeMap = new HashMap<>();

    // Number of index-mismatch warnings (Instance vs Sequence). Logging is capped so a
    // malformed dataset does not flood stderr or slow down the experiment runner.
    private long boundaryWarnings = 0;
    private static final long MAX_BOUNDARY_WARN = 20;

    public USpanAlgorithm(double absoluteMinUtility) {
        this.minUtility = absoluteMinUtility;
    }

    /**
     * Boundary check: the (itemsetIdx, itemIdx) of an Instance must lie inside seq.
     * Otherwise a warning is logged (capped) and false is returned, so the caller skips
     * the instance instead of throwing IndexOutOfBoundsException and stopping the runner.
     * Width and depth pruning are unaffected; this only blocks out-of-bounds access.
     */
    private boolean inBounds(Sequence seq, int itemsetIdx, int itemIdx) {
        if (itemsetIdx < 0 || itemsetIdx >= seq.itemsets.size()) {
            if (boundaryWarnings++ < MAX_BOUNDARY_WARN) {
                System.err.println("[USpan][WARN] itemsetIdx=" + itemsetIdx + " out of bounds (itemsets="
                        + seq.itemsets.size() + ", seqId=" + seq.id + ") - instance skipped");
            }
            return false;
        }
        if (itemIdx < 0 || itemIdx >= seq.itemsets.get(itemsetIdx).items.size()) {
            if (boundaryWarnings++ < MAX_BOUNDARY_WARN) {
                System.err.println("[USpan][WARN] itemIdx=" + itemIdx + " out of bounds (items="
                        + seq.itemsets.get(itemsetIdx).items.size() + ", seqId=" + seq.id + ") - instance skipped");
            }
            return false;
        }
        return true;
    }

    // ========================================================================
    // 3. USPAN CORE
    // ========================================================================

    public void run(List<Sequence> database) {
        // Compute the O(1) suffix sums only for sequences that do not have them yet
        for (Sequence seq : database) {
            if (seq.remainingUtilities.isEmpty()) {
                seq.calculateRemainingUtilities();
            }
        }

        // Reset metrics
        largeMap.clear();
        patternCount = 0;
        candidateCount = 0;
        exploredNodes = 0;
        prunedSWU = 0;
        prunedDepth = 0;

        // ==============================================================
        // STEP 1: WIDTH PRUNING on the 1-item candidates
        // ==============================================================
        Map<Integer, Double> initialSWU = new HashMap<>();
        for (Sequence seq : database) {
            Set<Integer> uniqueItems = new HashSet<>();
            for (QItemset is : seq.itemsets) {
                for (QItem item : is.items) uniqueItems.add(item.id);
            }
            for (Integer id : uniqueItems) {
                initialSWU.put(id, initialSWU.getOrDefault(id, 0.0) + seq.sequenceUtility);
            }
        }

        // Fair counting (a candidate counts when generated): every 1-item, BEFORE the global SWU filter.
        candidateCount += initialSWU.size();

        List<Integer> promisingItems = new ArrayList<>();
        for (Map.Entry<Integer, Double> entry : initialSWU.entrySet()) {
            if (entry.getValue() >= minUtility) {
                promisingItems.add(entry.getKey());
            } else {
                prunedSWU++; // removed by the global SWU
            }
        }
        Collections.sort(promisingItems); // lexicographic order

        // ==============================================================
        // STEP 2: PROJECTION AND RECURSION (PATTERN GROWTH)
        // ==============================================================
        Map<Integer, List<ProjectedSequence>> initialPDBs = buildInitialProjectedDBs(database, promisingItems);
        for (Integer item : promisingItems) {
            Pattern pattern = new Pattern(item);
            List<ProjectedSequence> projectedDB = initialPDBs.remove(item); // freed once its subtree is done

            double maxUtil = calculateMaxUtility(projectedDB);
            if (maxUtil >= minUtility) {
                savePattern(pattern, maxUtil);
            }

            uSpanRecursive(pattern, projectedDB, 1);
        }

        // Release the initial structures
        initialSWU.clear();
        promisingItems.clear();
    }

    private void savePattern(Pattern pattern, double utility) {
        largeMap.put(pattern, utility);
        patternCount = largeMap.size();
    }

    private void uSpanRecursive(Pattern prefix, List<ProjectedSequence> projectedDB, int depth) {
        // Depth cap: guards against StackOverflowError on very long sequences. It is a
        // safety net set well above the recursion depth reached on the evaluated datasets.
        if (depth > 2000) return;
        // Stop as soon as the runner cancels the run (timeout), so an orphaned thread does
        // not keep using CPU or holding heap and distort the next measurement. This only
        // fires after a timeout, when the result is discarded, so mining logic is unchanged.
        if (Thread.currentThread().isInterrupted()) return;
        // Fair counting: candidateCount is not incremented on entry. Extensions are counted when
        // generated (before the local SWU filter) below, as in the adaptive algorithm.
        // ==============================================================
        // STRATEGY 1: DEPTH PRUNING (based on prefix utility + remaining utility)
        // ==============================================================
        // Eq. (21) of the paper (utility at the pivot + rest after it) is not an upper bound (counterexample: Truong-Chi &
        // Fournier-Viger 2019); as in ProUM (2020) a sound bound replaces it: sum over s of max over ending p of u(t,p)+u_rest(p).
        double maxPossibleUtility = 0;
        for (ProjectedSequence pSeq : projectedDB) {
            double seqMaxUB = 0;
            for (Instance inst : pSeq.instances) {
                // Boundary check before getRestUtility (remainingUtilities.get(itemsetIdx).get(itemIdx))
                if (!inBounds(pSeq.seq, inst.itemsetIdx, inst.itemIdx)) continue;
                // O(1) getRestUtility from common.Sequence
                double ub = inst.utility + pSeq.seq.getRestUtility(inst.itemsetIdx, inst.itemIdx);
                if (ub > seqMaxUB) seqMaxUB = ub;
            }
            maxPossibleUtility += seqMaxUB;
        }

        // Cut the whole DFS branch if the summed upper bound does not reach the threshold
        if (maxPossibleUtility < minUtility) {
            prunedDepth++;
            return;
        }
        exploredNodes++; // counted after depth pruning: only nodes that are expanded

        // ==============================================================
        // STRATEGY 2: WIDTH PRUNING ON THE PROJECTED DB (LOCAL SWU)
        // ==============================================================
        Map<Integer, Double> iListSWU = new HashMap<>();
        Map<Integer, Double> sListSWU = new HashMap<>();

        for (ProjectedSequence pSeq : projectedDB) {
            double seqUtil = pSeq.seq.sequenceUtility;
            Set<Integer> iSeen = new HashSet<>();
            Set<Integer> sSeen = new HashSet<>();
            int pivotItemset = -1; // itemset of the pivot, the first ending position (p.665)

            for (Instance inst : pSeq.instances) {
                if (!inBounds(pSeq.seq, inst.itemsetIdx, inst.itemIdx)) continue;
                if (pivotItemset < 0 || inst.itemsetIdx < pivotItemset) pivotItemset = inst.itemsetIdx;
                QItemset currentItemset = pSeq.seq.itemsets.get(inst.itemsetIdx);

                // Collect I-extension candidates: larger items in the same itemset
                for (int i = inst.itemIdx + 1; i < currentItemset.items.size(); i++) {
                    iSeen.add(currentItemset.items.get(i).id);
                }
            }
            // Collect S-extension candidates in one scan: every item to the right of the pivot itemset
            if (pivotItemset >= 0) {
                for (int i = pivotItemset + 1; i < pSeq.seq.itemsets.size(); i++) {
                    for (QItem item : pSeq.seq.itemsets.get(i).items) {
                        sSeen.add(item.id);
                    }
                }
            }

            for (int item : iSeen) iListSWU.put(item, iListSWU.getOrDefault(item, 0.0) + seqUtil);
            for (int item : sSeen) sListSWU.put(item, sListSWU.getOrDefault(item, 0.0) + seqUtil);
        }

        // Fair counting: every generated I/S-extension candidate, BEFORE the local SWU filter.
        candidateCount += iListSWU.size() + sListSWU.size();

        // Apply the local SWU bound
        List<Integer> iList = new ArrayList<>();
        for (Map.Entry<Integer, Double> e : iListSWU.entrySet()) {
            if (e.getValue() >= minUtility) {
                iList.add(e.getKey());
            } else {
                prunedSWU++; // removed by the local SWU
            }
        }
        Collections.sort(iList);

        List<Integer> sList = new ArrayList<>();
        for (Map.Entry<Integer, Double> e : sListSWU.entrySet()) {
            if (e.getValue() >= minUtility) {
                sList.add(e.getKey());
            } else {
                prunedSWU++; // removed by the local SWU
            }
        }
        Collections.sort(sList);

        // ==============================================================
        // EXTENSION (I-EXTENSION AND S-EXTENSION)
        // ==============================================================
        for (Integer item : iList) {
            List<ProjectedSequence> newProjDB = buildIProjectedDB(projectedDB, item);
            if (!newProjDB.isEmpty()) {
                Pattern newPattern = prefix.iConcatenate(item);
                double actualUtility = calculateMaxUtility(newProjDB);
                if (actualUtility >= minUtility) savePattern(newPattern, actualUtility);
                uSpanRecursive(newPattern, newProjDB, depth + 1);
            }
        }

        for (Integer item : sList) {
            List<ProjectedSequence> newProjDB = buildSProjectedDB(projectedDB, item);
            if (!newProjDB.isEmpty()) {
                Pattern newPattern = prefix.sConcatenate(item);
                double actualUtility = calculateMaxUtility(newProjDB);
                if (actualUtility >= minUtility) savePattern(newPattern, actualUtility);
                uSpanRecursive(newPattern, newProjDB, depth + 1);
            }
        }

        // Release memory
        iListSWU.clear();
        sListSWU.clear();
    }

    private double calculateMaxUtility(List<ProjectedSequence> pdb) {
        double totalPatternUtility = 0;
        for (ProjectedSequence pSeq : pdb) {
            double seqMaxUtil = 0;
            for (Instance inst : pSeq.instances) {
                if (inst.utility > seqMaxUtil) seqMaxUtil = inst.utility;
            }
            totalPatternUtility += seqMaxUtil;
        }
        return totalPatternUtility;
    }

    /** All 1-item projections in one database scan; instances in increasing position order. */
    private Map<Integer, List<ProjectedSequence>> buildInitialProjectedDBs(List<Sequence> db, List<Integer> items) {
        Map<Integer, List<ProjectedSequence>> pdbs = new HashMap<>();
        for (Integer item : items) pdbs.put(item, new ArrayList<>());
        Map<Integer, List<Instance>> perSeq = new LinkedHashMap<>();
        for (Sequence seq : db) {
            for (int i = 0; i < seq.itemsets.size(); i++) {
                QItemset is = seq.itemsets.get(i);
                for (int j = 0; j < is.items.size(); j++) {
                    QItem qi = is.items.get(j);
                    if (!pdbs.containsKey(qi.id)) continue; // not promising
                    perSeq.computeIfAbsent(qi.id, k -> new ArrayList<>()).add(new Instance(i, j, qi.utility));
                }
            }
            for (Map.Entry<Integer, List<Instance>> e : perSeq.entrySet()) {
                pdbs.get(e.getKey()).add(new ProjectedSequence(seq, e.getValue()));
            }
            perSeq.clear();
        }
        return pdbs;
    }

    private List<ProjectedSequence> buildIProjectedDB(List<ProjectedSequence> pdb, int targetItem) {
        List<ProjectedSequence> newPdb = new ArrayList<>();
        for (ProjectedSequence pSeq : pdb) {
            // At most one instance per itemset and one match per instance, so no position repeats.
            List<Instance> newInstances = new ArrayList<>();
            for (Instance inst : pSeq.instances) {
                if (!inBounds(pSeq.seq, inst.itemsetIdx, inst.itemIdx)) continue;
                QItemset is = pSeq.seq.itemsets.get(inst.itemsetIdx);
                for (int j = inst.itemIdx + 1; j < is.items.size(); j++) {
                    if (is.items.get(j).id == targetItem) {
                        newInstances.add(new Instance(inst.itemsetIdx, j, inst.utility + is.items.get(j).utility));
                        break;
                    }
                }
            }
            if (!newInstances.isEmpty()) newPdb.add(new ProjectedSequence(pSeq.seq, newInstances));
        }
        return newPdb;
    }

    /**
     * One left-to-right pass from the pivot: an occurrence of the item in itemset i gets
     * (max utility over instances in itemsets strictly before i) + its own utility.
     */
    private List<ProjectedSequence> buildSProjectedDB(List<ProjectedSequence> pdb, int targetItem) {
        List<ProjectedSequence> newPdb = new ArrayList<>();
        for (ProjectedSequence pSeq : pdb) {
            List<Instance> insts = pSeq.instances; // increasing itemsetIdx
            if (insts.isEmpty()) continue;
            List<Instance> newInstances = new ArrayList<>();
            int k = 0;
            double runningMax = Double.NEGATIVE_INFINITY;
            for (int i = insts.get(0).itemsetIdx + 1; i < pSeq.seq.itemsets.size(); i++) {
                while (k < insts.size() && insts.get(k).itemsetIdx < i) {
                    if (insts.get(k).utility > runningMax) runningMax = insts.get(k).utility;
                    k++;
                }
                QItemset is = pSeq.seq.itemsets.get(i);
                for (int j = 0; j < is.items.size(); j++) {
                    if (is.items.get(j).id == targetItem) {
                        newInstances.add(new Instance(i, j, runningMax + is.items.get(j).utility));
                        break;
                    }
                }
            }
            if (!newInstances.isEmpty()) newPdb.add(new ProjectedSequence(pSeq.seq, newInstances));
        }
        return newPdb;
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
            System.err.println("Error writing USpan output file: " + e.getMessage());
        }
    }
}