package verify;

import java.util.Map;
import java.util.TreeSet;

/**
 * Compares two PATTERN SETS (not two counts) and the utility of each pattern.
 *
 * Two sets of the same size can still differ, so comparing HUSP counts cannot establish
 * that the incremental result equals remine-from-scratch. This class reports the number of
 * MISSING patterns (in the reference, not in the incremental result), the number of EXTRA
 * patterns, and the largest utility difference over the patterns both sets share.
 */
public final class PatternSetDiff {

    public final int missing;
    public final int extra;
    public final double maxUtilityGap;
    public final int refSize;
    public final int gotSize;
    public final String firstMissingExample;

    private PatternSetDiff(int missing, int extra, double maxUtilityGap,
                           int refSize, int gotSize, String firstMissingExample) {
        this.missing = missing;
        this.extra = extra;
        this.maxUtilityGap = maxUtilityGap;
        this.refSize = refSize;
        this.gotSize = gotSize;
        this.firstMissingExample = firstMissingExample;
    }

    public boolean isClean() {
        return missing == 0 && extra == 0 && maxUtilityGap <= UTILITY_TOLERANCE;
    }

    /** Accepted rounding error when utilities are accumulated in double precision. */
    public static final double UTILITY_TOLERANCE = 1e-6;

    public static PatternSetDiff of(Map<String, Double> reference, Map<String, Double> got) {
        int missing = 0, extra = 0;
        double maxGap = 0;
        String firstMissing = "";
        // Iterate in lexicographic order so the "first missing pattern" is stable across runs.
        for (String key : new TreeSet<>(reference.keySet())) {
            Double g = got.get(key);
            if (g == null) {
                missing++;
                if (firstMissing.isEmpty()) firstMissing = key;
            } else {
                double gap = Math.abs(g - reference.get(key));
                if (gap > maxGap) maxGap = gap;
            }
        }
        for (String k : got.keySet()) if (!reference.containsKey(k)) extra++;
        return new PatternSetDiff(missing, extra, maxGap, reference.size(), got.size(), firstMissing);
    }

    @Override
    public String toString() {
        return String.format("ref=%d got=%d missing=%d extra=%d maxUtilGap=%.6f%s",
                refSize, gotSize, missing, extra, maxUtilityGap,
                firstMissingExample.isEmpty() ? "" : " firstMissing=<" + firstMissingExample + ">");
    }
}
