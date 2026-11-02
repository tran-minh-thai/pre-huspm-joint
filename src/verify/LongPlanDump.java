package verify;

import common.DatasetLoader;
import common.Datasets;
import common.MixedPlan;
import common.QItem;
import common.QItemset;
import common.Sequence;
import common.StreamPlans;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.StringJoiner;
import java.util.TreeSet;

/**
 * Writes the operation plans of the long streams (SC8, SC8P, SC9, SC10, SC10H) as the runner builds
 * them, so that a replay can follow the same operations. SC10 changes sequences in place, so a
 * changed sequence is not in the file; every sequence is therefore written by its content as the
 * replay needs it: its utility and its items, "utility|item,item,..." separated by ';'.
 *
 *   java -cp build verify.LongPlanDump --out FILE [--datasets datasets/] [--scenarios SC8,SC9] [--db NAME,...]
 *
 * It calls common.StreamPlans.build with the runner's arguments (AutomatedExperimentRunner
 * runLongScenario): D_init the first Datasets.dInitCount sequences, the first three seeds and the
 * generator Random(seed + name.hashCode()). Those arguments are copied here, so a replay must check
 * the plans against the measured rows (the decisions of every operation) before trusting them.
 */
public final class LongPlanDump {

    static final long[] SEEDS = {42L, 1337L, 2024L};

    public static void main(String[] args) throws IOException {
        String out = null, dir = "datasets/";
        String[] scenarios = {"SC8", "SC8P", "SC9", "SC10", "SC10H"};
        String[] databases = {"SIGN", "LEVIATHAN", "C8T1S5I8N5K", "TAFENG"};
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--out")) out = args[++i];
            else if (args[i].equals("--datasets")) dir = args[++i];
            else if (args[i].equals("--scenarios")) scenarios = args[++i].split(",");
            else if (args[i].equals("--db")) databases = args[++i].split(",");
            else throw new IllegalArgumentException("unknown argument " + args[i]);
        }
        if (out == null) throw new IllegalArgumentException("--out FILE is required");
        int lines = 0;
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(Path.of(out), StandardCharsets.UTF_8))) {
            w.println("scenario\tdataset\tseed\td_init\top_size\titer\tkind\ta\tb");
            for (String db : databases) {
                List<Sequence> full = DatasetLoader.loadDataset(dir + db + "_seq.txt", dir + db + "_eui.txt");
                int nInit = Datasets.dInitCount(db, full.size());
                List<Sequence> dInit = new ArrayList<>(full.subList(0, nInit));
                List<Sequence> rest = new ArrayList<>(full.subList(nInit, full.size()));
                for (String sc : scenarios) {
                    if (!StreamPlans.fits(sc, rest)) {
                        System.out.println(sc + " " + db + ": no stream (" + rest.size() + " sequences after D_init)");
                        continue;
                    }
                    for (long seed : SEEDS) {
                        Random rnd = new Random(seed + db.hashCode());
                        for (MixedPlan.Op op : StreamPlans.build(sc, dInit, rest, rnd)) {
                            w.printf("%s\t%s\t%d\t%d\t%d\t%d\t%s\t%s\t%s%n", sc, db, seed, nInit,
                                    StreamPlans.opSize(rest), op.iter, op.kind, content(op.a), content(op.b));
                            lines++;
                        }
                    }
                }
            }
        }
        System.out.println("operations written: " + lines);
    }

    private static String content(List<Sequence> seqs) {
        if (seqs == null) return "";
        StringJoiner j = new StringJoiner(";");
        for (Sequence s : seqs) {
            TreeSet<Integer> items = new TreeSet<>();
            for (QItemset is : s.itemsets) for (QItem q : is.items) items.add(q.id);
            StringJoiner it = new StringJoiner(",");
            for (int i : items) it.add(String.valueOf(i));
            j.add(Double.toString(s.sequenceUtility) + "|" + it);
        }
        return j.toString();
    }
}
