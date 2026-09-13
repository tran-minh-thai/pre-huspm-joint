package algorithms;

import common.*;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

/**
 * Thuật toán Đối chứng: Pre-HUSPM (Yan et al., J. Supercomputing 2025) - ZERO-ALLOCATION.
 *
 * ───────────────────────────────────────────────────────────────────────────
 * TRUNG THỰC THEO BÀI BÁO GỐC.
 *
 * CẬN CẮT TỈA = "projected SWU" (SWU tính trên projection database):
 *   Với prefix X tại một chuỗi, đại lượng này = max(prefixUtil + restUtil) - cộng
 *   trên các chuỗi:  SWU_proj(X) = Σ_{s⊇X} max_occ(prefixUtil + restUtil) = PEU.
 *   (SWU TOÀN CỤC Σ SU(s) - Đ.n 8 - chỉ là baseline khái niệm; dùng nó sẽ bùng nổ
 *    ~10⁹ ứng viên. Bài báo nói rõ "projected SWU < global SWU".)
 *   Tỉa khi SWU_proj(X) < Sl·TSU (downward closure ⇒ không thể pre-large).
 *   * HUSP (Đ.n 10):           su(X) ≥ Su·TSU         (su = tiện ích THỰC).
 *   * Pre-large PSWU (Đ.n 12): Sl·TSU ≤ SWU_proj(X) < Su·TSU.
 *   * Bộ đệm = LSWU ∪ PSWU = mọi ứng viên SWU_proj ≥ Sl·TSU, lưu (SWU_proj, su).
 *
 * CƠ CHẾ GIA TĂNG - HAI BIẾN THỂ, đúng như bài gốc trình bày:
 *
 *   Ngân sách chung: f = ((Su-Sl)/(1-Su))·TSU_lastRescan.
 *
 *   Variant.TSU (Định lý 1 của Yan et al.) - Pre-HUSPM-TSU:
 *     đại lượng so sánh là TSU(d) = Σ_{s∈d} u(s), tổng utility của lô.
 *     ĐÚNG ĐẮN. Mức tăng utility của một mẫu X trên lô là tổng đóng góp của mọi
 *     chuỗi chèn chứa X, và tổng đó bị chặn bởi TSU(d).
 *
 *   Variant.SWUMAX (Định lý 2 của Yan et al.) - Pre-HUSPM-SWUmax:
 *     đại lượng so sánh là SWUmax(d) = max_{s∈d} u(s), utility của MỘT chuỗi.
 *     KHÔNG ĐÚNG ĐẮN. Chứng minh của họ, Eq. (14), đi qua bước
 *         TSU_d ≥ SWUmax ≥ SWU_d(X),
 *     nhưng SWUmax là utility của một chuỗi trong khi SWU_d(X) cộng trên MỌI chuỗi
 *     của d có chứa X, nên bất đẳng thức thứ hai sai. Phản ví dụ: Su=0.25, Sl=0.20,
 *     TSU(D)=100, u(X,D)=19; chèn hai chuỗi cùng chứa X với u=6 cho SWUmax=6 ≤ f=6.67
 *     nên không rescan, trong khi u(X,D')=31 > 0.25·112=28, tức X đã thành HUSP.
 *
 *   Cả hai biến thể ở đây đều dùng dạng TÍCH LUỸ (buf), tức đã chặt hơn bản gốc vốn
 *   chỉ so sánh trên từng lô riêng lẻ.
 *
 * ───────────────────────────────────────────────────────────────────────────
 * ZERO-ALLOCATION (KHÔNG đổi logic toán học - chỉ đổi cách CẤP PHÁT bộ nhớ):
 *   * Tracker dùng primitive arrays (int[] flatIndices, double[] utility) - KHÔNG
 *     còn List<Instance>/Instance trong vòng đệ quy.
 *   * Object Pool (TrackerPool + TrackerListPool) + warmup ⇒ recursion CHỈ borrow/release.
 *   * Scratch buffer O(1) merge theo flatIdx; 2-pha (Pass-1 gom item duy nhất; Pass-2
 *     lazy-materialize TỪNG item rồi đệ quy + release) ⇒ RAM = O(depth).
 *   * Biểu diễn flat của Sequence (flatItems/flatUtilities/isItemsetEnd/flatRemainingUtility).
 *   Kết quả khai phá (tập HUSP + utility) GIỮ NGUYÊN - đã verify khớp gold.
 * ───────────────────────────────────────────────────────────────────────────
 */
public class PreHUSPMAlgorithm {

    // ========================================================================
    // 1. TRACKER - primitive parallel arrays (KHÔNG còn Instance/List)
    // ========================================================================
    static final class Tracker {
        int seqId;
        int[] flatIndices;
        double[] utility;
        int size = 0;
        double exactUtility = 0;   // su cục bộ = max tiện ích tiền tố trong 1 chuỗi
        double peu = 0;            // SWU_proj cục bộ = max(prefixUtil + restUtil) trong 1 chuỗi

        Tracker(int id) {
            this.seqId = id;
            this.flatIndices = new int[4];
            this.utility = new double[4];
        }

        void reset(int newSeqId) {
            this.seqId = newSeqId; this.size = 0; this.exactUtility = 0; this.peu = 0;
        }

        /** Append (caller đảm bảo flatIdx unique trong 1 chuỗi qua scratch buffer merge). */
        void append(int flatIdx, double u, Sequence seq) {
            if (size == flatIndices.length) grow();
            flatIndices[size] = flatIdx;
            utility[size] = u;
            size++;
            if (u > exactUtility) exactUtility = u;
            double p = u + seq.getRestUtilityFlat(flatIdx);
            if (p > peu) peu = p;
        }

        private void grow() {
            int nc = flatIndices.length << 1;
            flatIndices = Arrays.copyOf(flatIndices, nc);
            utility = Arrays.copyOf(utility, nc);
        }
    }

    static final class TrackerList {
        final ArrayList<Tracker> trackers = new ArrayList<>();
        boolean isEmpty() { return trackers.isEmpty(); }
    }

    // ========================================================================
    // 2. LÕI THUẬT TOÁN PRE-HUSPM
    // ========================================================================
    public static class PreHUSPMAlgo {

        /** Hai biến thể của cơ chế gia tăng trong bài gốc. Xem chú thích đầu lớp. */
        public enum Variant {
            /** Định lý 1: so sánh TSU(d). Đúng đắn. */
            TSU,
            /** Định lý 2: so sánh SWUmax(d). Không đúng đắn; giữ để đối chiếu. */
            SWUMAX
        }

        public double Su, Sl;
        /** Mặc định SWUMAX để giữ nguyên hành vi của các kết quả đã công bố. */
        public Variant variant = Variant.SWUMAX;
        public double safetyBoundF = 0;
        /** Đại lượng đã dùng để so với f ở lô gần nhất (xuất CSV/chẩn đoán). */
        public double lastTriggerQuantity = 0;
        /** buf sau khi cập nhật ở lô gần nhất. */
        public double bufValue = 0;
        public int maxDepth = 1000;

        public Map<Pattern, Double> largeMap = new HashMap<>();      // HUSP: su ≥ Su·TSU
        public Map<Pattern, Double> prelargeMap = new HashMap<>();   // PSWU: Sl·TSU ≤ SWU_proj < Su·TSU
        private Map<Pattern, double[]> swuBuffer = new HashMap<>();  // LSWU∪PSWU → {SWU_proj, su}
        private double buf = 0.0;                                    // tích luỹ SWUmax từ rescan gần nhất

        public int rescanCount = 0, patternCount = 0;
        public int prelargeCount = 0;
        public long candidateCount = 0;
        public long exploredNodes = 0; // Số mẫu THỰC SỰ khai triển (đệ quy vào) - đo hiệu quả tỉa
        public long prunedPEU = 0;

        private boolean writeToFile;

        public PreHUSPMAlgo(double Su, double SlRatio, boolean writeToFile) {
            this(Su, SlRatio, writeToFile, Variant.SWUMAX);
        }

        public PreHUSPMAlgo(double Su, double SlRatio, boolean writeToFile, Variant variant) {
            this.variant = variant;
            this.Su = Su;
            this.Sl = SlRatio * Su;
            this.writeToFile = writeToFile;
        }

        // ---- DB lookup theo seqId bằng ARRAY (không Integer boxing) ----
        private Sequence[] dbLookupArr = new Sequence[16];
        private void dbPut(Sequence seq) {
            int sid = seq.id;
            if (sid >= dbLookupArr.length)
                dbLookupArr = Arrays.copyOf(dbLookupArr, Math.max(sid + 1, dbLookupArr.length * 2));
            dbLookupArr[sid] = seq;
        }
        private Sequence dbGet(int seqId) {
            return (seqId < 0 || seqId >= dbLookupArr.length) ? null : dbLookupArr[seqId];
        }
        private void dbClear() { Arrays.fill(dbLookupArr, null); }

        // ---- Object pools ----
        private static final int POOL_MAX_SIZE = 1 << 16;
        private static final int POOL_TRACKER_ARRAY_CAP = 512;
        private static final int POOL_WARMUP_TRACKERS = 4096;

        static final class TrackerPool {
            final Tracker[] pool = new Tracker[POOL_MAX_SIZE];
            int top = 0;
            Tracker borrow(int seqId) {
                if (top == 0) return new Tracker(seqId);
                Tracker t = pool[--top]; pool[top] = null; t.reset(seqId); return t;
            }
            void release(Tracker t) {
                if (t.flatIndices.length > POOL_TRACKER_ARRAY_CAP) { t.flatIndices = new int[4]; t.utility = new double[4]; }
                if (top < POOL_MAX_SIZE) pool[top++] = t;
            }
            void warmup(int n) { while (top < n && top < POOL_MAX_SIZE) pool[top++] = new Tracker(-1); }
        }
        static final class TrackerListPool {
            final TrackerList[] pool = new TrackerList[POOL_MAX_SIZE];
            int top = 0;
            private final TrackerPool trackerPool;
            TrackerListPool(TrackerPool tp) { this.trackerPool = tp; }
            TrackerList borrow() { return top == 0 ? new TrackerList() : pop(); }
            private TrackerList pop() { TrackerList tl = pool[--top]; pool[top] = null; return tl; }
            void release(TrackerList tl) {
                ArrayList<Tracker> ts = tl.trackers;
                for (int i = 0, n = ts.size(); i < n; i++) trackerPool.release(ts.get(i));
                ts.clear();
                if (top < POOL_MAX_SIZE) pool[top++] = tl;
            }
            void warmup(int n) { while (top < n && top < POOL_MAX_SIZE) pool[top++] = new TrackerList(); }
        }
        private transient final TrackerPool trackerPool = new TrackerPool();
        private transient final TrackerListPool trackerListPool = new TrackerListPool(trackerPool);
        private Tracker borrowTracker(int seqId) { return trackerPool.borrow(seqId); }
        private TrackerList borrowTrackerList() { return trackerListPool.borrow(); }
        private void releaseTrackerList(TrackerList tl) { trackerListPool.release(tl); }

        // ---- Slot theo itemId: gom con TRỰC TIẾP trong 1 LƯỢT quét (single-pass,
        //      khớp profile thuật toán gốc; nhanh hơn lazy-rescan trên data dày) ----
        private transient TrackerList[] iSlot, sSlot;   // iSlot[itemId] = TrackerList con I-ext
        private transient int[] usedItemI, usedItemS;   // danh sách itemId đã chạm (reset selective)
        private int maxItemId = -1;
        private int maxFlatLen = 0;
        private void ensureItemScratch(int sz) {
            if (iSlot == null || iSlot.length < sz) {
                iSlot = new TrackerList[sz]; sSlot = new TrackerList[sz];
                usedItemI = new int[sz]; usedItemS = new int[sz];
            }
        }

        // ---- Mảng depth-local (snapshot con trước khi đệ quy → chống clobber slot[]) ----
        private transient List<int[]> iItemPool = new ArrayList<>();
        private transient List<int[]> sItemPool = new ArrayList<>();
        private transient List<TrackerList[]> iTLPool = new ArrayList<>();
        private transient List<TrackerList[]> sTLPool = new ArrayList<>();
        private int[] borrowIntArr(List<int[]> pool, int depth, int sz) {
            while (pool.size() <= depth) pool.add(null);
            int[] a = pool.get(depth);
            if (a == null || a.length < sz) { a = new int[sz]; pool.set(depth, a); }
            return a;
        }
        private TrackerList[] borrowTLArr(List<TrackerList[]> pool, int depth, int sz) {
            while (pool.size() <= depth) pool.add(null);
            TrackerList[] a = pool.get(depth);
            if (a == null || a.length < sz) { a = new TrackerList[sz]; pool.set(depth, a); }
            return a;
        }

        // ====================================================================
        // PHA 1: KHAI PHÁ KHỞI TẠO
        // ====================================================================
        public void runInitialMining(List<Sequence> dInit, double currentTotalUtility) {
            rescanCount = 0; patternCount = 0; prelargeCount = 0;
            candidateCount = 0; exploredNodes = 0; prunedPEU = 0; buf = 0.0;
            largeMap.clear(); prelargeMap.clear(); swuBuffer.clear();
            executeRescanAndRebuild(dInit, currentTotalUtility, true);
        }

        // ====================================================================
        // PHA 2: KHAI PHÁ GIA TĂNG (Định lý 2 - Pre-HUSPM-SWUmax)
        // ====================================================================
        public void runIncrementalMining(List<Sequence> deltaD, List<Sequence> dSoFar, double newTotalUtility) {
            // Reset MỖI LÔ (nhất quán Adaptive) → candidate/explored/prunedPEU phản ánh ĐÚNG lô này
            // (lô safe-update không mine → 0; lô rescan → số của lần re-mine đó).
            candidateCount = 0; exploredNodes = 0; prunedPEU = 0;

            // Đại lượng so sánh phụ thuộc biến thể (xem chú thích đầu lớp).
            //   TSU    : TSU(d) = Σ_{s∈d} u(s)      - Định lý 1, đúng đắn
            //   SWUMAX : max_{s∈d} u(s)             - Định lý 2, không đúng đắn
            double swuMax = 0, tsuD = 0;
            for (Sequence seq : deltaD) {
                tsuD += seq.sequenceUtility;
                if (seq.sequenceUtility > swuMax) swuMax = seq.sequenceUtility;
            }
            double trigger = (variant == Variant.TSU) ? tsuD : swuMax;
            this.lastTriggerQuantity = trigger;

            if (buf + trigger > safetyBoundF) {
                executeRescanAndRebuild(dSoFar, newTotalUtility, false);
                buf = 0.0;
            } else {
                double newAbsSu = newTotalUtility * Su;
                double newAbsSl = newTotalUtility * Sl;

                Map<Pattern, double[]> updated = new HashMap<>();
                for (Map.Entry<Pattern, double[]> e : swuBuffer.entrySet()) {
                    Pattern p = e.getKey();
                    double projSwu = e.getValue()[0];
                    double su      = e.getValue()[1];
                    for (Sequence seq : deltaD) {
                        double[] c = evaluatePatternUtility(p, seq); // {su, SWU_proj}
                        su      += c[0];
                        projSwu += c[1];
                    }
                    if (projSwu >= newAbsSl) updated.put(p, new double[]{projSwu, su});
                }
                swuBuffer = updated;
                deriveMaps(newAbsSu, newAbsSl);
                buf += trigger;
            }
            this.bufValue = buf;
            this.patternCount = largeMap.size();
            this.prelargeCount = prelargeMap.size();
        }

        private void updateSafetyBound(double totalUtilityD) {
            this.safetyBoundF = ((Su - Sl) / (1.0 - Su)) * totalUtilityD;
        }

        private void executeRescanAndRebuild(List<Sequence> currentFullDB, double newTotalUtility, boolean isInitial) {
            if (!isInitial) rescanCount++;
            updateSafetyBound(newTotalUtility);
            double absSu = newTotalUtility * Su;
            double absSl = newTotalUtility * Sl;
            swuBuffer = mineFromScratch(currentFullDB, absSl);
            deriveMaps(absSu, absSl);
        }

        /** Suy ra largeMap (HUSP, Đ.n10) & prelargeMap (PSWU, Đ.n12) từ bộ đệm. */
        private void deriveMaps(double absSu, double absSl) {
            largeMap.clear(); prelargeMap.clear();
            for (Map.Entry<Pattern, double[]> e : swuBuffer.entrySet()) {
                double projSwu = e.getValue()[0], su = e.getValue()[1];
                if (su >= absSu) largeMap.put(e.getKey(), su);
                else if (projSwu >= absSl && projSwu < absSu) prelargeMap.put(e.getKey(), su);
            }
            patternCount = largeMap.size();
            prelargeCount = prelargeMap.size();
        }

        // ====================================================================
        // KHAI PHÁ TỪ ĐẦU - zero-allocation
        // ====================================================================
        private Map<Pattern, double[]> mineFromScratch(List<Sequence> db, double minSl) {
            dbClear();
            maxItemId = -1; maxFlatLen = 0;
            for (Sequence s : db) {
                if (s.flatItems == null) s.buildFlatRepresentation();
                dbPut(s);
                if (s.flatLen > maxFlatLen) maxFlatLen = s.flatLen;
                int[] fi = s.flatItems;
                for (int k = 0; k < s.flatLen; k++) if (fi[k] > maxItemId) maxItemId = fi[k];
            }
            ensureItemScratch(maxItemId + 2);
            trackerPool.warmup(POOL_WARMUP_TRACKERS);
            trackerListPool.warmup(1024);

            Map<Pattern, double[]> results = new HashMap<>();

            // ---- L1: gom theo item (một lần quét; flat Tracker, không Instance) ----
            Map<Integer, TrackerList> l1 = new HashMap<>();
            for (Sequence seq : db) {
                int sid = seq.id, flatLen = seq.flatLen;
                int[] fi = seq.flatItems; double[] fu = seq.flatUtilities;
                for (int k = 0; k < flatLen; k++) {
                    int item = fi[k];
                    TrackerList tl = l1.get(item);
                    if (tl == null) { tl = new TrackerList(); l1.put(item, tl); }
                    Tracker t;
                    int n = tl.trackers.size();
                    if (n > 0 && tl.trackers.get(n - 1).seqId == sid) t = tl.trackers.get(n - 1);
                    else { t = new Tracker(sid); tl.trackers.add(t); }
                    t.append(k, fu[k], seq);
                }
            }

            for (Map.Entry<Integer, TrackerList> e : l1.entrySet()) {
                Pattern p = new Pattern(e.getKey());
                expandNode(p, e.getValue(), minSl, results, 1);
            }
            return results;
        }

        /**
         * Đệ quy 2-pha zero-allocation (SINGLE-PASS) với cận SWU_proj(=PEU).
         *  Pass-1: quét parent ĐÚNG 1 LƯỢT, gom con TRỰC TIẾP vào iSlot/sSlot[itemId]
         *          (mượn Tracker/TrackerList từ pool; 1 Tracker / 1 seqId / 1 item).
         *  Pass-2: snapshot con ra mảng depth-local (chống clobber slot[]), đệ quy
         *          từng con rồi release về pool.
         *  Khớp luồng thuật toán gốc (gom toàn bộ mở rộng 1 lượt) - chỉ đổi cấp phát.
         */
        private void expandNode(Pattern currentPattern, TrackerList parentTrackers, double minSl,
                                Map<Pattern, double[]> results, int depth) {
            if (depth > maxDepth) return;
            if (Thread.currentThread().isInterrupted()) return;
            if (parentTrackers == null || parentTrackers.isEmpty()) return;

            exploredNodes++;  // mẫu này được KHAI TRIỂN (materialize + đệ quy vào)
            candidateCount++; // One Generation = One Candidate (Pre-HUSPM materialize MỌI extension
                              //   → candidate_count = explored_nodes; KHÁC Adaptive: EUCS lọc trước materialize)

            final ArrayList<Tracker> ptList = parentTrackers.trackers;
            final int ptSize = ptList.size();

            double projSwuD = 0, suD = 0;
            for (int i = 0; i < ptSize; i++) {
                Tracker t = ptList.get(i);
                suD += t.exactUtility;
                projSwuD += t.peu;
            }

            if (projSwuD < minSl) { prunedPEU++; return; }
            results.put(currentPattern.clonePattern(), new double[]{projSwuD, suD});

            // ===== PASS-1: SINGLE-PASS - gom con I-ext & S-ext trực tiếp vào slot[] =====
            int usedICount = 0, usedSCount = 0;
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
                for (int k = 0; k < tSize; k++) {
                    int p = tFlat[k];
                    double iu = tUtil[k];
                    // I-ext: cùng itemset, sau p (đến isItemsetEnd)
                    if (!isItemsetEnd[p]) {
                        for (int q = p + 1; q < flatLen; q++) {
                            int item = flatItems[q];
                            TrackerList tl = iSlot[item];
                            if (tl == null) { tl = borrowTrackerList(); iSlot[item] = tl; usedItemI[usedICount++] = item; }
                            ArrayList<Tracker> ts = tl.trackers; int n = ts.size();
                            Tracker ct = (n > 0 && ts.get(n - 1).seqId == sid) ? ts.get(n - 1) : null;
                            if (ct == null) { ct = borrowTracker(sid); ts.add(ct); }
                            ct.append(q, iu + flatUtils[q], seq);
                            if (isItemsetEnd[q]) break;
                        }
                    }
                    // S-ext: itemset sau itemset của p
                    int boundary = p; while (boundary < flatLen && !isItemsetEnd[boundary]) boundary++;
                    for (int q = boundary + 1; q < flatLen; q++) {
                        int item = flatItems[q];
                        TrackerList tl = sSlot[item];
                        if (tl == null) { tl = borrowTrackerList(); sSlot[item] = tl; usedItemS[usedSCount++] = item; }
                        ArrayList<Tracker> ts = tl.trackers; int n = ts.size();
                        Tracker ct = (n > 0 && ts.get(n - 1).seqId == sid) ? ts.get(n - 1) : null;
                        if (ct == null) { ct = borrowTracker(sid); ts.add(ct); }
                        ct.append(q, iu + flatUtils[q], seq);
                    }
                }
            }

            // ===== Snapshot con ra mảng depth-local + GIẢI PHÓNG slot[] (chống clobber) =====
            int[] itemsI = null; TrackerList[] tlsI = null;
            if (usedICount > 0) {
                itemsI = borrowIntArr(iItemPool, depth, usedICount);
                tlsI = borrowTLArr(iTLPool, depth, usedICount);
                for (int u = 0; u < usedICount; u++) { int item = usedItemI[u]; itemsI[u] = item; tlsI[u] = iSlot[item]; iSlot[item] = null; }
            }
            int[] itemsS = null; TrackerList[] tlsS = null;
            if (usedSCount > 0) {
                itemsS = borrowIntArr(sItemPool, depth, usedSCount);
                tlsS = borrowTLArr(sTLPool, depth, usedSCount);
                for (int u = 0; u < usedSCount; u++) { int item = usedItemS[u]; itemsS[u] = item; tlsS[u] = sSlot[item]; sSlot[item] = null; }
            }

            // ===== PASS-2: đệ quy từng con rồi release về pool =====
            for (int u = 0; u < usedICount; u++) {
                currentPattern.addIExtension(itemsI[u]);
                expandNode(currentPattern, tlsI[u], minSl, results, depth + 1);
                currentPattern.removeLastIExtension();
                releaseTrackerList(tlsI[u]);
            }
            for (int u = 0; u < usedSCount; u++) {
                currentPattern.addSExtension(itemsS[u]);
                expandNode(currentPattern, tlsS[u], minSl, results, depth + 1);
                currentPattern.removeLastSExtension();
                releaseTrackerList(tlsS[u]);
            }
        }

        // ====================================================================
        // evaluatePatternUtility - {su, SWU_proj} của p trong 1 chuỗi (frontier
        // bằng mảng song song tái sử dụng - KHÔNG List/Instance).
        // ====================================================================
        private int[] evCurIs = new int[16], evCurIi = new int[16];
        private double[] evCurU = new double[16];
        private int[] evNxtIs = new int[16], evNxtIi = new int[16];
        private double[] evNxtU = new double[16];
        private int evCurN = 0, evNxtN = 0;
        private final double[] evalOut = new double[2];

        private void evPushCur(int is, int ii, double u) {
            if (evCurN == evCurIs.length) {
                int nc = evCurN << 1;
                evCurIs = Arrays.copyOf(evCurIs, nc); evCurIi = Arrays.copyOf(evCurIi, nc); evCurU = Arrays.copyOf(evCurU, nc);
            }
            evCurIs[evCurN] = is; evCurIi[evCurN] = ii; evCurU[evCurN] = u; evCurN++;
        }
        private void evPushNxt(int is, int ii, double u) {
            if (evNxtN == evNxtIs.length) {
                int nc = evNxtN << 1;
                evNxtIs = Arrays.copyOf(evNxtIs, nc); evNxtIi = Arrays.copyOf(evNxtIi, nc); evNxtU = Arrays.copyOf(evNxtU, nc);
            }
            evNxtIs[evNxtN] = is; evNxtIi[evNxtN] = ii; evNxtU[evNxtN] = u; evNxtN++;
        }

        private double[] evaluatePatternUtility(Pattern p, Sequence seq) {
            int firstItem = p.elements.get(0).get(0);
            evCurN = 0;
            List<QItemset> isets = seq.itemsets;
            for (int is = 0; is < isets.size(); is++) {
                QItemset it = isets.get(is);
                for (int ii = 0; ii < it.items.size(); ii++)
                    if (it.items.get(ii).id == firstItem) evPushCur(is, ii, it.items.get(ii).utility);
            }
            if (evCurN == 0) { evalOut[0] = 0; evalOut[1] = 0; return evalOut; }

            for (int i = 0; i < p.elements.size(); i++) {
                List<Integer> itemset = p.elements.get(i);
                for (int j = 0; j < itemset.size(); j++) {
                    if (i == 0 && j == 0) continue;
                    int itemToFind = itemset.get(j);
                    boolean isIExt = (j > 0);
                    evNxtN = 0;
                    for (int e = 0; e < evCurN; e++) {
                        int cis = evCurIs[e], cii = evCurIi[e]; double cu = evCurU[e];
                        if (isIExt) {
                            QItemset cur = isets.get(cis);
                            for (int k = cii + 1; k < cur.items.size(); k++)
                                if (cur.items.get(k).id == itemToFind) evPushNxt(cis, k, cu + cur.items.get(k).utility);
                        } else {
                            for (int ns = cis + 1; ns < isets.size(); ns++) {
                                QItemset nxt = isets.get(ns);
                                for (int k = 0; k < nxt.items.size(); k++)
                                    if (nxt.items.get(k).id == itemToFind) evPushNxt(ns, k, cu + nxt.items.get(k).utility);
                            }
                        }
                    }
                    if (evNxtN == 0) { evalOut[0] = 0; evalOut[1] = 0; return evalOut; }
                    // swap cur <-> nxt (chỉ đổi tham chiếu - không alloc)
                    int[] ti = evCurIs; evCurIs = evNxtIs; evNxtIs = ti;
                    ti = evCurIi; evCurIi = evNxtIi; evNxtIi = ti;
                    double[] td = evCurU; evCurU = evNxtU; evNxtU = td;
                    evCurN = evNxtN;
                }
            }
            double su = 0, peu = 0;
            for (int e = 0; e < evCurN; e++) {
                if (evCurU[e] > su) su = evCurU[e];
                double pp = evCurU[e] + seq.getRestUtility(evCurIs[e], evCurIi[e]);
                if (pp > peu) peu = pp;
            }
            evalOut[0] = su; evalOut[1] = peu;
            return evalOut;
        }

        public void exportLargePatterns(String outputPath) {
            if (!writeToFile || outputPath == null) return;
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputPath))) {
                List<Map.Entry<Pattern, Double>> sorted = new ArrayList<>(largeMap.entrySet());
                sorted.sort((e1, e2) -> Double.compare(e2.getValue(), e1.getValue()));
                for (Map.Entry<Pattern, Double> entry : sorted) {
                    writer.write(entry.getKey().toIntuitiveString() + " #UTIL: " + entry.getValue());
                    writer.newLine();
                }
            } catch (IOException e) {
                System.err.println("Lỗi ghi file Pre-HUSPM: " + e.getMessage());
            }
        }
    }
}
