package experiments;

import algorithms.PreHUSPMAdaptiveAlgorithm.PreHUSPMAdaptiveAlgo;
import algorithms.PreHUSPMAdaptiveAlgorithm.PreHUSPMAdaptiveAlgo.AblationMode;
import common.DatasetLoader;
import common.Datasets;
import common.Sequence;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Settles two settings of the measurement campaign by measurement, on the measurement
 * machine, under rules fixed before the measurement. Started by campaign.ps1 -Calibrate,
 * which checks the pinned JDK and a clean src/ first:
 *
 *   java -Xmx4g -cp build -Dcalib.childHeap=48g experiments.CampaignCalibration all OUT_DIR DATASET_DIR
 *
 * Rule 1, item removal in the proposal's miner (adapt.iip = once | fixpoint; off is measured
 * as the reference and is not a candidate).
 *   Two workloads per database, each mined by the proposal in a fresh JVM: (a) D_init at the
 *   four SC1 thresholds in sweep order, as an SC1 arm does; (b) the whole database at its
 *   default threshold, the operation a rescan repeats, for every database measured on its
 *   whole scope. Three runs per variant, the order of the variants rotating between runs. The
 *   score of a variant on a database is the median time of (a) plus the median time of (b);
 *   its overall score is the sum over the databases. The candidate with the smaller overall
 *   score is chosen; if the two differ by less than 2 percent, "once" is chosen. All variants
 *   must return the same pattern and pre-large counts on every workload, otherwise the
 *   calibration stops.
 *
 * Rule 2, the floor ratio of KOSARAK.
 *   With the variant chosen by rule 1, the proposal mines (a) KOSARAK's D_init at its lowest
 *   SC1 threshold and (b) the whole of KOSARAK at its default threshold, both with the floor
 *   ratio 0.8, each in a fresh JVM with the campaign's heap and a limit of 120 minutes.
 *   r_l = 0.8 is chosen when both finish; otherwise r_l stays 0.85.
 *
 * Writes OUT_DIR/calibration.csv (every run, written as it goes) and OUT_DIR/decision.txt.
 */
public final class CampaignCalibration {

    private CampaignCalibration() {}

    // -Dcalib.only=A,B restricts rule 1 to those databases, for testing the tool only; the
    // decision it then writes covers those databases alone and says so.
    static final String[] DATABASES = System.getProperty("calib.only") != null
            ? System.getProperty("calib.only").split(",")
            : new String[]{"SIGN", "LEVIATHAN", "TAFENG", "BIBLE", "BMS1", "C8T1S5I8N5K", "KOSARAK", "FIFA"};
    static final String[] VARIANTS = {"off", "once", "fixpoint"};
    static final int RUNS = 3;
    static final double TIE = 0.02;
    static final long LIMIT_MINUTES = 120;
    static final String CHILD_HEAP = System.getProperty("calib.childHeap", "48g");

    public static void main(String[] a) throws Exception {
        if (a.length >= 1 && a[0].equals("child")) {
            try { child(a); } catch (OutOfMemoryError oom) { System.out.println("RESULT OOM,,,,,,"); }
            return;
        }
        if (a.length < 2 || !a[0].equals("all")) {
            System.err.println("usage: all OUT_DIR [DATASET_DIR]");
            System.exit(2);
        }
        Path out = Paths.get(a[1]);
        String dir = a.length > 2 ? a[2] : "datasets/";
        if (!dir.endsWith("/")) dir += "/";
        Files.createDirectories(out);
        List<String> rows = new ArrayList<>();
        rows.add("part,database,variant,run,scope,floor_ratio,status,seconds,patterns,prelarge,explored,candidates,removed_items");

        // ---- rule 1
        // times[db][variant][scope] -> seconds of each run
        Map<String, Map<String, Map<String, List<Double>>>> times = new LinkedHashMap<>();
        for (String db : DATABASES) {
            Datasets.Entry e = entry(db);
            List<String> scopes = new ArrayList<>(List.of("sc1"));
            if (e.fullScopeOk) scopes.add("whole@" + e.defaultUut);
            times.put(db, new LinkedHashMap<>());
            for (String scope : scopes) {
                Set<String> counts = new HashSet<>();
                for (int run = 0; run < RUNS; run++) {
                    for (int k = 0; k < VARIANTS.length; k++) {
                        String v = VARIANTS[(k + run) % VARIANTS.length];
                        double fr = Datasets.slMinRatio(db);
                        String[] r = spawn(dir, db, v, scope, fr);
                        rows.add("rule1," + db + "," + v + "," + run + "," + scope + "," + fr + "," + String.join(",", r));
                        flush(out, rows);
                        if (!r[0].equals("OK")) throw new IllegalStateException(db + " " + scope + " " + v + " did not finish: " + r[0]);
                        times.get(db).computeIfAbsent(v, x -> new LinkedHashMap<>())
                                .computeIfAbsent(scope, x -> new ArrayList<>()).add(Double.parseDouble(r[1]));
                        counts.add(r[2] + "/" + r[3]);
                    }
                }
                if (counts.size() != 1)
                    throw new IllegalStateException(db + " " + scope + ": pattern/pre-large counts differ: " + counts);
            }
        }
        double[] total = new double[VARIANTS.length];
        StringBuilder per = new StringBuilder();
        for (String db : DATABASES) {
            per.append(String.format(Locale.ROOT, "  %-12s", db));
            for (int i = 0; i < VARIANTS.length; i++) {
                double score = 0;
                for (List<Double> runs : times.get(db).get(VARIANTS[i]).values()) score += median(runs);
                total[i] += score;
                per.append(String.format(Locale.ROOT, "  %s %.3f s", VARIANTS[i], score));
            }
            per.append('\n');
        }
        double once = total[1], fix = total[2];
        double rel = Math.abs(once - fix) / Math.max(once, fix);
        String chosen = (rel < TIE || once <= fix) ? "once" : "fixpoint";

        // ---- rule 2
        Datasets.Entry kos = entry("KOSARAK");
        double lowest = Arrays.stream(kos.sweep).min().orElseThrow();
        String[] ra = spawn(dir, "KOSARAK", chosen, "dinit@" + lowest, 0.8);
        rows.add("rule2,KOSARAK," + chosen + ",0,dinit@" + lowest + ",0.8," + String.join(",", ra));
        flush(out, rows);
        String[] rb = spawn(dir, "KOSARAK", chosen, "whole@" + kos.defaultUut, 0.8);
        rows.add("rule2,KOSARAK," + chosen + ",0,whole@" + kos.defaultUut + ",0.8," + String.join(",", rb));
        flush(out, rows);
        boolean kosOk = ra[0].equals("OK") && rb[0].equals("OK");
        String kosRl = kosOk ? "0.8" : "0.85";

        String decision = (System.getProperty("calib.only") != null
                        ? "TEST RUN on " + String.join(",", DATABASES) + " only; not a calibration.\n" : "")
                + "adapt.iip=" + chosen + "\n"
                + "kosarak.r_l=" + kosRl + "\n\n"
                + String.format(Locale.ROOT, "Rule 1: sum over databases of the per-database score: off %.3f s, once %.3f s, "
                        + "fixpoint %.3f s; once vs fixpoint relative difference %.4f (tie below %.2f).%n",
                        total[0], once, fix, rel, TIE)
                + per
                + "Rule 2: KOSARAK at r_l 0.8 with adapt.iip=" + chosen + ": D_init at " + lowest + " "
                + ra[0] + " (" + ra[1] + " s), whole at " + kos.defaultUut + " " + rb[0] + " (" + rb[1] + " s).\n"
                + "Every run, with its JVM and operating system: calibration.csv and run.log beside this file.\n";
        Files.write(out.resolve("decision.txt"), decision.getBytes(StandardCharsets.UTF_8));
        System.out.print(decision);
    }

    /** Runs one measurement in a fresh JVM; returns {status, seconds, patterns, prelarge, explored, candidates, removed}. */
    static String[] spawn(String dir, String db, String variant, String scope, double floorRatio) throws Exception {
        String javaBin = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        List<String> cmd = new ArrayList<>(List.of(javaBin, "-Xmx" + CHILD_HEAP, "-Xss64m",
                "-Dadapt.iip=" + variant, "-cp", System.getProperty("java.class.path"),
                CampaignCalibration.class.getName(), "child", dir, db, scope, Double.toString(floorRatio)));
        System.out.println("  " + db + " " + scope + " adapt.iip=" + variant + " r_l=" + floorRatio);
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String[] result = {null};
        Thread drain = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    System.out.println("    " + line);
                    if (line.startsWith("RESULT ")) result[0] = line.substring(7);
                }
            } catch (IOException ignored) { }
        });
        drain.start();
        boolean done = p.waitFor(LIMIT_MINUTES, java.util.concurrent.TimeUnit.MINUTES);
        if (!done) { p.destroyForcibly(); p.waitFor(); }
        drain.join();
        if (!done) return new String[]{"TIMEOUT", "", "", "", "", "", ""};
        if (result[0] == null) return new String[]{"FAILED(exit " + p.exitValue() + ")", "", "", "", "", "", ""};
        return result[0].split(",", -1);
    }

    /** Child: one mine (or the four SC1 thresholds) in this JVM; prints one RESULT line. */
    static void child(String[] a) throws Exception {
        String dir = a[1], db = a[2], scope = a[3];
        double floorRatio = Double.parseDouble(a[4]);
        System.out.println("Platform: os=" + System.getProperty("os.name") + " " + System.getProperty("os.version")
                + " | java=" + System.getProperty("java.runtime.version") + " (" + System.getProperty("java.vendor") + ")"
                + " | adapt.iip=" + System.getProperty("adapt.iip"));
        Datasets.Entry e = entry(db);
        List<Sequence> all = DatasetLoader.loadDataset(dir + db + "_seq.txt", dir + db + "_eui.txt");
        List<double[]> jobs = new ArrayList<>();   // {threshold, useWhole}
        if (scope.equals("sc1")) for (double t : e.sweep) jobs.add(new double[]{t, 0});
        else if (scope.startsWith("dinit@")) jobs.add(new double[]{Double.parseDouble(scope.substring(6)), 0});
        else if (scope.startsWith("whole@")) jobs.add(new double[]{Double.parseDouble(scope.substring(6)), 1});
        else throw new IllegalArgumentException(scope);
        long patterns = 0, prelarge = 0, explored = 0, candidates = 0, removed = 0;
        long t0 = System.nanoTime();
        for (double[] j : jobs) {
            List<Sequence> db0 = (j[1] == 1) ? all : all.subList(0, Datasets.dInitCount(db, all.size()));
            double tu = 0;
            for (Sequence s : db0) tu += s.sequenceUtility;
            // kappa 0.5 and delta0 1e-4 are the runner's defaults, as in every campaign arm
            PreHUSPMAdaptiveAlgo alg = new PreHUSPMAdaptiveAlgo(j[0], 0.5, 1e-4, floorRatio, AblationMode.FULL, false);
            alg.runInitialMining(new ArrayList<>(db0), tu);
            patterns += alg.patternCount;
            prelarge += alg.prelargeCount;
            explored += alg.exploredNodes;
            candidates += alg.candidateCount;
            removed += alg.removedItems;
        }
        double secs = (System.nanoTime() - t0) / 1e9;
        System.out.println(String.format(Locale.ROOT, "RESULT OK,%.3f,%d,%d,%d,%d,%d",
                secs, patterns, prelarge, explored, candidates, removed));
    }

    static Datasets.Entry entry(String name) {
        for (Datasets.Entry x : Datasets.all()) if (x.name.equals(name)) return x;
        throw new IllegalArgumentException("no database named " + name);
    }

    static double median(List<Double> v) {
        List<Double> s = new ArrayList<>(v);
        Collections.sort(s);
        int n = s.size();
        return n % 2 == 1 ? s.get(n / 2) : (s.get(n / 2 - 1) + s.get(n / 2)) / 2;
    }

    static void flush(Path out, List<String> rows) throws IOException {
        Files.write(out.resolve("calibration.csv"), (String.join("\n", rows) + "\n").getBytes(StandardCharsets.UTF_8));
    }
}
