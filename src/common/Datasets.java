package common;

import java.util.List;
import java.util.Set;

/**
 * The databases this study measures, with the thresholds each one is measured at.
 *
 * This is the single place the thresholds live. Runners and verifiers read them from here
 * rather than keeping their own copy, because a duplicated table drifts out of agreement
 * with itself without any error being reported.
 *
 * AutomatedExperimentRunner lives in the default package and can read this; the reverse is
 * not possible, which is why the table sits here rather than there.
 *
 * The list must name every database that will be run, not just the ones already set up. A
 * table that quietly lags behind the data makes "all databases were calibrated" false while
 * looking complete, so entries that are not ready are present and marked rather than absent.
 *
 * Thresholds are ratios of total utility. They are well defined on any release of the data
 * but not comparable across releases: a different utility distribution makes the same ratio
 * select a different number of patterns. Each sweep below is chosen on the pinned release
 * with experiments.ThresholdScan; the raw search output is in results/thresholds/.
 */
public final class Datasets {

    /** One database: its name, the threshold it is measured at, and the sweep around it. */
    public static final class Entry {
        public final String name;
        public final double defaultUut;
        public final double[] sweep;
        /** Whether these thresholds were measured against the pinned release. */
        public final boolean calibrated;
        /** Pattern counts the sweep produced, or why there are none yet. */
        public final String note;
        /**
         * Whether this database can be measured on the WHOLE of itself, not only on D_init.
         *
         * SC1 is the only scenario that stays on D_init. SC3 and SC4 start on the whole
         * database and SC2, SC5 and SC6 grow to it, so a database that cannot be mined there
         * is measurable in SC1 alone however well it behaves on the half.
         *
         * BMS1 is that case and it is not a near miss. On D_init it returns 18 patterns in
         * 3.5 s; on the whole database no threshold works at all. 0.030 exceeds three billion
         * candidates and 0.032 returns the empty set in 1.8 s, so the region that holds
         * patterns lies entirely behind the wall - the shape that removed ONLINE_RETAIL_II
         * from the study. Unlike that database, BMS1 is well behaved on D_init, so it stays
         * in SC1.
         *
         * This flag makes the choice of databases for the whole-database scenarios explicit
         * and checkable.
         */
        public final boolean fullScopeOk;
        /**
         * How many sequences D_init holds, as a count rather than a fraction.
         *
         * Yan et al. (J Supercomputing 2025, 81:81), Table 16, give this per database as
         * OD.Num and it is not half of anything: 230 of SIGN's 730, but 1000 of
         * Kosarak10k's 10000. The remainder is always a round number - 500, 3000, 10000,
         * 15000, 9000, 20000 - which is what fixes the odd-looking counts here.
         *
         * A flat 50% split would not reproduce this. It makes the incremental stream half
         * the database everywhere, so the database only ever doubles, and on KOSARAK it
         * turns a tenfold growth into a twofold one. The growth scenarios then almost never
         * rescan, and rescan_count is what measures LUT_dyn.
         *
         * Five of the eight below are Yan's OD.Num verbatim, so those are directly
         * comparable with their figures. KOSARAK is this study's full release, 990002
         * sequences against their 10000, so it takes their Kosarak10k structure scaled by a
         * hundred: 1000/9000/90 becomes 90002/900000/9000. C8T1S5I8N5K and TAFENG are new
         * here and follow the same rule, a round incremental stream of 20000, which is what
         * Yan used for BMS at almost exactly TAFENG's size.
         */
        public final int dInitCount;
        /**
         * The pre-large floor for this database, as a fraction of Su.
         *
         * Sl_min = slMinRatio * Su is the lowest LUT_dyn may fall to, so it is the threshold
         * the algorithm actually mines at once the adaptive lower bound has settled. 0.8 on
         * all eight databases.
         *
         * KOSARAK was set to 0.85 while the miner had no irrelevant item pruning: 0.8 x its
         * lowest threshold then exceeded three billion candidates. With the pruning, the
         * calibration on the measurement machine (results/calibration, rule 2) mines KOSARAK's
         * D_init at its lowest threshold and the whole database at its default threshold,
         * both at 0.8, in 0.9 s and 14.3 s, so the eight databases share one value.
         *
         * Not higher than needed because a higher floor is not free: it thins the pre-large
         * buffer and buys rescans, which is what Yan et al. warn of when they write that too
         * large an Sl "will waste a lot of time by rescanning the database too many times".
         * Their section 6.3.1 treats Sl as a per-database choice throughout, so carrying one
         * value per database here follows their method rather than departing from it.
         *
         * PreHUSPMAdaptiveAlgorithm raises the floor by 0.05 by itself whenever a mining
         * attempt exceeds its budget, but only after paying for the attempt that fails; a
         * per-database value here starts it where that retry would land.
         */
        public final double slMinRatio;

        Entry(String name, int dInitCount, double defaultUut, double[] sweep,
              boolean calibrated, String note) {
            this(name, dInitCount, defaultUut, sweep, calibrated, note, true, DEFAULT_SL_MIN_RATIO);
        }

        Entry(String name, int dInitCount, double defaultUut, double[] sweep,
              boolean calibrated, String note, boolean fullScopeOk) {
            this(name, dInitCount, defaultUut, sweep, calibrated, note, fullScopeOk,
                 DEFAULT_SL_MIN_RATIO);
        }

        Entry(String name, int dInitCount, double defaultUut, double[] sweep,
              boolean calibrated, String note, boolean fullScopeOk, double slMinRatio) {
            this.name = name; this.dInitCount = dInitCount;
            this.defaultUut = defaultUut; this.sweep = sweep;
            this.calibrated = calibrated; this.note = note; this.fullScopeOk = fullScopeOk;
            this.slMinRatio = slMinRatio;
        }
    }

    /**
     * Databases whose item ids are compacted when they are loaded.
     *
     * An item id is a label. Every miner here also uses it as an array index, sizing its
     * per-item scratch arrays by the largest id, so a database whose ids are spread thinly
     * pays for ids that do not exist. C8T1S5I8N5K draws its 68,240 items from ids up to
     * 4,999,999, so each per-item array is 73 times longer than the alphabet. On D_init,
     * compacting takes the proposal from 784 MB to 46 and Pre-HUSPM from 357 MB to 162,
     * with the same patterns: most of what either held there was such arrays rather than
     * its own state.
     *
     * Compaction replaces each id by its rank among the ids of the _eui file, 1 for the
     * smallest. The order of the items is unchanged, so every miner meets them in the same
     * order and returns the same patterns under the new labels. The seven other databases
     * are left as they are: their ids are already close to dense, so compacting them would
     * change their labels and nothing else.
     */
    private static final Set<String> COMPACT_ITEM_IDS = Set.of("C8T1S5I8N5K");

    /** Whether a database's item ids are replaced by their ranks when it is loaded. */
    public static boolean compactItemIds(String name) {
        return name != null && COMPACT_ITEM_IDS.contains(name);
    }

    /** The pre-large floor used wherever a database does not need its own. */
    public static final double DEFAULT_SL_MIN_RATIO = 0.8;

    private static final List<Entry> ALL = List.of(
        // Calibrated on D_init. Patterns obtained, lowest threshold first.
        new Entry("SIGN",      230, 0.065911, new double[]{0.049134, 0.065911, 0.083371, 0.091052},
                true,  "2814 583 140 79"),
        new Entry("LEVIATHAN", 2834, 0.015530, new double[]{0.014644, 0.015530, 0.016470, 0.017987},
                true,  "136 107 89 63"),
        new Entry("BIBLE",     21369, 0.022895, new double[]{0.018100, 0.022895, 0.028960, 0.032570},
                true,  "376 192 96 58"),
        new Entry("BMS1",      39601, 0.013597, new double[]{0.012821, 0.013597, 0.015293, 0.016218},
                true,  "22 21 18 15; SC1 only, unmineable on the whole database", false),

        new Entry("FIFA",      10450, 0.059637,
                new double[]{0.056234, 0.059637, 0.067073, 0.075436},
                true,  "42 18 5 1"),

        // Chosen for the widest spread the database allows: pattern counts of 6 and 5 both
        // land at 0.030, which would give two operating points under four labels, and 0.019
        // already exceeds three billion candidates, so about twelve patterns is the ceiling
        // rather than a preference.
        new Entry("KOSARAK",   90002, 0.025000,
                new double[]{0.022228, 0.025000, 0.028117, 0.031623},
                true,  "11 10 7 5",
                true),

        // The one database here that reaches within-event extension, at 7.97 items per
        // event; the other seven hold exactly one item per event and exercise across-event
        // extension alone.
        // Every level keeps at least 23% of its D_init count on the whole database. Higher
        // thresholds collapse there: 0.00152 returns 153 patterns on D_init and 1 on the
        // whole database, which would leave SC2, SC5 and SC6 comparing algorithms against a
        // one-pattern answer. The band is narrow - six percent of threshold separates 313
        // patterns from 38 - so the sweep spans only 2.3x rather than the 43x of SIGN:
        // anything above 0.00130 collapses again.
        new Entry("C8T1S5I8N5K",          27133, 0.001132,
                new double[]{0.001099, 0.001132, 0.001200, 0.001236},
                true,  "6090 5067 3427 2722 on D_init; the highest level keeps 1484 of its "
                     + "2722 when the database grows"),

        // The only database whose utilities are measured on both sides: quantities from
        // Ta-Feng's AMOUNT, profits from SALES_PRICE / AMOUNT. It is also the one a
        // compared algorithm already measured on, so including it is not a dataset
        // chosen to suit this work. Every probe in the sweep finishes in under a second.
        new Entry("TAFENG",               40665, 0.003557,
                new double[]{0.002812, 0.003557, 0.004499, 0.005691},
                true,  "10 6 5 3; the D_init cost gate passes here - 17 patterns in "
                     + "309 ms")

        // ONLINE_RETAIL_II_ALL and ONLINE_RETAIL_II_QTY are measured and excluded. QTY gives
        // each invoice line its own event, so its sequences average 139.66 events against
        // 2.5 to 36 elsewhere: every threshold that completes returns nothing and every
        // threshold low enough to return a pattern exceeds three billion candidates, with
        // the two bracketing 0.15 and 0.16. A per-customer-month build (32.28 events) makes
        // the last computable threshold six hundred times cheaper and still returns nothing.
        // ALL carries generated utilities like seven of the nine and its within-event
        // extension is already covered by C8T1S5I8N5K, so it adds nothing the suite lacks.
        // Both stay in the dataset release; they are not measured here.
    );

    /**
     * How many increments the stream is cut into.
     *
     * Yan et al. section 6.1: "each dataset is divided into an original dataset and 100 new
     * datasets". Their section 6.3.1 confirms the arithmetic - SIGN's 500 incremental
     * sequences arrive as "5 sequences per increment" - and their Fig. 7f reads the rescans
     * straight off the curve, "each inflection point on the curve corresponds to a database
     * rescan", which only works when the curve has enough points to have inflections.
     *
     * A coarse cut hides the mechanism. An increment inside the insertion safety budget needs
     * no rescan, and a finer cut puts more increments inside it. SC5 measures this directly:
     * on SIGN the share of increments that rescan falls from 96 percent at K=25 to 52 percent
     * at K=100, and the proposal's time falls from 0.97 of remine-from-scratch's to 0.52. At a
     * handful of increments every one rescans and the proposal is a remine.
     *
     * The rescan counts of every arm at every K are given per database in SC5's README,
     * generated from the result files.
     */
    public static final int BATCH_COUNT = 100;

    /**
     * Increment counts for the batch-size study, holding the stream fixed.
     *
     * Follows Yan et al. Figs. 5-7, which vary the size of each increment while "the total
     * incremental dataset size remains the same", and compare 100 against 67 among others.
     */
    public static final int[] BATCH_COUNT_SWEEP = {100, 67, 50, 25};

    private Datasets() { }

    public static List<Entry> all() { return ALL; }

    /** The pre-large floor for a database, as a fraction of Su. */
    public static double slMinRatio(String name) {
        for (Entry e : ALL) if (e.name.equals(name)) return e.slMinRatio;
        return DEFAULT_SL_MIN_RATIO;
    }

    /**
     * Size of D_init for a database, falling back to half for anything not in the table.
     *
     * Clamped to the database: a count larger than the data would otherwise leave the
     * incremental stream empty and turn SC2 into a second copy of SC1 without saying so.
     */
    public static int dInitCount(String name, int dbSize) {
        for (Entry e : ALL) {
            if (e.name.equals(name)) return Math.max(1, Math.min(e.dInitCount, dbSize));
        }
        return Math.max(1, dbSize / 2);
    }

    /** The entry for a database, or null when it is not one this study measures. */
    public static Entry byName(String name) {
        for (Entry e : ALL) if (e.name.equalsIgnoreCase(name)) return e;
        return null;
    }

    /**
     * The threshold a database is measured at. Refuses rather than guessing: a tool that
     * quietly falls back to some default would verify or measure at an operating point
     * nobody chose, which is the failure this class exists to prevent.
     */
    public static double defaultUut(String name) {
        Entry e = byName(name);
        if (e == null) {
            throw new IllegalArgumentException("no threshold for " + name
                    + "; databases are " + names());
        }
        return e.defaultUut;
    }

    public static String names() {
        StringBuilder sb = new StringBuilder();
        for (Entry e : ALL) { if (sb.length() > 0) sb.append(' '); sb.append(e.name); }
        return sb.toString();
    }
}
