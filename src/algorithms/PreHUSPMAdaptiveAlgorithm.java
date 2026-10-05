package algorithms;

import common.*;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

/**
 * Thuật toán Đề xuất: Pre-HUSPM-Adaptive
 * Tích hợp 5 đóng góp lõi:
 * C1. Áp cận giá trị PEU gộp TRƯỚC khi materialize (single-pass survey→filter→materialize)
 * C2. Ngưỡng dưới thích ứng động (LUT_dyn)
 * C3. Cận trên cục bộ chặt IUB
 * C4. Chỉ mục ngược cấp độ khối (Block-level Inverted Index)
 * C5. Bộ đệm lợi ích ròng tích lũy (CUB)
 */
public class PreHUSPMAdaptiveAlgorithm {

    /**
     * Lightweight signal cho cooperative cancellation từ runner (timeout 60 phút).
     * Không fill stack trace → throw cực nhẹ (~10 ns).
     */
    public static final class CancellationSignal extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public CancellationSignal() { super("Algorithm cancelled by timeout", null, false, false); }
    }

    /**
     * Safety net: số mẫu (pre)large vượt ngân sách bộ nhớ → dừng êm thay vì OOM cứng
     * 24GB. Runner bắt Throwable → ghi FAILED. Tương tự cách baseline bị đánh dấu OOM/DNF
     * khi vượt giới hạn. Throw nhẹ (không stack trace).
     */
    public static final class PatternBudgetExceeded extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public PatternBudgetExceeded(long n) {
            super("Pattern budget exceeded (" + n + " patterns) - threshold quá thấp cho dataset này", null, false, false);
        }
    }


    // ========================================================================
    // ĐÓNG GÓP C4: CHỈ MỤC NGƯỢC CẤP ĐỘ KHỐI (BLOCK-INDEX)
    // ========================================================================
    static class BlockSummary {
        int blockId, startSeqId, endSeqId;
        double minSeqUtility = Double.MAX_VALUE;
        double maxSeqUtility = Double.MIN_VALUE;
        // No per-block item set. One used to be kept here, a BitSet over every item id the
        // block holds, and nothing read it: itemBlockBitmap below answers "which blocks hold
        // item i" on its own. Each such set is as long as the largest item id, so on
        // C8T1S5I8N5K it held 407 MB after the last increment for no use.

        public BlockSummary(int id, int start, int end) {
            this.blockId = id; this.startSeqId = start; this.endSeqId = end;
        }

        public void addSequenceInfo(Sequence seq) {
            if (seq.sequenceUtility < minSeqUtility) minSeqUtility = seq.sequenceUtility;
            if (seq.sequenceUtility > maxSeqUtility) maxSeqUtility = seq.sequenceUtility;
        }
    }

    /**
     * BLOCK-INDEX (C4) với BitMask Inverted Index.
     *
     * Thay {@code Map<Integer, List<Integer>>} (boxing tràn lan + ArrayList<Integer>
     * cho mỗi item) bằng {@code BitSet[] itemBlockBitmap}:
     *  - itemBlockBitmap[itemId].get(blockId) == true  ↔ item xuất hiện trong block đó.
     *  - Lookup O(1) qua bit-test (vs O(log M) binary search cũ).
     *  - Tiết kiệm RAM ~10×: N_items × M_blocks bits thay vì N_items × M_blocks ×
     *    24 bytes/Integer + ArrayList overhead.
     *  - Hỗ trợ stream tốt: append block mới chỉ là set bit ở vị trí cuối → O(1).
     *
     * seqIdToBlock vẫn là int[] (đã primitive, tăng trưởng power-of-two).
     */
    static class BlockIndex {
        int blockSize = 100;
        List<BlockSummary> blocks = new ArrayList<>();

        // BitMask inverted index: itemBlockBitmap[itemId] = BitSet of block ids containing it.
        BitSet[] itemBlockBitmap = new BitSet[256];
        int maxItemSeen = -1;

        // C4-OPT: Mảng seqId -> blockId cho tra cứu O(1). Tự động mở rộng theo nhu cầu.
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

        /** Tra cứu blockId của 1 sequence trong O(1). */
        public int blockOf(int seqId) {
            if (seqId < 0 || seqId >= seqIdToBlock.length) return -1;
            return seqIdToBlock[seqId];
        }

        /** BitMask của các block chứa item. null nếu item chưa từng xuất hiện. */
        public BitSet bitsetFor(int itemId) {
            if (itemId < 0 || itemId >= itemBlockBitmap.length) return null;
            return itemBlockBitmap[itemId];
        }
    }

    // ========================================================================
    // HỆ THỐNG TRACKER (ĐÓNG GÓP C3 - IUB)
    //
    // PRIMITIVE ARRAYS cho mỗi Tracker: 3 mảng nguyên thuỷ song song
    //   int[] itemsetIdx, int[] itemIdx, double[] utility
    // Tiết kiệm ~50% bộ nhớ so với List<Instance> (mỗi Instance ~24B header +
    // padding) + cache-friendly khi duyệt tuần tự instances. Doubling growth
    // capacity với amortized O(1) append; trimToSize() có điều kiện để gọt
    // mảng thừa trước khi đẩy xuống recursion sâu hơn.
    // ========================================================================

    /**
     * COMPACT PATTERN - int[] flat representation thay {@code List<List<Integer>>}.
     *
     * Pattern <code>&lt;{a,b},{c}&gt;</code> → {@code int[] = {a, b, -1, c}}.
     * Pattern <code>&lt;{a}&gt;</code>       → {@code int[] = {a}}.
     *
     * KÍCH THƯỚC: ~3-5× nhỏ hơn Pattern object.
     *  - Pattern object: ~16B header + List<List<Integer>> ~200B (nested ArrayList + autoboxed Integer)
     *  - CompactPattern: 16B header + int[] (4B/item) + 4B cachedHash ≈ 60B cho pattern 5 items
     *
     * HASH O(1): cachedHash tính 1 lần ở constructor → put/get trên Map cực nhanh.
     * EQUALS: Arrays.equals trên int[] cache-friendly, không recursive list comparison.
     *
     * MỤC ĐÍCH: dùng làm KEY trong largeMap/prelargeMap/results. Tại low-Su dense
     * data, số patterns qualifying có thể hàng triệu → tiết kiệm 3-5× = 10-20 GB.
     * Conversion sang Pattern chỉ khi cần (export, evaluate trong incremental).
     */
    static final class CompactPattern {
        final int[] flat;
        final int cachedHash;

        CompactPattern(int[] flat) {
            this.flat = flat;
            this.cachedHash = Arrays.hashCode(flat);
        }

        /** First item (flat[0] luôn là item, không bao giờ là -1 separator). */
        int firstItem() { return flat[0]; }

        /** Build từ Pattern đang ở trạng thái mutable trong expandNodeWithPruning. */
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

        /** Convert ngược về Pattern (dùng cho export/evaluate path, không hot). */
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

        // (Export đi qua toPattern().toIntuitiveString() trực tiếp - xem exportLargePatterns.
        //  Không cần toIntuitiveString() riêng trên CompactPattern.)

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
     * Tracker - 2 mảng primitive duy nhất:
     *   int[] flatIndices  - vị trí phẳng (flat) trong sequence
     *   double[] utility   - utility tích lũy của pattern tại vị trí đó
     *
     * Trước đây cần 2 mảng int (itemsetIdx + itemIdx) - nay gộp thành 1 (flatIdx).
     * Tiết kiệm 50% bộ nhớ int của Tracker. Mọi truy vấn rest-utility, itemsetIdx
     * đều đi qua Sequence.flatToItemsetIdx / itemsetStart / flatRemainingUtility
     * với chi phí O(1) lookup.
     *
     * Hỗ trợ Object Pool: reset() KHÔNG drop arrays, chỉ zero hoá scalar state →
     * lần borrow kế sử dụng lại arrays đã cấp phát → Zero-Allocation steady-state.
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

        /** Reset cho pool reuse - GIỮ NGUYÊN arrays nội bộ (zero-alloc). */
        public void reset(int newSeqId) {
            this.seqId = newSeqId;
            this.size = 0;
            this.exactUtility = 0;
            this.peu = 0;
        }

        /**
         * Direct append - KHÔNG dedup. Dùng sau scratch buffer merge (caller đảm bảo
         * flatIdx unique). Cách nhanh nhất, không cần backward scan O(L).
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

        // (REMOVED) addOrUpdateInstance - backward scan O(L) đã bị loại bỏ theo
        // yêu cầu toiuucode.txt. Mọi caller giờ dùng scratch buffer merge + append().

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
     * Sequential list of Tracker đối với 1 pattern. ArrayList giữ thứ tự seqId
     * tăng dần (invariant: caller iterate parentTrackers theo seq.id tăng dần).
     *
     * Pool-aware: KHÔNG có wrapper addInstance bên trong - caller dùng pool helper
     * borrowTracker/releaseTracker trên class PreHUSPMAdaptiveAlgo để quản lý.
     */
    static class TrackerList {
        final ArrayList<Tracker> trackers = new ArrayList<>();

        public boolean isEmpty() { return trackers.isEmpty(); }
        public int size() { return trackers.size(); }

        /**
         * Gọt primitive arrays trong các Tracker có waste lớn (>=4 slot và >=25%).
         * Có điều kiện để tránh Arrays.copyOf storm trên trackers nhỏ.
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
    // LÕI THUẬT TOÁN ĐỀ XUẤT (C1, C2, C5)
    // ========================================================================
    public static class PreHUSPMAdaptiveAlgo {
        public enum AblationMode { FULL, VARIANCE_ONLY, RHO_ONLY }

        // WP2.5 - ky hieu THONG NHAT voi ban thao: kappa (bai) == alpha (code cu).
        // Truong `alpha` cu da doi ten thanh `kappa`; khong con alias de tranh drift.
        public double Su, kappa, delta0, Sl_min;
        AblationMode mode;
        boolean writeToFile;
        public int maxDepth = 1000;
        /** Ablation flag (C4): nếu false, rescan tuần tự toàn bộ thay vì block-selective. */
        public boolean useBlockIndex = true;
        /**
         * Ngưỡng kích hoạt "xả hơi" results (Lớp phòng thủ 3). Khi results đạt mức này,
         * flushWeakestResults() bỏ 50% mẫu utility thấp nhất để chặn RAM ~1.5GB thay vì
         * OOM 24GB. Mặc định 1.5M (~1-3GB cho CompactPattern). Đặt Long.MAX_VALUE để tắt.
         */
        public long maxResultPatterns = 1_500_000L;

        /**
         * NGÂN SÁCH ỨNG VIÊN CPU - lưới an toàn (tuỳ chọn). TẮT MẶC ĐỊNH (Long.MAX_VALUE).
         *
         * LÝ DO TẮT: (1) C5 đã sửa hẳn nổ-rescan SC2 → mọi mine HỢP LỆ đều KẾT THÚC trong
         * timeout (BMS1 SC1 Su=0.013: 1.81 TỶ candidate nhưng chỉ 27s; FIFA 0.045: 318M/297s).
         * (2) Sau Nhiệm vụ 1 ("Sinh ra là đếm"), candidateCount đếm GENERATED (trước BoundEUCS)
         * → trần hợp lệ lên tới HÀNG TỶ → KHÔNG còn ngưỡng hữu hạn nào an toàn (mọi giá trị đều
         * có nguy cơ cắt OAN run thật - thảm hoạ cho báo cáo). (3) Chặn đúng đắn cho runaway =
         * 60' timeout + cooperative-cancellation (đã hoạt động cho MỌI thuật toán). Ablation
         * tắt-pruning (SC7) sẽ DNF tại 60' (chậm nhưng đúng, và DNF là kết quả mong đợi).
         *
         * Đặt giá trị hữu hạn CHỈ khi muốn ablation DNF nhanh hơn 60' VÀ chấp nhận rủi ro cắt
         * oan - cần > trần hợp lệ (≥ vài tỷ). Breaker CHỈ có ở Adaptive (baseline không ảnh hưởng).
         */
        public long maxCandidatesBudget = Long.MAX_VALUE;

        // ---- Thich nghi theo CONG, khong theo loi ich (tuy chon, mac dinh TAT) ----
        //
        // Moi tin hieu dieu khien khac o day la mot dai luong LOI ICH: cubValue, swuMax,
        // iauMax. Chung tra loi "da co bao nhieu loi ich di vao", dung cho tinh dung dan.
        // Khong cai nao tra loi "luot khai thac toi se ton bao nhieu cong", ma do moi la
        // thu no ra. Do duoc tren tam bo: BMS1 co loi ich nho nhat va chuoi ngan nhat, ma
        // mot lan rescan cua no vuot ba ty ung vien.
        //
        // Hai co che duoi day dong khoang trong do. Ca hai deu KHONG dung toi luat kich
        // hoat rescan lan can duoi LUT_dyn, nen khong no nghia vu chung minh nao: nang san
        // Sl_min chi thu hep dai tien-lon, va Theorem 3-4 dung voi moi ti le trong (0,1).
        //
        // Mac dinh tat de moi ket qua cu van tai lap duoc; bat bang co de do duoc ca hai
        // chieu, dung kieu cac ablation khac trong lop nay.

        /** Ti le san da cau hinh, giu nguyen de biet da lech bao nhieu. */
        public double slMinRatioCfg;
        /** Ti le san DANG dung. Chi tang, khong bao gio giam. */
        public double slMinRatioEff;
        /** Do lap cua CSDL: so lan xuat hien tren mot item phan biet. 0 khi chua do. */
        public double repetition = 0;
        /** So lan phai nang san vi mot luot rescan vuot tran ung vien. */
        public int rescanRetries = 0;
        /**
         * iauMax DUNG TAI THOI DIEM quyet dinh rescan, chup truoc khi resetIau() xoa no.
         *
         * Doc thang adp.iauMax sau mot lo co rescan luon cho 0: khoi rescan goi resetIau()
         * ngay sau phep so sanh, nen dai luong quyet dinh bien mat truoc khi ai kip ghi lai.
         * Cot iau_max vi the da nam trong CSV tu lau ma moi hang deu la 0.0, con sft_val -
         * ve KIA cua phep so sanh - thi co ghi. Quyet dinh rescan do do khong hau kiem duoc.
         */
        public double iauMaxAtDecision = 0;

        /** Tang 1: chon san khoi dau theo do lap. -Dadapt.densityFloor=true */
        public boolean useDensityFloor = Boolean.getBoolean("adapt.densityFloor");
        /**
         * Tang 2: tran ung vien cho MOT luot khai thac khi rescan. 0 = tat.
         * Vuot tran thi nang san roi thu lai, thay vi chay den khi het bo nho hoac het gio.
         * -Dadapt.rescanBudget=3000000000
         */
        public long rescanCandidateBudget = Long.getLong("adapt.rescanBudget", 0L);

        /**
         * Luoi an toan DONG HO cho mot luot khai thac. 0 = tat.
         *
         * Tran dem la gioi han chinh vi no tat dinh: cung mot luot bi cat o cung cho tren
         * moi may. Nhung no chi bat duoc chi phi ti le voi SO ung vien. Do tren BMS1 INC#4:
         * 798 trieu ung vien trong hon 35 phut, tuc 0,38 trieu moi giay, trong khi INC#3 cua
         * chinh no chay 28,2 trieu moi giay - cham hon 74 lan tren MOI ung vien. Nut that o
         * do khong phai sinh ung vien, nen mot tran tinh bang ung vien khong the bat duoc.
         *
         * ThresholdScan da ghi san bai hoc nay trong chu thich cua no; co che tran dem duoc
         * sao chep sang day ma bo mat luoi an toan di kem.
         *
         * -Dadapt.rescanMillis=600000
         */
        public long rescanMillisBudget = Long.getLong("adapt.rescanMillis", 0L);
        /** Han chot tuyet doi cua luot khai thac hien tai; 0 = khong co. */
        private long mineDeadlineNanos = 0;
        /** Dem thua de khong goi nanoTime() tren moi ung vien. */
        private int deadlineTick = 0;

        private static final double SL_RATIO_CAP = 0.98;   // tren nua thi dai tien-lon coi nhu khong con
        private static final double SL_RATIO_STEP = 0.05;
        private static final int    SL_MAX_RETRIES = 6;

        /**
         * Nhánh AN TOÀN (không rescan) có mine LẠI mỗi lô (mineBatchWithIUB trên deltaD)
         * hay KHÔNG. Mặc định FALSE = kiến trúc Pre-HUSPM đúng chuẩn pre-large:
         *   - false (MẶC ĐỊNH): nhánh an toàn CHỈ cập nhật mẫu đã-theo-dõi; khám phá mẫu
         *     mới dành cho rescan (kích hoạt bởi cận an toàn swuMax > F). → nhanh, KHÔNG
         *     nổ trên lô bệnh lý (BMS1), số mẫu ≥ Pre-HUSPM.
         *   - true : khám phá mẫu mới NGAY mỗi lô (mine deltaD) - chậm hơn & có thể nổ
         *     trên lô chứa chuỗi cực dày. Chỉ để thử nghiệm/so sánh.
         */
        public boolean mineNewPatternsPerBatch = false;

        /**
         * Bật/tắt NGƯỠNG PRE-LARGE THÍCH ỨNG (C2). Dùng cho ablation/so sánh:
         *   - true  (MẶC ĐỊNH): band [lutDynValue, uut) co/giãn theo δ_D (C2 active).
         *   - false: band CỐ ĐỊNH = [lut_min, uut) (Sl_min cố định, kiểu Pre-HUSPM) →
         *     dùng để minh hoạ dilemma (sl thấp → bùng nổ pre-large; sl cao → thiếu mẫu).
         */
        public boolean useAdaptiveBand = true;

        /** Ablation C3: nếu false → BỎ cận trên cục bộ IUB (không cắt nhánh theo IUB). */
        public boolean useIub = true;
        /** Ablation C5: nếu false → BỎ quyết định rescan thích ứng → rescan MỖI LÔ (naive). */
        public boolean useAdaptiveRescan = true;

        /**
         * true (mac dinh): thao tac sua tieu ngan sach bang u(old) + u(new) - can tren dung.
         * false: tai hien hanh vi cua ban nop (chi tieu |u(new) - u(old)|), KHONG sound.
         */
        public boolean strictModificationBudget =
                !"false".equalsIgnoreCase(System.getProperty("adapt.strictModify", "true"));

        /**
         * WP2.1 - B_sz, kich thuoc khoi cua chi muc nguoc cap do khoi (C4).
         * Truoc day la hang so cung 100 trong BlockIndex. Ghi de bang -Dblock.size=N.
         * Gia tri dang dung duoc xuat ra cot CSV block_size.
         */
        public int blockSize = Integer.getInteger("block.size", 100);

        /**
         * Xả 50% mẫu YẾU NHẤT (utility thấp) khỏi map. Bỏ theo SỐ LƯỢNG (n/2) nên luôn
         * giảm đúng một nửa bất kể phân bố utility. Giữ nguyên các mẫu utility cao
         * (gồm toàn bộ mẫu Lớn ≥ uut) → an toàn cho output cuối.
         */
        private void flushWeakestResults(Map<CompactPattern, Double> map) {
            int n = map.size();
            if (n == 0) return;
            ArrayList<Map.Entry<CompactPattern, Double>> entries = new ArrayList<>(map.entrySet());
            entries.sort(Map.Entry.comparingByValue()); // tăng dần theo utility
            int removeCount = n / 2;
            for (int i = 0; i < removeCount; i++) map.remove(entries.get(i).getKey());
            entries.clear();
            System.gc(); // ép GC dọn hàng trăm MB rác vừa thải
        }

        // --- COOPERATIVE CANCELLATION (cho timeout 60 phút trong runner) ---
        // volatile để runner thread set, recursion thread đọc thấy ngay.
        // Check tại entry expandNodeWithPruning → exit nhanh trong vài µs sau cancel().
        private volatile boolean cancelled = false;
        public void cancel() { this.cancelled = true; }
        public boolean isCancelled() { return cancelled; }

        // --- CÁC CHỈ SỐ THỰC NGHIỆM ĐỂ XUẤT RA CSV ---
        public int rescanCount = 0, patternCount = 0;
        public long candidateCount = 0, blocksReadCount = 0, numBlocksSkipped = 0;
        public long exploredNodes = 0; // Số mẫu THỰC SỰ khai triển (đệ quy) - cận PEU áp TRƯỚC materialize nên ≪ candidate_count
        // prunedValueBound = số item bị cận giá trị PEU loại (C1 - cơ chế tỉa chính).
        public long prunedValueBound = 0, prunedIub = 0;

        // --- CHỈ SỐ C5 (CUB) VÀ C2 (ADAPTIVE) ---
        public double lutDynValue, deltaDValue, sigmaDSquared, rhoD;
        public double cubValue = 0, sftValue = 0;

        // ====================================================================
        // WP1 - LUẬT KÍCH HOẠT RESCAN CHO PHA CHÈN
        // ====================================================================
        /**
         * Ba luật kích hoạt rescan cho pha CHÈN, chọn được bằng cờ để phục vụ
         * kịch bản trục kích thước lô (WP4.1).
         *
         * SWUMAX  Luật của bản nộp (Pre-HUSPM gốc, Eq. 9): rescan ⇔ max_{s∈d} u(s) > SFT.
         *         KHÔNG SOUND. u(s) là utility của MỘT chuỗi, trong khi độ tăng utility của
         *         một mẫu là TỔNG đóng góp của mọi chuỗi chèn chứa nó. Phản ví dụ của
         *         Reviewer #1: UUT=0.25, LUT=0.20, TSU=100, u(X)=19, chèn hai chuỗi cùng
         *         chứa X với u=6 ⇒ SWUmax=6 ≤ 6.67 nên không rescan, nhưng sau cập nhật
         *         u(X)=31 > 0.25·112=28, tức X đã thành HUSP mà bị bỏ sót.
         *         Giữ lại CHỈ để đối chiếu lịch sử; không dùng cho kết quả chính.
         *
         * CUB     Luật tích luỹ thô: rescan ⇔ Σ_{s∈d chèn từ rescan gần nhất} u(s) > SFT.
         *         Sound, nhưng lỏng vì giả định toàn bộ utility chèn dồn được vào một mẫu.
         *
         * IAUMAX  Luật đề xuất (mặc định): rescan ⇔ max_i A_i > SFT, với
         *         A_i = Σ_{s chèn từ rescan gần nhất, i ∈ s} u(s).
         *         Sound và luôn chặt hơn hoặc bằng CUB. Cơ sở: nếu X ⊑ s thì mọi item của
         *         X đều xuất hiện trong s, nên độ tăng utility của X bị chặn bởi
         *         min_{i∈X} A_i ≤ max_i A_i. Suy biến về CUB khi tồn tại một item xuất hiện
         *         trong mọi chuỗi chèn (quan sát được trên KOSARAK).
         */
        public enum TriggerRule { SWUMAX, CUB, IAUMAX }

        /** Luật kích hoạt cho pha chèn. Ghi đè bằng -Dadapt.trigger=swumax|cub|iaumax. */
        public TriggerRule triggerRule = parseTriggerRule(
                System.getProperty("adapt.trigger", "iaumax"));

        private static TriggerRule parseTriggerRule(String s) {
            switch (s.trim().toLowerCase(java.util.Locale.ROOT)) {
                case "swumax": return TriggerRule.SWUMAX;
                case "cub":    return TriggerRule.CUB;
                case "iaumax": return TriggerRule.IAUMAX;
                default: throw new IllegalArgumentException(
                        "adapt.trigger phải là swumax|cub|iaumax, nhận được: " + s);
            }
        }

        /**
         * A_i - bộ đếm utility chèn tích luỹ THEO TỪNG ITEM kể từ lần rescan gần nhất.
         * Chỉ mục theo itemId; tăng trưởng theo nhu cầu. Cập nhật một lượt khi nạp lô,
         * chi phí O(Σ_{s∈d}|s|), không đổi độ phức tạp tiệm cận.
         */
        private double[] iau = new double[256];
        /** max_i A_i, duy trì tăng dần để khỏi quét lại mảng. */
        public double iauMax = 0;
        /** Tỷ số CUB/iauMax của lô gần nhất - hệ số nới rộng kích thước lô (xuất CSV). */
        public double iauTightness = 1.0;
        // Mốc TSU tại lần rescan gần nhất (hoặc init) - dùng tính cận an toàn pre-large
        // F = (Su - Sl_min)/(1 - Su) · tsuAtLastRescan cho QUYẾT ĐỊNH rescan kiểu Pre-HUSPM.
        public double tsuAtLastRescan = 0;
        public boolean cubTriggeredRescan = false;

        // --- Pre-large & ngưỡng HUSP tuyệt đối (xuất CSV) ---
        public int prelargeCount = 0;   // |PL| = prelargeMap.size()
        public double uutValue = 0;     // uut_abs = TSU * Su

        BlockIndex globalBlockIndex = new BlockIndex();
        // === COMPACT STORAGE cho result maps ===
        // Sử dụng CompactPattern (int[] flat + cached hash) thay Pattern (List<List<Integer>>)
        // → tiết kiệm 3-5× memory khi pattern count lớn (low Su dense data).
        // Conversion sang Pattern chỉ khi exportLargePatterns hoặc incremental update.
        public Map<CompactPattern, Double> largeMap = new HashMap<>();

        /**
         * WP5 - anh chup TAP MAU lon duoi dang chuoi, de bo kiem chung so sanh tap mau
         * va utility tung mau voi remine-from-scratch. Ban nop chi so sanh SO LUONG HUSP,
         * khong du de chung minh Proposition 2.
         */
        public java.util.Map<String, Double> exportLargeAsStrings() {
            java.util.Map<String, Double> out = new java.util.HashMap<>(largeMap.size() * 2);
            for (Map.Entry<CompactPattern, Double> e : largeMap.entrySet()) {
                out.put(e.getKey().toPattern().toIntuitiveString(), e.getValue());
            }
            return out;
        }

        /** Anh chup tap TIEN LON, phuc vu chan doan khi tap lon lech nhau. */
        public java.util.Map<String, Double> exportPrelargeAsStrings() {
            java.util.Map<String, Double> out = new java.util.HashMap<>(prelargeMap.size() * 2);
            for (Map.Entry<CompactPattern, Double> e : prelargeMap.entrySet()) {
                out.put(e.getKey().toPattern().toIntuitiveString(), e.getValue());
            }
            return out;
        }
        Map<CompactPattern, Double> prelargeMap = new HashMap<>();
        // dbLookup được truy cập trong MỌI tracker iteration của recursion.
        // seqId trong DatasetLoader luôn sequential [0..N) → dùng Sequence[] direct
        // (1 array access, không hash compute, không open-addressing probing).
        // Sparse seqIds (gap lớn) sẽ chỉ tốn slot null - chấp nhận được cho stream.
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

        // Tối ưu: max itemId trong DB hiện tại → dùng cho mảng nguyên thuỷ thay HashMap
        private int maxItemId = -1;
        // Mảng scratch tái sử dụng trong Pass-1 (tránh allocate HashMap per tracker)
        private transient double[] scratchLocalI, scratchLocalS, scratchAggI, scratchAggS;
        private transient int[] scratchUsedI, scratchUsedS;
        private transient int scratchUsedICount, scratchUsedSCount;
        // Cờ "seen" thay vì BitSet để tránh allocate per-tracker
        private transient boolean[] scratchLocalSeenI, scratchLocalSeenS;
        private transient boolean[] scratchAggSeenI, scratchAggSeenS;
        private transient int[] scratchLocalUsedI, scratchLocalUsedS;
        private transient int scratchLocalUsedICount, scratchLocalUsedSCount;
        // Hoisted survivingI/S - hoist class-field để tránh allocate boolean[] mỗi call.
        // Reset selective qua scratchUsedI/S nên O(touched) thay vì O(maxItemId).
        private transient boolean[] scratchSurvivingI, scratchSurvivingS;

        // ====================================================================
        //  Survivor pool (depth-local snapshot list)
        //  Vì scratchUsedI/S + scratchSurvivingI/S là class field, sẽ bị overwrite
        //  bởi recursion → phải snapshot danh sách surviving items vào mảng
        //  depth-local TRƯỚC khi enter Pass-2 loop.
        // ====================================================================
        private transient List<int[]> iSurvivorPool = new ArrayList<>();
        private transient List<int[]> sSurvivorPool = new ArrayList<>();

        /** Mượn int[] survivor list theo depth (cấp lại nếu chưa đủ size). */
        private int[] borrowSurvivorArray(List<int[]> pool, int depth, int sz) {
            while (pool.size() <= depth) pool.add(null);
            int[] arr = pool.get(depth);
            if (arr == null || arr.length < sz) {
                arr = new int[sz];
                pool.set(depth, arr);
            }
            return arr;
        }

        // ===== SINGLE-PASS PASS-2 (mượn ưu điểm baseline: gom con 1 LƯỢT quét) =====
        // iSlot/sSlot[itemId] = TrackerList con (cấp trong allocateScratch). Dùng tạm
        // trong 1 node rồi snapshot ra mảng depth-local + giải phóng slot trước đệ quy.
        private transient TrackerList[] iSlot, sSlot;
        private transient long[] spSurvGenI, spSurvGenS; // gen marker (single-pass survivor membership)
        private long spNodeGen = 0;
        private transient List<TrackerList[]> iTLPool = new ArrayList<>();
        private transient List<TrackerList[]> sTLPool = new ArrayList<>();
        private TrackerList[] borrowTLArray(List<TrackerList[]> pool, int depth, int sz) {
            while (pool.size() <= depth) pool.add(null);
            TrackerList[] arr = pool.get(depth);
            if (arr == null || arr.length < sz) { arr = new TrackerList[sz]; pool.set(depth, arr); }
            return arr;
        }
        // Ngưỡng chọn chiến lược materialize: node "hẹp" (ptSize nhỏ - đa số node) →
        // single-pass (nhanh như baseline); node "rộng" (nông, dày, @ Su rất thấp) →
        // fallback LAZY (RAM tạm O(depth) - giữ điểm bán hàng "không OOM data dày").
        private static final int SINGLE_PASS_PT_CAP = Integer.getInteger("adapt.singlePassCap", 100_000);

        // ====================================================================
        //  OBJECT POOL - TrackerPool + TrackerListPool (Zero-Allocation)
        //
        //  Triển khai theo yêu cầu toiuucode.txt: mảng tĩnh + int top pointer.
        //  Đơn giản hơn ArrayDeque, không có Iterator allocation, cache-friendly.
        //
        //  WARMUP: pre-allocate N Trackers + M TrackerLists trong INIT phase.
        //  Sau khi warmup, recursion CHỈ borrow/release → ZERO `new Tracker()`
        //  trong vòng đệ quy (yêu cầu cứng từ user).
        //
        //  Pool đầy on release → drop (let GC reclaim). Pool rỗng on borrow → đáng
        //  lý không xảy ra sau warmup; cold fallback gọi `new` để không crash.
        // ====================================================================
        private static final int POOL_MAX_SIZE = 1 << 16;       // 65K cap
        private static final int POOL_TRACKER_ARRAY_CAP = 512;  // shrink Tracker arrays >512 before recycle
        /**
         * A TrackerList that held more trackers than this is not pooled again.
         *
         * clear() empties an ArrayList but keeps its backing array at the largest size it
         * ever reached, so a pooled list keeps the footprint of the widest node it served.
         * A rescan over a grown database serves wider nodes than the one before, and the
         * pool kept every one of them: on KOSARAK, which rescans at every increment, it held
         * 2,514 of the 3,078 MB the algorithm held after the last increment. Dropping the
         * wide lists keeps the pool for what it is for, the many narrow nodes.
         */
        private static final int POOL_LIST_CAP = 1024;
        private static final int POOL_WARMUP_TRACKERS     = 4096; // pre-allocate ở INIT
        private static final int POOL_WARMUP_TRACKER_LISTS = 256;

        /**
         * TrackerPool: stack đơn giản trên mảng cố định.
         *  - pool[0..top-1] = các Tracker đang chờ recycle.
         *  - borrow() lấy từ đỉnh stack (LIFO → cache-hot).
         *  - release(t) đẩy lên đỉnh; nếu đầy thì drop.
         *  - warmup(n) pre-alloc n Trackers (gọi 1 lần trong INIT).
         */
        static final class TrackerPool {
            final Tracker[] pool = new Tracker[POOL_MAX_SIZE];
            int top = 0;

            Tracker borrow(int seqId) {
                if (top == 0) {
                    // COLD FALLBACK - chỉ kích hoạt nếu warmup không đủ.
                    // Trong steady state sau INIT, KHÔNG được phép xảy ra.
                    return new Tracker(seqId);
                }
                Tracker t = pool[--top];
                pool[top] = null;       // help GC
                t.reset(seqId);
                return t;
            }

            void release(Tracker t) {
                // Shrink oversized arrays trước khi recycle (cap pool memory).
                if (t.flatIndices.length > POOL_TRACKER_ARRAY_CAP) {
                    t.flatIndices = new int[4];
                    t.utility = new double[4];
                }
                if (top < POOL_MAX_SIZE) pool[top++] = t;
                // else: drop, GC reclaim
            }

            /** Pre-allocate n Trackers; CHỈ gọi 1 lần trong INIT, không trong recursion. */
            void warmup(int n) {
                while (top < n && top < POOL_MAX_SIZE) {
                    pool[top++] = new Tracker(-1);
                }
            }
        }

        /**
         * TrackerListPool: tương tự nhưng cho TrackerList wrapper.
         * trackerList.trackers (ArrayList<Tracker>) được clear() trước recycle.
         */
        static final class TrackerListPool {
            final TrackerList[] pool = new TrackerList[POOL_MAX_SIZE];
            int top = 0;
            private final TrackerPool trackerPool; // để release nested Trackers

            TrackerListPool(TrackerPool tp) { this.trackerPool = tp; }

            TrackerList borrow() {
                if (top == 0) {
                    return new TrackerList(); // cold fallback
                }
                TrackerList tl = pool[--top];
                pool[top] = null;
                // trackers đã empty từ release trước (invariant)
                return tl;
            }

            void release(TrackerList tl) {
                // Release nested Trackers vào trackerPool trước khi recycle TrackerList
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

        /** Convenience helpers - hot path gọi qua đây để JIT inline. */
        private Tracker borrowTracker(int seqId) { return trackerPool.borrow(seqId); }
        private void releaseTracker(Tracker t)   { trackerPool.release(t); }
        private TrackerList borrowTrackerList()  { return trackerListPool.borrow(); }
        private void releaseTrackerList(TrackerList tl) { trackerListPool.release(tl); }

        // ====================================================================
        //  SCRATCH BUFFER O(1) INSTANCE MERGING (3 mảng song song theo toiuucode.txt)
        //
        //  Thay backward-scan O(L) trong addOrUpdateInstance bằng class-level
        //  scratch indexed by flatIdx → O(1) lookup/update.
        //
        //  3 mảng:
        //    double[]  scratchMaxUtil[flatIdx]   = max utility hội tụ về flatIdx
        //    boolean[] scratchPresent[flatIdx]   = true nếu flatIdx đã touched
        //                                          (CHÍNH XÁC HƠN sentinel utility=0)
        //    int[]     scratchUsedFlat[i]        = danh sách flatIdx đã touched
        //                                          (cho selective reset)
        //
        //  Workflow (per-parent-sequence trong Pass-2):
        //    int localUsedCount = 0;
        //    for each instance trong parent tracker:
        //      for each matching position j (flat):
        //        double newU = ...;
        //        if (!scratchPresent[j]) {
        //          scratchPresent[j] = true;
        //          scratchUsedFlat[localUsedCount++] = j;
        //          scratchMaxUtil[j] = newU;
        //        } else if (newU > scratchMaxUtil[j]) {
        //          scratchMaxUtil[j] = newU;
        //        }
        //    // FLUSH: copy scratch vào child Tracker và RESET selective
        //    Tracker child = borrowTracker(...);
        //    for (x in [0, localUsedCount)):
        //        int idx = scratchUsedFlat[x];
        //        child.append(idx, scratchMaxUtil[idx], seq);
        //        scratchPresent[idx] = false;   // reset O(usedCount)
        //        // scratchMaxUtil[idx] giữ nguyên - chỉ reset present (đỡ 1 store)
        //
        //  Lý do dùng boolean[] scratchPresent thay sentinel 0:
        //    - Robust trên data có utility = 0 (edge case)
        //    - Không phụ thuộc giá trị utility làm marker
        // ====================================================================
        private transient double[] scratchMaxUtil;
        private transient boolean[] scratchPresent;
        private transient int[] scratchUsedFlat;
        private int maxFlatLen = 0; // max flat length đã thấy across sequences

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

        // ================= PHA 1: KHỞI TẠO =================
        public void runInitialMining(List<Sequence> dInit, double currentTotalUtility) {
            // Reset trạng thái đầy đủ - bao gồm cancellation flag để instance
            // bị OT từ phiên trước có thể được tái sử dụng (defense in depth;
            // runner cũng kiểm tra poisoned trước khi gọi lại).
            cancelled = false;
            rescanCount = 0; patternCount = 0;
            candidateCount = 0; exploredNodes = 0; blocksReadCount = 0; numBlocksSkipped = 0;
            prunedValueBound = 0; prunedIub = 0;
            cubValue = 0; cubTriggeredRescan = false;
            resetIau();
            largeMap.clear(); prelargeMap.clear();
            dbClear();
            globalBlockIndex = new BlockIndex();
            if (blockSize < 1) throw new IllegalArgumentException("block.size phai >= 1");
            globalBlockIndex.blockSize = blockSize;   // WP2.1: B_sz tham so hoa

            globalBlockIndex.buildFromBatch(dInit);
            for (Sequence s : dInit) {
                s.calculateRemainingUtilities();
                s.buildFlatRepresentation();    // tiền xử lý sang flat 1D arrays
                if (s.flatLen > maxFlatLen) maxFlatLen = s.flatLen;
                dbPut(s.id, s);
                for (QItemset is : s.itemsets) for (QItem it : is.items)
                    if (it.id > maxItemId) maxItemId = it.id;
            }
            allocateScratch(maxItemId + 1);
            ensureScratchCapacity(maxFlatLen);
            // === POOL WARMUP - pre-alloc TRƯỚC khi recursion bắt đầu ===
            // Sau dòng này, expandNodeWithPruning sẽ KHÔNG gọi `new Tracker()`.
            trackerPool.warmup(POOL_WARMUP_TRACKERS);
            trackerListPool.warmup(POOL_WARMUP_TRACKER_LISTS);

            // Tang 1: do do lap cua CSDL roi chon san khoi dau. Chi nang, khong ha, nen
            // mot CSDL trong hien de van giu nguyen ti le da cau hinh.
            this.repetition = common.Density.repetition(dInit);
            if (useDensityFloor) {
                double suggested = common.Density.suggestFloor(slMinRatioCfg, repetition);
                if (suggested > slMinRatioEff) { slMinRatioEff = suggested; Sl_min = slMinRatioEff * Su; }
            }

            // Tính toán LUT_dyn và SFT ban đầu
            double uut_abs = currentTotalUtility * Su;
            this.uutValue = uut_abs; // ngưỡng HUSP tuyệt đối (xuất CSV)
            this.lutDynValue = currentTotalUtility * Sl_min; // Khởi tạo bằng ngưỡng cứng
            // SFT (Δ) = khoảng cách tiện ích TUYỆT ĐỐI giữa ngưỡng lớn (uut_abs)
            // và ngưỡng tiền-lớn động (lutDynValue). Cùng thứ nguyên với cubValue.
            this.sftValue = uut_abs - this.lutDynValue;

            mineWithBudget(dInit, uut_abs, currentTotalUtility);
            this.tsuAtLastRescan = currentTotalUtility; // mốc TSU cho cận an toàn F
        }

        // ================= PHA 2: GIA TĂNG =================
        public void runIncrementalMining(List<Sequence> deltaD, List<Sequence> dSoFar, double newTotalUtility) {
            cancelled = false; // reset cancellation từ phiên đo trước (nếu có)
            // PATCHED (tinhchinhcode5 #2): reset candidateCount MỖI LÔ → circuit-breaker
            // là ngân sách per-batch (lô bệnh lý đơn lẻ bị ngắt; nhiều lô hợp lệ cộng dồn
            // KHÔNG bị DNF nhầm) + metric candidate_count phản ánh đúng chi phí từng lô.
            candidateCount = 0; exploredNodes = 0;
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
                accumulateIau(s);          // WP1.1: A_i += u(s) cho mọi item i thuoc s
                for (QItemset is : s.itemsets) for (QItem it : is.items)
                    if (it.id > maxItemId) maxItemId = it.id;
            }
            allocateScratch(maxItemId + 1);
            ensureScratchCapacity(maxFlatLen);
            // Pool warmup ở incremental call cũng (pool có thể đã được drain).
            trackerPool.warmup(POOL_WARMUP_TRACKERS);
            trackerListPool.warmup(POOL_WARMUP_TRACKER_LISTS);

            double uut_abs = newTotalUtility * Su;
            this.uutValue = uut_abs; // ngưỡng HUSP tuyệt đối (xuất CSV)
            double lut_min_abs = newTotalUtility * Sl_min;

            // WP1.4 - TRINH TU BAT BUOC TRONG MOT LO:
            //   nap lo -> cap nhat bo dem (CUB, A_i) -> kiem tra kich hoat ->
            //   neu rescan thi RESET bo dem TRUOC -> roi moi tinh Delta va LUT_dyn.
            // Ban nop tinh Delta truoc khi reset nen mineAndClassify nhan LUT_dyn cu (da bi
            // bom phong boi cubValue chua reset), tuc mine lai bang mot nguong khong tuong
            // ung voi trang thai sau rescan.

            // --- Buoc 1: cap nhat bo dem tich luy ---
            this.cubValue += batchTotalUtil;       // perturbation tich luy ke tu rescan gan nhat
            this.iauTightness = (iauMax <= 0) ? 1.0 : (cubValue / iauMax);
            this.sigmaDSquared = calculateVariance(deltaD);
            this.rhoD = (batchTotalUtil == 0) ? 0 : (swuMax / batchTotalUtil);

            // --- Buoc 2: kiem tra dieu kien kich hoat (WP1.2) ---
            // SFT_ins = (UUT - LUT_min)/(1 - UUT) * TSU_ref  (Eq. 15, khong doi).
            double safetyBoundF = ((Su - Sl_min) / (1.0 - Su)) * tsuAtLastRescan;
            this.sftValue = safetyBoundF;
            double triggerQuantity;
            switch (this.triggerRule) {
                case SWUMAX: triggerQuantity = swuMax;   break;  // luat cu, KHONG sound
                case CUB:    triggerQuantity = cubValue; break;
                case IAUMAX: default: triggerQuantity = iauMax; break;
            }
            // C5 ablation: useAdaptiveRescan=false -> rescan MOI LO (bo quyet dinh thich ung)
            this.cubTriggeredRescan = useAdaptiveRescan ? (triggerQuantity > safetyBoundF) : true;
            this.iauMaxAtDecision = triggerQuantity;   // chup TRUOC resetIau() o buoc ke tiep

            // --- Buoc 3: neu rescan thi reset bo dem TRUOC khi tinh Delta ---
            if (cubTriggeredRescan) {
                rescanCount++;
                this.cubValue = 0;                      // Reset bo dem
                resetIau();                             // Reset A_i
                this.tsuAtLastRescan = newTotalUtility; // cap nhat moc TSU cho SFT
            }

            // --- Buoc 4: bien do thich ung Delta, nay co CA can duoi (WP1.3) ---
            // Can duoi bat buoc. Mau bi loai tai LUT_dyn phai KHONG THE thanh HUSP truoc
            // lan rescan ke tiep. Ngan sach chen con lai la A_max = SFT_ins - (bo dem hien
            // tai). Voi moi A_future <= A_max ta can u(X,D') + A_future < UUT*(TSU' + A_future);
            // hieu hai ve tang theo he so (1 - UUT) > 0 nen truong hop xau nhat o
            // A_future = A_max, cho u(X,D') < UUT*TSU' - (1-UUT)*A_max, tuc
            //     Delta >= (1 - UUT)*A_max = (UUT - LUT_min)*TSU_ref - (1 - UUT)*(bo dem).
            // Khi bo dem = 0 (ngay sau rescan) can duoi nay bang dung tran cu, nen LUT_dyn
            // roi ve san cung LUT_min*TSU_ref - khop pham vi Theorem 3 va 4.
            // Tran duoc nang tu (UUT - LUT_min)*TSU' len UUT*TSU' de can duoi khong bi cat
            // cut; LUT_dyn >= 0 luon dung.
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

            // Tran cua SO HANG HEURISTIC giu nguyen (UUT - LUT_min)*TSU'. Bo tran nay di
            // thi so hang +CUB (co the toi 10% TSU) se keo LUT_dyn ve 0 va bien toan bo
            // mau thanh tien-lon - dung loi da quan sat khi chay thu. Can duoi an toan
            // deltaMin duoc ap SAU tran do va co the vuot no (chi xay ra o pha xoa, khi
            // TSU' < TSU_ref); clamp cuoi cung tai UUT*TSU' bao dam LUT_dyn >= 0.
            //
            // Y nghia: tinh dung dan den TU deltaMin, KHONG tu so hang +CUB. Ban thao
            // tuyen bo +CUB bao phu duoc muc tang toi da cua mot mau; tuyen bo do khong
            // dung vi so hang ay bi tran cat cut. deltaMin la dieu kien dung.
            double heuristicIns = Math.min(delta0 * newTotalUtility + dynamicTerm + cubValue,
                                           uut_abs - lut_min_abs);
            this.deltaDValue = Math.min(Math.max(heuristicIns, deltaMinIns), uut_abs);
            this.lutDynValue = Math.max(0.0, uut_abs - deltaDValue); // band floor thich ung
            if (!useAdaptiveBand) this.lutDynValue = lut_min_abs; // ablation: band co dinh Sl_min

            if (cubTriggeredRescan) {
                // Can an toan vo -> Rescan (giu pruning chat khi mine lai)
                // Gia lap quet lai tren D_SoFar (thuc te se dung BlockIndex de nap selective)
                largeMap.clear(); prelargeMap.clear();
                mineWithBudget(dSoFar, uut_abs, newTotalUtility);

            } else {
                // An toàn -> Cập nhật trực tiếp trên Lớn và Tiền Lớn (CompactPattern)
                List<CompactPattern> allTrackedPatterns = new ArrayList<>();
                allTrackedPatterns.addAll(largeMap.keySet());
                allTrackedPatterns.addAll(prelargeMap.keySet());

                Map<CompactPattern, Double> oldUtilities = new HashMap<>(largeMap);
                oldUtilities.putAll(prelargeMap);
                largeMap.clear(); prelargeMap.clear();
                blocksReadCount = 0;

                for (CompactPattern p : allTrackedPatterns) {
                    double updatedUtility = oldUtilities.get(p) + evaluatePatternUtilityWithIndex(p, deltaD);
                    if (updatedUtility >= uut_abs) largeMap.put(p, updatedUtility);
                    // PATCHED (tinhchinhcode5 #8): PHỤC HỒI sàn pre-large THÍCH ỨNG lutDynValue
                    // (C2 active) thay cho lut_min cố định. Band = [lutDynValue, uut) co/giãn
                    // theo δ_D: hẹp khi ổn định (tránh bùng nổ pre-large) / rộng khi biến động
                    // (tránh thiếu mẫu). Đây là vai trò thật của C2 (xem thực nghiệm dilemma).
                    else if (updatedUtility >= lutDynValue) prelargeMap.put(p, updatedUtility);
                }

                // Khai phá lô mới để tìm mẫu Tiền Lớn/Lớn mới sinh ra trên deltaD.
                // (Vẫn dùng lutDynValue toàn cục - KHÔNG dùng localLut: ngưỡng thấp sẽ
                //  bơm mẫu toàn-cục-nhỏ vào prelargeMap qua nhánh merge bên dưới.)
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

        // ================= PHA 2b: XOÁ (Deletion) =================
        /**
         * Xoá tập deletedSeqs khỏi CSDL. dAfter là CSDL còn lại sau khi xoá.
         * newTotalUtility = TSU(dAfter). CUB cộng |δτ| = u(s) cho mỗi chuỗi xoá.
         */
        public void runDeletion(List<Sequence> deletedSeqs, List<Sequence> dAfter, double newTotalUtility) {
            cancelled = false; // reset cancellation từ phiên đo trước
            if (deletedSeqs == null || deletedSeqs.isEmpty()) { this.patternCount = largeMap.size(); this.prelargeCount = prelargeMap.size(); return; }
            // Defensive: calculateExactUtilityInSeq + Pass-2 dùng scratchMaxUtil/Present/UsedFlat
            // indexed by flatIdx. Đảm bảo allocate (no-op nếu đã đủ size từ INIT/INCREMENTAL).
            allocateScratch(maxItemId + 1);
            ensureScratchCapacity(maxFlatLen);
            double deletedTotal = 0;
            for (Sequence s : deletedSeqs) deletedTotal += s.sequenceUtility;

            double uut_abs = newTotalUtility * Su;
            this.uutValue = uut_abs; // ngưỡng HUSP tuyệt đối (xuất CSV)
            double lut_min_abs = newTotalUtility * Sl_min;

            // Cập nhật LUT_dyn (giống logic insertion để giữ tính nhất quán)
            this.sigmaDSquared = calculateVariance(deletedSeqs);
            double swuMaxDel = 0;
            for (Sequence s : deletedSeqs) if (s.sequenceUtility > swuMaxDel) swuMaxDel = s.sequenceUtility;
            this.rhoD = (deletedTotal == 0) ? 0 : (swuMaxDel / deletedTotal);
            double dynamicTerm = 0;
            switch (this.mode) {
                case FULL: dynamicTerm = kappa * sigmaDSquared * rhoD; break;
                case VARIANCE_ONLY: dynamicTerm = kappa * sigmaDSquared; break;
                case RHO_ONLY: dynamicTerm = kappa * rhoD; break;
            }
            // WP1.4 - cung trinh tu bat buoc nhu pha chen:
            //   cap nhat bo dem -> kiem tra kich hoat -> reset neu rescan -> roi tinh Delta.
            // Luat kich hoat cua pha XOA von da dung dang TICH LUY (cubValue) nen VAN SOUND;
            // chi phai sua thu tu va bo sung can duoi cho Delta.
            this.cubValue += deletedTotal;   // perturbation tich luy ke tu rescan gan nhat

            // F_del = (Su - Sl_min)/Su * TSU_lastRescan (Hong et al., chieu deletion).
            double safetyBoundDel = ((Su - Sl_min) / Su) * tsuAtLastRescan;
            this.sftValue = safetyBoundDel;
            this.cubTriggeredRescan = useAdaptiveRescan ? (cubValue > safetyBoundDel) : true;

            if (cubTriggeredRescan) {
                rescanCount++;
                cubValue = 0;
                resetIau();
                this.tsuAtLastRescan = newTotalUtility; // cap nhat moc TSU cho F
            }

            // WP1.3 - can duoi cho Delta, dang DOI XUNG voi pha chen.
            // Xoa lam TSU giam nen mot mau co the vuot len thanh HUSP ma khong tang utility.
            // Ngan sach xoa con lai A_max = SFT_del - CUB. Truong hop xau nhat: X khong mat
            // gi trong khi TSU giam dung A_max, can u(X,D') < UUT*(TSU' - A_max), tuc
            //     Delta >= UUT*A_max = (UUT - LUT_min)*TSU_ref - UUT*CUB.
            double deltaMinDel = Math.max(0.0,
                    (Su - Sl_min) * tsuAtLastRescan - Su * cubValue);
            double heuristicDel = Math.min(delta0 * newTotalUtility + dynamicTerm + cubValue,
                                           uut_abs - lut_min_abs);
            this.deltaDValue = Math.min(Math.max(heuristicDel, deltaMinDel), uut_abs);
            this.lutDynValue = Math.max(0.0, uut_abs - deltaDValue); // band floor thich ung
            if (!useAdaptiveBand) this.lutDynValue = lut_min_abs; // ablation: band co dinh Sl_min

            if (cubTriggeredRescan) {
                largeMap.clear(); prelargeMap.clear();
                // Loại bỏ các sequence đã xoá khỏi dbLookup để mineAndClassify không truy cập sai
                for (Sequence s : deletedSeqs) dbRemove(s.id);
                mineWithBudget(dAfter, uut_abs, newTotalUtility);
            } else {
                // Cập nhật trực tiếp: trừ utility do chuỗi bị xoá đóng góp (CompactPattern)
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
                    // PATCHED (tinhchinhcode5 #8): phục hồi sàn pre-large THÍCH ỨNG lutDynValue
                    // (C2 active), nhất quán với pha chèn.
                    else if (updated >= lutDynValue) prelargeMap.put(p, updated);
                }
                for (Sequence s : deletedSeqs) dbRemove(s.id);
            }
            this.patternCount = largeMap.size(); this.prelargeCount = prelargeMap.size();
        }

        // ================= PHA 2c: SỬA ĐỔI (Modification) =================
        /**
         * Sửa đổi tập chuỗi: tương đương xoá oldSeqs + chèn newSeqs.
         * CUB cộng |δτ| = |u(new) - u(old)| (theo Định nghĩa 9 / paper).
         */
        public void runModification(List<Sequence> oldSeqs, List<Sequence> newSeqs,
                                    List<Sequence> dAfter, double newTotalUtility) {
            // Mo hinh hoa modification = deletion + insertion.
            //
            // WP1 - LO HONG THU TU BON (phan bien chua neu, cung loai voi A1).
            // Ban nop ghi de bo dem bang cubValue = cubBefore + |u(new) - u(old)|, tuc lay
            // do lech utility RONG lam luong perturbation da tieu. Dai luong nay KHONG chan
            // duoc do bien dong cua tap mau: mot thao tac sua giu nguyen utility nhung doi
            // toan bo item cho |delta| = 0, tuc khong tieu ngan sach nao, trong khi tap mau
            // co the doi hoan toan. Day dung la loi ma Reviewer #1 chi ra o Eq. (9): lay mot
            // dai luong khong chan duoc thu can chan.
            //
            // Cach dung: de nguyen phan tich luy ma hai pha con da cong, tuc
            //     CUB += u(old) + u(new),
            // vi mot thao tac sua vua rut u(old) khoi CSDL vua bom u(new) vao, va ca hai deu
            // co the day mot mau qua nguong. Day la can tren dung cua perturbation.
            // Dat strictModificationBudget=false de tai hien hanh vi cu (chi de doi chieu).
            double delta = 0;
            int n = Math.min(oldSeqs.size(), newSeqs.size());
            for (int i = 0; i < n; i++) delta += Math.abs(newSeqs.get(i).sequenceUtility - oldSeqs.get(i).sequenceUtility);
            double cubBefore = this.cubValue;
            runDeletion(oldSeqs, subtract(dAfter, newSeqs), totalUtilityOf(subtract(dAfter, newSeqs)));
            runIncrementalMining(newSeqs, dAfter, newTotalUtility);
            if (!strictModificationBudget) {
                this.cubValue = cubBefore + delta;   // hanh vi cu, KHONG sound
            }
            // Khong reset triggeredRescan vi hai pha tren da xu ly dung.
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

        /** Đặt lại toàn bộ bộ đếm A_i (gọi tại init và tại mỗi lần rescan). */
        private void resetIau() {
            java.util.Arrays.fill(iau, 0.0);
            iauMax = 0;
        }

        private void ensureIauCapacity(int item) {
            if (item < iau.length) return;
            iau = java.util.Arrays.copyOf(iau, Math.max(item + 1, iau.length * 2));
        }

        /**
         * Cộng u(s) vào A_i cho mọi item PHÂN BIỆT i xuất hiện trong s, và cập nhật iauMax.
         * Dùng scratchIauSeen làm cờ chống cộng trùng khi item lặp lại trong cùng chuỗi
         * (cộng trùng vẫn sound vì chỉ nới cận lên, nhưng làm luật lỏng đi vô cớ).
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
                    if (scratchIauSeen[id] == iauStamp) continue;   // đã cộng cho chuỗi này
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

        /** Cấp phát mảng scratch double[] cho Pass-1 nhanh (tránh HashMap mỗi tracker). */
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
                // Single-pass Pass-2: slot TrackerList theo itemId (gom con 1 lượt quét)
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
            // PATCHED (tinhchinhcode5 #1): GIAO bitset của TẤT CẢ item trong pattern,
            // thay vì chỉ firstItem. Trên BMS1 (siêu thưa) item đầu xuất hiện ở gần như
            // mọi block → bitset toàn 1 → Index vô hiệu → ép quét thủ công hàng triệu
            // mẫu × toàn bộ deltaD (điểm treo CPU 100% ở vòng cập nhật dòng 772-776).
            // An toàn: seq chỉ chứa p nếu chứa MỌI item của p ⟹ block của seq phải nằm
            // trong GIAO các bitset item ⟹ giao là bộ lọc CHẶT HƠN, KHÔNG bỏ sót.
            // (Bỏ qua khi tắt block index - giữ đúng ablation KB7: quét tuần tự toàn bộ.)
            final int blkUnit = Math.max(1, globalBlockIndex.blockSize);
            BitSet relevantBlocks = null;
            if (useBlockIndex) {
                final int[] flat = p.flat;
                for (int idx = 0; idx < flat.length; idx++) {
                    int item = flat[idx];
                    if (item < 0) continue; // bỏ separator -1 (ranh giới itemset)
                    BitSet bs = globalBlockIndex.bitsetFor(item);
                    if (bs == null || bs.isEmpty()) {     // item không ở block nào → pattern bất khả
                        numBlocksSkipped += (deltaD.size() / blkUnit) + 1;
                        return 0.0;
                    }
                    if (relevantBlocks == null) {
                        relevantBlocks = (BitSet) bs.clone(); // CLONE: KHÔNG mutate bitset gốc trong index
                    } else {
                        relevantBlocks.and(bs);
                        if (relevantBlocks.isEmpty()) {   // giao rỗng → không block nào chứa đủ item
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
                // Ablation KB7: nếu tắt block index thì scan tuần tự toàn bộ (không skip)
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
                } else {
                    numBlocksSkipped++;
                }
            }
            return totalUtil;
        }

        private double calculateExactUtilityInSeq(CompactPattern p, Sequence seq) {
            // === SCRATCH BUFFER O(1) cho TỪNG step trong pattern walk ===
            // Thay backward-scan O(L) trong addOrUpdateInstance bằng class-level
            // scratch buffer indexed by flatIdx. Cấu trúc:
            //   Cho mỗi step (extension), accumulate matches vào scratch buffer,
            //   sau đó flush vào nextTracker bằng append() (no dedup needed).
            if (seq.flatItems == null) seq.buildFlatRepresentation();
            final int[] flatItems = seq.flatItems;
            final double[] flatUtils = seq.flatUtilities;
            final boolean[] isItemsetEnd = seq.isItemsetEnd;
            final int flatLen = seq.flatLen;
            final int[] pFlat = p.flat;
            final int pLen = pFlat.length;

            int firstItem = pFlat[0];

            // === STEP 0: build first Tracker cho firstItem ===
            // L1 không có duplicate (mỗi flatIdx unique cho 1 item) → append trực tiếp.
            Tracker tracker = borrowTracker(seq.id);
            for (int k = 0; k < flatLen; k++) {
                if (flatItems[k] == firstItem) {
                    tracker.append(k, flatUtils[k], seq);
                }
            }
            if (tracker.size == 0) {
                releaseTracker(tracker);
                return 0.0;
            }

            Tracker current = tracker;
            boolean nextIsSExt = false;

            // === STEPS 1..pLen-1: extend pattern ===
            for (int idx = 1; idx < pLen; idx++) {
                int v = pFlat[idx];
                if (v == -1) {
                    nextIsSExt = true;
                    continue;
                }
                int itemToFind = v;
                boolean isIExt = !nextIsSExt;
                nextIsSExt = false;

                // === SCRATCH BUFFER ACCUMULATE ===
                int localUsedCount = 0;

                final int curSize = current.size;
                final int[] curFlat = current.flatIndices;
                final double[] curUtil = current.utility;

                for (int k = 0; k < curSize; k++) {
                    int parentFlatIdx = curFlat[k];
                    double instUtility = curUtil[k];
                    if (isIExt) {
                        if (isItemsetEnd[parentFlatIdx]) continue;
                        // I-ext scan
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
                        // S-ext scan
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

                // === FLUSH vào nextTracker + reset scratch buffer selective ===
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
            releaseTracker(current);
            return result;
        }

        private Map<CompactPattern, Double> mineBatchWithIUB(List<Sequence> batch, double minThreshold) {
            Map<CompactPattern, Double> results = new HashMap<>();

            // === L1 trackers: TrackerList[] indexed by itemId (no boxing) ===
            // Dùng FLAT representation: scan flatItems[] thay vì itemsets.items.
            // L1: mỗi flatIdx unique → đi qua Tracker.append() (no-dedup) trực tiếp.
            // Pool: borrow TrackerList + borrow Tracker; release sau expand.
            final int sz = Math.max(1, maxItemId + 1);
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
                    TrackerList tl = l1Trackers[idx];
                    if (tl == null) {
                        tl = borrowTrackerList();
                        l1Trackers[idx] = tl;
                        l1Used[l1UsedCount++] = idx;
                    }
                    // Reuse last Tracker if same seqId; else borrow fresh from pool.
                    int trSize = tl.trackers.size();
                    Tracker last;
                    if (trSize > 0 && (last = tl.trackers.get(trSize - 1)).seqId == seqId) {
                        // reuse
                    } else {
                        last = borrowTracker(seqId);
                        tl.trackers.add(last);
                    }
                    // L1: mỗi flatIdx là unique (chỉ thấy 1 lần / sequence) →
                    // dùng append() trực tiếp (skip backward scan).
                    last.append(k, flatUtils[k], seq);
                }
            }

            // NHIỆM VỤ 1: đếm đầy đủ ứng viên 1-item sinh ra ở đợt quét đầu (đồng nhất baseline).
            candidateCount += l1UsedCount;

            // Recurse + release pool (Strategy 3 + Pool: l1Trackers[idx] = null trước recurse).
            for (int u = 0; u < l1UsedCount; u++) {
                int item = l1Used[u];
                TrackerList tl = l1Trackers[item];
                l1Trackers[item] = null;
                if (tl == null) continue;
                Pattern p = new Pattern(item);
                expandNodeWithPruning(p, tl, batch, minThreshold, results, 1);
                // Sau khi expand, release tl về pool (recycle Trackers bên trong).
                releaseTrackerList(tl);
            }
            return results;
        }

        /**
         * ĐÓNG GÓP C1 & C3: CƠ CHẾ CẮT TỈA PHÂN TẦNG (FILTER-FIRST, COMPUTE-SECOND)
         */
        private void expandNodeWithPruning(Pattern currentPattern, TrackerList parentTrackers, List<Sequence> batch, double minThreshold, Map<CompactPattern, Double> results, int depth) {

            // === COOPERATIVE CANCELLATION CHECK (timeout 60 phút từ runner) ===
            // Volatile read - JIT vẫn inline tốt; cost ~1 ns per recursion node.
            if (cancelled) throw new CancellationSignal();

            // XỬ LÝ DƯ THỪA: Ngăn OOM do deep recursion trên tập dữ liệu sparse/dài
            if (depth > maxDepth) return;
            if (parentTrackers == null || parentTrackers.isEmpty()) return;

            exploredNodes++; // mẫu này được KHAI TRIỂN (đã qua cận PEU, được materialize + đệ quy vào)
            // "Sinh ra là đếm": candidateCount đếm MỌI item sinh ra ở bước Filter survivors (đồng nhất baseline).
            double exactUtilityD = 0, IUB = 0;

            ArrayList<Tracker> ptList = parentTrackers.trackers;
            final int ptSize = ptList.size();
            for (int pi = 0; pi < ptSize; pi++) {
                Tracker t = ptList.get(pi);
                exactUtilityD += t.exactUtility;
                IUB += t.peu; // PEU cục bộ tạo thành IUB
            }

            if (exactUtilityD >= minThreshold) {
                // LỚP PHÒNG THỦ 3: XẢ HƠI CƯỠNG BỨC (Heuristic Flushing).
                // Khi results phình tới ngưỡng nguy hiểm (vd BMS1 sinh hàng triệu mẫu
                // có utility ≥ lutDyn), bỏ 50% mẫu YẾU NHẤT (utility thấp) để giữ RAM
                // bị chặn → không OOM cứng 24GB. An toàn cho output: mẫu LỚN (≥ uut,
                // utility cao nhất) LUÔN được giữ; chỉ tỉa bớt buffer Tiền-Lớn yếu
                // (định lý an toàn + cơ chế Rescan đảm bảo tính toàn vẹn).
                if (results.size() >= maxResultPatterns) flushWeakestResults(results);
                // Build CompactPattern DIRECTLY from currentPattern → 3-5× nhỏ hơn,
                // hashCode cached (1 phép hash O(L) khi tạo, sau đó O(1)).
                // Không clonePattern() → loại 3-5 ArrayList alloc per pattern.
                results.put(CompactPattern.fromMutable(currentPattern), exactUtilityD);
            }

            // --- TẦNG 2: BRANCH PRUNING (Cắt nhánh dựa trên IUB) ---
            if (useIub && IUB < minThreshold) {   // C3 ablation: useIub=false → bỏ cắt nhánh IUB
                prunedIub++; // Cận IUB đã loại nhánh này
                return;
            }

            allocateScratch(maxItemId + 1);

            // ===== TỐI ƯU PASS-1: mảng nguyên thuỷ + boolean[] thay BitSet =====
            // Không allocate gì per-tracker (BitSet trước đây đã gây GC churn).
            // Reset CHỈ các slot từng dùng ở lần gọi recursion trước (selective O(touched)).
            //
            // QUAN TRỌNG (tối ưu KOSARAK/BMS1 nhiều item): KHÔNG bulk-reset
            // scratchSurvivingI/S nữa. Lý do: scratchSurvivingI[idx] CHỈ được GHI ở
            // block "filter survivors" (cho mọi idx ∈ scratchUsedI, cả nhánh true & false)
            // và CHỈ được ĐỌC ở block snapshot (cũng chỉ duyệt scratchUsedI). Vì mọi lần
            // đọc luôn có lần ghi tươi trước đó trong CÙNG call, các bit "rác" ngoài
            // scratchUsedI KHÔNG BAO GIỜ bị đọc → bulk-reset O(maxItemId) là dư thừa.
            // (Trên KOSARAK maxItemId≈41k × hàng triệu node = hàng tỷ thao tác bỏ đi.)
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

                // Reset local seen từ tracker trước (selective)
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

                // FLAT REPRESENTATION - bỏ List<QItemset>.get() khỏi hot loop
                // isItemsetEnd boolean[] thay cho flatToItemsetIdx+itemsetStart:
                //   scan forward từ parentFlatIdx+1, phase I-ext cho tới khi gặp
                //   isItemsetEnd[q]=true → chuyển sang phase S-ext cho phần còn lại.
                final int tSize = t.size;
                final int[] tFlat = t.flatIndices;
                final double[] tUtil = t.utility;
                final int[] flatItems = seq.flatItems;
                final double[] flatUtils = seq.flatUtilities;
                final double[] flatRest = seq.flatRemainingUtility;
                final boolean[] isItemsetEnd = seq.isItemsetEnd;
                final int flatLen = seq.flatLen;

                for (int k = 0; k < tSize; k++) {
                    int parentFlatIdx = tFlat[k];
                    double instUtility = tUtil[k];

                    // ===== INTEGRATED SCAN: I-ext phase rồi S-ext phase =====
                    // IMPORTANT: nếu parent đã là phần tử CUỐI của itemset → skip I-ext
                    // (q = parent+1 sẽ là START của itemset kế, không phải I-ext của parent).
                    int q = parentFlatIdx + 1;

                    // PHASE 1: I-extension survey trong CÙNG itemset - gom MỌI item duy nhất
                    // SINH RA (dedup + max localPEU). Cắt tỉa thực hiện ở bước Filter (cận PEU).
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
                            if (endOfItemset) break; // chuyển sang phase S-ext
                        }
                    }

                    // PHASE 2: S-extension survey từ vị trí q (đã qua boundary).
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

                // Aggregate: cộng local max vào agg, track used cho selective reset sau này
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

            // ĐẾM CÔNG BẰNG ("Sinh ra là đếm"): candidateCount = TỔNG item duy nhất SINH RA tại
            // node này (TRƯỚC cắt tỉa) - đồng nhất baseline + Pre-HUSPM.
            candidateCount += (scratchUsedICount + scratchUsedSCount);
            // Circuit-breaker (dời từ entry): ngắt nhánh runaway khi vượt ngân sách ứng viên.
            if (candidateCount > maxCandidatesBudget) throw new PatternBudgetExceeded(candidateCount);
            // Luoi an toan dong ho. Kiem thua thot vi goi nanoTime() tren moi ung vien se tu
            // no thanh chi phi dang ke; 4096 ung vien mot lan la du day de bat mot luot chay
            // hang chuc phut ma bo dem khong thay gi.
            if (mineDeadlineNanos != 0 && (++deadlineTick & 0xFFF) == 0
                    && System.nanoTime() > mineDeadlineNanos) {
                throw new PatternBudgetExceeded(candidateCount);
            }

            // FILTER bằng CẬN GIÁ TRỊ PEU GỘP (đóng góp C1): áp cận TRƯỚC khi materialize con
            // ⇒ chỉ con sống sót mới được hiện thực hoá (explored ≪ candidate). prunedValueBound
            // đếm số item bị cận PEU loại. GEN MARKER: spSurvGen[idx]==spNodeGen ⟺ survivor node này.
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

            // ===== PASS-2: LAZY DFS MATERIALIZATION (PREFIXSPAN-STYLE) =====
            // Chiến lược cốt lõi (theo goiytinhchinhcode.txt): Chỉ quét parent CSDL
            // để build TrackerList cho MỘT item duy nhất ngay trước khi đệ quy.
            // Sau khi đệ quy xong → tl rơi vào Unreachable → GC thu hồi tức thì.
            //
            // INVARIANT MEMORY: tại mọi depth, chỉ có ĐÚNG 1 TrackerList đang sống
            // (cái đang được đệ quy). Tổng RAM = O(Depth) bất kể số item surviving.
            // → Không bao giờ OOM dù dense data (LEVIATHAN/SIGN @ Su=0.01).
            //
            // TRADE-OFF: rescan parent ptList × (#surviving_I + #surviving_S) lần thay
            // vì 1 lần. CPU tăng, nhưng đổi lại an toàn RAM tuyệt đối - đúng yêu cầu.
            //
            // !!! SNAPSHOT BẮT BUỘC !!! scratchUsedI/S và scratchSurvivingI/S là CLASS
            // FIELD - recursion sẽ OVERWRITE chúng. Phải copy danh sách surviving items
            // ra mảng depth-local TRƯỚC khi vào vòng lặp. (Bug do bỏ qua bước này:
            // pattern count đột ngột rớt 73 → 16 trên paper dataset.)
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

            // ===== CHỌN CHIẾN LƯỢC MATERIALIZE =====
            // Node "hẹp" (ptSize nhỏ - đa số node) → SINGLE-PASS: quét parent ĐÚNG 1
            // lượt/phase, gom MỌI con cùng lúc (nhanh như baseline). Node "rộng" (nông,
            // dày, @ Su rất thấp) → fallback LAZY: RAM tạm O(depth), chống OOM data dày.
            // Cả 2 cho TRACKER CON Y HỆT (cùng dedup scratch buffer theo vị trí, cùng thứ tự)
            // ⇒ kết quả & số ứng viên BẤT BIẾN.
            if ((long) ptSize <= SINGLE_PASS_PT_CAP) {
                // ---------- SINGLE-PASS: I-ext (1 lượt quét) → iSlot ----------
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
                            if (isItemsetEnd[parentFlatIdx]) continue; // không có I-ext
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
                        // FLUSH per-seq: gom theo item vào iSlot (1 Tracker / 1 seqId / 1 item)
                        for (int x = 0; x < localUsedCount; x++) {
                            int q = scratchUsedFlat[x];
                            int idx = flatItems[q];
                            TrackerList tl = iSlot[idx];
                            if (tl == null) { tl = borrowTrackerList(); iSlot[idx] = tl; }
                            ArrayList<Tracker> ts = tl.trackers; int n = ts.size();
                            Tracker ct = (n > 0 && ts.get(n - 1).seqId == sid) ? ts.get(n - 1) : null;
                            if (ct == null) { ct = borrowTracker(sid); ts.add(ct); }
                            ct.append(q, scratchMaxUtil[q], seq);
                            scratchPresent[q] = false; // reset selective
                        }
                    }
                }
                // ---------- SINGLE-PASS: S-ext (1 lượt quét) → sSlot ----------
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
                // ---------- Snapshot con ra depth-local + GIẢI PHÓNG slot[] trước đệ quy ----------
                // (tránh recursion clobber iSlot/sSlot - slot là class field)
                TrackerList[] tlsI = (anyI && survICount > 0) ? borrowTLArray(iTLPool, depth, survICount) : null;
                for (int u = 0; u < survICount; u++) { int idx = survI[u]; tlsI[u] = iSlot[idx]; iSlot[idx] = null; }
                TrackerList[] tlsS = (anyS && survSCount > 0) ? borrowTLArray(sTLPool, depth, survSCount) : null;
                for (int u = 0; u < survSCount; u++) { int idx = survS[u]; tlsS[u] = sSlot[idx]; sSlot[idx] = null; }
                // ---------- Đệ quy I rồi S, release từng con sau đệ quy ----------
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
            // ===== PASS-2 LAZY DFS (FALLBACK node rộng) - SCRATCH BUFFER + OBJECT POOL =====
            //
            // Mỗi surviving item: borrow TrackerList từ pool, scan parent ptList,
            // mỗi parent sequence dùng scratch buffer để merge max-utility theo flatIdx
            // (O(1) thay backward scan O(L)), flush vào Tracker pooled, recurse, release.
            // INVARIANT MEMORY: chỉ 1 TrackerList sống tại mỗi depth → RAM O(depth).

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

                        // === SCRATCH BUFFER per-sequence: localUsedCount reset = 0 ===
                        int localUsedCount = 0;

                        for (int k = 0; k < tSize; k++) {
                            int parentFlatIdx = tFlat[k];
                            double instUtility = tUtil[k];

                            // Nếu parent đã là itemsetEnd → KHÔNG có I-ext.
                            if (isItemsetEnd[parentFlatIdx]) continue;

                            // Scan I-ext: forward đến khi gặp isItemsetEnd
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

                        // FLUSH: dump scratch vào Tracker pooled + reset selective O(usedCount)
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
                    releaseTrackerList(tl); // recycle TrackerList + nested Trackers về pool
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

                            // Tìm sExtStart bằng forward scan đến isItemsetEnd, rồi +1.
                            // Nếu parent đã là itemsetEnd, while không lặp → sExtStart = parent+1.
                            int boundary = parentFlatIdx;
                            while (boundary < flatLen && !isItemsetEnd[boundary]) boundary++;
                            int sExtStart = boundary + 1;

                            // Scan S-ext: từ sExtStart đến hết flatLen
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
            } // end else (fallback lazy)
        }

        /**
         * Khai thac mot lan, co tran cong. Vuot tran thi nang san Sl_min roi thu lai tren
         * cung du lieu do.
         *
         * Vi sao phan ung thay vi du doan: chi phi la so ung vien, va khong dai luong cau
         * truc nao do duoc truoc khi chay du tin cay de dat mot hang so. Do lap tach duoc
         * tam bo cua nghien cuu nay, nhung tam diem va mot nguong la co so mong. Con tran
         * ung vien thi do dung thu can do.
         *
         * Het cach thi NEM ra thay vi chay khong tran. Mot bo du lieu khong co diem van hanh
         * kha thi nen bao duoc dieu do; chay den khi OOM thi nguoi doc khong biet vi sao.
         */
        private void mineWithBudget(List<Sequence> db, double uut, double tsuRef) {
            if (rescanCandidateBudget <= 0 && rescanMillisBudget <= 0) {
                mineAndClassify(db, uut, lutDynValue); return;
            }
            long candBudget = rescanCandidateBudget > 0 ? rescanCandidateBudget : Long.MAX_VALUE;
            long saved = maxCandidatesBudget;
            try {
                for (int attempt = 0; ; attempt++) {
                    // Tran GIAM NUA moi lan thu lai. Mot lan that bai la cong bo di hoan toan
                    // - khai thac bat dau lai tu dau tren cung du lieu - nen tran co dinh bien
                    // ngan sach thanh boi so cua so lan thu. Do tren BMS1 INC#4: hai lan thu
                    // voi tran co dinh ba ty da dot 6,8 ty ung vien TRUOC khi lan co co hoi
                    // bat dau, nhieu hon ca luot khong bat co che nay. Giam nua thi tong phi
                    // khong bao gio vuot hai lan tran ban dau.
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
                        largeMap.clear(); prelargeMap.clear();   // bo ket qua do dang
                        candidateCount = 0;
                    }
                }
            } finally { maxCandidatesBudget = saved; mineDeadlineNanos = 0; }
        }

        private void mineAndClassify(List<Sequence> db, double uut, double lut) {
            Map<CompactPattern, Double> all = mineBatchWithIUB(db, lut);
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
                    // Convert CompactPattern → Pattern chỉ tại điểm export (không hot path)
                    writer.write(entry.getKey().toPattern().toIntuitiveString()
                            + " #UTIL: " + entry.getValue());
                    writer.newLine();
                }
            } catch (IOException e) {
                System.err.println("Lỗi ghi file Adaptive: " + e.getMessage());
            }
        }
    }
}