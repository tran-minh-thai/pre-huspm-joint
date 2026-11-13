// Prints the trace of HUSPSPAsPublished (the TRSU of Definition 4.11) at one node of one database file in the
// format of the published code:   java -cp <our classes>:<this class> verify.TraceNode FILE THRESHOLD "PREFIX"
package verify;
import common.Sequence;
import java.util.*;
public class TraceNode {
    public static void main(String[] a) throws Exception {
        List<String> specs = new ArrayList<>();
        for (String line : java.nio.file.Files.readAllLines(java.nio.file.Paths.get(a[0]))) {
            if (line.isBlank()) continue;
            StringBuilder b = new StringBuilder(); boolean first = true;
            for (String el : line.substring(0, line.indexOf("-2")).split("-1")) {
                if (el.isBlank()) continue;
                if (!first) b.append(" | "); first = false; b.append(el.trim());
            }
            specs.add(b.toString());
        }
        SyntheticDb.resetIds();
        List<Sequence> db = new ArrayList<>();
        for (String sp : specs) db.add(SyntheticDb.seq(sp));
        HUSPSPAsPublished h = new HUSPSPAsPublished(Double.parseDouble(a[1]));
        boolean[] on = {false};
        h.trace = s -> { if (s.startsWith("PG ")) on[0] = s.startsWith("PG " + a[2] + " "); if (on[0]) System.out.println(s); };
        h.run(db);
        System.out.println("patterns " + h.largeMap.size());
    }
}
