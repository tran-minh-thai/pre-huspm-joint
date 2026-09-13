import java.io.*;

public class ResultCollector implements Closeable {
    private final BufferedWriter writer;

    public ResultCollector(String filePath) throws IOException {
        File file = new File(filePath);
        File parent = file.getParentFile();
        if (parent != null) parent.mkdirs();
        boolean exists = file.exists() && file.length() > 0;
        this.writer = new BufferedWriter(new FileWriter(file, true)); // append mode
        if (!exists) {
            writer.write(ExperimentMetrics.getHeader());
            writer.newLine();
            writer.flush();
        }
    }

    public synchronized void log(ExperimentMetrics metrics) {
        if (metrics == null) return;
        // Catch Throwable (không chỉ IOException): nếu toCsvRow()/write OOM trên heap
        // gần đầy (do CP-Tree baseline chưa được GC), NUỐT lỗi để Main Thread sống sót
        // và experiment chạy tiếp thuật toán/dataset kế - KHÔNG sập toàn bộ tiến trình.
        try {
            writer.write(metrics.toCsvRow());
            writer.newLine();
            writer.flush(); // Flush ngay để không mất log khi OOM/crash
        } catch (Throwable e) {
            try { System.err.println("Lỗi ghi log thực nghiệm: " + e.getClass().getSimpleName()); }
            catch (Throwable ignored) {}
        }
    }

    @Override
    public synchronized void close() {
        try { writer.close(); } catch (IOException ignored) {}
    }
}
