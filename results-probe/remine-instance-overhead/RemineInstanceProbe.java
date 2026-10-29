import algorithms.PreHUSPMAdaptiveAlgorithm.PreHUSPMAdaptiveAlgo;
import common.DatasetLoader;
import common.Sequence;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Probe, development machine: where does remining at the threshold (Remine-own-UUT) spend the time it
 * takes beyond the proposal's mine on a stream where both make the same search at every increment?
 *
 *     java -cp build:results-probe/remine-instance-overhead RemineInstanceProbe SIGN [rounds]
 *
 * Over the SC2 stream of the database (D_init, then 100 even increments in file order, as the runner
 * builds it) it times, per increment:
 *   FRESH   runRemine on a new instance, as the runner's Remine-own-UUT does;
 *   REUSED  runRemine on one instance kept across increments (pools and scratch arrays stay allocated);
 *   METHOD  the proposal's increment (runIncrementalMining) and, inside it, its mine (mineMs).
 * A full collection runs between increments in every mode, as in the runner. Rounds alternate the
 * order (FRESH, REUSED, METHOD, then the reverse) so that a drift over the run does not favour one
 * mode. Prints the totals and the explored nodes, which must agree between the three.
 */
public class RemineInstanceProbe {
    static Object call(String name, Class<?>[] types, Object... args) throws Exception {
        Method m = AutomatedExperimentRunner.class.getDeclaredMethod(name, types);
        m.setAccessible(true);
        return m.invoke(null, args);
    }

    static double sum(List<Sequence> s) { double t = 0; for (Sequence x : s) t += x.sequenceUtility; return t; }

    @SuppressWarnings("unchecked")
    public static void main(String[] a) throws Exception {
        String db = a[0];
        int rounds = a.length > 1 ? Integer.parseInt(a[1]) : 2;
        Field f = AutomatedExperimentRunner.class.getDeclaredField("DATASETS");
        f.setAccessible(true);
        AutomatedExperimentRunner.DatasetConfig ds = null;
        for (AutomatedExperimentRunner.DatasetConfig d : (List<AutomatedExperimentRunner.DatasetConfig>) f.get(null))
            if (d.name.equals(db)) ds = d;
        if (ds == null) throw new IllegalArgumentException("unknown database " + db);
        String dir = System.getProperty("datasets", "datasets/");
        String[] order = {"FRESH", "REUSED", "METHOD"};
        double[] tot = new double[3];
        long[] nodes = new long[3];
        double methodMine = 0;
        for (int round = 0; round < rounds; round++) {
            for (int k = 0; k < 3; k++) {
                String mode = order[round % 2 == 0 ? k : 2 - k];
                List<Sequence> full = DatasetLoader.loadDataset(dir + db + "_seq.txt", dir + db + "_eui.txt");
                List<Sequence> dInit = (List<Sequence>) call("dInitFor",
                        new Class<?>[]{AutomatedExperimentRunner.DatasetConfig.class, List.class}, ds, full);
                List<Sequence> rest = (List<Sequence>) call("tailFrom", new Class<?>[]{List.class, int.class}, full, dInit.size());
                double[] w = (double[]) call("equalWeights", new Class<?>[]{int.class}, 100);
                List<List<Sequence>> batches = (List<List<Sequence>>) call("splitByWeights",
                        new Class<?>[]{List.class, double[].class}, rest, w);
                double ratio = (double) call("slRatioFor", new Class<?>[]{String.class}, db);
                List<Sequence> dSoFar = new ArrayList<>(dInit);
                double total = sum(dInit);
                PreHUSPMAdaptiveAlgo reused = null, adp = null;
                if (mode.equals("METHOD")) {
                    adp = new PreHUSPMAdaptiveAlgo(ds.defaultSu, AutomatedExperimentRunner.DEFAULT_KAPPA,
                            AutomatedExperimentRunner.DEFAULT_DELTA0, ratio, PreHUSPMAdaptiveAlgo.AblationMode.FULL, false);
                    AutomatedExperimentRunner.setFloorRule(adp, "Pre-HUSPM-Joint");
                    adp.floor = AutomatedExperimentRunner.floorOf("Pre-HUSPM-Joint");
                    adp.runInitialMining(dInit, total);
                } else {
                    PreHUSPMAdaptiveAlgo rm0 = new PreHUSPMAdaptiveAlgo(ds.defaultSu, AutomatedExperimentRunner.DEFAULT_KAPPA,
                            AutomatedExperimentRunner.DEFAULT_DELTA0, 1.0, PreHUSPMAdaptiveAlgo.AblationMode.FULL, false);
                    rm0.runInitialMining(dInit, total);
                    if (mode.equals("REUSED")) reused = rm0;
                }
                double t = 0; long n = 0, mine = 0;
                for (List<Sequence> delta : batches) {
                    if (delta.isEmpty()) continue;
                    dSoFar.addAll(delta);
                    total += sum(delta);
                    System.gc();
                    long t0 = System.nanoTime();
                    if (mode.equals("METHOD")) {
                        adp.runIncrementalMining(delta, dSoFar, total);
                        t += (System.nanoTime() - t0) / 1e6;
                        if (!Double.isNaN(adp.mineMs)) methodMine += adp.mineMs;
                        n += adp.exploredNodes;
                    } else {
                        PreHUSPMAdaptiveAlgo rm = mode.equals("REUSED") ? reused
                                : new PreHUSPMAdaptiveAlgo(ds.defaultSu, AutomatedExperimentRunner.DEFAULT_KAPPA,
                                        AutomatedExperimentRunner.DEFAULT_DELTA0, 1.0, PreHUSPMAdaptiveAlgo.AblationMode.FULL, false);
                        rm.runRemine(new ArrayList<>(dSoFar), total);
                        t += (System.nanoTime() - t0) / 1e6;
                        n += rm.exploredNodes;
                    }
                }
                int i = mode.equals("FRESH") ? 0 : mode.equals("REUSED") ? 1 : 2;
                tot[i] += t; nodes[i] = n;
                System.out.printf("round %d %-6s %.1f ms, explored nodes %d%n", round, mode, t, n);
            }
        }
        System.out.printf("over %d rounds: FRESH %.1f ms, REUSED %.1f ms, METHOD %.1f ms (its mines %.1f ms); "
                + "nodes equal: %b%n", rounds, tot[0] / rounds, tot[1] / rounds, tot[2] / rounds, methodMine / rounds,
                nodes[0] == nodes[1] && nodes[1] == nodes[2]);
    }
}
