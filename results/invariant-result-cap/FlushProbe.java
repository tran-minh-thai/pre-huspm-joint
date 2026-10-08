import algorithms.PreHUSPMAdaptiveAlgorithm;
import common.*;
import java.util.*;
public class FlushProbe {
    public static void main(String[] a) throws Exception {
        String ds = a[0]; double sl = Double.parseDouble(a[1]); boolean cap = a[2].equals("default");
        common.Datasets.Entry e = null;
        for (common.Datasets.Entry x : common.Datasets.all()) if (x.name.equals(ds)) e = x;
        List<Sequence> full = DatasetLoader.loadDataset(a[3] + "/" + ds + "_seq.txt", a[3] + "/" + ds + "_eui.txt");
        double tu = 0; for (Sequence s : full) tu += s.sequenceUtility;
        PreHUSPMAdaptiveAlgorithm.PreHUSPMAdaptiveAlgo adp = new PreHUSPMAdaptiveAlgorithm.PreHUSPMAdaptiveAlgo(
            e.defaultUut, 0.5, 1e-4, sl, PreHUSPMAdaptiveAlgorithm.PreHUSPMAdaptiveAlgo.AblationMode.FULL, false);
        if (!cap) adp.maxResultPatterns = Long.MAX_VALUE;
        long t0 = System.nanoTime();
        adp.runInitialMining(full, tu);
        System.out.printf("ds=%s sl=%s cap=%s uut=%s large=%d prelarge=%d candidates=%d sec=%.1f%n",
            ds, sl, a[2], e.defaultUut, adp.patternCount, adp.prelargeCount, adp.candidateCount, (System.nanoTime()-t0)/1e9);
    }
}
