package common;

import java.util.List;

/**
 * The databases this study measures, with the thresholds each one is measured at.
 *
 * This is the single place the thresholds live. They used to be written out twice, once
 * here in the runner's own table and once in verify.CorrectnessVerifier, and the second
 * copy silently went stale: it still held the inherited pre-release values, so every
 * correctness artefact was produced at an operating point the study had stopped using, and
 * nothing reported a problem. A table duplicated is a table that will disagree with itself.
 *
 * AutomatedExperimentRunner lives in the default package and can read this; the reverse is
 * not possible, which is why the table sits here rather than there.
 *
 * The list must name every database that will be run, not just the ones already set up. A
 * table that quietly lags behind the data makes "all databases were calibrated" false while
 * looking complete, so entries that are not ready are present and marked rather than absent.
 *
 * Thresholds are ratios of total utility. They stay well defined across releases but not
 * comparable: the pinned release changed the shape of the utility distribution, so the same
 * ratio selects a different number of patterns. Each sweep below was re-chosen with
 * experiments.ThresholdScan; the raw search output is in results/thresholds/.
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
         * from the study. Unlike those, BMS1 is perfectly well behaved at half the data, so it
         * keeps SC1 rather than leaving.
         *
         * The submitted SC3 already covers only KOSARAK, LEVIATHAN and SIGN, omitting BIBLE,
         * BMS1 and FIFA without saying why. This makes that decision explicit and checkable.
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
         * This study used a flat 50% for every database instead, and that quietly broke the
         * growth experiment twice over. It made the incremental stream half the database
         * everywhere, so the database only ever doubled; and on KOSARAK it replaced a
         * tenfold growth with a twofold one. Across SC2 and SC5 that left rescan_count at
         * zero in 15 of 18 cells, and rescan_count is what measures LUT_dyn. An experiment
         * whose measured quantity is zero almost everywhere is not measuring.
         *
         * Five of the eight below are Yan's OD.Num verbatim, so those are directly
         * comparable with their figures. KOSARAK is this study's full release, 990002
         * sequences against their 10000, so it takes their Kosarak10k structure scaled by a
         * hundred: 1000/9000/90 becomes 90002/900000/9000. C8T1S5I8N5K and TAFENG are new
         * here and follow the same rule, a round incremental stream of 20000, which is what
         * Yan used for BMS at almost exactly TAFENG's size.
         */
        public final int dInitCount;

        Entry(String name, int dInitCount, double defaultUut, double[] sweep,
              boolean calibrated, String note) {
            this(name, dInitCount, defaultUut, sweep, calibrated, note, true);
        }

        Entry(String name, int dInitCount, double defaultUut, double[] sweep,
              boolean calibrated, String note, boolean fullScopeOk) {
            this.name = name; this.dInitCount = dInitCount;
            this.defaultUut = defaultUut; this.sweep = sweep;
            this.calibrated = calibrated; this.note = note; this.fullScopeOk = fullScopeOk;
        }
    }

    private static final List<Entry> ALL = List.of(
        // Calibrated on D_init. Patterns obtained, lowest threshold first.
        new Entry("SIGN",      230, 0.06215, new double[]{0.04499, 0.06215, 0.07634, 0.08585},
                true,  "3288 612 167 76"),
        new Entry("LEVIATHAN", 2834, 0.01553, new double[]{0.01465, 0.01553, 0.01647, 0.01799},
                true,  "133 107 88 63"),
        new Entry("BIBLE",     21369, 0.02428, new double[]{0.01976, 0.02428, 0.02982, 0.03557},
                true,  "357 194 104 54"),
        new Entry("BMS1",      39601, 0.01442, new double[]{0.01421, 0.01442, 0.01575, 0.01771},
                true,  "20 18 16 14; SC1 only, unmineable on the whole database", false),

        new Entry("FIFA",      10450, 0.059637,
                new double[]{0.056234, 0.059637, 0.067073, 0.076000},
                true,  "40 17 5 1"),

        // The submitted counts {12, 6, 5, 5} are unusable on this release: 6 and 5 both
        // land at 0.030, so three levels share one threshold and the sweep shows two
        // operating points wearing four labels. Re-chosen for the widest spread the
        // database allows; 0.019 already exceeds three billion candidates, so twelve
        // patterns is the ceiling rather than a preference.
        new Entry("KOSARAK",   90002, 0.025,
                new double[]{0.020, 0.025, 0.028, 0.030},
                true,  "12 9 7 5; re-chosen, the submitted ladder collapses to two "
                     + "distinct thresholds on this release"),

        // Added by the pinned release. The one database here that reaches within-event
        // extension, at 7.97 items per event; the other seven hold exactly one item per
        // event and exercise across-event extension alone.
        // Moved down. The old sweep matched its targets on D_init and collapsed on the whole
        // database: 0.00152 returns 153 patterns on the half and 1 on the whole, so SC2, SC5
        // and SC6 were comparing algorithms against an answer of one pattern. Every level here
        // keeps at least 23% of its D_init count. The band stays narrow - six percent of
        // threshold still separates 313 patterns from 38 - which is why the sweep spans only
        // 2.3x rather than the 43x SIGN manages: anything above 0.00130 collapses again.
        new Entry("C8T1S5I8N5K",          27133, 0.00120,
                new double[]{0.00115, 0.00120, 0.00125, 0.00130},
                true,  "6337 4894 3695 2741 on D_init; 3036 2110 1241 640 on the whole"),

        // The only database whose utilities are measured on both sides: quantities from
        // Ta-Feng's AMOUNT, profits from SALES_PRICE / AMOUNT. It is also the one a
        // compared algorithm already measured on, so including it is not a dataset
        // chosen to suit this work. Every probe in the sweep finishes in under a second.
        new Entry("TAFENG",               40665, 0.003557,
                new double[]{0.003162, 0.003557, 0.004000, 0.005060},
                true,  "9 6 5 4; these are what ThresholdScan itself converges to, so "
                     + "re-running it reproduces this row. Ten patterns is the ceiling, "
                     + "reached at 0.0029; 0.0028 exceeds the budget")

        // ONLINE_RETAIL_II_ALL and ONLINE_RETAIL_II_QTY were measured and dropped, not
        // forgotten. QTY gives each invoice line its own event, so its sequences average
        // 139.66 events against 2.5 to 36 elsewhere: every threshold that completes
        // returns nothing and every threshold low enough to return a pattern exceeds
        // three billion candidates, with the two bracketing 0.15 and 0.16. Rebuilding it
        // per customer-month cut that to 32.28 events and made the last computable
        // threshold six hundred times cheaper, and it still returned nothing. ALL carries
        // generated utilities like seven of the nine and its within-event extension is
        // already covered by C8T1S5I8N5K, so it brought nothing the suite lacked. Both
        // stay in the dataset release; they are simply not measured here.
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
     * This study used 5. That is twenty times coarser, and it is why the separation between
     * Adaptive and Pre-HUSPM never appeared: on SIGN, the one database that rescans at all,
     * K=3 gives 5 against 5, K=5 gives 10 against 11, and K=10 gives 17 against 25. The gap
     * opens with K. At the K this study used, there was nothing to see.
     */
    public static final int BATCH_COUNT = 100;

    /**
     * Increment counts for the batch-size study, holding the stream fixed.
     *
     * Yan et al. Figs. 5-7 vary the size of each increment while "the total incremental
     * dataset size remains the same", and compare 100 against 67 among others. SC5 used
     * {3, 5, 10}, which is a different experiment from theirs and not comparable with it.
     */
    public static final int[] BATCH_COUNT_SWEEP = {100, 67, 50, 25};

    private Datasets() { }

    public static List<Entry> all() { return ALL; }

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
