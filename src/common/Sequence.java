package common;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Sequence {
    public int id;
    public List<QItemset> itemsets = new ArrayList<>();
    public double sequenceUtility = 0;

    // Ma trận lưu trữ tiện ích còn lại (Rest Utility) phục vụ tra cứu O(1)
    public List<List<Double>> remainingUtilities = new ArrayList<>();

    // ====================================================================
    //  FLATTENED REPRESENTATION (cho Pre-HUSPM-Adaptive)
    //
    //  Trải phẳng toàn bộ List<QItemset> → List<List<QItem>> thành mảng 1D
    //  primitive. Mục tiêu: cache-friendly + bỏ Object/iterator overhead
    //  trong hot path đệ quy. Các thuật toán khác (USpan, HUSP-ULL, ...) vẫn
    //  dùng itemsets/remainingUtilities cũ - chỉ Adaptive đọc flat fields.
    //
    //  Layout (4 mảng nguyên thuỷ - tối thiểu hoá memory footprint):
    //    int[]    flatItems[k]            = item.id tại vị trí flat k
    //    double[] flatUtilities[k]        = utility tại vị trí flat k
    //    boolean[] isItemsetEnd[k]        = true nếu k là vị trí CUỐI của itemset
    //                                       (next itemset bắt đầu tại k+1)
    //    double[] flatRemainingUtility[k] = suffix sum utility từ k+1 đến hết
    //    flatLen                          = tổng số item (size mảng flat)
    //
    //  isItemsetEnd thay flatToItemsetIdx + itemsetStart cũ (~5× nhỏ hơn):
    //    - flatToItemsetIdx: 4L bytes - không cần (tích hợp ranh giới vào scan)
    //    - itemsetStart:     4(M+1) bytes - không cần (boundary biết qua isItemsetEnd)
    //    - isItemsetEnd:     L bytes (boolean[] mỗi entry 1 byte JVM)
    //
    //  Cách dùng (Pass-2 scan):
    //    for (int q = parentFlatIdx + 1; q < flatLen; q++) {
    //        if (flatItems[q] == itemToFind) { ... I-ext match ... }
    //        if (isItemsetEnd[q]) break;   // crossed itemset boundary → I-ext done
    //    }
    //    // S-ext bắt đầu từ q+1 (sau khi break ở isItemsetEnd[q])
    // ====================================================================
    public int[] flatItems;
    public double[] flatUtilities;
    public boolean[] isItemsetEnd;
    public double[] flatRemainingUtility;
    public int flatLen = 0;

    public Sequence(int id) {
        this.id = id;
    }

    // Tính toán trước Tiện ích còn lại (Suffix Sum) từ phải qua trái
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
     * Build the flat primitive representation. Idempotent: gọi lại sẽ rebuild.
     * Adaptive thuật toán gọi 1 lần per sequence khi nạp vào dbLookup.
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
                // Đánh dấu phần tử cuối của itemset
                if (j == n - 1) isItemsetEnd[pos] = true;
                pos++;
            }
        }

        // Build remaining utility (suffix sum của utilities sau vị trí k).
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

    // Lấy tiện ích còn lại siêu tốc O(1) hoặc tính thủ công O(N) nếu chưa khởi tạo
    public double getRestUtility(int itemsetIdx, int itemIdx) {
        if (!remainingUtilities.isEmpty()) {
            return remainingUtilities.get(itemsetIdx).get(itemIdx);
        }

        // Fallback O(N) cho các thuật toán không gọi calculateRemainingUtilities()
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