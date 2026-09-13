# Codebook các thông số thực nghiệm (CSV - 30 cột)

Tài liệu này giải thích **ý nghĩa, công thức, đơn vị, thuật toán ghi nhận** của từng cột
trong `results/SC*.csv`, để đọc/diễn giải chính xác. Mọi độ đo được **đo độc lập** (xem §1).

## Ký hiệu
| Ký hiệu | Nghĩa |
|---|---|
| `U(D)` | TSU - tổng lợi ích CSDL hiện hành = Σ_{s∈D} u(s) |
| `u(s)` | lợi ích (utility) của chuỗi s |
| `Su` | ngưỡng HU tương đối (theo dataset) |
| `Sl_min` | tỉ lệ sàn pre-large CỐ ĐỊNH = 0.8·Su |
| `ξ` (uut) | ngưỡng HU tuyệt đối = `Su·U(D)` |
| `B` | lô (batch) dữ liệu gia tăng δD |
| `swuMax` | max u(s) trong lô = lợi ích chuỗi lớn nhất của lô |

---

## §1. Phương pháp đo - ĐỘC LẬP & CHÍNH XÁC
- **Cô lập thuật toán**: mỗi thuật toán chạy trong scope riêng, `forceFullGC()` TRƯỚC mỗi
  lần đo ⇒ thời gian/bộ nhớ KHÔNG nhiễm chéo giữa các thuật toán.
- **runtime_ms**: wall-clock CHỈ của tác vụ khai phá (chạy trong ExecutorService) - KHÔNG
  gồm thời gian nạp dataset, KHÔNG gồm GC chuẩn bị.
- **peak_heap_mb**: đo bằng **sampler daemon poll 50ms** trong suốt lúc chạy → lấy ĐỈNH heap
  THẬT, trừ baseline (heap sau GC trước khi chạy). Khác hẳn "delta cuối" (vốn bỏ sót đỉnh
  transient đã bị GC).
- **candidate_count (Adaptive)**: **reset MỖI LÔ** ⇒ phản ánh chi phí TỪNG lô (không cộng dồn).
- **Đếm cắt tỉa (pruned_*)**: counter nguyên tử, mỗi cột đếm MỘT kỹ thuật riêng (không trùng).

### Sentinel & trạng thái
| `runtime_ms` | Ý nghĩa |
|---|---|
| `≥ 0` | hoàn thành bình thường (ms) |
| `-2` | OT - vượt timeout (mặc định 120 phút, xem `-Dtimeout.min`) |
| `-1` | thất bại (OOM / budget / exception / skipped) |

`op_type` mang nhãn scenario+pha, có thể kèm hậu tố: `_OT`, `_OOM`, `_BUDGET` (DNF do vượt
ngân sách ứng viên), `_FAILED`, `_SKIPPED_POISONED` (lô bị bỏ sau khi thuật toán đã lỗi).

---

## §2. Bảng 30 cột

Cột | Ý nghĩa | Công thức / nguồn | Đơn vị | Thuật toán ghi | Loại
---|---|---|---|---|---
`run_id` | định danh dòng | `algo_dataset_optype_iter` | - | tất cả | dẫn xuất
`timestamp` | thời điểm ghi | ISO-8601 | - | tất cả | đo
`algorithm` | tên thuật toán | - | - | tất cả | -
`dataset` | tên tập dữ liệu | - | - | tất cả | -
`iteration` | chỉ số lô/lần | 0 = INIT/static; 1..K = lô gia tăng | - | tất cả | -
`runtime_ms` | thời gian khai phá | wall-clock; -2=OT, -1=fail | ms | tất cả | **đo** (độc lập)
`peak_heap_mb` | đỉnh heap THẬT - baseline | `max(usedₜ) - heapBefore` (sampler 50ms) | MB | tất cả | **đo** (độc lập)
`candidate_count` | số ứng viên SINH RA (**Sinh ra là đếm**): mỗi mở rộng (k+1)-pattern duy nhất (I/S-ext) tại mỗi node được đếm **TRƯỚC MỌI cắt tỉa** (EUCS/SWU/PEU/IIP/TSU). NHẤT QUÁN mọi thuật toán ⇒ **bảo toàn: cand = Σ pruned_* + survivors**. Pruning mạnh KHÔNG làm cand nhỏ đi - nó làm `pruned_*` lớn lên (vd EUCS của Adaptive). **BẰNG NHAU trong cùng nhóm ngưỡng** (định lý phản-đơn-điệu): nhóm large-only {USpan, HUSP-ULL, IncUSP} sinh giống nhau (ngưỡng ξ); nhóm pre-large {Pre-HUSPM, Adaptive} sinh giống nhau & LỚN HƠN (giữ ứng viên xuống sàn Sl·U(D)<ξ để đón lô tới). | counter (Adaptive: reset/lô) | ứng viên | tất cả | **đo**
`explored_nodes` | số mẫu **THỰC SỰ khai triển/materialize** (đệ quy vào) - đếm tại entry đệ quy, SAU khi đã qua bộ lọc mở rộng. Đo **HIỆU QUẢ TỈA thật** (KHÁC `candidate_count`): tỉa tốt ⇒ explored ≪ candidate. **Pre-HUSPM: explored = candidate** (materialize MỌI extension trước khi xét). **Adaptive: explored ≪ candidate** vì EUCS lọc TRƯỚC materialize (C1) - vd SIGN@0.025: cùng sinh 67.2M ứng viên nhưng Pre-HUSPM khai triển 67.2M còn Adaptive chỉ 0.41M (≈163×). | counter (Adaptive: reset/lô) | mẫu | tất cả | **đo**
`total_patterns_found` | số HUSP (mẫu LỚN) | `|largeMap|` = #{p : u(p) ≥ ξ} | mẫu | tất cả | **đo**
`pruned_swu` | loại bởi SWU | counter | lần | USpan, HUSP-ULL | **đo**
`pruned_depth` | loại bởi chặn độ sâu | counter | lần | USpan | **đo**
`pruned_lar` | loại bởi Look-Ahead Removing | counter | lần | HUSP-ULL | **đo**
`pruned_iip` | loại bởi Irrelevant-Item (PEU) | counter | lần | HUSP-ULL | **đo**
`pruned_tsu` | loại bởi cận TSU | counter | lần | IncUSP-Miner+ | **đo**
`pruned_peu` | loại bởi cận **SWU_proj (=PEU)** - projected SWU đúng bài Yan2025 (≠ SWU toàn cục) | counter | lần | Pre-HUSPM | **đo**
`pruned_value` | **C1**: số mục mở rộng bị cận giá trị PEU gộp ($\IUB$) loại (áp trước khi hiện thực hoá) | counter | lần | Adaptive | **đo**
`pruned_iub` | **C3**: loại nhánh bởi cận IUB | counter | lần | Adaptive | **đo**
`lut_dyn` | **C2**: ngưỡng pre-large động | `ξ - δ_D` | utility | Adaptive | **đo**
`cub_val` | **C5**: bộ đệm lợi ích tích luỹ | Σ u(B) (chèn) / Σ|u(B)| (xoá) từ rescan gần nhất | utility | Adaptive | **đo**
`cub_triggered` | **C5**: lô này có kích hoạt rescan | bool (chèn: `swuMax>F`; xoá: `cub>F_del`) | bool | Adaptive | **đo**
`op_type` | nhãn scenario/pha (+hậu tố lỗi) | - | - | tất cả | -
`prelarge_count` | số mẫu Tiền-Lớn (PSWU) | Pre-HUSPM (Đ.n12 Yan2025): #{p : Sl·U(D) ≤ SWU_proj(p) < ξ}; Adaptive: #{p : lut ≤ u(p) < ξ} | mẫu | Pre-HUSPM, Adaptive | **đo**
`uut_val` | ngưỡng HU tuyệt đối ξ | `Su · U(D)` | utility | Adaptive | **đo**
`sft_val` | cận an toàn rescan (xem ghi chú⁺) | chèn: `((Su-Sl_min)/(1-Su))·U(D)` ; xoá: `((Su-Sl_min)/Su)·U(D)` ; Pre-HUSPM: `((Su-Sl)/(1-Su))·U(D)` | utility | Pre-HUSPM, Adaptive | **đo**
`rescan_count` | số lần rescan tích luỹ | counter | lần | Pre-HUSPM, Adaptive | **đo**
`blocks_read` | **C4**: số block ĐỌC khi đánh giá pattern | counter (pha gia tăng) | block | Adaptive | **đo**
`blocks_skipped` | **C4**: số block BỎ QUA nhờ Block-Index | counter (pha gia tăng) | block | Adaptive | **đo**
`sigma2` | **C2**: phương sai lợi ích lô | `σ² = (1/|B|) Σ_{s∈B}(u(s)-ū)²` | utility² | Adaptive | **đo**
`rho` | **C2**: độ lệch lô | `ρ = swuMax / Σ_{s∈B} u(s)` | tỉ lệ | Adaptive | **đo**

> Cột không áp dụng cho một thuật toán sẽ ghi `0` (vd USpan không có pre-large ⇒ `prelarge_count=0`).

⁺ **Ghi chú `sft_val`**: là cận an toàn pre-large quyết định khi nào rescan. Công thức **phụ
thuộc PHA** (chèn vs xoá vs baseline Pre-HUSPM - bảng trên). Ở pha mine-đơn (SC1/SC3/SC4/SC7
init) cột này ghi độ-rộng-band tối đa `ξ-lut_min` và KHÔNG dùng cho quyết định nào → **chỉ so
sánh `sft_val` trong PHA GIA TĂNG**.

---

## §3. Độ đo DẪN XUẤT (người đọc tự tính từ cột thô - đảm bảo tính độc lập của dữ liệu gốc)
| Độ đo | Công thức | Ý nghĩa |
|---|---|---|
| Speedup | `runtime_ms(baseline) / runtime_ms(Proposed)` | hệ số tăng tốc |
| Throughput | `total_patterns_found / (runtime_ms/1000)` | mẫu/giây |
| Tỉ lệ skip (C4) | `blocks_skipped / (blocks_read + blocks_skipped)` | hiệu quả Block-Index |
| Hiệu quả tỉa | `pruned_* / candidate_count` | tỉ lệ cắt tỉa |
| **Tỉ lệ materialize** | `explored_nodes / candidate_count` | phần ứng viên PHẢI khai triển (thấp = tỉa-trước-materialize tốt; Adaptive≪Pre-HUSPM=1.0) |
| Completeness | `total_patterns_found` so với GOLD (full re-mine ở ξ) | tính đầy đủ |
| δ_D (độ rộng band) | `uut_val - lut_dyn` | độ rộng band pre-large (KHÔNG lưu cột riêng - dẫn xuất) |
| Tần suất rescan | `rescan_count / số lô` | mức re-mine |

> **Nguyên tắc độc lập**: mọi cột §2 (trừ `delta_d`) là **đo/đếm trực tiếp, độc lập**; các độ đo
> phái sinh ở §3 KHÔNG lưu sẵn để tránh dư thừa/sai lệch - người đọc tính từ cột thô khi cần.

---

## §4. Ánh xạ Đóng góp ↔ Metric (cho phần Ablation SC7)
| Đóng góp | Metric trực tiếp |
|---|---|
| **C1** Áp cận trước materialize | `pruned_value`, **`explored_nodes`** (lọc-trước-materialize: Adaptive ≪ Pre-HUSPM dù cùng `candidate_count`) |
| **C2** LUT_dyn | `lut_dyn`, `sigma2`, `rho` (δ_D = `uut_val - lut_dyn`) |
| **C3** IUB | `pruned_iub` (+ candidate_count khi ablation `no_IUB`) |
| **C4** Block-Index | `blocks_read`, `blocks_skipped`, tỉ lệ skip |
| **C5** Adaptive rescan | `rescan_count`, `cub_val`, `cub_triggered`, `sft_val` (+ runtime khi `C5_off`) |

## Memory measurement columns

Two different quantities are recorded, and they must never be compared with each other.

| Column | Meaning |
|---|---|
| `mem_mode` | `used` or `live`; says which quantity the row's memory columns hold |
| `peak_heap_mb` | **used heap**: peak of `totalMemory - freeMemory` sampled every 50 ms, minus the post-GC baseline. Includes garbage that had not been collected yet. |
| `mem_live_mb` | **live set**: peak of the used heap read immediately after a forced full collection. Only filled when `mem_mode = live`. |
| `mem_retained_mb` | live set at the end of the call |
| `gc_forced` | number of forced collections during the call |
| `wall_ms_perturbed` | real wall-clock of a live-mode call; void as a performance figure |
| `allocated_mb` | total bytes the mining thread allocated. Deterministic, independent of GC timing. `-1` when unavailable. |

### Why `peak_heap_mb` is not the memory metric

`peak_heap_mb` counts uncollected garbage, so it measures when the collector happened to
run rather than what the algorithm needs to hold. Two consequences follow.

It grows with the heap. Same machine, same dataset (SIGN), same algorithm (Pre-HUSPM),
same threshold (0.025), only `-Xmx` differs:

| `-Xmx` | `peak_heap_mb` | `mem_live_mb` | `allocated_mb` |
|---|---:|---:|---:|
| 1g | 338 | 299 | 6909 |
| 2g | 545 | 299 | 6909 |
| 3g | 619 | 301 | 6909 |

The used-heap figure nearly doubles for identical work; the live set moves by 0.7 percent
and the allocation total is identical.

It also depends on what ran before in the same JVM. Project 02 traced a reported 23.3 GB
peak to a run that followed another arm in the same JVM whose heap had already grown to
about 23 GB; the sampler read 14 GB at batch 0, one percent into the data. That is why live
mode runs one JVM per arm and refuses to start otherwise.

### Reading a live-mode row

`runtime_ms` is `-3`. Forcing a collection at every sample point distorts timing, so live
rows carry no performance information; the real elapsed time is kept in
`wall_ms_perturbed` for the record only. Timing comes from separate `mem_mode = used`
runs. `mem_live_mb` is a lower bound on the true peak live set: a peak that appears and
disappears between two sample points is not seen, and the sampling interval bounds the gap.
