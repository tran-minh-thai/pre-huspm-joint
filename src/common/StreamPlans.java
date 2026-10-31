package common;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The long streams of SC8, SC8P, SC9, SC10 and SC10H, built once per database and seed and replayed
 * by every arm, as MixedPlan builds SC6's. Every operation takes opSize sequences; opSize is a
 * thousandth of the sequences the file holds after D_init, so a stream of 500 operations draws at
 * most half of them for insertions and new sequences and never runs out.
 *
 *   SC8    uniform: SC6's proportions, 70% insertions, 15% deletions and 15% modifications, as
 *          exact counts in a seeded order; a modification replaces random current sequences by the
 *          next ones of the file, as in SC6.
 *   SC8P   phase change: the first half 80% insertions, 10% deletions, 10% modifications, the
 *          second half 10%, 80%, 10%, each half in a seeded order.
 *   SC9    balanced: insertion and deletion alternate, the same size, so the database keeps about
 *          its size.
 *   SC10   modifications of current sequences: SC8's proportions, but a modification changes the
 *          sequences it picks rather than replacing them by others (see modify), half of the
 *          modifications and half of the deletions pick among the sequences of the last 20
 *          insertions and modifications still in the database, so a sequence can be changed again
 *          or deleted soon after it arrived.
 *   SC10H  uneven batches: SC8's schedule, every other insertion of ten times the utility (its
 *          sequences' quantities multiplied by 10), the same number of sequences.
 *
 * The seed decides the order of the operations, the sequences each one picks and, in SC10, how
 * each modification changes them. A changed sequence is a new sequence with a new id.
 */
public final class StreamPlans {

    private StreamPlans() {}

    public static final int OPERATIONS = 500;
    /** Operations whose new sequences SC10 may pick again. */
    public static final int RECENT_WINDOW = 20;

    /** Sequences per operation: a thousandth of the stream, at least one. */
    public static int opSize(List<Sequence> rest) {
        return Math.max(1, rest.size() / 1000);
    }

    public static List<MixedPlan.Op> build(String scenario, List<Sequence> dInit, List<Sequence> rest, Random rnd) {
        int size = opSize(rest);
        switch (scenario) {
            case "SC8":   return replay(dInit, rest, size, rnd, shuffled(rnd, 350, 75, 75), false, false);
            case "SC8P": {
                List<MixedPlan.Kind> kinds = shuffled(rnd, 200, 25, 25);
                kinds.addAll(shuffled(rnd, 25, 200, 25));
                return replay(dInit, rest, size, rnd, kinds, false, false);
            }
            case "SC9": {
                List<MixedPlan.Kind> kinds = new ArrayList<>();
                for (int i = 0; i < OPERATIONS / 2; i++) { kinds.add(MixedPlan.Kind.INS); kinds.add(MixedPlan.Kind.DEL); }
                return replay(dInit, rest, size, rnd, kinds, false, false);
            }
            case "SC10":  return replay(dInit, rest, size, rnd, shuffled(rnd, 350, 75, 75), true, false);
            case "SC10H": return replay(dInit, rest, size, rnd, shuffled(rnd, 350, 75, 75), false, true);
            default: throw new IllegalArgumentException("no long stream named " + scenario);
        }
    }

    private static List<MixedPlan.Kind> shuffled(Random rnd, int ins, int del, int mod) {
        List<MixedPlan.Kind> kinds = new ArrayList<>();
        for (int i = 0; i < ins; i++) kinds.add(MixedPlan.Kind.INS);
        for (int i = 0; i < del; i++) kinds.add(MixedPlan.Kind.DEL);
        for (int i = 0; i < mod; i++) kinds.add(MixedPlan.Kind.MOD);
        Collections.shuffle(kinds, rnd);
        return kinds;
    }

    private static List<MixedPlan.Op> replay(List<Sequence> dInit, List<Sequence> rest, int size, Random rnd,
                                             List<MixedPlan.Kind> kinds, boolean changeInPlace, boolean uneven) {
        List<MixedPlan.Op> plan = new ArrayList<>();
        List<Sequence> pool = new ArrayList<>(rest);
        List<Sequence> cur = new ArrayList<>(dInit);
        int nextId = 1;
        for (Sequence s : dInit) nextId = Math.max(nextId, s.id + 1);
        for (Sequence s : rest) nextId = Math.max(nextId, s.id + 1);
        // occurrences (item, utility) of the database, for an item substitution
        List<QItem> occurrences = new ArrayList<>();
        for (Sequence s : dInit) for (QItemset is : s.itemsets) occurrences.addAll(is.items);
        List<List<Sequence>> recent = new ArrayList<>();
        int insertions = 0;
        for (int i = 0; i < kinds.size(); i++) {
            int iter = i + 1;
            MixedPlan.Kind kind = kinds.get(i);
            boolean needsPool = kind == MixedPlan.Kind.INS || (kind == MixedPlan.Kind.MOD && !changeInPlace);
            if ((needsPool && pool.size() < size) || (kind != MixedPlan.Kind.INS && cur.size() <= size)) {
                throw new IllegalStateException("cannot build operation " + iter + " (" + kind + ") with opSize="
                        + size + ", pool=" + pool.size() + ", current=" + cur.size());
            }
            if (kind == MixedPlan.Kind.INS) {
                List<Sequence> delta = new ArrayList<>(pool.subList(0, size));
                pool.subList(0, size).clear();
                if (uneven && insertions % 2 == 1) {
                    List<Sequence> heavy = new ArrayList<>();
                    for (Sequence s : delta) heavy.add(scaled(s, 10.0, nextId++));
                    delta = heavy;
                }
                insertions++;
                plan.add(new MixedPlan.Op(MixedPlan.Kind.INS, iter, delta, null, false));
                cur.addAll(delta);
                remember(recent, delta);
            } else if (kind == MixedPlan.Kind.DEL) {
                List<Sequence> toDel = changeInPlace && rnd.nextBoolean() ? pickRecent(recent, cur, size, rnd)
                                                                         : MixedPlan.pickRandom(cur, size, rnd);
                plan.add(new MixedPlan.Op(MixedPlan.Kind.DEL, iter, toDel, null, false));
                cur.removeAll(toDel);
                forget(recent, toDel);
            } else {
                List<Sequence> oldSeqs = changeInPlace && rnd.nextBoolean() ? pickRecent(recent, cur, size, rnd)
                                                                           : MixedPlan.pickRandom(cur, size, rnd);
                List<Sequence> newSeqs = new ArrayList<>();
                if (changeInPlace) {
                    for (Sequence s : oldSeqs) newSeqs.add(modify(s, nextId++, occurrences, rnd));
                } else {
                    newSeqs.addAll(pool.subList(0, size));
                    pool.subList(0, size).clear();
                }
                plan.add(new MixedPlan.Op(MixedPlan.Kind.MOD, iter, oldSeqs, newSeqs, false));
                cur.removeAll(oldSeqs);
                cur.addAll(newSeqs);
                forget(recent, oldSeqs);
                remember(recent, newSeqs);
            }
        }
        return plan;
    }

    private static void remember(List<List<Sequence>> recent, List<Sequence> seqs) {
        recent.add(new ArrayList<>(seqs));
        if (recent.size() > RECENT_WINDOW) recent.remove(0);
    }

    private static void forget(List<List<Sequence>> recent, List<Sequence> gone) {
        for (List<Sequence> r : recent) r.removeAll(gone);
    }

    /** size sequences among those of the recent operations still in the database, topped up at random. */
    private static List<Sequence> pickRecent(List<List<Sequence>> recent, List<Sequence> cur, int size, Random rnd) {
        List<Sequence> pool = new ArrayList<>();
        for (List<Sequence> r : recent) pool.addAll(r);
        List<Sequence> out = MixedPlan.pickRandom(pool, size, rnd);
        if (out.size() < size) {
            List<Sequence> others = new ArrayList<>(cur);
            others.removeAll(out);
            out.addAll(MixedPlan.pickRandom(others, size - out.size(), rnd));
        }
        return out;
    }

    /**
     * A changed copy of s, one of three changes drawn from the seed:
     *   0  the quantities of some items change: each item with probability one half, at least one,
     *      has its utility (quantity times unit price) multiplied by a factor drawn in [0.5, 2];
     *   1  one item is replaced by an occurrence (item and utility) drawn from the database, an item
     *      the element does not hold already;
     *   2  two elements trade places (a sequence of one element changes its quantities instead).
     */
    static Sequence modify(Sequence s, int newId, List<QItem> occurrences, Random rnd) {
        List<List<QItem>> els = copy(s);
        int change = rnd.nextInt(3);
        if (change == 2 && els.size() < 2) change = 0;
        change(els, change, occurrences, rnd);
        // two equal elements traded, or no item to substitute: change the quantities instead
        if (sameAs(els, s)) change(els, 0, occurrences, rnd);
        return build(newId, els);
    }

    private static boolean sameAs(List<List<QItem>> els, Sequence s) {
        if (els.size() != s.itemsets.size()) return false;
        for (int e = 0; e < els.size(); e++) {
            List<QItem> a = els.get(e), b = s.itemsets.get(e).items;
            if (a.size() != b.size()) return false;
            for (int j = 0; j < a.size(); j++) if (a.get(j).id != b.get(j).id || a.get(j).utility != b.get(j).utility) return false;
        }
        return true;
    }

    private static void change(List<List<QItem>> els, int change, List<QItem> occurrences, Random rnd) {
        if (change == 0) {
            boolean any = false;
            for (List<QItem> el : els) for (int j = 0; j < el.size(); j++) {
                if (rnd.nextBoolean()) { el.set(j, new QItem(el.get(j).id, el.get(j).utility * (0.5 + 1.5 * rnd.nextDouble()))); any = true; }
            }
            if (!any) {
                List<QItem> el = els.get(rnd.nextInt(els.size()));
                int j = rnd.nextInt(el.size());
                el.set(j, new QItem(el.get(j).id, el.get(j).utility * (0.5 + 1.5 * rnd.nextDouble())));
            }
        } else if (change == 1) {
            List<QItem> el = els.get(rnd.nextInt(els.size()));
            int j = rnd.nextInt(el.size());
            for (int tries = 0; tries < 100; tries++) {
                QItem o = occurrences.get(rnd.nextInt(occurrences.size()));
                boolean held = false;
                for (QItem q : el) if (q.id == o.id) held = true;
                if (!held) { el.set(j, new QItem(o.id, o.utility)); break; }
            }
            el.sort((a, b) -> Integer.compare(a.id, b.id));
        } else {
            int a = rnd.nextInt(els.size()), b = rnd.nextInt(els.size() - 1);
            if (b >= a) b++;
            Collections.swap(els, a, b);
        }
    }

    /** s with every utility multiplied by factor, under a new id. */
    static Sequence scaled(Sequence s, double factor, int newId) {
        List<List<QItem>> els = copy(s);
        for (List<QItem> el : els) for (int j = 0; j < el.size(); j++) el.set(j, new QItem(el.get(j).id, el.get(j).utility * factor));
        return build(newId, els);
    }

    private static List<List<QItem>> copy(Sequence s) {
        List<List<QItem>> els = new ArrayList<>();
        for (QItemset is : s.itemsets) {
            List<QItem> el = new ArrayList<>();
            for (QItem q : is.items) el.add(new QItem(q.id, q.utility));
            els.add(el);
        }
        return els;
    }

    private static Sequence build(int id, List<List<QItem>> els) {
        Sequence s = new Sequence(id);
        for (List<QItem> el : els) {
            QItemset is = new QItemset();
            is.items.addAll(el);
            s.itemsets.add(is);
        }
        s.calculateRemainingUtilities();
        s.buildFlatRepresentation();
        return s;
    }

    /** "500 operations (350 insert, 75 delete, 75 modify)". */
    public static String summary(List<MixedPlan.Op> plan) {
        Map<MixedPlan.Kind, Integer> n = new HashMap<>();
        for (MixedPlan.Op o : plan) n.merge(o.kind, 1, Integer::sum);
        return plan.size() + " operations (" + n.getOrDefault(MixedPlan.Kind.INS, 0) + " insert, "
                + n.getOrDefault(MixedPlan.Kind.DEL, 0) + " delete, " + n.getOrDefault(MixedPlan.Kind.MOD, 0) + " modify)";
    }
}
