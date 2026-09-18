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
            // Re-chosen rather than inherited. The submitted counts {12, 6, 5, 5} collapse
            // on this release: 6 and 5 both land at 0.030, so three of the four levels share
            // one threshold and the sweep has two operating points wearing four labels. The
            // counts below were measured on D_init and are the widest spread the database
            // allows - 0.019 already exceeds three billion candidates, so twelve is the
            // ceiling.
            {"KOSARAK",   new int[]{12, 9, 7, 5}},

            // Added by the pinned release with no submitted counts to inherit, so the ladder
            // is chosen rather than reproduced. C8T1S5I8N5K is the one database here that
            // reaches within-event extension.
            //
            // Raised from {300, 150, 80, 40}, which are the counts the FIRST calibration
            // aimed at and hit. They were wrong: thresholds that give 300 patterns on D_init
            // give 1 on the whole database, so SC2, SC5 and SC6 would have compared five
            // algorithms against an answer of one pattern. The trajectory gate now refuses
            // that, and these counts are what the replacement band measured - every level
            // keeps at least 23% of its D_init count when the database grows.
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
     * The literals below are the submitted defaults, kept for anything not in the table.
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
            // No inherited default for these; start in the middle of the measured range.
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
     * cost gradually more; it explodes. One probe at a threshold a little too low ran for
     * an hour on the measurement machine and returned nothing, because nothing could stop
     * it and the search had no way to learn that going lower was the wrong direction.
     *
     * The candidate budget is the primary limit because it is deterministic: the same probe
     * is abandoned at the same point on any machine, so a calibration does not depend on how
     * fast the machine is. The wall-clock budget is a safety net for the cases the counter
     * does not catch, such as an allocation storm.
     */
    // Three billion, not three hundred million. The cap exists to catch a probe that has
    // fallen off the cliff below the useful threshold, not to interrupt normal work, and
    // normal work varies by two orders of magnitude between databases: one finishes a
    // useful probe in fourteen million candidates, another needs 1.7 billion at the
    // threshold that yields forty patterns. A cap set from the first aborts every probe
    // on the second, at every threshold, so nothing is ever measured and the tool reports
    // the level unreachable while the real limit is a number it chose itself.
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
     * Budget for one database, all levels together. Without it an overnight run cannot be
     * planned: four levels at their own budget, each able to widen and retry at three times
     * that, means the worst case is sixteen times the level budget rather than four. When
     * this is spent the remaining levels are reported as not attempted, which is a fact
     * worth recording rather than a run that silently overshot into the morning.
     */
    /**
     * Sau khi tim duoc dai, kiem no o hai cho ma dich so mau KHONG nhin thay. Tat bang
     * --no-check.
     *
     * Dich so mau tren D_init la tieu chi duy nhat tu truoc den nay, va no bo sot hai thu,
     * ca hai deu da gay thiet hai that:
     *
     *  - Nguong khai thac THAT khong phai UUT ma la r_l x UUT. Sau moi rescan LUT_dyn roi ve
     *    san do. BMS1 tai UUT cho 18 mau trong 3,5 giay; tai san no vuot ba ty ung vien.
     *
     *  - D_init khong phai CSDL lon nhat duoc do. SC2, SC5, SC6 chay tren du lieu lon dan va
     *    SC3 chay thang tren toan bo. BMS1 tai chinh nguong da hieu chinh cua no khong khai
     *    thac noi tren toan bo CSDL, va C8T1S5I8N5K tut tu 152 mau xuong con 1.
     *
     * Hai bo do deu qua tieu chi cu ma khong ai thay gi, cho den khi mot chien dich do dem
     * bao cao chung bang OOM va het gio.
     */
    private static boolean runChecks = true;
    /**
     * Ti le toi thieu con lai tren toan bo CSDL so voi D_init.
     *
     * Tuyet doi chu KHONG duoc, va lan dau viet cua nay dat san tuyet doi 10 mau nen no bao
     * truot ba bo hoan toan lanh: FIFA o muc cao nhat co DUNG 1 mau tren D_init - chinh la
     * dich da hieu chinh cua no, {41, 18, 5, 1} - va giu nguyen 1 mau tren toan bo. Doi no
     * con 10 la doi mot thu no chua bao gio co. KOSARAK 5 -> 5 va TAFENG 4 -> 3 cung vay.
     *
     * Thu can do la SUP DO TUONG DOI. Do duoc tren tam bo: bay bo on dinh nam o 34% den 129%,
     * con C8T1S5I8N5K tut xuong 2,6%. Khoang trong giua 34% va 2,6% rong den muc nguong dat o
     * dau trong do cung tach dung.
     */
    private static final double TRAJECTORY_MIN_RATIO = 0.20;

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
     *
     * The earlier version ran the levels in paper order, which meant it began with the
     * lowest threshold: the most patterns, the most expensive probe, and on BMS1 an hour
     * with nothing to show.
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

            // Nothing measured at all means the budgets were the binding constraint, not
            // the data. Guessing flags and re-running by hand is what a tool should spare
            // its user, so widen once and try again before giving up.
            // Widen when the search did not reach the target AND budgets were part of the
            // reason. Finishing is not succeeding: a probe at a threshold high enough to be
            // cheap can complete and be wildly off, which reads as a result while telling
            // you nothing.
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
     * Hai cua kiem, chay tren toan bo CSDL vi do moi la pham vi lon nhat cac kich ban dung.
     *
     * Cua chi phi kiem muc THAP NHAT, vi nguong thap nhat sinh nhieu ung vien nhat. Cua quy
     * dao kiem muc CAO NHAT, vi nguong cao nhat de sup ve rong nhat. Hai truong hop xau nhat
     * nguoc chieu nhau nen chi ton hai probe chu khong phai tam.
     */
    private static void checkGates(String name, List<Sequence> all, List<Sequence> dInit,
                                   double tsuInit, Result[] found,
                                   String[] levels, PrintWriter w) {
        double tsuAll = 0;
        for (Sequence s : all) tsuAll += s.sequenceUtility;
        Result lowest = found[0], highest = found[found.length - 1];
        System.out.printf("%n--- kiem tra dai --- D_init |D|=%,d  |  toan bo |D|=%,d%n",
                dInit.size(), all.size());

        // Cua chi phi, o CA HAI pham vi. Nguong la mot TI LE, nen cung mot ti le tren toan bo
        // CSDL la mot nguong tuyet doi cao hon - TSU lon hon - va co the re hon han tren
        // D_init. TAFENG la vi du: 0.002528 vuot 600 giay tren D_init va cho 21 mau trong 0,7
        // giay tren toan bo. Mot cua chi chay o pham vi lon nhat se bo lot dieu do, ma SC1
        // chay dung tren D_init.
        if (lowest.ok() && lowest.uut > 0) {
            double lut = RL * lowest.uut;
            costGate(name, "D_init", dInit, tsuInit, lut, levels[0], w);
            costGate(name, "full",   all,   tsuAll,  lut, levels[0], w);
        }

        // Cua quy dao: so mau tren toan bo so voi tren D_init, thuan ti le.
        if (highest.ok() && highest.uut > 0) {
            Probe p = mine(all, tsuAll, highest.uut);
            int need = Math.max(1, (int) Math.ceil(TRAJECTORY_MIN_RATIO * highest.patterns));
            boolean pass = !p.abandoned && p.patterns >= need;
            System.out.printf("  quy dao  UUT=%.6f (%s)  %d mau tren D_init -> %s, can >= %d  %s%n",
                    highest.uut, levels[levels.length - 1], highest.patterns,
                    p.abandoned ? "khong khai thac noi" : p.patterns + " mau",
                    need, pass ? "DAT" : "KHONG DAT");
            w.printf("%s,full,gate-trajectory,%d,%.6f,%d,1,%d,%s%n", name, need, highest.uut,
                    p.abandoned ? -1 : p.patterns, p.ms, pass ? "ok" : "gate-failed:collapsed");
            w.flush();
        }
        System.out.println("  (khong dat nghia la dai dung tren D_init nhung sai o pham vi ma "
                + "kich ban that su do: SC1 tren D_init, SC2/SC5/SC6 lon dan, SC3 toan bo)");
    }

    /** Mot lan kiem chi phi tai LUT, tren mot pham vi. */
    private static void costGate(String name, String scope, List<Sequence> db, double tsu,
                                 double lut, String level, PrintWriter w) {
        Probe p = mine(db, tsu, lut);
        boolean pass = !p.abandoned;
        System.out.printf("  chi phi  r_l x UUT=%.6f (%s) tren %-6s ->  %-34s %s%n",
                lut, level, scope,
                p.abandoned ? "khong khai thac noi: " + p.why
                            : p.patterns + " mau trong " + p.ms + " ms",
                pass ? "DAT" : "KHONG DAT");
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
