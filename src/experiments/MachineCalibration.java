package experiments;

import algorithms.PreHUSPMAdaptiveAlgorithm.PreHUSPMAdaptiveAlgo;
import algorithms.PreHUSPMAdaptiveAlgorithm.PreHUSPMAdaptiveAlgo.AblationMode;
import common.DatasetLoader;
import common.Sequence;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Estimates the speed ratio between this machine and the reference runtimes in REF.
 *
 * REF holds Adaptive runtimes recorded on a different machine (the SC1_su=<default> rows
 * of results/v1-submitted/SC1_static.csv). The ratio only estimates the TIME BUDGET of a
 * full rerun on this machine; it is not a measurement to report.
 *
 * Measures static mining on the first half of each database at the listed threshold with
 * the Adaptive algorithm, the same configuration as the reference rows.
 *
 * Run: java -Xmx24g -cp build experiments.MachineCalibration
 */
public final class MachineCalibration {

    private static final String DIR = "datasets/";
    private static final double RL = 0.8, KAPPA = 0.5, DELTA0 = 1e-4;

    /** dataset, UUT, reference runtime_ms recorded on the other machine (SC1, Adaptive). */
    private static final Object[][] REF = {
            {"SIGN",      0.025, 1670L},
            {"LEVIATHAN", 0.060,  126L},
            {"FIFA",      0.050, null},
            {"BIBLE",     0.025, null},
            {"BMS1",      0.014, 3095L},
            {"KOSARAK",   0.030, null},
    };

    public static void main(String[] args) throws IOException {
        int repeats = Integer.getInteger("repeats", 3);

        // Hard stop: a heap larger than physical RAM does not fail at once; it thrashes the
        // paging file and inflates runtimes by orders of magnitude, so any ratio computed
        // from such a run is meaningless.
        //
        // This guard must FAIL CLOSED: if the RAM size cannot be read, it refuses to run
        // rather than silently skipping the check.
        double heapGb = Runtime.getRuntime().maxMemory() / 1073741824.0;
        double ramGb;
        try {
            com.sun.management.OperatingSystemMXBean os =
                    (com.sun.management.OperatingSystemMXBean)
                            java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            ramGb = os.getTotalMemorySize() / 1073741824.0;
        } catch (Throwable t) {
            System.err.println("STOP: cannot read the physical RAM size (" + t + "). "
                    + "Cannot confirm that the heap fits, so not running. "
                    + "Set -Dcalib.force=true to override if you are sure.");
            if (!Boolean.getBoolean("calib.force")) System.exit(2);
            ramGb = Double.MAX_VALUE;
        }
        System.out.printf("physical RAM %.1f GB | max heap %.1f GB%n", ramGb, heapGb);
        if (heapGb > ramGb - 4) {
            System.err.printf("STOP: heap %.1f GB does not fit in %.1f GB RAM. The JVM does NOT "
                    + "fail at once: it thrashes the paging file, inflates every timing by orders "
                    + "of magnitude and then dies with a native allocation failure, so no "
                    + "measurement here would be valid. Run on the measurement machine, or lower -Xmx.%n", heapGb, ramGb);
            System.exit(2);
        }

        System.out.printf("JDK %s | %s | %d CPUs | max heap %.1f GB%n",
                System.getProperty("java.version"), System.getProperty("os.name"),
                Runtime.getRuntime().availableProcessors(),
                Runtime.getRuntime().maxMemory() / 1073741824.0);
        System.out.printf("%n%-11s %7s %10s %10s %9s %8s %9s%n",
                "dataset", "UUT", "this host", "std dev", "reference", "ratio", "HUSP");

        for (Object[] r : REF) {
            String name = (String) r[0];
            double uut = (Double) r[1];
            Long old = (Long) r[2];

            List<Sequence> all;
            try {
                all = DatasetLoader.loadDataset(
                        Paths.get(DIR, name + "_seq.txt").toString(),
                        Paths.get(DIR, name + "_eui.txt").toString());
            } catch (IOException e) {
                System.out.printf("%-11s  cannot load: %s%n", name, e.getMessage());
                continue;
            }
            List<Sequence> init = new ArrayList<>(all.subList(0, all.size() / 2));
            double tsu = 0;
            for (Sequence s : init) tsu += s.sequenceUtility;

            long[] ms = new long[repeats];
            int husp = 0;
            for (int i = 0; i < repeats; i++) {
                System.gc();
                PreHUSPMAdaptiveAlgo a = new PreHUSPMAdaptiveAlgo(
                        uut, KAPPA, DELTA0, RL, AblationMode.FULL, false);
                long t0 = System.nanoTime();
                a.runInitialMining(new ArrayList<>(init), tsu);
                ms[i] = (System.nanoTime() - t0) / 1_000_000L;
                husp = a.patternCount;
            }
            double mean = 0;
            for (long v : ms) mean += v;
            mean /= repeats;
            double sd = 0;
            for (long v : ms) sd += (v - mean) * (v - mean);
            sd = Math.sqrt(sd / repeats);

            System.out.printf("%-11s %7.4f %9.0fms %9.0fms %8s %8s %9d%n",
                    name, uut, mean, sd,
                    old == null ? "-" : old + "ms",
                    old == null ? "-" : String.format("%.2fx", mean / old),
                    husp);
        }
    }
}
