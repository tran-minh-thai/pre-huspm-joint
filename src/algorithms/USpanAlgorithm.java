package algorithms;

import common.*;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

/**
 * Baseline: USpan (Yin et al., KDD 2012), instrumented for measurement:
 * - candidateCount counts each extension when it is generated.
 * - prunedSWU counts candidates removed by the global and the local SWU bound.
 * - Width pruning (SWU) and depth pruning (prefix utility plus O(1) suffix-sum remaining utility).
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
    public long exploredNodes = 0; // recursion entries, counted before the node-level bound check
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
        for (Integer item : promisingItems) {
            Pattern pattern = new Pattern(item);
            List<ProjectedSequence> projectedDB = buildInitialProjectedDB(database, item);

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
        exploredNodes++; // this pattern is expanded; counted BEFORE depth pruning (the real expansion work)
        // Fair counting: candidateCount is not incremented on entry. Extensions are counted when
        // generated (before the local SWU filter) below, as in the adaptive algorithm.
        // ==============================================================
        // STRATEGY 1: DEPTH PRUNING (based on prefix utility + remaining utility)
        // ==============================================================
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

        // ==============================================================
        // STRATEGY 2: WIDTH PRUNING ON THE PROJECTED DB (LOCAL SWU)
        // ==============================================================
        Map<Integer, Double> iListSWU = new HashMap<>();
        Map<Integer, Double> sListSWU = new HashMap<>();

        for (ProjectedSequence pSeq : projectedDB) {
            double seqUtil = pSeq.seq.sequenceUtility;
            Set<Integer> iSeen = new HashSet<>();
            Set<Integer> sSeen = new HashSet<>();

            for (Instance inst : pSeq.instances) {
                if (!inBounds(pSeq.seq, inst.itemsetIdx, inst.itemIdx)) continue;
                QItemset currentItemset = pSeq.seq.itemsets.get(inst.itemsetIdx);

                // Collect I-extension candidates
                for (int i = inst.itemIdx + 1; i < currentItemset.items.size(); i++) {
                    iSeen.add(currentItemset.items.get(i).id);
                }
                // Collect S-extension candidates
                for (int i = inst.itemsetIdx + 1; i < pSeq.seq.itemsets.size(); i++) {
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

    private List<ProjectedSequence> buildInitialProjectedDB(List<Sequence> db, int targetItem) {
        List<ProjectedSequence> pdb = new ArrayList<>();
        for (Sequence seq : db) {
            List<Instance> instances = new ArrayList<>();
            for (int i = 0; i < seq.itemsets.size(); i++) {
                QItemset is = seq.itemsets.get(i);
                for (int j = 0; j < is.items.size(); j++) {
                    if (is.items.get(j).id == targetItem) {
                        instances.add(new Instance(i, j, is.items.get(j).utility));
                    }
                }
            }
            if (!instances.isEmpty()) pdb.add(new ProjectedSequence(seq, instances));
        }
        return pdb;
    }

    private List<ProjectedSequence> buildIProjectedDB(List<ProjectedSequence> pdb, int targetItem) {
        List<ProjectedSequence> newPdb = new ArrayList<>();
        for (ProjectedSequence pSeq : pdb) {
            // 64-bit key (high 32 bits = itemsetIdx, low 32 bits = itemIdx) avoids collisions on long sequences
            Map<Long, Instance> bestInstances = new HashMap<>();
            for (Instance inst : pSeq.instances) {
                if (!inBounds(pSeq.seq, inst.itemsetIdx, inst.itemIdx)) continue;
                QItemset is = pSeq.seq.itemsets.get(inst.itemsetIdx);
                for (int j = inst.itemIdx + 1; j < is.items.size(); j++) {
                    if (is.items.get(j).id == targetItem) {
                        double newUtil = inst.utility + is.items.get(j).utility;
                        long key = ((long) inst.itemsetIdx << 32) | (j & 0xFFFFFFFFL);
                        Instance prev = bestInstances.get(key);
                        if (prev == null || prev.utility < newUtil) {
                            bestInstances.put(key, new Instance(inst.itemsetIdx, j, newUtil));
                        }
                    }
                }
            }
            if (!bestInstances.isEmpty()) {
                newPdb.add(new ProjectedSequence(pSeq.seq, new ArrayList<>(bestInstances.values())));
            }
        }
        return newPdb;
    }

    private List<ProjectedSequence> buildSProjectedDB(List<ProjectedSequence> pdb, int targetItem) {
        List<ProjectedSequence> newPdb = new ArrayList<>();
        for (ProjectedSequence pSeq : pdb) {
            Map<Long, Instance> bestInstances = new HashMap<>();
            for (Instance inst : pSeq.instances) {
                if (!inBounds(pSeq.seq, inst.itemsetIdx, inst.itemIdx)) continue;
                for (int i = inst.itemsetIdx + 1; i < pSeq.seq.itemsets.size(); i++) {
                    QItemset is = pSeq.seq.itemsets.get(i);
                    for (int j = 0; j < is.items.size(); j++) {
                        if (is.items.get(j).id == targetItem) {
                            double newUtil = inst.utility + is.items.get(j).utility;
                            long key = ((long) i << 32) | (j & 0xFFFFFFFFL);
                            Instance prev = bestInstances.get(key);
                            if (prev == null || prev.utility < newUtil) {
                                bestInstances.put(key, new Instance(i, j, newUtil));
                            }
                        }
                    }
                }
            }
            if (!bestInstances.isEmpty()) {
                newPdb.add(new ProjectedSequence(pSeq.seq, new ArrayList<>(bestInstances.values())));
            }
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