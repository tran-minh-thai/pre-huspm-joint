package verify;

import algorithms.PreHUSPMAdaptiveAlgorithm.PreHUSPMAdaptiveAlgo;
import common.LogReplay;
import common.MixedPlan;
import common.QItem;
import common.QItemset;
import common.Sequence;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * What a result kept by insertions alone gets wrong on a replayed log (SC11).
 *
 *   java -Xmx16g -Xss64m -cp build verify.StaleResult DIR NAME UUT OUT_DIR
 *
 * Two databases are replayed from the same D_init day by day: the database of the log
 * (NAME_ops.txt, cancellations applied) and the database a method for insertion only maintains
 * (NAME_ops_nocancel.txt, every cancellation after D_init ignored, so it only inserts sequences and
 * appends invoices). After D_init and after every day each is mined from scratch at UUT times its
 * own total utility, which is the exact result a maintenance method returns for it. The patterns
 * of the second that the first does not hold are reported but below the threshold of the log's
 * database (false); the patterns of the first that the second lacks are missed.
 *
 * Every utility is also computed from its definition, u(X, s) the largest sum over the
 * occurrences of X in s, independently of the miner: every pattern the miner returns on the log's
 * database must have that utility, every false pattern must lie below the log's threshold and
 * every missed one below the other database's. The run stops on a disagreement.
 *
 * Events of the log's result: a pattern leaves it on day d when it was in the result of the day
 * before and is not in the result of d; it enters on d when the reverse holds. A leaving whose
 * utility fell is caused by a removal (a cancellation or a deletion); an entering whose utility did
 * not rise is caused by the fall of the threshold, i.e. of the total utility. The three leavings
 * with the largest fall of utility and the three enterings with the largest fall of the threshold
 * are written as examples, with the pattern, the day, and the utility and threshold before and
 * after.
 *
 * For the patterns both results hold, the utility the second reports is compared with the utility
 * in the log's database: the largest and the mean overstatement, in percent, per day.
 *
 * Writes OUT_DIR/stale_by_day.csv, stale_events.csv, stale_examples.csv and stale_summary.txt.
 */
public final class StaleResult {

    private StaleResult() {}

    private static double globalOverMax;
    private static String globalOverDay;

    public static void main(String[] args) throws IOException {
        if (args.length == 1 && "--self-test".equals(args[0])) { System.exit(selfTest()); }
        if (args.length != 4) {
            System.err.println("usage: StaleResult DIR NAME UUT OUT_DIR");
            System.exit(2);
        }
        String dir = args[0], name = args[1];
        double uut = Double.parseDouble(args[2]);
        Path out = Paths.get(args[3]);
        Files.createDirectories(out);

        LogReplay.Log log = LogReplay.load(dir, name, "_ops.txt");
        LogReplay.Log nc = LogReplay.load(dir, name, "_ops_nocancel.txt");
        for (MixedPlan.Op op : nc.plan)
            if (op.kind == MixedPlan.Kind.DEL) throw new IllegalStateException("the log without cancellations deletes");

        TreeMap<String, List<MixedPlan.Op>> byDayLog = byDay(log), byDayNc = byDay(nc);
        TreeSet<String> days = new TreeSet<>(byDayLog.keySet());
        days.addAll(byDayNc.keySet());

        List<Sequence> d = new ArrayList<>(log.dInit), e = new ArrayList<>(nc.dInit);
        int checked = 0;

        PrintWriter byDay = new PrintWriter(Files.newBufferedWriter(out.resolve("stale_by_day.csv"), StandardCharsets.UTF_8));
        byDay.println("day,sequences,total_utility,threshold,sequences_without_cancellations,"
                + "total_utility_without_cancellations,threshold_without_cancellations,result,result_without_cancellations,"
                + "false_patterns,missed_patterns,wrong_patterns,common_patterns,utility_overstated_max_pct,"
                + "utility_overstated_mean_pct");
        PrintWriter events = new PrintWriter(Files.newBufferedWriter(out.resolve("stale_events.csv"), StandardCharsets.UTF_8));
        events.println("day,event,pattern,utility_before,utility_after,threshold_before,threshold_after");

        Map<String, Double> prev = null;
        double prevTu = 0, prevThr = 0;
        List<Sequence> prevDb = null;
        List<String[]> leave = new ArrayList<>(), enter = new ArrayList<>();
        int maxWrong = -1;
        globalOverMax = 0; globalOverDay = "";
        String maxWrongDay = "";
        String lastLine = "";
        List<String> allDays = new ArrayList<>();
        allDays.add("D_init");
        allDays.addAll(days);
        for (String day : allDays) {
            if (!"D_init".equals(day)) {
                prevDb = new ArrayList<>(d);
                for (MixedPlan.Op op : byDayLog.getOrDefault(day, List.of())) apply(d, op);
                for (MixedPlan.Op op : byDayNc.getOrDefault(day, List.of())) apply(e, op);
            }
            double tu = total(d), tuE = total(e);
            double thr = uut * tu, thrE = uut * tuE;
            Map<String, Double> res = mine(d, uut, tu), resE = mine(e, uut, tuE);

            // the miner's utilities against the definition, and the two sides of every difference
            for (Map.Entry<String, Double> x : res.entrySet()) {
                double u = utility(parse(x.getKey()), d);
                if (Math.abs(u - x.getValue()) > 1e-6 * Math.max(1, u))
                    throw new IllegalStateException(day + ": " + x.getKey() + " miner " + x.getValue() + ", definition " + u);
                if (u < thr - 1e-9) throw new IllegalStateException(day + ": " + x.getKey() + " below the threshold");
                checked++;
            }
            Set<String> fls = new TreeSet<>(resE.keySet()); fls.removeAll(res.keySet());
            Set<String> mis = new TreeSet<>(res.keySet()); mis.removeAll(resE.keySet());
            for (String x : fls) {
                if (utility(parse(x), d) >= thr - 1e-9)
                    throw new IllegalStateException(day + ": false pattern " + x + " reaches the threshold");
                checked++;
            }
            for (String x : mis) {
                if (utility(parse(x), e) >= thrE - 1e-9)
                    throw new IllegalStateException(day + ": missed pattern " + x + " reaches the other threshold");
                checked++;
            }
            int wrong = fls.size() + mis.size();
            // the patterns both report: by how much the result kept without the cancellations
            // overstates their utility, relative to the utility in the log's database
            double overMax = 0, overSum = 0;
            int common = 0;
            for (Map.Entry<String, Double> x : res.entrySet()) {
                Double ue = resE.get(x.getKey());
                if (ue == null) continue;
                double pct = 100.0 * (ue - x.getValue()) / x.getValue();
                overMax = Math.max(overMax, pct);
                overSum += pct;
                common++;
            }
            if (common > 0 && overMax > globalOverMax) { globalOverMax = overMax; globalOverDay = day; }
            if (wrong > maxWrong) { maxWrong = wrong; maxWrongDay = day; }
            lastLine = String.format(Locale.ROOT, "%s,%d,%.2f,%.4f,%d,%.2f,%.4f,%d,%d,%d,%d,%d,%d,%.4f,%.4f", day, d.size(),
                    tu, thr, e.size(), tuE, thrE, res.size(), resE.size(), fls.size(), mis.size(), wrong, common,
                    overMax, common > 0 ? overSum / common : 0.0);
            byDay.println(lastLine);

            if (prev != null) {
                for (Map.Entry<String, Double> x : prev.entrySet()) {
                    if (res.containsKey(x.getKey())) continue;
                    double after = utility(parse(x.getKey()), d);
                    String[] row = {day, "leave", x.getKey(), num(x.getValue()), num(after), num(prevThr), num(thr)};
                    events.println(String.join(",", row));
                    if (after < x.getValue() - 1e-9) leave.add(row);
                }
                for (Map.Entry<String, Double> x : res.entrySet()) {
                    if (prev.containsKey(x.getKey())) continue;
                    double before = utility(parse(x.getKey()), prevDb);
                    String[] row = {day, "enter", x.getKey(), num(before), num(x.getValue()), num(prevThr), num(thr)};
                    events.println(String.join(",", row));
                    if (x.getValue() <= before + 1e-9 && thr < prevThr) enter.add(row);
                }
            }
            prev = res; prevTu = tu; prevThr = thr;
        }
        byDay.close();
        events.close();

        leave.sort(Comparator.comparingDouble((String[] r) -> -(Double.parseDouble(r[3]) - Double.parseDouble(r[4])))
                .thenComparing(r -> r[0]).thenComparing(r -> r[2]));
        enter.sort(Comparator.comparingDouble((String[] r) -> -(Double.parseDouble(r[5]) - Double.parseDouble(r[6])))
                .thenComparing(r -> r[0]).thenComparing(r -> r[2]));
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out.resolve("stale_examples.csv"), StandardCharsets.UTF_8))) {
            w.println("kind,rank,day,pattern,utility_before,utility_after,threshold_before,threshold_after");
            for (int i = 0; i < Math.min(3, leave.size()); i++)
                w.println("leave by removal," + (i + 1) + "," + String.join(",", leave.get(i)[0], leave.get(i)[2],
                        leave.get(i)[3], leave.get(i)[4], leave.get(i)[5], leave.get(i)[6]));
            for (int i = 0; i < Math.min(3, enter.size()); i++)
                w.println("enter by threshold," + (i + 1) + "," + String.join(",", enter.get(i)[0], enter.get(i)[2],
                        enter.get(i)[3], enter.get(i)[4], enter.get(i)[5], enter.get(i)[6]));
        }
        String[] last = lastLine.split(",");
        String summary = String.format(Locale.ROOT,
                "database=%s uut=%s days=%d (with D_init %d states)%n"
                        + "end of log: result %s, false %s, missed %s%n"
                        + "largest number of wrong patterns: %d on %s%n"
                        + "leavings with a fall of utility: %d; enterings by a fall of the threshold: %d%n"
                        + "end of log: utility of the common patterns overstated by at most %s%% (mean %s%%)%n"
                        + "largest overstatement over the log: %.4f%% on %s%n"
                        + "total utility without the cancellations at the end: %s against %s%n"
                        + "utilities checked against the definition: %d%n",
                name, args[2], days.size(), allDays.size(), last[7], last[9], last[10], maxWrong, maxWrongDay,
                leave.size(), enter.size(), last[13], last[14], globalOverMax, globalOverDay, last[5], last[2], checked);
        Files.writeString(out.resolve("stale_summary.txt"), summary, StandardCharsets.UTF_8);
        System.out.print(summary);
        if (checked == 0) {
            System.out.println("nothing was compared");
            System.exit(1);
        }
    }

    /** u(X, s) on a sequence worked by hand: <(1:2 2:3)(1:5)(2:1)>, items 1 and 2. */
    static int selfTest() {
        Sequence s = new Sequence(0);
        QItemset a = new QItemset(); a.items.add(new QItem(1, 2)); a.items.add(new QItem(2, 3));
        QItemset b = new QItemset(); b.items.add(new QItem(1, 5));
        QItemset c = new QItemset(); c.items.add(new QItem(2, 1));
        s.itemsets.add(a); s.itemsets.add(b); s.itemsets.add(c);
        Object[][] cases = {{"1 2", 6.0}, {"(1 2)", 5.0}, {"1 1", 7.0}, {"2 1", 8.0}, {"(1 2) 2", 6.0},
                {"3", 0.0}, {"1 1 1", 0.0}, {"2", 3.0}, {"(1 2) 1 2", 11.0}};
        int bad = 0;
        for (Object[] k : cases) {
            double got = utility(parse((String) k[0]), s);
            boolean ok = Math.abs(got - (Double) k[1]) < 1e-12;
            if (!ok) bad++;
            System.out.printf("SELF-TEST u(<%s>) = %s, expected %s: %s%n", k[0], got, k[1], ok ? "ok" : "FAILED");
        }
        System.out.printf("self-test: %d/%d cases as expected%n", cases.length - bad, cases.length);
        return bad == 0 ? 0 : 1;
    }

    private static String num(double v) { return String.format(Locale.ROOT, "%.4f", v); }

    private static TreeMap<String, List<MixedPlan.Op>> byDay(LogReplay.Log log) {
        TreeMap<String, List<MixedPlan.Op>> m = new TreeMap<>();
        for (int i = 0; i < log.plan.size(); i++) m.computeIfAbsent(log.days.get(i), k -> new ArrayList<>()).add(log.plan.get(i));
        return m;
    }

    private static void apply(List<Sequence> db, MixedPlan.Op op) {
        switch (op.kind) {
            case INS: db.addAll(op.a); break;
            case DEL: removeAll(db, op.a); break;
            default: removeAll(db, op.a); db.addAll(op.b);
        }
    }

    private static void removeAll(List<Sequence> db, List<Sequence> gone) {
        Set<Sequence> g = Collections.newSetFromMap(new IdentityHashMap<>());
        g.addAll(gone);
        int before = db.size();
        db.removeIf(g::contains);
        if (db.size() != before - gone.size()) throw new IllegalStateException("removed " + (before - db.size()) + " of " + gone.size());
    }

    private static double total(List<Sequence> db) {
        double t = 0;
        for (Sequence s : db) for (QItemset is : s.itemsets) for (QItem q : is.items) t += q.utility;
        return t;
    }

    /** The exact result at uut: the method's miner mining from scratch with its cutoff at the threshold. */
    private static Map<String, Double> mine(List<Sequence> db, double uut, double tu) {
        PreHUSPMAdaptiveAlgo a = new PreHUSPMAdaptiveAlgo(uut, 0.5, 1e-4, 1.0, PreHUSPMAdaptiveAlgo.AblationMode.FULL, false);
        a.runRemine(new ArrayList<>(db), tu);
        return a.exportLargeAsStrings();
    }

    /** "12 (3 4) 5" -> [[12], [3, 4], [5]]. */
    static int[][] parse(String p) {
        List<int[]> els = new ArrayList<>();
        String s = p.trim();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == ' ') { i++; continue; }
            if (c == '(') {
                int j = s.indexOf(')', i);
                String[] t = s.substring(i + 1, j).trim().split("\\s+");
                int[] el = new int[t.length];
                for (int k = 0; k < t.length; k++) el[k] = Integer.parseInt(t[k]);
                els.add(el);
                i = j + 1;
            } else {
                int j = i;
                while (j < s.length() && s.charAt(j) != ' ') j++;
                els.add(new int[]{Integer.parseInt(s.substring(i, j))});
                i = j;
            }
        }
        return els.toArray(new int[0][]);
    }

    /** u(X, D): the sum over the sequences of the largest utility of an occurrence of X. */
    static double utility(int[][] x, List<Sequence> db) {
        double u = 0;
        for (Sequence s : db) u += utility(x, s);
        return u;
    }

    /** u(X, s): over the occurrences of X in s (element i of X contained in element j_i of s, the
     *  j_i strictly increasing), the largest sum of the utilities of the matched items; 0 when X does
     *  not occur. Dynamic programming over (element of X, element of s). */
    static double utility(int[][] x, Sequence s) {
        int n = s.itemsets.size(), k = x.length;
        if (k == 0 || n < k) return 0;
        double neg = Double.NEGATIVE_INFINITY;
        double[] best = new double[n];       // best[j]: largest sum matching x[0..i] with x[i] in element j
        for (int i = 0; i < k; i++) {
            double[] next = new double[n];
            double prefix = (i == 0) ? 0 : neg;  // best over elements before j for x[0..i-1]
            for (int j = 0; j < n; j++) {
                double v = contained(x[i], s.itemsets.get(j));
                next[j] = (v < 0 || prefix == neg) ? neg : prefix + v;
                if (i > 0) prefix = Math.max(prefix, best[j]);
            }
            best = next;
        }
        double m = neg;
        for (double v : best) m = Math.max(m, v);
        return m == neg ? 0 : m;
    }

    /** The utility of the items of el in the itemset, or -1 when one of them is missing. */
    private static double contained(int[] el, QItemset is) {
        double v = 0;
        for (int item : el) {
            double found = -1;
            for (QItem q : is.items) if (q.id == item) { found = q.utility; break; }
            if (found < 0) return -1;
            v += found;
        }
        return v;
    }
}
