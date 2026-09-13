package algorithms;

import common.Pattern;
import common.QItem;
import common.QItemset;
import common.Sequence;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Thuat toan doi chung: HUSP-SP (Zhang, Yang, Du, Gan, Yu; ACM TKDD 2023).
 *
 * ---------------------------------------------------------------------------
 * NGUON GOC CAI DAT
 *
 * Cai dat lai DOC LAP tu pseudocode va cac dinh nghia trong bai bao (Dinh nghia
 * 4.5, 4.6, 4.7, 4.9, 4.11; Dinh ly 4.8, 4.10, 4.12; Thuat toan 1, 2, 3).
 * Ma nguon cua tac gia tai github.com/DSI-Lab1/HUSPM KHONG co file license nen
 * khong duoc sao chep vao day; khong dong nao trong file nay lay tu do.
 * ---------------------------------------------------------------------------
 *
 * BA THANH PHAN CHINH CUA BAI BAO
 *
 * 1. seq-array (Dinh nghia 4.5). Moi q-sequence duoc trai phang thanh bon mang
 *    song song cung do dai: ten item, utility cua item, utility cua phan con lai
 *    ke tu sau vi tri do, va chi so item dau tien cua itemset dang chua vi tri
 *    do. Kem mot bang item-indices anh xa item sang danh sach vi tri cua no.
 *
 * 2. seqPro (Dinh nghia 4.6). Bieu dien CSDL chieu cua mot nut: con tro toi
 *    seq-array goc cong mot extension-list ghi cac the hien cua mau hien tai,
 *    moi the hien gom utility tich luy (acu) va vi tri mo rong (exIndex).
 *    Khac voi UL-list cua HUSP-ULL o cho khong phai luu o rong.
 *
 * 3. Ba can tren, tu long den chat: PEU, RSU, va TRSU.
 *    TRSU la dong gop cua bai: no tru di utility cua doan CON KHONG LIEN QUAN
 *    nam giua vi tri mo rong dau tien cua tien to va lan xuat hien dau tien cua
 *    item moi. Doan do gop vao PEU nhung khong the gop vao bat ky mo rong nao
 *    cua mau moi, nen tru di van con la can tren hop le.
 *
 * Hai chien luoc tia: IIP (theo RSU, ke thua tu HUSP-ULL) va EP (theo TRSU).
 *
 * ---------------------------------------------------------------------------
 * MOT CHI TIET DE SAI, BAI BAO NEU RO
 *
 * Trong Thuat toan 3, phai GHI KET QUA TRUOC roi moi kiem tra dieu kien tia
 * theo PEU. Lam nguoc lai se bo sot mau: mot mau co the la HUSP trong khi PEU
 * cua no khong du de mo rong tiep.
 * ---------------------------------------------------------------------------
 *
 * DEM DE SO SANH CONG BANG VOI CAC BASELINE KHAC
 *   candidateCount  dem MOI mo rong duoc sinh ra, TRUOC bat ky phep tia nao,
 *                   nen no la bien tim kiem va bang nhau giua cac thuat toan
 *                   cung ho nguong.
 *   exploredNodes   dem tai diem VAO de quy, tuc so mau thuc su duoc khai trien
 *                   sau khi tia. Day moi la so do hieu qua tia.
 *
 * La thuat toan TINH, khong xu ly cap nhat gia tang, nen chi dung o SC1 va SC3.
 */
public class HUSPSPAlgorithm {

    // =====================================================================
    // 1. seq-array (Dinh nghia 4.5)
    // =====================================================================
    static final class SeqArray {
        final int seqId;
        final int[] item;          // ten item tai moi vi tri phang
        final double[] util;       // utility cua item tai vi tri do
        final double[] remUtil;    // utility cua phan con lai KE TU SAU vi tri do
        final int[] elemStart;     // chi so item dau tien cua itemset dang chua vi tri do
        final int len;
        /** item -> cac vi tri phang ma no xuat hien (tang dan). */
        final Map<Integer, int[]> itemIndices;
        final double seqUtility;

        SeqArray(Sequence s) {
            this.seqId = s.id;
            int n = 0;
            for (QItemset is : s.itemsets) n += is.items.size();
            this.len = n;
            this.item = new int[n];
            this.util = new double[n];
            this.remUtil = new double[n];
            this.elemStart = new int[n];

            int k = 0;
            for (QItemset is : s.itemsets) {
                int start = k;
                for (QItem it : is.items) {
                    item[k] = it.id;
                    util[k] = it.utility;
                    elemStart[k] = start;
                    k++;
                }
            }
            // remUtil la tong hau to: utility cua toan bo phan sau vi tri i.
            double acc = 0;
            for (int i = n - 1; i >= 0; i--) {
                remUtil[i] = acc;
                acc += util[i];
            }
            this.seqUtility = acc;

            Map<Integer, List<Integer>> tmp = new HashMap<>();
            for (int i = 0; i < n; i++) {
                tmp.computeIfAbsent(item[i], x -> new ArrayList<>()).add(i);
            }
            Map<Integer, int[]> idx = new HashMap<>(tmp.size() * 2);
            for (Map.Entry<Integer, List<Integer>> e : tmp.entrySet()) {
                List<Integer> v = e.getValue();
                int[] a = new int[v.size()];
                for (int i = 0; i < a.length; i++) a[i] = v.get(i);
                idx.put(e.getKey(), a);
            }
            this.itemIndices = idx;
        }

        /** true neu vi tri a va b thuoc CUNG mot itemset. */
        boolean sameElement(int a, int b) { return elemStart[a] == elemStart[b]; }
    }

    // =====================================================================
    // 2. seqPro (Dinh nghia 4.6)
    // =====================================================================
    /**
     * Mot the hien cua mau trong mot q-sequence: utility tich luy den vi tri mo
     * rong, va chinh vi tri mo rong do.
     */
    static final class Ins {
        final double acu;
        final int exIndex;
        Ins(double acu, int exIndex) { this.acu = acu; this.exIndex = exIndex; }
    }

    static final class SeqPro {
        final SeqArray sa;
        final List<Ins> exList;    // sap tang dan theo exIndex
        SeqPro(SeqArray sa, List<Ins> exList) { this.sa = sa; this.exList = exList; }

        /** u(t, s): utility cua mau trong chuoi nay = max tren moi the hien. */
        double patternUtility() {
            double best = 0;
            for (Ins in : exList) if (in.acu > best) best = in.acu;
            return best;
        }

        /**
         * PEU(t, s) theo Dinh nghia 4.7: max tren cac the hien cua
         * (acu + utility phan con lai), va bang 0 khi khong con gi de mo rong.
         */
        double peu() {
            double best = 0;
            for (Ins in : exList) {
                double rem = sa.remUtil[in.exIndex];
                if (rem <= 0) continue;              // het cho mo rong
                double v = in.acu + rem;
                if (v > best) best = v;
            }
            return best;
        }

        /** Vi tri mo rong DAU TIEN cua mau trong chuoi nay. */
        int firstExIndex() { return exList.isEmpty() ? -1 : exList.get(0).exIndex; }

        /**
         * PEU co dat tai the hien DAU TIEN hay khong. Dieu kien nay quyet dinh
         * TRSU dung nhanh tru bot hay lui ve RSU (Dinh nghia 4.11).
         */
        boolean peuAttainedAtFirst() {
            if (exList.isEmpty()) return false;
            Ins f = exList.get(0);
            double rf = sa.remUtil[f.exIndex];
            if (rf <= 0) return false;
            double first = f.acu + rf;
            return first >= peu() - EPS;
        }
    }

    private static final double EPS = 1e-9;

    // =====================================================================
    // 3. Chi so thuc nghiem
    // =====================================================================
    public double minUtility;
    public int patternCount = 0;
    public long candidateCount = 0;   // MOI mo rong sinh ra, truoc moi phep tia
    public long exploredNodes = 0;    // dem tai diem vao de quy
    public long prunedSWU = 0;        // item bi loai o buoc quet dau bang SWU
    public long prunedIIP = 0;        // item bi loai khoi seqPro boi IIP (theo RSU)
    public long prunedEP = 0;         // ung vien bi loai boi EP (theo TRSU)
    public long prunedPEU = 0;        // nhanh bi dung boi can PEU trong Thuat toan 3

    public Map<Pattern, Double> largeMap = new HashMap<>();

    /** Cho phep runner ngat khi vuot timeout, giong cac baseline khac. */
    private volatile boolean cancelled = false;
    public void cancel() { this.cancelled = true; }
    public boolean isCancelled() { return cancelled; }

    public static final class CancellationSignal extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public CancellationSignal() { super("HUSP-SP cancelled by timeout", null, false, false); }
    }

    public HUSPSPAlgorithm(double absoluteMinUtility) {
        this.minUtility = absoluteMinUtility;
    }

    private List<SeqArray> db;

    // =====================================================================
    // Thuat toan 1
    // =====================================================================
    public void run(List<Sequence> database) {
        cancelled = false;
        patternCount = 0; candidateCount = 0; exploredNodes = 0;
        prunedSWU = 0; prunedIIP = 0; prunedEP = 0; prunedPEU = 0;
        largeMap.clear();

        // Buoc 1: dung seq-array cho tung q-sequence.
        db = new ArrayList<>(database.size());
        for (Sequence s : database) {
            SeqArray sa = new SeqArray(s);
            if (sa.len > 0) db.add(sa);
        }

        // Buoc 2: loai vinh vien cac item co SWU duoi nguong. Voi mau MOT item,
        // RSU trung voi SWU (ghi chu sau Dinh nghia 4.9), nen day chinh la IIP
        // ap o muc toan CSDL. Bai bao lam dung buoc nay trong vi du chay.
        Map<Integer, Double> swu = new HashMap<>();
        for (SeqArray sa : db) {
            for (Integer it : sa.itemIndices.keySet()) {
                swu.merge(it, sa.seqUtility, Double::sum);
            }
        }
        Set<Integer> promising = new HashSet<>();
        for (Map.Entry<Integer, Double> e : swu.entrySet()) {
            if (e.getValue() >= minUtility) promising.add(e.getKey());
            else prunedSWU++;
        }

        // Buoc 3: voi moi item hua hen, dung seqPro roi goi UtilityCalculation.
        // Mau rong khong co the hien nen ta khoi tao truc tiep o day; day dung la
        // vong lap ma Thuat toan 2 se chay tren tien to rong.
        for (Integer it : promising) {
            checkCancelled();
            candidateCount++;
            List<SeqPro> proj = projectSingleItem(it);
            if (proj.isEmpty()) continue;
            Pattern p = new Pattern(it);
            utilityCalculation(p, proj);
        }
    }

    /** seqPro cua mau mot item: moi lan xuat hien la mot the hien. */
    private List<SeqPro> projectSingleItem(int item) {
        List<SeqPro> out = new ArrayList<>();
        for (SeqArray sa : db) {
            int[] pos = sa.itemIndices.get(item);
            if (pos == null) continue;
            List<Ins> ex = new ArrayList<>(pos.length);
            for (int p : pos) ex.add(new Ins(sa.util[p], p));
            out.add(new SeqPro(sa, ex));
        }
        return out;
    }

    // =====================================================================
    // Thuat toan 3 - UtilityCalculation
    // =====================================================================
    private void utilityCalculation(Pattern prefix, List<SeqPro> proj) {
        double util = 0, peu = 0;
        for (SeqPro sp : proj) {
            util += sp.patternUtility();
            peu += sp.peu();
        }

        // GHI KET QUA TRUOC. Bai bao canh bao ro: dao thu tu se bo sot mau, vi
        // mot mau co the la HUSP trong khi PEU cua no khong du de di tiep.
        if (util >= minUtility) {
            largeMap.put(prefix.clonePattern(), util);
            patternCount = largeMap.size();
        }

        if (peu >= minUtility) {
            patternGrowth(prefix, proj);
        } else {
            prunedPEU++;
        }
    }

    // =====================================================================
    // Thuat toan 2 - PatternGrowth
    // =====================================================================
    private void patternGrowth(Pattern prefix, List<SeqPro> proj) {
        checkCancelled();
        exploredNodes++;

        // --- IIP (theo RSU) ---
        // RSU cua mot item mo rong = tong PEU(prefix, s) tren cac chuoi s con
        // chua item do sau vi tri mo rong. Item duoi nguong bi loai khoi seqPro
        // cua prefix VA cua moi mo rong cua prefix.
        Map<Integer, Double> rsu = new HashMap<>();
        for (SeqPro sp : proj) {
            double peuS = sp.peu();
            if (peuS <= 0) continue;
            // cac item con co the xuat hien sau vi tri mo rong dau tien
            int from = sp.firstExIndex();
            Set<Integer> seen = new HashSet<>();
            for (int i = from + 1; i < sp.sa.len; i++) {
                if (seen.add(sp.sa.item[i])) rsu.merge(sp.sa.item[i], peuS, Double::sum);
            }
            // item cung itemset voi vi tri mo rong cung la ung vien I-Extension
            for (Ins in : sp.exList) {
                int es = sp.sa.elemStart[in.exIndex];
                for (int i = es; i < sp.sa.len && sp.sa.elemStart[i] == es; i++) {
                    if (i > in.exIndex && seen.add(sp.sa.item[i])) {
                        rsu.merge(sp.sa.item[i], peuS, Double::sum);
                    }
                }
            }
        }
        // --- Gom ung vien I-Extension va S-Extension, kem TRSU cho EP ---
        // Liet ke TRUOC khi loc. candidateCount phai dem bien tim kiem THO, giong
        // moi baseline khac, neu khong thi con so cua HUSP-SP se nho hon chi vi no
        // tia som hon - dung thu khong duoc so sanh voi nhau.
        Map<Integer, Double> trsuI = new HashMap<>();
        Map<Integer, Double> trsuS = new HashMap<>();
        for (SeqPro sp : proj) {
            accumulateTrsu(sp, null, trsuI, true);
            accumulateTrsu(sp, null, trsuS, false);
        }
        candidateCount += trsuI.size();
        candidateCount += trsuS.size();

        // --- IIP roi EP, theo dung thu tu cua Thuat toan 2 ---
        expand(prefix, proj, trsuI, rsu, true);
        expand(prefix, proj, trsuS, rsu, false);
    }

    /**
     * Ap IIP roi EP cho mot phia mo rong, sau do di sau.
     *
     * IIP loai theo RSU, EP loai theo TRSU. Vi TRSU <= RSU nen EP mot minh da du
     * chat; giu ca hai va dem rieng de bao cao dung dong gop cua tung chien luoc,
     * dung nhu bai bao trinh bay chung.
     */
    private void expand(Pattern prefix, List<SeqPro> proj, Map<Integer, Double> trsu,
                        Map<Integer, Double> rsu, boolean iExtension) {
        for (Map.Entry<Integer, Double> e : trsu.entrySet()) {
            checkCancelled();
            int item = e.getKey();
            Double r = rsu.get(item);
            if (r == null || r < minUtility) { prunedIIP++; continue; }   // IIP
            if (e.getValue() < minUtility)   { prunedEP++;  continue; }   // EP
            List<SeqPro> child = project(proj, item, iExtension);
            if (child.isEmpty()) continue;
            Pattern p = iExtension ? prefix.iConcatenate(item) : prefix.sConcatenate(item);
            utilityCalculation(p, child);
        }
    }

    /**
     * TRSU (Dinh nghia 4.11) cong don cho moi item mo rong kha di trong mot chuoi.
     *
     * Khi PEU dat tai the hien DAU TIEN, doan nam giua vi tri mo rong dau tien cua
     * TIEN TO va lan xuat hien dau tien cua ITEM MOI khong the gop vao bat ky mo
     * rong nao cua mau moi, nen tru phan do khoi PEU van con la can tren:
     *
     *     TRSU = PEU(t,s) - [ u(s / p1) - u(s / (q1 - 1)) ]
     *
     * voi p1 la vi tri mo rong dau tien cua tien to va q1 la vi tri hop le dau tien
     * cua item moi. Neu khong thoa dieu kien thi TRSU lui ve RSU, tuc bang PEU(t,s).
     */
    private void accumulateTrsu(SeqPro sp, Set<Integer> allowed,
                                Map<Integer, Double> out, boolean iExtension) {
        // allowed == null: liet ke moi item, khong loc. Dung khi con dang dem bien
        // tim kiem tho, truoc khi IIP va EP duoc ap.
        double peuS = sp.peu();
        if (peuS <= 0) return;
        boolean tighten = sp.peuAttainedAtFirst();
        int p1 = sp.firstExIndex();
        double remP1 = sp.sa.remUtil[p1];

        // Vi tri hop le dau tien cua tung item mo rong trong chuoi nay.
        Map<Integer, Integer> firstPos = new HashMap<>();
        for (Ins in : sp.exList) {
            if (iExtension) {
                // I-Extension: item phai nam trong CUNG itemset voi vi tri mo rong,
                // va o sau no.
                int es = sp.sa.elemStart[in.exIndex];
                for (int i = in.exIndex + 1; i < sp.sa.len && sp.sa.elemStart[i] == es; i++) {
                    int it = sp.sa.item[i];
                    if (allowed != null && !allowed.contains(it)) continue;
                    Integer cur = firstPos.get(it);
                    if (cur == null || i < cur) firstPos.put(it, i);
                }
            } else {
                // S-Extension: item phai nam trong itemset SAU itemset cua vi tri mo rong.
                int es = sp.sa.elemStart[in.exIndex];
                int i = in.exIndex + 1;
                while (i < sp.sa.len && sp.sa.elemStart[i] == es) i++;
                for (; i < sp.sa.len; i++) {
                    int it = sp.sa.item[i];
                    if (allowed != null && !allowed.contains(it)) continue;
                    Integer cur = firstPos.get(it);
                    if (cur == null || i < cur) firstPos.put(it, i);
                }
            }
        }

        for (Map.Entry<Integer, Integer> e : firstPos.entrySet()) {
            double contrib = peuS;
            if (tighten) {
                int q1 = e.getValue();
                double remBeforeQ1 = (q1 - 1 >= 0) ? sp.sa.remUtil[q1 - 1] : sp.sa.seqUtility;
                double cut = remP1 - remBeforeQ1;
                if (cut > 0) contrib = peuS - cut;
                if (contrib < 0) contrib = 0;
            }
            out.merge(e.getKey(), contrib, Double::sum);
        }
    }

    /**
     * Chieu seqPro cua tien to sang seqPro cua mau da noi them mot item.
     * Utility cua mau trong mot chuoi la LON NHAT tren moi the hien, nhung moi the
     * hien deu phai giu lai vi bat ky the hien nao cung co the sinh ra mo rong tot
     * hon ve sau. Do la ly do exList giu tat ca chu khong chi giu the hien tot nhat.
     */
    private List<SeqPro> project(List<SeqPro> proj, int item, boolean iExtension) {
        List<SeqPro> out = new ArrayList<>();
        for (SeqPro sp : proj) {
            int[] pos = sp.sa.itemIndices.get(item);
            if (pos == null) continue;

            // Voi moi vi tri cua item, giu the hien co acu LON NHAT trong so cac
            // the hien cua tien to co the noi dai toi vi tri do.
            List<Ins> next = null;
            for (int q : pos) {
                double best = -1;
                for (Ins in : sp.exList) {
                    if (iExtension) {
                        if (q > in.exIndex && sp.sa.sameElement(q, in.exIndex)) {
                            if (in.acu > best) best = in.acu;
                        }
                    } else {
                        if (sp.sa.elemStart[q] > sp.sa.elemStart[in.exIndex]) {
                            if (in.acu > best) best = in.acu;
                        }
                    }
                }
                if (best >= 0) {
                    if (next == null) next = new ArrayList<>();
                    next.add(new Ins(best + sp.sa.util[q], q));
                }
            }
            if (next != null && !next.isEmpty()) out.add(new SeqPro(sp.sa, next));
        }
        return out;
    }

    private void checkCancelled() {
        if (cancelled) throw new CancellationSignal();
    }
}
