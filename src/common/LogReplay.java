package common;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A database replayed from a log (SC11): D_init in NAME_seq.txt and the operations of the log in
 * NAME_ops.txt, both written by onlineretail/build_onlineretail.py.
 *
 * The operation file holds blocks "OP n KIND day k" followed by k lines: "newId sequence" for an
 * insertion, "oldId newId sequence" for a modification, "oldId" for a deletion. The sequences of
 * D_init carry the ids 1..|D_init| in file order. Every deletion and every old version names a
 * sequence that is in the database at that point; this class resolves it to the same Sequence
 * object, since the harness removes sequences by identity, and refuses an id that is missing,
 * reused or deleted twice. A Sequence keeps the loader's convention of id = file id - 1.
 */
public final class LogReplay {

    private LogReplay() {}

    /** D_init, the operations, and the day of each operation (index = position in the plan). */
    public static final class Log {
        public final List<Sequence> dInit;
        public final List<MixedPlan.Op> plan;
        public final List<String> days;
        public final List<Sequence> last;

        Log(List<Sequence> dInit, List<MixedPlan.Op> plan, List<String> days, List<Sequence> last) {
            this.dInit = dInit; this.plan = plan; this.days = days; this.last = last;
        }
    }

    /** Reads dir/NAME_seq.txt, dir/NAME_eui.txt and dir/NAME + opsSuffix (e.g. "_ops.txt"). */
    public static Log load(String dir, String name, String opsSuffix) throws IOException {
        String euiPath = Paths.get(dir, name + "_eui.txt").toString();
        Map<Integer, Double> eui = DatasetLoader.loadExternalUtilities(euiPath);
        List<Sequence> dInit = DatasetLoader.loadDataset(Paths.get(dir, name + "_seq.txt").toString(), euiPath);
        Map<Integer, Sequence> live = new HashMap<>();
        for (Sequence s : dInit) {
            if (live.put(s.id + 1, s) != null) throw new IllegalStateException("D_init id " + (s.id + 1) + " twice");
        }
        int maxId = dInit.size();
        List<MixedPlan.Op> plan = new ArrayList<>();
        List<String> days = new ArrayList<>();
        Path ops = Paths.get(dir, name + opsSuffix);
        try (BufferedReader r = Files.newBufferedReader(ops, StandardCharsets.UTF_8)) {
            String header;
            while ((header = r.readLine()) != null) {
                if (header.isBlank()) continue;
                String[] h = header.trim().split(" ");
                if (h.length != 5 || !"OP".equals(h[0]))
                    throw new IllegalStateException(ops + ": expected an OP line, found '" + header + "'");
                int iter = Integer.parseInt(h[1]);
                if (iter != plan.size() + 1)
                    throw new IllegalStateException(ops + ": operation " + iter + " out of order");
                MixedPlan.Kind kind = MixedPlan.Kind.valueOf(h[2]);
                int k = Integer.parseInt(h[4]);
                List<Sequence> a = new ArrayList<>(), b = kind == MixedPlan.Kind.MOD ? new ArrayList<>() : null;
                for (int i = 0; i < k; i++) {
                    String line = r.readLine();
                    if (line == null) throw new IllegalStateException(ops + ": operation " + iter + " is cut short");
                    String[] t = line.split(" ", kind == MixedPlan.Kind.MOD ? 3 : 2);
                    switch (kind) {
                        case INS: {
                            int id = Integer.parseInt(t[0]);
                            a.add(fresh(live, id, t[1], eui, ops, iter));
                            maxId = Math.max(maxId, id);
                            break;
                        }
                        case DEL:
                            a.add(take(live, Integer.parseInt(t[0]), ops, iter));
                            break;
                        default: {
                            a.add(take(live, Integer.parseInt(t[0]), ops, iter));
                            int id = Integer.parseInt(t[1]);
                            b.add(fresh(live, id, t[2], eui, ops, iter));
                            maxId = Math.max(maxId, id);
                        }
                    }
                }
                plan.add(new MixedPlan.Op(kind, iter, a, b, false));
                days.add(h[3]);
            }
        }
        List<Sequence> last = new ArrayList<>(live.values());
        last.sort((x, y) -> Integer.compare(x.id, y.id));
        return new Log(dInit, plan, days, last);
    }

    private static Sequence fresh(Map<Integer, Sequence> live, int id, String line, Map<Integer, Double> eui,
                                  Path ops, int iter) {
        if (live.containsKey(id) || id <= 0)
            throw new IllegalStateException(ops + ": operation " + iter + " reuses id " + id);
        Sequence s = DatasetLoader.parseSequenceLine(line.trim(), id - 1, eui, null);
        if (s == null) throw new IllegalStateException(ops + ": operation " + iter + ", id " + id + " is empty");
        live.put(id, s);
        return s;
    }

    private static Sequence take(Map<Integer, Sequence> live, int id, Path ops, int iter) {
        Sequence s = live.remove(id);
        if (s == null)
            throw new IllegalStateException(ops + ": operation " + iter + " names id " + id + ", not in the database");
        return s;
    }

    /** Self-check: java -cp build common.LogReplay DIR NAME. Replays both logs and compares the
     *  last state with NAME_final_seq.txt, sequence by sequence. */
    public static void main(String[] args) throws IOException {
        String dir = args[0], name = args[1];
        Log log = load(dir, name, "_ops.txt");
        Log nc = load(dir, name, "_ops_nocancel.txt");
        List<Sequence> fin = DatasetLoader.loadDataset(Paths.get(dir, name + "_final_seq.txt").toString(),
                Paths.get(dir, name + "_eui.txt").toString());
        List<String> a = new ArrayList<>(), f = new ArrayList<>();
        for (Sequence s : log.last) a.add(text(s));
        for (Sequence s : fin) f.add(text(s));
        a.sort(null); f.sort(null);
        int kinds[] = new int[3];
        for (MixedPlan.Op op : log.plan) kinds[op.kind.ordinal()]++;
        System.out.printf("%s: D_init %d, %d operations (%d INS, %d DEL, %d MOD), %d after the last; "
                        + "final file %d; without cancellations %d operations, %d after the last%n",
                name, log.dInit.size(), log.plan.size(), kinds[0], kinds[1], kinds[2], log.last.size(), fin.size(),
                nc.plan.size(), nc.last.size());
        if (!a.equals(f)) {
            System.out.println("MISMATCH: the replayed database differs from the final file");
            System.exit(1);
        }
        System.out.println("the replayed database equals the final file, sequence by sequence");
    }

    private static String text(Sequence s) {
        StringBuilder sb = new StringBuilder();
        for (QItemset is : s.itemsets) {
            for (QItem q : is.items) sb.append(q.id).append(':').append(q.utility).append(' ');
            sb.append("| ");
        }
        return sb.toString();
    }
}
