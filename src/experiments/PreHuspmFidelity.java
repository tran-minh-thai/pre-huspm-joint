package experiments;

import algorithms.PreHUSPMAlgorithm.PreHUSPMAlgo;
import algorithms.PreHUSPMAlgorithm.PreHUSPMAlgo.Variant;
import common.DatasetLoader;
import common.Sequence;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Runs the Pre-HUSPM implementation under the setting of the Pre-HUSPM paper's runtime figure
 * (Yan et al., J. Supercomputing 2025): the first OD.Num sequences mined, then up to 100
 * increments of a fixed number of sequences each, in file order, with Su and Sl given as
 * fractions of the total utility. One variant per run.
 *
 *   java -Xmx48g -Xss64m -cp build experiments.PreHuspmFidelity DATA_DIR NAME SU SL OD_NUM PER_INCREMENT
 *        swumax|tsu OUT_DIR [INCREMENTS]
 *
 * Appends one row per call to OUT_DIR/fidelity_increments.csv: the initial mine (increment 0)
 * and each increment, with whether it rescanned, its time and the running total. A stream that
 * runs out of sequences stops early; the rows say how many increments ran.
 */
public final class PreHuspmFidelity {

    public static void main(String[] a) throws IOException {
        if (a.length < 8) {
            System.err.println("usage: DATA_DIR NAME SU SL OD_NUM PER_INCREMENT swumax|tsu OUT_DIR [INCREMENTS]");
            System.exit(2);
        }
        String dir = a[0], name = a[1];
        double su = Double.parseDouble(a[2]), sl = Double.parseDouble(a[3]);
        int odNum = Integer.parseInt(a[4]), per = Integer.parseInt(a[5]);
        Variant variant = Variant.valueOf(a[6].toUpperCase(Locale.ROOT));
        Path out = Paths.get(a[7]);
        int increments = a.length > 8 ? Integer.parseInt(a[8]) : 100;
        if (!(sl > 0 && sl < su && su < 1)) throw new IllegalArgumentException("need 0 < SL < SU < 1");

        List<Sequence> all = DatasetLoader.loadDataset(Paths.get(dir, name + "_seq.txt").toString(),
                Paths.get(dir, name + "_eui.txt").toString());
        if (all.size() < odNum) throw new IllegalArgumentException(name + " has " + all.size()
                + " sequences, fewer than OD.Num " + odNum);
        List<Sequence> current = new ArrayList<>(all.subList(0, odNum));
        double tu = 0;
        for (Sequence s : current) tu += s.sequenceUtility;

        Files.createDirectories(out);
        Path csv = out.resolve("fidelity_increments.csv");
        boolean fresh = !Files.exists(csv);
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(csv, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND))) {
            if (fresh) w.println("dataset,variant,su,sl,od_num,per_increment,increment,sequences,total_utility,"
                    + "rescanned,rescans,runtime_ms,cumulative_ms,husps,prelarge");
            // Sl is passed as its ratio to Su, as the implementation takes it
            PreHUSPMAlgo algo = new PreHUSPMAlgo(su, sl / su, false, variant);
            System.gc();
            long t0 = System.nanoTime();
            algo.runInitialMining(new ArrayList<>(current), tu);
            double ms = (System.nanoTime() - t0) / 1e6, cum = ms;
            row(w, name, variant, su, sl, odNum, per, 0, current.size(), tu, false, algo, ms, cum);
            int next = odNum, done = 0;
            for (int k = 1; k <= increments; k++) {
                if (next + per > all.size()) {
                    System.out.printf("%s %s: the stream ran out after %d increments%n", name, variant, done);
                    break;
                }
                List<Sequence> delta = new ArrayList<>(all.subList(next, next + per));
                next += per;
                for (Sequence s : delta) tu += s.sequenceUtility;
                current.addAll(delta);
                int before = algo.rescanCount;
                System.gc();
                t0 = System.nanoTime();
                algo.runIncrementalMining(delta, new ArrayList<>(current), tu);
                ms = (System.nanoTime() - t0) / 1e6;
                cum += ms;
                row(w, name, variant, su, sl, odNum, per, k, current.size(), tu, algo.rescanCount > before, algo, ms, cum);
                done = k;
            }
            System.out.printf("%s %s: %d increments, %d rescans, %.1f s%n", name, variant, done,
                    algo.rescanCount, cum / 1000);
        }
    }

    private static void row(PrintWriter w, String name, Variant v, double su, double sl, int od, int per, int k,
                            int n, double tu, boolean rescanned, PreHUSPMAlgo algo, double ms, double cum) {
        w.printf(Locale.ROOT, "%s,%s,%.6f,%.6f,%d,%d,%d,%d,%.1f,%s,%d,%.3f,%.3f,%d,%d%n", name,
                v.name().toLowerCase(Locale.ROOT), su, sl, od, per, k, n, tu, rescanned, algo.rescanCount,
                ms, cum, algo.patternCount, algo.prelargeCount);
        w.flush();
    }
}
