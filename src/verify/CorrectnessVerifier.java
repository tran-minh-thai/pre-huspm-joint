package verify;

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
import java.util.Map;
import java.util.Random;

/**
 * WP5 - bo kiem chung tinh dung dan.
 *
 * So sanh TAP MAU va utility TUNG MAU cua thuat toan gia tang voi remine-from-scratch,
 * sau MOI thao tac. Ban nop chi doi chieu SO LUONG HUSP, khong du de chung minh
 * Proposition 2: hai tap cung kich thuoc van co the khac nhau.
 *
 * Chay:
 *   java -cp build verify.CorrectnessVerifier SIGN 0.025
 *   java -cp build verify.CorrectnessVerifier SIGN 0.025 --rule swumax --batches 5
 *   java -cp build verify.CorrectnessVerifier ALL --out results/verify
 *
 * Nghiem thu: voi luat iaumax, moi cot missing/extra/maxUtilGap phai bang 0 tren moi
 * cau hinh. Voi luat swumax cu, phai xuat hien mau THIEU o it nhat mot cau hinh - do
 * chinh la bang chung thuc nghiem cho loi ma Reviewer #1 neu o Eq. (9).
 */
public final class CorrectnessVerifier {

    private static final String DATASET_DIR = "datasets";

    // Thresholds come from common.Datasets, the same table the runner measures at. This
    // class used to carry its own copy of the pre-release values, so every artefact it
    // produced verified correctness at an operating point the study had stopped using -
    // LEVIATHAN at 0.060 where the calibrated threshold is 0.01553 - and nothing reported
    // it, because a threshold that is merely wrong still runs perfectly well.

    private static final double RL = 0.8, KAPPA = 0.5, DELTA0 = 1e-4;

    public static void main(String[] args) throws IOException {
        if (args.length == 0) {
            System.out.println("dung: verify.CorrectnessVerifier <DATASET|ALL> [UUT] "
                    + "[--rule swumax|cub|iaumax] [--batches K] [--mixed] [--out DIR]");
            System.out.println("databases: " + common.Datasets.names());
            return;
        }

        String target = args[0];
        Double uutArg = null;
        TriggerRule rule = TriggerRule.IAUMAX;
        int batches = 5;
        boolean mixed = false;
        Path outDir = Paths.get("results", "verify");

        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--rule":    rule = TriggerRule.valueOf(args[++i].toUpperCase()); break;
                case "--batches": batches = Integer.parseInt(args[++i]); break;
                case "--mixed":   mixed = true; break;
                case "--out":     outDir = Paths.get(args[++i]); break;
                default:          uutArg = Double.parseDouble(args[i]);
            }
        }

        Files.createDirectories(outDir);
        Path csv = outDir.resolve("verification.csv");
        boolean fresh = !Files.exists(csv);

        int totalViolations = 0;
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(csv,
                StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND))) {
            if (fresh) {
                w.println("dataset,uut,trigger_rule,batches,op_index,op_type,"
                        + "ref_husp,got_husp,missing,extra,max_utility_gap,rescans,clean");
            }
            boolean ran = false;
            for (common.Datasets.Entry e : common.Datasets.all()) {
                if (!"ALL".equalsIgnoreCase(target) && !e.name.equalsIgnoreCase(target)) continue;
                double uut = (uutArg != null) ? uutArg : e.defaultUut;
                totalViolations += verifyDataset(e.name, uut, rule, batches, mixed, w);
                ran = true;
            }
            if (!ran) {
                System.out.println("khong co database nao ten " + target
                        + "; co the chon: " + common.Datasets.names());
                return;
            }
        }

        System.out.println();
        System.out.println("verification.csv -> " + csv.toAbsolutePath());
        if (totalViolations == 0) {
            System.out.println("KET LUAN: khong co sai lech nao. Tap mau khop remine-from-scratch"
                    + " sau moi thao tac.");
        } else {
            System.out.println("KET LUAN: " + totalViolations + " thao tac co sai lech."
                    + (rule == TriggerRule.SWUMAX
                       ? " (Voi luat swumax day la ket qua MONG DOI - bang chung cho loi Eq. (9).)"
                       : " (Voi luat nay day la LOI - phai sua truoc khi chay thuc nghiem.)"));
        }
        // Chi bao loi khi luat dang duoc DE XUAT lai sai.
        if (totalViolations > 0 && rule != TriggerRule.SWUMAX) System.exit(1);
    }

    private static int verifyDataset(String name, double uut, TriggerRule rule,
                                     int nBatches, boolean mixed, PrintWriter w) throws IOException {
        List<Sequence> all = DatasetLoader.loadDataset(
                Paths.get(DATASET_DIR, name + "_seq.txt").toString(),
                Paths.get(DATASET_DIR, name + "_eui.txt").toString());

        System.out.printf("%n=== %s | UUT=%.4f | luat=%s | %d lo | %s ===%n",
                name, uut, rule, nBatches, mixed ? "hon hop" : "chi chen");

        int half = all.size() / 2;
        List<Sequence> current = new ArrayList<>(all.subList(0, half));
        List<Sequence> stream = new ArrayList<>(all.subList(half, all.size()));

        PreHUSPMAdaptiveAlgo algo = new PreHUSPMAdaptiveAlgo(
                uut, KAPPA, DELTA0, RL, AblationMode.FULL, false);
        algo.triggerRule = rule;
        algo.runInitialMining(new ArrayList<>(current), tsu(current));

        int violations = 0;
        int perBatch = Math.max(1, stream.size() / nBatches);
        Random rnd = new Random(42);

        for (int b = 0; b < nBatches; b++) {
            int from = b * perBatch;
            int to = (b == nBatches - 1) ? stream.size() : Math.min(stream.size(), from + perBatch);
            if (from >= to) break;
            List<Sequence> batch = new ArrayList<>(stream.subList(from, to));

            String opType;
            if (!mixed || b % 3 != 2) {
                opType = "insert";
                current.addAll(batch);
                algo.runIncrementalMining(batch, new ArrayList<>(current), tsu(current));
            } else {
                // xoa mot phan nho cua CSDL hien tai de kich hoat nhanh xoa
                opType = "delete";
                int k = Math.max(1, current.size() / 20);
                List<Sequence> victims = new ArrayList<>();
                for (int i = 0; i < k; i++) victims.add(current.get(rnd.nextInt(current.size() - i)));
                current.removeAll(victims);
                algo.runDeletion(victims, new ArrayList<>(current), tsu(current));
            }

            // moc tham chieu: mine lai tu dau tren CSDL hien tai
            PreHUSPMAdaptiveAlgo ref = new PreHUSPMAdaptiveAlgo(
                    uut, KAPPA, DELTA0, RL, AblationMode.FULL, false);
            ref.runInitialMining(new ArrayList<>(current), tsu(current));

            Map<String, Double> refSet = ref.exportLargeAsStrings();
            Map<String, Double> gotSet = algo.exportLargeAsStrings();
            PatternSetDiff diff = PatternSetDiff.of(refSet, gotSet);
            if (!diff.isClean()) violations++;

            w.printf("%s,%.6f,%s,%d,%d,%s,%d,%d,%d,%d,%.9f,%d,%s%n",
                    name, uut, rule, nBatches, b + 1, opType,
                    diff.refSize, diff.gotSize, diff.missing, diff.extra,
                    diff.maxUtilityGap, algo.rescanCount, diff.isClean());
            w.flush();

            System.out.printf("  thao tac %d (%s): %s  rescans=%d  %s%n",
                    b + 1, opType, diff, algo.rescanCount,
                    diff.isClean() ? "OK" : "<<< SAI LECH");
        }
        return violations;
    }

    private static double tsu(List<Sequence> seqs) {
        double t = 0;
        for (Sequence s : seqs) t += s.sequenceUtility;
        return t;
    }
}
