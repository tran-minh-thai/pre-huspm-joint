package algorithms;

import common.*;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

/**
 * Baseline: HUSP-ULL (Gan et al., IEEE Transactions on Cybernetics, 2021), instrumented for measurement:
 * - candidateCount: total number of candidates generated.
 * - prunedSWU: 1-items pruned by SWU.
 * - prunedLAR: subtrees cut because the PEU of the current pattern is below the threshold.
 * - prunedIIP: candidate extensions discarded because their own PEU is below the threshold.
 */
public class HUSPULLAlgorithm {

    // ========================================================================
    // 1. UL-NODE (one entry of a utility-linked list)
    // ========================================================================
    static class ULNode {
        int seqId;
        int itemsetIdx;
        int itemIdx;
        double prefixUtility;
        double remainingUtility;

        public ULNode(int seqId, int itemsetIdx, int itemIdx, double prefixUtility, double remainingUtility) {
            this.seqId = seqId;
            this.itemsetIdx = itemsetIdx;
            this.itemIdx = itemIdx;
            this.prefixUtility = prefixUtility;
            this.remainingUtility = remainingUtility;
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
    public long prunedSWU = 0;  // 1-items removed by SWU
    public long prunedLAR = 0;  // subtree-level cuts (PEU of the current pattern)
    public long prunedIIP = 0;  // candidate-level cuts (PEU of the extension)

    public Map<Pattern, Double> largeMap = new HashMap<>();
    private Map<Integer, Sequence> seqLookupMap = new HashMap<>();

    public HUSPULLAlgorithm(double absoluteMinUtility) {
        this.minUtility = absoluteMinUtility;
    }

    // ========================================================================
    // 3. HUSP-ULL CORE
    // ========================================================================
    public void run(List<Sequence> database) {
        // Reset all counters before mining a new batch
        largeMap.clear();
        patternCount = 0;
        candidateCount = 0;
        exploredNodes = 0;
        prunedSWU = 0;
        prunedLAR = 0;
        prunedIIP = 0;
        seqLookupMap.clear();

        // 1. Precompute suffix sums (O(1) remaining utility)
        for (Sequence seq : database) {
            seq.calculateRemainingUtilities();
            seqLookupMap.put(seq.id, seq);
        }

        // 2. Compute the SWU (sequence-weighted utility) of each 1-item
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

        // Fair counting (a candidate counts when generated): every 1-item, BEFORE the SWU filter.
        candidateCount += initialSWU.size();

        // 3. Keep the promising items by SWU
        List<Integer> promisingItems = new ArrayList<>();
        for (Map.Entry<Integer, Double> entry : initialSWU.entrySet()) {
            if (entry.getValue() >= minUtility) {
                promisingItems.add(entry.getKey());
            } else {
                prunedSWU++; // removed by SWU
            }
        }
        Collections.sort(promisingItems); // lexicographic order

        // 4. Start the recursion (pattern growth)
        for (Integer item : promisingItems) {
            Pattern pattern = new Pattern(item);
            List<ULNode> initialULList = buildInitialULList(database, item);

            double maxUtil = calculateMaxUtility(initialULList);
            if (maxUtil >= minUtility) {
                savePattern(pattern, maxUtil);
            }

            huspULLRecursive(pattern, initialULList, 1);
        }
    }

    private void savePattern(Pattern pattern, double utility) {
        largeMap.put(pattern, utility);
        patternCount = largeMap.size();
    }

    private void huspULLRecursive(Pattern prefix, List<ULNode> ulList, int depth) {
        // Depth cap: guards against StackOverflowError on very long sequences. It is a
        // safety net set well above the recursion depth reached on the evaluated datasets.
        if (depth > 2000) return;
        // Stop as soon as the runner cancels the run (timeout), so an orphaned thread does
        // not keep using CPU or holding heap and distort the next measurement. This only
        // fires after a timeout, when the result is discarded, so mining logic is unchanged.
        if (Thread.currentThread().isInterrupted()) return;
        exploredNodes++; // this pattern is expanded; counted BEFORE the subtree-level check
        // Fair counting: candidateCount is not incremented on entry. I/S-extension candidates are
        // counted when generated (before the PEU check) below, as in the adaptive algorithm.
        // ==========================================================
        // SUBTREE-LEVEL CUT (counter prunedLAR): PEU of the current pattern
        // ==========================================================
        double totalMaxUpperBound = 0;
        Map<Integer, Double> seqMaxUBMap = new HashMap<>();
        for (ULNode node : ulList) {
            double currentUB = node.prefixUtility + node.remainingUtility;
            if (currentUB > seqMaxUBMap.getOrDefault(node.seqId, 0.0)) {
                seqMaxUBMap.put(node.seqId, currentUB);
            }
        }
        for (double ub : seqMaxUBMap.values()) totalMaxUpperBound += ub;

        // If the summed upper bound is below the threshold, cut the whole branch
        if (totalMaxUpperBound < minUtility) {
            prunedLAR++; // one subtree cut
            return;
        }

        // ==========================================================
        // CANDIDATE GENERATION
        // ==========================================================
        Map<Integer, List<ULNode>> iCandidatesMap = new HashMap<>();
        Map<Integer, List<ULNode>> sCandidatesMap = new HashMap<>();

        for (ULNode node : ulList) {
            Sequence originalSeq = seqLookupMap.get(node.seqId);
            if (originalSeq == null) continue;

            // 1. I-Extension
            QItemset currentSet = originalSeq.itemsets.get(node.itemsetIdx);
            for (int j = node.itemIdx + 1; j < currentSet.items.size(); j++) {
                QItem targetItem = currentSet.items.get(j);
                double newPrefixUtil = node.prefixUtility + targetItem.utility;
                ULNode newNode = new ULNode(node.seqId, node.itemsetIdx, j, newPrefixUtil, originalSeq.getRestUtility(node.itemsetIdx, j));
                iCandidatesMap.computeIfAbsent(targetItem.id, k -> new ArrayList<>()).add(newNode);
            }

            // 2. S-Extension
            for (int i = node.itemsetIdx + 1; i < originalSeq.itemsets.size(); i++) {
                QItemset targetSet = originalSeq.itemsets.get(i);
                for (int j = 0; j < targetSet.items.size(); j++) {
                    QItem targetItem = targetSet.items.get(j);
                    double newPrefixUtil = node.prefixUtility + targetItem.utility;
                    ULNode newNode = new ULNode(node.seqId, i, j, newPrefixUtil, originalSeq.getRestUtility(i, j));
                    sCandidatesMap.computeIfAbsent(targetItem.id, k -> new ArrayList<>()).add(newNode);
                }
            }
        }

        // Fair counting: every generated I/S-extension candidate (grouped by item),
        // BEFORE the PEU check, as in the adaptive algorithm.
        candidateCount += iCandidatesMap.size() + sCandidatesMap.size();

        // ==========================================================
        // CANDIDATE-LEVEL CUT (counter prunedIIP): PEU of each extension
        // Each entry is removed through the Iterator once processed, so the full I/S
        // candidate maps are not kept alive on the call stack during the recursion.
        // ==========================================================

        // I-extensions
        Iterator<Map.Entry<Integer, List<ULNode>>> itI = iCandidatesMap.entrySet().iterator();
        while (itI.hasNext()) {
            Map.Entry<Integer, List<ULNode>> entry = itI.next();
            List<ULNode> nodes = entry.getValue();

            if (calculatePEU(nodes) >= minUtility) { // passes the PEU check
                Pattern newPattern = prefix.iConcatenate(entry.getKey());
                double actualUtility = calculateMaxUtility(nodes);
                if (actualUtility >= minUtility) {
                    savePattern(newPattern, actualUtility);
                }
                huspULLRecursive(newPattern, nodes, depth + 1);
            } else {
                prunedIIP++; // cut by the PEU check
            }
            itI.remove(); // drop the reference to nodes once processed
        }

        // S-extensions
        Iterator<Map.Entry<Integer, List<ULNode>>> itS = sCandidatesMap.entrySet().iterator();
        while (itS.hasNext()) {
            Map.Entry<Integer, List<ULNode>> entry = itS.next();
            List<ULNode> nodes = entry.getValue();

            if (calculatePEU(nodes) >= minUtility) {
                Pattern newPattern = prefix.sConcatenate(entry.getKey());
                double actualUtility = calculateMaxUtility(nodes);
                if (actualUtility >= minUtility) {
                    savePattern(newPattern, actualUtility);
                }
                huspULLRecursive(newPattern, nodes, depth + 1);
            } else {
                prunedIIP++;
            }
            itS.remove();
        }
    }

    private double calculatePEU(List<ULNode> nodes) {
        Map<Integer, Double> maxUBPerSeq = new HashMap<>();
        for (ULNode node : nodes) {
            double ub = node.prefixUtility + node.remainingUtility;
            if (ub > maxUBPerSeq.getOrDefault(node.seqId, 0.0)) {
                maxUBPerSeq.put(node.seqId, ub);
            }
        }
        double totalPEU = 0;
        for (double val : maxUBPerSeq.values()) totalPEU += val;
        return totalPEU;
    }

    private double calculateMaxUtility(List<ULNode> nodes) {
        Map<Integer, Double> maxUtilPerSeq = new HashMap<>();
        for (ULNode node : nodes) {
            if (node.prefixUtility > maxUtilPerSeq.getOrDefault(node.seqId, 0.0)) {
                maxUtilPerSeq.put(node.seqId, node.prefixUtility);
            }
        }
        double total = 0;
        for (double val : maxUtilPerSeq.values()) total += val;
        return total;
    }

    private List<ULNode> buildInitialULList(List<Sequence> db, int targetItem) {
        List<ULNode> list = new ArrayList<>();
        for (Sequence seq : db) {
            for (int i = 0; i < seq.itemsets.size(); i++) {
                QItemset is = seq.itemsets.get(i);
                for (int j = 0; j < is.items.size(); j++) {
                    if (is.items.get(j).id == targetItem) {
                        list.add(new ULNode(seq.id, i, j, is.items.get(j).utility, seq.getRestUtility(i, j)));
                    }
                }
            }
        }
        return list;
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