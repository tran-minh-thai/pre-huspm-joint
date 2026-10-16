package experiments;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;

/**
 * Converts SPMF's high-utility sequence files (item[utility] tokens, -1 closing an element, -2 the
 * sequence, then SUtility:total) to this repository's format: NAME_seq.txt with item[quantity]
 * tokens and NAME_eui.txt with item:profit lines. Every profit is 1 and every quantity is the
 * occurrence's utility, so each occurrence keeps its utility exactly. The output is byte for byte
 * that of analysis/convert_spmf_husp.py.
 *
 *   java -cp build experiments.SpmfHuspConvert SRC_DIR OUT_DIR
 *
 * Fails when a line's utilities do not add up to its SUtility.
 */
public final class SpmfHuspConvert {

    static final Map<String, String> FILES = new LinkedHashMap<>();
    static {
        FILES.put("SIGN", "SIGN_sequence_utility.txt");
        FILES.put("FIFA", "FIFA_sequence_utility.txt");
        FILES.put("BIBLE", "BIBLE_sequence_utility.txt");
        FILES.put("KOSARAK10K", "kosarak10k_sequence_utility.txt");
        FILES.put("BMS", "BMS_sequence_utility.txt");
    }

    public static void main(String[] a) throws IOException {
        Path src = Paths.get(a[0]), out = Paths.get(a[1]);
        Files.createDirectories(out);
        int bad = 0;
        for (Map.Entry<String, String> f : FILES.entrySet()) {
            TreeSet<Long> items = new TreeSet<>();
            int n = 0, mism = 0;
            double tu = 0;
            try (BufferedReader r = Files.newBufferedReader(src.resolve(f.getValue()), StandardCharsets.UTF_8);
                 BufferedWriter w = Files.newBufferedWriter(out.resolve(f.getKey() + "_seq.txt"), StandardCharsets.UTF_8)) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.trim().isEmpty()) continue;
                    int at = line.indexOf("SUtility:");
                    if (at < 0) throw new IOException(f.getValue() + ": no SUtility on line " + (n + 1));
                    double su = Double.parseDouble(line.substring(at + 9).trim());
                    StringBuilder sb = new StringBuilder();
                    double total = 0;
                    for (String tok : line.substring(0, at).trim().split("\\s+")) {
                        if (tok.isEmpty()) continue;
                        if (sb.length() > 0) sb.append(' ');
                        if (tok.equals("-1") || tok.equals("-2")) { sb.append(tok); continue; }
                        int lb = tok.indexOf('[');
                        if (lb <= 0 || !tok.endsWith("]")) throw new IOException("unreadable token " + tok);
                        String item = tok.substring(0, lb), util = tok.substring(lb + 1, tok.length() - 1);
                        total += Double.parseDouble(util);
                        items.add(Long.parseLong(item));
                        sb.append(item).append('[').append(util).append(']');
                    }
                    if (Math.abs(total - su) > 1e-9) mism++;
                    w.write(sb.toString());
                    w.write('\n');
                    n++;
                    tu += total;
                }
            }
            try (BufferedWriter w = Files.newBufferedWriter(out.resolve(f.getKey() + "_eui.txt"), StandardCharsets.UTF_8)) {
                w.write("# itemID:profit, 1 for every item: the quantities are the occurrence utilities of "
                        + f.getValue() + "\n");
                for (long i : items) w.write(i + ":1\n");
            }
            bad += mism;
            System.out.printf("%s: %d sequences, %d items, total utility %.0f, lines whose utilities differ "
                    + "from SUtility: %d%n", f.getKey(), n, items.size(), tu, mism);
        }
        if (bad > 0) System.exit(1);
    }
}
