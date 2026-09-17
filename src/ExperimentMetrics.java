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

    // 10. WP2 - CAU HINH CUA LAN CHAY. Truoc day cac gia tri nay khong duoc ghi ra CSV,
    //     nen khong the truy nguoc mot dong ket qua ve tham so da sinh ra no. Reviewer #1
    //     (comment 4a, 4e) va Reviewer #4 (comment 5) deu neu dung diem nay.
    //     seed        - hat giong sinh lo/thao tac (truoc day co dinh 42, khong ghi ra)
    //     repeat      - so hieu lan lap cua CUNG mot cau hinh (khu nhieu JIT/GC)
    //     blockSize   - B_sz cua chi muc nguoc cap do khoi (C4), truoc day la hang so cung
    //     kappa       - he so thich ung (bai goi kappa, code cu goi alpha)
    //     delta0      - bien do khoi tao
    //     triggerRule - luat kich hoat rescan cho pha chen: swumax | cub | iaumax
    //     iauMax      - max_i A_i cua lo gan nhat (dai luong kich hoat cua luat moi)
    //     iauTightness- CUB / max_i A_i, he so noi rong kich thuoc lo do duoc
    // 11. CHẾ ĐỘ ĐO BỘ NHỚ.
    //
    //   memMode = "used"  (mặc định): peak_heap_mb là ĐỈNH USED-HEAP lấy mẫu 50 ms, tức
    //     totalMemory-freeMemory, KỂ CẢ rác chưa được thu. Đại lượng này đo LỊCH GC chứ
    //     không đo nhu cầu bộ nhớ của thuật toán: nó phụ thuộc -Xmx (heap lớn ⇒ G1 thu
    //     lười ⇒ đỉnh cao hơn) và phụ thuộc thuật toán nào đã chạy TRƯỚC trong cùng JVM.
    //     Project 02 (HAUSP/IAUUB) đã truy ra một con số 23.3 GB hoàn toàn là artifact
    //     kiểu này: lần chạy đó nối sau một arm đã nống heap lên ~23 GB nên đọc được
    //     14 GB ngay tại batch 0, lúc mới xử lý 1% dữ liệu.
    //
    //   memMode = "live": ép một lần thu gom đầy đủ tại các mốc (mỗi giây và cuối mỗi
    //     lô) rồi đọc used-heap NGAY SAU đó. memLiveMb = đỉnh của các lần đọc ấy,
    //     memRetainedMb = lần đọc cuối cùng. Đây là tập sống thật sự phải giữ, không
    //     phụ thuộc lịch GC, không phụ thuộc -Xmx, không phụ thuộc thứ tự arm.
    //     memLiveMb là CẬN DƯỚI của đỉnh tập sống thật; sai số bị chặn bởi khoảng lấy mẫu.
    //
    //   BẮT BUỘC: ép GC làm hỏng thời gian, nên runtime của lần chạy ở chế độ live KHÔNG
    //   được dùng. runtimeMs xuất ra -3 và giá trị thật đi vào wallMsPerturbed để giữ dấu
    //   vết mà không thể bị vẽ nhầm thành số hiệu năng.
    public String memMode = "used";
    public long memLiveMb;        // đỉnh used-heap đo NGAY SAU khi ép thu gom
    public long memRetainedMb;    // used-heap sau khi ép thu gom lần cuối
    public int  gcForced;         // số lần ép thu gom (0 ⇒ chỉ có giá trị cuối)
    public long wallMsPerturbed;  // thời gian thật của lần chạy live (đã bị GC làm nhiễu)
    /** Tổng byte thread khai phá đã cấp phát. Tất định, không phụ thuộc lịch GC. */
    public long allocatedMb;

    public long seed;
    public int repeat;
    public int blockSize;
    public double kappa;
    public double delta0;
    public String triggerRule = "";
    public double iauMax;
    public double iauTightness;

    // Thich nghi theo CONG thay vi theo loi ich. repetition la do lap cua CSDL
    // (so lan xuat hien tren mot item phan biet), dai luong cau truc duy nhat tach duoc
    // cac bo re khoi cac bo dat tren tam bo cua nghien cuu nay. sl_min_eff cho biet san
    // da bi nang len bao nhieu so voi ti le cau hinh, va rescan_retries dem so lan phai
    // nang - bang 0 nghia la co che khong phai can thiep.
    public double repetition;
    public double slMinEff;
    public int rescanRetries;

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
        // WP2.4 - cấu hình của lần chạy (đặt cuối để giữ tương thích thứ tự cột cũ).
        sj.add(memMode).add(String.valueOf(memLiveMb)).add(String.valueOf(memRetainedMb))
                .add(String.valueOf(gcForced)).add(String.valueOf(wallMsPerturbed))
                .add(String.valueOf(allocatedMb));
        sj.add(String.valueOf(seed)).add(String.valueOf(repeat))
                .add(String.valueOf(blockSize))
                .add(String.valueOf(kappa)).add(String.valueOf(delta0))
                .add(triggerRule)
                .add(String.valueOf(iauMax)).add(String.valueOf(iauTightness))
                .add(String.valueOf(repetition)).add(String.valueOf(slMinEff))
                .add(String.valueOf(rescanRetries));
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
                "sigma2,rho," +
                "mem_mode,mem_live_mb,mem_retained_mb,gc_forced,wall_ms_perturbed,allocated_mb," +
                "seed,repeat,block_size,kappa,delta0,trigger_rule,iau_max,iau_tightness," +
                "repetition,sl_min_eff,rescan_retries";
    }
}