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

        Entry(String name, double defaultUut, double[] sweep, boolean calibrated, String note) {
            this.name = name; this.defaultUut = defaultUut; this.sweep = sweep;
            this.calibrated = calibrated; this.note = note;
        }
    }

    private static final List<Entry> ALL = List.of(
        // Calibrated on D_init. Patterns obtained, lowest threshold first.
        new Entry("SIGN",      0.06215, new double[]{0.04499, 0.06215, 0.07634, 0.08585},
                true,  "3288 612 167 76"),
        new Entry("LEVIATHAN", 0.01553, new double[]{0.01465, 0.01553, 0.01647, 0.01799},
                true,  "133 107 88 63"),
        new Entry("BIBLE",     0.02428, new double[]{0.01976, 0.02428, 0.02982, 0.03557},
                true,  "357 194 104 54"),
        new Entry("BMS1",      0.01442, new double[]{0.01421, 0.01442, 0.01575, 0.01771},
                true,  "20 18 16 14"),

        new Entry("FIFA",      0.059637,
                new double[]{0.056234, 0.059637, 0.067073, 0.076000},
                true,  "40 17 5 1"),

        // The submitted counts {12, 6, 5, 5} are unusable on this release: 6 and 5 both
        // land at 0.030, so three levels share one threshold and the sweep shows two
        // operating points wearing four labels. Re-chosen for the widest spread the
        // database allows; 0.019 already exceeds three billion candidates, so twelve
        // patterns is the ceiling rather than a preference.
        new Entry("KOSARAK",   0.025,
                new double[]{0.020, 0.025, 0.028, 0.030},
                true,  "12 9 7 5; re-chosen, the submitted ladder collapses to two "
                     + "distinct thresholds on this release"),

        // Added by the pinned release. The one database here that reaches within-event
        // extension, at 7.97 items per event; the other seven hold exactly one item per
        // event and exercise across-event extension alone.
        new Entry("C8T1S5I8N5K",          0.00152,
                new double[]{0.00149, 0.00152, 0.00155, 0.00158},
                true,  "313 153 79 38; note how narrow the band is, six percent of "
                     + "threshold separates 313 patterns from 38"),

        // The only database whose utilities are measured on both sides: quantities from
        // Ta-Feng's AMOUNT, profits from SALES_PRICE / AMOUNT. It is also the one a
        // compared algorithm already measured on, so including it is not a dataset
        // chosen to suit this work. Every probe in the sweep finishes in under a second.
        new Entry("TAFENG",               0.0035,
                new double[]{0.0029, 0.0035, 0.0045, 0.0050},
                true,  "10 6 5 4; ten is the ceiling, 0.0028 exceeds the budget")

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

    private Datasets() { }

    public static List<Entry> all() { return ALL; }

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
