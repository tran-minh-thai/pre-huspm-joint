package algorithms;

import common.*;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

/**
 * Thuật toán Đối chứng: USpan (Yin et al., KDD 2012)
 * Đã được tinh chỉnh để ĐO LƯỜNG THỰC NGHIỆM:
 * - Theo dõi candidateCount chính xác tại thời điểm sinh ứng viên mở rộng.
 * - Theo dõi prunedSWU (số lần ứng viên bị cắt bỏ bởi cận SWU cục bộ và toàn cục).
 * - Tích hợp cắt tỉa Width Pruning (SWU) & Depth Pruning (O(1) Suffix Sum).
 */
public class USpanAlgorithm {

    // ========================================================================
    // 1. CẤU TRÚC THEO DÕI TRẠNG THÁI (PROJECTED DATABASE)
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
    // 2. BIẾN LƯU TRỮ CHỈ SỐ THỰC NGHIỆM (Metrics)
    // ========================================================================

    public double minUtility;
    public int patternCount = 0;

    // Các biến đếm phục vụ Logging theo chuẩn thiết kế
    public long candidateCount = 0;
    public long exploredNodes = 0; // Số mẫu THỰC SỰ khai triển (đệ quy vào) - đo hiệu quả tỉa
    public long prunedSWU = 0; // Số lần cắt tỉa bởi Sequence-Weighted Utilization
    public long prunedDepth = 0; // Số lần nhánh bị cắt bởi Depth Pruning (tùy chọn theo dõi)

    // Bộ nhớ lưu trữ các mẫu HUSP đạt chuẩn (phục vụ xuất file)
    public Map<Pattern, Double> largeMap = new HashMap<>();

    // Đếm cảnh báo lệch chỉ mục (Instance ↔ Sequence desync). Cap log để không
    // làm ngập stderr / kéo chậm experiment runner trên dataset lỗi cấu trúc.
    private long boundaryWarnings = 0;
    private static final long MAX_BOUNDARY_WARN = 20;

    public USpanAlgorithm(double absoluteMinUtility) {
        this.minUtility = absoluteMinUtility;
    }

    /**
     * Boundary check: chỉ mục (itemsetIdx, itemIdx) của 1 Instance phải nằm trong
     * cấu trúc thực của seq. Nếu lệch → log cảnh báo (có cap) + trả false để caller
     * BỎ QUA instance đó thay vì văng IndexOutOfBoundsException làm sập runner.
     * KHÔNG thay đổi cơ chế cắt tỉa Width/Depth - chỉ chặn truy xuất mảng ngoài biên.
     */
    private boolean inBounds(Sequence seq, int itemsetIdx, int itemIdx) {
        if (itemsetIdx < 0 || itemsetIdx >= seq.itemsets.size()) {
            if (boundaryWarnings++ < MAX_BOUNDARY_WARN) {
                System.err.println("[USpan][WARN] itemsetIdx=" + itemsetIdx + " ngoài biên (itemsets="
                        + seq.itemsets.size() + ", seqId=" + seq.id + ") - bỏ qua instance");
            }
            return false;
        }
        if (itemIdx < 0 || itemIdx >= seq.itemsets.get(itemsetIdx).items.size()) {
            if (boundaryWarnings++ < MAX_BOUNDARY_WARN) {
                System.err.println("[USpan][WARN] itemIdx=" + itemIdx + " ngoài biên (items="
                        + seq.itemsets.get(itemsetIdx).items.size() + ", seqId=" + seq.id + ") - bỏ qua instance");
            }
            return false;
        }
        return true;
    }

    // ========================================================================
    // 3. LÕI THUẬT TOÁN USPAN
    // ========================================================================

    public void run(List<Sequence> database) {
        // Tối ưu: Chỉ tính lại O(1) Suffix Sum nếu CSDL chưa được tính
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
        // BƯỚC 1: WIDTH PRUNING (Cắt tỉa chiều rộng ở thế hệ 1-item)
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

        // ĐẾM CÔNG BẰNG (Sinh ra là đếm): MỌI 1-item sinh ra, TRƯỚC khi lọc bằng SWU toàn cục.
        candidateCount += initialSWU.size();

        List<Integer> promisingItems = new ArrayList<>();
        for (Map.Entry<Integer, Double> entry : initialSWU.entrySet()) {
            if (entry.getValue() >= minUtility) {
                promisingItems.add(entry.getKey());
            } else {
                prunedSWU++; // Bị loại bởi SWU toàn cục
            }
        }
        Collections.sort(promisingItems); // Sắp xếp theo từ vựng học

        // ==============================================================
        // BƯỚC 2: PHÓNG CHIẾU VÀ ĐỆ QUY (PATTERN-GROWTH)
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

        // Dọn rác khởi tạo
        initialSWU.clear();
        promisingItems.clear();
    }

    private void savePattern(Pattern pattern, double utility) {
        largeMap.put(pattern, utility);
        patternCount = largeMap.size();
    }

    private void uSpanRecursive(Pattern prefix, List<ProjectedSequence> projectedDB, int depth) {
        // LỚP PHÒNG THỦ 2: chặn StackOverflow trên dữ liệu chuỗi cực dài.
        // 2000 là safety net (depth thực tế của các dataset ≤ ~270) → không đổi kết quả.
        if (depth > 2000) return;
        // PHÒNG THỦ ĐO LƯỜNG: dừng NGAY khi runner cancel (timeout) → tránh orphan
        // thread chạy tiếp ăn CPU/giữ heap, NHIỄM thời gian/bộ nhớ của lần đo kế. Chỉ
        // kích hoạt khi đã OT (kết quả bị bỏ) → KHÔNG đổi logic khai phá baseline.
        if (Thread.currentThread().isInterrupted()) return;
        exploredNodes++; // mẫu này được KHAI TRIỂN (đệ quy vào) - đếm TRƯỚC depth-prune (= công khai triển thật)
        // ĐẾM CÔNG BẰNG (Sinh ra là đếm): candidateCount ĐÃ DỜI khỏi entry. Ứng viên mở rộng
        // được đếm KHI SINH RA (trước lọc SWU cục bộ) ở dưới - đồng nhất với thuật toán đề xuất.
        // ==============================================================
        // CHIẾN LƯỢC 1: DEPTH PRUNING (CẮT TỈA CHIỀU SÂU DỰA TRÊN U_REST)
        // ==============================================================
        double maxPossibleUtility = 0;
        for (ProjectedSequence pSeq : projectedDB) {
            double seqMaxUB = 0;
            for (Instance inst : pSeq.instances) {
                // Boundary check trước getRestUtility (remainingUtilities.get(itemsetIdx).get(itemIdx))
                if (!inBounds(pSeq.seq, inst.itemsetIdx, inst.itemIdx)) continue;
                // Tận dụng getRestUtility O(1) từ common.Sequence
                double ub = inst.utility + pSeq.seq.getRestUtility(inst.itemsetIdx, inst.itemIdx);
                if (ub > seqMaxUB) seqMaxUB = ub;
            }
            maxPossibleUtility += seqMaxUB;
        }

        // Khai tử toàn bộ nhánh DFS nếu tổng trần tiềm năng không chạm ngưỡng
        if (maxPossibleUtility < minUtility) {
            prunedDepth++;
            return;
        }

        // ==============================================================
        // CHIẾN LƯỢC 2: WIDTH PRUNING TRÊN PROJECTED DB (TÍNH SWU CỤC BỘ)
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

                // Thu thập ứng viên I-Extension
                for (int i = inst.itemIdx + 1; i < currentItemset.items.size(); i++) {
                    iSeen.add(currentItemset.items.get(i).id);
                }
                // Thu thập ứng viên S-Extension
                for (int i = inst.itemsetIdx + 1; i < pSeq.seq.itemsets.size(); i++) {
                    for (QItem item : pSeq.seq.itemsets.get(i).items) {
                        sSeen.add(item.id);
                    }
                }
            }

            for (int item : iSeen) iListSWU.put(item, iListSWU.getOrDefault(item, 0.0) + seqUtil);
            for (int item : sSeen) sListSWU.put(item, sListSWU.getOrDefault(item, 0.0) + seqUtil);
        }

        // ĐẾM CÔNG BẰNG (Sinh ra là đếm): MỌI ứng viên I/S-ext sinh ra, TRƯỚC khi lọc bằng SWU cục bộ.
        candidateCount += iListSWU.size() + sListSWU.size();

        // Đếm ứng viên và áp dụng SWU cục bộ
        List<Integer> iList = new ArrayList<>();
        for (Map.Entry<Integer, Double> e : iListSWU.entrySet()) {
            if (e.getValue() >= minUtility) {
                iList.add(e.getKey());
            } else {
                prunedSWU++; // Bị loại bởi SWU cục bộ
            }
        }
        Collections.sort(iList);

        List<Integer> sList = new ArrayList<>();
        for (Map.Entry<Integer, Double> e : sListSWU.entrySet()) {
            if (e.getValue() >= minUtility) {
                sList.add(e.getKey());
            } else {
                prunedSWU++; // Bị loại bởi SWU cục bộ
            }
        }
        Collections.sort(sList);

        // ==============================================================
        // TIẾN HÀNH MỞ RỘNG (I-EXTENSION & S-EXTENSION)
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

        // Tối ưu hóa Bộ nhớ
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
            // Khóa Long 64-bit (32 cao = itemsetIdx, 32 thấp = itemIdx) tránh xung đột với chuỗi dài
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
            System.err.println("Lỗi ghi file USpan: " + e.getMessage());
        }
    }
}