package common;

import java.util.List;

/**
 * How repetitive a database is, and what lower-threshold floor that suggests.
 *
 * The cost of mining is the number of candidates explored, and extending a prefix has to
 * consider every position at which the extending item occurs. So the quantity that governs
 * branching is not how long the sequences are, nor how many there are, but how often the
 * average item recurs.
 *
 * Measured on the eight databases of this study, against the cost of one mining pass at the
 * pre-large floor:
 *
 *   C8T1S5I8N5K   13.0    2.8 s      KOSARAK   194.3    210 s
 *   LEVIATHAN     21.9    1.6 s      FIFA      247.9    263 s
 *   TAFENG        34.2    0.4 s      BMS1      301.1    beyond three billion candidates
 *   BIBLE         56.6    3.2 s
 *   SIGN         142.2    1.3 s
 *
 * The split is clean, and no other structural measure separates those two groups. Sequence
 * length does not: the cheap group spans 13.4 to 52.0 and the expensive group 2.51 to 36.2,
 * fully interleaved. Nor does the database size, nor the item count, nor items per event.
 *
 * Eight points and one threshold is a weak basis for a constant, which is why this is only a
 * starting guess. The guarantee is the candidate budget on a rescan, which measures the work
 * instead of predicting it; this just saves the first few retries on databases whose shape
 * already says they will be needed.
 */
public final class Density {

    /**
     * Above this, mining at the usual floor stops being affordable on the measured suite.
     *
     * Stated on D_init, which is what this class sees and therefore the only scale the
     * constant may be stated on. Repetition over the whole database is a different scale
     * with no conversion factor: the ratio between the two runs from 0.53 to 0.71 across the
     * suite (FIFA is 137.5 on D_init against 247.9 on the whole database, KOSARAK 112.9
     * against 194.3). A constant read off whole-database values would leave such databases
     * at the configured floor.
     */
    public static final double REPETITION_HIGH = 90.0;

    private Density() { }

    /** Item occurrences per distinct item: |D| x average length / |I|. */
    public static double repetition(List<Sequence> db) {
        if (db == null || db.isEmpty()) return 0;
        long occurrences = 0;
        int maxId = 0;
        for (Sequence s : db)
            for (QItemset is : s.itemsets)
                for (QItem it : is.items) { occurrences++; if (it.id > maxId) maxId = it.id; }
        if (occurrences == 0) return 0;
        boolean[] seen = new boolean[maxId + 1];
        int distinct = 0;
        for (Sequence s : db)
            for (QItemset is : s.itemsets)
                for (QItem it : is.items)
                    if (!seen[it.id]) { seen[it.id] = true; distinct++; }
        return distinct == 0 ? 0 : (double) occurrences / distinct;
    }

    /**
     * A floor ratio to start from, given how repetitive the database is. Never lowers the
     * configured ratio: a database that looks easy is left exactly as configured, so this can
     * only ever narrow the pre-large band, which is sound for any ratio in (0,1).
     */
    public static double suggestFloor(double configured, double repetition) {
        if (repetition <= REPETITION_HIGH) return configured;
        // One point on this line is measured rather than chosen: BMS1, at 202.5 here, mines
        // at 0.92 in 57 s where 0.80 exceeds three billion candidates without finishing. The
        // boundary is the middle of the gap the suite leaves between 74.7 and 112.9. Between
        // and beyond them the line is straight because nothing measured argues for any other
        // shape - which is the whole reason the candidate and clock budgets exist, to catch
        // the databases this guess places wrongly.
        double raised = 0.85 + 0.07 * Math.min(1.0, (repetition - REPETITION_HIGH) / 120.0);
        return Math.max(configured, Math.min(0.95, raised));
    }
}
