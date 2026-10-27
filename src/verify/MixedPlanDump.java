package verify;

import common.DatasetLoader;
import common.Datasets;
import common.MixedPlan;
import common.Sequence;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.StringJoiner;

/**
 * Writes the operation plans of SC6, SC6D and SC6B as the runner builds them, so that a replay can
 * follow the same operations: one line per operation with the positions, in the loaded database, of
 * the sequences it inserts, deletes or replaces.
 *
 *   java -cp build verify.MixedPlanDump --out FILE [--datasets datasets/]
 *
 * It calls common.MixedPlan.build with the runner's arguments (AutomatedExperimentRunner
 * runMixedScenario): D_init the first Datasets.dInitCount sequences, one operation of
 * floor(|rest| / BATCH_COUNT) sequences, the counts of each scenario, the generator
 * Random(seed + name.hashCode()) and, for SC6B, Random(~seed + name.hashCode()) with the share 1 - r_l.
 * Those arguments are copied here, so a replay must check the plans against the measured rows
 * (the kind of every operation, and the decisions) before trusting them.
 */
public final class MixedPlanDump {

    static final long[] SEEDS = {42L, 1337L, 2024L, 31415L, 271828L};
    static final String[] DATABASES = {"SIGN", "LEVIATHAN", "C8T1S5I8N5K", "TAFENG"};

    public static void main(String[] args) throws IOException {
        String out = null, dir = "datasets/";
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--out")) out = args[++i];
            else if (args[i].equals("--datasets")) dir = args[++i];
            else throw new IllegalArgumentException("unknown argument " + args[i]);
        }
        if (out == null) throw new IllegalArgumentException("--out FILE is required");
        int lines = 0;
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(Path.of(out), StandardCharsets.UTF_8))) {
            w.println("scenario\tdataset\tseed\td_init\top_size\titer\tkind\tbeyond_budget\ta\tb");
            for (String db : DATABASES) {
                List<Sequence> full = DatasetLoader.loadDataset(dir + db + "_seq.txt", dir + db + "_eui.txt");
                Map<Sequence, Integer> pos = new IdentityHashMap<>();
                for (int i = 0; i < full.size(); i++) pos.put(full.get(i), i);
                int nInit = Datasets.dInitCount(db, full.size());
                List<Sequence> dInit = new ArrayList<>(full.subList(0, nInit));
                List<Sequence> rest = new ArrayList<>(full.subList(nInit, full.size()));
                int opSize = Math.max(1, rest.size() / Datasets.BATCH_COUNT);
                for (String sc : new String[]{"SC6", "SC6D", "SC6B"}) {
                    int ins = sc.equals("SC6D") ? 6 : 14, del = sc.equals("SC6D") ? 10 : 3, mod = sc.equals("SC6D") ? 4 : 3;
                    double share = sc.equals("SC6B") ? 1.0 - Datasets.slMinRatio(db) : 0.0;
                    for (long seed : SEEDS) {
                        Random rnd = new Random(seed + db.hashCode());
                        Random bigRnd = new Random(~seed + db.hashCode());
                        List<MixedPlan.Op> plan = MixedPlan.build(dInit, rest, opSize, rnd, ins, del, mod, share, bigRnd);
                        for (MixedPlan.Op op : plan) {
                            w.printf("%s\t%s\t%d\t%d\t%d\t%d\t%s\t%b\t%s\t%s%n", sc, db, seed, nInit, opSize, op.iter,
                                    op.kind, op.beyondBudget, positions(op.a, pos), positions(op.b, pos));
                            lines++;
                        }
                    }
                }
            }
        }
        System.out.println("operations written: " + lines);
    }

    private static String positions(List<Sequence> seqs, Map<Sequence, Integer> pos) {
        if (seqs == null) return "";
        StringJoiner j = new StringJoiner(" ");
        for (Sequence s : seqs) j.add(String.valueOf(pos.get(s)));
        return j.toString();
    }
}
