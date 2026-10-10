package verify;

import algorithms.PreHUSPMAdaptiveAlgorithm.PreHUSPMAdaptiveAlgo;
import common.DatasetLoader;
import common.Datasets;
import common.Sequence;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Prints the work counters and a digest of the pattern set of one static mine by the proposal,
 * at a database's default threshold and floor, on D_init or on the whole database:
 *
 *   java -Xmx24g -Xss64m -cp build verify.MinerCounters SIGN init|whole
 *
 * Two builds that print the same line ran the same search and returned the same patterns.
 * Uses only the miner's public interface, so it compiles against earlier versions of it.
 */
public final class MinerCounters {

    private MinerCounters() {}

    public static void main(String[] a) throws Exception {
        String name = a[0];
        boolean whole = a.length > 1 && a[1].equals("whole");
        String dir = System.getProperty("datasets.dir", "datasets");
        List<Sequence> all = DatasetLoader.loadDataset(Paths.get(dir, name + "_seq.txt").toString(),
                Paths.get(dir, name + "_eui.txt").toString());
        List<Sequence> db = whole ? all : new ArrayList<>(all.subList(0, Datasets.dInitCount(name, all.size())));
        double tu = 0;
        for (Sequence s : db) tu += s.sequenceUtility;
        PreHUSPMAdaptiveAlgo g = new PreHUSPMAdaptiveAlgo(Datasets.defaultUut(name), 0.5, 1e-4,
                Datasets.slMinRatio(name), PreHUSPMAdaptiveAlgo.AblationMode.FULL, false);
        g.runInitialMining(db, tu);
        Map<String, Double> m = new TreeMap<>(g.exportLargeAsStrings());
        m.putAll(g.exportPrelargeAsStrings());
        System.out.println(name + " " + (whole ? "whole" : "init") + " patterns=" + g.patternCount
                + " prelarge=" + g.prelargeCount + " explored=" + g.exploredNodes + " candidates=" + g.candidateCount
                + " prunedValue=" + g.prunedValueBound + " prunedIub=" + g.prunedIub + " digest=" + m.hashCode());
    }
}
