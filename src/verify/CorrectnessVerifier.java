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
 * Correctness verifier.
 *
 * Compares the PATTERN SET and the utility of EVERY PATTERN of the incremental algorithm
 * with remine-from-scratch after EVERY operation. Comparing HUSP counts alone cannot
 * establish that the incremental pattern set is exact: two sets of the same size can still
 * differ.
 *
 * Run:
 *   java -cp build verify.CorrectnessVerifier SIGN 0.025
 *   java -cp build verify.CorrectnessVerifier SIGN 0.025 --rule swumax --batches 5
 *   java -cp build verify.CorrectnessVerifier ALL --out results/verify
 *
 * Acceptance: with any rule other than swumax, missing, extra and maxUtilGap must be 0 in
 * every configuration, and the exit status is 1 otherwise. With the swumax rule MISSING
 * patterns are expected in at least one configuration, as empirical evidence that the
 * SWUmax trigger is unsound; they do not change the exit status.
 */
public final class CorrectnessVerifier {

    private static final String DATASET_DIR = "datasets";

    // Thresholds come from common.Datasets, the same table the runner measures at, never
    // from a local copy: a wrong threshold still runs perfectly well, so nothing would
    // report it.

    private static final double RL = 0.8, KAPPA = 0.5, DELTA0 = 1e-4;

    public static void main(String[] args) throws IOException {
        if (args.length == 0) {
            System.out.println("usage: verify.CorrectnessVerifier <DATASET|ALL> [UUT] "
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
                System.out.println("no database named " + target
                        + "; choose from " + common.Datasets.names());
                return;
            }
        }

        System.out.println();
        System.out.println("verification.csv -> " + csv.toAbsolutePath());
        if (totalViolations == 0) {
            System.out.println("RESULT: no mismatch. The pattern set matches remine-from-scratch"
                    + " after every operation.");
        } else {
            System.out.println("RESULT: " + totalViolations + " operations with a mismatch."
                    + (rule == TriggerRule.SWUMAX
                       ? " (EXPECTED with the swumax rule, whose trigger is unsound.)"
                       : " (With this rule it is an ERROR - fix it before running experiments.)"));
        }
        // Fail only when a rule other than swumax produces a mismatch.
        if (totalViolations > 0 && rule != TriggerRule.SWUMAX) System.exit(1);
    }

    /** k distinct sequences of {@code from}, in random order. */
    private static List<Sequence> sample(List<Sequence> from, int k, Random rnd) {
        List<Sequence> copy = new ArrayList<>(from);
        java.util.Collections.shuffle(copy, rnd);
        return new ArrayList<>(copy.subList(0, Math.min(k, copy.size())));
    }

    private static int verifyDataset(String name, double uut, TriggerRule rule,
                                     int nBatches, boolean mixed, PrintWriter w) throws IOException {
        List<Sequence> all = DatasetLoader.loadDataset(
                Paths.get(DATASET_DIR, name + "_seq.txt").toString(),
                Paths.get(DATASET_DIR, name + "_eui.txt").toString());

        System.out.printf("%n=== %s | UUT=%.4f | rule=%s | %d batches | %s ===%n",
                name, uut, rule, nBatches, mixed ? "mixed" : "insert only");

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

            // --mixed cycles insert, insert, delete, modify, so the default five operations
            // are I, I, D, M, I: every kind occurs, and the last insertion follows a deletion
            // and a modification, so it is checked against a state the other two left behind.
            String opType;
            int kind = mixed ? b % 4 : 0;
            if (kind == 0 || kind == 1) {
                opType = "insert";
                current.addAll(batch);
                algo.runIncrementalMining(batch, new ArrayList<>(current), tsu(current));
            } else if (kind == 2) {
                // delete a twentieth of the current database, drawn without replacement: a
                // sequence drawn twice would have its utility subtracted twice
                opType = "delete";
                List<Sequence> victims = sample(current, Math.max(1, current.size() / 20), rnd);
                current.removeAll(victims);
                algo.runDeletion(victims, new ArrayList<>(current), tsu(current));
            } else {
                // modify as SC6 does: k sequences of the current database are replaced by k
                // sequences of the stream, so each pair is an old and a new version of a record.
                // A five-hundredth of the database: small enough to fall inside the safety
                // budget, so it exercises the direct update rather than a rescan. At a
                // twentieth every modification rescans, and a rescan is only a remine.
                opType = "modify";
                int k = Math.min(batch.size(), Math.max(1, current.size() / 500));
                List<Sequence> oldSeqs = sample(current, k, rnd);
                List<Sequence> newSeqs = new ArrayList<>(batch.subList(0, k));
                current.removeAll(oldSeqs);
                current.addAll(newSeqs);
                algo.runModification(oldSeqs, newSeqs, new ArrayList<>(current), tsu(current));
            }

            // reference: remine from scratch on the current database
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

            System.out.printf("  operation %d (%s): %s  rescans=%d  %s%n",
                    b + 1, opType, diff, algo.rescanCount,
                    diff.isClean() ? "OK" : "<<< MISMATCH");
        }
        return violations;
    }

    private static double tsu(List<Sequence> seqs) {
        double t = 0;
        for (Sequence s : seqs) t += s.sequenceUtility;
        return t;
    }
}
