package algorithms;

import common.*;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

/**
 * Thuật toán Đối chứng: HUSP-ULL (IEEE Transactions on Cybernetics, 2021)
 * Phiên bản đã được tinh chỉnh để ĐO LƯỜNG THỰC NGHIỆM:
 * - Ghi nhận tổng số candidate sinh ra.
 * - Ghi nhận số lần cắt tỉa bởi SWU (cho 1-item).
 * - Ghi nhận số lần cắt tỉa bởi LAR (Look-Ahead Removing - Subtree level).
 * - Ghi nhận số lần cắt tỉa bởi IIP/PEU (Irrelevant Item Pruning - Item level).
 */
public class HUSPULLAlgorithm {

    // ========================================================================
    // 1. CẤU TRÚC UL-NODE ĐỂ LƯU VẾT
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
    // 2. BIẾN LƯU TRỮ CHỈ SỐ THỰC NGHIỆM (Metrics)
    // ========================================================================
    public double minUtility;
    public int patternCount = 0;

    // Các biến đếm phục vụ Logging theo file .docx
    public long candidateCount = 0;
    public long exploredNodes = 0; // Số mẫu THỰC SỰ khai triển (đệ quy vào) - đo hiệu quả tỉa
    public long prunedSWU = 0;  // Số lượng 1-item bị loại bởi SWU
    public long prunedLAR = 0;  // Cắt tỉa nhánh (Subtree-level)
    public long prunedIIP = 0;  // Cắt tỉa ứng viên (Item-level PEU)

    public Map<Pattern, Double> largeMap = new HashMap<>();
    private Map<Integer, Sequence> seqLookupMap = new HashMap<>();

    public HUSPULLAlgorithm(double absoluteMinUtility) {
        this.minUtility = absoluteMinUtility;
    }

    // ========================================================================
    // 3. LÕI THUẬT TOÁN HUSP-ULL
    // ========================================================================
    public void run(List<Sequence> database) {
        // Reset toàn bộ metrics trước khi chạy batch mới
        largeMap.clear();
        patternCount = 0;
        candidateCount = 0;
        exploredNodes = 0;
        prunedSWU = 0;
        prunedLAR = 0;
        prunedIIP = 0;
        seqLookupMap.clear();

        // 1. Tính toán trước Suffix Sum (Ma trận tiện ích còn lại O(1))
        for (Sequence seq : database) {
            seq.calculateRemainingUtilities();
            seqLookupMap.put(seq.id, seq);
        }

        // 2. Tính SWU (Sequence-Weighted Utility) cho các Item có độ dài 1
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

        // ĐẾM CÔNG BẰNG (Sinh ra là đếm): MỌI 1-item sinh ra, TRƯỚC khi lọc bằng SWU.
        candidateCount += initialSWU.size();

        // 3. Lọc các Item tiềm năng bằng SWU
        List<Integer> promisingItems = new ArrayList<>();
        for (Map.Entry<Integer, Double> entry : initialSWU.entrySet()) {
            if (entry.getValue() >= minUtility) {
                promisingItems.add(entry.getKey());
            } else {
                prunedSWU++; // Bị loại bởi SWU
            }
        }
        Collections.sort(promisingItems); // Sắp xếp theo từ vựng học (Lexicographical)

        // 4. Bắt đầu quá trình đệ quy (Pattern-Growth)
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
        // LỚP PHÒNG THỦ 2: chặn StackOverflow trên dữ liệu chuỗi cực dài.
        // 2000 là safety net (depth thực tế ≤ ~270) → không đổi kết quả.
        if (depth > 2000) return;
        // PHÒNG THỦ ĐO LƯỜNG: dừng NGAY khi runner cancel (timeout) → tránh orphan
        // thread chạy tiếp ăn CPU/giữ heap, NHIỄM thời gian/bộ nhớ của lần đo kế. Chỉ
        // kích hoạt khi đã OT (kết quả bị bỏ) → KHÔNG đổi logic khai phá baseline.
        if (Thread.currentThread().isInterrupted()) return;
        exploredNodes++; // mẫu này được KHAI TRIỂN (đệ quy vào) - đếm TRƯỚC LAR-prune
        // ĐẾM CÔNG BẰNG (Sinh ra là đếm): candidateCount ĐÃ DỜI khỏi entry. Ứng viên I/S-ext
        // được đếm KHI SINH RA (trước lọc PEU/IIP) ở dưới - đồng nhất với thuật toán đề xuất.
        // ==========================================================
        // CẮT TỈA TẦNG 2 (SUBTREE-LEVEL): LOOK-AHEAD REMOVING (LAR)
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

        // Nếu tổng trần không qua ngưỡng, cắt bỏ toàn bộ nhánh này
        if (totalMaxUpperBound < minUtility) {
            prunedLAR++; // Ghi nhận 1 lần cắt nhánh thành công
            return;
        }

        // ==========================================================
        // SINH ỨNG VIÊN (CANDIDATE GENERATION)
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

        // ĐẾM CÔNG BẰNG (Sinh ra là đếm): MỌI ứng viên I/S-ext (gom theo item) sinh ra,
        // TRƯỚC khi lọc bằng PEU/IIP - đồng nhất với thuật toán đề xuất.
        candidateCount += iCandidatesMap.size() + sCandidatesMap.size();

        // ==========================================================
        // CẮT TỈA TẦNG 1 (ITEM-LEVEL): IRRELEVANT ITEM PRUNING (IIP / PEU)
        // Vệ sinh bộ nhớ: dùng Iterator để giải phóng từng entry ngay khi xử lý xong,
        // tránh giữ toàn bộ I/S candidate map trên call-stack trong suốt quá trình đệ quy.
        // ==========================================================

        // Xét nhánh I-Extension
        Iterator<Map.Entry<Integer, List<ULNode>>> itI = iCandidatesMap.entrySet().iterator();
        while (itI.hasNext()) {
            Map.Entry<Integer, List<ULNode>> entry = itI.next();
            List<ULNode> nodes = entry.getValue();

            if (calculatePEU(nodes) >= minUtility) { // Vượt qua kiểm tra PEU
                Pattern newPattern = prefix.iConcatenate(entry.getKey());
                double actualUtility = calculateMaxUtility(nodes);
                if (actualUtility >= minUtility) {
                    savePattern(newPattern, actualUtility);
                }
                huspULLRecursive(newPattern, nodes, depth + 1);
            } else {
                prunedIIP++; // Bị cắt bởi IIP/PEU
            }
            itI.remove(); // Giải phóng tham chiếu nodes ngay sau khi xử lý
        }

        // Xét nhánh S-Extension
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
            System.err.println("Lỗi ghi file HUSP-ULL: " + e.getMessage());
        }
    }
}