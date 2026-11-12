package verify;

import common.QItem;
import common.QItemset;
import common.Sequence;

import java.util.*;

/**
 * The small random databases of `ItemRemovalCheck random TRIALS SEED`, rebuilt from the same seed
 * (the trial loop draws su, the floor ratio, the number of sequences and the sequences, and nothing
 * else, from its generator), described without mining them: sequences per database, elements and
 * items per sequence, and the databases where some pattern occurs at least twice in one sequence
 * with different utilities, so that the utility of the pattern in that sequence is a maximum over
 * occurrences rather than the utility of its only occurrence.
 *
 * args: trials seed   (those of the record, 2000 1)
 */
public final class ExhaustiveDbStats {
    private ExhaustiveDbStats() {}

    /** Every occurrence of every pattern in s, as pattern -> the utilities of its occurrences. */
    static void occurrences(Sequence s, int from, List<List<Integer>> pat, double u, Map<String, Set<Double>> out) {
        for (int j = from; j < s.itemsets.size(); j++) {
            List<QItem> items = s.itemsets.get(j).items;
            int m = items.size();
            for (int mask = 1; mask < (1 << m); mask++) {
                List<Integer> el = new ArrayList<>();
                double add = 0;
                for (int k = 0; k < m; k++) if ((mask & (1 << k)) != 0) { el.add(items.get(k).id); add += items.get(k).utility; }
                Collections.sort(el);
                pat.add(el);
                double v = u + add;
                out.computeIfAbsent(pat.toString(), x -> new HashSet<>()).add(v);
                occurrences(s, j + 1, pat, v, out);
                pat.remove(pat.size() - 1);
            }
        }
    }

    public static void main(String[] args) {
        int trials = args.length > 0 ? Integer.parseInt(args[0]) : 2000;
        long seed = args.length > 1 ? Long.parseLong(args[1]) : 1;
        Random r = new Random(seed);
        int minSeqs = Integer.MAX_VALUE, maxSeqs = 0, maxElements = 0, maxItems = 0, maxDistinctItems = 0;
        int multiDbs = 0;
        long seqs = 0, multiSeqs = 0;
        for (int t = 0; t < trials; t++) {
            SyntheticDb.resetIds();
            r.nextInt(ItemRemovalCheck.SUS.length);      // su, as the check draws it
            r.nextInt(ItemRemovalCheck.RATIOS.length);   // the floor ratio
            int n = 8 + r.nextInt(13);
            minSeqs = Math.min(minSeqs, n); maxSeqs = Math.max(maxSeqs, n);
            Set<Integer> distinct = new HashSet<>();
            boolean multi = false;
            for (int i = 0; i < n; i++) {
                Sequence s = SyntheticDb.seq(ItemRemovalCheck.randSeq(r));
                seqs++;
                int items = 0;
                for (QItemset is : s.itemsets) { items += is.items.size(); for (QItem q : is.items) distinct.add(q.id); }
                maxElements = Math.max(maxElements, s.itemsets.size());
                maxItems = Math.max(maxItems, items);
                Map<String, Set<Double>> occ = new HashMap<>();
                occurrences(s, 0, new ArrayList<>(), 0, occ);
                boolean m = false;
                for (Set<Double> v : occ.values()) if (v.size() > 1) { m = true; break; }
                if (m) { multi = true; multiSeqs++; }
            }
            maxDistinctItems = Math.max(maxDistinctItems, distinct.size());
            if (multi) multiDbs++;
        }
        System.out.printf("databases=%d seed=%d sequences-per-database=%d..%d sequences=%d elements-per-sequence-max=%d "
                        + "items-per-sequence-max=%d distinct-items-per-database-max=%d "
                        + "databases-with-a-pattern-of-two-occurrence-utilities=%d sequences-with-such-a-pattern=%d%n",
                trials, seed, minSeqs, maxSeqs, seqs, maxElements, maxItems, maxDistinctItems, multiDbs, multiSeqs);
    }
}
