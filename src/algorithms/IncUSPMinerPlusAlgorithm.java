package algorithms;

import common.*;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

/**
 * Thuật toán Đối chứng: IncUSP-Miner+ (ACM TIST 2018)
 * Đã sửa đổi TSU Bounding theo ĐÚNG Định nghĩa 16 (2-case condition).
 * - Theo dõi candidateCount (số lượng node ứng viên sinh ra).
 * - Theo dõi prunedTSU (số lần ứng viên bị cắt bỏ bởi cận TSU).
 */
public class IncUSPMinerPlusAlgorithm {

    public double Su;
    public double currentMinUtil;
    public int maxDepth = 1000; // Chỉ là chốt an toàn chống tràn stack - không thay đổi logic baseline

    // ========================================================================
    // BIẾN LƯU TRỮ CHỈ SỐ THỰC NGHIỆM (Metrics)
    // ========================================================================
    public int patternCount = 0;
    public long candidateCount = 0;
    public long exploredNodes = 0; // Số mẫu THỰC SỰ khai triển (đệ quy vào) - đo hiệu quả tỉa
    public long prunedTSU = 0;

    public Map<Pattern, Double> largeMap = new HashMap<>();

    public IncUSPMinerPlusAlgorithm(double Su) {
        this.Su = Su;
    }

    // ========================================================================
    // CẤU TRÚC DỮ LIỆU CỦA INCUSP-MINER+
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

        double exactUtility = 0; // u(alpha, s) - Utility tốt nhất
        double peu = 0;          // PEU(alpha, s)
        double tsu = 0;          // TSU của tracker này (tính theo Định nghĩa 16)

        public Tracker(int id) {
            this.seqId = id;
        }

        public void addInstance(Instance inst, Sequence seq) {
            instances.add(inst);
            // Cập nhật exactUtility
            if (inst.utility > exactUtility) exactUtility = inst.utility;

            // Cập nhật PEU cho prefix này (để dự phòng cho case 2 của TSU)
            double currentPeu = inst.utility + seq.getRestUtility(inst.itemsetIdx, inst.itemIdx);
            if (currentPeu > peu) peu = currentPeu;
        }

        /**
         * CHÍNH XÁC ĐỊNH NGHĨA 16 CỦA BÀI BÁO INCUSP-MINER+
         */
        public void finalizeTSU(Tracker parentTracker, Sequence seq) {
            if (instances.isEmpty()) {
                this.tsu = 0;
                return;
            }

            // Trường hợp 1-item (Định nghĩa 15)
            if (parentTracker == null) {
                Instance firstInst = instances.get(0);
                this.tsu = firstInst.utility + seq.getRestUtility(firstInst.itemsetIdx, firstInst.itemIdx);
            }
            // Trường hợp l-sequence (Định nghĩa 16: Phân nhánh 2 case)
            else {
                Instance parentFirstInst = parentTracker.instances.get(0);

                // Phép thử: Vị trí đầu tiên của prefix CÓ PHẢI là vị trí mang lại utility lớn nhất không?
                // epsilon 1e-6 để tránh sai số dấu phẩy động trong Java
                if (Math.abs(parentTracker.exactUtility - parentFirstInst.utility) < 1e-6) {
                    // Case 1: TSU = u(alpha, a_p1, s) + u(i, p1, s) + ru(i, p1, s)
                    // (Lưu ý: instances.get(0).utility chính là tổng u(alpha, a_p1) + u(i, p1))
                    Instance firstInst = instances.get(0);
                    this.tsu = firstInst.utility + seq.getRestUtility(firstInst.itemsetIdx, firstInst.itemIdx);
                } else {
                    // Case 2: Trở về dùng PEU của parent
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
     * Giải phóng NGAY cấu trúc nặng (CP-tree + globalDB) khi thuật toán không thể tiếp
     * (OOM/poison ở pha gia tăng). CP-tree IncUSP tích luỹ qua các lô → có thể chiếm
     * nhiều GB; nếu không thả ngay, các cấp phát main-thread kế tiếp (log/snapshot) sẽ
     * tiếp tục OOM. Gọi xong nên forceFullGC ở caller để thu hồi heap.
     */
    public void releaseState() {
        root = null;
        if (globalDB != null) globalDB.clear();
        if (largeMap != null) largeMap.clear();
    }

    // ========================================================================
    // LÕI THUẬT TOÁN (KHỞI TẠO VÀ GIA TĂNG)
    // ========================================================================
    public void runInitialMining(List<Sequence> dInit, double currentTotalUtility) {
        // Reset state TRƯỚC khi bắt đầu để 1-item candidate được đếm đầy đủ.
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

        // ĐẾM CÔNG BẰNG (Sinh ra là đếm): MỌI 1-item sinh ra, TRƯỚC khi lọc bằng TSU.
        candidateCount += l1Trackers.size();

        // Chốt TSU cho các phần tử 1-item
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
                prunedTSU++; // Loại 1-item ngay, không lưu vào CPTree
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

        // ĐẾM CÔNG BẰNG (Sinh ra là đếm): MỌI 1-item lô gia tăng sinh ra, TRƯỚC khi lọc bằng TSU.
        candidateCount += deltaL1Trackers.size();

        // Chốt TSU cho 1-item trong deltaD
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
    // HÀM ĐỆ QUY (MỞ RỘNG VÀ CẮT TỈA TSU)
    // ========================================================================
    private void expandNode(CPTreeNode node, List<Sequence> db) {
        expandNode(node, db, 1);
    }

    private void expandNode(CPTreeNode node, List<Sequence> db, int depth) {
        if (depth > maxDepth) return;
        // PHÒNG THỦ ĐO LƯỜNG: dừng NGAY khi runner cancel (timeout) → tránh orphan
        // thread chạy tiếp ăn CPU/giữ heap, NHIỄM thời gian/bộ nhớ của lần đo kế. Chỉ
        // kích hoạt khi đã OT (kết quả bị bỏ) → KHÔNG đổi logic khai phá baseline.
        if (Thread.currentThread().isInterrupted()) return;
        exploredNodes++; // mẫu này được KHAI TRIỂN (đệ quy vào) - đếm TRƯỚC node-prune TSU
        // ĐẾM CÔNG BẰNG (Sinh ra là đếm): candidateCount ĐÃ DỜI khỏi entry. Ứng viên I/S-ext
        // được đếm KHI SINH RA (trước lọc TSU) ở dưới - đồng nhất với thuật toán đề xuất.

        double exactUtil = 0, tsuAccumulated = 0;
        for (Tracker t : node.trackerList.trackers.values()) {
            exactUtil += t.exactUtility;
            tsuAccumulated += t.tsu;
        }
        node.exactUtilityD = exactUtil;
        node.tsuD = tsuAccumulated;

        // Safety net: tsu phải đã pass khi node được thêm vào CPTree.
        // Giữ check ở đây để bảo vệ root + 1-item entry.
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

        // ĐẾM CÔNG BẰNG (Sinh ra là đếm): MỌI ứng viên I/S-ext sinh ra, TRƯỚC khi lọc bằng TSU.
        candidateCount += iExtensions.size() + sExtensions.size();

        // Chốt TSU cho I-Ext, kiểm tra TSU TRƯỚC khi lưu vào CPTree.
        // Theo paper IncUSP-Miner+ 2018: CP-tree CHỈ lưu candidate có TSU >= min_util.
        // Trước đây code lưu cả TSU-pruned children → CPTree phình to gây OOM.
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
                itI.remove(); // KHÔNG lưu vào CPTree - TrackerList được GC ngay
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
        exploredNodes++; // mẫu này được KHAI TRIỂN ở pha gia tăng - đếm TRƯỚC node-prune TSU

        // Merge Tracker từ delta vào Node hiện tại
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

        // ĐẾM CÔNG BẰNG (Sinh ra là đếm): MỌI ứng viên I/S-ext lô gia tăng sinh ra, TRƯỚC khi chốt/lọc TSU.
        candidateCount += deltaIExts.size() + deltaSExts.size();

        // Chốt TSU cho các extension của lô gia tăng
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
            System.err.println("Lỗi ghi file IncUSP-MinerPlus: " + e.getMessage());
        }
    }
}