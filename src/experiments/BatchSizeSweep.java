package experiments;

import algorithms.PreHUSPMAdaptiveAlgorithm.PreHUSPMAdaptiveAlgo;
import algorithms.PreHUSPMAdaptiveAlgorithm.PreHUSPMAdaptiveAlgo.AblationMode;
import algorithms.PreHUSPMAdaptiveAlgorithm.PreHUSPMAdaptiveAlgo.TriggerRule;
import common.DatasetLoader;
import common.Sequence;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Batch-size sweep.
 *
 * The insertion safety budget of the pre-large layer in the utility setting is
 *
 *     SFT_ins = (UUT - LUT_min)/(1 - UUT) * TSU_ref,
 *
 * only about 0.28% to 1.28% of total utility at the default parameters. A batch of 10% of
 * the data exceeds it 8 to 36 times, so a trigger rule that rarely rescans at that size is
 * missing perturbations rather than adapting to them.
 *
 * This study measures the rescan ratio against batch size for all three trigger rules on
 * the same batch stream, to locate the range in which the pre-large layer actually operates.
 *
 * Run:
 *   java -cp build experiments.BatchSizeSweep LEVIATHAN
 *   java -cp build experiments.BatchSizeSweep ALL --out results/sweep
 */
public final class BatchSizeSweep {

    private static final String DATASET_DIR = "datasets/";
    private static final double KAPPA = 0.5, DELTA0 = 1e-4;

    /**
     * Batch size as a percentage of the TOTAL UTILITY of D_init.
     *
     * This axis is measured in utility rather than in batch count, so it complements SC5
     * instead of repeating it: SC5 sweeps the number of batches
     * (common.Datasets.BATCH_COUNT_SWEEP) over a fixed stream, while this sweeps the size
     * of one batch against the safety budget, which is a share of total utility rather
     * than of the sequence count.
     */
    private static final double[] BATCH_PCT = {0.1, 0.25, 0.5, 1.0, 2.0, 5.0, 10.0};

    public static void main(String[] args) throws IOException {
        String target = args.length > 0 ? args[0] : "ALL";
        Path outDir = Paths.get("results", "sweep");
        for (int i = 1; i < args.length; i++) {
            if ("--out".equals(args[i])) outDir = Paths.get(args[++i]);
        }
        if (!"ALL".equalsIgnoreCase(target) && common.Datasets.byName(target) == null) {
            System.out.println("no database named " + target + "; choose from "
                    + common.Datasets.names());
            System.exit(2);
        }
        Files.createDirectories(outDir);
        Path csv = outDir.resolve("SC_batchsize.csv");

        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(csv, StandardCharsets.UTF_8))) {
            w.println("dataset,uut,r_l,d_init,trigger_rule,batch_pct,batch_seqs,n_batches,"
                    + "rescans,rescan_ratio,runtime_ms,husp_count,prelarge_count,"
                    + "cub_final,iau_max_final,iau_tightness,sft_ins,tsu_final");
            for (common.Datasets.Entry e : common.Datasets.all()) {
                if (!"ALL".equalsIgnoreCase(target) && !e.name.equalsIgnoreCase(target)) continue;
                sweep(e.name, e.defaultUut, w);
            }
        }
        System.out.println("\n-> " + csv.toAbsolutePath());
    }

    private static void sweep(String name, double uut, PrintWriter w) throws IOException {
        List<Sequence> all = DatasetLoader.loadDataset(
                DATASET_DIR + name + "_seq.txt", DATASET_DIR + name + "_eui.txt");
        // D_init and r_l come from common.Datasets: the thresholds are calibrated on exactly
        // this scope, and r_l is read per database. Half the database or a hand-set r_l would
        // measure at an operating point nobody chose.
        double rl = common.Datasets.slMinRatio(name);
        int dInit = common.Datasets.dInitCount(name, all.size());
        List<Sequence> init = new ArrayList<>(all.subList(0, dInit));
        List<Sequence> stream = new ArrayList<>(all.subList(dInit, all.size()));
        double tsuInit = tsu(init);
        double sftIns = ((uut - rl * uut) / (1.0 - uut)) * tsuInit;

        System.out.printf("%n=== %s | UUT=%.4f | TSU(D_init)=%.0f | SFT_ins=%.0f (%.3f%% TSU) ===%n",
                name, uut, tsuInit, sftIns, 100 * sftIns / tsuInit);
        System.out.printf("%8s %10s %9s %9s %9s %9s %9s%n",
                "batch%", "seqs/batch", "batches", "swumax", "cub", "iaumax", "tightness");

        for (double pct : BATCH_PCT) {
            // batch size in utility, converted to a sequence count through the mean utility
            double meanU = tsuInit / Math.max(1, init.size());
            int perBatch = Math.max(1, (int) Math.round(pct / 100.0 * tsuInit / meanU));
            int nBatches = Math.max(1, stream.size() / perBatch);
            if (nBatches > 40) nBatches = 40;      // cap that keeps the run time bounded

            int[] rescans = new int[3];
            double tightness = 1.0;
            long[] runtimes = new long[3];
            int[] husp = new int[3];
            int[] prelarge = new int[3];
            double[] cubF = new double[3], iauF = new double[3], tsuF = new double[3];

            TriggerRule[] rules = {TriggerRule.SWUMAX, TriggerRule.CUB, TriggerRule.IAUMAX};
            for (int r = 0; r < rules.length; r++) {
                PreHUSPMAdaptiveAlgo algo = new PreHUSPMAdaptiveAlgo(
                        uut, KAPPA, DELTA0, rl, AblationMode.FULL, false);
                algo.triggerRule = rules[r];

                List<Sequence> current = new ArrayList<>(init);
                long t0 = System.nanoTime();
                algo.runInitialMining(new ArrayList<>(current), tsu(current));
                for (int b = 0; b < nBatches; b++) {
                    int from = b * perBatch;
                    int to = Math.min(stream.size(), from + perBatch);
                    if (from >= to) break;
                    List<Sequence> batch = new ArrayList<>(stream.subList(from, to));
                    current.addAll(batch);
                    algo.runIncrementalMining(batch, new ArrayList<>(current), tsu(current));
                }
                runtimes[r] = (System.nanoTime() - t0) / 1_000_000L;
                rescans[r] = algo.rescanCount;
                husp[r] = algo.patternCount;
                prelarge[r] = algo.prelargeCount;
                cubF[r] = algo.cubValue;
                iauF[r] = algo.iauMax;
                tsuF[r] = tsu(current);
                if (rules[r] == TriggerRule.IAUMAX) tightness = algo.iauTightness;

                w.printf("%s,%.6f,%.2f,%d,%s,%.4f,%d,%d,%d,%.6f,%d,%d,%d,%.3f,%.3f,%.4f,%.3f,%.3f%n",
                        name, uut, rl, dInit, rules[r], pct, perBatch, nBatches,
                        rescans[r], nBatches == 0 ? 0.0 : (double) rescans[r] / nBatches,
                        runtimes[r], husp[r], prelarge[r],
                        cubF[r], iauF[r], tightness, sftIns, tsuF[r]);
                w.flush();
            }

            System.out.printf("%7.2f%% %10d %9d %9s %9s %9s %9.2fx%n",
                    pct, perBatch, nBatches,
                    rescans[0] + "/" + nBatches, rescans[1] + "/" + nBatches,
                    rescans[2] + "/" + nBatches, tightness);
        }
    }

    private static double tsu(List<Sequence> seqs) {
        double t = 0;
        for (Sequence s : seqs) t += s.sequenceUtility;
        return t;
    }
}
