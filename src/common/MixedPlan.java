package common;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * A fixed schedule of insertions, deletions and modifications, built once per database and
 * seed and replayed by every arm, so the arms are comparable.
 *
 * The counts are exact rather than drawn: with independent draws the mix of operations would
 * depend on the seed and the database, and since a deletion costs a few milliseconds where an
 * insertion costs a remine, each total would follow its draw. Only the order and the sequences
 * depend on the seed.
 *
 * Every operation takes opSize sequences: an insertion the next ones of the stream, a deletion
 * random ones of the current database, a modification random current ones replaced by the next
 * ones of the stream. One deletion can instead be made to exceed the rescan budget
 * (see {@link #build}).
 */
public final class MixedPlan {

    private MixedPlan() {}

    public enum Kind { INS, DEL, MOD }

    /** One operation of the schedule. */
    public static final class Op {
        public final Kind kind;
        public final int iter;
        /** INS: inserted sequences; DEL: deleted ones; MOD: the old sequences. */
        public final List<Sequence> a;
        /** MOD: the new sequences; null otherwise. */
        public final List<Sequence> b;
        /** A deletion built to exceed the rescan budget. */
        public final boolean beyondBudget;

        Op(Kind kind, int iter, List<Sequence> a, List<Sequence> b, boolean beyondBudget) {
            this.kind = kind; this.iter = iter; this.a = a; this.b = b; this.beyondBudget = beyondBudget;
        }

        /** The op_type written on the operation's row. */
        public String tag() {
            switch (kind) {
                case INS: return "SC6_INS";
                case DEL: return beyondBudget ? "SC6_DEL_BIG" : "SC6_DEL";
                default:  return "SC6_MOD";
            }
        }
    }

    /**
     * Builds the schedule.
     *
     * @param bigDeleteShare when positive, one deletion, at a position drawn from bigRnd among
     *        the deletions, takes random current sequences (in the order of a shuffle by rnd)
     *        until their total utility exceeds bigDeleteShare times the largest total utility
     *        the database has had so far. The rescan budget of the joint rule is
     *        (UUT - LUT_min) * TSU_ref = UUT * (1 - r_l) * TSU_ref, and TSU_ref is the total
     *        utility of an earlier state, so with bigDeleteShare = 1 - r_l such a deletion
     *        alone charges UUT * U_del > the budget. Zero or negative: no such deletion.
     * @param bigRnd decides only which deletion is the large one, so the operations before it
     *        are those of the same seed without one (unused when bigDeleteShare <= 0)
     */
    public static List<Op> build(List<Sequence> dInit, List<Sequence> rest, int opSize, Random rnd,
                                 int inserts, int deletes, int modifies,
                                 double bigDeleteShare, Random bigRnd) {
        List<Kind> kinds = new ArrayList<>();
        for (int i = 0; i < inserts; i++)  kinds.add(Kind.INS);
        for (int i = 0; i < deletes; i++)  kinds.add(Kind.DEL);
        for (int i = 0; i < modifies; i++) kinds.add(Kind.MOD);
        Collections.shuffle(kinds, rnd);
        int bigAt = -1;                       // index among the deletions
        if (bigDeleteShare > 0) {
            if (deletes == 0) throw new IllegalArgumentException("a large deletion needs a deletion");
            bigAt = bigRnd.nextInt(deletes);
        }

        List<Op> plan = new ArrayList<>();
        List<Sequence> pool = new ArrayList<>(rest);
        List<Sequence> cur = new ArrayList<>(dInit);
        double maxTotal = utility(cur);
        int delSeen = 0;
        for (int i = 0; i < kinds.size(); i++) {
            int iter = i + 1;
            Kind kind = kinds.get(i);
            // An operation that cannot be built is refused rather than skipped: a plan quietly
            // shorter than its schedule is the problem the exact counts exist to remove.
            boolean needsPool = (kind != Kind.DEL);
            if ((needsPool && pool.size() < opSize) || (kind != Kind.INS && cur.size() <= opSize)) {
                throw new IllegalStateException("cannot build operation " + iter + " (" + kind
                        + ") with opSize=" + opSize + ", pool=" + pool.size() + ", current=" + cur.size());
            }
            if (kind == Kind.INS) {
                List<Sequence> delta = new ArrayList<>(pool.subList(0, opSize));
                pool.subList(0, opSize).clear();
                plan.add(new Op(Kind.INS, iter, delta, null, false));
                cur.addAll(delta);
            } else if (kind == Kind.DEL) {
                boolean big = (delSeen++ == bigAt);
                List<Sequence> toDel = big ? pickUntil(cur, bigDeleteShare * maxTotal, rnd, iter)
                                           : pickRandom(cur, opSize, rnd);
                plan.add(new Op(Kind.DEL, iter, toDel, null, big));
                cur.removeAll(toDel);
            } else {
                List<Sequence> oldSeqs = pickRandom(cur, opSize, rnd);
                List<Sequence> newSeqs = new ArrayList<>(pool.subList(0, opSize));
                pool.subList(0, opSize).clear();
                plan.add(new Op(Kind.MOD, iter, oldSeqs, newSeqs, false));
                cur.removeAll(oldSeqs);
                cur.addAll(newSeqs);
            }
            maxTotal = Math.max(maxTotal, utility(cur));
        }
        return plan;
    }

    public static List<Sequence> pickRandom(List<Sequence> from, int count, Random rnd) {
        List<Sequence> copy = new ArrayList<>(from);
        Collections.shuffle(copy, rnd);
        return new ArrayList<>(copy.subList(0, Math.min(count, copy.size())));
    }

    /** Random sequences of from, in the order of one shuffle, until their utility exceeds bound. */
    private static List<Sequence> pickUntil(List<Sequence> from, double bound, Random rnd, int iter) {
        List<Sequence> copy = new ArrayList<>(from);
        Collections.shuffle(copy, rnd);
        List<Sequence> out = new ArrayList<>();
        double u = 0;
        for (Sequence s : copy) {
            if (u > bound) break;
            out.add(s);
            u += s.sequenceUtility;
        }
        if (u <= bound || out.size() >= from.size()) {
            throw new IllegalStateException("cannot build the large deletion at operation " + iter
                    + ": the current database holds " + u + ", the deletion needs more than " + bound
                    + " and must leave a sequence");
        }
        return out;
    }

    public static double utility(List<Sequence> seqs) {
        double t = 0;
        for (Sequence s : seqs) t += s.sequenceUtility;
        return t;
    }

    /** "20 operations (6 insert, 10 delete, 4 modify; operation 7 deletes 412 sequences beyond the budget)". */
    public static String summary(List<Op> plan) {
        int i = 0, d = 0, m = 0;
        String big = "";
        for (Op o : plan) {
            if (o.kind == Kind.INS) i++;
            else if (o.kind == Kind.DEL) d++;
            else m++;
            if (o.beyondBudget) big = "; operation " + o.iter + " deletes " + o.a.size()
                    + " sequences beyond the budget";
        }
        return plan.size() + " operations (" + i + " insert, " + d + " delete, " + m + " modify" + big + ")";
    }
}
