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
    public static final String DATASET_DIR = "datasets/";
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
    public static final double DEFAULT_ALPHA  = 0.5;
    public static final double DEFAULT_DELTA0 = 1e-4;
    public static final double D_INIT_RATIO = 0.5; // 50% init per paper

    // === TIMEOUT PER ALGORITHM RUN ===
    // Mỗi lời gọi measure() bị bao trong ExecutorService timeout (60 phút - cân bằng:
    // đủ rộng cho Adaptive (<5 phút) + baseline ở Su cao có cơ hội xong, nhưng không
    // kéo dài quá mức khi baseline OT trên dữ liệu dày/thưa).
    // Khi timeout: cancel Adaptive flag, future.cancel(true), ghi opType "_OT",
    // runtime_ms = -2 (phân biệt với -1 = FAILED).
    public static final long TIMEOUT_MINUTES = Long.getLong("timeout.min", 60);
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
                List<Sequence> full = DatasetLoader.loadDataset(DATASET_DIR + ds.name + "_seq.txt",
                                                                DATASET_DIR + ds.name + "_eui.txt");
                if (full.isEmpty()) continue;
                List<Sequence> dInit = headPercent(full, D_INIT_RATIO);
                double totalUtil = sumUtility(dInit);
                String[] algos = {"USpan", "HUSP-ULL", "IncUSP-Miner+", "Pre-HUSPM", "Adaptive-Full"};
                for (double su : ds.suSweep) {
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

    // ==================================================================
    // SC2 - INCREMENTAL SEQUENCE: D_init=50% + 5 batch x 10% UNIFORM
    // ==================================================================
    private static void runSC2_IncrementalSequence() throws IOException {
        System.out.println("\n--- [SC2] Incremental sequence (5 batch x 10% UNIFORM) ---");
        try (ResultCollector col = new ResultCollector(RESULTS_DIR + "SC2_incremental.csv")) {
            for (DatasetConfig ds : DATASETS) {
                List<Sequence> full = DatasetLoader.loadDataset(DATASET_DIR + ds.name + "_seq.txt",
                                                                DATASET_DIR + ds.name + "_eui.txt");
                if (full.isEmpty()) continue;
                List<Sequence> dInit = headPercent(full, D_INIT_RATIO);
                List<Sequence> rest = tailFrom(full, dInit.size());
                List<List<Sequence>> batches = splitByWeights(rest, equalWeights(5));
                runIncrementalScenario(ds.name, ds.defaultSu, dInit, batches, col, "SC2");
            }
        }
    }

    // ==================================================================
    // SC3 - SENSITIVITY ON Su (3-dataset subset)
    // ==================================================================
    private static void runSC3_SensitivitySu() throws IOException {
        System.out.println("\n--- [SC3] Su sensitivity ---");
        try (ResultCollector col = new ResultCollector(RESULTS_DIR + "SC3_su.csv")) {
            String[] algos = {"USpan", "HUSP-ULL", "IncUSP-Miner+", "Pre-HUSPM", "Adaptive-Full"};
            for (DatasetConfig ds : DATASETS) {
                if (!SUBSET_3.contains(ds.name)) continue;
                List<Sequence> full = DatasetLoader.loadDataset(DATASET_DIR + ds.name + "_seq.txt",
                                                                DATASET_DIR + ds.name + "_eui.txt");
                if (full.isEmpty()) continue;
                double totalUtil = sumUtility(full);
                for (double su : ds.suSweep) {
                    for (String algo : algos) {
                        ExperimentMetrics m = runSingle(algo, ds.name, full, totalUtil, su, 0, "SC3_su=" + su);
                        if (m != null) col.log(m);
                        forceFullGC(); // Cleanup heap giữa các thuật toán
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
                if (!SUBSET_3.contains(ds.name)) continue;
                List<Sequence> full = DatasetLoader.loadDataset(DATASET_DIR + ds.name + "_seq.txt",
                                                                DATASET_DIR + ds.name + "_eui.txt");
                if (full.isEmpty()) continue;
                double totalUtil = sumUtility(full);

                // (a) Sl/Su sensitivity - chạy SONG SONG Pre-HUSPM + Adaptive với
                //     CÙNG slRatio mỗi iteration để so sánh apple-to-apple công bằng.
                //     Trước đây Pre-HUSPM sweep slRatios={0.3..0.9} còn Adaptive
                //     bị FIX cứng ở DEFAULT_SL_MIN_RATIO → không thể so sánh trực tiếp.
                //
                //     Mỗi instance được scope-hẹp trong block → ra khỏi block là
                //     unreachable; forceFullGC() ngay sau thu hồi heap.
                for (double slRatio : slRatios) {
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
                                ds.defaultSu, DEFAULT_ALPHA, DEFAULT_DELTA0, slRatio,
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
                if (!SUBSET_3.contains(ds.name)) continue;
                List<Sequence> full = DatasetLoader.loadDataset(DATASET_DIR + ds.name + "_seq.txt",
                                                                DATASET_DIR + ds.name + "_eui.txt");
                if (full.isEmpty()) continue;
                List<Sequence> dInit = headPercent(full, D_INIT_RATIO);
                List<Sequence> rest = tailFrom(full, dInit.size());

                for (int k : kList) for (BatchSplitMode strat : strategies) {
                    long seed = SEEDS[0];
                    BatchSplitSpec spec = new BatchSplitSpec(strat, k, null, seed);
                    double[] weights = computeWeights(spec);
                    List<List<Sequence>> batches = splitByWeights(rest, weights);
                    String tag = "SC5_K=" + k + "_strat=" + strat.name();
                    System.out.println(">> [SC5] " + ds.name + " " + tag
                            + " -> sizes=" + batchSizes(batches));
                    runIncrementalScenario(ds.name, ds.defaultSu, dInit, batches, col, tag);
                }
            }
        }
    }

    // ==================================================================
    // SC6 - MIXED I/D/M 70:15:15 (Adaptive self-compare)
    // ==================================================================
    private static void runSC6_InsertDeleteModify() throws IOException {
        System.out.println("\n--- [SC6] Mixed I/D/M 70:15:15 (Adaptive) ---");
        try (ResultCollector col = new ResultCollector(RESULTS_DIR + "SC6_idm_mixed.csv")) {
            Random rnd = new Random(SEEDS[0]);
            for (DatasetConfig ds : DATASETS) {
                if (!SUBSET_3.contains(ds.name)) continue;
                List<Sequence> full = DatasetLoader.loadDataset(DATASET_DIR + ds.name + "_seq.txt",
                                                                DATASET_DIR + ds.name + "_eui.txt");
                if (full.size() < 20) {
                    System.out.println(">> [SC6] skipping " + ds.name + " (only " + full.size() + " sequences)");
                    continue;
                }

                List<Sequence> dInit = new ArrayList<>(headPercent(full, D_INIT_RATIO));
                List<Sequence> rest  = new ArrayList<>(tailFrom(full, dInit.size()));

                PreHUSPMAdaptiveAlgorithm.PreHUSPMAdaptiveAlgo adp = new PreHUSPMAdaptiveAlgorithm.PreHUSPMAdaptiveAlgo(
                        ds.defaultSu, DEFAULT_ALPHA, DEFAULT_DELTA0, DEFAULT_SL_MIN_RATIO,
                        PreHUSPMAdaptiveAlgorithm.PreHUSPMAdaptiveAlgo.AblationMode.FULL,
                        ENABLE_PATTERN_EXPORT);

                double initTotalUtil = sumUtility(dInit);
                boolean poisoned;
                announce("Adaptive-Full", ds.name, "SC6_INIT", 0);
                ExperimentMetrics mInit = measure("Adaptive-Full", ds.name, 0, "SC6_INIT",
                        () -> adp.runInitialMining(dInit, initTotalUtil),
                        () -> adp.candidateCount, () -> adp.patternCount,
                        () -> adp.lutDynValue, () -> adp.cubValue, () -> adp.cubTriggeredRescan,
                        null, () -> adp.prunedValueBound,
                            () -> adp.prelargeCount, () -> adp.uutValue, () -> adp.sftValue, adp::cancel);
                attachAdaptive(mInit, adp);
                col.log(mInit);
                poisoned = (mInit.runtimeMs < 0);

                int opCount = 10;
                int opSize = Math.max(1, rest.size() / (opCount * 2));
                List<Sequence> dSoFar = new ArrayList<>(dInit);
                double[] totalUtilBox = {initTotalUtil};

                for (int i = 0; i < opCount; i++) {
                    double roll = rnd.nextDouble();
                    int iter = i + 1;
                    if (roll < 0.70) {
                        // Insertion
                        List<Sequence> delta = new ArrayList<>();
                        for (int j = 0; j < opSize && !rest.isEmpty(); j++) delta.add(rest.remove(0));
                        if (delta.isEmpty()) break;
                        double newTotal = totalUtilBox[0] + sumUtility(delta);
                        List<Sequence> snapshot = new ArrayList<>(dSoFar); snapshot.addAll(delta);
                        if (poisoned) {
                            col.log(skippedMetric("Adaptive-Full", ds.name, iter, "SC6_INS"));
                        } else {
                            announce("Adaptive-Full", ds.name, "SC6_INS", iter);
                            ExperimentMetrics m = measure("Adaptive-Full", ds.name, iter, "SC6_INS",
                                    () -> adp.runIncrementalMining(delta, snapshot, newTotal),
                                    () -> adp.candidateCount, () -> adp.patternCount,
                                    () -> adp.lutDynValue, () -> adp.cubValue, () -> adp.cubTriggeredRescan,
                                    null, () -> adp.prunedValueBound,
                            () -> adp.prelargeCount, () -> adp.uutValue, () -> adp.sftValue, adp::cancel);
                            attachAdaptive(m, adp);
                            col.log(m);
                            if (m.runtimeMs < 0) poisoned = true;   // -1 = FAILED, -2 = OT - đều poison
                        }
                        dSoFar.addAll(delta);
                        totalUtilBox[0] = newTotal;
                    } else if (roll < 0.85) {
                        // Deletion
                        if (dSoFar.size() < opSize + 1) continue;
                        List<Sequence> toDel = pickRandom(dSoFar, opSize, rnd);
                        List<Sequence> dAfter = new ArrayList<>(dSoFar);
                        dAfter.removeAll(toDel);
                        double newTotal = sumUtility(dAfter);
                        if (poisoned) {
                            col.log(skippedMetric("Adaptive-Full", ds.name, iter, "SC6_DEL"));
                        } else {
                            announce("Adaptive-Full", ds.name, "SC6_DEL", iter);
                            ExperimentMetrics m = measure("Adaptive-Full", ds.name, iter, "SC6_DEL",
                                    () -> adp.runDeletion(toDel, dAfter, newTotal),
                                    () -> adp.candidateCount, () -> adp.patternCount,
                                    () -> adp.lutDynValue, () -> adp.cubValue, () -> adp.cubTriggeredRescan,
                                    null, () -> adp.prunedValueBound,
                            () -> adp.prelargeCount, () -> adp.uutValue, () -> adp.sftValue, adp::cancel);
                            attachAdaptive(m, adp);
                            col.log(m);
                            if (m.runtimeMs < 0) poisoned = true;   // -1 = FAILED, -2 = OT - đều poison
                        }
                        dSoFar = dAfter;
                        totalUtilBox[0] = newTotal;
                    } else {
                        // Modification
                        if (dSoFar.size() < opSize + 1 || rest.size() < opSize) continue;
                        List<Sequence> oldSeqs = pickRandom(dSoFar, opSize, rnd);
                        List<Sequence> newSeqs = new ArrayList<>();
                        for (int j = 0; j < opSize && !rest.isEmpty(); j++) newSeqs.add(rest.remove(0));
                        List<Sequence> dAfter = new ArrayList<>(dSoFar);
                        dAfter.removeAll(oldSeqs);
                        dAfter.addAll(newSeqs);
                        double newTotal = sumUtility(dAfter);
                        if (poisoned) {
                            col.log(skippedMetric("Adaptive-Full", ds.name, iter, "SC6_MOD"));
                        } else {
                            announce("Adaptive-Full", ds.name, "SC6_MOD", iter);
                            ExperimentMetrics m = measure("Adaptive-Full", ds.name, iter, "SC6_MOD",
                                    () -> adp.runModification(oldSeqs, newSeqs, dAfter, newTotal),
                                    () -> adp.candidateCount, () -> adp.patternCount,
                                    () -> adp.lutDynValue, () -> adp.cubValue, () -> adp.cubTriggeredRescan,
                                    null, () -> adp.prunedValueBound,
                            () -> adp.prelargeCount, () -> adp.uutValue, () -> adp.sftValue, adp::cancel);
                            attachAdaptive(m, adp);
                            col.log(m);
                            if (m.runtimeMs < 0) poisoned = true;   // -1 = FAILED, -2 = OT - đều poison
                        }
                        dSoFar = dAfter;
                        totalUtilBox[0] = newTotal;
                    }
                }
                // Hết dataset → drop adp (out of scope) + forceFullGC trước dataset kế.
            }
            forceFullGC();
        }
    }

    // ==================================================================
    // SC7 - ABLATION ĐÓNG GÓP: C3 (IUB) / C4 (Block-Index) / C5 (Adaptive rescan). C1 (cận PEU áp trước materialize) là cố hữu.
    // ==================================================================
    private static void runSC7_Ablation() throws IOException {
        System.out.println("\n--- [SC7] Ablation C3(IUB)/C4(Block)/C5(rescan) ---");
        try (ResultCollector col = new ResultCollector(RESULTS_DIR + "SC7_ablation.csv")) {
            for (DatasetConfig ds : DATASETS) {
                List<Sequence> full = DatasetLoader.loadDataset(DATASET_DIR + ds.name + "_seq.txt",
                                                                DATASET_DIR + ds.name + "_eui.txt");
                if (full.isEmpty()) continue;
                double totalUtil = sumUtility(full);

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
                forceFullGC();
            }
        }
    }

    private static void logAblation(ResultCollector col, DatasetConfig ds, List<Sequence> full,
                                    double totalUtil, String tag, boolean useBlockIndex,
                                    boolean useIub) {
        PreHUSPMAdaptiveAlgorithm.PreHUSPMAdaptiveAlgo adp = new PreHUSPMAdaptiveAlgorithm.PreHUSPMAdaptiveAlgo(
                ds.defaultSu, DEFAULT_ALPHA, DEFAULT_DELTA0, DEFAULT_SL_MIN_RATIO,
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
                ds.defaultSu, DEFAULT_ALPHA, DEFAULT_DELTA0, DEFAULT_SL_MIN_RATIO,
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
        if (!ADAPTIVE_ONLY) {
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

        // -------- Algorithm 2: Pre-HUSPM (isolated scope) --------
        if (!ADAPTIVE_ONLY) {
            PreHUSPMAlgorithm.PreHUSPMAlgo preh = new PreHUSPMAlgorithm.PreHUSPMAlgo(
                    Su, DEFAULT_PRE_HUSPM_SL_RATIO, ENABLE_PATTERN_EXPORT);
            boolean poisoned = false;
            announce("Pre-HUSPM", dsName, tag + "_INIT", 0);
            ExperimentMetrics m0 = measure("Pre-HUSPM", dsName, 0, tag + "_INIT",
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
                if (!poisoned && !memoryGuardOk("Pre-HUSPM", dsName, tag + "_INC")) poisoned = true;
                if (poisoned) {
                    col.log(skippedMetric("Pre-HUSPM", dsName, batchIdx, tag + "_INC"));
                } else {
                    List<Sequence> snapshot = new ArrayList<>(dSoFar);
                    snapshot.addAll(delta);
                    final double finalNewTotal = newTotal;
                    announce("Pre-HUSPM", dsName, tag + "_INC", batchIdx);
                    ExperimentMetrics mb = measure("Pre-HUSPM", dsName, batchIdx, tag + "_INC",
                            () -> preh.runIncrementalMining(delta, snapshot, finalNewTotal),
                            () -> preh.candidateCount, () -> preh.patternCount, null, null);
                    attachPreHuspm(mb, preh);
                    col.log(mb);
                    if (mb.runtimeMs < 0) poisoned = true;
                }
                dSoFar.addAll(delta);
                rolling = newTotal;
            }
        }
        forceFullGC();

        // -------- Algorithm 3: Adaptive-Full (isolated scope) --------
        {
            PreHUSPMAdaptiveAlgorithm.PreHUSPMAdaptiveAlgo adp = new PreHUSPMAdaptiveAlgorithm.PreHUSPMAdaptiveAlgo(
                    Su, DEFAULT_ALPHA, DEFAULT_DELTA0, DEFAULT_SL_MIN_RATIO,
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
    }

    private static ExperimentMetrics runSingle(String algo, String dsName, List<Sequence> db,
                                               double totalUtil, double su, int iter, String tag) {
        if (ADAPTIVE_ONLY && !algo.equals("Adaptive-Full")) return null; // chỉ chạy lại Adaptive
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
                            su, DEFAULT_ALPHA, DEFAULT_DELTA0, DEFAULT_SL_MIN_RATIO,
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
     * Chạy task trên worker thread riêng với TIMEOUT 90 PHÚT (cấu hình qua TIMEOUT_MINUTES).
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

        // === PEAK HEAP SAMPLER: lấy ĐỈNH heap THẬT trong lúc chạy (thay vì delta cuối,
        // vốn bỏ sót đỉnh transient đã bị GC thu hồi trước heapAfter). Daemon, poll 50ms,
        // KHÔNG allocate trong vòng lặp (native mem-read + atomic) → an toàn cả khi heap đầy.
        final java.util.concurrent.atomic.AtomicLong peakUsed =
                new java.util.concurrent.atomic.AtomicLong(heapBefore);
        final java.util.concurrent.atomic.AtomicBoolean sampling =
                new java.util.concurrent.atomic.AtomicBoolean(true);
        Thread heapSampler = new Thread(() -> {
            // Bọc toàn bộ: nếu heap cạn, thread này có thể bị ném OOM tại safepoint →
            // KHÔNG để rơi vào UncaughtExceptionHandler (nó in stack-trace = cấp phát →
            // OOM lần 2, gây nhiễu "thrown from UncaughtExceptionHandler"). Nuốt im lặng.
            try {
                Runtime r = Runtime.getRuntime();
                while (sampling.get()) {
                    long used = r.totalMemory() - r.freeMemory();
                    long prev = peakUsed.get();
                    while (used > prev && !peakUsed.compareAndSet(prev, used)) prev = peakUsed.get();
                    try { Thread.sleep(50); } catch (InterruptedException e) { break; }
                }
            } catch (Throwable ignored) {}
        }, "heap-sampler-" + algo);
        heapSampler.setDaemon(true);
        heapSampler.start();

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
            try { task.run(); }
            catch (Throwable t) { thrown[0] = t; }
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
        m.runtimeMs = timedOut ? -2 : (failed ? -1 : runtime);
        // Peak THẬT: max(đỉnh-mẫu, heapAfter) trừ baseline → footprint thật của thuật toán.
        long peakHeap = Math.max(peakUsed.get(), heapAfter);
        m.peakHeapMb = Math.max(0, (peakHeap - heapBefore)) / (1024 * 1024);
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
