package common;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Sequence {
    public int id;
    public List<QItemset> itemsets = new ArrayList<>();
    public double sequenceUtility = 0;

    // Remaining (rest) utility per item position, for O(1) lookup
    public List<List<Double>> remainingUtilities = new ArrayList<>();

    // ====================================================================
    //  FLATTENED REPRESENTATION (used by Pre-HUSPM-Joint)
    //
    //  The itemsets are flattened into 1-D primitive arrays, which keeps the
    //  recursive hot path cache-friendly and free of object and iterator
    //  overhead. Other algorithms (USpan, HUSP-ULL, ...) read itemsets and
    //  remainingUtilities; only the adaptive algorithm reads the flat fields.
    //
    //  Layout (four primitive arrays):
    //    int[]     flatItems[k]            = item id at flat position k
    //    double[]  flatUtilities[k]        = utility at flat position k
    //    boolean[] isItemsetEnd[k]         = true if k is the LAST position of its itemset
    //                                        (the next itemset starts at k+1)
    //    double[]  flatRemainingUtility[k] = sum of utilities from k+1 to the end
    //    flatLen                           = total number of items (flat array length)
    //
    //  Itemset boundaries are read from isItemsetEnd (L bytes), so no position-to-
    //  itemset index (4L bytes) or itemset start table (4(M+1) bytes) is stored.
    //
    //  Usage (second-pass scan):
    //    for (int q = parentFlatIdx + 1; q < flatLen; q++) {
    //        if (flatItems[q] == itemToFind) { ... I-extension match ... }
    //        if (isItemsetEnd[q]) break;   // crossed the itemset boundary: I-extensions done
    //    }
    //    // S-extensions start at q+1 (after breaking at isItemsetEnd[q])
    // ====================================================================
    public int[] flatItems;
    public double[] flatUtilities;
    public boolean[] isItemsetEnd;
    public double[] flatRemainingUtility;
    public int flatLen = 0;

    public Sequence(int id) {
        this.id = id;
    }

    // Precomputes the remaining utility (suffix sum), right to left
    public void calculateRemainingUtilities() {
        remainingUtilities.clear();
        double currentRemaining = 0;
        for (int i = itemsets.size() - 1; i >= 0; i--) {
            QItemset itemset = itemsets.get(i);
            List<Double> itemsetRemUtil = new ArrayList<>(Collections.nCopies(itemset.items.size(), 0.0));
            for (int j = itemset.items.size() - 1; j >= 0; j--) {
                itemsetRemUtil.set(j, currentRemaining);
                currentRemaining += itemset.items.get(j).utility;
            }
            remainingUtilities.add(0, itemsetRemUtil);
        }
        this.sequenceUtility = currentRemaining;
    }

    /**
     * Builds the flat primitive representation. Idempotent: calling it again rebuilds it.
     * The adaptive algorithm calls it once per sequence when adding it to dbLookup.
     */
    public void buildFlatRepresentation() {
        int total = 0;
        for (QItemset is : itemsets) total += is.items.size();
        flatLen = total;
        flatItems = new int[total];
        flatUtilities = new double[total];
        isItemsetEnd = new boolean[total];
        flatRemainingUtility = new double[total];

        int pos = 0;
        for (int i = 0; i < itemsets.size(); i++) {
            QItemset is = itemsets.get(i);
            int n = is.items.size();
            for (int j = 0; j < n; j++) {
                QItem qi = is.items.get(j);
                flatItems[pos] = qi.id;
                flatUtilities[pos] = qi.utility;
                // Mark the last element of the itemset
                if (j == n - 1) isItemsetEnd[pos] = true;
                pos++;
            }
        }

        // Remaining utility: suffix sum of the utilities after position k.
        double running = 0;
        for (int k = total - 1; k >= 0; k--) {
            flatRemainingUtility[k] = running;
            running += flatUtilities[k];
        }
        if (this.sequenceUtility == 0) this.sequenceUtility = running;
    }

    /** O(1) rest utility lookup via flat index. */
    public double getRestUtilityFlat(int flatIdx) {
        return flatRemainingUtility[flatIdx];
    }

    // Remaining utility: O(1) when precomputed, otherwise computed in O(N)
    public double getRestUtility(int itemsetIdx, int itemIdx) {
        if (!remainingUtilities.isEmpty()) {
            return remainingUtilities.get(itemsetIdx).get(itemIdx);
        }

        // O(N) fallback for algorithms that never call calculateRemainingUtilities()
        double rest = 0;
        for (int i = itemsetIdx; i < itemsets.size(); i++) {
            QItemset is = itemsets.get(i);
            int start = (i == itemsetIdx) ? (itemIdx + 1) : 0;
            for (int j = start; j < is.items.size(); j++) {
                rest += is.items.get(j).utility;
            }
        }
        return rest;
    }
}