import algorithms.*;
import common.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Automated experiment runner for the Pre-HUSPM-Adaptive framework
 * (7 scenarios SC1-SC7 matching draftPaper.pdf).
 *
 * Running from IntelliJ IDEA:
 *  1. Open Code_PC as the project root.
 *  2. Ensure "datasets" and "results" directories exist in the working directory.
 *  3. Run > Run 'AutomatedExperimentRunner.main()'.
 *  4. For dense datasets (SIGN, LEVIATHAN, ...) set VM options "-Xmx8g".
 *
 * Program Arguments to pick scenarios:
 *   ALL                  - run all 7 scenarios (default if no args).
 *   SC1 SC3              - run SC1 and SC3.
 *   SC1,SC2,SC7          - comma syntax is equivalent.
 *   TEST                 - switch dataset to paper (4 chuỗi, smoke-test < 1s).
 *   TEST SC1             - combine: smoke-test on SC1 only.
 *
 * PRODUCTION mode dùng 6 dataset chuẩn (Table 4 paper): SIGN, LEVIATHAN, FIFA,
 *   KOSARAK, BMS1, BIBLE. paper dataset KHÔNG dùng cho thực nghiệm.
 *
 * SC1 - Static baseline on D_init (50%) sweeping Su.
 * SC2 - Incremental: D_init=50% + 5 batches x 10% UNIFORM.
 * SC3 - Su sensitivity on a 3-dataset subset.
 * SC4 - LUT_dyn sensitivity (Sl/Su for Pre-HUSPM; Delta0, alpha for Adaptive).
 * SC5 - Batch strategy grid (K x strategy in {EVEN, INC, DEC, BURST}).
 * SC6 - Mixed Insert/Delete/Modify 70:15:15 (Adaptive self-compare).
 * SC7 - Ablation: proposed_full vs no_block_index vs no_IUB vs Pre-HUSPM ref.
 *
 * Each scenario writes one CSV file in results/ using the ExperimentMetrics format.
 * Before launching any individual mining call, the runner announces
 * "-> running ALGORITHM/dataset/op (heapFreeMB=...)" so progress is visible.
 * Any OutOfMemoryError or RuntimeException in a single mining call is caught,
 * logged and the run continues - the offending row is written with runtime=-1.
 */
public class AutomatedExperimentRunner {

    // ------------------------------------------------------------------
    // GENERAL CONFIG
    // ------------------------------------------------------------------
    /**
     * Where the shared databases live. They are not kept in this repository; fetch
     * them with fetch-datasets.ps1 (or .sh), which pins a release of
     * github.com/tran-minh-thai/huspm-datasets and verifies every file against its
     * SHA-256 manifest.
     */
    public static final String DATASET_DIR = "datasets/";

    /**
     * The worked example from the manuscript. Four short sequences, used by TEST mode
     * as a smoke test. It belongs to this study rather than to the shared collection,
     * so it stays in the repository.
     */
    public static final String EXAMPLE_DIR = "examples/";
    public static final String RESULTS_DIR = System.getProperty("results.dir", "results/");
    public static final boolean ENABLE_PATTERN_EXPORT = false; // disable for bulk runs
    /** Chế độ chạy lại CHỈ biến thể Adaptive (bỏ baseline) - dùng khi chỉ runtime Adaptive đổi
     *  (vd sau khi tắt EUCS). Merge số mới vào CSV cũ. Bật bằng -Dadaptive.only=true. */
    public static final boolean ADAPTIVE_ONLY = Boolean.getBoolean("adaptive.only");
    public static final int REPETITIONS = 1;
    public static final long[] SEEDS = {42L, 1337L, 2024L, 31415L, 271828L};

    // Default algorithm parameters
    // QUAN TRỌNG: Pre-HUSPM (Sl/Su) và Adaptive (Sl_min/Su) chia SẺ chung tỉ lệ Sl
    // → BẮT BUỘC dùng cùng 1 constant để tránh drift gây so sánh bất công bằng.
    public static final double DEFAULT_SL_MIN_RATIO = 0.8;
    /** Alias đảm bảo Pre-HUSPM dùng đúng Sl ratio như Adaptive. KHÔNG sửa độc lập. */
    public static final double DEFAULT_PRE_HUSPM_SL_RATIO = DEFAULT_SL_MIN_RATIO;
    /** WP2.5 - bai goi la kappa; ten cu trong code la DEFAULT_KAPPA. Ghi de: -Dkappa=... */
    public static final double DEFAULT_KAPPA  = Double.parseDouble(System.getProperty("kappa", "0.5"));
    public static final double DEFAULT_DELTA0 = Double.parseDouble(System.getProperty("delta0", "1e-4"));
    /** WP2.1 - B_sz, ghi de: -Dblock.size=... */
    public static final int DEFAULT_BLOCK_SIZE = Integer.getInteger("block.size", 100);
    /** WP2.3 - so lan lap moi cau hinh de lay do lech chuan (khu nhieu JIT/GC). */
    public static final int REPEATS = Integer.getInteger("repeats", 3);
    /** WP2.2 - so seed thuc su duoc dung (<= SEEDS.length). Ghi de: -Dseeds=5 */
    public static final int SEED_COUNT = Math.min(
            Integer.getInteger("seeds", 1), 5);

    // === CHE DO DO BO NHO ===
    //
    // "used" (mac dinh): dinh used-heap lay mau 50 ms, KE CA rac chua thu. Dai luong nay
    //   phu thuoc lich GC, phu thuoc -Xmx, va phu thuoc thuat toan nao chay TRUOC trong
    //   cung JVM. Project 02 da truy ra mot con so 23.3 GB hoan toan la artifact kieu do.
    //
    // "live": ep thu gom day du tai cac moc roi doc used-heap ngay sau. Do la tap song
    //   that su phai giu. Doi lai, ep GC lam hong thoi gian, nen runtime cua lan chay nay
    //   KHONG duoc dung: no xuat ra -3.
    //
    // Ghi de: -Dmem.mode=live -Dmem.interval.ms=1000
    public static final String MEM_MODE = System.getProperty("mem.mode", "used")
            .trim().toLowerCase(java.util.Locale.ROOT);
    public static final boolean MEM_LIVE = "live".equals(MEM_MODE);
    public static final long MEM_SAMPLE_MS = Long.getLong("mem.interval.ms", 1000L);

    /**
     * Chi chay MOT thuat toan trong tien trinh nay. Che do do bo nho yeu cau moi arm mot
     * JVM rieng, vi neu khong thi heap da bi arm truoc nong rong. Ghi de: -Dalgo.only=USpan
     * Rong = chay tat ca (hanh vi cu).
     */
    public static final String ALGO_ONLY = System.getProperty("algo.only", "").trim();

    /** Luat kich hoat rescan dang dung, ghi vao CSV de truy nguoc. */
    public static final String ADAPT_TRIGGER = System.getProperty("adapt.trigger", "iaumax");

    /** Chi chay MOT dataset. Ghi de: -Ddataset.only=SIGN. Rong = tat ca. */
    public static final String DATASET_ONLY = System.getProperty("dataset.only", "").trim();

    public static boolean datasetEnabled(String ds) {
        return DATASET_ONLY.isEmpty() || DATASET_ONLY.equalsIgnoreCase(ds);
    }

    /** true neu thuat toan nay duoc phep chay trong tien trinh hien tai. */
    public static boolean algoEnabled(String algo) {
        return ALGO_ONLY.isEmpty() || ALGO_ONLY.equalsIgnoreCase(algo);
    }

    // === NGU CANH CUA MOT LAN DO ===
    //
    // Truoc day khong dong ket qua nao ghi lai seed hay so hieu lan lap da sinh ra
    // no, nen khong the tinh trung binh va do lech chuan, va cung khong the truy
    // nguoc mot dong ve cau hinh cua no. Ca hai phan bien deu yeu cau dieu nay.
    //
    // Hai nguon bien thien KHAC NHAU, khong duoc gop:
    //   seed   doi CAU HINH: lo nao chua chuoi nao, thao tac nao xay ra khi nao.
    //          Chi co tac dung o noi that su co ngau nhien (xem ghi chu tung kich ban).
    //   repeat lap lai DUNG cung mot cau hinh de do nhieu cua JIT va GC.
    // Do lech chuan bao cao trong bai la cua repeat; seed dung de kiem tra ket luan
    // khong phu thuoc mot cach chia lo cu the.
    private static long currentSeed = SEEDS[0];
    private static int currentRepeat = 0;

    private static void setRunContext(long seed, int repeat) {
        currentSeed = seed;
        currentRepeat = repeat;
    }

    /** Cac seed thuc su duoc dung trong lan chay nay. */
    private static long[] activeSeeds() {
        long[] out = new long[SEED_COUNT];
        System.arraycopy(SEEDS, 0, out, 0, SEED_COUNT);
        return out;
    }
    public static final double D_INIT_RATIO = 0.5; // 50% init per paper

    // === TIMEOUT PER ALGORITHM RUN ===
    // Mỗi lời gọi measure() bị bao trong ExecutorService timeout. Khi timeout: cancel
    // Adaptive flag, future.cancel(true), ghi opType "_OT", runtime_ms = -2 (phân biệt
    // với -1 = FAILED).
    //
    // NGÂN SÁCH 120 PHÚT (bản nộp dùng 60). Lý do không phải là nới tay mà là GIỮ NGUYÊN
    // ngân sách tính toán khi đổi máy đo. Máy đo mới (Dell XPS, i7-10750H, 6 nhân /
    // 12 luồng, 2.60 GHz, 64 GB, Windows) chậm hơn máy ghi trong bản nộp (Apple M5)
    // khoảng 2.1x đến 3.5x trên cùng khối lượng khai phá tĩnh - đo bằng
    // experiments.MachineCalibration. Giữ nguyên 60 phút trên máy chậm hơn 2-3.5 lần
    // tức là SIẾT ngân sách xuống còn 17 đến 29 phút theo thang máy cũ, làm baseline bị
    // đánh OT nhiều hơn và vô tình có lợi cho phương pháp đề xuất - đúng thứ phản biện
    // sẽ bắt. Muốn bảo toàn đúng ngân sách cũ thì cần 126 đến 210 phút; chọn 120 phút
    // là mức vẫn CHẶT HƠN bản nộp về mặt tính toán mà thời gian chạy còn kiểm soát được.
    // Giá trị dùng thực tế phải được ghi vào bài và vào manifest của mỗi lần chạy.
    public static final long TIMEOUT_MINUTES = Long.getLong("timeout.min", 120);
    public static final long TIMEOUT_MS = TIMEOUT_MINUTES * 60_000L;

    // ------------------------------------------------------------------
    // BATCH SPLIT
    // ------------------------------------------------------------------
    public enum BatchSplitMode { FILE_MARKERS, EVEN, INCREASING, DECREASING, CUSTOM, RANDOM, BURST }

    public static class BatchSplitSpec {
        public final BatchSplitMode mode;
        public final int numBatches;
        public final double[] customRatios;
        public final long seed;

        public BatchSplitSpec(BatchSplitMode mode, int numBatches, double[] customRatios, long seed) {
            this.mode = mode;
            this.numBatches = Math.max(1, numBatches);
            this.customRatios = customRatios;
            this.seed = seed;
        }
        public static BatchSplitSpec fileMarkers()             { return new BatchSplitSpec(BatchSplitMode.FILE_MARKERS, 0, null, 0L); }
        public static BatchSplitSpec even(int k)               { return new BatchSplitSpec(BatchSplitMode.EVEN, k, null, 0L); }
        public static BatchSplitSpec increasing(int k)         { return new BatchSplitSpec(BatchSplitMode.INCREASING, k, null, 0L); }
        public static BatchSplitSpec decreasing(int k)         { return new BatchSplitSpec(BatchSplitMode.DECREASING, k, null, 0L); }
        public static BatchSplitSpec custom(double... ratios)  { return new BatchSplitSpec(BatchSplitMode.CUSTOM, ratios.length, ratios, 0L); }
        public static BatchSplitSpec random(int k, long seed)  { return new BatchSplitSpec(BatchSplitMode.RANDOM, k, null, seed); }
        public static BatchSplitSpec burst(int k, long seed)   { return new BatchSplitSpec(BatchSplitMode.BURST, k, null, seed); }

        @Override public String toString() {
            switch (mode) {
                case CUSTOM:    return "CUSTOM" + Arrays.toString(customRatios);
                case RANDOM:    return "RANDOM(" + numBatches + ",s=" + seed + ")";
                case BURST:     return "BURST(" + numBatches + ",s=" + seed + ")";
                case FILE_MARKERS: return "FILE";
                default:        return mode.name() + "(" + numBatches + ")";
            }
        }
    }

    /** Per-dataset configuration. */
    static class DatasetConfig {
        final String name;
        final double defaultSu;
        final double[] suSweep;
        DatasetConfig(String name, double defaultSu, double[] suSweep) {
            this.name = name; this.defaultSu = defaultSu; this.suSweep = suSweep;
        }
    }

    /**
     * Datasets to run - PRODUCTION SET (mapping draftPaper.pdf Table 4):
     *   - SIGN, LEVIATHAN, FIFA: dense / very-long-sequence datasets.
     *   - KOSARAK, ONLINE_RETAIL_II_BEST, E_SHOP: sparse / short-sequence datasets.
     * "paper" dataset (4 chuỗi) chỉ dùng để smoke-test trong TEST mode.
     *
     * Su mỗi dataset theo Table 4 + Mục 4.6.3 (KB3 sweep S_u ∈ {0.05, 0.1, 0.2, 0.5}%).
     * Heap: với LEVIATHAN/SIGN dùng VM options "-Xmx8g" trở lên.
     *
     * Bật/tắt từng dataset theo nhu cầu (commit-out với "//") - không cần xoá.
     */
    private static final List<DatasetConfig> DATASETS_PRODUCTION = List.of(
            new DatasetConfig("SIGN",       0.025,  new double[]{0.02, 0.025, 0.030, 0.032}),
            // BMS1 cực thưa (~48k item, avg 2.4 itemset/chuỗi). Đo Adaptive/D_init:
            // 0.014→4s/20pat, 0.013→27s/23pat, 0.012→>180s (cand 92M), 0.011→>180s.
            // Candidate bùng nổ ~7× mỗi bước 0.001. Default 0.014 để SC2/SC5 incremental
            // (lặp nhiều call) không quá lâu; 0.013 là điểm baseline bắt đầu OT.
            new DatasetConfig("BMS1",       0.014, new double[]{0.013, 0.014, 0.015, 0.016}),
            // BIBLE (text, ~10.7k item, avg 22 itemset/chuỗi) - rất "ngoan": candidate
            // gần như phẳng. Đo Adaptive/D_init: 0.045→17pat/1s, 0.03→103pat/1s,
            // 0.02→343pat/2s. Hạ ngưỡng để mẫu phong phú hơn mà vẫn <2s.
            new DatasetConfig("BIBLE",      0.025, new double[]{0.020, 0.025, 0.030, 0.035}),
            new DatasetConfig("LEVIATHAN",  0.060,  new double[]{0.055, 0.060, 0.065, 0.070}),
            // FIFA CỰC DÀY (avg 36.7 itemset/chuỗi → đệ quy sâu). Mẫu HUSP rất hiếm:
            // Su≥0.06 chỉ cho 0-1 large (vô nghĩa). Đo Adaptive/D_init vùng có mẫu:
            // 0.060→1 large/122s, 0.055→5/159s, 0.050→18/222s, 0.045→41 large/305s.
            // Candidate tăng tuyến tính (~6k/s) → mọi Su xong trong <90 phút. Chọn
            // {0.045..0.06} để large pattern THỰC SỰ có nghĩa (1→5→18→41).
            new DatasetConfig("FIFA",       0.05,  new double[]{0.045, 0.050, 0.055, 0.060}),
            // KOSARAK siêu thưa (~41k item, 495k chuỗi/D_init). Đo thực tế: Adaptive
            // 0.05→9s, 0.04→18s, 0.03→34s, 0.025→58s, 0.02→>120s. Baseline chậm hơn
            // 10-100×. Su≥0.025 để toàn bộ thuật toán hoàn thành/so sánh hợp lý;
            // 0.025 là điểm baseline bắt đầu OT (minh chứng ưu thế scaling).
            new DatasetConfig("KOSARAK",    0.03, new double[]{0.025, 0.030, 0.040, 0.050})

    );

    /** Dataset rút gọn cho smoke-test (paper = 4 chuỗi, < 1s). */
    private static final List<DatasetConfig> DATASETS_TEST = List.of(
            new DatasetConfig("paper", 0.001, new double[]{0.0005, 0.001, 0.002, 0.005})
    );

    /** Chọn dataset thực tế: TEST mode hay PRODUCTION mode. */
    private static List<DatasetConfig> DATASETS = DATASETS_PRODUCTION;
    /** Resolved at start-up: examples/ in TEST mode, datasets/ otherwise. */
    private static String datasetDir = DATASET_DIR;

    /**
     * Subset 3 dataset cho SC3, SC4, SC6 (theo paper Mục 4.6.3-4.6.6).
     * Chọn 3 dataset dày + thưa đại diện. paper KHÔNG bao giờ thuộc subset này.
     */
    private static final Set<String> SUBSET_3 = new HashSet<>(Arrays.asList(
            "SIGN", "LEVIATHAN", "KOSARAK"));

    /**
     * Tập đại diện cho ABLATION C5 (incremental, nặng) trong SC7.
     */
    private static final Set<String> C5_ABLATION_SET = new HashSet<>(Arrays.asList(
            "BMS1", "SIGN", "KOSARAK"));

    // ==================================================================
    // ENTRY POINT
    // ==================================================================
    public static void main(String[] args) throws IOException {
        // === PREFLIGHT CHE DO DO BO NHO ===
        // Ket qua live va ket qua used do HAI DAI LUONG KHAC NHAU va chi khac nhau o mot
        // cot, nen rat de bi tron rooi so sanh nham. Project 02 giai bang cach bat launcher
        // ghi vao thu muc rieng; lam y het o day.
        if (MEM_LIVE) {
            String rd = RESULTS_DIR.replace('\\', '/').toLowerCase(java.util.Locale.ROOT);
            if (!rd.contains("mem")) {
                System.err.println("DUNG: -Dmem.mode=live phai ghi vao thu muc co chua 'mem' "
                        + "trong ten, vd -Dresults.dir=results/run-xxx/mem/. Thu muc hien tai: "
                        + RESULTS_DIR);
                System.exit(2);
            }
            if (ALGO_ONLY.isEmpty()) {
                System.err.println("DUNG: che do live yeu cau MOI ARM MOT JVM RIENG "
                        + "(-Dalgo.only=<ten>). Chay nhieu arm chung JVM thi heap da bi arm "
                        + "truoc nong rong - dung loi ma che do live sinh ra de tranh. "
                        + "Dung run.ps1 -MemMode live de no tu lap qua tung arm.");
                System.exit(2);
            }
            System.out.println("### LIVE HEAP: ep thu gom moi " + MEM_SAMPLE_MS
                    + " ms | arm = " + ALGO_ONLY);
            System.out.println("### runtime_ms xuat -3 (GC lam nhieu); gia tri that o "
                    + "wall_ms_perturbed. KHONG dung lam so hieu nang.");
        }

        new File(RESULTS_DIR).mkdirs();

        // Phát hiện cờ TEST trong args → dùng dataset rút gọn (paper, 4 chuỗi, < 1s).
        // Truyền "TEST" hoặc "TEST SC1" để smoke-test, ALL/SC* (mặc định) dùng production.
        boolean testMode = false;
        if (args != null) for (String a : args) {
            for (String t : a.split("[,\\s]+")) {
                if (t.equalsIgnoreCase("TEST") || t.equalsIgnoreCase("SMOKE")) { testMode = true; break; }
            }
        }
        DATASETS = testMode ? DATASETS_TEST : DATASETS_PRODUCTION;
        datasetDir = testMode ? EXAMPLE_DIR : DATASET_DIR;

        Set<String> scenarios = parseScenarios(args);
        System.out.println("=== HUSPM-Adapt-Prelarge AUTOMATED EXPERIMENT RUNNER ===");
        System.out.println("Mode: " + (testMode ? "TEST (paper dataset)" : "PRODUCTION")
                + " | Datasets: " + DATASETS.size() + " | Scenarios: " + scenarios);
        System.out.println("Heap max: " + (Runtime.getRuntime().maxMemory() / (1024 * 1024)) + " MB");

        long t0 = System.currentTimeMillis();
        if (scenarios.contains("SC1")) runSC1_StaticBaseline();
        if (scenarios.contains("SC2")) runSC2_IncrementalSequence();
        if (scenarios.contains("SC3")) runSC3_SensitivitySu();
        if (scenarios.contains("SC4")) runSC4_SensitivitySl();
        if (scenarios.contains("SC5")) runSC5_BatchStrategyGrid();
        if (scenarios.contains("SC6")) runSC6_InsertDeleteModify();
        if (scenarios.contains("SC7")) runSC7_Ablation();
        System.out.printf("=== ALL SCENARIOS DONE (%.1fs) ===%n",
                (System.currentTimeMillis() - t0) / 1000.0);
    }

    private static Set<String> parseScenarios(String[] args) {
        Set<String> all = new LinkedHashSet<>(Arrays.asList("SC1","SC2","SC3","SC4","SC5","SC6","SC7"));
        if (args == null || args.length == 0) return all;
        Set<String> chosen = new LinkedHashSet<>();
        for (String a : args) {
            for (String token : a.split("[,\\s]+")) {
                String t = token.trim().toUpperCase(Locale.ROOT);
                if (t.equals("ALL")) return all;
                // Aliases for legacy KB naming
                if (t.equals("KB1")) t = "SC1";
                else if (t.equals("KB2")) t = "SC2";
                else if (t.equals("KB3")) t = "SC3";
                else if (t.equals("KB4")) t = "SC4";
                else if (t.equals("KB5")) t = "SC5";
                else if (t.equals("KB6")) t = "SC6";
                else if (t.equals("KB7")) t = "SC7";
                if (all.contains(t)) chosen.add(t);
            }
        }
        return chosen.isEmpty() ? all : chosen;
    }

    // ==================================================================
    // SC1 - STATIC BASELINE ON D_INIT (50%)
    // ==================================================================
    private static void runSC1_StaticBaseline() throws IOException {
        System.out.println("\n--- [SC1] Static baseline on D_init (50%) ---");
        try (ResultCollector col = new ResultCollector(RESULTS_DIR + "SC1_static.csv")) {
            for (DatasetConfig ds : DATASETS) {
                if (!datasetEnabled(ds.name)) continue;
                List<Sequence> full = DatasetLoader.loadDataset(datasetDir + ds.name + "_seq.txt",
                                                                datasetDir + ds.name + "_eui.txt");
                if (full.isEmpty()) continue;
                List<Sequence> dInit = headPercent(full, D_INIT_RATIO);
                double totalUtil = sumUtility(dInit);
                String[] algos = {"USpan", "HUSP-ULL", "HUSP-SP", "IncUSP-Miner+", "Pre-HUSPM", "Adaptive-Full"};
                // SC1 khong co ngau nhien nao: khong chia lo, khong sinh thao tac. Seed
                // khong doi duoc gi, nen chi lap lai cung cau hinh de do nhieu JIT/GC.
                for (double su : ds.suSweep) {
                    for (int r = 0; r < REPEATS; r++) {
                    setRunContext(SEEDS[0], r);
                    for (String algo : algos) {
                        ExperimentMetrics m = runSingle(algo, ds.name, dInit, totalUtil, su, 0, "SC1_su=" + su);
                        if (m != null) col.log(m);
                        // GC giữa các thuật toán: heap mà algo vừa rồi giữ
                        // (instance đã hết scope trong runSingle) phải được thu hồi
                        // trước khi algo sau cấp phát; nếu thuật toán trước OOM,
                        // forceFullGC() đảm bảo budget heap "sạch" cho thuật toán sau.
                        forceFullGC();
                    }
                    }
                }
            }
        }
    }

    // ==================================================================
    // SC2 - INCREMENTAL SEQUENCE: D_init=50% + 5 batch x 10% UNIFORM
    // ==================================================================
    private static void runSC2_IncrementalSequence() throws IOException {
        System.out.println("\n--- [SC2] Incremental sequence (5 batch x 10% UNIFORM) ---");
        try (ResultCollector col = new ResultCollector(RESULTS_DIR + "SC2_incremental.csv")) {
            for (DatasetConfig ds : DATASETS) {
                if (!datasetEnabled(ds.name)) continue;
                List<Sequence> full = DatasetLoader.loadDataset(datasetDir + ds.name + "_seq.txt",
                                                                datasetDir + ds.name + "_eui.txt");
                if (full.isEmpty()) continue;
                List<Sequence> dInit = headPercent(full, D_INIT_RATIO);
                List<Sequence> rest = tailFrom(full, dInit.size());

                // SC2 chia lo DEU va TUAN TU theo thu tu file, tuc hoan toan tat dinh:
                // voi mot seed thi nam seed cho ra dung mot cach chia, va "chay nam seed"
                // se chi la lap lai mot cau hinh - do nhieu do dac chu khong do bien thien
                // cua cach chia lo. Neu muon bien thien that thi phai hoan vi dong du lieu
                // gia tang, va do la mot kich ban KHAC voi kich ban mo ta trong bai.
                //
                // Vi vay: mot seed giu nguyen dinh nghia cu; tu hai seed tro len thi dong
                // gia tang duoc hoan vi theo seed, va dieu do phai duoc noi ro trong bai.
                long[] seeds = activeSeeds();
                for (long seed : seeds) {
                    List<Sequence> stream = rest;
                    if (seeds.length > 1) {
                        stream = new ArrayList<>(rest);
                        Collections.shuffle(stream, new Random(seed));
                    }
                    List<List<Sequence>> batches = splitByWeights(stream, equalWeights(5));
                    for (int r = 0; r < REPEATS; r++) {
                        setRunContext(seed, r);
                        runIncrementalScenario(ds.name, ds.defaultSu, dInit, batches, col, "SC2");
                    }
                }
            }
        }
    }

    // ==================================================================
    // SC3 - SENSITIVITY ON Su (3-dataset subset)
    // ==================================================================
    private static void runSC3_SensitivitySu() throws IOException {
        System.out.println("\n--- [SC3] Su sensitivity ---");
        try (ResultCollector col = new ResultCollector(RESULTS_DIR + "SC3_su.csv")) {
            String[] algos = {"USpan", "HUSP-ULL", "HUSP-SP", "IncUSP-Miner+", "Pre-HUSPM", "Adaptive-Full"};
            for (DatasetConfig ds : DATASETS) {
                if (!datasetEnabled(ds.name)) continue;
                if (!SUBSET_3.contains(ds.name)) continue;
                List<Sequence> full = DatasetLoader.loadDataset(datasetDir + ds.name + "_seq.txt",
                                                                datasetDir + ds.name + "_eui.txt");
                if (full.isEmpty()) continue;
                double totalUtil = sumUtility(full);
                // Nhu SC1: khong co ngau nhien, chi lap lai de do nhieu do dac.
                for (double su : ds.suSweep) {
                    for (int r = 0; r < REPEATS; r++) {
                        setRunContext(SEEDS[0], r);
                        for (String algo : algos) {
                            ExperimentMetrics m = runSingle(algo, ds.name, full, totalUtil, su, 0, "SC3_su=" + su);
                            if (m != null) col.log(m);
                            forceFullGC(); // Cleanup heap giữa các thuật toán
                        }
                    }
                }
            }
        }
    }

    // ==================================================================
    // SC4 - ĐỘ NHẠY NGƯỠNG PRE-LARGE (Sl/Su): Pre-HUSPM vs Adaptive
    //        (minh hoạ dilemma bùng-nổ-pre-large + ưu thế pruning; KHÔNG còn sweep Delta0/alpha)
    // ==================================================================
    private static void runSC4_SensitivitySl() throws IOException {
        System.out.println("\n--- [SC4] Pre-large threshold (Sl) sensitivity: Pre-HUSPM vs Adaptive ---");
        double[] slRatios = {0.3, 0.5, 0.7, 0.9};

        try (ResultCollector col = new ResultCollector(RESULTS_DIR + "SC4_sl_sensitivity.csv")) {
            for (DatasetConfig ds : DATASETS) {
                if (!datasetEnabled(ds.name)) continue;
                if (!SUBSET_3.contains(ds.name)) continue;
                List<Sequence> full = DatasetLoader.loadDataset(datasetDir + ds.name + "_seq.txt",
                                                                datasetDir + ds.name + "_eui.txt");
                if (full.isEmpty()) continue;
                double totalUtil = sumUtility(full);

                // (a) Sl/Su sensitivity - chạy SONG SONG Pre-HUSPM + Adaptive với
                //     CÙNG slRatio mỗi iteration để so sánh apple-to-apple công bằng.
                //     Trước đây Pre-HUSPM sweep slRatios={0.3..0.9} còn Adaptive
                //     bị FIX cứng ở DEFAULT_SL_MIN_RATIO → không thể so sánh trực tiếp.
                //
                //     Mỗi instance được scope-hẹp trong block → ra khỏi block là
                //     unreachable; forceFullGC() ngay sau thu hồi heap.
                // Khong co ngau nhien; chi lap lai de do nhieu JIT/GC.
                for (double slRatio : slRatios) {
                  for (int r = 0; r < REPEATS; r++) {
                    setRunContext(SEEDS[0], r);
                    // --- Pre-HUSPM @ slRatio ---
                    if (!ADAPTIVE_ONLY) {
                        String tag = "Pre-HUSPM[sl=" + slRatio + "]";
                        PreHUSPMAlgorithm.PreHUSPMAlgo pre = new PreHUSPMAlgorithm.PreHUSPMAlgo(
                                ds.defaultSu, slRatio, ENABLE_PATTERN_EXPORT);
                        announce(tag, ds.name, "SC4", 0);
                        ExperimentMetrics m = measure(tag, ds.name, 0, "SC4",
                                () -> pre.runInitialMining(full, totalUtil),
                                () -> pre.candidateCount, () -> pre.patternCount, null, null);
                        attachPreHuspm(m, pre);
                        col.log(m);
                        forceFullGC();
                    }
                    // --- Adaptive @ SAME slRatio (giữ default alpha+delta0) ---
                    {
                        String tag = "Adaptive[sl=" + slRatio + "]";
                        PreHUSPMAdaptiveAlgorithm.PreHUSPMAdaptiveAlgo adp = new PreHUSPMAdaptiveAlgorithm.PreHUSPMAdaptiveAlgo(
                                ds.defaultSu, DEFAULT_KAPPA, DEFAULT_DELTA0, slRatio,
                                PreHUSPMAdaptiveAlgorithm.PreHUSPMAdaptiveAlgo.AblationMode.FULL,
                                ENABLE_PATTERN_EXPORT);
                        announce(tag, ds.name, "SC4", 0);
                        ExperimentMetrics m = measure(tag, ds.name, 0, "SC4",
                                () -> adp.runInitialMining(full, totalUtil),
                                () -> adp.candidateCount, () -> adp.patternCount,
                                () -> adp.lutDynValue, () -> adp.cubValue, () -> adp.cubTriggeredRescan,
                                null, () -> adp.prunedValueBound,
                            () -> adp.prelargeCount, () -> adp.uutValue, () -> adp.sftValue, adp::cancel);
                        attachAdaptive(m, adp);
                        col.log(m);
                        forceFullGC();
                    }
                  }
                }

                // (b) ĐÃ BỎ sweep (delta0, alpha): chạy trên runInitialMining nên 2 tham số
                //     này KHÔNG có tác dụng (init đặt lutDynValue = TSU·Sl_min trực tiếp, không
                //     tính δ_D) → kết quả vô nghĩa. C2 cũng đã de-emphasize. Chỉ giữ SC4a
                //     (độ nhạy slRatio: minh hoạ bùng nổ pre-large + ưu thế pruning của Adaptive).
            }
        }
    }

    // ==================================================================
    // SC5 - BATCH STRATEGY GRID (K x strategy)
    // ==================================================================
    private static void runSC5_BatchStrategyGrid() throws IOException {
        System.out.println("\n--- [SC5] Batch strategy grid ---");
        int[] kList = {3, 5, 10};
        BatchSplitMode[] strategies = {BatchSplitMode.EVEN, BatchSplitMode.INCREASING,
                                        BatchSplitMode.DECREASING, BatchSplitMode.BURST};

        try (ResultCollector col = new ResultCollector(RESULTS_DIR + "SC5_batch_strategy.csv")) {
            for (DatasetConfig ds : DATASETS) {
                if (!datasetEnabled(ds.name)) continue;
                if (!SUBSET_3.contains(ds.name)) continue;
                List<Sequence> full = DatasetLoader.loadDataset(datasetDir + ds.name + "_seq.txt",
                                                                datasetDir + ds.name + "_eui.txt");
                if (full.isEmpty()) continue;
                List<Sequence> dInit = headPercent(full, D_INIT_RATIO);
                List<Sequence> rest = tailFrom(full, dInit.size());

                // Trong bon chien luoc thi chi BURST dung den seed; EVEN, INCREASING va
                // DECREASING deu tat dinh. Vong seed vi vay chi sinh cau hinh khac nhau o
                // BURST - voi ba chien luoc kia no tro thanh lap lai, nen ta bo qua de
                // khong nhan ba lan khoi luong ma khong thu duoc thong tin nao.
                for (int k : kList) for (BatchSplitMode strat : strategies) {
                    boolean seedMatters = (strat == BatchSplitMode.BURST
                                        || strat == BatchSplitMode.RANDOM);
                    long[] seeds = seedMatters ? activeSeeds() : new long[]{SEEDS[0]};
                    for (long seed : seeds) {
                        BatchSplitSpec spec = new BatchSplitSpec(strat, k, null, seed);
                        double[] weights = computeWeights(spec);
                        List<List<Sequence>> batches = splitByWeights(rest, weights);
                        String tag = "SC5_K=" + k + "_strat=" + strat.name();
                        System.out.println(">> [SC5] " + ds.name + " " + tag
                                + " seed=" + seed + " -> sizes=" + batchSizes(batches));
                        for (int r = 0; r < REPEATS; r++) {
                            setRunContext(seed, r);
                            runIncrementalScenario(ds.name, ds.defaultSu, dInit, batches, col, tag);
                        }
                    }
                }
            }
        }
    }

    // ==================================================================
    // SC6 - MIXED I/D/M 70:15:15 (Adaptive self-compare)
    // ==================================================================
    /** Mot thao tac trong chuoi hon hop cua SC6. */
    private enum OpKind { INS, DEL, MOD }

    private static final class Op {
        final OpKind kind;
        final int iter;
        final List<Sequence> a;   // INS: lo chen | DEL: chuoi bi xoa | MOD: chuoi cu
        final List<Sequence> b;   // MOD: chuoi moi
        Op(OpKind kind, int iter, List<Sequence> a, List<Sequence> b) {
            this.kind = kind; this.iter = iter; this.a = a; this.b = b;
        }
        String tag() {
            switch (kind) {
                case INS: return "SC6_INS";
                case DEL: return "SC6_DEL";
                default:  return "SC6_MOD";
            }
        }
    }

    /**
     * Sinh chuoi thao tac hon hop MOT LAN, de moi arm phat lai DUNG chuoi do.
     *
     * Truoc day chuoi thao tac duoc sinh truc tiep trong vong lap do, nen khong the
     * them arm thu hai: arm sau se rut ra mot chuoi thao tac khac tu cung bo sinh
     * ngau nhien, va hai arm khong con so sanh duoc voi nhau.
     */
    private static List<Op> buildSC6Plan(List<Sequence> dInit, List<Sequence> rest,
                                         int opCount, int opSize, Random rnd) {
        List<Op> plan = new ArrayList<>();
        List<Sequence> pool = new ArrayList<>(rest);
        List<Sequence> cur = new ArrayList<>(dInit);
        for (int i = 0; i < opCount; i++) {
            double roll = rnd.nextDouble();
            int iter = i + 1;
            if (roll < 0.70) {
                List<Sequence> delta = new ArrayList<>();
                for (int j = 0; j < opSize && !pool.isEmpty(); j++) delta.add(pool.remove(0));
                if (delta.isEmpty()) break;
                plan.add(new Op(OpKind.INS, iter, delta, null));
                cur.addAll(delta);
            } else if (roll < 0.85) {
                if (cur.size() < opSize + 1) continue;
                List<Sequence> toDel = pickRandom(cur, opSize, rnd);
                plan.add(new Op(OpKind.DEL, iter, toDel, null));
                cur.removeAll(toDel);
            } else {
                if (cur.size() < opSize + 1 || pool.size() < opSize) continue;
                List<Sequence> oldSeqs = pickRandom(cur, opSize, rnd);
                List<Sequence> newSeqs = new ArrayList<>();
                for (int j = 0; j < opSize && !pool.isEmpty(); j++) newSeqs.add(pool.remove(0));
                plan.add(new Op(OpKind.MOD, iter, oldSeqs, newSeqs));
                cur.removeAll(oldSeqs);
                cur.addAll(newSeqs);
            }
        }
        return plan;
    }

    // ==================================================================
    // SC6 - MIXED I/D/M 70:15:15
    // ==================================================================
    /**
     * Chuoi thao tac hon hop chen / xoa / sua theo ty le 70:15:15.
     *
     * Ban nop chi bao cao phuong phap de xuat o kich ban nay, khong co moc so sanh
     * nao, nen khong the dinh luong duoc loi ich thuc te cua co che duy tri nhieu
     * thao tac (review, comment 4c). Nay chay HAI arm tren CUNG mot chuoi thao tac:
     *
     *   Adaptive-Full        duy tri gia tang
     *   Remine-from-scratch  mine lai tu dau sau MOI thao tac
     *
     * Moc so sanh dung chinh bo khai pha tinh cua khung de xuat, nen moi khoang cach
     * do duoc deu den tu viec khong phai mine lai chu khong den tu viec bo mine cua ta
     * nhanh hon cua nguoi khac.
     *
     * Runtime cong don khong luu thanh cot rieng: no la tong runtime_ms tren cac dong
     * cung arm cung dataset, tinh duoc tu du lieu tho.
     */
    private static void runSC6_InsertDeleteModify() throws IOException {
        System.out.println("\n--- [SC6] Mixed I/D/M 70:15:15 (Adaptive vs Remine-from-scratch) ---");
        try (ResultCollector col = new ResultCollector(RESULTS_DIR + "SC6_idm_mixed.csv")) {
            for (DatasetConfig ds : DATASETS) {
                if (!datasetEnabled(ds.name)) continue;
                if (!SUBSET_3.contains(ds.name)) continue;
                List<Sequence> full = DatasetLoader.loadDataset(datasetDir + ds.name + "_seq.txt",
                                                                datasetDir + ds.name + "_eui.txt");
                if (full.size() < 20) {
                    System.out.println(">> [SC6] skipping " + ds.name + " (only " + full.size() + " sequences)");
                    continue;
                }

                List<Sequence> dInit = new ArrayList<>(headPercent(full, D_INIT_RATIO));
                List<Sequence> rest  = new ArrayList<>(tailFrom(full, dInit.size()));
                double initTotalUtil = sumUtility(dInit);

                int opCount = 10;
                int opSize = Math.max(1, rest.size() / (opCount * 2));

                // Day la kich ban ma seed thuc su quan trong: no quyet dinh thao tac nao
                // xay ra khi nao va tren chuoi nao. Ket luan cua SC6 khong duoc phu thuoc
                // vao mot chuoi thao tac may man.
                for (long seed : activeSeeds()) {
                    // Seed rieng cho tung dataset, de chuoi thao tac cua dataset nay khong
                    // phu thuoc vao viec dataset nao da chay truoc no.
                    Random rnd = new Random(seed + ds.name.hashCode());
                    List<Op> plan = buildSC6Plan(dInit, rest, opCount, opSize, rnd);
                    System.out.println(">> [SC6] " + ds.name + " seed=" + seed
                            + " opSize=" + opSize + " plan=" + planSummary(plan));

                    for (int r = 0; r < REPEATS; r++) {
                        setRunContext(seed, r);
                        if (algoEnabled("Adaptive-Full"))       runSC6Arm(ds, dInit, initTotalUtil, plan, col, false);
                        if (algoEnabled("Remine-from-scratch")) runSC6Arm(ds, dInit, initTotalUtil, plan, col, true);
                    }
                    forceFullGC();
                }
            }
        }
    }

    private static String planSummary(List<Op> plan) {
        int i = 0, d = 0, m = 0;
        for (Op o : plan) {
            if (o.kind == OpKind.INS) i++;
            else if (o.kind == OpKind.DEL) d++;
            else m++;
        }
        return plan.size() + " thao tac (" + i + " chen, " + d + " xoa, " + m + " sua)";
    }

    /** Phat lai ke hoach thao tac cho mot arm. remine=true nghia la mine lai tu dau moi thao tac. */
    private static void runSC6Arm(DatasetConfig ds, List<Sequence> dInit, double initTotalUtil,
                                  List<Op> plan, ResultCollector col, boolean remine) throws IOException {
        final String label = remine ? "Remine-from-scratch" : "Adaptive-Full";

        PreHUSPMAdaptiveAlgorithm.PreHUSPMAdaptiveAlgo adp = newAdaptive(ds.defaultSu);
        announce(label, ds.name, "SC6_INIT", 0);
        ExperimentMetrics mInit = measureAdaptive(adp, label, ds.name, 0, "SC6_INIT",
                () -> adp.runInitialMining(dInit, initTotalUtil));
        col.log(mInit);
        boolean poisoned = (mInit.runtimeMs < 0);
        forceFullGC();

        List<Sequence> cur = new ArrayList<>(dInit);
        double total = initTotalUtil;

        for (Op op : plan) {
            // Trang thai CSDL tien trien giong het nhau o ca hai arm, vi ke hoach da co dinh.
            List<Sequence> after = new ArrayList<>(cur);
            switch (op.kind) {
                case INS: after.addAll(op.a); break;
                case DEL: after.removeAll(op.a); break;
                default:  after.removeAll(op.a); after.addAll(op.b); break;
            }
            final double newTotal = (op.kind == OpKind.INS) ? total + sumUtility(op.a) : sumUtility(after);

            if (poisoned) {
                col.log(skippedMetric(label, ds.name, op.iter, op.tag()));
                cur = after; total = newTotal;
                continue;
            }

            ExperimentMetrics m;
            if (remine) {
                // Mine lai tu dau: instance MOI, khong giu bat cu trang thai nao.
                final List<Sequence> snapshot = new ArrayList<>(after);
                PreHUSPMAdaptiveAlgorithm.PreHUSPMAdaptiveAlgo rm = newAdaptive(ds.defaultSu);
                announce(label, ds.name, op.tag(), op.iter);
                m = measureAdaptive(rm, label, ds.name, op.iter, op.tag(),
                        () -> rm.runInitialMining(snapshot, newTotal));
                m.rescanCount = 0;   // khong co khai niem rescan gia tang o moc nay
            } else {
                final List<Sequence> snapshot = new ArrayList<>(after);
                announce(label, ds.name, op.tag(), op.iter);
                switch (op.kind) {
                    case INS:
                        m = measureAdaptive(adp, label, ds.name, op.iter, op.tag(),
                                () -> adp.runIncrementalMining(op.a, snapshot, newTotal));
                        break;
                    case DEL:
                        m = measureAdaptive(adp, label, ds.name, op.iter, op.tag(),
                                () -> adp.runDeletion(op.a, snapshot, newTotal));
                        break;
                    default:
                        m = measureAdaptive(adp, label, ds.name, op.iter, op.tag(),
                                () -> adp.runModification(op.a, op.b, snapshot, newTotal));
                        break;
                }
            }
            col.log(m);
            if (m.runtimeMs < 0) poisoned = true;
            cur = after; total = newTotal;
            forceFullGC();
        }
    }

    private static PreHUSPMAdaptiveAlgorithm.PreHUSPMAdaptiveAlgo newAdaptive(double su) {
        return new PreHUSPMAdaptiveAlgorithm.PreHUSPMAdaptiveAlgo(
                su, DEFAULT_KAPPA, DEFAULT_DELTA0, DEFAULT_SL_MIN_RATIO,
                PreHUSPMAdaptiveAlgorithm.PreHUSPMAdaptiveAlgo.AblationMode.FULL,
                ENABLE_PATTERN_EXPORT);
    }

    /** Goi measure() voi day du cac supplier cua Adaptive; gom lai de khoi lap 11 doi so. */
    private static ExperimentMetrics measureAdaptive(
            PreHUSPMAdaptiveAlgorithm.PreHUSPMAdaptiveAlgo a,
            String label, String dsName, int iter, String tag, Task task) {
        ExperimentMetrics m = measure(label, dsName, iter, tag, task,
                () -> a.candidateCount, () -> a.patternCount,
                () -> a.lutDynValue, () -> a.cubValue, () -> a.cubTriggeredRescan,
                null, () -> a.prunedValueBound,
                () -> a.prelargeCount, () -> a.uutValue, () -> a.sftValue, a::cancel);
        attachAdaptive(m, a);
        return m;
    }

    // ==================================================================
    // SC7 - ABLATION ĐÓNG GÓP: C3 (IUB) / C4 (Block-Index) / C5 (Adaptive rescan). C1 (cận PEU áp trước materialize) là cố hữu.
    // ==================================================================
    private static void runSC7_Ablation() throws IOException {
        System.out.println("\n--- [SC7] Ablation C3(IUB)/C4(Block)/C5(rescan) ---");
        try (ResultCollector col = new ResultCollector(RESULTS_DIR + "SC7_ablation.csv")) {
            for (DatasetConfig ds : DATASETS) {
                if (!datasetEnabled(ds.name)) continue;
                List<Sequence> full = DatasetLoader.loadDataset(datasetDir + ds.name + "_seq.txt",
                                                                datasetDir + ds.name + "_eui.txt");
                if (full.isEmpty()) continue;
                double totalUtil = sumUtility(full);

                // Ablation khong co ngau nhien; chi lap lai de do nhieu JIT/GC.
                for (int r = 0; r < REPEATS; r++) {
                setRunContext(SEEDS[0], r);

                // --- Ablation cận tỉa (single full-mine): C1 (cận PEU áp trước materialize) là
                //     CỐ HỮU; ablation đo C3 (IUB) và C4 (Block-Index). ---
                logAblation(col, ds, full, totalUtil, "proposed_full", true, true);        // đề xuất đầy đủ
                forceFullGC();
                logAblation(col, ds, full, totalUtil, "proposed_no_block_index", false, true); // -C4
                forceFullGC();
                logAblation(col, ds, full, totalUtil, "proposed_no_IUB", true, false);     // -C3
                forceFullGC();

                // --- Ablation C5 (incremental, NẶNG): chỉ chạy trên TẬP ĐẠI DIỆN ---
                if (C5_ABLATION_SET.contains(ds.name)) {
                    logC5Ablation(col, ds, full, true,  "proposed_C5_on");   // C5 đầy đủ
                    forceFullGC();
                    logC5Ablation(col, ds, full, false, "proposed_C5_off");  // -C5: rescan mỗi lô (naive)
                    forceFullGC();
                }

                // Pre-HUSPM reference
                if (!ADAPTIVE_ONLY) {
                announce("Pre-HUSPM", ds.name, "SC7_ref", 0);
                PreHUSPMAlgorithm.PreHUSPMAlgo pre = new PreHUSPMAlgorithm.PreHUSPMAlgo(
                        ds.defaultSu, DEFAULT_PRE_HUSPM_SL_RATIO, ENABLE_PATTERN_EXPORT);
                ExperimentMetrics m = measure("Pre-HUSPM", ds.name, 0, "SC7_ref",
                        () -> pre.runInitialMining(full, totalUtil),
                        () -> pre.candidateCount, () -> pre.patternCount, null, null);
                attachPreHuspm(m, pre);
                col.log(m);
                }
                }   // het vong lap repeats

                forceFullGC();
            }
        }
    }

    private static void logAblation(ResultCollector col, DatasetConfig ds, List<Sequence> full,
                                    double totalUtil, String tag, boolean useBlockIndex,
                                    boolean useIub) {
        PreHUSPMAdaptiveAlgorithm.PreHUSPMAdaptiveAlgo adp = new PreHUSPMAdaptiveAlgorithm.PreHUSPMAdaptiveAlgo(
                ds.defaultSu, DEFAULT_KAPPA, DEFAULT_DELTA0, DEFAULT_SL_MIN_RATIO,
                PreHUSPMAdaptiveAlgorithm.PreHUSPMAdaptiveAlgo.AblationMode.FULL,
                ENABLE_PATTERN_EXPORT);
        adp.useBlockIndex = useBlockIndex; // C4
        adp.useIub = useIub;            // C3
        announce(tag, ds.name, "SC7", 0);
        ExperimentMetrics m = measure(tag, ds.name, 0, "SC7",
                () -> adp.runInitialMining(full, totalUtil),
                () -> adp.candidateCount, () -> adp.patternCount,
                () -> adp.lutDynValue, () -> adp.cubValue, () -> adp.cubTriggeredRescan,
                null, () -> adp.prunedValueBound,
                            () -> adp.prelargeCount, () -> adp.uutValue, () -> adp.sftValue, adp::cancel);
        attachAdaptive(m, adp);
        col.log(m);
    }

    /**
     * Ablation C5 (incremental): chạy Adaptive qua D_init(50%) + 5 lô; đo TỔNG thời gian,
     * rescanCount và completeness. useAdaptiveRescan=false ⇒ rescan MỖI LÔ (naive) → minh
     * hoạ giá trị C5 (rescan thích ứng): ít rescan + nhanh + không treo, vẫn đầy đủ.
     */
    private static void logC5Ablation(ResultCollector col, DatasetConfig ds, List<Sequence> full,
                                      boolean useAdaptiveRescan, String tag) {
        List<Sequence> dInit = headPercent(full, D_INIT_RATIO);
        List<Sequence> rest = tailFrom(full, dInit.size());
        List<List<Sequence>> batches = splitByWeights(rest, equalWeights(5));
        final double initTotal = sumUtility(dInit);
        PreHUSPMAdaptiveAlgorithm.PreHUSPMAdaptiveAlgo adp = new PreHUSPMAdaptiveAlgorithm.PreHUSPMAdaptiveAlgo(
                ds.defaultSu, DEFAULT_KAPPA, DEFAULT_DELTA0, DEFAULT_SL_MIN_RATIO,
                PreHUSPMAdaptiveAlgorithm.PreHUSPMAdaptiveAlgo.AblationMode.FULL,
                ENABLE_PATTERN_EXPORT);
        adp.useAdaptiveRescan = useAdaptiveRescan; // C5 on/off
        announce(tag, ds.name, "SC7_C5", 0);
        ExperimentMetrics m = measure(tag, ds.name, 0, "SC7_C5",
                () -> {
                    adp.runInitialMining(dInit, initTotal);
                    double rolling = initTotal;
                    List<Sequence> dSoFar = new ArrayList<>(dInit);
                    for (List<Sequence> delta : batches) {
                        if (delta.isEmpty()) continue;
                        double newTotal = rolling + sumUtility(delta);
                        List<Sequence> snap = new ArrayList<>(dSoFar);
                        snap.addAll(delta);
                        adp.runIncrementalMining(delta, snap, newTotal);
                        dSoFar.addAll(delta);
                        rolling = newTotal;
                    }
                },
                () -> adp.candidateCount, () -> adp.patternCount,
                () -> adp.lutDynValue, () -> adp.cubValue, () -> adp.cubTriggeredRescan,
                null, () -> adp.prunedValueBound,
                () -> adp.prelargeCount, () -> adp.uutValue, () -> adp.sftValue, adp::cancel);
        attachAdaptive(m, adp);
        col.log(m);
    }

    // ==================================================================
    // SHARED: INCREMENTAL PIPELINE (used by SC2 and SC5)
    //
    // OOM ISOLATION: trước đây 3 thuật toán (inc, preh, adp) cùng sống xuyên
    // suốt scenario, chia chung budget heap → OOM của thuật toán này CHẶN heap
    // của thuật toán kế tiếp. Giờ tách thành 3 BLOCK độc lập, mỗi block tự kết
    // thúc, drop reference rồi forceFullGC() trước khi block tiếp theo bắt đầu.
    //
    // Thêm cờ `poisoned`: khi 1 op fail (OOM/Exception), bỏ qua hết các op còn
    // lại của THUẬT TOÁN ĐÓ (state đã hỏng, retrying chỉ tốn thời gian + OOM).
    // ==================================================================
    private static void runIncrementalScenario(String dsName, double Su, List<Sequence> dInit,
                                               List<List<Sequence>> batches, ResultCollector col,
                                               String tag) {
        final double initTotal = sumUtility(dInit);

        // -------- Algorithm 1: IncUSP-Miner+ (isolated scope) --------
        if (!ADAPTIVE_ONLY && algoEnabled("IncUSP-Miner+")) {
            IncUSPMinerPlusAlgorithm inc = new IncUSPMinerPlusAlgorithm(Su);
            boolean poisoned = false;
            announce("IncUSP-Miner+", dsName, tag + "_INIT", 0);
            ExperimentMetrics m0 = measure("IncUSP-Miner+", dsName, 0, tag + "_INIT",
                    () -> inc.runInitialMining(dInit, initTotal),
                    () -> inc.candidateCount, () -> inc.patternCount, null, null);
            if (m0.runtimeMs != -1) { m0.prunedTsu = inc.prunedTSU; m0.exploredNodes = inc.exploredNodes; }
            col.log(m0);
            // OOM/poison → GIẢI PHÓNG NGAY CP-tree (nhiều GB) để các cấp phát main kế tiếp
            // (skipped log / snapshot / scenario) KHÔNG tiếp tục OOM rồi sập experiment.
            if (m0.runtimeMs < 0) { poisoned = true; inc.releaseState(); forceFullGC(); }

            double rolling = initTotal;
            List<Sequence> dSoFar = new ArrayList<>(dInit);
            for (int b = 0; b < batches.size(); b++) {
                List<Sequence> delta = batches.get(b);
                if (delta.isEmpty()) continue;
                double newTotal = rolling + sumUtility(delta);
                int batchIdx = b + 1;
                // LỚP PHÒNG THỦ 1: check RAM trước batch - nếu thiếu, poison + skip.
                if (!poisoned && !memoryGuardOk("IncUSP-Miner+", dsName, tag + "_INC")) poisoned = true;
                if (poisoned) {
                    col.log(skippedMetric("IncUSP-Miner+", dsName, batchIdx, tag + "_INC"));
                } else {
                    List<Sequence> snapshot = new ArrayList<>(dSoFar);
                    snapshot.addAll(delta);
                    final double finalNewTotal = newTotal;
                    announce("IncUSP-Miner+", dsName, tag + "_INC", batchIdx);
                    ExperimentMetrics mb = measure("IncUSP-Miner+", dsName, batchIdx, tag + "_INC",
                            () -> inc.runIncrementalMining(delta, snapshot, finalNewTotal),
                            () -> inc.candidateCount, () -> inc.patternCount, null, null);
                    if (mb.runtimeMs != -1) { mb.prunedTsu = inc.prunedTSU; mb.exploredNodes = inc.exploredNodes; }
                    col.log(mb);
                    if (mb.runtimeMs < 0) { poisoned = true; inc.releaseState(); forceFullGC(); }
                }
                dSoFar.addAll(delta);
                rolling = newTotal;
            }
        } // inc out of scope here - GC can reclaim its heap
        forceFullGC();

        // -------- Algorithm 2: Pre-HUSPM, CA HAI BIEN THE (moi cai mot scope rieng) --------
        // (-Dalgo.only loc o vong lap ben trong vi hai bien the la hai arm rieng)
        //
        // Yan et al. dinh nghia hai luat kich hoat rescan va so sanh CA HAI trong bai cua
        // ho. Ban nop cua ta chi cai bien the SWUmax (Dinh ly 2 cua ho), von KHONG sound.
        // Chi so sanh voi bien the yeu hon la danh gia thap baseline - dung cao buoc ma
        // Reviewer #1 neu o comment 3. Tu day chay ca hai:
        //   Pre-HUSPM      = bien the SWUmax, Dinh ly 2  (giu ten cu de khong vo CSV cu)
        //   Pre-HUSPM-TSU  = bien the TSU(d), Dinh ly 1, sound
        // O pha khai pha TINH hai bien the la mot, nen chung chi tach nhau o SC2/SC5.
        if (!ADAPTIVE_ONLY) {
            PreHUSPMAlgorithm.PreHUSPMAlgo.Variant[] variants = {
                    PreHUSPMAlgorithm.PreHUSPMAlgo.Variant.SWUMAX,
                    PreHUSPMAlgorithm.PreHUSPMAlgo.Variant.TSU,
            };
            for (PreHUSPMAlgorithm.PreHUSPMAlgo.Variant variant : variants) {
                final String label = (variant == PreHUSPMAlgorithm.PreHUSPMAlgo.Variant.TSU)
                        ? "Pre-HUSPM-TSU" : "Pre-HUSPM";
                if (!algoEnabled(label)) continue;
                final PreHUSPMAlgorithm.PreHUSPMAlgo preh = new PreHUSPMAlgorithm.PreHUSPMAlgo(
                        Su, DEFAULT_PRE_HUSPM_SL_RATIO, ENABLE_PATTERN_EXPORT, variant);
                boolean poisoned = false;
                announce(label, dsName, tag + "_INIT", 0);
                ExperimentMetrics m0 = measure(label, dsName, 0, tag + "_INIT",
                        () -> preh.runInitialMining(dInit, initTotal),
                        () -> preh.candidateCount, () -> preh.patternCount, null, null);
                attachPreHuspm(m0, preh);
                col.log(m0);
                if (m0.runtimeMs < 0) poisoned = true;

                double rolling = initTotal;
                List<Sequence> dSoFar = new ArrayList<>(dInit);
                for (int b = 0; b < batches.size(); b++) {
                    List<Sequence> delta = batches.get(b);
                    if (delta.isEmpty()) continue;
                    double newTotal = rolling + sumUtility(delta);
                    int batchIdx = b + 1;
                    // LỚP PHÒNG THỦ 1: check RAM trước batch.
                    if (!poisoned && !memoryGuardOk(label, dsName, tag + "_INC")) poisoned = true;
                    if (poisoned) {
                        col.log(skippedMetric(label, dsName, batchIdx, tag + "_INC"));
                    } else {
                        List<Sequence> snapshot = new ArrayList<>(dSoFar);
                        snapshot.addAll(delta);
                        final double finalNewTotal = newTotal;
                        announce(label, dsName, tag + "_INC", batchIdx);
                        ExperimentMetrics mb = measure(label, dsName, batchIdx, tag + "_INC",
                                () -> preh.runIncrementalMining(delta, snapshot, finalNewTotal),
                                () -> preh.candidateCount, () -> preh.patternCount, null, null);
                        attachPreHuspm(mb, preh);
                        col.log(mb);
                        if (mb.runtimeMs < 0) poisoned = true;
                    }
                    dSoFar.addAll(delta);
                    rolling = newTotal;
                }
                forceFullGC();
            }
        }
        forceFullGC();

        // -------- Moc so sanh: Remine-from-scratch (isolated scope) --------
        //
        // Tra loi cau hoi ma review neu o comment 4b: duy tri gia tang re hon bao nhieu
        // so voi don gian mine lai tu dau sau moi lo. Neu khong co moc nay thi khong the
        // dinh luong duoc loi ich cua toan bo co che gia tang.
        //
        // Moc nay dung CHINH bo khai pha tinh cua khung de xuat, khong dung USpan hay
        // HUSP-ULL. Do la lua chon co chu y: no khien Remine tro thanh doi thu MANH NHAT
        // co the cho tuyen bo gia tang, vi moi khoang cach do duoc deu den tu viec KHONG
        // phai mine lai, chu khong den tu viec bo mine cua ta nhanh hon bo mine cua nguoi
        // khac. Lay mot baseline tinh cham hon lam moc se thoi phong loi ich len.
        //
        // Runtime cong don khong luu thanh cot rieng: no la tong cua cot runtime_ms tren
        // cac dong cung mot lan chay, tinh duoc tu du lieu tho, dung quy uoc chung cua
        // ExperimentMetrics la khong luu dai luong dan xuat.
        if (algoEnabled("Remine-from-scratch")) {
            boolean poisoned = false;
            double rolling = initTotal;
            List<Sequence> dSoFar = new ArrayList<>(dInit);

            PreHUSPMAdaptiveAlgorithm.PreHUSPMAdaptiveAlgo rm0 =
                    new PreHUSPMAdaptiveAlgorithm.PreHUSPMAdaptiveAlgo(
                            Su, DEFAULT_KAPPA, DEFAULT_DELTA0, DEFAULT_SL_MIN_RATIO,
                            PreHUSPMAdaptiveAlgorithm.PreHUSPMAdaptiveAlgo.AblationMode.FULL,
                            ENABLE_PATTERN_EXPORT);
            announce("Remine-from-scratch", dsName, tag + "_INIT", 0);
            ExperimentMetrics m0 = measure("Remine-from-scratch", dsName, 0, tag + "_INIT",
                    () -> rm0.runInitialMining(dInit, initTotal),
                    () -> rm0.candidateCount, () -> rm0.patternCount,
                    () -> rm0.lutDynValue, () -> rm0.cubValue, () -> rm0.cubTriggeredRescan,
                    null, () -> rm0.prunedValueBound,
                    () -> rm0.prelargeCount, () -> rm0.uutValue, () -> rm0.sftValue, rm0::cancel);
            attachAdaptive(m0, rm0);
            col.log(m0);
            if (m0.runtimeMs < 0) poisoned = true;
            forceFullGC();

            for (int b = 0; b < batches.size(); b++) {
                List<Sequence> delta = batches.get(b);
                if (delta.isEmpty()) continue;
                double newTotal = rolling + sumUtility(delta);
                int batchIdx = b + 1;
                dSoFar.addAll(delta);
                rolling = newTotal;

                if (!poisoned && !memoryGuardOk("Remine-from-scratch", dsName, tag + "_INC")) poisoned = true;
                if (poisoned) {
                    col.log(skippedMetric("Remine-from-scratch", dsName, batchIdx, tag + "_INC"));
                    continue;
                }
                // Moi lo mot instance moi: mine lai tu dau nghia la KHONG giu bat cu trang
                // thai nao cua lo truoc.
                final List<Sequence> snapshot = new ArrayList<>(dSoFar);
                final double finalTotal = newTotal;
                PreHUSPMAdaptiveAlgorithm.PreHUSPMAdaptiveAlgo rm =
                        new PreHUSPMAdaptiveAlgorithm.PreHUSPMAdaptiveAlgo(
                                Su, DEFAULT_KAPPA, DEFAULT_DELTA0, DEFAULT_SL_MIN_RATIO,
                                PreHUSPMAdaptiveAlgorithm.PreHUSPMAdaptiveAlgo.AblationMode.FULL,
                                ENABLE_PATTERN_EXPORT);
                announce("Remine-from-scratch", dsName, tag + "_INC", batchIdx);
                ExperimentMetrics mb = measure("Remine-from-scratch", dsName, batchIdx, tag + "_INC",
                        () -> rm.runInitialMining(snapshot, finalTotal),
                        () -> rm.candidateCount, () -> rm.patternCount,
                        () -> rm.lutDynValue, () -> rm.cubValue, () -> rm.cubTriggeredRescan,
                        null, () -> rm.prunedValueBound,
                        () -> rm.prelargeCount, () -> rm.uutValue, () -> rm.sftValue, rm::cancel);
                attachAdaptive(mb, rm);
                // Mine lai tu dau khong co khai niem rescan gia tang; de 0 thay vi 1 de
                // cot rescan_count khong bi doc nham thanh "moc nay cung rescan moi lo".
                mb.rescanCount = 0;
                col.log(mb);
                if (mb.runtimeMs < 0) poisoned = true;
                forceFullGC();
            }
        }
        forceFullGC();

        // -------- Algorithm 3: Adaptive-Full (isolated scope) --------
        if (algoEnabled("Adaptive-Full")) {
            PreHUSPMAdaptiveAlgorithm.PreHUSPMAdaptiveAlgo adp = new PreHUSPMAdaptiveAlgorithm.PreHUSPMAdaptiveAlgo(
                    Su, DEFAULT_KAPPA, DEFAULT_DELTA0, DEFAULT_SL_MIN_RATIO,
                    PreHUSPMAdaptiveAlgorithm.PreHUSPMAdaptiveAlgo.AblationMode.FULL,
                    ENABLE_PATTERN_EXPORT);
            boolean poisoned = false;
            announce("Adaptive-Full", dsName, tag + "_INIT", 0);
            ExperimentMetrics m0 = measure("Adaptive-Full", dsName, 0, tag + "_INIT",
                    () -> adp.runInitialMining(dInit, initTotal),
                    () -> adp.candidateCount, () -> adp.patternCount,
                    () -> adp.lutDynValue, () -> adp.cubValue, () -> adp.cubTriggeredRescan,
                    null, () -> adp.prunedValueBound,
                            () -> adp.prelargeCount, () -> adp.uutValue, () -> adp.sftValue, adp::cancel);
            attachAdaptive(m0, adp);
            col.log(m0);
            if (m0.runtimeMs < 0) poisoned = true;

            double rolling = initTotal;
            List<Sequence> dSoFar = new ArrayList<>(dInit);
            for (int b = 0; b < batches.size(); b++) {
                List<Sequence> delta = batches.get(b);
                if (delta.isEmpty()) continue;
                double newTotal = rolling + sumUtility(delta);
                int batchIdx = b + 1;
                // LỚP PHÒNG THỦ 1: check RAM trước batch.
                if (!poisoned && !memoryGuardOk("Adaptive-Full", dsName, tag + "_INC")) poisoned = true;
                if (poisoned) {
                    col.log(skippedMetric("Adaptive-Full", dsName, batchIdx, tag + "_INC"));
                } else {
                    List<Sequence> snapshot = new ArrayList<>(dSoFar);
                    snapshot.addAll(delta);
                    final double finalNewTotal = newTotal;
                    announce("Adaptive-Full", dsName, tag + "_INC", batchIdx);
                    ExperimentMetrics mb = measure("Adaptive-Full", dsName, batchIdx, tag + "_INC",
                            () -> adp.runIncrementalMining(delta, snapshot, finalNewTotal),
                            () -> adp.candidateCount, () -> adp.patternCount,
                            () -> adp.lutDynValue, () -> adp.cubValue, () -> adp.cubTriggeredRescan,
                            null, () -> adp.prunedValueBound,
                            () -> adp.prelargeCount, () -> adp.uutValue, () -> adp.sftValue, adp::cancel);
                    attachAdaptive(mb, adp);
                    col.log(mb);
                    if (mb.runtimeMs < 0) poisoned = true;
                }
                dSoFar.addAll(delta);
                rolling = newTotal;
            }
        }
        forceFullGC();
    }

    // ==================================================================
    // RUN ONE ALGORITHM (used by SC1, SC3)
    // ==================================================================
    /**
     * Gom TẤT CẢ metric riêng của Adaptive vào 1 chỗ (tránh bỏ sót khi thêm site mới):
     * prunedIub (C3) + rescanCount (C5) + blocksRead/blocksSkipped (C4 Block-Index).
     * Bỏ qua nếu metric null hoặc lần chạy thất bại (runtimeMs == -1).
     */
    private static void attachAdaptive(ExperimentMetrics m, PreHUSPMAdaptiveAlgorithm.PreHUSPMAdaptiveAlgo adp) {
        if (m == null || m.runtimeMs == -1) return;
        m.exploredNodes = adp.exploredNodes;    // mẫu khai triển thật (sau cận PEU)
        m.prunedIub     = adp.prunedIub;        // C3
        m.rescanCount   = adp.rescanCount;      // C5
        m.blocksRead    = adp.blocksReadCount;  // C4
        m.blocksSkipped = adp.numBlocksSkipped; // C4
        m.sigma2        = adp.sigmaDSquared;    // C2 (phương sai lô - thô, độc lập)
        m.rho           = adp.rhoD;             // C2 (độ lệch - thô, độc lập)
        // δ_D KHÔNG lưu (dẫn xuất = uut_val - lut_dyn)
    }

    /**
     * Gom metric Pre-HUSPM: prunedPeu + prelarge + rescanCount + safetyBoundF→sft_val
     * (map cận an toàn của Pre-HUSPM vào cột sft_val để SO TRỰC TIẾP với cận F của Adaptive).
     */
    private static void attachPreHuspm(ExperimentMetrics m, PreHUSPMAlgorithm.PreHUSPMAlgo pre) {
        if (m == null || m.runtimeMs == -1) return;
        m.exploredNodes        = pre.exploredNodes; // = candidateCount (materialize MỌI extension)
        m.prunedPeu            = pre.prunedPEU; // Pre-HUSPM tỉa bằng SWU_proj (= PEU) đúng bài báo
        m.prelargePatternCount = pre.prelargeCount;
        m.rescanCount          = pre.rescanCount;
        m.sftValue             = pre.safetyBoundF; // cận an toàn pre-large (so với Adaptive)
        m.cubValue             = pre.bufValue;     // bộ đệm tích luỹ của Pre-HUSPM
        // WP2.4 - ghi rõ biến thể nào đã sinh ra dòng này.
        m.triggerRule          = (pre.variant == PreHUSPMAlgorithm.PreHUSPMAlgo.Variant.TSU)
                                 ? "tsud" : "swumax";
        m.kappa = 0; m.delta0 = 0; m.blockSize = 0;
    }

    /**
     * Tong byte thread da cap phat. Tat dinh (khong phu thuoc lich GC), nen dung de bao
     * cao ap luc cap phat tach roi khoi tap song. Tra -1 neu JVM khong ho tro.
     */
    private static long threadAllocatedBytes(long tid) {
        try {
            java.lang.management.ThreadMXBean b = java.lang.management.ManagementFactory.getThreadMXBean();
            if (b instanceof com.sun.management.ThreadMXBean) {
                com.sun.management.ThreadMXBean sb = (com.sun.management.ThreadMXBean) b;
                if (sb.isThreadAllocatedMemorySupported()) return sb.getThreadAllocatedBytes(tid);
            }
        } catch (Throwable ignored) {}
        return -1L;
    }

    private static ExperimentMetrics runSingle(String algo, String dsName, List<Sequence> db,
                                               double totalUtil, double su, int iter, String tag) {
        if (ADAPTIVE_ONLY && !algo.equals("Adaptive-Full")) return null; // chỉ chạy lại Adaptive
        if (!algoEnabled(algo)) return null;   // -Dalgo.only: mot arm mot JVM (che do live)
        announce(algo, dsName, tag, iter);
        try {
            switch (algo) {
                case "USpan": {
                    USpanAlgorithm u = new USpanAlgorithm(totalUtil * su);
                    ExperimentMetrics m = measure(algo, dsName, iter, tag,
                            () -> u.run(db),
                            () -> u.candidateCount, () -> u.patternCount, null, null);
                    if (m != null && m.runtimeMs != -1) {
                        m.prunedSwu = u.prunedSWU;
                        m.prunedDepth = u.prunedDepth;
                        m.exploredNodes = u.exploredNodes;
                    }
                    return m;
                }
                case "HUSP-SP": {
                    // Thuat toan TINH, khong xu ly cap nhat gia tang, nen chi co mat o
                    // SC1 va SC3. Dem giong het cac baseline khac: candidate_count truoc
                    // moi phep tia, explored_nodes tai diem vao de quy.
                    HUSPSPAlgorithm sp = new HUSPSPAlgorithm(totalUtil * su);
                    ExperimentMetrics m = measure(algo, dsName, iter, tag,
                            () -> sp.run(db),
                            () -> sp.candidateCount, () -> sp.patternCount, null, null,
                            null, null, null, null, null, null, sp::cancel);
                    if (m != null && m.runtimeMs != -1) {
                        m.prunedSwu = sp.prunedSWU;
                        m.prunedIip = sp.prunedIIP;      // IIP theo RSU
                        m.prunedPeu = sp.prunedPEU;      // nhanh dung boi can PEU
                        m.prunedLar = sp.prunedEP;       // EP theo TRSU, dong gop rieng cua bai
                        m.exploredNodes = sp.exploredNodes;
                    }
                    return m;
                }
                case "HUSP-ULL": {
                    HUSPULLAlgorithm h = new HUSPULLAlgorithm(totalUtil * su);
                    ExperimentMetrics m = measure(algo, dsName, iter, tag,
                            () -> h.run(db),
                            () -> h.candidateCount, () -> h.patternCount, null, null);
                    if (m != null && m.runtimeMs != -1) {
                        m.prunedSwu = h.prunedSWU;
                        m.prunedLar = h.prunedLAR;
                        m.prunedIip = h.prunedIIP;
                        m.exploredNodes = h.exploredNodes;
                    }
                    return m;
                }
                case "IncUSP-Miner+": {
                    IncUSPMinerPlusAlgorithm inc = new IncUSPMinerPlusAlgorithm(su);
                    ExperimentMetrics m = measure(algo, dsName, iter, tag,
                            () -> inc.runInitialMining(db, totalUtil),
                            () -> inc.candidateCount, () -> inc.patternCount, null, null);
                    if (m != null && m.runtimeMs != -1) { m.prunedTsu = inc.prunedTSU; m.exploredNodes = inc.exploredNodes; }
                    return m;
                }
                case "Pre-HUSPM": {
                    PreHUSPMAlgorithm.PreHUSPMAlgo pre = new PreHUSPMAlgorithm.PreHUSPMAlgo(
                            su, DEFAULT_PRE_HUSPM_SL_RATIO, ENABLE_PATTERN_EXPORT);
                    ExperimentMetrics m = measure(algo, dsName, iter, tag,
                            () -> pre.runInitialMining(db, totalUtil),
                            () -> pre.candidateCount, () -> pre.patternCount, null, null);
                    attachPreHuspm(m, pre);
                    return m;
                }
                case "Adaptive-Full": {
                    PreHUSPMAdaptiveAlgorithm.PreHUSPMAdaptiveAlgo adp = new PreHUSPMAdaptiveAlgorithm.PreHUSPMAdaptiveAlgo(
                            su, DEFAULT_KAPPA, DEFAULT_DELTA0, DEFAULT_SL_MIN_RATIO,
                            PreHUSPMAdaptiveAlgorithm.PreHUSPMAdaptiveAlgo.AblationMode.FULL,
                            ENABLE_PATTERN_EXPORT);
                    ExperimentMetrics m = measure(algo, dsName, iter, tag,
                            () -> adp.runInitialMining(db, totalUtil),
                            () -> adp.candidateCount, () -> adp.patternCount,
                            () -> adp.lutDynValue, () -> adp.cubValue, () -> adp.cubTriggeredRescan,
                            null, () -> adp.prunedValueBound,
                            () -> adp.prelargeCount, () -> adp.uutValue, () -> adp.sftValue, adp::cancel);
                    attachAdaptive(m, adp);
                    return m;
                }
                default: return null;
            }
        } catch (Throwable t) {
            System.err.println("[" + algo + "/" + dsName + "] ERROR: " + t.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------------
    // ANNOUNCE + MEASURE (with OOM safety)
    // ------------------------------------------------------------------
    /** Print a "now running" line BEFORE actually executing the algorithm. */
    private static void announce(String algo, String dataset, String opType, int iter) {
        Runtime rt = Runtime.getRuntime();
        long freeMb = rt.freeMemory() / (1024 * 1024);
        long totalMb = rt.totalMemory() / (1024 * 1024);
        long maxMb = rt.maxMemory() / (1024 * 1024);
        System.out.printf(">> RUNNING %s/%s/%s#%d  (heap free=%dMB / total=%dMB / max=%dMB)%n",
                algo, dataset, opType, iter, freeMb, totalMb, maxMb);
        System.out.flush();
    }

    @FunctionalInterface interface Task { void run(); }
    @FunctionalInterface interface LongSup { long get(); }
    @FunctionalInterface interface IntSup  { int  get(); }
    @FunctionalInterface interface DblSup  { double get(); }
    @FunctionalInterface interface BoolSup { boolean get(); }

    /** Overload: gọi không cần adaptive supplier. */
    private static ExperimentMetrics measure(String algo, String dataset, int iter, String opType,
                                             Task task,
                                             LongSup candidateCount, IntSup patternCount,
                                             DblSup lutSup, DblSup cubSup) {
        return measure(algo, dataset, iter, opType, task, candidateCount, patternCount,
                lutSup, cubSup, null, null, null, null, null, null, null);
    }

    /** Overload: cũ - Adaptive supplier nhưng KHÔNG có cancelHook + 3 supplier mới. */
    private static ExperimentMetrics measure(String algo, String dataset, int iter, String opType,
                                             Task task,
                                             LongSup candidateCount, IntSup patternCount,
                                             DblSup lutSup, DblSup cubSup, BoolSup rescanSup,
                                             LongSup prunedEucs, LongSup prunedBound) {
        return measure(algo, dataset, iter, opType, task, candidateCount, patternCount,
                lutSup, cubSup, rescanSup, prunedEucs, prunedBound, null, null, null, null);
    }

    /**
     * Chạy task trên worker thread riêng với TIMEOUT mặc định 60 PHÚT (cấu hình qua -Dtimeout.min).
     *
     * Đường đi:
     *  * Tạo single-thread ExecutorService (daemon → không khoá JVM exit).
     *  * Submit task; main thread chờ tối đa TIMEOUT_MS.
     *  * Trường hợp COMPLETE: trả về metric bình thường với opType gốc.
     *  * Trường hợp TIMEOUT: gọi cancelHook (signal cooperative cancellation cho Adaptive),
     *      future.cancel(true) (interrupt worker), ghi opType "_OT", runtime_ms = -2.
     *      Đọc partial counters nếu có thể (volatile read an toàn cho int/long).
     *  * Trường hợp OOM/Exception: opType "_FAILED", runtime_ms = -1.
     *
     * Sentinel runtime_ms:
     *    >=0 : OK (thực tế ms)
     *    -1  : FAILED (OOM hoặc Exception khác)
     *    -2  : OT (Overtime - vượt TIMEOUT_MINUTES)
     */
    private static ExperimentMetrics measure(String algo, String dataset, int iter, String opType,
                                             Task task,
                                             LongSup candidateCount, IntSup patternCount,
                                             DblSup lutSup, DblSup cubSup, BoolSup rescanSup,
                                             LongSup prunedEucs, LongSup prunedBound,
                                             IntSup prelargeCount, DblSup uutSup, DblSup sftSup,
                                             Runnable cancelHook) {
        Runtime rt = Runtime.getRuntime();
        rt.gc();
        long heapBefore = rt.totalMemory() - rt.freeMemory();
        long start = System.currentTimeMillis();

        // === BO LAY MAU BO NHO ===
        //
        // used : dinh used-heap moi 50 ms, KE CA rac chua thu (hanh vi cu).
        // live : moi MEM_SAMPLE_MS ep MOT lan thu gom day du roi doc used-heap NGAY SAU.
        //        Dinh cua cac lan doc ay la tap song, khong phu thuoc lich GC hay -Xmx.
        //        Day la CAN DUOI cua dinh tap song that: mot dinh xuat hien va bien mat
        //        giua hai moc se khong duoc thay. Khoang lay mau chan sai so do.
        //
        // Ca hai deu KHONG cap phat trong vong lap (doc native + atomic) nen an toan ca
        // khi heap gan day. Neu heap can, thread co the bi nem OOM tai safepoint; nuot im
        // lang vi in stack-trace se lai cap phat va gay OOM lan hai.
        final java.util.concurrent.atomic.AtomicLong peakUsed =
                new java.util.concurrent.atomic.AtomicLong(heapBefore);
        final java.util.concurrent.atomic.AtomicLong peakLive =
                new java.util.concurrent.atomic.AtomicLong(0);
        final java.util.concurrent.atomic.AtomicLong lastLive =
                new java.util.concurrent.atomic.AtomicLong(0);
        final java.util.concurrent.atomic.AtomicInteger gcCount =
                new java.util.concurrent.atomic.AtomicInteger(0);
        final java.util.concurrent.atomic.AtomicBoolean sampling =
                new java.util.concurrent.atomic.AtomicBoolean(true);
        Thread heapSampler = new Thread(() -> {
            try {
                Runtime r = Runtime.getRuntime();
                while (sampling.get()) {
                    if (MEM_LIVE) {
                        System.gc();
                        long live = r.totalMemory() - r.freeMemory();
                        lastLive.set(live);
                        gcCount.incrementAndGet();
                        long prevL = peakLive.get();
                        while (live > prevL && !peakLive.compareAndSet(prevL, live)) prevL = peakLive.get();
                        try { Thread.sleep(MEM_SAMPLE_MS); } catch (InterruptedException e) { break; }
                    } else {
                        long used = r.totalMemory() - r.freeMemory();
                        long prev = peakUsed.get();
                        while (used > prev && !peakUsed.compareAndSet(prev, used)) prev = peakUsed.get();
                        try { Thread.sleep(50); } catch (InterruptedException e) { break; }
                    }
                }
            } catch (Throwable ignored) {}
        }, "heap-sampler-" + algo);
        heapSampler.setDaemon(true);
        heapSampler.start();

        // Byte da cap phat cua thread khai phá. Tat dinh, khong phu thuoc lich GC, nen no
        // do "thuat toan nay cap phat nhieu hay it" ma khong tron lan voi thoi diem GC.
        // Phai doc NGAY TRONG worker thread truoc khi no ket thuc: sau shutdownNow thread
        // da chet va getThreadAllocatedBytes(tid) tra -1.
        final long[] allocStart = {-1L};
        final long[] allocEnd   = {-1L};

        // Pre-build runId NGAY BÂY GIỜ (heap còn khỏe) để KHỎI phải concat chuỗi lúc
        // dựng metric sau OOM (heap gần đầy do CP-Tree baseline → concat có thể OOM main).
        final String preRunId = algo + "_" + dataset + "_" + opType + "_" + iter;
        // PRE-ALLOCATE metric + các chuỗi opType NGAY (heap còn khỏe) → toàn bộ post-processing
        // CHỈ gán field nguyên thuỷ/tham chiếu, KHÔNG `new`/concat → KHÔNG thể OOM main-thread
        // ngay cả khi worker để heap gần đầy (vd CP-tree IncUSP-Miner+ tích luỹ qua các lô).
        final ExperimentMetrics m = new ExperimentMetrics();
        m.runId = preRunId; m.algorithm = algo; m.dataset = dataset; m.iteration = iter;
        final String OPT_OT = opType + "_OT", OPT_BUDGET = opType + "_BUDGET",
                     OPT_OOM = opType + "_OOM", OPT_FAILED = opType + "_FAILED";

        boolean failed = false;
        boolean timedOut = false;
        boolean oom = false;
        boolean budgetExceeded = false;
        String errMsg = null;
        final Throwable[] thrown = new Throwable[1];

        // Daemon thread → orphan execution không khoá JVM exit nếu timeout.
        ExecutorService exec = null;
        Future<?> future = null;
        // OUTER GUARD: bọc TOÀN BỘ thân measure → mọi OOM/lỗi MAIN-THREAD ở BẤT KỲ điểm cấp
        // phát nào (submit / future.get / build metric / shutdown ...) rơi vào catch cuối →
        // trả metric `m` (đã pre-allocate, alloc-free) → KHÔNG propagate làm SẬP experiment.
        try {
        exec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "algo-" + algo + "-" + dataset);
            t.setDaemon(true);
            return t;
        });

        future = exec.submit(() -> {
            long tid = Thread.currentThread().getId();
            allocStart[0] = threadAllocatedBytes(tid);
            try { task.run(); }
            catch (Throwable t) { thrown[0] = t; }
            finally {
                // Doc trong finally de van co so khi task bi huy hoac nem loi.
                long a = threadAllocatedBytes(tid);
                if (a >= 0) allocEnd[0] = a;
            }
        });

        try {
            future.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            timedOut = true;
            errMsg = "OT (>" + TIMEOUT_MINUTES + " min)";
            // 1. Signal cooperative cancellation (Adaptive sẽ thoát đệ quy gần ngay).
            if (cancelHook != null) {
                try { cancelHook.run(); } catch (Throwable ignored) {}
            }
            // 2. Interrupt thread (best-effort cho baselines không có cancel flag).
            future.cancel(true);
            // 3. Chờ thoát êm tối đa 5s; nếu orphan vẫn chạy, ta vẫn move on.
            try { future.get(5, TimeUnit.SECONDS); } catch (Throwable ignored) {}
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            failed = true; errMsg = "Interrupted";
        } catch (ExecutionException ee) {
            // Bao bọc khi task không catch - hiếm vì lambda đã try/catch.
            Throwable cause = ee.getCause();
            if (cause != null) thrown[0] = cause;
        } catch (Throwable mainErr) {
            // CÔ LẬP LỖI MAIN-THREAD (đặc biệt OutOfMemoryError): khi worker nhồi heap
            // gần đầy (vd CP-tree IncUSP-Miner+ tích luỹ qua các lô), chính future.get()
            // ở MAIN cấp Node-chờ trên heap đầy → ném OOM. TRƯỚC ĐÂY KHÔNG bắt → propagate
            // → SẬP toàn experiment. Nay: bắt → đánh dấu fail/oom → GC → trả metric DNF,
            // experiment CHẠY TIẾP (poison thuật toán này, giải phóng heap ở cuối scope).
            failed = true;
            if (mainErr instanceof OutOfMemoryError) { oom = true; errMsg = "MAIN-OOM"; }
            else { errMsg = "MAIN-" + mainErr.getClass().getSimpleName(); }
            try { future.cancel(true); } catch (Throwable ignored) {}
            forceFullGC();
        }

        long runtime = System.currentTimeMillis() - start;

        // Phân loại thrown[0] (OOM vs Exception khác).
        // QUAN TRỌNG: nếu timedOut, worker thread sẽ catch CancellationSignal mà
        // ta CHỦ ĐỘNG kích hoạt qua cancelHook → thrown[0] = CancellationSignal.
        // Không được treat đây là FAILED - phải giữ semantic OT.
        if (thrown[0] != null && !timedOut) {
            Throwable t = thrown[0];
            failed = true;
            if (t instanceof OutOfMemoryError) {
                // === OOM RECOVERY AN TOÀN ===
                // KHÔNG concat chuỗi (t.getMessage()) ở đây: heap đang gần đầy do worker
                // (vd CP-Tree của IncUSP-Miner+) → string-concat sẽ làm CHÍNH main thread
                // OOM → sập experiment (đúng lỗi đã gặp ở line 918 cũ). Dùng hằng số +
                // GC NGAY để giải phóng rác transient trước khi làm bất cứ allocation nào.
                oom = true;
                errMsg = "OOM";
                forceFullGC();
            } else if (t instanceof PreHUSPMAdaptiveAlgorithm.PatternBudgetExceeded) {
                budgetExceeded = true;        // ngưỡng quá thấp → quá nhiều mẫu (DNF, không phải OOM cứng)
                errMsg = "BUDGET";
                forceFullGC();
            } else if (t instanceof StackOverflowError) {
                oom = true; // gộp nhóm "tài nguyên" - đệ quy quá sâu
                errMsg = "STACKOVERFLOW";
                forceFullGC();
            } else {
                errMsg = "EXC:" + t.getClass().getSimpleName();
                forceFullGC();
            }
            // Logging bọc try/catch: nếu vẫn OOM khi build log, NUỐT để main thread sống.
            try { System.err.println("[" + algo + "/" + dataset + "/" + opType + "] " + errMsg); }
            catch (Throwable ignored) {}
        }
        if (timedOut) {
            try { System.err.println("[" + algo + "/" + dataset + "/" + opType + "] " + errMsg); }
            catch (Throwable ignored) {}
            forceFullGC();
        }

        // Shutdown executor - orphan thread (nếu còn) là daemon, sẽ tự chết khi JVM exit.
        exec.shutdownNow();
        try { exec.awaitTermination(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}

        // Dừng sampler; peakUsed = đỉnh heap THẬT đã ghi trong toàn bộ window chạy.
        sampling.set(false); heapSampler.interrupt();

        long heapAfter = rt.totalMemory() - rt.freeMemory();

        // m đã pre-allocate ở đầu hàm (heap khỏe) → đây chỉ FILL field, KHÔNG cấp phát.
        long realRuntime = timedOut ? -2 : (failed ? -1 : runtime);
        // Cau hinh cua lan do nay. attachPreHuspm ghi de mot so cot cho baseline
        // khong co khai niem tuong ung.
        m.seed = currentSeed;
        m.repeat = currentRepeat;
        m.blockSize = DEFAULT_BLOCK_SIZE;
        m.kappa = DEFAULT_KAPPA;
        m.delta0 = DEFAULT_DELTA0;
        m.triggerRule = ADAPT_TRIGGER;
        m.memMode = MEM_MODE;
        if (MEM_LIVE) {
            // Ep GC lam hong thoi gian. Xuat -3 de khong ai ve nham no thanh so hieu nang;
            // gia tri that van duoc giu o wall_ms_perturbed.
            m.runtimeMs = (realRuntime < 0) ? realRuntime : -3;
            m.wallMsPerturbed = runtime;
            // Doc cuoi cung sau khi worker da dung: day moi la tap song thuc su con lai.
            System.gc();
            long finalLive = rt.totalMemory() - rt.freeMemory();
            gcCount.incrementAndGet();
            long pl = Math.max(peakLive.get(), finalLive);
            m.memLiveMb     = Math.max(0, pl - heapBefore) / (1024 * 1024);
            m.memRetainedMb = Math.max(0, finalLive - heapBefore) / (1024 * 1024);
            m.gcForced      = gcCount.get();
        } else {
            m.runtimeMs = realRuntime;
        }
        // Peak THẬT: max(đỉnh-mẫu, heapAfter) trừ baseline → footprint thật của thuật toán.
        // CHÚ Ý: ở chế độ "used" đại lượng này gồm cả rác chưa thu - xem ExperimentMetrics.
        long peakHeap = Math.max(peakUsed.get(), heapAfter);
        m.peakHeapMb = Math.max(0, (peakHeap - heapBefore)) / (1024 * 1024);
        if (allocStart[0] >= 0 && allocEnd[0] >= allocStart[0]) {
            m.allocatedMb = (allocEnd[0] - allocStart[0]) / (1024 * 1024);
        } else {
            m.allocatedMb = -1;   // khong doc duoc (vd worker bi huy giua chung)
        }
        // Trên TIMEOUT vẫn cố đọc partial counters - int/long read an toàn,
        // worst case là giá trị stale (chấp nhận được).
        boolean readCounters = !failed; // OK hoặc timeout đều có partial state
        m.candidateCount = (readCounters && candidateCount != null) ? safeLong(candidateCount) : 0;
        m.totalPatternsFound = (readCounters && patternCount != null) ? safeInt(patternCount) : 0;
        m.lutDynValue = (readCounters && lutSup != null) ? safeDbl(lutSup) : 0;
        m.cubValue   = (readCounters && cubSup != null) ? safeDbl(cubSup) : 0;
        m.cubTriggeredRescan = readCounters && rescanSup != null && safeBool(rescanSup);
        m.prunedValueBound     = (readCounters && prunedBound != null) ? safeLong(prunedBound) : 0;
        // Pre-large & ngưỡng tuyệt đối (đọc an toàn; null → 0 cho thuật toán không hỗ trợ)
        m.prelargePatternCount = (readCounters && prelargeCount != null) ? safeInt(prelargeCount) : 0;
        m.uutValue             = (readCounters && uutSup != null) ? safeDbl(uutSup) : 0;
        m.sftValue             = (readCounters && sftSup != null) ? safeDbl(sftSup) : 0;
        // opType phản ánh rõ loại kết thúc để phân tích CSV: _OT (timeout),
        // _OOM (hết RAM), _BUDGET (quá nhiều mẫu), _FAILED (lỗi khác), hoặc gốc (OK).
        if (timedOut)            m.opType = OPT_OT;
        else if (budgetExceeded) m.opType = OPT_BUDGET;
        else if (oom)            m.opType = OPT_OOM;
        else if (failed)         m.opType = OPT_FAILED;
        else                     m.opType = opType;

        // Bọc printf trong try/catch: format string cũng allocate → trên heap đầy có
        // thể OOM. Nuốt để main thread sống (metric m đã sẵn sàng để caller log).
        try {
            if (timedOut) {
                System.out.printf("[%s/%s/%s#%d] TIMEOUT after %dms (>%d min) cand=%d pat=%d%n",
                        algo, dataset, opType, iter, runtime, TIMEOUT_MINUTES,
                        m.candidateCount, m.totalPatternsFound);
            } else if (failed) {
                System.out.printf("[%s/%s/%s#%d] FAILED after %dms (%s)%n",
                        algo, dataset, opType, iter, runtime, errMsg);
            } else {
                System.out.printf("[%s/%s/%s#%d] OK t=%dms cand=%d pat=%d heapDelta=%dMB%n",
                        algo, dataset, opType, iter, runtime,
                        m.candidateCount, m.totalPatternsFound, m.peakHeapMb);
            }
        } catch (Throwable ignored) {}
        return m;
        } catch (Throwable fatal) {
            // OOM/lỗi MAIN-THREAD bất kỳ trong measure → trả `m` đã pre-allocate (KHÔNG cấp
            // phát mới ⇒ catch không thể OOM). Dừng worker + GC để pha/thuật toán kế có heap.
            try { if (future != null) future.cancel(true); } catch (Throwable ig) {}
            try { if (exec != null) exec.shutdownNow(); } catch (Throwable ig) {}
            try { if (exec != null) exec.awaitTermination(3, TimeUnit.SECONDS); } catch (Throwable ig) {}
            forceFullGC();
            m.runtimeMs = -1;
            m.opType = (fatal instanceof OutOfMemoryError || fatal instanceof StackOverflowError) ? OPT_OOM : OPT_FAILED;
            try { System.err.println("[" + algo + "/" + dataset + "/" + opType + "] MAIN-FATAL recovered: "
                    + fatal.getClass().getSimpleName()); } catch (Throwable ig) {}
            return m;
        } finally {
            try { sampling.set(false); heapSampler.interrupt(); } catch (Throwable ig) {}
        }
    }

    // Safe accessors so an algorithm that crashed mid-state doesn't crash the metric pull
    private static long safeLong(LongSup s) { try { return s.get(); } catch (Throwable t) { return 0; } }
    private static int  safeInt (IntSup s)  { try { return s.get(); } catch (Throwable t) { return 0; } }
    private static double safeDbl(DblSup s) { try { return s.get(); } catch (Throwable t) { return 0; } }
    private static boolean safeBool(BoolSup s) { try { return s.get(); } catch (Throwable t) { return false; } }

    /**
     * Aggressive GC để thu hồi heap sau khi 1 thuật toán OOM hoặc trước khi
     * chuyển sang thuật toán tiếp theo trong cùng 1 scenario. Nhiều cycle
     * gc() cách nhau ngắn buộc HotSpot xử lý hết soft/weak refs và promote
     * young → old. Lưu ý: System.gc() là hint, nhưng khi heap đang gần max,
     * JVM gần như luôn honor; đặc biệt cần thiết ngay sau OOM.
     * (Không gọi System.runFinalization() vì đã deprecated từ Java 18+.)
     */
    private static void forceFullGC() {
        for (int i = 0; i < 3; i++) {
            System.gc();
            try { Thread.sleep(80); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
    }

    // === LỚP PHÒNG THỦ 1: CẢM BIẾN RAM ===
    // Tránh Global OOM làm sập Main Thread khi baseline (vd IncUSP-Miner+ với CP-Tree
    // persistent) ăn dần RAM qua từng batch. Available = maxMemory - used
    // (= freeMemory + phần chưa cấp phát). Nếu < 10% max → KHÔNG an toàn để chạy
    // batch tiếp theo (nguy cơ heap đầy → main thread OOM lúc recovery).
    private static final double MEM_SAFE_FRACTION = 0.10;
    private static boolean isMemorySafe() {
        Runtime rt = Runtime.getRuntime();
        long max = rt.maxMemory();
        long used = rt.totalMemory() - rt.freeMemory();
        long available = max - used; // free trong heap đã cấp + phần còn có thể cấp
        return available > (long) (max * MEM_SAFE_FRACTION);
    }

    /**
     * Guard trước mỗi batch: forceFullGC rồi check isMemorySafe.
     * Trả true nếu AN TOÀN để chạy; false nếu nên skip (đã in cảnh báo).
     */
    private static boolean memoryGuardOk(String algo, String dataset, String op) {
        forceFullGC();
        if (isMemorySafe()) return true;
        Runtime rt = Runtime.getRuntime();
        System.err.println("OOM Warning: Bỏ qua lô này để bảo vệ Main Thread [" + algo + "/" + dataset
                + "/" + op + "] availableMB=" + ((rt.maxMemory() - (rt.totalMemory() - rt.freeMemory())) / 1048576));
        return false;
    }

    /** Tạo metric "SKIPPED" cho ops bị bỏ qua sau khi algorithm bị poisoned (đã OOM trước đó). */
    private static ExperimentMetrics skippedMetric(String algo, String dataset, int iter, String opType) {
        ExperimentMetrics m = new ExperimentMetrics();
        m.runId = algo + "_" + dataset + "_" + opType + "_" + iter;
        m.algorithm = algo;
        m.dataset = dataset;
        m.iteration = iter;
        m.runtimeMs = -1;
        m.opType = opType + "_SKIPPED_POISONED";
        return m;
    }

    // ==================================================================
    // DATASET / BATCH UTILITIES
    // ==================================================================
    private static double sumUtility(List<Sequence> db) {
        double s = 0; for (Sequence sq : db) s += sq.sequenceUtility; return s;
    }

    private static List<Sequence> headPercent(List<Sequence> all, double ratio) {
        if (all.isEmpty()) return Collections.emptyList();
        int n = Math.max(1, (int) Math.round(all.size() * ratio));
        return new ArrayList<>(all.subList(0, Math.min(n, all.size())));
    }

    private static List<Sequence> tailFrom(List<Sequence> all, int startIdx) {
        if (startIdx >= all.size()) return Collections.emptyList();
        return new ArrayList<>(all.subList(startIdx, all.size()));
    }

    private static List<Sequence> pickRandom(List<Sequence> from, int count, Random rnd) {
        List<Sequence> copy = new ArrayList<>(from);
        Collections.shuffle(copy, rnd);
        return new ArrayList<>(copy.subList(0, Math.min(count, copy.size())));
    }

    private static double[] computeWeights(BatchSplitSpec spec) {
        int k = spec.numBatches;
        switch (spec.mode) {
            case EVEN:        return equalWeights(k);
            case INCREASING:  return arithmeticWeights(k, true);
            case DECREASING:  return arithmeticWeights(k, false);
            case CUSTOM:      return normalize(spec.customRatios);
            case RANDOM:      return randomWeights(k, spec.seed);
            case BURST:       return burstWeights(k, spec.seed);
            default:          return equalWeights(Math.max(1, k));
        }
    }

    private static double[] equalWeights(int k) {
        double[] w = new double[k]; Arrays.fill(w, 1.0 / k); return w;
    }

    private static double[] arithmeticWeights(int k, boolean increasing) {
        double[] w = new double[k];
        double total = k * (k + 1) / 2.0;
        for (int i = 0; i < k; i++) {
            int rank = increasing ? (i + 1) : (k - i);
            w[i] = rank / total;
        }
        return w;
    }

    private static double[] normalize(double[] in) {
        if (in == null || in.length == 0) return new double[]{1.0};
        double sum = 0; for (double v : in) sum += Math.max(0, v);
        if (sum <= 0) return equalWeights(in.length);
        double[] w = new double[in.length];
        for (int i = 0; i < in.length; i++) w[i] = Math.max(0, in[i]) / sum;
        return w;
    }

    private static double[] randomWeights(int k, long seed) {
        Random rnd = new Random(seed);
        double[] w = new double[k]; double sum = 0;
        for (int i = 0; i < k; i++) { w[i] = 0.05 + rnd.nextDouble(); sum += w[i]; }
        for (int i = 0; i < k; i++) w[i] /= sum;
        return w;
    }

    /** BURST: ceil(k/4) batches get weight 5x, others 1x. */
    private static double[] burstWeights(int k, long seed) {
        Random rnd = new Random(seed);
        double[] w = new double[k];
        int burst = Math.max(1, (int) Math.ceil(k / 4.0));
        Set<Integer> burstIdx = new HashSet<>();
        while (burstIdx.size() < burst) burstIdx.add(rnd.nextInt(k));
        double small = 1.0, big = 5.0, sum = 0;
        for (int i = 0; i < k; i++) {
            w[i] = burstIdx.contains(i) ? big : small;
            sum += w[i];
        }
        for (int i = 0; i < k; i++) w[i] /= sum;
        return w;
    }

    private static List<List<Sequence>> splitByWeights(List<Sequence> all, double[] weights) {
        List<List<Sequence>> out = new ArrayList<>();
        int n = all.size();
        if (n == 0) { out.add(new ArrayList<>()); return out; }
        if (weights == null || weights.length == 0) { out.add(new ArrayList<>(all)); return out; }
        int k = weights.length;
        int[] sizes = new int[k];
        int allocated = 0;
        for (int i = 0; i < k; i++) { sizes[i] = (int) Math.floor(weights[i] * n); allocated += sizes[i]; }
        int remaining = n - allocated;
        for (int i = 0; remaining > 0 && i < k; i++, remaining--) sizes[i]++;
        int idx = 0;
        for (int i = 0; i < k; i++) {
            int end = Math.min(n, idx + sizes[i]);
            out.add(new ArrayList<>(all.subList(idx, end)));
            idx = end;
        }
        return out;
    }

    private static List<Integer> batchSizes(List<List<Sequence>> batches) {
        List<Integer> sizes = new ArrayList<>(batches.size());
        for (List<Sequence> b : batches) sizes.add(b.size());
        return sizes;
    }
}
