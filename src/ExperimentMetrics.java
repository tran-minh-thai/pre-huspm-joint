import java.util.StringJoiner;
import java.time.LocalDateTime;

/**
 * DTO chứa TẤT CẢ chỉ số thực nghiệm (31 cột CSV). Codebook đầy đủ (ý nghĩa/công thức/đơn vị
 * /thuật toán ghi): xem analysis/METRICS.md.
 *
 * KÝ HIỆU:  U(D)=TSU (tổng utility CSDL) ; Su=ngưỡng HU tương đối ; Sl_min=0.8·Su (sàn pre-large
 *           cố định) ; ξ=uut=Su·U(D) (ngưỡng HU tuyệt đối) ; B=lô gia tăng.
 *
 * NGUYÊN TẮC ĐO (độc lập & chính xác):
 *   - Mỗi thuật toán đo trong scope cô lập + forceFullGC trước → KHÔNG nhiễm chéo.
 *   - runtime_ms = wall-clock CHỈ phần khai phá (không gồm nạp dữ liệu); -2=OT(60'), -1=fail.
 *   - peak_heap_mb = ĐỈNH heap THẬT (sampler 50ms) - baseline; không phải delta cuối.
 *   - Mọi cột là ĐO/ĐẾM trực tiếp & độc lập, TRỪ delta_d là DẪN XUẤT (= uut_val - lut_dyn).
 *   - Speedup/throughput/skip-ratio... KHÔNG lưu sẵn → tính từ cột thô (tránh dư thừa).
 */
public class ExperimentMetrics {
    // 1. Identifier
    public String runId;
    public String timestamp = LocalDateTime.now().toString();
    public String algorithm;
    public String dataset;
    public int iteration;

    // 2. Performance Metrics
    public long runtimeMs;
    public long peakHeapMb;
    public long candidateCount;   // ứng viên SINH RA (trước tỉa) - biên tìm kiếm (bằng nhau cùng ngưỡng)
    public long exploredNodes;    // mẫu THỰC SỰ khai triển/materialize (sau tỉa ext-filter) - KHÁC nhau, đo hiệu quả tỉa

    // 3. Pruning Metrics (Đóng góp C1)
    //
    //   USpan       : prunedSwu (SWU global+local), prunedDepth (depth bound)
    //   HUSP-ULL    : prunedSwu (1-item only), prunedLar (subtree LAR),
    //                 prunedIip (item-level PEU/IIP)
    //   IncUSP-Miner+: prunedTsu (TSU upper bound)
    //   Pre-HUSPM   : prunedPeu (PEU upper bound)
    //   Adaptive    : prunedValueBound (cận giá trị PEU, C1), prunedIub (cắt nhánh, C3)
    public long prunedSwu, prunedDepth;          // USpan
    public long prunedLar, prunedIip;            // HUSP-ULL (ngoài prunedSwu)
    public long prunedTsu;                       // IncUSP-Miner+
    public long prunedPeu;                       // Pre-HUSPM
    public long prunedValueBound, prunedIub;     // Adaptive (C1 lọc giá trị + C3 cắt nhánh)

    // 4. Adaptive/CUB Status (Đóng góp C2 & C5)
    public double lutDynValue, cubValue;
    public boolean cubTriggeredRescan;
    public String opType;

    // 5. Thêm trường mới theo yêu cầu: Throughput
    public long totalPatternsFound;

    // 6. Pre-large & ngưỡng tuyệt đối (Pre-HUSPM + Adaptive)
    //   prelargePatternCount = |PL| (số mẫu Tiền-Lớn). Thuật toán không có khái niệm
    //                          Pre-large (USpan/HUSP-ULL/IncUSP+) sẽ để 0.
    //   uutValue             = ngưỡng HUSP tuyệt đối (uut_abs). Chỉ Adaptive set.
    //   sftValue             = ngân sách an toàn tuyệt đối Δ = uut_abs - lutDyn. Chỉ Adaptive set.
    public long prelargePatternCount;
    public double uutValue;
    public double sftValue;
    // 7. Số lần RESCAN tích luỹ (Pre-HUSPM & Adaptive). Thuật toán không có rescan
    //    (USpan/HUSP-ULL/IncUSP+) để 0. Là metric so sánh CỐT LÕI cho pha gia tăng.
    public int rescanCount;
    // 8. Hiệu quả Block-Index (C4): số block ĐỌC vs BỎ QUA khi đánh giá pattern gia tăng.
    //    Tỉ lệ skip = blocksSkipped/(blocksRead+blocksSkipped) → định lượng trực tiếp C4.
    //    Chỉ Adaptive set (thuật toán khác để 0).
    public long blocksRead;
    public long blocksSkipped;
    // 9. Thống kê thích ứng C2 - ĐỘ ĐO THÔ ĐỘC LẬP (chỉ Adaptive, pha gia tăng/xoá set; =0 ở init):
    //    sigma2 = phương sai lợi ích lô, rho = độ lệch (swuMax/tổng lô).
    //    (δ_D KHÔNG lưu vì là DẪN XUẤT = uut_val - lut_dyn - tránh cột dư thừa/không độc lập.)
    public double sigma2;
    public double rho;

    public String toCsvRow() {
        StringJoiner sj = new StringJoiner(",");
        sj.add(runId).add(timestamp).add(algorithm).add(dataset).add(String.valueOf(iteration));
        sj.add(String.valueOf(runtimeMs)).add(String.valueOf(peakHeapMb)).add(String.valueOf(candidateCount));
        sj.add(String.valueOf(exploredNodes)); // mẫu khai triển thật (sau ext-filter)
        sj.add(String.valueOf(totalPatternsFound)); // Thêm throughput metric
        // Pruning metrics - gom theo từng algorithm để dễ đọc:
        //   USpan       extras: prunedSwu + prunedDepth
        //   HUSP-ULL    extras: prunedLar + prunedIip
        //   IncUSP+     : prunedTsu
        //   Pre-HUSPM   : prunedPeu
        //   Adaptive    : prunedValueBound + prunedIub
        sj.add(String.valueOf(prunedSwu)).add(String.valueOf(prunedDepth))
                .add(String.valueOf(prunedLar)).add(String.valueOf(prunedIip))
                .add(String.valueOf(prunedTsu))
                .add(String.valueOf(prunedPeu))
                .add(String.valueOf(prunedValueBound))
                .add(String.valueOf(prunedIub));
        sj.add(String.valueOf(lutDynValue)).add(String.valueOf(cubValue))
                .add(String.valueOf(cubTriggeredRescan)).add(opType);
        // Pre-large & ngưỡng tuyệt đối (cuối hàng để giữ tương thích ngược thứ tự cột cũ).
        sj.add(String.valueOf(prelargePatternCount))
                .add(String.valueOf(uutValue))
                .add(String.valueOf(sftValue))
                .add(String.valueOf(rescanCount))
                .add(String.valueOf(blocksRead))      // C4
                .add(String.valueOf(blocksSkipped))   // C4
                .add(String.valueOf(sigma2))          // C2 (thô, độc lập)
                .add(String.valueOf(rho));            // C2 (thô, độc lập)
        return sj.toString();
    }

    public static String getHeader() {
        return "run_id,timestamp,algorithm,dataset,iteration," +
                "runtime_ms,peak_heap_mb,candidate_count,explored_nodes,total_patterns_found," +
                "pruned_swu,pruned_depth,pruned_lar,pruned_iip," +
                "pruned_tsu,pruned_peu," +
                "pruned_value,pruned_iub," +
                "lut_dyn,cub_val,cub_triggered,op_type," +
                "prelarge_count,uut_val,sft_val,rescan_count,blocks_read,blocks_skipped," +
                "sigma2,rho";
    }
}