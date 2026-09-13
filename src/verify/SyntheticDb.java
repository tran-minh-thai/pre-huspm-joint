package verify;

import common.QItem;
import common.QItemset;
import common.Sequence;

import java.util.ArrayList;
import java.util.List;

/**
 * Dung cac chuoi nho ngay trong bo nho, phuc vu cac test tinh dung dan.
 *
 * Cu phap: "1[19] 2[31] | 3[4]" nghia la mot chuoi gom hai itemset,
 * itemset dau co item 1 (utility 19) va item 2 (utility 31), itemset sau co
 * item 3 (utility 4). Utility ghi trong ngoac vuong la utility DA NHAN
 * (internal x external), giong gia tri ma DatasetLoader tinh ra.
 */
public final class SyntheticDb {

    private SyntheticDb() {}

    private static int nextId = 0;

    /** Dat lai bo cap phat id chuoi (goi o dau moi kich ban test). */
    public static void resetIds() { nextId = 0; }

    public static Sequence seq(String spec) {
        Sequence s = new Sequence(nextId++);
        for (String part : spec.split("\\|")) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            QItemset is = new QItemset();
            for (String tok : p.split("\\s+")) {
                int l = tok.indexOf('['), r = tok.indexOf(']');
                if (l < 0 || r < 0) throw new IllegalArgumentException("token sai dinh dang: " + tok);
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
