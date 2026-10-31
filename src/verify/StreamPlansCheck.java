package verify;

import common.DatasetLoader;
import common.Datasets;
import common.MixedPlan;
import common.QItem;
import common.QItemset;
import common.Sequence;
import common.StreamPlans;

import java.util.*;

/**
 * Checks the plans of the long streams (common.StreamPlans) on the mixed databases and the first
 * three seeds, and exits 1 on any violation:
 *   - 500 operations, the counts of each kind as specified (each half of SC8P on its own),
 *     insertion and deletion alternating in SC9 with the database within one operation of its size;
 *   - a deleted or modified sequence is in the current database, a new one has an id not seen before,
 *     every operation takes opSize sequences;
 *   - every new sequence is well formed: non-empty elements, items strictly increasing within an
 *     element, positive finite utilities, sequenceUtility the sum of its utilities;
 *   - SC10: a changed sequence differs from the one it replaces, and some changes and deletions pick
 *     sequences of the recent operations; SC10H: every other insertion carries ten times the utility
 *     of the file's next sequences;
 *   - the same seed builds the same plan.
 *
 *   java -cp build verify.StreamPlansCheck [DATASET ...]
 */
public final class StreamPlansCheck {

    private static int bad = 0;

    private static void fail(String what) {
        bad++;
        if (bad <= 30) System.out.println("  VIOLATION " + what);
    }

    public static void main(String[] args) throws Exception {
        List<String> dbs = args.length > 0 ? Arrays.asList(args) : Arrays.asList("SIGN", "LEVIATHAN", "C8T1S5I8N5K", "TAFENG");
        long[] seeds = {42L, 1337L, 2024L};
        int plans = 0, ops = 0, newSeqs = 0;
        for (String db : dbs) {
            List<Sequence> full = DatasetLoader.loadDataset("datasets/" + db + "_seq.txt", "datasets/" + db + "_eui.txt");
            int nInit = Datasets.dInitCount(db, full.size());
            List<Sequence> dInit = new ArrayList<>(full.subList(0, nInit));
            List<Sequence> rest = new ArrayList<>(full.subList(nInit, full.size()));
            int size = StreamPlans.opSize(rest);
            for (String sc : new String[]{"SC8", "SC8P", "SC9", "SC10", "SC10H"}) {
                for (long seed : seeds) {
                    List<MixedPlan.Op> plan = StreamPlans.build(sc, dInit, rest, new Random(seed + db.hashCode()));
                    List<MixedPlan.Op> again = StreamPlans.build(sc, dInit, rest, new Random(seed + db.hashCode()));
                    if (!signature(plan).equals(signature(again))) fail(sc + " " + db + " " + seed + ": the same seed builds another plan");
                    int[] c = check(sc, db + " " + seed, plan, dInit, rest, size);
                    plans++; ops += plan.size(); newSeqs += c[0];
                    System.out.printf("%s %-12s seed %-5d opSize %-3d %s; new sequences %d%s%n", sc, db, seed, size,
                            StreamPlans.summary(plan), c[0], sc.equals("SC10") ? "; picked among recent: " + c[1] + " of " + c[2] : "");
                }
            }
        }
        System.out.printf("plans: %d, operations: %d, new sequences checked: %d, violations: %d%n", plans, ops, newSeqs, bad);
        System.exit(bad == 0 ? 0 : 1);
    }

    private static String signature(List<MixedPlan.Op> plan) {
        StringBuilder sb = new StringBuilder();
        for (MixedPlan.Op o : plan) {
            sb.append(o.kind).append(':');
            for (Sequence s : o.a) sb.append(s.id).append(',').append(s.sequenceUtility).append(';');
            if (o.b != null) for (Sequence s : o.b) sb.append(s.id).append(',').append(s.sequenceUtility).append(';');
            sb.append('|');
        }
        return sb.toString();
    }

    /** Returns {new sequences, operations of SC10 that picked recent sequences, SC10 deletions and modifications}. */
    private static int[] check(String sc, String where, List<MixedPlan.Op> plan, List<Sequence> dInit, List<Sequence> rest, int size) {
        if (plan.size() != StreamPlans.OPERATIONS) fail(sc + " " + where + ": " + plan.size() + " operations");
        Map<MixedPlan.Kind, Integer> first = new EnumMap<>(MixedPlan.Kind.class), second = new EnumMap<>(MixedPlan.Kind.class);
        for (int i = 0; i < plan.size(); i++) (i < plan.size() / 2 ? first : second).merge(plan.get(i).kind, 1, Integer::sum);
        int ins = first.getOrDefault(MixedPlan.Kind.INS, 0) + second.getOrDefault(MixedPlan.Kind.INS, 0);
        int del = first.getOrDefault(MixedPlan.Kind.DEL, 0) + second.getOrDefault(MixedPlan.Kind.DEL, 0);
        int mod = first.getOrDefault(MixedPlan.Kind.MOD, 0) + second.getOrDefault(MixedPlan.Kind.MOD, 0);
        switch (sc) {
            case "SC8": case "SC10": case "SC10H":
                if (ins != 350 || del != 75 || mod != 75) fail(sc + " " + where + ": counts " + ins + "/" + del + "/" + mod);
                break;
            case "SC8P":
                if (!first.equals(Map.of(MixedPlan.Kind.INS, 200, MixedPlan.Kind.DEL, 25, MixedPlan.Kind.MOD, 25))
                        || !second.equals(Map.of(MixedPlan.Kind.INS, 25, MixedPlan.Kind.DEL, 200, MixedPlan.Kind.MOD, 25)))
                    fail(sc + " " + where + ": halves " + first + " " + second);
                break;
            case "SC9":
                for (int i = 0; i < plan.size(); i++)
                    if (plan.get(i).kind != (i % 2 == 0 ? MixedPlan.Kind.INS : MixedPlan.Kind.DEL)) { fail(sc + " " + where + ": operation " + (i + 1) + " is " + plan.get(i).kind); break; }
                break;
        }
        Set<Integer> current = new HashSet<>(), seen = new HashSet<>();
        for (Sequence s : dInit) { current.add(s.id); seen.add(s.id); }
        Map<Integer, Sequence> fileSeq = new HashMap<>();
        for (Sequence s : rest) { fileSeq.put(s.id, s); }
        Set<Integer> fileIds = fileSeq.keySet();
        int news = 0, recentPicks = 0, picks = 0, insertions = 0;
        Deque<Set<Integer>> recent = new ArrayDeque<>();
        int poolAt = 0;
        for (MixedPlan.Op o : plan) {
            if (o.a.size() != size) fail(sc + " " + where + " op " + o.iter + ": takes " + o.a.size() + " sequences, opSize " + size);
            Set<Integer> recentIds = new HashSet<>();
            for (Set<Integer> r : recent) recentIds.addAll(r);
            if (o.kind != MixedPlan.Kind.INS) {
                boolean fromRecent = false;
                for (Sequence s : o.a) {
                    if (!current.contains(s.id)) fail(sc + " " + where + " op " + o.iter + ": sequence " + s.id + " is not in the database");
                    if (recentIds.contains(s.id)) fromRecent = true;
                }
                picks++;
                if (fromRecent) recentPicks++;
                for (Sequence s : o.a) current.remove(s.id);
                for (Set<Integer> r : recent) for (Sequence s : o.a) r.remove(s.id);
            }
            List<Sequence> added = o.kind == MixedPlan.Kind.INS ? o.a : o.kind == MixedPlan.Kind.MOD ? o.b : List.of();
            if (o.kind == MixedPlan.Kind.MOD && o.b.size() != o.a.size()) fail(sc + " " + where + " op " + o.iter + ": " + o.a.size() + " old, " + o.b.size() + " new");
            for (int k = 0; k < added.size(); k++) {
                Sequence s = added.get(k);
                if (!seen.add(s.id)) fail(sc + " " + where + " op " + o.iter + ": id " + s.id + " used before");
                current.add(s.id);
                if (!fileIds.contains(s.id)) { news++; wellFormed(sc + " " + where + " op " + o.iter, s); }
                if (sc.equals("SC10") && o.kind == MixedPlan.Kind.MOD && same(s, o.a.get(k)))
                    fail(sc + " " + where + " op " + o.iter + ": a changed sequence equals the one it replaces");
            }
            if (o.kind == MixedPlan.Kind.INS && sc.equals("SC10H")) {
                double u = 0, base = 0;
                for (int k = 0; k < o.a.size(); k++) { u += o.a.get(k).sequenceUtility; base += rest.get(poolAt + k).sequenceUtility; }
                double want = insertions % 2 == 1 ? 10.0 : 1.0;
                if (Math.abs(u - want * base) > 1e-6 * Math.max(1, want * base))
                    fail(sc + " " + where + " op " + o.iter + ": insertion utility " + u + ", expected " + want + " x " + base);
            }
            if (o.kind == MixedPlan.Kind.INS || (o.kind == MixedPlan.Kind.MOD && !sc.equals("SC10"))) poolAt += size;
            if (o.kind == MixedPlan.Kind.INS) insertions++;
            if (o.kind != MixedPlan.Kind.DEL) {
                Set<Integer> ids = new HashSet<>();
                for (Sequence s : added) ids.add(s.id);
                recent.addLast(ids);
                if (recent.size() > StreamPlans.RECENT_WINDOW) recent.removeFirst();
            }
            if (sc.equals("SC9") && Math.abs(current.size() - dInit.size()) > size)
                fail(sc + " " + where + " op " + o.iter + ": database of " + current.size() + " sequences against " + dInit.size());
        }
        if (sc.equals("SC10") && recentPicks == 0) fail(sc + " " + where + ": no operation picked a recent sequence");
        return new int[]{news, recentPicks, picks};
    }

    private static boolean same(Sequence x, Sequence y) {
        if (x.itemsets.size() != y.itemsets.size()) return false;
        for (int e = 0; e < x.itemsets.size(); e++) {
            List<QItem> a = x.itemsets.get(e).items, b = y.itemsets.get(e).items;
            if (a.size() != b.size()) return false;
            for (int j = 0; j < a.size(); j++) if (a.get(j).id != b.get(j).id || a.get(j).utility != b.get(j).utility) return false;
        }
        return true;
    }

    private static void wellFormed(String where, Sequence s) {
        double u = 0;
        if (s.itemsets.isEmpty()) fail(where + ": sequence " + s.id + " has no element");
        for (QItemset is : s.itemsets) {
            if (is.items.isEmpty()) fail(where + ": sequence " + s.id + " has an empty element");
            for (int j = 0; j < is.items.size(); j++) {
                QItem q = is.items.get(j);
                if (!(q.utility > 0) || Double.isInfinite(q.utility)) fail(where + ": sequence " + s.id + " item " + q.id + " utility " + q.utility);
                if (j > 0 && q.id <= is.items.get(j - 1).id) fail(where + ": sequence " + s.id + " items not increasing");
                u += q.utility;
            }
        }
        if (Math.abs(u - s.sequenceUtility) > 1e-9 * Math.max(1, u)) fail(where + ": sequence " + s.id + " utility " + s.sequenceUtility + " against " + u);
        if (s.flatItems == null || s.flatLen == 0) fail(where + ": sequence " + s.id + " has no flat representation");
    }
}
