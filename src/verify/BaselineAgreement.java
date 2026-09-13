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
 * Nghiem thu cho baseline tinh: moi thuat toan tinh phai tra ve DUNG CUNG MOT
 * tap HUSP tren cung du lieu va cung nguong.
 *
 * Day la dieu kien nghiem thu cua HUSP-SP: no duoc cai lai doc lap tu pseudocode
 * nen phai chung minh la cai dung, va cach chung minh la doi chieu tap mau voi
 * USpan chu khong phai chi doi chieu SO LUONG mau.
 *
 * Chay:
 *   java -Xmx24g -Xss64m -cp build verify.BaselineAgreement SIGN 0.025
 *   java -Xmx24g -Xss64m -cp build verify.BaselineAgreement ALL
 *
 * Thoat 0 neu moi thuat toan khop nhau, 1 neu co sai lech.
 */
public final class BaselineAgreement {

    private static final String DIR = "datasets";

    private static final String[][] DEFAULTS = {
            {"SIGN", "0.025"}, {"LEVIATHAN", "0.060"}, {"FIFA", "0.050"},
            {"BIBLE", "0.025"}, {"BMS1", "0.014"}, {"KOSARAK", "0.030"},
    };

    public static void main(String[] args) throws IOException {
        String target = args.length > 0 ? args[0] : "ALL";
        Double uutArg = (args.length > 1) ? Double.parseDouble(args[1]) : null;
        boolean halfOnly = System.getProperty("half", "true").equalsIgnoreCase("true");

        int bad = 0;
        for (String[] d : DEFAULTS) {
            if (!"ALL".equalsIgnoreCase(target) && !d[0].equalsIgnoreCase(target)) continue;
            bad += check(d[0], uutArg != null ? uutArg : Double.parseDouble(d[1]), halfOnly);
        }
        System.out.println();
        System.out.println(bad == 0
                ? "KET LUAN: moi thuat toan tinh tra ve cung mot tap HUSP."
                : "KET LUAN: " + bad + " cap khong khop - PHAI sua truoc khi bao cao so nao.");
        if (bad > 0) System.exit(1);
    }

    private static int check(String name, double uut, boolean halfOnly) throws IOException {
        List<Sequence> all = DatasetLoader.loadDataset(
                Paths.get(DIR, name + "_seq.txt").toString(),
                Paths.get(DIR, name + "_eui.txt").toString());
        // Mac dinh dung D_init (50% dau) cho khop voi SC1; -Dhalf=false de chay toan bo.
        List<Sequence> db = halfOnly ? new ArrayList<>(all.subList(0, all.size() / 2)) : all;
        double tsu = 0;
        for (Sequence s : db) tsu += s.sequenceUtility;
        double minUtil = tsu * uut;

        System.out.printf("%n=== %s | |D|=%d | UUT=%.4f | nguong tuyet doi=%.1f ===%n",
                name, db.size(), uut, minUtil);

        USpanAlgorithm us = new USpanAlgorithm(minUtil);
        us.run(copy(db));
        Map<String, Double> refSet = asStrings(us.largeMap);
        System.out.printf("  %-10s HUSP=%-7d cand=%-12d explored=%d%n",
                "USpan", refSet.size(), us.candidateCount, us.exploredNodes);

        int bad = 0;
        bad += compare("HUSP-SP", refSet, runHuspSp(copy(db), minUtil));
        bad += compare("HUSP-ULL", refSet, runHuspUll(copy(db), minUtil));
        return bad;
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
        PatternSetDiff d = PatternSetDiff.of(ref, got);
        if (d.isClean()) {
            System.out.printf("    %-10s khop USpan tuyet doi%n", who);
            return 0;
        }
        System.out.printf("    %-10s SAI LECH: %s%n", who, d);
        return 1;
    }

    private static Map<String, Double> asStrings(Map<Pattern, Double> m) {
        Map<String, Double> out = new HashMap<>(m.size() * 2);
        for (Map.Entry<Pattern, Double> e : m.entrySet()) {
            out.put(e.getKey().toIntuitiveString(), e.getValue());
        }
        return out;
    }

    /** Cac thuat toan ghi de truong noi bo cua Sequence, nen moi lan chay mot ban sao list. */
    private static List<Sequence> copy(List<Sequence> src) {
        return new ArrayList<>(src);
    }
}
