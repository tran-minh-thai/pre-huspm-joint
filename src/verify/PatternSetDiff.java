package verify;

import java.util.Map;
import java.util.TreeSet;

/**
 * So sanh hai TAP MAU (khong phai hai so dem) va utility cua tung mau.
 *
 * Ban nop chi doi chieu SO LUONG HUSP giua thuat toan gia tang va remine-from-scratch.
 * Hai tap co the cung kich thuoc ma khac nhau, nen phep so sanh do khong du de chung
 * minh Proposition 2. Lop nay bao cao: so mau THIEU (co o ban tham chieu, khong co o
 * ban gia tang), so mau THUA, va sai lech utility lon nhat tren phan giao.
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

    /** Sai so so hoc chap nhan duoc khi cong don utility bang double. */
    public static final double UTILITY_TOLERANCE = 1e-6;

    public static PatternSetDiff of(Map<String, Double> reference, Map<String, Double> got) {
        int missing = 0, extra = 0;
        double maxGap = 0;
        String firstMissing = "";
        // Duyet theo thu tu tu dien de "mau thieu dau tien" on dinh giua cac lan chay.
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
