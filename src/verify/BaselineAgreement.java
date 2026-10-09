package verify;

import algorithms.HUSPSPAlgorithm;
import algorithms.HUSPULLAlgorithm;
import algorithms.USpanAlgorithm;
import common.DatasetLoader;
import common.Pattern;
import common.Sequence;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Acceptance check for the static baselines: every static algorithm must return EXACTLY
 * THE SAME set of HUSPs on the same data at the same threshold.
 *
 * This is the acceptance condition for HUSP-SP, which is reimplemented independently from
 * its pseudocode: its pattern set is compared with USpan's, not only its pattern count.
 *
 * Thresholds and D_init come from common.Datasets, the table the runner measures at, so
 * agreement is checked at the operating point actually used. Agreement holds at any
 * threshold, so a wrong threshold would raise no error; pass a threshold by hand only to
 * explore.
 *
 * Run:
 *   java -Xmx48g -Xss64m -cp build verify.BaselineAgreement SIGN
 *   java -Xmx48g -Xss64m -cp build verify.BaselineAgreement SIGN 0.08337   (another sweep level)
 *   java -Xmx48g -Xss64m -cp build verify.BaselineAgreement ALL
 *
 * A miner that runs out of heap is reported as not finished and is not a mismatch: it
 * returned nothing to compare. The summary gives how many comparisons were made, so a run in
 * which most miners did not finish cannot pass as agreement.
 *
 * Exits 0 if every comparison made agrees, 1 on any mismatch.
 */
public final class BaselineAgreement {

    private static final String DIR = "datasets";

    public static void main(String[] args) throws IOException {
        String target = args.length > 0 ? args[0] : "ALL";
        Double uutArg = (args.length > 1) ? Double.parseDouble(args[1]) : null;
        boolean halfOnly = System.getProperty("half", "true").equalsIgnoreCase("true");

        if (!"ALL".equalsIgnoreCase(target) && common.Datasets.byName(target) == null) {
            System.out.println("no database named " + target + "; choose from "
                    + common.Datasets.names());
            System.exit(2);
        }

        int bad = 0;
        for (common.Datasets.Entry e : common.Datasets.all()) {
            if (!"ALL".equalsIgnoreCase(target) && !e.name.equalsIgnoreCase(target)) continue;
            bad += check(e.name, uutArg != null ? uutArg : e.defaultUut, halfOnly);
        }
        System.out.println();
        System.out.println("Comparisons made: " + compared + ", mismatching: " + bad
                + ", miners that did not finish: " + unfinished.size()
                + (unfinished.isEmpty() ? "" : " " + unfinished));
        System.out.println(bad == 0
                ? "RESULT: every comparison made returns the same set of HUSPs."
                : "RESULT: " + bad + " mismatching pairs - fix before reporting any number.");
        if (bad > 0) System.exit(1);
    }

    private static int check(String name, double uut, boolean halfOnly) throws IOException {
        List<Sequence> all = DatasetLoader.loadDataset(
                Paths.get(DIR, name + "_seq.txt").toString(),
                Paths.get(DIR, name + "_eui.txt").toString());
        // By default use exactly the D_init of the database - the sequence count from
        // Datasets, not half - to match SC1, since the thresholds are calibrated on that
        // scope. -Dhalf=false runs on the whole database.
        int dInit = common.Datasets.dInitCount(name, all.size());
        List<Sequence> db = halfOnly ? new ArrayList<>(all.subList(0, dInit)) : all;
        double tsu = 0;
        for (Sequence s : db) tsu += s.sequenceUtility;
        final double minUtil = tsu * uut, tsuAll = tsu;

        System.out.printf("%n=== %s | |D|=%d | UUT=%.4f | absolute threshold=%.1f ===%n",
                name, db.size(), uut, minUtil);

        Map<String, Double> refSet = guarded(name, "USpan", () -> {
            USpanAlgorithm us = new USpanAlgorithm(minUtil);
            us.run(copy(db));
            System.out.printf("  %-10s HUSP=%-7d cand=%-12d explored=%d%n",
                    "USpan", us.largeMap.size(), us.candidateCount, us.exploredNodes);
            return asStrings(us.largeMap);
        });
        if (refSet == null) return 0;

        int bad = 0;
        bad += compare("HUSP-SP", refSet, guarded(name, "HUSP-SP", () -> runHuspSp(copy(db), minUtil)));
        bad += compare("HUSP-ULL", refSet, guarded(name, "HUSP-ULL", () -> runHuspUll(copy(db), minUtil)));
        // Implementations that share no mining code with USpan, so a fault common to the
        // three static baselines above would still show up here.
        bad += compare("IncUSP-Miner+", refSet, guarded(name, "IncUSP-Miner+", () -> runIncUsp(copy(db), uut, tsuAll)));
        bad += compare("Pre-HUSPM", refSet, guarded(name, "Pre-HUSPM", () -> runPreHuspm(copy(db), uut, tsuAll, name)));
        bad += compare("Adaptive", refSet, guarded(name, "Adaptive", () -> runAdaptive(copy(db), uut, tsuAll, name)));
        return bad;
    }

    private static int compared = 0;
    private static final List<String> unfinished = new ArrayList<>();

    /** Runs one miner; null when it ran out of heap, which is recorded and not compared. */
    private static Map<String, Double> guarded(String db, String who,
                                               java.util.function.Supplier<Map<String, Double>> run) {
        try {
            return run.get();
        } catch (OutOfMemoryError oom) {
            System.out.printf("  %-10s did not finish: heap exhausted (-Xmx%dm)%n",
                    who, Runtime.getRuntime().maxMemory() >> 20);
            unfinished.add(db + "/" + who);
            System.gc();
            return null;
        }
    }

    private static Map<String, Double> runIncUsp(List<Sequence> db, double uut, double tsu) {
        algorithms.IncUSPMinerPlusAlgorithm a = new algorithms.IncUSPMinerPlusAlgorithm(uut);
        a.runInitialMining(db, tsu);
        System.out.printf("  %-10s HUSP=%-7d cand=%-12d explored=%d%n",
                "IncUSP-Miner+", a.largeMap.size(), a.candidateCount, a.exploredNodes);
        return asStrings(a.husps());
    }

    private static Map<String, Double> runPreHuspm(List<Sequence> db, double uut, double tsu, String name) {
        algorithms.PreHUSPMAlgorithm.PreHUSPMAlgo a = new algorithms.PreHUSPMAlgorithm.PreHUSPMAlgo(
                uut, common.Datasets.slMinRatio(name), false);
        a.runInitialMining(db, tsu);
        System.out.printf("  %-10s HUSP=%-7d cand=%-12d explored=%d%n",
                "Pre-HUSPM", a.largeMap.size(), a.candidateCount, a.exploredNodes);
        return asStrings(a.largeMap);
    }

    private static Map<String, Double> runAdaptive(List<Sequence> db, double uut, double tsu, String name) {
        algorithms.PreHUSPMAdaptiveAlgorithm.PreHUSPMAdaptiveAlgo a =
                new algorithms.PreHUSPMAdaptiveAlgorithm.PreHUSPMAdaptiveAlgo(uut, 0.5, 1e-4,
                        common.Datasets.slMinRatio(name),
                        algorithms.PreHUSPMAdaptiveAlgorithm.PreHUSPMAdaptiveAlgo.AblationMode.FULL, false);
        a.runInitialMining(db, tsu);
        Map<String, Double> got = a.exportLargeAsStrings();
        System.out.printf("  %-10s HUSP=%-7d cand=%-12d explored=%d%n",
                "Adaptive", got.size(), a.candidateCount, a.exploredNodes);
        return got;
    }

    private static Map<String, Double> runHuspSp(List<Sequence> db, double minUtil) {
        HUSPSPAlgorithm a = new HUSPSPAlgorithm(minUtil);
        a.run(db);
        System.out.printf("  %-10s HUSP=%-7d cand=%-12d explored=%-10d swu=%d iip=%d ep=%d peu=%d%n",
                "HUSP-SP", a.largeMap.size(), a.candidateCount, a.exploredNodes,
                a.prunedSWU, a.prunedIIP, a.prunedEP, a.prunedPEU);
        return asStrings(a.largeMap);
    }

    private static Map<String, Double> runHuspUll(List<Sequence> db, double minUtil) {
        HUSPULLAlgorithm a = new HUSPULLAlgorithm(minUtil);
        a.run(db);
        System.out.printf("  %-10s HUSP=%-7d cand=%-12d explored=%d%n",
                "HUSP-ULL", a.largeMap.size(), a.candidateCount, a.exploredNodes);
        return asStrings(a.largeMap);
    }

    private static int compare(String who, Map<String, Double> ref, Map<String, Double> got) {
        if (got == null) return 0;
        compared++;
        PatternSetDiff d = PatternSetDiff.of(ref, got);
        if (d.isClean()) {
            System.out.printf("    %-10s matches USpan exactly%n", who);
            return 0;
        }
        System.out.printf("    %-10s MISMATCH: %s%n", who, d);
        return 1;
    }

    private static Map<String, Double> asStrings(Map<Pattern, Double> m) {
        Map<String, Double> out = new HashMap<>(m.size() * 2);
        for (Map.Entry<Pattern, Double> e : m.entrySet()) {
            out.put(e.getKey().toIntuitiveString(), e.getValue());
        }
        return out;
    }

    /** The algorithms overwrite internal fields of Sequence, so each run gets its own list copy. */
    private static List<Sequence> copy(List<Sequence> src) {
        return new ArrayList<>(src);
    }
}
