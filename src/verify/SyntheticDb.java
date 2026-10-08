package verify;

import common.QItem;
import common.QItemset;
import common.Sequence;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds small in-memory sequences for the correctness tests.
 *
 * Syntax: "1[19] 2[31] | 3[4]" is one sequence of two itemsets: the first holds item 1
 * (utility 19) and item 2 (utility 31), the second holds item 3 (utility 4). The utility in
 * brackets is the MULTIPLIED utility (internal x external), the same value DatasetLoader
 * computes.
 */
public final class SyntheticDb {

    private SyntheticDb() {}

    private static int nextId = 0;

    /** Resets the sequence id counter (call at the start of every test scenario). */
    public static void resetIds() { nextId = 0; }

    public static Sequence seq(String spec) {
        Sequence s = new Sequence(nextId++);
        for (String part : spec.split("\\|")) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            QItemset is = new QItemset();
            for (String tok : p.split("\\s+")) {
                int l = tok.indexOf('['), r = tok.indexOf(']');
                if (l < 0 || r < 0) throw new IllegalArgumentException("malformed token: " + tok);
                int id = Integer.parseInt(tok.substring(0, l).trim());
                double u = Double.parseDouble(tok.substring(l + 1, r).trim());
                is.items.add(new QItem(id, u));
            }
            if (!is.items.isEmpty()) s.itemsets.add(is);
        }
        s.calculateRemainingUtilities();
        return s;
    }

    public static List<Sequence> db(String... specs) {
        List<Sequence> out = new ArrayList<>(specs.length);
        for (String spec : specs) out.add(seq(spec));
        return out;
    }

    public static double tsu(List<Sequence> seqs) {
        double t = 0;
        for (Sequence s : seqs) t += s.sequenceUtility;
        return t;
    }

    public static List<Sequence> concat(List<Sequence> a, List<Sequence> b) {
        List<Sequence> out = new ArrayList<>(a.size() + b.size());
        out.addAll(a);
        out.addAll(b);
        return out;
    }
}
