package verify;

import common.DatasetLoader;
import common.Datasets;
import common.QItem;
import common.QItemset;
import common.Sequence;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Checks the per-sequence data that the harness drops before the static mines and the streams of
 * the baselines that build it only when it is missing (AutomatedExperimentRunner.freshSequences).
 *
 *   java -cp build verify.DerivedDataCheck [--datasets datasets/] [--db SIGN,TAFENG]
 *
 * For every sequence of every database (all of Datasets.all() by default):
 *
 *   1. utilityFromItems() equals the sequence utility the loader stored, bit for bit, so that
 *      Pre-HUSPM, which now computes u(s) itself, mines with the same values as before;
 *   2. clearDerived() leaves no remaining utilities and no flat arrays, and keeps the sequence utility;
 *   3. rebuilding after clearDerived() gives the same remaining utilities and flat arrays, bit for bit.
 *
 * Control: the same comparison as 1 with the utilities summed left to right. Where that order gives a
 * different double on some sequence, check 1 is shown able to tell a summation order apart; the count
 * is printed so a database where it cannot is visible. Exits 1 on any failure.
 */
public final class DerivedDataCheck {

    public static void main(String[] args) throws Exception {
        String dir = "datasets/";
        List<String> names = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--datasets")) dir = args[++i];
            else if (args[i].equals("--db")) names.addAll(Arrays.asList(args[++i].split(",")));
            else throw new IllegalArgumentException("unknown argument " + args[i]);
        }
        if (names.isEmpty()) for (Datasets.Entry e : Datasets.all()) names.add(e.name);

        long failures = 0, sequences = 0, orderSensitive = 0;
        for (String name : names) {
            List<Sequence> db = DatasetLoader.loadDataset(Paths.get(dir, name + "_seq.txt").toString(),
                    Paths.get(dir, name + "_eui.txt").toString());
            long utilBad = 0, clearBad = 0, rebuildBad = 0, ltr = 0;
            for (Sequence s : db) {
                double stored = s.sequenceUtility;
                if (Double.doubleToRawLongBits(s.utilityFromItems()) != Double.doubleToRawLongBits(stored)) utilBad++;
                double left = 0;
                for (QItemset is : s.itemsets) for (QItem q : is.items) left += q.utility;
                if (Double.doubleToRawLongBits(left) != Double.doubleToRawLongBits(stored)) ltr++;

                s.buildFlatRepresentation();
                List<List<Double>> remBefore = new ArrayList<>();
                for (List<Double> l : s.remainingUtilities) remBefore.add(new ArrayList<>(l));
                int[] items = s.flatItems.clone();
                double[] utils = s.flatUtilities.clone(), rest = s.flatRemainingUtility.clone();
                boolean[] ends = s.isItemsetEnd.clone();

                s.clearDerived();
                if (!s.remainingUtilities.isEmpty() || s.flatItems != null || s.flatUtilities != null
                        || s.flatRemainingUtility != null || s.isItemsetEnd != null || s.flatLen != 0
                        || Double.doubleToRawLongBits(s.sequenceUtility) != Double.doubleToRawLongBits(stored)) clearBad++;

                s.calculateRemainingUtilities();
                s.buildFlatRepresentation();
                if (!remBefore.equals(s.remainingUtilities) || !Arrays.equals(items, s.flatItems)
                        || !Arrays.equals(utils, s.flatUtilities) || !Arrays.equals(rest, s.flatRemainingUtility)
                        || !Arrays.equals(ends, s.isItemsetEnd)
                        || Double.doubleToRawLongBits(s.sequenceUtility) != Double.doubleToRawLongBits(stored)) rebuildBad++;
            }
            sequences += db.size();
            orderSensitive += ltr;
            long bad = utilBad + clearBad + rebuildBad;
            failures += bad;
            System.out.printf("%-24s sequences %8d | utilityFromItems differs %d | clearDerived wrong %d | rebuild differs %d"
                    + " | control: left-to-right sum differs on %d%n", name, db.size(), utilBad, clearBad, rebuildBad, ltr);
        }
        System.out.printf("total: %d databases, %d sequences, %d failures; the control order differs on %d sequences%n",
                names.size(), sequences, failures, orderSensitive);
        if (orderSensitive == 0) System.out.println("note: no sequence tells the two summation orders apart; check 1 is not shown discriminating");
        System.exit(failures == 0 && sequences > 0 ? 0 : 1);
    }
}
