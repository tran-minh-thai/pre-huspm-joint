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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Choose the default utility thresholds for a database.
 *
 * A threshold is a ratio of total utility, so it stays well defined when the databases
 * change. It does not stay comparable. The defaults inherited from the earlier vendored
 * files select vastly more patterns on the pinned release: SIGN at 0.025 went from 641
 * patterns to 36,582 while total utility moved by under eight percent. The change is in
 * the shape of the utility distribution, not its scale, so the thresholds have to be
 * re-chosen against the data actually being measured.
 *
 * Two modes:
 *
 *   --targets    for each sweep level, search for the threshold yielding the pattern
 *                count the submitted experiments produced, so the new runs pose
 *                comparable work. This is the default.
 *   --grid a,b,c report the pattern count at each listed threshold.
 *
 * Pattern counts do not depend on the machine, so this can be run anywhere. Runtimes
 * printed here are for impatience only and must not be reported.
 *
 * Examples:
 *   java -Xmx24g -Xss64m -cp build experiments.ThresholdScan BMS1
 *   java -Xmx24g -Xss64m -cp build experiments.ThresholdScan ALL --out results/thresholds
 *   java -Xmx24g -Xss64m -cp build experiments.ThresholdScan FIFA --grid 0.05,0.08,0.12
 *   java -Xmx24g -Xss64m -cp build experiments.ThresholdScan BMS1 --budget-sec 60
 */
public final class ThresholdScan {

    private static final String DIR = "datasets";
    private static final double RL = 0.8, KAPPA = 0.5, DELTA0 = 1e-4;

    /**
     * Pattern counts the submitted experiments produced on D_init, used as the targets.
     * Listed lowest threshold first, matching the sweep order in the paper, so the counts
     * descend. The search runs them in reverse; see searchAll.
     */
    private static final Object[][] TARGETS = {
            {"SIGN",      new int[]{3030, 641, 152, 83}},
            {"LEVIATHAN", new int[]{146, 113, 89, 66}},
            {"FIFA",      new int[]{41, 18, 5, 1}},
            {"BIBLE",     new int[]{343, 177, 103, 56}},
            {"BMS1",      new int[]{23, 20, 17, 15}},
            {"KOSARAK",   new int[]{12, 6, 5, 5}},

            // The three databases added by the pinned release have no submitted counts to
            // inherit, so their ladder is chosen rather than reproduced: four levels inside
            // the range the other databases occupy, which is enough to see a trend without
            // the highest level collapsing to a handful of patterns. Adjust once measured.
            // These are also the only databases that reach within-event extension
            // (C8T1S5I8N5K, ONLINE_RETAIL_II_ALL) or carry measured rather than generated
            // quantities (ONLINE_RETAIL_II_QTY).
            {"C8T1S5I8N5K",          new int[]{300, 150, 80, 40}},
            {"ONLINE_RETAIL_II_ALL", new int[]{300, 150, 80, 40}},
            {"ONLINE_RETAIL_II_QTY", new int[]{300, 150, 80, 40}},
    };

    /** Where the search starts: the default threshold of the submitted experiments. */
    private static double startFrom(String name) {
        switch (name) {
            case "SIGN":      return 0.025;
            case "LEVIATHAN": return 0.060;
            case "FIFA":      return 0.050;
            case "BIBLE":     return 0.025;
            case "BMS1":      return 0.014;
            case "KOSARAK":   return 0.030;
            // No inherited default for these; start in the middle of the observed range.
            case "C8T1S5I8N5K":          return 0.02;
            case "ONLINE_RETAIL_II_ALL": return 0.02;
            case "ONLINE_RETAIL_II_QTY": return 0.02;
            default:          return 0.02;
        }
    }

    private static final double LO = 1e-5, HI = 0.95;
    private static final int MAX_STEPS = 20;

    /**
     * A probe is abandoned when it exceeds either budget, and an abandoned probe counts as
     * "far too many patterns", which pushes the search towards higher thresholds.
     *
     * Without this the tool hangs. Lowering the threshold on a database like BMS1 does not
     * cost gradually more; it explodes. One probe at a threshold a little too low ran for
     * an hour on the measurement machine and returned nothing, because nothing could stop
     * it and the search had no way to learn that going lower was the wrong direction.
     *
     * The candidate budget is the primary limit because it is deterministic: the same probe
     * is abandoned at the same point on any machine, so a calibration does not depend on how
     * fast the machine is. The wall-clock budget is a safety net for the cases the counter
     * does not catch, such as an allocation storm.
     */
    private static long maxCandidates = Long.getLong("scan.maxCandidates", 300_000_000L);
    private static long budgetMs = 180_000L;

    /**
     * Total budget for one sweep level. The per-probe budget alone does not bound the run:
     * a level can take thirty-odd probes, and if every one of them is abandoned that is
     * still hours. When this is spent the search stops and reports the best threshold it
     * actually measured, or says the level was not reachable. Bounded time, always a line
     * of output.
     */
    private static long levelBudgetMs = 900_000L;

    public static void main(String[] args) throws IOException {
        if (args.length == 0) {
            System.out.println("usage: ThresholdScan <DATASET|ALL> [--target N | --grid a,b,c] "
                    + "[--full] [--budget-sec N] [--level-budget-sec N] [--max-candidates N] "
                    + "[--out DIR]");
            System.out.println("databases: SIGN LEVIATHAN FIFA BIBLE BMS1 KOSARAK "
                    + "C8T1S5I8N5K ONLINE_RETAIL_II_ALL ONLINE_RETAIL_II_QTY");
            return;
        }
        String target = args[0];
        double[] grid = null;
        Integer single = null;
        boolean full = false;
        Path outDir = Paths.get("results", "thresholds");

        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--grid":
                    String[] parts = args[++i].split(",");
                    grid = new double[parts.length];
                    for (int j = 0; j < parts.length; j++) grid[j] = Double.parseDouble(parts[j].trim());
                    break;
                case "--full":           full = true; break;
                case "--budget-sec":     budgetMs = Long.parseLong(args[++i]) * 1000L; break;
                case "--level-budget-sec": levelBudgetMs = Long.parseLong(args[++i]) * 1000L; break;
                case "--max-candidates": maxCandidates = Long.parseLong(args[++i]); break;
                case "--target":         single = Integer.parseInt(args[++i]); break;
                case "--targets":        break;                 // the default; accepted for clarity
                case "--out":            outDir = Paths.get(args[++i]); break;
                default: throw new IllegalArgumentException("unknown option: " + args[i]);
            }
        }

        Files.createDirectories(outDir);
        // One file per machine. Two machines appending to one tracked path collide on the
        // next pull: git refuses to overwrite an untracked file, and if it is tracked the
        // two sets of rows conflict. Each machine owns its own file; merging them is a
        // deliberate step, not an accident of whoever pushed last.
        Path csv = outDir.resolve("thresholds-" + hostTag() + ".csv");
        boolean fresh = !Files.exists(csv);
        System.out.printf("budgets: %,d candidates and %d s per probe, %d s per level%n",
                maxCandidates, budgetMs / 1000, levelBudgetMs / 1000);

        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(csv, StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND))) {
            if (fresh) w.println("dataset,scope,level,target_patterns,uut,patterns,probes,mine_ms,status");
            for (Object[] row : TARGETS) {
                String name = (String) row[0];
                if (!"ALL".equalsIgnoreCase(target) && !name.equalsIgnoreCase(target)) continue;
                int[] counts = (single != null) ? new int[]{single} : (int[]) row[1];
                run(name, full, grid, counts, w);
            }
        }
        System.out.println("\n-> " + csv.toAbsolutePath());
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
            System.out.printf("%10s %12s %10s  %s%n", "uut", "patterns", "ms", "status");
            for (double u : grid) {
                Probe p = mine(db, tsu, u);
                System.out.printf("%10.5f %12s %10d  %s%n", u, p.show(), p.ms, p.status());
                w.printf("%s,%s,grid,,%.6f,%d,1,%d,%s%n", name, scope, u,
                        p.abandoned ? -1 : p.patterns, p.ms, p.status());
                w.flush();
            }
            return;
        }
        searchAll(name, scope, db, tsu, targets, w);
    }

    /**
     * Search every sweep level, from the highest threshold down.
     *
     * Two reasons for that direction. The highest threshold yields the fewest patterns and
     * is by far the cheapest probe, so the run produces its first usable line quickly
     * instead of possibly nothing at all. And each level solved becomes an upper bound for
     * the next: wanting more patterns can only mean a lower threshold, so the bracket
     * narrows as the search goes on rather than starting wide every time.
     *
     * The earlier version ran the levels in paper order, which meant it began with the
     * lowest threshold: the most patterns, the most expensive probe, and on BMS1 an hour
     * with nothing to show.
     */
    private static void searchAll(String name, String scope, List<Sequence> db, double tsu,
                                  int[] targets, PrintWriter w) {
        String[] levels = (targets.length == 4)
                ? new String[]{"low", "default", "high", "highest"} : new String[]{"target"};

        System.out.printf("%8s %10s %11s %12s %7s %9s  %s%n",
                "level", "target", "uut", "patterns", "probes", "ms", "status");

        double hi = HI;                       // upper bound carried down from the level above
        double seed = startFrom(name);
        Result[] found = new Result[targets.length];

        for (int i = targets.length - 1; i >= 0; i--) {
            Result r = search(db, tsu, targets[i], seed, LO, hi);
            found[i] = r;
            if (r.ok()) {
                hi = r.uut;                   // a lower level needs a lower threshold
                seed = r.uut;                 // and starts from here
            }
            System.out.printf("%8s %10d %11.5f %12s %7d %9d  %s%n",
                    levels[i], targets[i], r.uut, r.show(), r.probes, r.ms, r.status);
        }

        for (int i = 0; i < targets.length; i++) {
            Result r = found[i];
            w.printf("%s,%s,%s,%d,%.6f,%d,%d,%d,%s%n", name, scope, levels[i], targets[i],
                    r.uut, r.ok() ? r.patterns : -1, r.probes, r.ms, r.status);
            w.flush();
        }
    }

    // ---------------------------------------------------------------- probing

    private static final class Probe {
        int patterns;
        long ms;
        boolean abandoned;      // exceeded a budget, or ran out of memory
        String why = "";
        String show()   { return abandoned ? ">budget" : Integer.toString(patterns); }
        String status() { return abandoned ? "abandoned:" + why : "ok"; }
    }

    private static final class Result {
        double uut;
        int patterns = -1;
        int probes;
        long ms;
        String status = "not-found";
        boolean ok() { return patterns >= 0; }
        String show() { return ok() ? Integer.toString(patterns) : "-"; }
    }

    /**
     * One mining run, abandoned if it exceeds either budget. An abandoned probe is reported
     * rather than retried: to the search it means "too many patterns to be worth counting",
     * which is the same signal as a count above the target.
     */
    private static Probe mine(List<Sequence> db, double tsu, double uut) {
        Probe p = new Probe();
        PreHUSPMAdaptiveAlgo a = new PreHUSPMAdaptiveAlgo(
                uut, KAPPA, DELTA0, RL, AblationMode.FULL, false);
        a.maxCandidatesBudget = maxCandidates;

        ExecutorService ex = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "probe");
            t.setDaemon(true);
            return t;
        });
        final Throwable[] thrown = new Throwable[1];
        long t0 = System.nanoTime();
        Future<?> f = ex.submit(() -> {
            try { a.runInitialMining(new ArrayList<>(db), tsu); }
            catch (Throwable t) { thrown[0] = t; }
        });
        try {
            f.get(budgetMs, TimeUnit.MILLISECONDS);
            if (thrown[0] == null) {
                p.patterns = a.patternCount;
            } else {
                p.abandoned = true;
                p.why = (thrown[0] instanceof OutOfMemoryError) ? "oom"
                      : thrown[0].getClass().getSimpleName().toLowerCase(Locale.ROOT);
            }
        } catch (TimeoutException te) {
            a.cancel();                       // cooperative: the recursion checks this flag
            f.cancel(true);
            try { f.get(10, TimeUnit.SECONDS); } catch (Throwable ignored) { }
            p.abandoned = true;
            p.why = "time";
        } catch (Throwable t) {
            p.abandoned = true;
            p.why = "error";
        } finally {
            ex.shutdownNow();
        }
        p.ms = (System.nanoTime() - t0) / 1_000_000L;
        System.gc();                          // a probe can leave a large pattern set behind
        return p;
    }

    /**
     * Find the threshold yielding about `target` patterns, within [lo, hi], starting at
     * `seed`. Pattern count falls monotonically as the threshold rises, so the search
     * gallops until the target is bracketed and then bisects geometrically, thresholds
     * spanning decades rather than a fixed additive range.
     *
     * An exact count is rarely attainable, since the count moves in jumps as individual
     * patterns cross the threshold, so the search accepts anything within ten percent.
     */
    private static Result search(List<Sequence> db, double tsu, int target,
                                 double seed, double loBound, double hiBound) {
        Result best = new Result();
        double lo = loBound, hi = hiBound;
        double u = Math.min(Math.max(seed, lo), hi);

        // Gallop. Abandoned probes always mean "raise the threshold", never "lower it",
        // which is what keeps the search away from the explosive end of the range.
        int probes = 0;
        long ms = 0;
        long deadline = System.nanoTime() + levelBudgetMs * 1_000_000L;
        boolean bracketed = false;
        Probe p = mine(db, tsu, u);
        probes++; ms += p.ms;
        note(best, u, p, target);

        boolean tooMany = p.abandoned || p.patterns > target;
        for (int i = 0; i < 14 && !bracketed && System.nanoTime() < deadline; i++) {
            if (tooMany) {
                lo = u;
                if (u >= hi) break;
                u = Math.min(hi, u * 1.6);
            } else {
                hi = u;
                if (u <= lo) break;
                u = Math.max(lo, u / 1.6);
            }
            Probe q = mine(db, tsu, u);
            probes++; ms += q.ms;
            note(best, u, q, target);
            boolean qTooMany = q.abandoned || q.patterns > target;
            if (qTooMany != tooMany) {                 // crossed the target
                if (qTooMany) lo = u; else hi = u;
                bracketed = true;
            } else if (tooMany) {
                lo = u;
            } else {
                hi = u;
            }
        }

        for (int i = 0; i < MAX_STEPS && hi / lo > 1.002 && System.nanoTime() < deadline; i++) {
            double mid = Math.sqrt(lo * hi);
            Probe q = mine(db, tsu, mid);
            probes++; ms += q.ms;
            note(best, mid, q, target);
            if (!q.abandoned && Math.abs(q.patterns - target) <= Math.max(1, target * 0.10)) break;
            if (q.abandoned || q.patterns > target) lo = mid; else hi = mid;
        }

        best.probes = probes;
        best.ms = ms;
        boolean spent = System.nanoTime() >= deadline;
        if (best.ok() && best.patterns == 0 && target > 0) {
            // Every threshold that finished returned nothing, and every threshold low
            // enough to return something was abandoned. Reporting the highest one as the
            // "closest" would read as a calibrated value; it is the opposite, a threshold
            // known to be useless. Say that, and leave the threshold out.
            best.uut = 0;
            best.status = "no-patterns-within-budget";
        } else if (best.ok()) {
            best.status = (Math.abs(best.patterns - target) <= Math.max(1, target * 0.10))
                    ? "ok" : (spent ? "closest-budget-spent" : "closest");
        } else {
            // Every probe was abandoned. Say so plainly instead of emitting a threshold
            // that was never actually measured.
            best.uut = 0;
            best.status = "unreachable-within-budget";
        }
        return best;
    }

    /** Keep the probe closest to the target among those that finished. */
    private static void note(Result best, double uut, Probe p, int target) {
        if (p.abandoned) return;
        if (!best.ok() || Math.abs(p.patterns - target) < Math.abs(best.patterns - target)) {
            best.uut = uut;
            best.patterns = p.patterns;
        }
    }

    /** Machine name, reduced to something safe for a file name. */
    private static String hostTag() {
        String h = System.getenv("COMPUTERNAME");
        if (h == null || h.isEmpty()) h = System.getenv("HOSTNAME");
        if (h == null || h.isEmpty()) {
            try { h = java.net.InetAddress.getLocalHost().getHostName(); }
            catch (Exception ignored) { h = "unknown"; }
        }
        return h.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "-");
    }

    static {
        Locale.setDefault(Locale.ROOT);
    }
}
