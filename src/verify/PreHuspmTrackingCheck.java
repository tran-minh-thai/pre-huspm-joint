package verify;

import algorithms.PreHUSPMAdaptiveAlgorithm.PreHUSPMAdaptiveAlgo;
import algorithms.PreHUSPMAdaptiveAlgorithm.PreHUSPMAdaptiveAlgo.AblationMode;
import algorithms.PreHUSPMAlgorithm.PreHUSPMAlgo;
import common.Datasets;
import common.DatasetLoader;
import common.Pattern;
import common.Sequence;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The proposal's tracking in Pre-HUSPM's manner (PreHUSPMAdaptiveAlgo.Tracking.PREHUSPM) against the
 * buffer of the Pre-HUSPM implementation itself (PreHUSPMAlgorithm), on each database's D_init at its
 * default UUT and r_l:
 *   1. after the initial mine, the two track the same patterns, with the same utility and the same
 *      bound (Pre-HUSPM's SWU on the projected database);
 *   2. after the first SC2 increment, when neither rescans, every pattern both still track has the
 *      same utility and the same bound (each adds the pattern's utility and projected SWU in the batch).
 *
 *   java -Xmx24g -Xss64m -cp build verify.PreHuspmTrackingCheck [NAME ...]
 *
 * Exits 1 on any difference and prints the number of patterns compared.
 */
public final class PreHuspmTrackingCheck {

    private static final double KAPPA = 0.5, DELTA0 = 1e-4;

    public static void main(String[] args) throws IOException {
        List<String> names = new ArrayList<>();
        if (args.length == 0) for (Datasets.Entry e : Datasets.all()) names.add(e.name);
        else names.addAll(List.of(args));
        long compared = 0, after = 0;
        int bad = 0;
        for (String name : names) {
            Datasets.Entry e = Datasets.byName(name);
            List<Sequence> all = DatasetLoader.loadDataset(Paths.get("datasets", name + "_seq.txt").toString(),
                    Paths.get("datasets", name + "_eui.txt").toString());
            List<Sequence> init = new ArrayList<>(all.subList(0, e.dInitCount));
            double tu = 0;
            for (Sequence s : init) tu += s.sequenceUtility;

            PreHUSPMAlgo pre = new PreHUSPMAlgo(e.defaultUut, e.slMinRatio, false);
            pre.runInitialMining(new ArrayList<>(init), tu);
            PreHUSPMAdaptiveAlgo ours = new PreHUSPMAdaptiveAlgo(e.defaultUut, KAPPA, DELTA0, e.slMinRatio,
                    AblationMode.FULL, false);
            ours.tracking = PreHUSPMAdaptiveAlgo.Tracking.PREHUSPM;
            ours.runInitialMining(new ArrayList<>(init), tu);

            Map<String, double[]> p = buffer(pre), q = ours.exportTrackedWithBounds();
            int diff = diff(name + " initial mine", p, q, true);
            compared += p.size();
            bad += diff;

            // the SC2 increments in order, while the two take the same path: both rescan (the same
            // database mined again: the whole sets must agree) or both update directly (the
            // patterns both still track must agree); the walk stops when one rescans and the other not
            int rest = all.size() - e.dInitCount;
            int size = Math.max(1, rest / Datasets.BATCH_COUNT);
            List<Sequence> u = new ArrayList<>(init);
            double tu2 = tu;
            int direct = 0, rescans = 0, d2 = 0, steps = 0;
            for (int k = 0; k < Datasets.BATCH_COUNT && e.dInitCount + (k + 1) * size <= all.size(); k++) {
                List<Sequence> batch = new ArrayList<>(all.subList(e.dInitCount + k * size, e.dInitCount + (k + 1) * size));
                u.addAll(batch);
                for (Sequence s : batch) tu2 += s.sequenceUtility;
                int r0 = pre.rescanCount, r1 = ours.rescanCount;
                pre.runIncrementalMining(new ArrayList<>(batch), new ArrayList<>(u), tu2);
                ours.runIncrementalMining(new ArrayList<>(batch), new ArrayList<>(u), tu2);
                boolean pr = pre.rescanCount > r0, or = ours.rescanCount > r1;
                if (pr != or) break;
                steps++;
                Map<String, double[]> p2 = buffer(pre), q2 = ours.exportTrackedWithBounds();
                if (pr) {
                    rescans++;
                    d2 += diff(name + " increment " + (k + 1) + " (both rescan)", p2, q2, true);
                    after += p2.size();
                } else {
                    direct++;
                    Map<String, double[]> pc = new HashMap<>(), qc = new HashMap<>();
                    for (String key : p2.keySet()) if (q2.containsKey(key)) { pc.put(key, p2.get(key)); qc.put(key, q2.get(key)); }
                    d2 += diff(name + " increment " + (k + 1) + " (both direct, common patterns)", pc, qc, false);
                    after += pc.size();
                }
            }
            bad += d2;
            System.out.printf("%s: initial mine %d patterns, %d differing; then %d increments on the same path "
                    + "(%d both rescan, %d both direct), %d differing%n", name, p.size(), diff, steps, rescans, direct, d2);
        }
        System.out.printf("Pre-HUSPM's tracking on the proposal's miner against PreHUSPMAlgorithm: %d patterns after the "
                + "initial mines and %d over the increments compared, %d differing%n", compared, after, bad);
        System.exit(bad == 0 && compared > 0 ? 0 : 1);
    }

    private static Map<String, double[]> buffer(PreHUSPMAlgo a) {
        Map<String, double[]> m = new HashMap<>();
        for (Map.Entry<Pattern, double[]> e : a.bufferSnapshot().entrySet())
            m.put(e.getKey().toIntuitiveString(), new double[]{e.getValue()[1], e.getValue()[0]});   // {utility, bound}
        return m;
    }

    private static int diff(String what, Map<String, double[]> pre, Map<String, double[]> ours, boolean sameKeys) {
        int d = 0, shown = 0;
        if (sameKeys) {
            for (String k : pre.keySet()) if (!ours.containsKey(k)) { d++; if (shown++ < 5) System.out.println("  " + what + ": only Pre-HUSPM tracks " + k); }
            for (String k : ours.keySet()) if (!pre.containsKey(k)) { d++; if (shown++ < 5) System.out.println("  " + what + ": only the proposal tracks " + k); }
        }
        for (Map.Entry<String, double[]> e : pre.entrySet()) {
            double[] o = ours.get(e.getKey());
            if (o == null) continue;
            double[] x = e.getValue();
            if (Math.abs(x[0] - o[0]) > 1e-6 || Math.abs(x[1] - o[1]) > 1e-6) {
                d++;
                if (shown++ < 5) System.out.printf("  %s: %s utility %.3f / %.3f, bound %.3f / %.3f (Pre-HUSPM / proposal)%n",
                        what, e.getKey(), x[0], o[0], x[1], o[1]);
            }
        }
        return d;
    }
}
