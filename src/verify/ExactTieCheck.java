package verify;

import algorithms.PreHUSPMAdaptiveAlgorithm.PreHUSPMAdaptiveAlgo;
import common.DatasetLoader;
import common.Sequence;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Patterns whose utility equals the threshold, with decimal prices: the maintained result must be the
 * result of a fresh mine after every insertion, deletion and modification.
 *
 *   java -cp build verify.ExactTieCheck [TRIALS SEED]
 *
 * Part 1, cases worked by hand, each just below, at and just above the threshold, with prices of 0.01
 * per unit and UUT = 0.5, read through DatasetLoader as every experiment reads its data:
 *   deletion      A(1), A(6), B(6); delete the first: A and B have 0.06 of 0.12 (a case of an
 *                 independent review); below and above with A(5) and A(7) in the second sequence
 *   insertion     A(6), C(4); insert B(2): A has 0.06 of 0.12, its utility unchanged and the threshold
 *                 raised to it; below and above with C(5) and C(3)
 *   modification  A(6), C(5); replace C(5) by C(6): A has 0.06 of 0.12; below and above with C(7) and C(5)
 * Part 2, random databases of single-item and two-item sequences over four items priced 0.01 to 0.09,
 * quantities 1 to 9, UUT in {0.5, 0.25, 0.2, 0.1}, ten random operations each; after every operation
 * the maintained set and utilities are compared with a fresh mine, and the operations at which a
 * pattern of the fresh mine has a utility exactly equal to UUT * TU (in decimal) are counted, so that
 * the check states how many ties it saw.
 *
 * Exit 1 on any difference, or when part 2 saw no tie.
 */
public final class ExactTieCheck {

    private ExactTieCheck() {}

    private static int compared = 0, mismatches = 0;

    public static void main(String[] args) throws IOException {
        int trials = args.length > 0 ? Integer.parseInt(args[0]) : 500;
        long seed = args.length > 1 ? Long.parseLong(args[1]) : 1;
        Path dir = Files.createTempDirectory("exact-tie");
        System.out.println("Part 1, cases by hand (prices 0.01, UUT 0.5)");
        int[][] del = {{1, 5, 6}, {1, 6, 6}, {1, 7, 6}};       // A(x0), A(x1), B(x2); delete the first
        String[] where = {"below", "at", "above"};
        for (int k = 0; k < 3; k++) {
            List<Sequence> all = load(dir, new String[]{"1[" + del[k][0] + "]", "1[" + del[k][1] + "]", "2[" + del[k][2] + "]"},
                    "1:0.01\n2:0.01\n");
            caseDeletion("deletion " + where[k], all.subList(0, 3), List.of(all.get(0)));
        }
        int[] c = {5, 4, 3};                                     // A(6), C(c); insert B(2)
        for (int k = 0; k < 3; k++) {
            List<Sequence> all = load(dir, new String[]{"1[6]", "3[" + c[k] + "]", "2[2]"}, "1:0.01\n2:0.01\n3:0.01\n");
            caseInsertion("insertion " + where[k], all.subList(0, 2), all.subList(2, 3));
        }
        int[] m = {7, 6, 5};                                     // A(6), C(5); replace C(5) by C(m)
        for (int k = 0; k < 3; k++) {
            List<Sequence> all = load(dir, new String[]{"1[6]", "3[5]", "3[" + m[k] + "]"}, "1:0.01\n3:0.01\n");
            caseModification("modification " + where[k], all.subList(0, 2), List.of(all.get(1)), all.subList(2, 3));
        }
        int byHand = mismatches;

        System.out.printf("Part 2, random databases: %d trials, seed %d%n", trials, seed);
        Random rnd = new Random(seed);
        double[] uuts = {0.5, 0.25, 0.2, 0.1};
        int ops = 0, ties = 0;
        for (int t = 0; t < trials; t++) {
            StringBuilder eui = new StringBuilder();
            for (int i = 1; i <= 4; i++) eui.append(i).append(":0.0").append(1 + rnd.nextInt(9)).append('\n');
            String[] lines = new String[30];
            for (int s = 0; s < lines.length; s++) {
                int a = 1 + rnd.nextInt(4);
                StringBuilder sb = new StringBuilder(a + "[" + (1 + rnd.nextInt(9)) + "]");
                if (rnd.nextInt(3) == 0) {
                    int b = 1 + rnd.nextInt(4);
                    if (b != a) sb.append(rnd.nextBoolean() ? " -1 " : " ").append(b).append("[").append(1 + rnd.nextInt(9)).append("]");
                }
                lines[s] = normalise(sb.toString());
            }
            List<Sequence> all = load(dir, lines, eui.toString());
            double uut = uuts[rnd.nextInt(uuts.length)];
            List<Sequence> cur = new ArrayList<>(all.subList(0, 10));
            List<Sequence> pool = new ArrayList<>(all.subList(10, all.size()));
            PreHUSPMAdaptiveAlgo inc = miner(uut);
            inc.runInitialMining(new ArrayList<>(cur), total(cur));
            for (int op = 0; op < 10; op++) {
                int kind = rnd.nextInt(3);
                List<Sequence> after = new ArrayList<>(cur);
                if (kind == 0 && !pool.isEmpty()) {
                    List<Sequence> ins = List.of(pool.remove(0));
                    after.addAll(ins);
                    inc.runIncrementalMining(ins, new ArrayList<>(after), total(after));
                } else if (kind == 1 && cur.size() > 2) {
                    List<Sequence> d = List.of(cur.get(rnd.nextInt(cur.size())));
                    after.removeAll(d);
                    inc.runDeletion(d, new ArrayList<>(after), total(after));
                } else if (cur.size() > 2 && !pool.isEmpty()) {
                    List<Sequence> old = List.of(cur.get(rnd.nextInt(cur.size())));
                    List<Sequence> nw = List.of(pool.remove(0));
                    after.removeAll(old);
                    after.addAll(nw);
                    inc.runModification(old, nw, new ArrayList<>(after), total(after));
                } else {
                    continue;
                }
                cur = after;
                Map<String, Double> fresh = freshMine(uut, cur);
                ops++;
                BigDecimal thr = new BigDecimal(Double.toString(uut)).multiply(BigDecimal.valueOf((long) total(cur)));
                boolean tie = false;
                for (double u : fresh.values()) tie |= BigDecimal.valueOf((long) u).compareTo(thr) == 0;
                if (tie) ties++;
                compare("trial " + t + " op " + op, inc.exportLargeAsStrings(), fresh);
            }
        }
        System.out.printf("cases by hand: %d of 9 mismatching; random: %d operations compared, %d with a pattern exactly "
                + "at the threshold, %d mismatching%n", byHand, ops, ties, mismatches - byHand);
        System.out.printf("total: %d comparisons, %d mismatching%n", compared, mismatches);
        if (mismatches > 0 || ties == 0) System.exit(1);
    }

    /** "3[2] 1[5]" in one element -> items in increasing id order, as DatasetLoader requires. */
    private static String normalise(String s) {
        String[] els = s.split(" -1 ");
        StringBuilder out = new StringBuilder();
        for (String el : els) {
            String[] toks = el.trim().split("\\s+");
            java.util.Arrays.sort(toks, (x, y) -> Integer.compare(Integer.parseInt(x.substring(0, x.indexOf('['))),
                    Integer.parseInt(y.substring(0, y.indexOf('[')))));
            out.append(String.join(" ", toks)).append(" -1 ");
        }
        return out.append("-2").toString();
    }

    private static List<Sequence> load(Path dir, String[] lines, String eui) throws IOException {
        Path seq = dir.resolve("tie_seq.txt"), e = dir.resolve("tie_eui.txt");
        StringBuilder sb = new StringBuilder();
        for (String l : lines) sb.append(l.endsWith("-2") ? l : l + " -1 -2").append('\n');
        Files.writeString(seq, sb.toString());
        Files.writeString(e, eui);
        return DatasetLoader.loadDataset(seq.toString(), e.toString());
    }

    private static PreHUSPMAdaptiveAlgo miner(double uut) {
        return new PreHUSPMAdaptiveAlgo(uut, 0.5, 1e-4, 0.8, PreHUSPMAdaptiveAlgo.AblationMode.FULL, false);
    }

    private static double total(List<Sequence> db) {
        double t = 0;
        for (Sequence s : db) t += s.sequenceUtility;
        return t;
    }

    private static Map<String, Double> freshMine(double uut, List<Sequence> db) {
        PreHUSPMAdaptiveAlgo f = miner(uut);
        f.runInitialMining(new ArrayList<>(db), total(db));
        return f.exportLargeAsStrings();
    }

    private static void caseDeletion(String name, List<Sequence> d0, List<Sequence> del) {
        List<Sequence> after = new ArrayList<>(d0);
        after.removeAll(del);
        PreHUSPMAdaptiveAlgo inc = miner(0.5);
        inc.runInitialMining(new ArrayList<>(d0), total(d0));
        inc.runDeletion(del, new ArrayList<>(after), total(after));
        report(name, inc, after);
    }

    private static void caseInsertion(String name, List<Sequence> d0, List<Sequence> ins) {
        List<Sequence> after = new ArrayList<>(d0);
        after.addAll(ins);
        PreHUSPMAdaptiveAlgo inc = miner(0.5);
        inc.runInitialMining(new ArrayList<>(d0), total(d0));
        inc.runIncrementalMining(ins, new ArrayList<>(after), total(after));
        report(name, inc, after);
    }

    private static void caseModification(String name, List<Sequence> d0, List<Sequence> old, List<Sequence> nw) {
        List<Sequence> after = new ArrayList<>(d0);
        after.removeAll(old);
        after.addAll(nw);
        PreHUSPMAdaptiveAlgo inc = miner(0.5);
        inc.runInitialMining(new ArrayList<>(d0), total(d0));
        inc.runModification(old, nw, new ArrayList<>(after), total(after));
        report(name, inc, after);
    }

    private static void report(String name, PreHUSPMAdaptiveAlgo inc, List<Sequence> after) {
        Map<String, Double> got = inc.exportLargeAsStrings(), fresh = freshMine(0.5, after);
        boolean ok = compare(name, got, fresh);
        System.out.printf("  %-22s rescans %d  maintained %s  fresh %s  %s%n", name, inc.rescanCount, got, fresh,
                ok ? "ok" : "MISMATCH");
    }

    private static boolean compare(String where, Map<String, Double> got, Map<String, Double> want) {
        compared++;
        boolean ok = got.equals(want);
        if (!ok) {
            mismatches++;
            if (mismatches <= 10) System.out.println("  MISMATCH " + where + ": maintained " + got + ", fresh " + want);
        }
        return ok;
    }
}
