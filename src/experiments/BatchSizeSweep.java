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
 * WP4.1 - TRUC KICH THUOC LO.
 *
 * Kich ban tra loi truc tiep cho van de trung tam ma Reviewer #1 neu o comment 1 va 2:
 * ngan sach an toan cua lop pre-large trong boi canh utility la
 *
 *     SFT_ins = (UUT - LUT_min)/(1 - UUT) * TSU_ref,
 *
 * tuc chi khoang 0.28% den 1.28% tong utility voi tham so cua ban thao. Trong khi do
 * thiet ke thuc nghiem cu dung lo bang 10% du lieu, vuot ngan sach tu 8 den 36 lan.
 * Ket qua "rat it rescan" cua ban nop la he qua truc tiep cua luat khong sound, khong
 * phai cua co che thich ung.
 *
 * Kich ban do ty le rescan theo kich thuoc lo cho CA BA luat kich hoat, tren cung mot
 * chuoi lo, de xac dinh VUNG VAN HANH thuc su cua lop pre-large.
 *
 * Chay:
 *   java -cp build experiments.BatchSizeSweep LEVIATHAN
 *   java -cp build experiments.BatchSizeSweep ALL --out results/sweep
 */
public final class BatchSizeSweep {

    private static final String DATASET_DIR = "datasets/";
    private static final double KAPPA = 0.5, DELTA0 = 1e-4;

    /**
     * Kich thuoc lo tinh theo phan tram TONG UTILITY cua CSDL ban dau.
     *
     * Truc nay do theo UTILITY chu khong theo so lo, nen no bo tro cho SC5 thay vi trung:
     * SC5 quet so lo (common.Datasets.BATCH_COUNT_SWEEP) tren cung mot dong du lieu, con
     * day quet kich thuoc mot lo so voi NGAN SACH AN TOAN, vi ngan sach do la mot ty le
     * cua tong utility chu khong phai cua so chuoi.
     */
    private static final double[] BATCH_PCT = {0.1, 0.25, 0.5, 1.0, 2.0, 5.0, 10.0};

    public static void main(String[] args) throws IOException {
        String target = args.length > 0 ? args[0] : "ALL";
        Path outDir = Paths.get("results", "sweep");
        for (int i = 1; i < args.length; i++) {
            if ("--out".equals(args[i])) outDir = Paths.get(args[++i]);
        }
        if (!"ALL".equalsIgnoreCase(target) && common.Datasets.byName(target) == null) {
            System.out.println("khong co CSDL " + target + "; co the chon: "
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
        // D_init va r_l lay tu common.Datasets: nguong duoc hieu chinh tren dung pham vi
        // nay, va KOSARAK dung r_l = 0.85 chu khong 0.8 - dung mot nua CSDL hay mot r_l
        // chung se do o diem van hanh khong ai chon.
        double rl = common.Datasets.slMinRatio(name);
        int dInit = common.Datasets.dInitCount(name, all.size());
        List<Sequence> init = new ArrayList<>(all.subList(0, dInit));
        List<Sequence> stream = new ArrayList<>(all.subList(dInit, all.size()));
        double tsuInit = tsu(init);
        double sftIns = ((uut - rl * uut) / (1.0 - uut)) * tsuInit;

        System.out.printf("%n=== %s | UUT=%.4f | TSU(D_init)=%.0f | SFT_ins=%.0f (%.3f%% TSU) ===%n",
                name, uut, tsuInit, sftIns, 100 * sftIns / tsuInit);
        System.out.printf("%8s %10s %9s %9s %9s %9s %9s%n",
                "batch%", "seqs/lo", "so lo", "swumax", "cub", "iaumax", "chat/tho");

        for (double pct : BATCH_PCT) {
            // kich thuoc lo tinh theo UTILITY, quy doi sang so chuoi qua utility trung binh
            double meanU = tsuInit / Math.max(1, init.size());
            int perBatch = Math.max(1, (int) Math.round(pct / 100.0 * tsuInit / meanU));
            int nBatches = Math.max(1, stream.size() / perBatch);
            if (nBatches > 40) nBatches = 40;      // tran de thoi gian chay huu han

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
