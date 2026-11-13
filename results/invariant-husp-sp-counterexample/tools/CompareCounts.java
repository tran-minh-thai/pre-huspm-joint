package verify;

import common.Sequence;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The comparisons of verify.BaselineExhaustiveCheck (zero-to-one) where HUSPSPAsPublished, the TRSU of
 * Definition 4.11, returns fewer patterns than exhaustive enumeration, one line each, in the format of
 * RandomDatabases, so that the two lists can be compared comparison by comparison.
 *
 *   java -cp <our classes>:<this class> verify.CompareCounts TRIALS SEED
 */
public final class CompareCounts {
    private CompareCounts() {}

    public static void main(String[] a) {
        Random r = new Random(Long.parseLong(a[1]));
        for (int t = 0; t < Integer.parseInt(a[0]); t++) {
            SyntheticDb.resetIds();
            double su = ItemRemovalCheck.SUS[r.nextInt(ItemRemovalCheck.SUS.length)];
            double ratio = ItemRemovalCheck.RATIOS[r.nextInt(ItemRemovalCheck.RATIOS.length)];
            int n = 8 + r.nextInt(13);
            List<String> specs = new ArrayList<>();
            List<Sequence> db = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                String sp = ItemRemovalCheck.randSeq(r).replace("[0]", "[1]");
                specs.add(sp); db.add(SyntheticDb.seq(sp));
            }
            double tsu = SyntheticDb.tsu(db);
            double[] shares = ratio < 1.0 ? new double[]{su, ratio * su} : new double[]{su};
            for (int lv = 0; lv < shares.length; lv++) {
                Map<String, Double> ref = IncUspVerifier.bruteForceHusps(db, shares[lv]);
                Map<String, Double> got = BaselineExhaustiveCheck.mine(4, specs, tsu * shares[lv]);
                if (got.size() != ref.size())
                    System.out.println("differs trial=" + t + " level=" + lv + " enumeration=" + ref.size() + " definition-4.11=" + got.size());
            }
        }
    }
}
