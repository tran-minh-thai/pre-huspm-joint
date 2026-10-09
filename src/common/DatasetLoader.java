package common;

import java.io.*;
import java.util.*;

/**
 * Loads a database in one of two forms:
 *  - loadDataset(seq, eui): every sequence in a single list.
 *  - loadBatches(seq, eui): the sequences split into batches at each
 *    '# --- BATCH n' marker line of the _seq.txt file.
 *
 * Data line format: tokens "id[utility]"; "-1" starts a new itemset and "-2" ends the
 * sequence. Lines starting with '#', '%' or '@' are comments or metadata.
 */
public class DatasetLoader {

    /** Loads the whole database as one flat list. */
    public static List<Sequence> loadDataset(String seqPath, String euiPath) throws IOException {
        List<List<Sequence>> batched = loadBatches(seqPath, euiPath);
        List<Sequence> flat = new ArrayList<>();
        for (List<Sequence> b : batched) flat.addAll(b);
        return flat;
    }

    /**
     * Loads the database split into batches at the "# --- BATCH ..." lines of the _seq file.
     * A file without batch markers is returned as a single batch.
     */
    public static List<List<Sequence>> loadBatches(String seqPath, String euiPath) throws IOException {
        Map<Integer, Double> externalUtilities = loadExternalUtilities(euiPath);
        Map<Integer, Integer> relabel = null;          // null: ids are used as they are
        if (Datasets.compactItemIds(databaseName(seqPath))) {
            // Rank among the ids of the _eui file, 1 for the smallest - see
            // Datasets.compactItemIds. Order preserving, so it changes labels and nothing else.
            List<Integer> ids = new ArrayList<>(externalUtilities.keySet());
            Collections.sort(ids);
            relabel = new HashMap<>(ids.size() * 2);
            Map<Integer, Double> ranked = new HashMap<>(ids.size() * 2);
            for (int r = 0; r < ids.size(); r++) {
                relabel.put(ids.get(r), r + 1);
                ranked.put(r + 1, externalUtilities.get(ids.get(r)));
            }
            externalUtilities = ranked;
        }
        List<List<Sequence>> batches = new ArrayList<>();
        List<Sequence> currentBatch = new ArrayList<>();
        boolean sawBatchMarker = false;
        int seqId = 0;

        try (BufferedReader reader = new BufferedReader(new FileReader(seqPath))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;

                // Batch boundary
                if (trimmed.startsWith("#")) {
                    String upper = trimmed.toUpperCase(Locale.ROOT);
                    if (upper.contains("BATCH")) {
                        if (!currentBatch.isEmpty()) batches.add(currentBatch);
                        currentBatch = new ArrayList<>();
                        sawBatchMarker = true;
                    }
                    continue;
                }
                if (trimmed.startsWith("%") || trimmed.startsWith("@")) continue;

                Sequence seq = parseSequenceLine(trimmed, seqId++, externalUtilities, relabel);
                if (seq != null) currentBatch.add(seq);
            }
        }
        if (!currentBatch.isEmpty()) batches.add(currentBatch);

        // No marker: return one batch holding everything.
        if (!sawBatchMarker && batches.size() > 1) {
            List<Sequence> all = new ArrayList<>();
            for (List<Sequence> b : batches) all.addAll(b);
            batches.clear();
            batches.add(all);
        }
        return batches;
    }

    /** The database a _seq file belongs to: its file name without the _seq.txt suffix. */
    private static String databaseName(String seqPath) {
        String f = new File(seqPath).getName();
        return f.endsWith("_seq.txt") ? f.substring(0, f.length() - "_seq.txt".length()) : f;
    }

    private static Map<Integer, Double> loadExternalUtilities(String euiPath) throws IOException {
        Map<Integer, Double> map = new HashMap<>();
        try (BufferedReader r = new BufferedReader(new FileReader(euiPath))) {
            String line;
            while ((line = r.readLine()) != null) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("#") || t.startsWith("%")) continue;
                String[] parts = t.split(":");
                if (parts.length == 2) {
                    map.put(Integer.parseInt(parts[0].trim()),
                            Double.parseDouble(parts[1].trim()));
                }
            }
        }
        return map;
    }

    private static Sequence parseSequenceLine(String line, int seqId, Map<Integer, Double> eui,
                                              Map<Integer, Integer> relabel) {
        Sequence seq = new Sequence(seqId);
        QItemset currentSet = new QItemset();
        String[] tokens = line.split("\\s+");
        boolean addedAny = false;

        for (String token : tokens) {
            if (token.equals("-1")) {
                if (!currentSet.items.isEmpty()) {
                    seq.itemsets.add(currentSet);
                    currentSet = new QItemset();
                    addedAny = true;
                }
            } else if (token.equals("-2")) {
                if (!currentSet.items.isEmpty()) {
                    seq.itemsets.add(currentSet);
                    addedAny = true;
                }
                break;
            } else {
                int leftIdx = token.indexOf('[');
                int rightIdx = token.indexOf(']');
                if (leftIdx < 0 || rightIdx < 0) continue;
                try {
                    int itemId = Integer.parseInt(token.substring(0, leftIdx).trim());
                    if (relabel != null) {
                        Integer r = relabel.get(itemId);
                        if (r == null) throw new IllegalStateException(
                                "item " + itemId + " is not in the _eui file; cannot compact ids");
                        itemId = r;
                    }
                    double internalUtil = Double.parseDouble(token.substring(leftIdx + 1, rightIdx).trim());
                    currentSet.items.add(new QItem(itemId, internalUtil * eui.getOrDefault(itemId, 0.0)));
                } catch (NumberFormatException nfe) {
                    // Skip a malformed token
                }
            }
        }
        if (!addedAny) return null;
        // Every miner here takes I-extensions from the later positions of an itemset and
        // relies on items being sorted by id without repeats; a file that breaks this
        // would give wrong patterns without any error, so it is refused here.
        for (int k = 0; k < seq.itemsets.size(); k++) {
            List<QItem> items = seq.itemsets.get(k).items;
            for (int j = 1; j < items.size(); j++) {
                if (items.get(j).id <= items.get(j - 1).id) {
                    throw new IllegalStateException("sequence " + seqId + ", itemset " + k
                            + ": items are not in strictly increasing id order ("
                            + items.get(j - 1).id + " then " + items.get(j).id + ")");
                }
            }
        }
        seq.calculateRemainingUtilities();
        return seq;
    }
}
