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
        // Catch Throwable, not only IOException: if toCsvRow() or write throws
        // OutOfMemoryError on a nearly full heap, swallow it so the main thread survives
        // and the run continues with the next algorithm or dataset.
        try {
            writer.write(metrics.toCsvRow());
            writer.newLine();
            writer.flush(); // flush every row so nothing is lost on OOM or crash
        } catch (Throwable e) {
            try { System.err.println("Failed to write result row: " + e.getClass().getSimpleName()); }
            catch (Throwable ignored) {}
        }
    }

    @Override
    public synchronized void close() {
        try { writer.close(); } catch (IOException ignored) {}
    }
}
