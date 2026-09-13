package experiments;

import algorithms.PreHUSPMAdaptiveAlgorithm.PreHUSPMAdaptiveAlgo;
import algorithms.PreHUSPMAdaptiveAlgorithm.PreHUSPMAdaptiveAlgo.AblationMode;
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
import java.util.Locale;

/**
 * Choose the default utility thresholds for a database.
 *
 * A threshold is a ratio of total utility, so it stays well defined when the databases
 * change. It does not stay comparable. The defaults inherited from the earlier vendored
 * files select vastly more patterns on the pinned release: SIGN at 0.025 went from 641
 * patterns to 36,582, while total utility moved by under eight percent. The change is in
 * the shape of the utility distribution, not its scale, so the thresholds have to be
 * re-chosen against the data actually being measured.
 *
 * Two modes:
 *
 *   --target N   bisect for the threshold that yields about N patterns, then emit four
 *                sweep levels around it. This is the one to use when reproducing the
 *                difficulty of an earlier experiment on new data.
 *   --grid a,b,c report the pattern count at each listed threshold.
 *
 * Pattern counts do not depend on the machine, so this can be run anywhere. Runtimes
 * printed here are for impatience only and must not be reported.
 *
 * Examples:
 *   java -Xmx24g -Xss64m -cp build experiments.ThresholdScan SIGN --target 641
 *   java -Xmx24g -Xss64m -cp build experiments.ThresholdScan ALL --targets
 *   java -Xmx24g -Xss64m -cp build experiments.ThresholdScan FIFA --grid 0.05,0.08,0.12
 */
public final class ThresholdScan {

    private static final String DIR = "datasets";
    private static final double RL = 0.8, KAPPA = 0.5, DELTA0 = 1e-4;

    /**
     * Pattern counts the submitted experiments produced on D_init, used as the targets so
     * the new runs pose a comparable amount of work. Order: the four sweep levels, lowest
     * threshold first; the second is the default.
     */
    private static final Object[][] TARGETS = {
            //  name,          default count, and the counts of the other three levels
            {"SIGN",      new int[]{3030, 641, 152, 83}},
            {"LEVIATHAN", new int[]{146, 113, 89, 66}},
            {"FIFA",      new int[]{41, 18, 5, 1}},
            {"BIBLE",     new int[]{343, 177, 103, 56}},
            {"BMS1",      new int[]{23, 20, 17, 15}},
            {"KOSARAK",   new int[]{12, 6, 5, 5}},
    };

    /**
     * Where the search starts, per database: the default threshold of the submitted
     * experiments. Bisecting the whole plausible range instead would spend its first
     * probes at very low thresholds, where a database can yield hundreds of thousands
     * of patterns and a single probe costs minutes.
     */
    private static double startFrom(String name) {
        switch (name) {
            case "SIGN":      return 0.025;
            case "LEVIATHAN": return 0.060;
            case "FIFA":      return 0.050;
            case "BIBLE":     return 0.025;
            case "BMS1":      return 0.014;
            case "KOSARAK":   return 0.030;
            default:          return 0.02;
        }
    }

    /** Hard bounds on the search. */
    private static final double LO = 1e-5, HI = 0.95;
    private static final int MAX_STEPS = 24;
    /** A probe yielding more than this is treated as "far too many" without finishing. */
    private static final int PROBE_CAP = 400_000;

    public static void main(String[] args) throws IOException {
        if (args.length == 0) {
            System.out.println("usage: ThresholdScan <DATASET|ALL> [--target N | --targets | "
                    + "--grid a,b,c] [--full] [--out DIR]");
            return;
        }
        String target = args[0];
        Integer single = null;
        boolean useTable = false;
        double[] grid = null;
        boolean full = false;                 // scan the whole database instead of D_init
        Path outDir = Paths.get("results", "thresholds");

        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--target":  single = Integer.parseInt(args[++i]); break;
                case "--targets": useTable = true; break;
                case "--grid":
                    String[] parts = args[++i].split(",");
                    grid = new double[parts.length];
                    for (int j = 0; j < parts.length; j++) grid[j] = Double.parseDouble(parts[j].trim());
                    break;
                case "--full":    full = true; break;
                case "--out":     outDir = Paths.get(args[++i]); break;
                default: throw new IllegalArgumentException("unknown option: " + args[i]);
            }
        }
        if (single == null && !useTable && grid == null) useTable = true;

        Files.createDirectories(outDir);
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(
                outDir.resolve("thresholds.csv"), StandardCharsets.UTF_8))) {
            w.println("dataset,scope,level,target_patterns,uut,patterns,mine_ms");
            for (Object[] row : TARGETS) {
                String name = (String) row[0];
                if (!"ALL".equalsIgnoreCase(target) && !name.equalsIgnoreCase(target)) continue;
                int[] counts = (int[]) row[1];
                if (single != null) counts = new int[]{single};
                run(name, full, grid, counts, w);
            }
        }
        System.out.println("\n-> " + outDir.resolve("thresholds.csv").toAbsolutePath());
    }

    private static void run(String name, boolean full, double[] grid, int[] targets, PrintWriter w)
            throws IOException {
        List<Sequence> all = DatasetLoader.loadDataset(
                Paths.get(DIR, name + "_seq.txt").toString(),
                Paths.get(DIR, name + "_eui.txt").toString());
        List<Sequence> db = full ? all : new ArrayList<>(all.subList(0, all.size() / 2));
        double tsu = 0;
        for (Sequence s : db) tsu += s.sequenceUtility;
        String scope = full ? "full" : "D_init";

        System.out.printf("%n=== %s | %s | |D|=%,d | TSU=%,.0f ===%n", name, scope, db.size(), tsu);

        if (grid != null) {
            System.out.printf("%10s %12s %10s%n", "uut", "patterns", "ms");
            for (double u : grid) {
                Probe p = mine(db, tsu, u);
                System.out.printf("%10.5f %12d %10d%n", u, p.patterns, p.ms);
                w.printf("%s,%s,grid,,%.6f,%d,%d%n", name, scope, u, p.patterns, p.ms);
                w.flush();
            }
            return;
        }

        System.out.printf("%7s %12s %10s %12s %8s%n", "level", "target", "uut", "patterns", "steps");
        for (int i = 0; i < targets.length; i++) {
            Bisect b = bisect(db, tsu, targets[i], startFrom(name));
            String level = (targets.length == 4)
                    ? new String[]{"low", "default", "high", "highest"}[i] : "target";
            System.out.printf("%7s %12d %10.5f %12d %8d%n",
                    level, targets[i], b.uut, b.patterns, b.steps);
            w.printf("%s,%s,%s,%d,%.6f,%d,%d%n", name, scope, level, targets[i], b.uut, b.patterns, b.ms);
            w.flush();
        }
    }

    private static final class Probe { int patterns; long ms; }
    private static final class Bisect { double uut; int patterns; int steps; long ms; }

    private static Probe mine(List<Sequence> db, double tsu, double uut) {
        PreHUSPMAdaptiveAlgo a = new PreHUSPMAdaptiveAlgo(
                uut, KAPPA, DELTA0, RL, AblationMode.FULL, false);
        long t0 = System.nanoTime();
        a.runInitialMining(new ArrayList<>(db), tsu);
        Probe p = new Probe();
        p.patterns = a.patternCount;
        p.ms = (System.nanoTime() - t0) / 1_000_000L;
        return p;
    }

    /**
     * Pattern count falls monotonically as the threshold rises, so bisection converges.
     * It stops as soon as the count is within ten percent of the target, or when the
     * bracket is too narrow to matter: an exact count is rarely attainable because the
     * count moves in jumps as individual patterns cross the threshold.
     */
    private static Bisect bisect(List<Sequence> db, double tsu, int target, double seed) {
        // Gallop outward from the seed until the target is bracketed, then bisect. Each
        // step doubles or halves the distance, so a seed off by an order of magnitude
        // costs a handful of probes rather than a scan of the whole range.
        double lo = LO, hi = HI;
        double u = seed;
        Probe p0 = mine(db, tsu, u);
        long galloped = p0.ms;
        int gallopSteps = 1;
        if (p0.patterns > target) {
            lo = u;
            while (gallopSteps < 12) {
                u = Math.min(HI, u * 1.6);
                Probe q = mine(db, tsu, u);
                galloped += q.ms; gallopSteps++;
                if (q.patterns <= target || u >= HI) { hi = u; break; }
                lo = u;
            }
        } else {
            hi = u;
            while (gallopSteps < 12) {
                u = Math.max(LO, u / 1.6);
                Probe q = mine(db, tsu, u);
                galloped += q.ms; gallopSteps++;
                if (q.patterns >= target || u <= LO) { lo = u; break; }
                hi = u;
            }
        }
        Bisect best = new Bisect();
        best.patterns = -1;
        long total = galloped;
        int steps = 0;

        for (; steps < MAX_STEPS; steps++) {
            double mid = Math.sqrt(lo * hi);      // geometric: thresholds span decades
            Probe p = mine(db, tsu, mid);
            total += p.ms;

            if (best.patterns < 0 || Math.abs(p.patterns - target) < Math.abs(best.patterns - target)) {
                best.uut = mid;
                best.patterns = p.patterns;
            }
            if (p.patterns == target) break;
            if (Math.abs(p.patterns - target) <= Math.max(1, target * 0.10)) break;
            if (hi / lo < 1.001) break;           // bracket exhausted

            if (p.patterns > target) lo = mid;    // too many: raise the threshold
            else hi = mid;
        }
        best.steps = steps + 1 + gallopSteps;
        best.ms = total;
        return best;
    }

    static {
        Locale.setDefault(Locale.ROOT);
    }
}
