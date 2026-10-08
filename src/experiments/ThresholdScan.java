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
 * A threshold is a ratio of total utility, so it stays well defined when the data
 * changes, but it does not stay comparable: on two releases of SIGN whose total utility
 * differs by under eight percent, 0.025 selects 641 patterns on one and 36,582 on the
 * other. The difference is in the shape of the utility distribution, not its scale, so
 * thresholds are chosen against the data actually being measured.
 *
 * Two modes:
 *
 *   --targets    for each sweep level, search for the threshold yielding the target
 *                pattern count in TARGETS, so every level poses a known amount of work.
 *                This is the default.
 *   --grid a,b,c report the pattern count at each listed threshold.
 *
 * Pattern counts do not depend on the machine, so this can be run anywhere. Runtimes
 * printed here are for impatience only and must not be reported.
 *
 * Usage:
 *   java -Xmx24g -Xss64m -cp build experiments.ThresholdScan BMS1
 *   java -Xmx24g -Xss64m -cp build experiments.ThresholdScan ALL --out results/thresholds
 *   java -Xmx24g -Xss64m -cp build experiments.ThresholdScan FIFA --grid 0.05,0.08,0.12
 *   java -Xmx24g -Xss64m -cp build experiments.ThresholdScan BMS1 --budget-sec 60
 */
public final class ThresholdScan {

    private static final String DIR = "datasets";
    private static final double KAPPA = 0.5, DELTA0 = 1e-4;

    /**
     * The pre-large floor the gate probes at, which has to be the one the database is
     * measured at or the gate is testing a threshold nothing ever mines. KOSARAK is 0.85.
     */
    private static double rl(String name) { return common.Datasets.slMinRatio(name); }

    /**
     * The floor for the database being scanned, set once per run.
     *
     * mine() builds the algorithm and does not know which database it is on, so the value
     * is parked here rather than threaded through every probe. One database per process,
     * one thread doing the mining, so there is nothing for it to race with.
     */
    private static double currentRl = common.Datasets.DEFAULT_SL_MIN_RATIO;

    /**
     * Target pattern counts on D_init, one per sweep level. Listed lowest threshold first,
     * matching the sweep order of common.Datasets, so the counts descend. The search runs
     * them in reverse; see searchAll.
     */
    private static final Object[][] TARGETS = {
            {"SIGN",      new int[]{3030, 641, 152, 83}},
            {"LEVIATHAN", new int[]{146, 113, 89, 66}},
            {"FIFA",      new int[]{41, 18, 5, 1}},
            {"BIBLE",     new int[]{343, 177, 103, 56}},
            {"BMS1",      new int[]{23, 20, 17, 15}},
            // The widest spread the database allows on D_init. Counts of 6 and 5 both land
            // at 0.030, which would put two operating points under four labels, and 0.019
            // already exceeds three billion candidates, so twelve is the ceiling.
            {"KOSARAK",   new int[]{12, 9, 7, 5}},

            // C8T1S5I8N5K is the one database here that reaches within-event extension.
            // The counts are high because lower ones collapse: thresholds that give 300
            // patterns on D_init give 1 on the whole database, which the trajectory gate
            // rejects. At these counts every level keeps at least 23% of its D_init count
            // when the database grows.
            {"C8T1S5I8N5K",          new int[]{6337, 4894, 3695, 2741}},

            // Ta-Feng is the only database whose utilities are measured on both sides:
            // quantities from AMOUNT, profits from SALES_PRICE / AMOUNT. Ten is the ceiling,
            // 0.0029 yields ten patterns in under a second and 0.0028 exceeds the budget.
            {"TAFENG",               new int[]{10, 6, 5, 4}},
    };

    /**
     * Where the search starts.
     *
     * The calibrated default from common.Datasets when there is one, because it is the
     * closest known threshold and a search seeded near the answer spends its budget
     * bisecting rather than travelling. This is only a starting point - the bracket still
     * has to earn the result - so seeding from the table the scan will overwrite costs
     * nothing in independence and saves probes on the expensive databases.
     *
     * The literals below are fallback seeds for anything not calibrated in the table.
     */
    private static double startFrom(String name) {
        for (common.Datasets.Entry e : common.Datasets.all()) {
            if (e.name.equals(name) && e.calibrated && e.defaultUut > 0) return e.defaultUut;
        }
        switch (name) {
            case "SIGN":      return 0.025;
            case "LEVIATHAN": return 0.060;
            case "FIFA":      return 0.050;
            case "BIBLE":     return 0.025;
            case "BMS1":      return 0.014;
            case "KOSARAK":   return 0.025;
            // Start in the middle of the measured range.
            case "C8T1S5I8N5K":          return 0.02;
            case "TAFENG":               return 0.004;
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
     * cost gradually more; it explodes. An unbounded probe at a threshold a little too low
     * can run for an hour and return nothing, and the search has no way to learn that going
     * lower is the wrong direction.
     *
     * The candidate budget is the primary limit because it is deterministic: the same probe
     * is abandoned at the same point on any machine, so a calibration does not depend on how
     * fast the machine is. The wall-clock budget is a safety net for the cases the counter
     * does not catch, such as an allocation storm.
     */
    // Three billion. The cap exists to catch a probe that has fallen off the cliff below the
    // useful threshold, not to interrupt normal work, and normal work varies by two orders
    // of magnitude between databases: one finishes a useful probe in fourteen million
    // candidates, another needs 1.7 billion at the threshold that yields forty patterns. A
    // cap sized for the first would abort every probe on the second, at every threshold,
    // and report the level unreachable when the real limit is the cap itself.
    private static long maxCandidates = Long.getLong("scan.maxCandidates", 3_000_000_000L);
    private static long budgetMs = 180_000L;

    /**
     * Total budget for one sweep level. The per-probe budget alone does not bound the run:
     * a level can take thirty-odd probes, and if every one of them is abandoned that is
     * still hours. When this is spent the search stops and reports the best threshold it
     * actually measured, or says the level was not reachable. Bounded time, always a line
     * of output.
     */
    private static long levelBudgetMs = 900_000L;

    /**
     * After the band is found, check it in the two places the pattern-count target does
     * NOT see. Disabled with --no-check.
     *
     *  - The threshold actually mined is not UUT but r_l x UUT: after every rescan LUT_dyn
     *    falls back to that floor. BMS1 gives 18 patterns at UUT in 3.5 s; at the floor it
     *    exceeds three billion candidates.
     *
     *  - D_init is not the largest database measured. SC2, SC5 and SC6 run on growing data
     *    and SC3 runs on the whole database. BMS1 cannot be mined on the whole database at
     *    its own calibrated threshold, and a C8T1S5I8N5K band chosen on D_init alone can
     *    drop from 152 patterns to 1.
     */
    private static boolean runChecks = true;
    /**
     * Minimum share of the D_init pattern count that must remain on the whole database.
     *
     * The floor is relative, not absolute: an absolute floor of 10 patterns would fail
     * healthy databases. FIFA at its highest level has exactly 1 pattern on D_init - its
     * calibrated target, {41, 18, 5, 1} - and keeps 1 on the whole database; KOSARAK 5 -> 5
     * and TAFENG 4 -> 3 likewise.
     *
     * What matters is the RELATIVE collapse. On the eight databases the stable bands keep
     * 34% to 129%, while a collapsing band keeps a few percent (2.6% for a C8T1S5I8N5K band
     * chosen on D_init alone). The gap is wide enough that any value inside it separates
     * the two.
     */
    private static final double TRAJECTORY_MIN_RATIO = 0.20;

    /**
     * Budget for one database, all levels together. Without it an overnight run cannot be
     * planned: four levels at their own budget, each able to widen and retry at three times
     * that, means the worst case is sixteen times the level budget rather than four. When
     * this is spent the remaining levels are reported as not attempted, which is a fact
     * worth recording rather than a run that silently overshoots.
     */
    private static long totalBudgetMs = Long.MAX_VALUE;
    private static long runDeadline = Long.MAX_VALUE;

    public static void main(String[] args) throws IOException {
        if (args.length == 0) {
            System.out.println("usage: ThresholdScan <DATASET|ALL> [--target N | --grid a,b,c] "
                    + "[--full] [--budget-sec N] [--level-budget-sec N] "
                    + "[--total-budget-sec N] [--max-candidates N] [--no-check] [--out DIR]");
            System.out.println("databases: SIGN LEVIATHAN FIFA BIBLE BMS1 KOSARAK "
                    + "C8T1S5I8N5K TAFENG");
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
                case "--total-budget-sec": totalBudgetMs = Long.parseLong(args[++i]) * 1000L; break;
                case "--max-candidates": maxCandidates = Long.parseLong(args[++i]); break;
                case "--target":         single = Integer.parseInt(args[++i]); break;
                case "--targets":        break;                 // the default; accepted for clarity
                case "--no-check":       runChecks = false; break;
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
        System.out.printf("budgets: %,d candidates, %d s per probe, %d s per level%s%n",
                maxCandidates, budgetMs / 1000, levelBudgetMs / 1000,
                totalBudgetMs == Long.MAX_VALUE ? ""
                        : String.format(", %d s per database", totalBudgetMs / 1000));

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
        // D_init is Yan et al.'s OD.Num for this database, not half of it. Calibration has
        // to cut the database where the scenarios cut it, or the thresholds are chosen on a
        // different database from the one they are used on.
        currentRl = rl(name);
        int initN = common.Datasets.dInitCount(name, all.size());
        List<Sequence> db = full ? all : new ArrayList<>(all.subList(0, initN));
        double tsu = 0;
        for (Sequence s : db) tsu += s.sequenceUtility;
        String scope = full ? "full" : "D_init";

        System.out.printf("%n=== %s | %s | |D|=%,d | TSU=%,.0f ===%n", name, scope, db.size(), tsu);
        runDeadline = (totalBudgetMs == Long.MAX_VALUE) ? Long.MAX_VALUE
                : System.nanoTime() + totalBudgetMs * 1_000_000L;

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
        Result[] found = searchAll(name, scope, db, tsu, targets, w);
        if (runChecks && targets.length == 4 && !full) {
            checkGates(name, all, db, tsu, found,
                    new String[]{"low", "default", "high", "highest"}, w);
        }
    }

    /**
     * Search every sweep level, from the highest threshold down.
     *
     * Two reasons for that direction. The highest threshold yields the fewest patterns and
     * is by far the cheapest probe, so the run produces its first usable line quickly
     * instead of possibly nothing at all. And each level solved becomes an upper bound for
     * the next: wanting more patterns can only mean a lower threshold, so the bracket
     * narrows as the search goes on rather than starting wide every time.
     */
    private static Result[] searchAll(String name, String scope, List<Sequence> db, double tsu,
                                  int[] targets, PrintWriter w) {
        String[] levels = (targets.length == 4)
                ? new String[]{"low", "default", "high", "highest"} : new String[]{"target"};

        System.out.printf("%8s %10s %11s %12s %7s %9s  %s%n",
                "level", "target", "uut", "patterns", "probes", "ms", "status");

        double hi = HI;                       // upper bound carried down from the level above
        double seed = startFrom(name);
        Result[] found = new Result[targets.length];

        for (int i = targets.length - 1; i >= 0; i--) {
            if (System.nanoTime() >= runDeadline) {
                Result skipped = new Result();
                skipped.status = "not-attempted-database-budget-spent";
                found[i] = skipped;
                System.out.printf("%8s %10d  %s%n", levels[i], targets[i], skipped.status);
                continue;
            }
            Result r = search(db, tsu, targets[i], seed, LO, hi);

            // Widen the budgets once and retry when nothing was measured at all (the budgets,
            // not the data, were the binding constraint), or when the target was missed and
            // budgets were part of the reason. Finishing is not succeeding: a probe at a
            // threshold high enough to be cheap can complete and be wildly off.
            if ((!r.ok() || (!r.onTarget() && r.hitBudget)) && System.nanoTime() < runDeadline) {
                long oldC = maxCandidates, oldB = budgetMs, oldL = levelBudgetMs;
                maxCandidates *= 10;
                budgetMs *= 3;
                levelBudgetMs *= 3;
                System.out.printf("%8s %10d  %s; retrying with %,d candidates, %d s per probe%n",
                        levels[i], targets[i],
                        r.ok() ? "target missed and probes were cut short" : "nothing measured",
                        maxCandidates, budgetMs / 1000);
                Result r2 = search(db, tsu, targets[i], seed, LO, hi);
                r2.probes += r.probes;
                r2.ms += r.ms;
                if (r2.ok()) r2.status += "-after-widening";
                // Keep whichever attempt landed closer to the target.
                boolean keepOld = r.ok() && (!r2.ok()
                        || Math.abs(r.patterns - targets[i]) < Math.abs(r2.patterns - targets[i]));
                if (keepOld) { r.probes += r2.probes; r.ms += r2.ms; }
                else         { r2.probes += r.probes; r2.ms += r.ms; r = r2; }
                maxCandidates = oldC; budgetMs = oldB; levelBudgetMs = oldL;
            }

            found[i] = r;
            // Only a level that actually measured a threshold can bound the next one.
            // ok() is merely "a probe finished", and both no-patterns-within-budget and
            // unreachable-within-budget deliberately set uut to zero to say "no usable
            // threshold". Carrying that down as the upper bound makes every level below
            // probe at threshold zero, which explodes, and the run reports them unreachable
            // after one or two probes while nothing was ever measured.
            if (r.ok() && r.uut > 0) {
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
        return found;
    }

    /**
     * Two gates, run on the whole database because that is the largest scope any scenario
     * uses.
     *
     * The cost gate checks the LOWEST level, whose threshold generates the most candidates.
     * The trajectory gate checks the HIGHEST level, whose threshold is the most likely to
     * collapse to an empty answer. The two worst cases lie at opposite ends, so a few probes
     * cover them instead of one per level.
     */
    private static void checkGates(String name, List<Sequence> all, List<Sequence> dInit,
                                   double tsuInit, Result[] found,
                                   String[] levels, PrintWriter w) {
        double tsuAll = 0;
        for (Sequence s : all) tsuAll += s.sequenceUtility;
        Result lowest = found[0], highest = found[found.length - 1];
        System.out.printf("%n--- band check --- D_init |D|=%,d  |  whole |D|=%,d%n",
                dInit.size(), all.size());

        // Cost gate, on BOTH scopes. A threshold is a RATIO, so the same ratio on the whole
        // database is a higher absolute threshold - TSU is larger - and can be far cheaper
        // than on D_init. TAFENG is an example: 0.002528 exceeds 600 s on D_init and gives
        // 21 patterns in 0.7 s on the whole database. A gate run only on the largest scope
        // would miss that, and SC1 runs on D_init.
        if (lowest.ok() && lowest.uut > 0) {
            double lut = rl(name) * lowest.uut;
            costGate(name, "D_init", dInit, tsuInit, lut, levels[0], w);
            costGate(name, "full",   all,   tsuAll,  lut, levels[0], w);
        }

        // Trajectory gate: pattern count on the whole database relative to D_init.
        if (highest.ok() && highest.uut > 0) {
            Probe p = mine(all, tsuAll, highest.uut);
            int need = Math.max(1, (int) Math.ceil(TRAJECTORY_MIN_RATIO * highest.patterns));
            boolean pass = !p.abandoned && p.patterns >= need;
            System.out.printf("  trajectory  UUT=%.6f (%s)  %d patterns on D_init -> %s, need >= %d  %s%n",
                    highest.uut, levels[levels.length - 1], highest.patterns,
                    p.abandoned ? "not mineable" : p.patterns + " patterns",
                    need, pass ? "PASS" : "FAIL");
            w.printf("%s,full,gate-trajectory,%d,%.6f,%d,1,%d,%s%n", name, need, highest.uut,
                    p.abandoned ? -1 : p.patterns, p.ms, pass ? "ok" : "gate-failed:collapsed");
            w.flush();
        }
        System.out.println("  (FAIL means the band is right on D_init but wrong at a scope the "
                + "scenarios measure: SC1 on D_init, SC2/SC5/SC6 growing, SC3 the whole database)");
    }

    /** One cost check at LUT, on one scope. */
    private static void costGate(String name, String scope, List<Sequence> db, double tsu,
                                 double lut, String level, PrintWriter w) {
        Probe p = mine(db, tsu, lut);
        boolean pass = !p.abandoned;
        System.out.printf("  cost  r_l x UUT=%.6f (%s) on %-6s ->  %-34s %s%n",
                lut, level, scope,
                p.abandoned ? "not mineable: " + p.why
                            : p.patterns + " patterns in " + p.ms + " ms",
                pass ? "PASS" : "FAIL");
        w.printf("%s,%s,gate-cost,,%.6f,%d,1,%d,%s%n", name, scope, lut,
                p.abandoned ? -1 : p.patterns, p.ms, pass ? "ok" : "gate-failed:" + p.why);
        w.flush();
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
        /** At least one probe was cut short by a budget rather than by the data. */
        boolean hitBudget;
        boolean ok() { return patterns >= 0; }
        /** Reached the target, as opposed to merely finishing. */
        boolean onTarget() { return status.startsWith("ok"); }
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
                uut, KAPPA, DELTA0, currentRl, AblationMode.FULL, false);
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
        best.hitBudget |= p.abandoned;
        note(best, u, p, target);

        boolean tooMany = p.abandoned || p.patterns > target;
        for (int i = 0; i < 14 && !bracketed && System.nanoTime() < deadline && System.nanoTime() < runDeadline; i++) {
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
            best.hitBudget |= q.abandoned;
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

        for (int i = 0; i < MAX_STEPS && hi / lo > 1.002 && System.nanoTime() < deadline && System.nanoTime() < runDeadline; i++) {
            double mid = Math.sqrt(lo * hi);
            Probe q = mine(db, tsu, mid);
            probes++; ms += q.ms;
            best.hitBudget |= q.abandoned;
            note(best, mid, q, target);
            // A probe returning nothing is never on target when one pattern was asked for.
            // The band is max(1, target/10), so for target 1 it spans [0, 2] and an empty
            // probe satisfies it; the search then stops at the first threshold high enough
            // to return nothing, and the guard below throws that same result away as
            // useless. Accepting only non-empty probes keeps the bisection going into the
            // interval that actually holds the answer.
            if (!q.abandoned && q.patterns > 0
                    && Math.abs(q.patterns - target) <= Math.max(1, target * 0.10)) break;
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
