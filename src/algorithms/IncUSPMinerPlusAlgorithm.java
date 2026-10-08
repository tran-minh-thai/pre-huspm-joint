package algorithms;

import common.*;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

/**
 * Baseline: IncUSP-Miner+ (Wang and Huang, ACM TIST 2018).
 * The TSU bound follows Definitions 15 and 16 of Wang and Huang (two cases for l-sequences).
 * - candidateCount: number of candidate nodes generated.
 * - prunedTSU: number of candidates pruned by the TSU bound.
 */
public class IncUSPMinerPlusAlgorithm {

    public double Su;
    public double currentMinUtil;
    public int maxDepth = 1000; // safety cap against stack overflow; not part of the baseline logic

    // ========================================================================
    // MEASUREMENT COUNTERS
    // ========================================================================
    public int patternCount = 0;
    public long candidateCount = 0;
    public long exploredNodes = 0; // recursion entries, counted before the node-level bound check
    public long prunedTSU = 0;

    public Map<Pattern, Double> largeMap = new HashMap<>();

    public IncUSPMinerPlusAlgorithm(double Su) {
        this.Su = Su;
    }

    // ========================================================================
    // INCUSP-MINER+ DATA STRUCTURES
    // ========================================================================
    static class Instance {
        int itemsetIdx, itemIdx;
        double utility;

        public Instance(int is, int ii, double u) {
            itemsetIdx = is;
            itemIdx = ii;
            utility = u;
        }
    }

    static class Tracker {
        int seqId;
        List<Instance> instances = new ArrayList<>();

        double exactUtility = 0; // u(alpha, s) - best utility over the instances
        double peu = 0;          // PEU(alpha, s)
        double tsu = 0;          // TSU of this tracker (Definitions 15 and 16)

        public Tracker(int id) {
            this.seqId = id;
        }

        public void addInstance(Instance inst, Sequence seq) {
            instances.add(inst);
            // Update exactUtility
            if (inst.utility > exactUtility) exactUtility = inst.utility;

            // Update the PEU of this prefix (used by case 2 of the TSU of its extensions)
            double currentPeu = inst.utility + seq.getRestUtility(inst.itemsetIdx, inst.itemIdx);
            if (currentPeu > peu) peu = currentPeu;
        }

        /**
         * TSU(t, s) as in Definitions 15 and 16 of Wang and Huang.
         */
        public void finalizeTSU(Tracker parentTracker, Sequence seq) {
            if (instances.isEmpty()) {
                this.tsu = 0;
                return;
            }

            // 1-sequence (Definition 15)
            if (parentTracker == null) {
                Instance firstInst = instances.get(0);
                this.tsu = firstInst.utility + seq.getRestUtility(firstInst.itemsetIdx, firstInst.itemIdx);
            }
            // l-sequence (Definition 16: two cases)
            else {
                Instance parentFirstInst = parentTracker.instances.get(0);

                // Test: does the first position of the prefix carry its largest utility?
                // The 1e-6 tolerance absorbs floating-point rounding.
                if (Math.abs(parentTracker.exactUtility - parentFirstInst.utility) < 1e-6) {
                    // Case 1: TSU = u(alpha, a_p1, s) + u(i, p1, s) + ru(i, p1, s)
                    // (instances.get(0).utility is taken as u(alpha, a_p1) + u(i, p1))
                    Instance firstInst = instances.get(0);
                    this.tsu = firstInst.utility + seq.getRestUtility(firstInst.itemsetIdx, firstInst.itemIdx);
                } else {
                    // Case 2: fall back to the PEU of the parent
                    this.tsu = parentTracker.peu;
                }
            }
        }
    }

    static class TrackerList {
        Map<Integer, Tracker> trackers = new HashMap<>();

        public Tracker getTracker(int seqId) {
            return trackers.computeIfAbsent(seqId, k -> new Tracker(seqId));
        }
    }

    static class CPTreeNode {
        Pattern pattern;
        TrackerList trackerList;
        double exactUtilityD = 0;
        double tsuD = 0;
        Map<Integer, CPTreeNode> iChildren = new HashMap<>();
        Map<Integer, CPTreeNode> sChildren = new HashMap<>();

        public CPTreeNode(Pattern p) {
            this.pattern = p;
            this.trackerList = new TrackerList();
        }
    }

    CPTreeNode root;
    Map<Integer, Sequence> globalDB = new HashMap<>();

    /**
     * Releases the large structures (CP-tree and globalDB) at once when the algorithm cannot
     * continue (for example after an OutOfMemoryError in an incremental batch). The CP-tree
     * grows across batches and can occupy several GB; unless it is released, the following
     * allocations on the main thread (logging, snapshots) also run out of memory. The caller
     * should force a full GC afterwards to reclaim the heap.
     */
    public void releaseState() {
        root = null;
        if (globalDB != null) globalDB.clear();
        if (largeMap != null) largeMap.clear();
    }

    // ========================================================================
    // CORE (INITIAL AND INCREMENTAL MINING)
    // ========================================================================
    public void runInitialMining(List<Sequence> dInit, double currentTotalUtility) {
        // Reset state BEFORE starting so every 1-item candidate is counted.
        patternCount = 0;
        candidateCount = 0;
        exploredNodes = 0;
        prunedTSU = 0;
        largeMap.clear();
        globalDB.clear();

        this.currentMinUtil = currentTotalUtility * Su;
        for (Sequence seq : dInit) globalDB.put(seq.id, seq);

        root = new CPTreeNode(new Pattern());

        Map<Integer, TrackerList> l1Trackers = new HashMap<>();
        for (Sequence seq : dInit) {
            for (int is = 0; is < seq.itemsets.size(); is++) {
                QItemset itemset = seq.itemsets.get(is);
                for (int ii = 0; ii < itemset.items.size(); ii++) {
                    QItem item = itemset.items.get(ii);
                    TrackerList tl = l1Trackers.computeIfAbsent(item.id, k -> new TrackerList());
                    tl.getTracker(seq.id).addInstance(new Instance(is, ii, item.utility), seq);
                }
            }
        }

        // Fair counting (a candidate counts when generated): every 1-item, BEFORE the TSU filter.
        candidateCount += l1Trackers.size();

        // Finalize the TSU of the 1-items
        for (TrackerList tl : l1Trackers.values()) {
            for (Tracker t : tl.trackers.values()) {
                t.finalizeTSU(null, globalDB.get(t.seqId));
            }
        }

        for (Map.Entry<Integer, TrackerList> entry : l1Trackers.entrySet()) {
            TrackerList tl = entry.getValue();
            double aggTsu = 0;
            for (Tracker t : tl.trackers.values()) aggTsu += t.tsu;
            if (aggTsu < currentMinUtil) {
                prunedTSU++; // drop the 1-item here; it is not stored in the CP-tree
                continue;
            }
            Pattern p = new Pattern(entry.getKey());
            CPTreeNode child = new CPTreeNode(p);
            child.trackerList = tl;
            root.sChildren.put(entry.getKey(), child);

            expandNode(child, dInit);
        }

        countHUSP(root);
    }

    public void runIncrementalMining(List<Sequence> deltaD, List<Sequence> dSoFar, double newTotalUtility) {
        this.currentMinUtil = newTotalUtility * Su;
        for (Sequence seq : deltaD) globalDB.put(seq.id, seq);

        patternCount = 0;
        candidateCount = 0;
        exploredNodes = 0;
        prunedTSU = 0;
        largeMap.clear();

        Map<Integer, TrackerList> deltaL1Trackers = new HashMap<>();
        for (Sequence seq : deltaD) {
            for (int is = 0; is < seq.itemsets.size(); is++) {
                QItemset itemset = seq.itemsets.get(is);
                for (int ii = 0; ii < itemset.items.size(); ii++) {
                    QItem item = itemset.items.get(ii);
                    TrackerList tl = deltaL1Trackers.computeIfAbsent(item.id, k -> new TrackerList());
                    tl.getTracker(seq.id).addInstance(new Instance(is, ii, item.utility), seq);
                }
            }
        }

        // Fair counting: every 1-item generated from the batch, BEFORE the TSU filter.
        candidateCount += deltaL1Trackers.size();

        // Finalize the TSU of the 1-items in deltaD
        for (TrackerList tl : deltaL1Trackers.values()) {
            for (Tracker t : tl.trackers.values()) {
                t.finalizeTSU(null, globalDB.get(t.seqId));
            }
        }

        for (Map.Entry<Integer, TrackerList> entry : deltaL1Trackers.entrySet()) {
            int item = entry.getKey();
            CPTreeNode child = root.sChildren.get(item);

            if (child == null) {
                Pattern p = new Pattern(item);
                child = new CPTreeNode(p);
                root.sChildren.put(item, child);
            }
            updateAndExpandNode(child, entry.getValue(), deltaD);
        }

        countHUSP(root);
    }

    // ========================================================================
    // RECURSION (EXTENSION AND TSU PRUNING)
    // ========================================================================
    private void expandNode(CPTreeNode node, List<Sequence> db) {
        expandNode(node, db, 1);
    }

    private void expandNode(CPTreeNode node, List<Sequence> db, int depth) {
        if (depth > maxDepth) return;
        // Stop as soon as the runner cancels the run (timeout), so an orphaned thread does
        // not keep using CPU or holding heap and distort the next measurement. This only
        // fires after a timeout, when the result is discarded, so mining logic is unchanged.
        if (Thread.currentThread().isInterrupted()) return;
        exploredNodes++; // this pattern is expanded; counted BEFORE the node-level TSU check
        // Fair counting: candidateCount is not incremented on entry. I/S-extension candidates are
        // counted when generated (before the TSU filter) below, as in the adaptive algorithm.

        double exactUtil = 0, tsuAccumulated = 0;
        for (Tracker t : node.trackerList.trackers.values()) {
            exactUtil += t.exactUtility;
            tsuAccumulated += t.tsu;
        }
        node.exactUtilityD = exactUtil;
        node.tsuD = tsuAccumulated;

        // Safety net: a node's TSU has already passed when it was added to the CP-tree.
        // The check stays here to cover the root and the 1-item entries.
        if (node.tsuD < currentMinUtil) {
            prunedTSU++;
            return;
        }

        Map<Integer, TrackerList> iExtensions = new HashMap<>();
        Map<Integer, TrackerList> sExtensions = new HashMap<>();

        for (Tracker t : node.trackerList.trackers.values()) {
            Sequence seq = globalDB.get(t.seqId);
            for (Instance inst : t.instances) {
                QItemset currentSet = seq.itemsets.get(inst.itemsetIdx);
                for (int i = inst.itemIdx + 1; i < currentSet.items.size(); i++) {
                    QItem item = currentSet.items.get(i);
                    TrackerList tl = iExtensions.computeIfAbsent(item.id, k -> new TrackerList());
                    tl.getTracker(seq.id).addInstance(new Instance(inst.itemsetIdx, i, inst.utility + item.utility), seq);
                }
                for (int is = inst.itemsetIdx + 1; is < seq.itemsets.size(); is++) {
                    QItemset nextSet = seq.itemsets.get(is);
                    for (int ii = 0; ii < nextSet.items.size(); ii++) {
                        QItem item = nextSet.items.get(ii);
                        TrackerList tl = sExtensions.computeIfAbsent(item.id, k -> new TrackerList());
                        tl.getTracker(seq.id).addInstance(new Instance(is, ii, inst.utility + item.utility), seq);
                    }
                }
            }
        }

        // Fair counting: every generated I/S-extension candidate, BEFORE the TSU filter.
        candidateCount += iExtensions.size() + sExtensions.size();

        // Finalize the TSU of each I-extension and check it BEFORE storing the node in the CP-tree.
        // The CP-tree stores only candidates whose TSU reaches min_util.
        Iterator<Map.Entry<Integer, TrackerList>> itI = iExtensions.entrySet().iterator();
        while (itI.hasNext()) {
            Map.Entry<Integer, TrackerList> entry = itI.next();
            TrackerList childTl = entry.getValue();
            double childAggTsu = 0;
            for (Tracker childT : childTl.trackers.values()) {
                Tracker parentT = node.trackerList.trackers.get(childT.seqId);
                childT.finalizeTSU(parentT, globalDB.get(childT.seqId));
                childAggTsu += childT.tsu;
            }
            if (childAggTsu < currentMinUtil) {
                prunedTSU++;
                itI.remove(); // not stored in the CP-tree; the TrackerList becomes garbage at once
                continue;
            }
            Pattern np = node.pattern.iConcatenate(entry.getKey());
            CPTreeNode child = new CPTreeNode(np);
            child.trackerList = childTl;
            node.iChildren.put(entry.getKey(), child);
            expandNode(child, db, depth + 1);
            itI.remove();
        }

        Iterator<Map.Entry<Integer, TrackerList>> itS = sExtensions.entrySet().iterator();
        while (itS.hasNext()) {
            Map.Entry<Integer, TrackerList> entry = itS.next();
            TrackerList childTl = entry.getValue();
            double childAggTsu = 0;
            for (Tracker childT : childTl.trackers.values()) {
                Tracker parentT = node.trackerList.trackers.get(childT.seqId);
                childT.finalizeTSU(parentT, globalDB.get(childT.seqId));
                childAggTsu += childT.tsu;
            }
            if (childAggTsu < currentMinUtil) {
                prunedTSU++;
                itS.remove();
                continue;
            }
            Pattern np = node.pattern.sConcatenate(entry.getKey());
            CPTreeNode child = new CPTreeNode(np);
            child.trackerList = childTl;
            node.sChildren.put(entry.getKey(), child);
            expandNode(child, db, depth + 1);
            itS.remove();
        }
    }

    private void updateAndExpandNode(CPTreeNode node, TrackerList deltaTrackers, List<Sequence> deltaD) {
        exploredNodes++; // this pattern is expanded in the incremental phase; counted BEFORE the node-level TSU check

        // Merge the delta trackers into the current node
        if (deltaTrackers != null) {
            for (Map.Entry<Integer, Tracker> entry : deltaTrackers.trackers.entrySet()) {
                node.trackerList.trackers.put(entry.getKey(), entry.getValue());
            }
        }

        double newExactUtil = 0, newTsu = 0;
        for (Tracker t : node.trackerList.trackers.values()) {
            newExactUtil += t.exactUtility;
            newTsu += t.tsu;
        }
        node.exactUtilityD = newExactUtil;
        node.tsuD = newTsu;

        if (node.tsuD < currentMinUtil) {
            prunedTSU++;
            return;
        }

        Map<Integer, TrackerList> deltaIExts = new HashMap<>();
        Map<Integer, TrackerList> deltaSExts = new HashMap<>();

        if (deltaTrackers != null) {
            for (Tracker t : deltaTrackers.trackers.values()) {
                Sequence seq = globalDB.get(t.seqId);
                for (Instance inst : t.instances) {
                    QItemset currentSet = seq.itemsets.get(inst.itemsetIdx);
                    for (int i = inst.itemIdx + 1; i < currentSet.items.size(); i++) {
                        QItem item = currentSet.items.get(i);
                        TrackerList tl = deltaIExts.computeIfAbsent(item.id, k -> new TrackerList());
                        tl.getTracker(seq.id).addInstance(new Instance(inst.itemsetIdx, i, inst.utility + item.utility), seq);
                    }
                    for (int is = inst.itemsetIdx + 1; is < seq.itemsets.size(); is++) {
                        QItemset nextSet = seq.itemsets.get(is);
                        for (int ii = 0; ii < nextSet.items.size(); ii++) {
                            QItem item = nextSet.items.get(ii);
                            TrackerList tl = deltaSExts.computeIfAbsent(item.id, k -> new TrackerList());
                            tl.getTracker(seq.id).addInstance(new Instance(is, ii, inst.utility + item.utility), seq);
                        }
                    }
                }
            }
        }

        // Fair counting: every I/S-extension candidate generated from the batch, BEFORE the TSU is finalized and checked.
        candidateCount += deltaIExts.size() + deltaSExts.size();

        // Finalize the TSU of the batch extensions
        for (Map.Entry<Integer, TrackerList> entry : deltaIExts.entrySet()) {
            TrackerList childTl = entry.getValue();
            for (Tracker childT : childTl.trackers.values()) {
                Tracker parentT = node.trackerList.trackers.get(childT.seqId);
                childT.finalizeTSU(parentT, globalDB.get(childT.seqId));
            }
        }

        for (Map.Entry<Integer, TrackerList> entry : deltaSExts.entrySet()) {
            TrackerList childTl = entry.getValue();
            for (Tracker childT : childTl.trackers.values()) {
                Tracker parentT = node.trackerList.trackers.get(childT.seqId);
                childT.finalizeTSU(parentT, globalDB.get(childT.seqId));
            }
        }

        Set<Integer> allIKeys = new HashSet<>(node.iChildren.keySet());
        allIKeys.addAll(deltaIExts.keySet());
        for (int key : allIKeys) {
            CPTreeNode child = node.iChildren.get(key);
            if (child == null) {
                Pattern np = node.pattern.iConcatenate(key);
                child = new CPTreeNode(np);
                node.iChildren.put(key, child);
            }
            updateAndExpandNode(child, deltaIExts.get(key), deltaD);
        }

        Set<Integer> allSKeys = new HashSet<>(node.sChildren.keySet());
        allSKeys.addAll(deltaSExts.keySet());
        for (int key : allSKeys) {
            CPTreeNode child = node.sChildren.get(key);
            if (child == null) {
                Pattern np = node.pattern.sConcatenate(key);
                child = new CPTreeNode(np);
                node.sChildren.put(key, child);
            }
            updateAndExpandNode(child, deltaSExts.get(key), deltaD);
        }
    }

    private void countHUSP(CPTreeNode node) {
        if (node == null) return;
        if (node != root && node.exactUtilityD >= currentMinUtil) {
            largeMap.put(node.pattern, node.exactUtilityD);
        }
        for (CPTreeNode child : node.iChildren.values()) countHUSP(child);
        for (CPTreeNode child : node.sChildren.values()) countHUSP(child);
        patternCount = largeMap.size();
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
            System.err.println("Error writing IncUSP-MinerPlus output file: " + e.getMessage());
        }
    }
}