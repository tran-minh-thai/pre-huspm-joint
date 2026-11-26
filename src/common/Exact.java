package common;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Exact arithmetic for thresholds and rescan decisions.
 *
 * DatasetLoader turns every utility into an integer: each external utility is scaled by 10^d, d the
 * largest number of decimals in the database's _eui file, and every quantity must be an integer. Every
 * utility, every sum of utilities and every counter is then an integer held exactly by a double up to
 * 2^53, so insertion, deletion and a fresh mine reach the same value for the same pattern, whatever
 * the order of the additions and subtractions.
 *
 * A threshold is a ratio written in decimal (UUT, the lower threshold ratio) times an integer total.
 * Computed in binary floating point it can land on either side of an integer that a pattern's utility
 * equals. Here it is formed in decimal and rounded up: for an integer utility u and a real threshold
 * x, u >= x exactly when u >= ceil(x), so every comparison of a utility with a threshold compares two
 * integers. The rescan rules compare sums of ratios times integers, also in decimal.
 *
 * A ratio is read through Double.toString, the shortest decimal that rounds to the double, which is
 * the value as written in a configuration (0.00549 stays 0.00549).
 */
public final class Exact {

    private Exact() {}

    /** The decimal a double was written as. */
    public static BigDecimal dec(double x) {
        return new BigDecimal(Double.toString(x));
    }

    /** An integer-valued double as a decimal; refuses a value with a fraction. */
    public static BigDecimal integral(double v, String what) {
        if (v != Math.rint(v) || Math.abs(v) > 9.007199254740992E15)
            throw new IllegalStateException(what + " = " + v + " is not an integer held exactly; the utilities "
                    + "must be integers (DatasetLoader scales the external utilities)");
        return BigDecimal.valueOf((long) v);
    }

    /** ceil(ratio * total) for an integer total, as a double. */
    public static double ceilTimes(BigDecimal ratio, double total) {
        return ratio.multiply(integral(total, "total")).setScale(0, RoundingMode.CEILING).doubleValue();
    }

    /** ceil(ratio * total) for a ratio written as a double. */
    public static double ceilTimes(double ratio, double total) {
        return ceilTimes(dec(ratio), total);
    }

    /** a > b. */
    public static boolean exceeds(BigDecimal a, BigDecimal b) {
        return a.compareTo(b) > 0;
    }
}
