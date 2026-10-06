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
 * SC1 - Static baseline on D_init (Yan et al. Table 16 OD.Num) sweeping Su.
 * SC2 - Incremental: D_init per Yan et al. Table 16 + 100 EVEN batches.
 * SC3 - Su sensitivity on a 3-dataset subset.
 * SC4 - LUT_dyn sensitivity (Sl/Su for Pre-HUSPM; Delta0, alpha for Adaptive).
 * SC5 - Batch strategy grid (K in {100,67,50,25} x strategy in {EVEN, INC, DEC, BURST}).
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
    public static final double DEFAULT_SL_MIN_RATIO = common.Datasets.DEFAULT_SL_MIN_RATIO;
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
    /**
     * Shortest wait between two forced collections in live mode. The actual wait is the larger
     * of this and four times the last collection's duration - see the sampler in measure().
     *
     * It was a fixed 1000 ms. Two thirds of the proposal's SC1 cells finish in under a second,
     * and a run shorter than the interval is sampled only at its start, before it has allocated
     * anything, and after it ends, when its working set is already garbage. What was reported
     * as its peak was what it kept, while a slower baseline was sampled throughout and credited
     * with its true peak: a bias toward whichever algorithm is faster, which here is the one
     * being proposed.
     */
    public static final long MEM_SAMPLE_MS = Long.getLong("mem.interval.ms", 20L);

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
    /**
     * Superseded by common.Datasets.dInitCount, which carries Yan et al. Table 16's OD.Num
     * per database. Kept only as the fallback for a database not in that table.
     */
    public static final double D_INIT_RATIO = 0.5;

    /** Increments per incremental scenario; -Dbatch.count overrides for cost probes. */
    public static final int BATCH_COUNT = Integer.getInteger("batch.count", common.Datasets.BATCH_COUNT);

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
        /** Whether these thresholds were measured against the pinned release. */
        final boolean calibrated;
        /** Pattern counts the sweep produced, or why there are none yet. */
        final String note;
        /** Whether it can be measured on the whole database, not only on D_init. */
        boolean fullScopeOk = true;
        /** Sequences in D_init, from Yan et al. Table 16 where the database is theirs. */
        int dInitCount = -1;

        DatasetConfig(String name, double defaultSu, double[] suSweep) {
            this(name, defaultSu, suSweep, true, "");
        }

        DatasetConfig(String name, double defaultSu, double[] suSweep,
                      boolean calibrated, String note) {
            this.name = name; this.defaultSu = defaultSu; this.suSweep = suSweep;
            this.calibrated = calibrated; this.note = note;
        }
    }

    /**
     * Measuring at a threshold chosen for different data is not a rerun of anything: the
     * pattern counts, and with them the amount of work, are simply different. Rather than
     * let that happen quietly, an uncalibrated database is skipped with a message saying
     * what to run. Override with -Duncalibrated=true when the point is to explore.
     */
    public static final boolean ALLOW_UNCALIBRATED = Boolean.getBoolean("uncalibrated");

    // ================================================================
    //  SPENDING THE MEASUREMENT BUDGET
    // ================================================================
    //
    // Covering every database in every scenario is not negotiable: review asked why some
    // databases appear and others do not, and the honest answer is to measure them. The
    // grid is datasets x scenarios x algorithms x thresholds x seeds x repeats, so the
    // question is where to stop paying without losing anything.
    //
    // Three places, each cutting a different kind of waste.
    //
    //   1. A configuration that failed is not measured again. Overtime and out-of-memory
    //      are properties of the configuration, not samples of a distribution; repeating
    //      one buys nothing and costs the full timeout every time. On the submitted
    //      results this alone is hours: the failures cluster on the expensive databases.
    //
    //   2. Repeats are for measurement noise, and noise matters in proportion to the
    //      measurement. A call of a second repeated three times costs three seconds; a
    //      call of eight minutes repeated three times costs twenty-four. The long call is
    //      also the one where JIT and GC noise is proportionally smallest. So repeats stop
    //      once a configuration has shown it is slow, and the row records how many
    //      actually ran so nothing is reported as an average of three that was not.
    //
    //   3. Cheap databases run first, so an interrupted campaign leaves whole databases
    //      finished rather than every database half done.

    /** Above this, a configuration is measured once rather than repeated. */
    public static final long REPEAT_MAX_MS = Long.getLong("repeat.maxms", 60_000L);

    /** Configurations already seen to fail: algorithm|dataset|opType. */
    private static final Set<String> FAILED_ONCE = new HashSet<>();
    /** Configurations already seen to be slow enough that repeating is not worth it. */
    private static final Set<String> TOO_SLOW = new HashSet<>();

    private static String cfgKey(String algo, String dataset, String opType) {
        return algo + "|" + dataset + "|" + opType;
    }

    /** True when this repeat should be skipped. Repeat 0 always runs. */
    private static boolean skipRepeat(String algo, String dataset, String opType, int repeat) {
        if (repeat == 0) return false;
        String k = cfgKey(algo, dataset, opType);
        if (FAILED_ONCE.contains(k)) {
            System.out.println("   skip repeat " + repeat + " of " + k
                    + ": the configuration already failed, and a failure is not a sample");
            return true;
        }
        if (TOO_SLOW.contains(k)) {
            System.out.println("   skip repeat " + repeat + " of " + k
                    + ": first call over " + (REPEAT_MAX_MS / 1000) + "s");
            return true;
        }
        return false;
    }

    /**
     * Whether a measured call failed: -1 (failed, out of memory, over budget) or -2 (overtime).
     *
     * A negative runtime is not by itself a failure. In live memory mode a call that completed
     * records -3, because the collections forced at every sample make its timing meaningless.
     * Every incremental pipeline used to test runtimeMs < 0, so in live mode the initial mine
     * of every arm counted as failed and all 100 increments were skipped as poisoned: the first
     * SC2 memory campaign measured nothing but the initial mines.
     */
    private static boolean failedRun(ExperimentMetrics m) {
        return m == null || m.runtimeMs == -1 || m.runtimeMs == -2;
    }

    /**
     * Live mode's memory baseline for an incremental arm, or -1 to measure each call on its own.
     *
     * measure() reads memory against the heap at the start of the call. For a static mine that
     * is right: the call is the whole run. For an incremental arm it is not. The heap at the
     * start of an increment already holds everything the algorithm kept from the increments
     * before it, so each row recorded only what its increment added - under a megabyte on SIGN,
     * rounded to zero - and the state an incremental method keeps, the thing its memory claim
     * is about, was recorded nowhere. With the baseline fixed once per arm, before the
     * algorithm exists, an increment's mem_retained_mb is everything the algorithm holds after
     * it and mem_live_mb is the peak during it, state included. Remine-from-scratch, which
     * starts a new miner every increment, reports the peak of each fresh mine.
     *
     * The harness's own list of the sequences seen so far grows along the stream too and is
     * counted the same way in every arm, so it shifts all arms equally.
     */
    private static long memArmBaseline = -1;

    /** Fix the baseline of an incremental arm. Live mode only; call before the arm's algorithm exists. */
    private static void beginMemArm() {
        if (!MEM_LIVE) { memArmBaseline = -1; return; }
        forceFullGC();
        Runtime rt = Runtime.getRuntime();
        memArmBaseline = rt.totalMemory() - rt.freeMemory();
    }

    /** Back to measuring each call on its own, as a static mine is measured. */
    private static void endMemArm() { memArmBaseline = -1; }

    /** Record what the call turned out to be, so later repeats can be skipped. */
    private static void noteOutcome(ExperimentMetrics m) {
        if (m == null) return;
        String k = cfgKey(m.algorithm, m.dataset, m.opType.replaceAll("_(OT|OOM|BUDGET|FAILED|SKIPPED_POISONED)$", ""));
        if (failedRun(m)) FAILED_ONCE.add(k);
        else if (m.runtimeMs > REPEAT_MAX_MS) TOO_SLOW.add(k);
    }

    /**
     * Databases ordered cheapest first, by measured cost per call on the submitted
     * results. An interrupted campaign then leaves whole databases complete. FIFA is last
     * on purpose: at 450 s per call it costs more than the other five together.
     */
    private static final List<String> COST_ORDER = List.of(
            "LEVIATHAN", "SIGN", "BMS1", "KOSARAK", "BIBLE",
            // TAFENG has no submitted results to take a cost from. Placed here on the only
            // evidence there is, its calibration probes, which finish in under a second at
            // every threshold of its sweep. Move it once a run has measured it.
            "TAFENG", "C8T1S5I8N5K", "FIFA");

    private static List<DatasetConfig> inCostOrder(List<DatasetConfig> in) {
        List<DatasetConfig> out = new ArrayList<>(in);
        out.sort((a, b) -> {
            int ia = COST_ORDER.indexOf(a.name), ib = COST_ORDER.indexOf(b.name);
            return Integer.compare(ia < 0 ? 99 : ia, ib < 0 ? 99 : ib);
        });
        return out;
    }

    /**
     * Whether a scenario that reaches beyond D_init may run on this database.
     *
     * Only SC1 stays on D_init. SC3 and SC4 begin on the whole database and SC2, SC5 and SC6
     * grow to it, so a database that cannot be mined there produces a table of timeouts rather
     * than a measurement. Skipping it says so once, at the start, instead.
     */
    private static boolean scopeOk(DatasetConfig ds, String scenario) {
        if (ds.fullScopeOk || "SC1".equals(scenario)) return true;
        System.out.println(">> skipping " + ds.name + " for " + scenario
                + ": measurable on D_init only (" + ds.note + ").");
        return false;
    }

    private static boolean calibrationOk(DatasetConfig ds) {
        if (ds.calibrated || ALLOW_UNCALIBRATED) return true;
        System.out.println(">> skipping " + ds.name + ": thresholds not calibrated for the "
                + "pinned release (" + ds.note + ").");
        System.out.println("   java -cp build experiments.ThresholdScan " + ds.name
                + " --out results/thresholds     (or -Duncalibrated=true to run anyway)");
        return false;
    }

    /**
     * Datasets to run - PRODUCTION SET.
     *   - SIGN, LEVIATHAN, FIFA: dense, long sequences.
     *   - BMS1, KOSARAK, TAFENG: sparse or short sequences.
     *   - C8T1S5I8N5K: the only one with more than one item per event.
     * The "paper" dataset (4 sequences) is for the TEST-mode smoke test only.
     *
     * Heap: LEVIATHAN and SIGN need -Xmx8g or more.
     */
    /**
     * Every database of the pinned release, with the thresholds each one is measured at.
     *
     * The list must name every database that will be run, not just the ones already set
     * up. A table that quietly lags behind the data makes "all databases were calibrated"
     * false while looking complete, so entries that are not ready are present and marked
     * rather than absent.
     *
     * Thresholds are ratios of total utility. They stay well defined across releases but
     * not comparable: the pinned release changed the shape of the utility distribution, so
     * the same ratio selects a different number of patterns. Each sweep below was re-chosen
     * with experiments.ThresholdScan to reproduce the pattern counts of the submitted
     * experiments, so the runs pose comparable work; the raw search output is in
     * results/thresholds/thresholds.csv.
     */
    private static final List<DatasetConfig> DATASETS_PRODUCTION = buildProduction();

    /**
     * The table itself lives in common.Datasets so that verify.CorrectnessVerifier reads the
     * same thresholds this runner measures at. It used to keep its own copy, which went
     * stale without anyone noticing.
     */
    private static List<DatasetConfig> buildProduction() {
        List<DatasetConfig> out = new ArrayList<>();
        for (common.Datasets.Entry e : common.Datasets.all()) {
            DatasetConfig c = new DatasetConfig(e.name, e.defaultUut, e.sweep, e.calibrated, e.note);
            c.fullScopeOk = e.fullScopeOk;
            c.dInitCount = e.dInitCount;
            out.add(c);
        }
        return List.copyOf(out);
    }

    /** Dataset rút gọn cho smoke-test (paper = 4 chuỗi, < 1s). */
    private static final List<DatasetConfig> DATASETS_TEST = List.of(
            new DatasetConfig("paper", 0.001, new double[]{0.0005, 0.001, 0.002, 0.005})
    );

    /** Chọn dataset thực tế: TEST mode hay PRODUCTION mode. */
    private static List<DatasetConfig> DATASETS = DATASETS_PRODUCTION;
    /** Resolved at start-up: examples/ in TEST mode, datasets/ otherwise. */
    private static String datasetDir = DATASET_DIR;

    /**
     * The three databases SC3, SC4, SC5 and SC6 are measured on.
     *
     * KOSARAK was the third and is now C8T1S5I8N5K. The reason is SC5, which measures a grid
     * of four increment counts by four arrival strategies. Measured on SIGN, that grid costs
     * 10.5 times what SC2 costs for the same arm: halving K saves only 24% rather than 50%,
     * because each batch carries a fixed cost and the rescans do not thin out in proportion.
     * Multiplying KOSARAK's SC2 time by 10.5 gives about fifty hours for its four working
     * arms, and over eighty for Pre-HUSPM-TSU alone, which already fails to finish SC2.
     *
     * The replacement is not a concession. KOSARAK and BMS1 are both sparse click-streams, so
     * the old three covered dense-dense-sparse; SIGN, LEVIATHAN and C8T1S5I8N5K cover small
     * and dense, medium, and large - and C8T1S5I8N5K is the only database here that reaches
     * within-event extension, at 7.97 items per event where the other seven hold exactly one.
     * Losing it from these four scenarios would have left that case measured only in SC1,
     * SC2 and SC7.
     *
     * KOSARAK stays in SC1, SC2 and SC7, which is where its size earns its keep.
     */
    private static final Set<String> SUBSET_3 = new HashSet<>(Arrays.asList(
            "SIGN", "LEVIATHAN", "C8T1S5I8N5K"));

    /**
     * Databases that also get the C5 ablation, the incremental half of SC7.
     *
     * The other three SC7 arms are one full mine each and cost seconds. This one is not:
     * proposed_C5_off rescans on EVERY batch by design, so at BATCH_COUNT=100 it mines from
     * scratch a hundred times. Its cost is therefore a hundred times a full mine, and that
     * is what decides who can be in this set.
     *
     * KOSARAK was here and is now C8T1S5I8N5K. A full-scope mine of KOSARAK takes 438 s, so
     * proposed_C5_off alone would be of the order of twelve hours - for one arm, of one
     * scenario, on one database. C8T1S5I8N5K also replaced it in SUBSET_3, for the same kind
     * of reason, so the two sets now name the same databases and a reader does not have to
     * hold two different threes in mind.
     *
     * BMS1 stays listed although SC7 does not currently reach it: scopeOk filters it out
     * because SC7 mines the whole database and BMS1 cannot be mined there. If that ever
     * changes the ablation should cover it again, which is why the name is left rather than
     * deleted. Today this set delivers SIGN and C8T1S5I8N5K.
     */
    private static final Set<String> C5_ABLATION_SET = new HashSet<>(Arrays.asList(
            "BMS1", "SIGN", "C8T1S5I8N5K"));

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
        DATASETS = inCostOrder(testMode ? DATASETS_TEST : DATASETS_PRODUCTION);
        datasetDir = testMode ? EXAMPLE_DIR : DATASET_DIR;

        // STATUS answers "what is ready to run" without depending on any result file.
        // The calibrated thresholds live in the code, not in results/thresholds/, so a
        // machine that has just cloned is not missing anything by having no CSV yet.
        if (args != null) {
            for (String a : args) {
                for (String t : a.split("[,\s]+")) {
                    if (t.equalsIgnoreCase("STATUS")) { printStatus(); return; }
                }
            }
        }

        Set<String> scenarios = parseScenarios(args);
        System.out.println("=== HUSPM-Adapt-Prelarge AUTOMATED EXPERIMENT RUNNER ===");
        System.out.println("Mode: " + (testMode ? "TEST (paper dataset)" : "PRODUCTION")
                + " | Datasets: " + DATASETS.size() + " | Scenarios: " + scenarios);
        System.out.println("Heap max: " + (Runtime.getRuntime().maxMemory() / (1024 * 1024)) + " MB");
        if (SL_RATIO_OVERRIDE > 0) {
            System.out.println("### PROBE: r_l = " + SL_RATIO_OVERRIDE + " for every database, "
                    + "overriding common.Datasets. Not a campaign measurement.");
        }

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

    /** Print each database, its thresholds, and whether they were measured on this data. */
    private static void printStatus() {
        System.out.printf("%-14s %-9s %9s %5s %-34s %s%n", "database", "calibrated", "D_init", "r_l", "thresholds", "note");
        System.out.println("-".repeat(110));
        int ready = 0;
        for (DatasetConfig ds : DATASETS_PRODUCTION) {
            StringBuilder sw = new StringBuilder();
            for (int i = 0; i < ds.suSweep.length; i++) {
                if (i > 0) sw.append(' ');
                sw.append(String.format("%.5f", ds.suSweep[i]));
            }
            if (ds.calibrated) ready++;
            System.out.printf("%-14s %-9s %9s %5.2f %-34s %s%n",
                    ds.name, ds.calibrated ? "yes" : "NO",
                    ds.dInitCount > 0 ? String.format("%,d", ds.dInitCount) : "half",
                    slRatioFor(ds.name), sw, ds.note);
        }
        System.out.println("-".repeat(110));
        System.out.printf("%d of %d ready. Uncalibrated databases are skipped by every "
                        + "scenario; calibrate with:%n", ready, DATASETS_PRODUCTION.size());
        for (DatasetConfig ds : DATASETS_PRODUCTION) {
            if (!ds.calibrated) {
                System.out.println("  java -cp build experiments.ThresholdScan " + ds.name
                        + " --out results/thresholds");
            }
        }
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
                if (!calibrationOk(ds)) continue;
                List<Sequence> full = DatasetLoader.loadDataset(datasetDir + ds.name + "_seq.txt",
                                                                datasetDir + ds.name + "_eui.txt");
                if (full.isEmpty()) continue;
                List<Sequence> dInit = dInitFor(ds, full);
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
        System.out.println("\n--- [SC2] Incremental sequence (" + BATCH_COUNT + " batches, EVEN; D_init per Yan et al. Table 16) ---");
        try (ResultCollector col = new ResultCollector(RESULTS_DIR + "SC2_incremental.csv")) {
            for (DatasetConfig ds : DATASETS) {
                if (!datasetEnabled(ds.name)) continue;
                if (!calibrationOk(ds)) continue;
                if (!scopeOk(ds, "SC2")) continue;
                List<Sequence> full = DatasetLoader.loadDataset(datasetDir + ds.name + "_seq.txt",
                                                                datasetDir + ds.name + "_eui.txt");
                if (full.isEmpty()) continue;
                List<Sequence> dInit = dInitFor(ds, full);
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
                    List<List<Sequence>> batches = splitByWeights(stream, equalWeights(BATCH_COUNT));
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
                if (!calibrationOk(ds)) continue;
                if (!scopeOk(ds, "SC3")) continue;
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
                if (!calibrationOk(ds)) continue;
                if (!scopeOk(ds, "SC4")) continue;
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
                    // Each block answers to -Dalgo.only. Without the gates every arm of this scenario
                    // ran both algorithms at all four levels, so campaign.ps1 measured the whole of SC4
                    // once per arm name - five copies of the same eight rows.
                    // --- Pre-HUSPM @ slRatio ---
                    if (!ADAPTIVE_ONLY && algoEnabled("Pre-HUSPM")) {
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
                    if (algoEnabled("Adaptive-Full")) {
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
        int[] kList = common.Datasets.BATCH_COUNT_SWEEP;
        BatchSplitMode[] strategies = {BatchSplitMode.EVEN, BatchSplitMode.INCREASING,
                                        BatchSplitMode.DECREASING, BatchSplitMode.BURST};

        try (ResultCollector col = new ResultCollector(RESULTS_DIR + "SC5_batch_strategy.csv")) {
            for (DatasetConfig ds : DATASETS) {
                if (!datasetEnabled(ds.name)) continue;
                if (!calibrationOk(ds)) continue;
                if (!scopeOk(ds, "SC5")) continue;
                if (!SUBSET_3.contains(ds.name)) continue;
                List<Sequence> full = DatasetLoader.loadDataset(datasetDir + ds.name + "_seq.txt",
                                                                datasetDir + ds.name + "_eui.txt");
                if (full.isEmpty()) continue;
                List<Sequence> dInit = dInitFor(ds, full);
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
    /**
     * SC6's schedule: 70:15:15 as exact counts, not as probabilities.
     *
     * Drawing each operation independently at 70:15:15 gave ten operations whose mix depended
     * on the seed and the database: SIGN drew three deletions and no modification, LEVIATHAN
     * no deletion and two modifications, C8T1S5I8N5K one deletion. A deletion costs a few
     * milliseconds where an insertion costs a remine, so each database's total followed its
     * draw and the three could not be compared (results/sc6-random-draw). Every database now
     * gets the same operations; only their order depends on the seed.
     */
    static final int SC6_INSERTS = 14, SC6_DELETES = 3, SC6_MODIFIES = 3;

    private static List<Op> buildSC6Plan(List<Sequence> dInit, List<Sequence> rest,
                                         int opSize, Random rnd) {
        List<OpKind> kinds = new ArrayList<>();
        for (int i = 0; i < SC6_INSERTS; i++)  kinds.add(OpKind.INS);
        for (int i = 0; i < SC6_DELETES; i++)  kinds.add(OpKind.DEL);
        for (int i = 0; i < SC6_MODIFIES; i++) kinds.add(OpKind.MOD);
        Collections.shuffle(kinds, rnd);

        List<Op> plan = new ArrayList<>();
        List<Sequence> pool = new ArrayList<>(rest);
        List<Sequence> cur = new ArrayList<>(dInit);
        for (int i = 0; i < kinds.size(); i++) {
            int iter = i + 1;
            OpKind kind = kinds.get(i);
            // An operation that cannot be built is refused rather than skipped: a plan
            // quietly shorter than its schedule is the problem this schedule exists to remove.
            boolean needsPool = (kind != OpKind.DEL);
            if ((needsPool && pool.size() < opSize) || (kind != OpKind.INS && cur.size() <= opSize)) {
                throw new IllegalStateException("SC6: cannot build operation " + iter + " (" + kind
                        + ") with opSize=" + opSize + ", pool=" + pool.size() + ", current=" + cur.size());
            }
            if (kind == OpKind.INS) {
                List<Sequence> delta = new ArrayList<>(pool.subList(0, opSize));
                pool.subList(0, opSize).clear();
                plan.add(new Op(OpKind.INS, iter, delta, null));
                cur.addAll(delta);
            } else if (kind == OpKind.DEL) {
                List<Sequence> toDel = pickRandom(cur, opSize, rnd);
                plan.add(new Op(OpKind.DEL, iter, toDel, null));
                cur.removeAll(toDel);
            } else {
                List<Sequence> oldSeqs = pickRandom(cur, opSize, rnd);
                List<Sequence> newSeqs = new ArrayList<>(pool.subList(0, opSize));
                pool.subList(0, opSize).clear();
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
        System.out.println("\n--- [SC6] Mixed I/D/M, exactly " + SC6_INSERTS + ":" + SC6_DELETES + ":"
                + SC6_MODIFIES + " (Adaptive vs Remine-from-scratch) ---");
        try (ResultCollector col = new ResultCollector(RESULTS_DIR + "SC6_idm_mixed.csv")) {
            for (DatasetConfig ds : DATASETS) {
                if (!datasetEnabled(ds.name)) continue;
                if (!calibrationOk(ds)) continue;
                if (!scopeOk(ds, "SC6")) continue;
                if (!SUBSET_3.contains(ds.name)) continue;
                List<Sequence> full = DatasetLoader.loadDataset(datasetDir + ds.name + "_seq.txt",
                                                                datasetDir + ds.name + "_eui.txt");
                if (full.size() < 20) {
                    System.out.println(">> [SC6] skipping " + ds.name + " (only " + full.size() + " sequences)");
                    continue;
                }

                List<Sequence> dInit = dInitFor(ds, full);
                List<Sequence> rest  = new ArrayList<>(tailFrom(full, dInit.size()));
                double initTotalUtil = sumUtility(dInit);

                // One hundredth of the stream, the size of an SC2 increment. At a twentieth every
                // insertion exceeded the insertion budget and rescanned, 24 of 24, which measured
                // the regime SC5 already shows converging to remine rather than the one SC2 runs in.
                int opSize = Math.max(1, rest.size() / common.Datasets.BATCH_COUNT);

                // Day la kich ban ma seed thuc su quan trong: no quyet dinh thao tac nao
                // xay ra khi nao va tren chuoi nao. Ket luan cua SC6 khong duoc phu thuoc
                // vao mot chuoi thao tac may man.
                for (long seed : activeSeeds()) {
                    // Seed rieng cho tung dataset, de chuoi thao tac cua dataset nay khong
                    // phu thuoc vao viec dataset nao da chay truoc no.
                    Random rnd = new Random(seed + ds.name.hashCode());
                    List<Op> plan = buildSC6Plan(dInit, rest, opSize, rnd);
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

        beginMemArm();
        PreHUSPMAdaptiveAlgorithm.PreHUSPMAdaptiveAlgo adp = newAdaptive(ds.name, ds.defaultSu);
        announce(label, ds.name, "SC6_INIT", 0);
        ExperimentMetrics mInit = measureAdaptive(adp, label, ds.name, 0, "SC6_INIT",
                () -> adp.runInitialMining(dInit, initTotalUtil));
        col.log(mInit);
        boolean poisoned = (failedRun(mInit));
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
                PreHUSPMAdaptiveAlgorithm.PreHUSPMAdaptiveAlgo rm = newAdaptive(ds.name, ds.defaultSu);
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
            if (failedRun(m)) poisoned = true;
            cur = after; total = newTotal;
            forceFullGC();
        }
    }

    /**
     * The pre-large floor this database is measured at.
     *
     * Seven of the eight use 0.8; KOSARAK needs 0.85 because 0.8 times its lowest threshold
     * exceeds the candidate budget outright. Both the proposal and the Pre-HUSPM baseline
     * read it from here, because giving one of them a floor the other cannot use would not
     * be a comparison.
     */
    private static double slRatioFor(String dsName) {
        if (SL_RATIO_OVERRIDE > 0) return SL_RATIO_OVERRIDE;
        return common.Datasets.slMinRatio(dsName);
    }

    /**
     * -Dsl.ratio=0.5 replaces r_l for every database and every arm in this run.
     *
     * For probes of the floor/budget trade-off only. The insertion budget is proportional to
     * 1 - r_l, so lowering the floor widens it; SC4 shows what the lower floor costs to hold,
     * and this is how SC2 is re-run to see what it buys. Both algorithms read the same value,
     * as they do from common.Datasets, and every row records it in sl_min_eff.
     *
     * Refused when results.dir points into results/campaign: a campaign table holds one r_l
     * per database, the one in common.Datasets, and a probe written there would sit beside
     * the real arms looking like one of them.
     */
    private static final double SL_RATIO_OVERRIDE = parseSlOverride();

    private static double parseSlOverride() {
        String v = System.getProperty("sl.ratio", "").trim();
        if (v.isEmpty()) return -1;
        double r = Double.parseDouble(v);
        if (!(r > 0 && r < 1)) throw new IllegalArgumentException("sl.ratio must lie in (0, 1): " + v);
        String rd = System.getProperty("results.dir", "results/").replace(java.io.File.separatorChar, '/');
        if (rd.contains("results/campaign")) {
            throw new IllegalArgumentException("sl.ratio is for probes; refusing to write into "
                    + rd + ". Use -Dresults.dir=results/probe-rl/...");
        }
        return r;
    }

    private static PreHUSPMAdaptiveAlgorithm.PreHUSPMAdaptiveAlgo newAdaptive(String dsName, double su) {
        return new PreHUSPMAdaptiveAlgorithm.PreHUSPMAdaptiveAlgo(
                su, DEFAULT_KAPPA, DEFAULT_DELTA0, slRatioFor(dsName),
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
    // SC7 - ABLATION: C3 (IUB) on a static mine; C4 (block index) and C5 (adaptive rescan) on
    // the incremental stream, where they act. C1 (the PEU bound before materialising) is inherent.
    // ==================================================================
    private static void runSC7_Ablation() throws IOException {
        System.out.println("\n--- [SC7] Ablation C3(IUB)/C4(Block)/C5(rescan) ---");
        try (ResultCollector col = new ResultCollector(RESULTS_DIR + "SC7_ablation.csv")) {
            for (DatasetConfig ds : DATASETS) {
                if (!datasetEnabled(ds.name)) continue;
                if (!calibrationOk(ds)) continue;
                if (!scopeOk(ds, "SC7")) continue;
                List<Sequence> full = DatasetLoader.loadDataset(datasetDir + ds.name + "_seq.txt",
                                                                datasetDir + ds.name + "_eui.txt");
                if (full.isEmpty()) continue;
                double totalUtil = sumUtility(full);

                // Ablation khong co ngau nhien; chi lap lai de do nhieu JIT/GC.
                for (int r = 0; r < REPEATS; r++) {
                setRunContext(SEEDS[0], r);

                // --- Static ablation (one mine of the whole database): C3, the IUB bound, acts
                //     inside the miner, so a single mine is where it shows. C1 is inherent. ---
                logAblation(col, ds, full, totalUtil, "proposed_full", true, true);        // đề xuất đầy đủ
                forceFullGC();
                logAblation(col, ds, full, totalUtil, "proposed_no_IUB", true, false);     // -C3
                forceFullGC();

                // --- Incremental ablations, on the representative set: C5 (adaptive rescan)
                //     and C4 (block index) act only while the database grows, and so does C2,
                //     the adaptive pre-large band, which is recomputed at every increment.
                //     proposed_C5_on is the full proposal on that stream and the reference
                //     for all three. ---
                if (C5_ABLATION_SET.contains(ds.name)) {
                    logIncrementalAblation(col, ds, full, "proposed_C5_on",  "SC7_C5", true,  true,  true);
                    forceFullGC();
                    logIncrementalAblation(col, ds, full, "proposed_C5_off", "SC7_C5", false, true,  true);  // -C5
                    forceFullGC();
                    logIncrementalAblation(col, ds, full, "proposed_no_block_index", "SC7_C4", true, false, true); // -C4
                    forceFullGC();
                    logIncrementalAblation(col, ds, full, "proposed_no_C2", "SC7_C2", true, true, false);  // -C2
                    forceFullGC();
                }

                // Pre-HUSPM reference. algoEnabled as well as ADAPTIVE_ONLY: campaign.ps1
                // runs one arm per JVM, so without it this reference repeats once for every
                // ablation in the list and the CSV carries six copies of one measurement.
                if (!ADAPTIVE_ONLY && algoEnabled("Pre-HUSPM")) {
                endMemArm();
                announce("Pre-HUSPM", ds.name, "SC7_ref", 0);
                PreHUSPMAlgorithm.PreHUSPMAlgo pre = new PreHUSPMAlgorithm.PreHUSPMAlgo(
                        ds.defaultSu, slRatioFor(ds.name), ENABLE_PATTERN_EXPORT);
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
        endMemArm();   // static: one mine, measured against the heap at the start of the call
        // SC7's arms carry their own names - proposed_full, proposed_no_IUB and the rest -
        // and until now nothing here consulted -Dalgo.only. campaign.ps1 gives each arm its
        // own JVM, so running SC7 through it repeated every ablation once per name in the
        // algorithm list: five times the work, five copies of each row.
        if (!algoEnabled(tag)) return;
        PreHUSPMAdaptiveAlgorithm.PreHUSPMAdaptiveAlgo adp = new PreHUSPMAdaptiveAlgorithm.PreHUSPMAdaptiveAlgo(
                ds.defaultSu, DEFAULT_KAPPA, DEFAULT_DELTA0, slRatioFor(ds.name),
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
     * The incremental ablations: D_init, then BATCH_COUNT equal increments, one row per
     * increment as SC2 records them.
     *
     * C5 (adaptive rescan) and C4 (block index) act only while the database grows. C4 in
     * particular is read nowhere but on an increment that does not rescan -
     * evaluatePatternUtilityWithIndex has a single caller - so the static mine this ablation
     * used to run with the index off did exactly the work of one with it on, and would have
     * reported C4 as contributing nothing. Per-increment rows are what let its effect be read
     * off the increments where it can have one, those with cub_triggered = false; a single
     * total would bury it under the rescans, which carry almost all of the time.
     */
    private static void logIncrementalAblation(ResultCollector col, DatasetConfig ds, List<Sequence> full,
                                               String tag, String opTag,
                                               boolean useAdaptiveRescan, boolean useBlockIndex,
                                               boolean useAdaptiveBand) {
        if (!algoEnabled(tag)) return;
        List<Sequence> dInit = dInitFor(ds, full);
        List<Sequence> rest = tailFrom(full, dInit.size());
        List<List<Sequence>> batches = splitByWeights(rest, equalWeights(BATCH_COUNT));
        final double initTotal = sumUtility(dInit);
        beginMemArm();
        PreHUSPMAdaptiveAlgorithm.PreHUSPMAdaptiveAlgo adp = new PreHUSPMAdaptiveAlgorithm.PreHUSPMAdaptiveAlgo(
                ds.defaultSu, DEFAULT_KAPPA, DEFAULT_DELTA0, slRatioFor(ds.name),
                PreHUSPMAdaptiveAlgorithm.PreHUSPMAdaptiveAlgo.AblationMode.FULL,
                ENABLE_PATTERN_EXPORT);
        adp.useAdaptiveRescan = useAdaptiveRescan; // C5 on/off
        adp.useBlockIndex = useBlockIndex;         // C4 on/off
        // C2 off: the pre-large band is fixed at the hard floor LUT_min, as in Pre-HUSPM,
        // instead of following the batch statistics. Still sound: on insertion TSU' >= TSU_ref,
        // so the fixed margin (UUT - LUT_min) * TSU' is never below the safety lower bound.
        adp.useAdaptiveBand = useAdaptiveBand;

        announce(tag, ds.name, opTag + "_INIT", 0);
        ExperimentMetrics m0 = measureAdaptive(adp, tag, ds.name, 0, opTag + "_INIT",
                () -> adp.runInitialMining(dInit, initTotal));
        col.log(m0);
        boolean poisoned = failedRun(m0);

        double rolling = initTotal;
        List<Sequence> dSoFar = new ArrayList<>(dInit);
        for (int b = 0; b < batches.size(); b++) {
            List<Sequence> delta = batches.get(b);
            if (delta.isEmpty()) continue;
            int batchIdx = b + 1;
            double newTotal = rolling + sumUtility(delta);
            if (!poisoned && !memoryGuardOk(tag, ds.name, opTag + "_INC")) poisoned = true;
            if (poisoned) {
                col.log(skippedMetric(tag, ds.name, batchIdx, opTag + "_INC"));
            } else {
                List<Sequence> snap = new ArrayList<>(dSoFar);
                snap.addAll(delta);
                final double nt = newTotal;
                announce(tag, ds.name, opTag + "_INC", batchIdx);
                ExperimentMetrics mb = measureAdaptive(adp, tag, ds.name, batchIdx, opTag + "_INC",
                        () -> adp.runIncrementalMining(delta, snap, nt));
                col.log(mb);
                if (failedRun(mb)) poisoned = true;
            }
            dSoFar.addAll(delta);
            rolling = newTotal;
        }
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
            beginMemArm();
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
            if (failedRun(m0)) { poisoned = true; inc.releaseState(); forceFullGC(); }

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
                    if (failedRun(mb)) { poisoned = true; inc.releaseState(); forceFullGC(); }
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
                beginMemArm();
                final PreHUSPMAlgorithm.PreHUSPMAlgo preh = new PreHUSPMAlgorithm.PreHUSPMAlgo(
                        Su, slRatioFor(dsName), ENABLE_PATTERN_EXPORT, variant);
                boolean poisoned = false;
                announce(label, dsName, tag + "_INIT", 0);
                ExperimentMetrics m0 = measure(label, dsName, 0, tag + "_INIT",
                        () -> preh.runInitialMining(dInit, initTotal),
                        () -> preh.candidateCount, () -> preh.patternCount, null, null);
                attachPreHuspm(m0, preh);
                col.log(m0);
                if (failedRun(m0)) poisoned = true;

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
                        if (failedRun(mb)) poisoned = true;
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
            beginMemArm();
            boolean poisoned = false;
            double rolling = initTotal;
            List<Sequence> dSoFar = new ArrayList<>(dInit);

            PreHUSPMAdaptiveAlgorithm.PreHUSPMAdaptiveAlgo rm0 =
                    new PreHUSPMAdaptiveAlgorithm.PreHUSPMAdaptiveAlgo(
                            Su, DEFAULT_KAPPA, DEFAULT_DELTA0, slRatioFor(dsName),
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
            if (failedRun(m0)) poisoned = true;
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
                                Su, DEFAULT_KAPPA, DEFAULT_DELTA0, slRatioFor(dsName),
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
                if (failedRun(mb)) poisoned = true;
                forceFullGC();
            }
        }
        forceFullGC();

        // -------- Algorithm 3: Adaptive-Full (isolated scope) --------
        if (algoEnabled("Adaptive-Full")) {
            beginMemArm();
            PreHUSPMAdaptiveAlgorithm.PreHUSPMAdaptiveAlgo adp = new PreHUSPMAdaptiveAlgorithm.PreHUSPMAdaptiveAlgo(
                    Su, DEFAULT_KAPPA, DEFAULT_DELTA0, slRatioFor(dsName),
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
            if (failedRun(m0)) poisoned = true;

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
                    if (failedRun(mb)) poisoned = true;
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
        if (m == null) return;
        // Trang thai thich nghi duoc ghi KE CA tren mot luot that bai. Hang that bai la hang
        // dang doc nhat trong ca bang, va day la ba cot noi duoc VI SAO no that bai: do lap
        // cua CSDL, san da bi day len toi dau, va co phai nang may lan moi chiu dung. Truoc
        // day chung bi bo qua cung voi tat ca metric khac, nen mot hang BUDGET hay OOM khong
        // mang theo chan doan nao - dung luc no can nhat.
        m.repetition    = adp.repetition;
        m.slMinEff      = adp.slMinRatioEff;
        m.rescanRetries = adp.rescanRetries;
        // Bo dem CONG thi khong: mot luot vo giua chung de lai so lieu do dang, va mot con
        // so do dang lot vao ho so con te hon la khong co.
        if (m.runtimeMs == -1) return;
        m.exploredNodes = adp.exploredNodes;    // mẫu khai triển thật (sau cận PEU)
        m.prunedIub     = adp.prunedIub;        // C3
        m.rescanCount   = adp.rescanCount;      // C5
        m.blocksRead    = adp.blocksReadCount;  // C4
        m.blocksSkipped = adp.numBlocksSkipped; // C4
        m.sigma2        = adp.sigmaDSquared;    // C2 (phương sai lô - thô, độc lập)
        m.rho           = adp.rhoD;             // C2 (độ lệch - thô, độc lập)
        // iau_max la dai luong QUYET DINH rescan (triggerQuantity > safetyBoundF), nhung
        // cot nay ton tai ma khong ai dien: moi hang tu truoc den nay deu la 0.0, nen quyet
        // dinh rescan khong hau kiem duoc tu ho so. sft_val co ghi, nhung no la ve kia cua
        // phep so sanh chu khong phai dai luong duoc so.
        m.iauMax        = adp.iauMaxAtDecision;   // gia tri TAI diem quyet dinh, truoc resetIau()
        m.iauTightness  = adp.iauTightness;
        m.repetition    = adp.repetition;
        m.slMinEff      = adp.slMinRatioEff;
        m.rescanRetries = adp.rescanRetries;
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
        // The floor this row was measured at, as the proposal's rows already record it.
        // Without it a Pre-HUSPM row cannot say which r_l it belongs to.
        m.slMinEff = (pre.Su > 0) ? pre.Sl / pre.Su : 0;
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

    /** Wrap a scenario call so its outcome feeds the repeat decisions. */
    private static ExperimentMetrics logged(ExperimentMetrics m) {
        noteOutcome(m);
        return m;
    }

    private static ExperimentMetrics runSingle(String algo, String dsName, List<Sequence> db,
                                               double totalUtil, double su, int iter, String tag) {
        if (ADAPTIVE_ONLY && !algo.equals("Adaptive-Full")) return null; // chỉ chạy lại Adaptive
        if (!algoEnabled(algo)) return null;   // -Dalgo.only: mot arm mot JVM (che do live)
        if (skipRepeat(algo, dsName, tag, currentRepeat)) return null;

        endMemArm();   // a static mine is measured against the heap at the start of the call

        // Built before anything is measured, while there is memory to build it. If anything
        // below throws, this row is what gets logged, so a run that started always leaves one.
        //
        // This used to return null, and SC1 and SC3 log only non-null rows. Pre-HUSPM on
        // LEVIATHAN in the memory campaign reached the 120-minute limit; it has no cooperative
        // cancel, so its worker kept running and kept the heap full, measure() returned an
        // overtime row, and the first allocation after it - recording the outcome - threw
        // OutOfMemoryError into this catch. The row vanished and nothing but a line on stderr
        // said so.
        final ExperimentMetrics failure = skippedMetric(algo, dsName, iter, tag);
        final String opOom = tag + "_OOM", opFailed = tag + "_FAILED";
        failure.seed = currentSeed;
        failure.repeat = currentRepeat;
        failure.memMode = MEM_MODE;

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
                    return logged(m);
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
                    return logged(m);
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
                    return logged(m);
                }
                case "IncUSP-Miner+": {
                    IncUSPMinerPlusAlgorithm inc = new IncUSPMinerPlusAlgorithm(su);
                    ExperimentMetrics m = measure(algo, dsName, iter, tag,
                            () -> inc.runInitialMining(db, totalUtil),
                            () -> inc.candidateCount, () -> inc.patternCount, null, null);
                    if (m != null && m.runtimeMs != -1) { m.prunedTsu = inc.prunedTSU; m.exploredNodes = inc.exploredNodes; }
                    return logged(m);
                }
                case "Pre-HUSPM": {
                    PreHUSPMAlgorithm.PreHUSPMAlgo pre = new PreHUSPMAlgorithm.PreHUSPMAlgo(
                            su, slRatioFor(dsName), ENABLE_PATTERN_EXPORT);
                    ExperimentMetrics m = measure(algo, dsName, iter, tag,
                            () -> pre.runInitialMining(db, totalUtil),
                            () -> pre.candidateCount, () -> pre.patternCount, null, null);
                    attachPreHuspm(m, pre);
                    return logged(m);
                }
                case "Adaptive-Full": {
                    PreHUSPMAdaptiveAlgorithm.PreHUSPMAdaptiveAlgo adp = new PreHUSPMAdaptiveAlgorithm.PreHUSPMAdaptiveAlgo(
                            su, DEFAULT_KAPPA, DEFAULT_DELTA0, slRatioFor(dsName),
                            PreHUSPMAdaptiveAlgorithm.PreHUSPMAdaptiveAlgo.AblationMode.FULL,
                            ENABLE_PATTERN_EXPORT);
                    ExperimentMetrics m = measure(algo, dsName, iter, tag,
                            () -> adp.runInitialMining(db, totalUtil),
                            () -> adp.candidateCount, () -> adp.patternCount,
                            () -> adp.lutDynValue, () -> adp.cubValue, () -> adp.cubTriggeredRescan,
                            null, () -> adp.prunedValueBound,
                            () -> adp.prelargeCount, () -> adp.uutValue, () -> adp.sftValue, adp::cancel);
                    attachAdaptive(m, adp);
                    return logged(m);
                }
                default: return null;
            }
        } catch (Throwable t) {
            // No allocation before the row is ready: the heap may be full.
            failure.opType = (t instanceof OutOfMemoryError) ? opOom : opFailed;
            try { System.err.println("[" + algo + "/" + dsName + "/" + tag + "] ERROR after the run began: " + t); }
            catch (Throwable ignored) { }
            try { noteOutcome(failure); } catch (Throwable ignored) { }
            return failure;
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
        // live : ep MOT lan thu gom day du roi doc used-heap NGAY SAU, cach nhau
        //        max(MEM_SAMPLE_MS, 4 x thoi gian lan thu gom truoc).
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
                        long gcStart = System.nanoTime();
                        System.gc();
                        long gcMs = (System.nanoTime() - gcStart) / 1_000_000L;
                        long live = r.totalMemory() - r.freeMemory();
                        lastLive.set(live);
                        gcCount.incrementAndGet();
                        long prevL = peakLive.get();
                        while (live > prevL && !peakLive.compareAndSet(prevL, live)) prevL = peakLive.get();
                        // Dense for a small live set, sparse for a large one. A full collection
                        // costs roughly the size of what is live: milliseconds for the proposal
                        // on SIGN, seconds for IncUSP-Miner+ holding tens of gigabytes. A fixed
                        // short interval would spend a large run almost entirely collecting; a
                        // fixed long one misses every peak of a short run. Waiting four times the
                        // last collection keeps collection under a fifth of the wall time, and
                        // gc_forced on the row records how many samples its figure rests on.
                        long waitMs = Math.max(MEM_SAMPLE_MS, 4 * gcMs);
                        try { Thread.sleep(waitMs); } catch (InterruptedException e) { break; }
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
            long memBase = (memArmBaseline >= 0) ? memArmBaseline : heapBefore;
            m.memLiveMb     = Math.max(0, pl - memBase) / (1024 * 1024);
            m.memRetainedMb = Math.max(0, finalLive - memBase) / (1024 * 1024);
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

    /**
     * D_init for a database: an absolute count from Yan et al. Table 16, not a fraction.
     *
     * Every scenario takes D_init from here, so SC1, SC2, SC5, SC6 and SC7 all cut the
     * database at the same place. A reviewer comparing two of our figures sees one
     * definition; the batching study in SC5 varies only how finely the rest is delivered.
     */
    private static List<Sequence> dInitFor(DatasetConfig ds, List<Sequence> all) {
        if (all.isEmpty()) return Collections.emptyList();
        int n = (ds.dInitCount > 0) ? Math.min(ds.dInitCount, all.size())
                                    : Math.max(1, (int) Math.round(all.size() * D_INIT_RATIO));
        return new ArrayList<>(all.subList(0, n));
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
        // Every batch gets one sequence first, then the rest is shared out by weight, with the
        // units left over by rounding going to the largest fractional parts (ties to the lower
        // index).
        //
        // The previous rule floored every share and handed the remainder out from the front.
        // That treats the two ends of the stream differently: INCREASING has its small batches
        // at the front, where the remainder lands, and DECREASING has them at the back, where
        // it does not. On SIGN at K = 100, DECREASING left ten batches empty and INCREASING
        // none, and the arms skip an empty batch without a word, so "DECREASING, K = 100" was
        // ninety increments against a hundred - a difference in K passed off as a difference
        // in strategy, in the one scenario built to compare strategies.
        //
        // EVEN splits come out exactly as they did before, batch for batch, on every database
        // the campaign measures, so SC2 and SC7 are unaffected.
        int[] sizes = new int[k];
        if (n >= k) {
            int m = n - k;
            double[] frac = new double[k];
            int allocated = 0;
            for (int i = 0; i < k; i++) {
                double raw = weights[i] * m;
                int f = (int) Math.floor(raw);
                sizes[i] = f; frac[i] = raw - f; allocated += f;
            }
            Integer[] order = new Integer[k];
            for (int i = 0; i < k; i++) order[i] = i;
            Arrays.sort(order, (a, b) -> frac[a] != frac[b] ? Double.compare(frac[b], frac[a])
                                                            : Integer.compare(a, b));
            for (int r = 0; r < m - allocated; r++) sizes[order[r]]++;
            for (int i = 0; i < k; i++) sizes[i]++;
        } else {
            // Fewer sequences than batches: some batch must be empty whatever the rule.
            System.out.println("   ! " + k + " batches over " + n + " sequences: "
                    + (k - n) + " will be empty and skipped");
            int allocated = 0;
            for (int i = 0; i < k; i++) { sizes[i] = (int) Math.floor(weights[i] * n); allocated += sizes[i]; }
            for (int i = 0, rem = n - allocated; rem > 0 && i < k; i++, rem--) sizes[i]++;
        }
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
