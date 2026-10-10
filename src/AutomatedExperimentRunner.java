import algorithms.*;
import common.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Automated experiment runner for the Pre-HUSPM-Joint framework (scenarios SC1-SC7).
 *
 * Run from the repository root, with the shared databases in datasets/ (see DATASET_DIR).
 * Dense databases (SIGN, LEVIATHAN, ...) need -Xmx8g or more.
 *
 * Program arguments select scenarios:
 *   ALL                  - run all seven scenarios (default if no arguments).
 *   SC1 SC3              - run SC1 and SC3.
 *   SC1,SC2,SC7          - comma syntax is equivalent.
 *   TEST                 - use the four-sequence worked example in examples/ (smoke test, < 1 s).
 *   TEST SC1             - smoke test on SC1 only.
 *   STATUS               - print each database's thresholds and calibration state, then exit.
 *
 * Without TEST, every database listed in common.Datasets is used, in cost order.
 *
 * SC1 - Static mining on D_init (Yan et al. Table 16 OD.Num), sweeping Su.
 * SC2 - Incremental: D_init per Yan et al. Table 16, then BATCH_COUNT even batches.
 * SC3 - Su sensitivity on the database subset SUBSET_3.
 * SC4 - Pre-large floor sensitivity (Sl/Su) for Pre-HUSPM and Adaptive.
 * SC5 - Batch strategy grid (K from common.Datasets.BATCH_COUNT_SWEEP x {EVEN, INC, DEC, BURST}).
 * SC6 - Mixed insert/delete/modify stream (Adaptive vs Remine-from-scratch).
 * SC7 - Ablation: proposed_full, proposed_no_IUB, proposed_no_IPS, the incremental block-index and adaptive-rescan
 *       ablations, and a Pre-HUSPM reference.
 *
 * Each scenario writes one CSV file in RESULTS_DIR in the ExperimentMetrics format.
 * Before each mining call the runner prints ">> RUNNING algorithm/dataset/op#iter" with the
 * current heap state. An error in a single mining call is caught and logged, and the run
 * continues; the row is written with runtime_ms = -1 (or -2 on timeout).
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
     * A small worked example of four short sequences, used by TEST mode as a smoke
     * test. It belongs to this study rather than to the shared collection,
     * so it stays in the repository.
     */
    public static final String EXAMPLE_DIR = "examples/";
    public static final String RESULTS_DIR = System.getProperty("results.dir", "results/");
    public static final boolean ENABLE_PATTERN_EXPORT = false; // disable for bulk runs
    /** Run only the Adaptive arm and skip the baselines; rows are appended to the existing
     *  CSV files. Enable with -Dadaptive.only=true. */
    public static final boolean ADAPTIVE_ONLY = Boolean.getBoolean("adaptive.only");
    public static final int REPETITIONS = 1;
    public static final long[] SEEDS = {42L, 1337L, 2024L, 31415L, 271828L};

    // Default algorithm parameters
    // Pre-HUSPM (Sl/Su) and Adaptive (Sl_min/Su) must use the same Sl ratio; both read one
    // constant so the comparison cannot drift.
    public static final double DEFAULT_SL_MIN_RATIO = common.Datasets.DEFAULT_SL_MIN_RATIO;
    /** Alias so Pre-HUSPM uses the same Sl ratio as Adaptive. Do not change it on its own. */
    public static final double DEFAULT_PRE_HUSPM_SL_RATIO = DEFAULT_SL_MIN_RATIO;
    /** Adaptation coefficient kappa. Override with -Dkappa=... */
    public static final double DEFAULT_KAPPA  = Double.parseDouble(System.getProperty("kappa", "0.5"));
    public static final double DEFAULT_DELTA0 = Double.parseDouble(System.getProperty("delta0", "1e-4"));
    /** Block size of the block index. Override with -Dblock.size=... */
    public static final int DEFAULT_BLOCK_SIZE = Integer.getInteger("block.size", 100);
    /** Repeats per configuration, for measurement noise (JIT/GC). Override with -Drepeats=... */
    public static final int REPEATS = Integer.getInteger("repeats", 3);
    /** Number of seeds used (at most SEEDS.length). Override with -Dseeds=5 */
    public static final int SEED_COUNT = Math.min(
            Integer.getInteger("seeds", 1), 5);
    /**
     * One replicate per JVM. campaign.ps1 measures every repeat and every seed of a
     * replicated scenario in a JVM of its own, as it measures every arm, so that no
     * replicate runs on a JIT warmed by the one before it - a repeat run in the same JVM is
     * faster for that reason alone, and the spread it gives measures warm-up, not noise.
     *   -Drepeat.index=n  writes n into the repeat column (the run itself is unchanged)
     *   -Dseed.index=k    runs with SEEDS[k] alone, where a scenario draws from a seed
     */
    public static final int REPEAT_INDEX = Integer.getInteger("repeat.index", 0);
    public static final int SEED_INDEX = Integer.getInteger("seed.index", 0);

    // === MEMORY MEASUREMENT MODE ===
    //
    // "used" (default): peak used heap sampled every 50 ms, including uncollected garbage.
    //   It depends on GC scheduling, on -Xmx, and on which algorithm ran earlier in the
    //   same JVM.
    //
    // "live": forces a full collection at sampling points and reads used heap right after,
    //   which gives the live set. Forced GC invalidates timing, so runtime_ms is written
    //   as -3 in this mode.
    //
    // Override: -Dmem.mode=live -Dmem.interval.ms=<ms>
    public static final String MEM_MODE = System.getProperty("mem.mode", "used")
            .trim().toLowerCase(java.util.Locale.ROOT);
    public static final boolean MEM_LIVE = "live".equals(MEM_MODE);
    /**
     * Shortest wait between two forced collections in live mode. The actual wait is the larger
     * of this and four times the last collection's duration - see the sampler in measure().
     *
     * Keep it short. A run shorter than the interval is sampled only at its start, before it
     * has allocated anything, and after it ends, when its working set is already garbage, so
     * its reported peak would be only what it retains. A slower algorithm is sampled
     * throughout and credited with its true peak, so a long interval biases the comparison
     * toward the faster algorithm.
     */
    public static final long MEM_SAMPLE_MS = Long.getLong("mem.interval.ms", 20L);

    /**
     * Run only ONE algorithm (arm) in this process. Live memory mode requires one JVM per
     * arm, because otherwise the heap has already been grown by the previous arm.
     * Override: -Dalgo.only=USpan. Empty = run all.
     */
    public static final String ALGO_ONLY = System.getProperty("algo.only", "").trim();

    /** Rescan trigger rule in use, recorded in the CSV and on the Platform line. */
    public static final String ADAPT_TRIGGER = System.getProperty("adapt.trigger", "joint");

    /** Run only ONE dataset. Override: -Ddataset.only=SIGN. Empty = all. */
    public static final String DATASET_ONLY = System.getProperty("dataset.only", "").trim();

    public static boolean datasetEnabled(String ds) {
        return DATASET_ONLY.isEmpty() || DATASET_ONLY.equalsIgnoreCase(ds);
    }

    /** True if this algorithm may run in the current process. */
    public static boolean algoEnabled(String algo) {
        return ALGO_ONLY.isEmpty() || ALGO_ONLY.equalsIgnoreCase(algo);
    }

    // === CONTEXT OF ONE MEASUREMENT ===
    //
    // Every row records the seed and repeat index that produced it, so rows can be
    // averaged and traced back to their configuration.
    //
    // Two different sources of variation; do not pool them:
    //   seed   changes the CONFIGURATION: which batch holds which sequence, which
    //          operation happens when. It matters only where a scenario is random (see
    //          the note in each scenario).
    //   repeat runs the SAME configuration again to measure JIT and GC noise.
    // The standard deviation of runtime comes from repeats; seeds check that a conclusion
    // does not depend on one particular batch split.
    private static long currentSeed = SEEDS[0];
    private static int currentRepeat = 0;

    private static void setRunContext(long seed, int repeat) {
        currentSeed = seed;
        currentRepeat = repeat + REPEAT_INDEX;
    }

    /** The seeds used in this run. */
    private static long[] activeSeeds() {
        if (SEED_INDEX > 0) {
            if (SEED_INDEX >= SEEDS.length) {
                throw new IllegalArgumentException("seed.index must be below " + SEEDS.length
                        + ", got " + SEED_INDEX);
            }
            return new long[]{SEEDS[SEED_INDEX]};
        }
        long[] out = new long[SEED_COUNT];
        System.arraycopy(SEEDS, 0, out, 0, SEED_COUNT);
        return out;
    }
    /**
     * Fallback D_init fraction for a database without a count in common.Datasets.dInitCount
     * (which carries Yan et al. Table 16's OD.Num per database).
     */
    public static final double D_INIT_RATIO = 0.5;

    /** Increments per incremental scenario; -Dbatch.count overrides for cost probes. */
    public static final int BATCH_COUNT = Integer.getInteger("batch.count", common.Datasets.BATCH_COUNT);

    // === TIMEOUT PER ALGORITHM RUN ===
    // Every measure() call runs under an ExecutorService timeout. On timeout: the cancel
    // hook is signalled, future.cancel(true) is called, opType gets "_OT" and
    // runtime_ms = -2 (distinct from -1 = FAILED).
    //
    // Default 120 minutes; override with -Dtimeout.min. The value in effect is printed on
    // the Platform line of every log and must be reported with the results. A shorter limit
    // on a slower machine tightens the budget and marks more baselines as overtime, which
    // favours the proposed method.
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
    // Every database is measured in every scenario it can run in. The grid is datasets x
    // scenarios x algorithms x thresholds x seeds x repeats, so the question is where to
    // stop paying without losing anything.
    //
    // Three places, each cutting a different kind of waste.
    //
    //   1. A configuration that failed is not measured again. Overtime and out-of-memory
    //      are properties of the configuration, not samples of a distribution; repeating
    //      one buys nothing and costs the full timeout every time. Failures cluster on the
    //      expensive databases, so this saves hours.
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
     * Do not test runtimeMs < 0: in live mode that would count every initial mine as failed and
     * skip all increments after it as poisoned.
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
     * before it, so a per-call baseline would record only what each increment adds and miss
     * the state an incremental method keeps. With the baseline fixed once per arm, before the
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
     * Databases ordered cheapest first, by measured cost per call. An interrupted campaign
     * then leaves whole databases complete. FIFA is last on purpose: at about 450 s per call
     * it costs more than the other five together.
     */
    private static final List<String> COST_ORDER = List.of(
            "LEVIATHAN", "SIGN", "BMS1", "KOSARAK", "BIBLE",
            // TAFENG's place comes from its calibration probes, which finish in under a
            // second at every threshold of its sweep. Move it once a run has measured it.
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
     * Every database of the pinned release, with the thresholds each one is measured at.
     *   - SIGN, LEVIATHAN, FIFA: dense, long sequences.
     *   - BMS1, KOSARAK, TAFENG: sparse or short sequences.
     *   - C8T1S5I8N5K: the only one with more than one item per event.
     * The "paper" example (4 sequences) is for the TEST-mode smoke test only.
     * Heap: LEVIATHAN and SIGN need -Xmx8g or more.
     *
     * The list must name every database that will be run, not just the ones already set
     * up. A table that quietly lags behind the data makes "all databases were calibrated"
     * false while looking complete, so entries that are not ready are present and marked
     * rather than absent.
     *
     * Thresholds are ratios of total utility. They stay well defined across releases but
     * not comparable: the shape of the utility distribution differs between releases, so the
     * same ratio selects a different number of patterns. Each sweep is chosen with
     * experiments.ThresholdScan to hit target pattern counts, so the runs pose comparable
     * work; the raw search output is in results/thresholds/thresholds.csv.
     */
    private static final List<DatasetConfig> DATASETS_PRODUCTION = buildProduction();

    /**
     * The table itself lives in common.Datasets so that verify.CorrectnessVerifier reads the
     * same thresholds this runner measures at. Do not keep a second copy here.
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

    /** Smoke-test dataset (paper = 4 sequences, < 1 s). */
    private static final List<DatasetConfig> DATASETS_TEST = List.of(
            new DatasetConfig("paper", 0.001, new double[]{0.0005, 0.001, 0.002, 0.005})
    );

    /** Datasets in use: the TEST set or the production set. */
    private static List<DatasetConfig> DATASETS = DATASETS_PRODUCTION;
    /** Resolved at start-up: examples/ in TEST mode, datasets/ otherwise. */
    private static String datasetDir = DATASET_DIR;

    /**
     * The three databases SC3, SC4, SC5 and SC6 are measured on.
     *
     * KOSARAK is not in this set because of SC5, which measures a grid of four increment
     * counts by four arrival strategies. Measured on SIGN, that grid costs 10.5 times what SC2
     * costs for the same arm: halving K saves only 24% rather than 50%, because each batch
     * carries a fixed cost and the rescans do not thin out in proportion. For KOSARAK that is
     * about fifty hours for its four working arms, and over eighty for Pre-HUSPM-TSU alone.
     *
     * SIGN, LEVIATHAN and C8T1S5I8N5K cover small and dense, medium, and large, and
     * C8T1S5I8N5K is the only database here that reaches within-event extension (7.97 items
     * per event; the other seven hold exactly one).
     *
     * KOSARAK runs in SC1, SC2 and SC7.
     */
    private static final Set<String> SUBSET_3 = new HashSet<>(Arrays.asList(
            "SIGN", "LEVIATHAN", "C8T1S5I8N5K"));

    /**
     * Databases that also get the incremental ablations of SC7 (adaptive rescan and block
     * index).
     *
     * The static SC7 arms are one full mine each and cost seconds. proposed_C5_off is not: it
     * rescans on EVERY batch by design, so at BATCH_COUNT=100 it mines from scratch a hundred
     * times. Its cost, a hundred full mines, decides who can be in this set.
     *
     * KOSARAK is excluded: a full-scope mine takes 438 s, so proposed_C5_off alone would take
     * about twelve hours. The set matches SUBSET_3 apart from BMS1.
     *
     * BMS1 is listed although SC7 does not reach it: scopeOk filters it out because SC7 mines
     * the whole database and BMS1 cannot be mined there. If that changes, the ablation covers
     * it again. In practice this set delivers SIGN and C8T1S5I8N5K.
     */
    private static final Set<String> C5_ABLATION_SET = new HashSet<>(Arrays.asList(
            "BMS1", "SIGN", "C8T1S5I8N5K"));

    // ==================================================================
    // ENTRY POINT
    // ==================================================================
    public static void main(String[] args) throws IOException {
        // === MEMORY MODE PREFLIGHT ===
        // Live and used results measure DIFFERENT quantities but differ in only one column,
        // so they are easy to mix up. Live mode must therefore write to its own directory.
        if (MEM_LIVE) {
            String rd = RESULTS_DIR.replace('\\', '/').toLowerCase(java.util.Locale.ROOT);
            if (!rd.contains("mem")) {
                System.err.println("STOP: -Dmem.mode=live must write to a directory with 'mem' "
                        + "in its name, e.g. -Dresults.dir=results/run-xxx/mem/. Current directory: "
                        + RESULTS_DIR);
                System.exit(2);
            }
            if (ALGO_ONLY.isEmpty()) {
                System.err.println("STOP: live mode requires ONE JVM PER ARM "
                        + "(-Dalgo.only=<name>). With several arms in one JVM the heap has "
                        + "already been grown by the previous arm, which is what live mode "
                        + "exists to avoid. Use run.ps1 -MemMode live to loop over the arms.");
                System.exit(2);
            }
            System.out.println("### LIVE HEAP: forced collection, interval >= " + MEM_SAMPLE_MS
                    + " ms | arm = " + ALGO_ONLY);
            System.out.println("### runtime_ms is written as -3 (perturbed by forced GC); the "
                    + "wall time is in wall_ms_perturbed. NOT a performance figure.");
        }

        new File(RESULTS_DIR).mkdirs();

        // TEST (or SMOKE) in the arguments selects the smoke-test dataset (paper, 4 sequences,
        // < 1 s). "TEST" or "TEST SC1" for a smoke test; ALL/SC* (default) use production.
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
        // The platform each arm ran on, recorded in the arm's own log.
        System.out.println("Platform: os=" + System.getProperty("os.name") + " "
                + System.getProperty("os.version") + " (" + System.getProperty("os.arch") + ")"
                + " | java=" + System.getProperty("java.runtime.version")
                + " " + System.getProperty("java.vm.name") + " (" + System.getProperty("java.vendor") + ")"
                + " | cpus=" + Runtime.getRuntime().availableProcessors()
                + " | adapt.trigger=" + ADAPT_TRIGGER
                + " | adapt.iip=" + PreHUSPMAdaptiveAlgorithm.PreHUSPMAdaptiveAlgo.configuredIipMode().name().toLowerCase(Locale.ROOT)
                + " | block.size=" + DEFAULT_BLOCK_SIZE
                + " | timeout.min=" + TIMEOUT_MINUTES
                + " | repeats=" + REPEATS + " repeat.index=" + REPEAT_INDEX
                + " | seed.index=" + SEED_INDEX);
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
                // KB1..KB7 are accepted as aliases for SC1..SC7
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
    // SC1 - STATIC MINING ON D_INIT
    // ==================================================================
    private static void runSC1_StaticBaseline() throws IOException {
        System.out.println("\n--- [SC1] Static baseline on D_init ---");
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
                // SC1 has no randomness: no batch split, no generated operations. A seed
                // changes nothing, so only the same configuration is repeated (JIT/GC noise).
                for (double su : ds.suSweep) {
                    for (int r = 0; r < REPEATS; r++) {
                    setRunContext(SEEDS[0], r);
                    for (String algo : algos) {
                        ExperimentMetrics m = runSingle(algo, ds.name, dInit, totalUtil, su, 0, "SC1_su=" + su);
                        if (m != null) col.log(m);
                        // GC between algorithms: the heap held by the previous algorithm
                        // (its instance went out of scope in runSingle) must be reclaimed
                        // before the next one allocates, including after an OOM.
                        forceFullGC();
                    }
                    }
                }
            }
        }
    }

    // ==================================================================
    // SC2 - INCREMENTAL SEQUENCE: D_init, then BATCH_COUNT EVEN batches
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

                // SC2 splits EVENLY and IN FILE ORDER, so the split is fully deterministic:
                // every seed would give the same split, and running several seeds would only
                // repeat one configuration. Real variation needs a permuted incremental
                // stream, which is a different scenario.
                //
                // So: with one seed the stream stays in file order; with two or more seeds
                // the incremental stream is shuffled by seed. Report which was used.
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
                // As in SC1: no randomness, only repeats for measurement noise.
                for (double su : ds.suSweep) {
                    for (int r = 0; r < REPEATS; r++) {
                        setRunContext(SEEDS[0], r);
                        for (String algo : algos) {
                            ExperimentMetrics m = runSingle(algo, ds.name, full, totalUtil, su, 0, "SC3_su=" + su);
                            if (m != null) col.log(m);
                            forceFullGC(); // clean up the heap between algorithms
                        }
                    }
                }
            }
        }
    }

    // ==================================================================
    // SC4 - PRE-LARGE FLOOR SENSITIVITY (Sl/Su): Pre-HUSPM vs Adaptive
    //        (shows the pre-large explosion at low floors and the pruning advantage)
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

                // Sl/Su sensitivity: Pre-HUSPM and Adaptive run at the SAME slRatio in each
                // iteration, so the comparison is like for like.
                //
                // Each instance is scoped to its block, so it is unreachable after the block
                // and forceFullGC() reclaims its heap.
                // No randomness; only repeats for JIT/GC noise.
                for (double slRatio : slRatios) {
                  for (int r = 0; r < REPEATS; r++) {
                    setRunContext(SEEDS[0], r);
                    // Each block answers to -Dalgo.only. Without the gates every arm would run both
                    // algorithms at all four levels, and campaign.ps1, which starts one JVM per arm
                    // name, would measure the whole of SC4 once per arm.
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
                    // --- Adaptive @ SAME slRatio (default kappa and delta0) ---
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

                // No (delta0, kappa) sweep: SC4 runs runInitialMining, where these two
                // parameters have no effect (the initial mine sets lutDynValue = TSU * Sl_min
                // directly, without delta_D).
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

                // Of the four strategies only BURST uses the seed; EVEN, INCREASING and
                // DECREASING are deterministic. The seed loop therefore runs for BURST (and
                // RANDOM) only; for the others it would only repeat the same configuration.
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
    /** One operation in SC6's mixed stream. */
    private enum OpKind { INS, DEL, MOD }

    private static final class Op {
        final OpKind kind;
        final int iter;
        final List<Sequence> a;   // INS: inserted batch | DEL: deleted sequences | MOD: old sequences
        final List<Sequence> b;   // MOD: new sequences
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
     * SC6's schedule: 70:15:15 as exact counts, not as probabilities.
     *
     * The plan is built ONCE per database and seed, and every arm replays that same plan, so
     * the arms are comparable.
     *
     * Exact counts rather than independent draws: with independent draws the mix of
     * operations depends on the seed and the database, and since a deletion costs a few
     * milliseconds where an insertion costs a remine, each database's total would follow its
     * draw. Every database gets the same operations; only their order depends on the seed.
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
     * Mixed insert / delete / modify stream at 70:15:15.
     *
     * Two arms run on the SAME operation plan:
     *
     *   Adaptive-Full        incremental maintenance
     *   Remine-from-scratch  mines from scratch after EVERY operation
     *
     * The reference uses the proposed framework's own static miner, so any measured gap
     * comes from not having to remine, not from one miner being faster than another.
     *
     * Cumulative runtime is not stored as a column: it is the sum of runtime_ms over the
     * rows of one arm and dataset, computable from the raw data.
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

                // One hundredth of the stream, the size of an SC2 increment. Much larger operations
                // exceed the insertion budget on every insertion and rescan, which measures the
                // remine regime rather than the one SC2 runs in.
                int opSize = Math.max(1, rest.size() / common.Datasets.BATCH_COUNT);

                // Here the seed matters: it decides which operation happens when and on which
                // sequences. SC6's conclusions must not depend on one lucky plan.
                for (long seed : activeSeeds()) {
                    // A per-dataset seed, so this dataset's plan does not depend on which
                    // datasets ran before it.
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
        return plan.size() + " operations (" + i + " insert, " + d + " delete, " + m + " modify)";
    }

    /** Replay the operation plan for one arm. remine=true mines from scratch after every operation. */
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
            // The database evolves identically in both arms, because the plan is fixed.
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
                // Remine from scratch: a NEW instance that keeps no state.
                final List<Sequence> snapshot = new ArrayList<>(after);
                PreHUSPMAdaptiveAlgorithm.PreHUSPMAdaptiveAlgo rm = newAdaptive(ds.name, ds.defaultSu);
                announce(label, ds.name, op.tag(), op.iter);
                m = measureAdaptive(rm, label, ds.name, op.iter, op.tag(),
                        () -> rm.runInitialMining(snapshot, newTotal));
                m.rescanCount = 0;   // no incremental rescan in this reference
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
     * 0.8 on all eight databases (common.Datasets.Entry.slMinRatio). Both the proposal and the Pre-HUSPM baseline
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

    /** Call measure() with all of Adaptive's suppliers, so callers do not repeat 11 arguments. */
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
    // SC7 - ABLATION: IUB pruning on a static mine; block index and adaptive rescan on the
    // incremental stream, where they act. The PEU bound before materialising is always on.
    // ==================================================================
    private static void runSC7_Ablation() throws IOException {
        System.out.println("\n--- [SC7] Ablation: IUB pruning / block index / adaptive rescan ---");
        try (ResultCollector col = new ResultCollector(RESULTS_DIR + "SC7_ablation.csv")) {
            for (DatasetConfig ds : DATASETS) {
                if (!datasetEnabled(ds.name)) continue;
                if (!calibrationOk(ds)) continue;
                if (!scopeOk(ds, "SC7")) continue;
                List<Sequence> full = DatasetLoader.loadDataset(datasetDir + ds.name + "_seq.txt",
                                                                datasetDir + ds.name + "_eui.txt");
                if (full.isEmpty()) continue;
                double totalUtil = sumUtility(full);

                // The ablation has no randomness; only repeats for JIT/GC noise.
                for (int r = 0; r < REPEATS; r++) {
                setRunContext(SEEDS[0], r);

                // --- Static ablation (one mine of the whole database): the IUB bound acts inside
                //     the miner, so a single mine is where it shows. The PEU bound is always on. ---
                logAblation(col, ds, full, totalUtil, "proposed_full", true, true, true);     // full proposal
                forceFullGC();
                logAblation(col, ds, full, totalUtil, "proposed_no_IUB", true, false, true);  // IUB pruning off
                forceFullGC();
                logAblation(col, ds, full, totalUtil, "proposed_no_IPS", true, true, false);  // item removal (IPS) off
                forceFullGC();

                // --- Incremental ablations, on the representative set: adaptive rescan and
                //     the block index act only while the database grows.
                //     proposed_C5_on is the full proposal on that stream and the reference
                //     for all three. ---
                if (C5_ABLATION_SET.contains(ds.name)) {
                    logIncrementalAblation(col, ds, full, "proposed_C5_on",  "SC7_C5", true,  true,  true);
                    forceFullGC();
                    logIncrementalAblation(col, ds, full, "proposed_C5_off", "SC7_C5", false, true,  true);  // adaptive rescan off
                    forceFullGC();
                    logIncrementalAblation(col, ds, full, "proposed_no_block_index", "SC7_C4", true, false, true); // block index off
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
                }   // end of repeats loop

                forceFullGC();
            }
        }
    }

    private static void logAblation(ResultCollector col, DatasetConfig ds, List<Sequence> full,
                                    double totalUtil, String tag, boolean useBlockIndex,
                                    boolean useIub, boolean useIps) {
        endMemArm();   // static: one mine, measured against the heap at the start of the call
        // SC7's arms carry their own names (proposed_full, proposed_no_IUB, ...) and answer
        // to -Dalgo.only. campaign.ps1 gives each arm its own JVM; without this gate every
        // JVM would repeat every ablation.
        if (!algoEnabled(tag)) return;
        PreHUSPMAdaptiveAlgorithm.PreHUSPMAdaptiveAlgo adp = new PreHUSPMAdaptiveAlgorithm.PreHUSPMAdaptiveAlgo(
                ds.defaultSu, DEFAULT_KAPPA, DEFAULT_DELTA0, slRatioFor(ds.name),
                PreHUSPMAdaptiveAlgorithm.PreHUSPMAdaptiveAlgo.AblationMode.FULL,
                ENABLE_PATTERN_EXPORT);
        adp.useBlockIndex = useBlockIndex; // block index
        adp.useIub = useIub;            // IUB pruning
        if (!useIps) adp.iipMode = PreHUSPMAdaptiveAlgorithm.PreHUSPMAdaptiveAlgo.IipMode.OFF;  // item removal
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
     * Adaptive rescan and the block index act only while the database grows. The block index
     * in particular is read only on an increment that does not rescan -
     * evaluatePatternUtilityWithIndex has a single caller - so a static mine with the index
     * off does exactly the work of one with it on. Per-increment rows let its effect be read
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
        adp.useAdaptiveRescan = useAdaptiveRescan; // adaptive rescan on/off
        adp.useBlockIndex = useBlockIndex;         // block index on/off
        // Adaptive band off: the pre-large band is fixed at the hard floor LUT_min, as in Pre-HUSPM,
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
    // OOM ISOLATION: each algorithm runs in its own block, which ends, drops its
    // references and calls forceFullGC() before the next block starts, so one
    // algorithm's OOM cannot starve the next one's heap.
    //
    // The `poisoned` flag: once an operation fails (OOM/exception), the remaining
    // operations of THAT algorithm are skipped; its state is broken and retrying
    // only costs time and more OOMs.
    // ==================================================================
    private static void runIncrementalScenario(String dsName, double Su, List<Sequence> dInit,
                                               List<List<Sequence>> batches, ResultCollector col,
                                               String tag) {
        final double initTotal = sumUtility(dInit);

        // -------- Arm: IncUSP-Miner+ (isolated scope) --------
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
            // On OOM/poison, release the CP-tree (several GB) at once so the next main-thread
            // allocations (skipped rows, snapshots, the scenario) do not OOM and end the run.
            if (failedRun(m0)) { poisoned = true; inc.releaseState(); forceFullGC(); }

            double rolling = initTotal;
            List<Sequence> dSoFar = new ArrayList<>(dInit);
            for (int b = 0; b < batches.size(); b++) {
                List<Sequence> delta = batches.get(b);
                if (delta.isEmpty()) continue;
                double newTotal = rolling + sumUtility(delta);
                int batchIdx = b + 1;
                // Memory guard before the batch: if memory is short, poison and skip.
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

        // -------- Arm: Pre-HUSPM, BOTH VARIANTS (each in its own scope) --------
        // (-Dalgo.only is applied in the inner loop because the two variants are separate arms)
        //
        // Yan et al. define two rescan tests and compare both; both run here, under the names
        // of their paper:
        //   Pre-HUSPM-SWUmax = test of Theorem 2 (largest sequence-weighted utility of an item)
        //   Pre-HUSPM-TSU    = test of Theorem 1 (total utility of the inserted sequences)
        // Static mining is the same for both, so the static scenarios run one arm, "Pre-HUSPM".
        if (!ADAPTIVE_ONLY) {
            PreHUSPMAlgorithm.PreHUSPMAlgo.Variant[] variants = {
                    PreHUSPMAlgorithm.PreHUSPMAlgo.Variant.SWUMAX,
                    PreHUSPMAlgorithm.PreHUSPMAlgo.Variant.TSU,
            };
            for (PreHUSPMAlgorithm.PreHUSPMAlgo.Variant variant : variants) {
                final String label = (variant == PreHUSPMAlgorithm.PreHUSPMAlgo.Variant.TSU)
                        ? "Pre-HUSPM-TSU" : "Pre-HUSPM-SWUmax";
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
                    // Memory guard before the batch.
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

        // -------- Reference: Remine-from-scratch (isolated scope) --------
        //
        // Measures how much cheaper incremental maintenance is than simply mining from
        // scratch after every batch.
        //
        // It uses the proposed framework's OWN static miner, not USpan or HUSP-ULL. This
        // makes Remine the STRONGEST possible opponent for the incremental claim: every
        // measured gap comes from NOT having to remine, not from one miner being faster
        // than another. A slower static baseline as reference would inflate the benefit.
        //
        // Cumulative runtime is not stored as a column: it is the sum of runtime_ms over
        // the rows of one run, computable from the raw data, following ExperimentMetrics'
        // rule of not storing derived quantities.
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
                // A new instance per batch: mining from scratch keeps NO state from the
                // previous batch.
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
                // Mining from scratch has no incremental rescan; write 0 rather than 1 so
                // rescan_count is not misread as "this reference rescans every batch".
                mb.rescanCount = 0;
                col.log(mb);
                if (failedRun(mb)) poisoned = true;
                forceFullGC();
            }
        }
        forceFullGC();

        // -------- Arm: Adaptive-Full (isolated scope) --------
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
                // Memory guard before the batch.
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
     * Copies ALL Adaptive-specific metrics in one place, so a new call site cannot miss one:
     * trigger rule and adaptation state always; the work counters (prunedIub, rescanCount,
     * blocksRead/blocksSkipped, ...) only when the run did not fail (runtimeMs != -1).
     * Does nothing if m is null.
     */
    private static void attachAdaptive(ExperimentMetrics m, PreHUSPMAdaptiveAlgorithm.PreHUSPMAdaptiveAlgo adp) {
        if (m == null) return;
        // The adaptation state is written EVEN on a failed run. These three columns say WHY
        // it failed: the database's repetition, how far the floor was raised, and how many
        // raises it took. A BUDGET or OOM row needs that diagnosis most.
        // The rule the instance actually ran with, read from the instance rather than from a
        // second default here that could drift from the algorithm's own. Under JOINT kappa and
        // delta0 have no effect, so they are written as NaN rather than as a value that looks
        // like a setting.
        m.triggerRule   = adp.triggerRule.name().toLowerCase(java.util.Locale.ROOT);
        if (adp.triggerRule == PreHUSPMAdaptiveAlgorithm.PreHUSPMAdaptiveAlgo.TriggerRule.JOINT) {
            m.kappa = Double.NaN; m.delta0 = Double.NaN;
        }
        m.repetition    = adp.repetition;
        m.slMinEff      = adp.slMinRatioEff;
        m.rescanRetries = adp.rescanRetries;
        // The work counters are not: a run that broke off leaves partial counts, and a
        // partial count in the record is worse than none.
        if (m.runtimeMs == -1) return;
        m.exploredNodes = adp.exploredNodes;    // patterns materialised (after the PEU bound)
        m.prunedIub     = adp.prunedIub;        // IUB pruning
        m.rescanCount   = adp.rescanCount;      // adaptive rescan
        m.blocksRead    = adp.blocksReadCount;  // block index
        m.blocksSkipped = adp.numBlocksSkipped; // block index
        m.sigma2        = adp.sigmaDSquared;    // adaptive band: batch variance, raw
        m.rho           = adp.rhoD;             // adaptive band: skew, raw
        // iau_max is the quantity that DECIDES a rescan (triggerQuantity > safetyBoundF), so
        // it is recorded to let each rescan decision be checked from the results. sft_val is
        // the other side of that comparison, not the compared quantity.
        m.iauMax        = adp.iauMaxAtDecision;   // value AT the decision, before resetIau()
        m.iauTightness  = adp.iauTightness;
        m.repetition    = adp.repetition;
        m.slMinEff      = adp.slMinRatioEff;
        m.rescanRetries = adp.rescanRetries;
        // delta_D is not stored (derived: uut_val - lut_dyn)
    }

    /**
     * Copies Pre-HUSPM's metrics: prunedPeu, pre-large count, rescanCount, and safetyBoundF into
     * sft_val (so Pre-HUSPM's safety bound compares DIRECTLY with Adaptive's bound F).
     */
    private static void attachPreHuspm(ExperimentMetrics m, PreHUSPMAlgorithm.PreHUSPMAlgo pre) {
        if (m == null || m.runtimeMs == -1) return;
        m.exploredNodes        = pre.exploredNodes; // patterns that pass the bound (buffer size after a mine)
        m.prunedPeu            = pre.prunedPEU; // candidates or buffered patterns discarded by the bound
        m.prelargePatternCount = pre.prelargeCount;
        m.rescanCount          = pre.rescanCount;
        m.sftValue             = pre.safetyBoundF; // pre-large safety bound (compare with Adaptive)
        m.cubValue             = pre.bufValue;     // Pre-HUSPM's accumulated buffer
        // Record which variant produced this row.
        m.triggerRule          = (pre.variant == PreHUSPMAlgorithm.PreHUSPMAlgo.Variant.TSU)
                                 ? "tsud" : "swumax";
        m.kappa = 0; m.delta0 = 0; m.blockSize = 0;
        // The floor this row was measured at, as the proposal's rows already record it.
        // Without it a Pre-HUSPM row cannot say which r_l it belongs to.
        m.slMinEff = (pre.Su > 0) ? pre.Sl / pre.Su : 0;
    }

    /**
     * Total bytes allocated by a thread. Deterministic (independent of GC scheduling), so it
     * reports allocation pressure separately from the live set. Returns -1 if the JVM does
     * not support it.
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
        if (ADAPTIVE_ONLY && !algo.equals("Adaptive-Full")) return null; // rerun Adaptive only
        if (!algoEnabled(algo)) return null;   // -Dalgo.only: one arm per JVM
        if (skipRepeat(algo, dsName, tag, currentRepeat)) return null;

        endMemArm();   // a static mine is measured against the heap at the start of the call

        // Built before anything is measured, while there is memory to build it. If anything
        // below throws, this row is what gets logged, so a run that started always leaves one.
        //
        // Do not return null on failure: SC1 and SC3 log only non-null rows. A baseline without
        // cooperative cancel keeps running after a timeout and keeps the heap full, so the
        // first allocation after measure() returns can throw OutOfMemoryError into this catch.
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
                    // A STATIC algorithm without incremental updates, so it appears only in
                    // SC1 and SC3. Counted like the other baselines: candidate_count before
                    // any pruning, explored_nodes at each recursion entry.
                    HUSPSPAlgorithm sp = new HUSPSPAlgorithm(totalUtil * su);
                    ExperimentMetrics m = measure(algo, dsName, iter, tag,
                            () -> sp.run(db),
                            () -> sp.candidateCount, () -> sp.patternCount, null, null,
                            null, null, null, null, null, null, sp::cancel);
                    if (m != null && m.runtimeMs != -1) {
                        m.prunedSwu = sp.prunedSWU;
                        m.prunedIip = sp.prunedIIP;      // candidates removed by IIP (RSU below the threshold)
                        m.prunedPeu = sp.prunedPEU;      // Algorithm 3 calls whose PEU is below the threshold
                        m.prunedLar = sp.prunedEP;       // candidates removed by early pruning (sound bound in place of TRSU)
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
                        m.prunedLar = h.prunedLAR;       // candidates discarded by the look-ahead strategy (LAS)
                        m.prunedIip = h.prunedIIP;       // items removed by irrelevant item pruning (IPS)
                        m.prunedPeu = h.prunedPEU;       // lists built whose PEU is below the threshold
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

    /** Overload without the Adaptive suppliers. */
    private static ExperimentMetrics measure(String algo, String dataset, int iter, String opType,
                                             Task task,
                                             LongSup candidateCount, IntSup patternCount,
                                             DblSup lutSup, DblSup cubSup) {
        return measure(algo, dataset, iter, opType, task, candidateCount, patternCount,
                lutSup, cubSup, null, null, null, null, null, null, null);
    }

    /** Overload with Adaptive suppliers but without the cancel hook and the pre-large/threshold suppliers. */
    private static ExperimentMetrics measure(String algo, String dataset, int iter, String opType,
                                             Task task,
                                             LongSup candidateCount, IntSup patternCount,
                                             DblSup lutSup, DblSup cubSup, BoolSup rescanSup,
                                             LongSup prunedEucs, LongSup prunedBound) {
        return measure(algo, dataset, iter, opType, task, candidateCount, patternCount,
                lutSup, cubSup, rescanSup, prunedEucs, prunedBound, null, null, null, null);
    }

    /**
     * Runs the task on its own worker thread under TIMEOUT_MINUTES (default 120, set with
     * -Dtimeout.min).
     *
     * Flow:
     *  * Create a single-thread ExecutorService (daemon, so it cannot block JVM exit).
     *  * Submit the task; the main thread waits at most TIMEOUT_MS.
     *  * COMPLETE: return the metrics with the original opType.
     *  * TIMEOUT: call cancelHook (cooperative cancellation for Adaptive), future.cancel(true)
     *      (interrupt the worker), opType "_OT", runtime_ms = -2. Partial counters are read
     *      where possible.
     *  * Failure: opType "_OOM", "_BUDGET" or "_FAILED", runtime_ms = -1.
     *
     * runtime_ms sentinels:
     *    >=0 : OK (milliseconds)
     *    -1  : failed (OOM, pattern budget exceeded, or another exception)
     *    -2  : overtime (over TIMEOUT_MINUTES)
     *    -3  : completed in live memory mode (timing invalid; see wall_ms_perturbed)
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

        // === MEMORY SAMPLER ===
        //
        // used : peak used heap every 50 ms, including uncollected garbage.
        // live : force ONE full collection, then read used heap RIGHT AFTER, at intervals of
        //        max(MEM_SAMPLE_MS, 4 x the previous collection's duration).
        //        The peak of these readings is the live set, independent of GC scheduling
        //        and -Xmx. It is a LOWER BOUND on the true live-set peak: a peak that comes
        //        and goes between two samples is not seen. The interval bounds that error.
        //
        // Neither mode allocates in the loop (native reads and atomics), so both are safe on
        // a nearly full heap. If the heap is exhausted the thread may get an OOM at a
        // safepoint; it is swallowed silently because printing a stack trace would allocate
        // and cause a second OOM.
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

        // Bytes allocated by the mining thread. Deterministic and independent of GC scheduling,
        // so it measures how much the algorithm allocates without mixing in GC timing.
        // Must be read INSIDE the worker thread before it ends: after shutdownNow the thread
        // is dead and getThreadAllocatedBytes(tid) returns -1.
        final long[] allocStart = {-1L};
        final long[] allocEnd   = {-1L};

        // Build runId NOW, while the heap is healthy, so no string concatenation is needed
        // when filling the metrics after an OOM (a nearly full heap can make concat OOM too).
        final String preRunId = algo + "_" + dataset + "_" + opType + "_" + iter;
        // PRE-ALLOCATE the metrics object and opType strings NOW. All post-processing only
        // assigns primitive or reference fields, with no `new` or concatenation, so it cannot
        // OOM the main thread even when the worker leaves the heap nearly full (e.g. the
        // IncUSP-Miner+ CP-tree growing across batches).
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

        // Daemon thread: an orphaned worker after a timeout cannot block JVM exit.
        ExecutorService exec = null;
        Future<?> future = null;
        // OUTER GUARD around the WHOLE body of measure(): any main-thread OOM or error at ANY
        // allocation point (submit, future.get, filling metrics, shutdown, ...) lands in the
        // final catch, which returns the pre-allocated `m` without allocating, instead of
        // propagating and ending the run.
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
                // Read in finally so there is a value even if the task is cancelled or throws.
                long a = threadAllocatedBytes(tid);
                if (a >= 0) allocEnd[0] = a;
            }
        });

        try {
            future.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            timedOut = true;
            errMsg = "OT (>" + TIMEOUT_MINUTES + " min)";
            // 1. Signal cooperative cancellation (Adaptive leaves its recursion almost at once).
            if (cancelHook != null) {
                try { cancelHook.run(); } catch (Throwable ignored) {}
            }
            // 2. Interrupt the thread (best effort for baselines without a cancel flag).
            future.cancel(true);
            // 3. Wait up to 5 s for a clean exit; if the orphan keeps running, move on anyway.
            try { future.get(5, TimeUnit.SECONDS); } catch (Throwable ignored) {}
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            failed = true; errMsg = "Interrupted";
        } catch (ExecutionException ee) {
            // Only when the task did not catch - rare, since the lambda already catches.
            Throwable cause = ee.getCause();
            if (cause != null) thrown[0] = cause;
        } catch (Throwable mainErr) {
            // ISOLATE MAIN-THREAD ERRORS (especially OutOfMemoryError): when the worker fills
            // the heap (e.g. the IncUSP-Miner+ CP-tree growing across batches), future.get()
            // itself allocates a wait node on the MAIN thread and can throw OOM. Catch it, mark
            // the call failed/OOM, GC, and return a did-not-finish row so the run continues
            // (the caller poisons this algorithm; its heap is freed at the end of its scope).
            failed = true;
            if (mainErr instanceof OutOfMemoryError) { oom = true; errMsg = "MAIN-OOM"; }
            else { errMsg = "MAIN-" + mainErr.getClass().getSimpleName(); }
            try { future.cancel(true); } catch (Throwable ignored) {}
            forceFullGC();
        }

        long runtime = System.currentTimeMillis() - start;

        // Classify thrown[0] (OOM vs other exceptions).
        // IMPORTANT: on timeout the worker catches the CancellationSignal that the cancelHook
        // raised on purpose, so thrown[0] = CancellationSignal. That must NOT be treated as
        // FAILED; it stays an overtime.
        if (thrown[0] != null && !timedOut) {
            Throwable t = thrown[0];
            failed = true;
            if (t instanceof OutOfMemoryError) {
                // === SAFE OOM RECOVERY ===
                // Do NOT concatenate strings (t.getMessage()) here: the worker has left the heap
                // nearly full (e.g. the IncUSP-Miner+ CP-tree), so concatenation can OOM the main
                // thread itself and end the run. Use constants and GC AT ONCE to free transient
                // garbage before any allocation.
                oom = true;
                errMsg = "OOM";
                forceFullGC();
            } else if (t instanceof PreHUSPMAdaptiveAlgorithm.PatternBudgetExceeded) {
                budgetExceeded = true;        // threshold too low, too many patterns (did not finish, not a hard OOM)
                errMsg = "BUDGET";
                forceFullGC();
            } else if (t instanceof StackOverflowError) {
                oom = true; // counted as a resource failure: recursion too deep
                errMsg = "STACKOVERFLOW";
                forceFullGC();
            } else {
                errMsg = "EXC:" + t.getClass().getSimpleName();
                forceFullGC();
            }
            // Logging is wrapped in try/catch: an OOM while building the message is swallowed so the main thread survives.
            try { System.err.println("[" + algo + "/" + dataset + "/" + opType + "] " + errMsg); }
            catch (Throwable ignored) {}
        }
        if (timedOut) {
            try { System.err.println("[" + algo + "/" + dataset + "/" + opType + "] " + errMsg); }
            catch (Throwable ignored) {}
            forceFullGC();
        }

        // Shut down the executor; an orphaned thread, if any, is a daemon and dies with the JVM.
        exec.shutdownNow();
        try { exec.awaitTermination(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}

        // Stop the sampler; peakUsed is the peak heap sampled over the whole run.
        sampling.set(false); heapSampler.interrupt();

        long heapAfter = rt.totalMemory() - rt.freeMemory();

        // m was pre-allocated at the top while the heap was healthy; this only FILLS fields, with no allocation.
        long realRuntime = timedOut ? -2 : (failed ? -1 : runtime);
        // Configuration of this measurement. attachPreHuspm overwrites some columns for a
        // baseline without the corresponding concept.
        m.seed = currentSeed;
        m.repeat = currentRepeat;
        m.blockSize = DEFAULT_BLOCK_SIZE;
        m.kappa = DEFAULT_KAPPA;
        m.delta0 = DEFAULT_DELTA0;
        m.triggerRule = ADAPT_TRIGGER;
        m.memMode = MEM_MODE;
        if (MEM_LIVE) {
            // Forced GC invalidates timing. Write -3 so nobody plots it as a performance
            // figure; the wall time is kept in wall_ms_perturbed.
            m.runtimeMs = (realRuntime < 0) ? realRuntime : -3;
            m.wallMsPerturbed = runtime;
            // Final reading after the worker has stopped: the live set that actually remains.
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
        // Peak: max(sampled peak, heapAfter) minus the baseline.
        // NOTE: in "used" mode this includes uncollected garbage - see ExperimentMetrics.
        long peakHeap = Math.max(peakUsed.get(), heapAfter);
        m.peakHeapMb = Math.max(0, (peakHeap - heapBefore)) / (1024 * 1024);
        if (allocStart[0] >= 0 && allocEnd[0] >= allocStart[0]) {
            m.allocatedMb = (allocEnd[0] - allocStart[0]) / (1024 * 1024);
        } else {
            m.allocatedMb = -1;   // unavailable (e.g. the worker was cancelled midway)
        }
        // On TIMEOUT the partial counters are still read; int/long reads are safe and
        // at worst stale, which is acceptable.
        boolean readCounters = !failed; // OK and timeout both have (partial) state
        m.candidateCount = (readCounters && candidateCount != null) ? safeLong(candidateCount) : 0;
        m.totalPatternsFound = (readCounters && patternCount != null) ? safeInt(patternCount) : 0;
        m.lutDynValue = (readCounters && lutSup != null) ? safeDbl(lutSup) : 0;
        m.cubValue   = (readCounters && cubSup != null) ? safeDbl(cubSup) : 0;
        m.cubTriggeredRescan = readCounters && rescanSup != null && safeBool(rescanSup);
        m.prunedValueBound     = (readCounters && prunedBound != null) ? safeLong(prunedBound) : 0;
        // Pre-large count and absolute thresholds (safe reads; null -> 0 for algorithms without them)
        m.prelargePatternCount = (readCounters && prelargeCount != null) ? safeInt(prelargeCount) : 0;
        m.uutValue             = (readCounters && uutSup != null) ? safeDbl(uutSup) : 0;
        m.sftValue             = (readCounters && sftSup != null) ? safeDbl(sftSup) : 0;
        // opType records how the call ended: _OT (timeout), _OOM (out of memory),
        // _BUDGET (too many patterns), _FAILED (other error), or unchanged (OK).
        if (timedOut)            m.opType = OPT_OT;
        else if (budgetExceeded) m.opType = OPT_BUDGET;
        else if (oom)            m.opType = OPT_OOM;
        else if (failed)         m.opType = OPT_FAILED;
        else                     m.opType = opType;

        // printf is wrapped in try/catch: formatting allocates and can OOM on a full heap.
        // Swallow it so the main thread survives; m is ready for the caller to log.
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
            // Any main-thread OOM or error in measure(): return the pre-allocated `m` (no new
            // allocation, so this catch cannot OOM). Stop the worker and GC so the next step has heap.
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
     * Aggressive GC to reclaim the heap after an algorithm's OOM, or before moving to the
     * next algorithm in a scenario. Several gc() cycles a short time apart make HotSpot
     * clear soft and weak references and promote young to old. System.gc() is a hint, but
     * the JVM almost always honours it when the heap is near its maximum, which matters most
     * right after an OOM. System.runFinalization() is not called (deprecated since Java 18).
     */
    private static void forceFullGC() {
        for (int i = 0; i < 3; i++) {
            System.gc();
            try { Thread.sleep(80); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
    }

    // === MEMORY GUARD ===
    // Prevents a global OOM from killing the main thread when a baseline (e.g. IncUSP-Miner+
    // with its persistent CP-tree) consumes memory batch by batch. Available = maxMemory - used
    // (= freeMemory + the part not yet committed). Below 10% of max it is NOT safe to run the
    // next batch (the heap could fill and the main thread OOM during recovery).
    private static final double MEM_SAFE_FRACTION = 0.10;
    private static boolean isMemorySafe() {
        Runtime rt = Runtime.getRuntime();
        long max = rt.maxMemory();
        long used = rt.totalMemory() - rt.freeMemory();
        long available = max - used; // free in the committed heap + what can still be committed
        return available > (long) (max * MEM_SAFE_FRACTION);
    }

    /**
     * Guard before each batch: forceFullGC, then isMemorySafe.
     * Returns true if it is SAFE to run; false if the batch should be skipped (a warning is printed).
     */
    private static boolean memoryGuardOk(String algo, String dataset, String op) {
        forceFullGC();
        if (isMemorySafe()) return true;
        Runtime rt = Runtime.getRuntime();
        System.err.println("OOM Warning: skipping this batch to protect the main thread [" + algo + "/" + dataset
                + "/" + op + "] availableMB=" + ((rt.maxMemory() - (rt.totalMemory() - rt.freeMemory())) / 1048576));
        return false;
    }

    /** Builds a "SKIPPED" row for an operation skipped because its algorithm is poisoned (an earlier call failed). */
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
     * database at the same place and share one definition; SC5 varies only how finely the
     * rest is delivered.
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
        // Do not floor every share and hand the remainder out from the front. That treats the
        // two ends of the stream differently: INCREASING has its small batches at the front,
        // where the remainder lands, and DECREASING has them at the back, where it does not.
        // On SIGN at K = 100 that leaves ten DECREASING batches empty and no INCREASING ones,
        // and the arms skip an empty batch silently, so a difference in K would pass for a
        // difference in strategy.
        //
        // For EVEN splits this rule gives the same batches as floor-and-remainder on every
        // database the campaign measures, so SC2 and SC7 are unaffected.
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
