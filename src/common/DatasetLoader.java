package common;

import java.io.*;
import java.util.*;

/**
 * Bộ nạp dữ liệu hỗ trợ hai chế độ:
 *  - loadDataset(seq, eui): trả về toàn bộ chuỗi làm 1 batch.
 *  - loadBatches(seq, eui): trả về List<List<Sequence>> đã chia batch theo
 *    chỉ thị '# --- BATCH n' trong file _seq.txt.
 *
 * Định dạng dòng dữ liệu: token "id[utility]", "-1" chuyển sang itemset mới,
 * "-2" kết thúc chuỗi. Các dòng bắt đầu '#', '%', '@' đều là comment/meta.
 */
public class DatasetLoader {

    /** Nạp toàn bộ dataset, không chia batch. */
    public static List<Sequence> loadDataset(String seqPath, String euiPath) throws IOException {
        List<List<Sequence>> batched = loadBatches(seqPath, euiPath);
        List<Sequence> flat = new ArrayList<>();
        for (List<Sequence> b : batched) flat.addAll(b);
        return flat;
    }

    /**
     * Nạp dataset và CHIA THEO BATCH dựa trên dòng "# --- BATCH ..." trong file _seq.
     * Nếu file không có chỉ thị BATCH thì coi toàn bộ là 1 batch duy nhất.
     */
    public static List<List<Sequence>> loadBatches(String seqPath, String euiPath) throws IOException {
        Map<Integer, Double> externalUtilities = loadExternalUtilities(euiPath);
        List<List<Sequence>> batches = new ArrayList<>();
        List<Sequence> currentBatch = new ArrayList<>();
        boolean sawBatchMarker = false;
        int seqId = 0;

        try (BufferedReader reader = new BufferedReader(new FileReader(seqPath))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;

                // Phát hiện ranh giới batch
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

                Sequence seq = parseSequenceLine(trimmed, seqId++, externalUtilities);
                if (seq != null) currentBatch.add(seq);
            }
        }
        if (!currentBatch.isEmpty()) batches.add(currentBatch);

        // Trường hợp không có marker -> trả 1 batch chứa tất cả.
        if (!sawBatchMarker && batches.size() > 1) {
            List<Sequence> all = new ArrayList<>();
            for (List<Sequence> b : batches) all.addAll(b);
            batches.clear();
            batches.add(all);
        }
        return batches;
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

    private static Sequence parseSequenceLine(String line, int seqId, Map<Integer, Double> eui) {
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
                    double internalUtil = Double.parseDouble(token.substring(leftIdx + 1, rightIdx).trim());
                    currentSet.items.add(new QItem(itemId, internalUtil * eui.getOrDefault(itemId, 0.0)));
                } catch (NumberFormatException nfe) {
                    // Bỏ qua token bị lỗi định dạng
                }
            }
        }
        if (!addedAny) return null;
        seq.calculateRemainingUtilities();
        return seq;
    }
}
