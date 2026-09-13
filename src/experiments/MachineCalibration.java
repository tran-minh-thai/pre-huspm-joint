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
 * Do he so quy doi giua may do MOI va so lieu cua ban nop.
 *
 * Ban nop ghi may do la Apple M5 / 32 GB / JDK 26. May do thuc te cua du an la
 * Dell XPS, Intel Core i7-10750H (6 nhan / 12 luong, 2.60 GHz), 64 GB RAM, Windows.
 * Moi so runtime va bo nho cua ban nop deu phai do lai; kich ban nay chi de UOC LUONG
 * NGAN SACH THOI GIAN cho dot chay lai, khong phai de bao cao trong bai.
 *
 * Do khai pha tinh tren D_init (50% dau) o nguong mac dinh, dung phan Adaptive - cung
 * cau hinh voi cot SC1_su=<default> trong results/v1-submitted/SC1_static.csv.
 *
 * Chay: java -Xmx24g -cp build experiments.MachineCalibration
 */
public final class MachineCalibration {

    private static final String DIR = "datasets/";
    private static final double RL = 0.8, KAPPA = 0.5, DELTA0 = 1e-4;

    /** dataset, UUT mac dinh, runtime_ms da bao cao tren may cu (SC1, Adaptive). */
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

        // Chan cung: heap lon hon RAM vat ly khong bao loi ngay ma thrashing paging file,
        // lam runtime phong len hang bac. He so k sinh ra tu do la rac. Da xay ra mot lan.
        //
        // Hang rao nay phai FAIL CLOSED. Ban dau no doc RAM bang reflection va nuot
        // exception, nen tren JDK 17 no im lang bo qua va van chay - dung kieu hong tệ
        // nhat: co ve nhu da kiem tra.
        double heapGb = Runtime.getRuntime().maxMemory() / 1073741824.0;
        double ramGb;
        try {
            com.sun.management.OperatingSystemMXBean os =
                    (com.sun.management.OperatingSystemMXBean)
                            java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            ramGb = os.getTotalMemorySize() / 1073741824.0;
        } catch (Throwable t) {
            System.err.println("DUNG: khong doc duoc dung luong RAM vat ly (" + t + "). "
                    + "Khong the xac nhan heap co vua hay khong, nen khong chay. "
                    + "Dat -Dcalib.force=true de bo qua neu ban chac chan.");
            if (!Boolean.getBoolean("calib.force")) System.exit(2);
            ramGb = Double.MAX_VALUE;
        }
        System.out.printf("RAM vat ly %.1f GB | heap toi da %.1f GB%n", ramGb, heapGb);
        if (heapGb > ramGb - 4) {
            System.err.printf("DUNG: heap %.1f GB khong vua trong %.1f GB RAM. JVM se KHONG "
                    + "bao loi ngay ma thrashing paging file, lam moi so thoi gian phong len "
                    + "hang bac roi sap bang native allocation failure. Moi so do o day deu "
                    + "vo gia tri. Chay tren may do, hoac ha -Xmx.%n", heapGb, ramGb);
            System.exit(2);
        }

        System.out.printf("JDK %s | %s | %d nhan | heap toi da %.1f GB%n",
                System.getProperty("java.version"), System.getProperty("os.name"),
                Runtime.getRuntime().availableProcessors(),
                Runtime.getRuntime().maxMemory() / 1073741824.0);
        System.out.printf("%n%-11s %7s %10s %10s %9s %8s %9s%n",
                "dataset", "UUT", "may nay", "do lech", "may cu", "ty le", "HUSP");

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
                System.out.printf("%-11s  khong nap duoc: %s%n", name, e.getMessage());
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
