package verify;

import common.Sequence;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Runs the HUSP-SP code its authors publish, unchanged, on the small random databases of
 * verify.ItemRemovalCheck random (same generator and seed, so the same databases as
 * verify.BaselineExhaustiveCheck), at the same two thresholds, and compares the number of HUSPs it
 * counts with exhaustive enumeration (verify.IncUspVerifier.bruteForceHusps).
 *
 *   java -cp <our classes>:<the authors' classes>:<this class> verify.RandomDatabases TRIALS SEED DIR [zero-to-one]
 *
 * zero-to-one sets every utility 0 to 1 (see verify.BaselineExhaustiveCheck): the published code drops
 * an S-extension position when the utility of the prefix ending before it is 0 (maxPositionUtility != 0
 * in sConcatenation), and Definition 3.1 of the paper takes quantities to be positive, so a database with
 * a utility 0 is outside the model it was written for.
 *
 * The published code counts HUSPs (field huspNum) and writes no pattern, so only counts are compared
 * here; tools/WatchHusps.java lists the patterns of one database. The code reads the threshold as a
 * share and sets minUtility = (int) (total utility * share); every utility is an integer, so the
 * share passed is (c + 0.5) / TU with c the least integer >= share * TU, which makes minUtility = c,
 * the threshold of the enumeration. Each database is written to DIR/trial<t>-<level>.txt in the
 * format of the published code. Prints one line per comparison whose counts differ.
 */
public final class RandomDatabases {

    private RandomDatabases() {}

    static String line(String spec) {
        StringBuilder b = new StringBuilder();
        int su = 0;
        for (String part : spec.split("\\|")) {
            for (String tok : part.trim().split("\\s+")) {
                b.append(tok).append(' ');
                su += Integer.parseInt(tok.substring(tok.indexOf('[') + 1, tok.indexOf(']')));
            }
            b.append("-1 ");
        }
        return b.append("-2 SUtility:").append(su).toString();
    }

    public static void main(String[] a) throws Exception {
        int trials = Integer.parseInt(a[0]);
        long seed = Long.parseLong(a[1]);
        File dir = new File(a[2]);
        boolean zeroToOne = a.length > 3 && a[3].equals("zero-to-one");
        dir.mkdirs();
        Random r = new Random(seed);
        long comparisons = 0, differ = 0, fewer = 0, more = 0, deficit = 0, surplus = 0, refTotal = 0, gotTotal = 0;
        List<Integer> dbs = new ArrayList<>();
        for (int t = 0; t < trials; t++) {
            SyntheticDb.resetIds();
            double su = ItemRemovalCheck.SUS[r.nextInt(ItemRemovalCheck.SUS.length)];
            double ratio = ItemRemovalCheck.RATIOS[r.nextInt(ItemRemovalCheck.RATIOS.length)];
            int n = 8 + r.nextInt(13);
            List<String> specs = new ArrayList<>();
            List<Sequence> db = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                String sp = ItemRemovalCheck.randSeq(r);
                if (zeroToOne) sp = sp.replace("[0]", "[1]");
                specs.add(sp); db.add(SyntheticDb.seq(sp));
            }
            double tsu = SyntheticDb.tsu(db);
            double[] shares = ratio < 1.0 ? new double[]{su, ratio * su} : new double[]{su};
            boolean bad = false;
            for (int lv = 0; lv < shares.length; lv++) {
                Map<String, Double> ref = IncUspVerifier.bruteForceHusps(db, shares[lv]);
                long c = (long) Math.ceil(shares[lv] * tsu);
                File f = new File(dir, "trial" + t + "-" + lv + ".txt");
                try (BufferedWriter w = new BufferedWriter(new FileWriter(f))) {
                    for (String sp : specs) { w.write(line(sp)); w.newLine(); }
                }
                HUSP_SP.HUSP_SP_Algo alg = new HUSP_SP.HUSP_SP_Algo(f.getPath(), (c + 0.5) / tsu, new File(dir, "unused").getPath());
                alg.runAlgo();
                Field mu = HUSP_SP.HUSP_SP_Algo.class.getDeclaredField("minUtility"); mu.setAccessible(true);
                Field hn = HUSP_SP.HUSP_SP_Algo.class.getDeclaredField("huspNum"); hn.setAccessible(true);
                int minU = mu.getInt(alg);
                long got = hn.getLong(alg);
                if (minU != c) throw new IllegalStateException("trial " + t + ": minUtility " + minU + " != " + c);
                comparisons++;
                refTotal += ref.size(); gotTotal += got;
                if (got != ref.size()) {
                    differ++; bad = true;
                    if (got < ref.size()) { fewer++; deficit += ref.size() - got; } else { more++; surplus += got - ref.size(); }
                    System.out.printf("differs trial=%d level=%d threshold=%d enumeration=%d published-code=%d file=%s%n",
                            t, lv, c, ref.size(), got, f.getName());
                }
            }
            if (bad) dbs.add(t);
        }
        System.out.printf("published HUSP-SP on the random databases: trials=%d seed=%d utilities=%s comparisons=%d count-differs=%d "
                + "(fewer %d, more %d) databases=%d deficit=%d surplus=%d enumeration-total=%d published-total=%d%n",
                trials, seed, zeroToOne ? "zero-to-one" : "as-generated", comparisons, differ, fewer, more, dbs.size(), deficit, surplus, refTotal, gotTotal);
    }
}
