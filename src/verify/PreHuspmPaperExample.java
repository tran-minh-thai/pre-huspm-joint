package verify;

import algorithms.PreHUSPMAlgorithm.PreHUSPMAlgo;
import common.Pattern;
import common.Sequence;
import common.DatasetLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Pre-HUSPM implementation on the worked example of its paper (Yan et al., J. Supercomputing
 * 2025, Section 5: Tables 5 and 9, profits of Table 6, Su = 35%, Sl = 25%).
 *
 * The paper lists the items of an element in no fixed order; the loader takes them in increasing
 * order, as elements are sets. Utilities, the rescan decision and the HUSPs do not depend on that
 * order and are checked against the paper's tables. The bound kept for each pattern, the SWU
 * computed on the projected database, does depend on it: the paper's values (Tables 7 and 8) are
 * reproduced only with its written order, so the bound is checked against values computed
 * independently on the ordered elements (a separate enumeration of the occurrences, outside this
 * repository's code). Plain SWU (Definition 8 of the paper) would give 115 for <(a)>, which matches
 * none of the paper's tables.
 *
 *   java -cp build verify.PreHuspmPaperExample
 *
 * Exits 1 on any mismatch and prints the number of values compared.
 */
public final class PreHuspmPaperExample {

    // items a..e as 1..5, profits 3, 2, 4, 2, 1 (Table 6)
    private static final String EUI = "1:3\n2:2\n3:4\n4:2\n5:1\n";
    private static final String D = String.join("\n",
            "1[3] -1 2[3] -1 1[2] 3[3] 5[3] -1 -2",
            "5[2] -1 3[3] -1 3[2] 4[2] -1 -2",
            "3[3] -1 4[2] 5[3] -1 1[3] -1 -2",
            "5[4] -1 4[2] -1 1[3] 2[3] -1 -2",
            "4[3] -1 1[2] 2[2] 3[3] -1 -2") + "\n";
    private static final String DELTA = "5[1] -1 3[2] 5[1] -1 -2\n4[1] -1 1[1] -1 2[1] -1 -2\n";

    private static int compared = 0, failed = 0;

    private static void check(String what, double got, double want) {
        compared++;
        if (Math.abs(got - want) > 1e-9) {
            failed++;
            System.out.printf("MISMATCH %s: %.3f, expected %.3f%n", what, got, want);
        }
    }

    private static String name(Pattern p) {
        StringBuilder b = new StringBuilder("<");
        for (List<Integer> e : p.elements) {
            b.append('(');
            for (int i = 0; i < e.size(); i++) b.append(i > 0 ? "," : "").append("?abcde".charAt(e.get(i)));
            b.append(')');
        }
        return b.append('>').toString();
    }

    private static Map<String, double[]> buffer(PreHUSPMAlgo a) {
        Map<String, double[]> m = new LinkedHashMap<>();
        for (Map.Entry<Pattern, double[]> e : a.bufferSnapshot().entrySet()) m.put(name(e.getKey()), e.getValue());
        return m;
    }

    public static void main(String[] args) throws IOException {
        Path dir = Files.createTempDirectory("prehuspm-example");
        Files.write(dir.resolve("EXD_eui.txt"), EUI.getBytes(StandardCharsets.UTF_8));
        Files.write(dir.resolve("EXD_seq.txt"), D.getBytes(StandardCharsets.UTF_8));
        Files.write(dir.resolve("EXN_eui.txt"), EUI.getBytes(StandardCharsets.UTF_8));
        Files.write(dir.resolve("EXN_seq.txt"), DELTA.getBytes(StandardCharsets.UTF_8));
        List<Sequence> d0 = DatasetLoader.loadDataset(dir.resolve("EXD_seq.txt").toString(), dir.resolve("EXD_eui.txt").toString());
        List<Sequence> d1 = DatasetLoader.loadDataset(dir.resolve("EXN_seq.txt").toString(), dir.resolve("EXN_eui.txt").toString());
        for (Sequence s : d1) s.id += 100;
        double tD = 0, td = 0;
        for (Sequence s : d0) tD += s.sequenceUtility;
        for (Sequence s : d1) td += s.sequenceUtility;
        check("TSU of D (Section 5)", tD, 141);
        check("TSU of d (Section 5)", td, 17);

        for (PreHUSPMAlgo.Variant v : PreHUSPMAlgo.Variant.values()) {
            PreHUSPMAlgo a = new PreHUSPMAlgo(0.35, 0.25 / 0.35, false, v);
            a.runInitialMining(new ArrayList<>(d0), tD);
            Map<String, double[]> b = buffer(a);
            // Tables 7 and 8: utilities su (order independent)
            String[][] su = {{"<(a)>", "33"}, {"<(c)>", "48"}, {"<(c)(d)>", "32"}, {"<(d)>", "18"}, {"<(d)(a)>", "38"},
                    {"<(e)>", "12"}, {"<(a,c)>", "36"}, {"<(a)(b)>", "15"}, {"<(a)(b)(a)>", "21"},
                    {"<(a)(b)(a,c)>", "33"}, {"<(a)(b)(a,c,e)>", "36"}, {"<(b)>", "16"}};
            for (String[] e : su) {
                double[] x = b.get(e[0]);
                check(v + " su " + e[0] + " in D (Tables 7, 8)", x == null ? -1 : x[1], Double.parseDouble(e[1]));
            }
            // the projected SWU on ordered elements, computed independently
            String[][] bound = {{"<(a)>", "82"}, {"<(c)>", "79"}, {"<(d)>", "67"}, {"<(e)>", "64"}, {"<(b)>", "49"},
                    {"<(a,c)>", "39"}, {"<(a)(b)>", "36"}, {"<(d)(a)>", "60"}, {"<(c)(d)>", "44"}};
            for (String[] e : bound) {
                double[] x = b.get(e[0]);
                check(v + " projected SWU " + e[0] + " in D", x == null ? -1 : x[0], Double.parseDouble(e[1]));
            }
            List<Sequence> u = new ArrayList<>(d0);
            u.addAll(d1);
            int before = a.rescanCount;
            a.runIncrementalMining(new ArrayList<>(d1), u, tD + td);
            check(v + " rescans after d (Section 5: buf + TSU_d = 17 <= f)", a.rescanCount - before, 0);
            Map<String, double[]> c = buffer(a);
            // Tables 11, 12 and 14: updated utilities
            String[][] su2 = {{"<(a)>", "36"}, {"<(c)>", "56"}, {"<(d)>", "20"}, {"<(e)>", "13"}, {"<(b)>", "18"},
                    {"<(d)(a)>", "43"}, {"<(c)(d)>", "32"}};
            for (String[] e : su2) {
                double[] x = c.get(e[0]);
                check(v + " su " + e[0] + " in D + d (Tables 11, 12, 14)", x == null ? -1 : x[1], Double.parseDouble(e[1]));
            }
            // the HUSPs of D + d: <(c)> alone (Section 5, Table 15)
            int husps = 0, cIsHusp = 0;
            for (Map.Entry<Pattern, Double> e : a.largeMap.entrySet()) {
                husps++;
                if (name(e.getKey()).equals("<(c)>")) cIsHusp = 1;
            }
            check(v + " number of HUSPs of D + d", husps, 1);
            check(v + " <(c)> is the HUSP", cIsHusp, 1);
        }
        System.out.printf("Pre-HUSPM on its paper's example: %d values compared, %d mismatching%n", compared, failed);
        System.exit(failed == 0 ? 0 : 1);
    }
}
