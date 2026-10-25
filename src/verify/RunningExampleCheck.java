package verify;

import algorithms.PreHUSPMAdaptiveAlgorithm.PreHUSPMAdaptiveAlgo;
import common.DatasetLoader;
import common.Sequence;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The proposal on the running example of the manuscript: external utilities a..h = 5, 3, 2, 4, 1, 2,
 * D = {S1, S2} with TU(D) = 68, S5 = <(g:2)(h:1)> inserted later, UUT = 0.25, r_l = 0.8, so the
 * mining cutoff is 0.2 * 68 = 13.6.
 *
 *   java -cp build verify.RunningExampleCheck
 *
 * Checks, each against the value the manuscript states:
 *   1. the accumulators of the extensions of <(a)> (their prefix extension utility bound): the
 *      miner records, under Pre-HUSPM's tracking, the bound of every node whose bound reaches the
 *      cutoff, and with no item removed at <(a)> that bound is the accumulator of the extension;
 *      the eight kept extensions at 13.6, and <(a)(h)> with 11 at a cutoff below it;
 *   2. the mine of D with the proposal's settings: <(a)> 20, <(c)> 14, <(a)(g)> 16, budget 3.4;
 *   3. Example 1: inserting S5 gives Phi = 3 and no rescan, <(a)(g)> discarded, <(a)> and <(c)>
 *      unchanged; inserting S5 again gives Phi = 6 and a rescan;
 *   4. Example 2 with w = 1: a batch of S5 twice rescans at lambda = UUT with cutoff 19, then a
 *      batch of S5 once rescans back at LUT_min with cutoff 16.
 * Exits 1 on any mismatch and prints the number of values compared.
 */
public final class RunningExampleCheck {

    // a b c d g h as 1..6
    private static final String EUI = "1:5\n2:3\n3:2\n4:4\n5:1\n6:2\n";
    private static final String D = "1[2] 2[1] -1 3[5] -1 1[3] 4[2] -1 5[1] -1 -2\n"
            + "1[1] 3[2] -1 2[2] 6[3] -1 -2\n";
    private static final String S5 = "5[2] -1 6[1] -1 -2\n";

    private static int compared = 0, failed = 0;

    private static void check(String what, double got, double want) {
        compared++;
        if (Math.abs(got - want) > 1e-9) {
            failed++;
            System.out.printf("MISMATCH %s: %s, expected %s%n", what, got, want);
        }
    }

    private static Path dir;
    private static int nextId = 100;

    private static List<Sequence> load(String name, String seqs) throws IOException {
        Files.write(dir.resolve(name + "_eui.txt"), EUI.getBytes(StandardCharsets.UTF_8));
        Files.write(dir.resolve(name + "_seq.txt"), seqs.getBytes(StandardCharsets.UTF_8));
        return DatasetLoader.loadDataset(dir.resolve(name + "_seq.txt").toString(), dir.resolve(name + "_eui.txt").toString());
    }

    /** A fresh copy of S5 with an id of its own, as a later insertion of the same sequence. */
    private static Sequence s5() throws IOException {
        Sequence s = load("S5_" + nextId, S5).get(0);
        s.id = nextId++;
        return s;
    }

    private static double tu(List<Sequence> d) {
        double t = 0;
        for (Sequence s : d) t += s.sequenceUtility;
        return t;
    }

    private static PreHUSPMAdaptiveAlgo proposal(double lowerRatio) {
        return new PreHUSPMAdaptiveAlgo(0.25, 0.5, 1e-4, lowerRatio, PreHUSPMAdaptiveAlgo.AblationMode.FULL, false);
    }

    private static double tracked(PreHUSPMAdaptiveAlgo a, String pattern) {
        double[] v = a.exportTrackedWithBounds().get(pattern);
        return v == null ? -1 : v[0];
    }

    public static void main(String[] args) throws IOException {
        dir = Files.createTempDirectory("running-example");
        List<Sequence> d = load("D", D);
        check("TU(D)", tu(d), 68);

        // 1. accumulators of the extensions of <(a)> (Table of the walk-through)
        String[][] kept = {{"(1 2)", "47"}, {"(1 4)", "24"}, {"(1 3)", "21"}, {"1 3", "44"}, {"1 1", "34"},
                {"1 4", "19"}, {"1 2", "17"}, {"1 5", "16"}};
        PreHUSPMAdaptiveAlgo b = proposal(0.8);
        b.tracking = PreHUSPMAdaptiveAlgo.Tracking.PREHUSPM;
        b.runInitialMining(new ArrayList<>(d), 68);
        Map<String, double[]> bounds = b.exportTrackedWithBounds();
        for (String[] e : kept) {
            double[] v = bounds.get(e[0]);
            check("accumulator of <(a)> extended to " + e[0] + " at 13.6", v == null ? -1 : v[1], Double.parseDouble(e[1]));
        }
        check("<(a)(h)> has no list at 13.6 (accumulator below the cutoff)", bounds.containsKey("1 6") ? 1 : 0, 0);
        PreHUSPMAdaptiveAlgo low = proposal(0.6);   // cutoff 0.15 * 68 = 10.2
        low.tracking = PreHUSPMAdaptiveAlgo.Tracking.PREHUSPM;
        low.runInitialMining(new ArrayList<>(d), 68);
        double[] ah = low.exportTrackedWithBounds().get("1 6");
        check("accumulator of <(a)(h)> at a cutoff of 10.2", ah == null ? -1 : ah[1], 11);

        // 2. the mine of D with the proposal's settings
        PreHUSPMAdaptiveAlgo p = proposal(0.8);
        p.runInitialMining(new ArrayList<>(d), 68);
        check("mining cutoff L_ref", p.lutDynValue, 13.6);
        check("u(<(a)>)", tracked(p, "1"), 20);
        check("u(<(c)>)", tracked(p, "3"), 14);
        check("u(<(a)(g)>)", tracked(p, "1 5"), 16);

        // 3. Example 1: insert S5 once, then again
        List<Sequence> cur = new ArrayList<>(d);
        Sequence first = s5();
        cur.add(first);
        int r0 = p.rescanCount;
        p.runIncrementalMining(new ArrayList<>(List.of(first)), new ArrayList<>(cur), tu(cur));
        check("Example 1, first S5: Phi", p.iauMaxAtDecision, 3);
        check("Example 1, first S5: budget beta", p.sftValue, 3.4);
        check("Example 1, first S5: rescans", p.rescanCount - r0, 0);
        check("Example 1, first S5: <(a)(g)> discarded", tracked(p, "1 5"), -1);
        check("Example 1, first S5: u(<(a)>)", tracked(p, "1"), 20);
        check("Example 1, first S5: u(<(c)>)", tracked(p, "3"), 14);
        Sequence second = s5();
        cur.add(second);
        p.runIncrementalMining(new ArrayList<>(List.of(second)), new ArrayList<>(cur), tu(cur));
        check("Example 1, second S5: Phi", p.iauMaxAtDecision, 6);
        check("Example 1, second S5: rescans", p.rescanCount - r0, 1);

        // 4. Example 2 with w = 1
        PreHUSPMAdaptiveAlgo s = proposal(0.8);
        s.floorWindow = 1;
        s.runInitialMining(new ArrayList<>(d), 68);
        List<Sequence> c2 = new ArrayList<>(d);
        List<Sequence> d1 = List.of(s5(), s5());
        c2.addAll(d1);
        s.runIncrementalMining(new ArrayList<>(d1), new ArrayList<>(c2), tu(c2));
        check("Example 2, d1 = S5 twice: Phi", s.iauMaxAtDecision, 6);
        check("Example 2, d1: rescans", s.rescanCount, 1);
        check("Example 2, d1: rescan at lambda = UUT, cutoff", s.lutDynValue, 19);
        List<Sequence> d2 = List.of(s5());
        c2.addAll(d2);
        s.runIncrementalMining(new ArrayList<>(d2), new ArrayList<>(c2), tu(c2));
        check("Example 2, d2 = S5 once: Phi", s.iauMaxAtDecision, 3);
        check("Example 2, d2: rescans", s.rescanCount, 2);
        check("Example 2, d2: rescan back at LUT_min, cutoff", s.lutDynValue, 16);

        System.out.printf("Running example: %d values compared, %d mismatching%n", compared, failed);
        System.exit(failed == 0 && compared > 0 ? 0 : 1);
    }
}
