package experiments;

import algorithms.PreHUSPMAlgorithm.PreHUSPMAlgo;
import algorithms.PreHUSPMAlgorithm.PreHUSPMAlgo.Variant;
import common.DatasetLoader;
import common.Pattern;
import common.Sequence;
import verify.PatternSetDiff;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Compares the two variants of the Pre-HUSPM baseline.
 *
 * Yan et al. give two rescan trigger rules: one bounded by TSU(d), which is sound, and one
 * bounded by SWUmax(d), which is not. This study measures the COST of the sound variant
 * under the authors' own protocol: an original database of OD.Num sequences, with the rest
 * split into 100 batches (their Table 16).
 *
 * For each variant: rescan count, runtime, and the pattern-set difference from
 * remine-from-scratch after the last batch.
 *
 * Run: java -Xmx24g -cp build experiments.PreHuspmVariantCompare LEVIATHAN
 */
public final class PreHuspmVariantCompare {

    private static final String DIR = "datasets/";
    private static final double RL = 0.8;

    /** dataset, UUT, OD.Num from Table 16 of Yan et al. */
    private static final Object[][] CFG = {
            {"SIGN",      0.025,   230},
            {"LEVIATHAN", 0.060,  2834},
            {"FIFA",      0.050, 10450},
            {"BIBLE",     0.025, 21369},
            {"BMS1",      0.014, 39601},
            {"KOSARAK",   0.030,     0},   // they use Kosarak10k, which is not directly comparable
    };

    private static final int N_BATCHES = 100;

    public static void main(String[] args) throws IOException {
        String target = args.length > 0 ? args[0] : "ALL";
        Path outDir = Paths.get("results", "variants");
        for (int i = 1; i < args.length; i++) if ("--out".equals(args[i])) outDir = Paths.get(args[++i]);
        Files.createDirectories(outDir);

        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(
                outDir.resolve("prehuspm_variants.csv"), StandardCharsets.UTF_8))) {
            w.println("dataset,uut,variant,od_num,n_batches,batch_seqs,batch_pct_utility,"
                    + "rescans,runtime_ms,husp_count,missing,extra,max_utility_gap");
            for (Object[] c : CFG) {
                String name = (String) c[0];
                int od = (Integer) c[2];
                if (od <= 0) continue;
                if (!"ALL".equalsIgnoreCase(target) && !name.equalsIgnoreCase(target)) continue;
                compare(name, (Double) c[1], od, w);
            }
        }
        System.out.println("\n-> " + outDir.resolve("prehuspm_variants.csv").toAbsolutePath());
    }

    private static void compare(String name, double uut, int odNum, PrintWriter w) throws IOException {
        List<Sequence> all = DatasetLoader.loadDataset(
                DIR + name + "_seq.txt", DIR + name + "_eui.txt");
        if (odNum >= all.size()) {
            System.out.printf("%s: OD.Num=%d >= |D|=%d, skipped%n", name, odNum, all.size());
            return;
        }
        List<Sequence> init = new ArrayList<>(all.subList(0, odNum));
        List<Sequence> stream = new ArrayList<>(all.subList(odNum, all.size()));
        int perBatch = Math.max(1, stream.size() / N_BATCHES);
        double tsuInit = tsu(init);
        double batchPct = 100.0 * (perBatch * (tsuInit / init.size())) / tsuInit;

        System.out.printf("%n=== %s | UUT=%.4f | Yan et al. protocol: OD=%d, %d batches, %d seqs/batch "
                        + "(~%.3f%% utility) ===%n",
                name, uut, odNum, N_BATCHES, perBatch, batchPct);

        Map<String, Double> reference = null;
        for (Variant v : new Variant[]{Variant.SWUMAX, Variant.TSU}) {
            PreHUSPMAlgo algo = new PreHUSPMAlgo(uut, RL, false, v);
            List<Sequence> current = new ArrayList<>(init);
            long t0 = System.nanoTime();
            algo.runInitialMining(new ArrayList<>(current), tsu(current));
            for (int b = 0; b < N_BATCHES; b++) {
                int from = b * perBatch;
                int to = (b == N_BATCHES - 1) ? stream.size() : Math.min(stream.size(), from + perBatch);
                if (from >= to) break;
                List<Sequence> batch = new ArrayList<>(stream.subList(from, to));
                current.addAll(batch);
                algo.runIncrementalMining(batch, new ArrayList<>(current), tsu(current));
            }
            long ms = (System.nanoTime() - t0) / 1_000_000L;

            if (reference == null) {
                PreHUSPMAlgo ref = new PreHUSPMAlgo(uut, RL, false, Variant.TSU);
                ref.runInitialMining(new ArrayList<>(current), tsu(current));
                reference = asStrings(ref.largeMap);
            }
            PatternSetDiff d = PatternSetDiff.of(reference, asStrings(algo.largeMap));

            System.out.printf("  %-8s rescans=%3d/%d  %7dms  HUSP=%d  %s%n",
                    v, algo.rescanCount, N_BATCHES, ms, algo.patternCount,
                    d.isClean() ? "matches remine" : "MISMATCH: " + d);

            w.printf("%s,%.6f,%s,%d,%d,%d,%.4f,%d,%d,%d,%d,%d,%.9f%n",
                    name, uut, v, odNum, N_BATCHES, perBatch, batchPct,
                    algo.rescanCount, ms, algo.patternCount,
                    d.missing, d.extra, d.maxUtilityGap);
            w.flush();
        }
    }

    private static Map<String, Double> asStrings(Map<Pattern, Double> m) {
        Map<String, Double> out = new HashMap<>(m.size() * 2);
        for (Map.Entry<Pattern, Double> e : m.entrySet()) out.put(e.getKey().toIntuitiveString(), e.getValue());
        return out;
    }

    private static double tsu(List<Sequence> seqs) {
        double t = 0;
        for (Sequence s : seqs) t += s.sequenceUtility;
        return t;
    }
}
