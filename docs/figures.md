# Experiment report pipeline - `make_figures.py`

Tổng hợp + trực quan hoá kết quả thực nghiệm HUSP (SC1-SC7) cho bài báo Q1.
Đọc các CSV 31-cột do `AutomatedExperimentRunner.java` sinh ra (gồm `explored_nodes`),
xuất **hình PDF vector** (serif, DPI 300, LaTeX-ready) + **bảng tổng hợp** CSV/LaTeX.

> File `yeucautonghopthucnghiem.txt` chỉ là GỢI Ý. Pipeline được khớp với **cấu trúc
> dữ liệu THẬT** của từng kịch bản (parse `su`/`sl`/`K`/`strat`/ablation từ
> `op_type`/`algorithm`), không bám cứng theo file gợi ý.

## Cách chạy

**Local (máy này):**
```bash
python3 analysis/make_figures.py
# đọc results/, ghi figures/
```
Phụ thuộc: `pandas`, `matplotlib`, `numpy` (bắt buộc); `seaborn` (tuỳ chọn - heatmap
đẹp hơn; thiếu thì fallback `imshow`).

**Google Colab (khuyến nghị):** mở **`make_figures_colab.ipynb`** (File → Upload notebook,
hoặc mở thẳng từ Drive). Chạy *Runtime → Run all*:
1. Cell **Setup** mount Drive + **sửa `PROJECT`** trỏ tới thư mục `pre-huspm-adaptive` của bạn
   (mặc định `/content/drive/MyDrive/Researching/pre-huspm-adaptive`).
2. Cell cuối `main()` sinh toàn bộ hình + bảng vào `.../figures/` trên Drive.

> Notebook là **self-contained**, sinh tự động từ `make_figures.py` bằng
> `python3 analysis/build_colab_notebook.py` (chạy lại builder mỗi khi sửa script để
> regenerate `.ipynb` - giữ 1 nguồn code duy nhất). Colab có sẵn seaborn + jinja2.

> Cơ chế đường dẫn: `make_figures.py` đọc env `HUSP_DATA_DIR`/`HUSP_OUT_DIR` trước
> (cell Setup của notebook set 2 biến này), nên cùng 1 script chạy được cả local lẫn Colab.

## Xử lý dữ liệu (đã cài trong loader)
- **Phân loại trạng thái CHÍNH XÁC** (`classify_status`) - KHÔNG gộp chung "OOM/Fail".
  Hậu tố `op_type` là nguồn chuẩn (runner set ở `measure()`), sentinel `runtime_ms` dự phòng:

  | Trạng thái | Điều kiện | Nghĩa |
  |---|---|---|
  | `OK` | `runtime_ms ≥ 0` | chạy xong |
  | `OT` | `_OT` / `rt=-2` | **Overtime** (>60' timeout) |
  | `OOM` | `_OOM` | **OutOfMemory / StackOverflow** |
  | `Fail` | `_FAILED` / `rt=-1` (khác) | exception khác |
  | `MemSkip` | `_BUDGET` | bỏ qua do memory-guard trước khi chạy |
  | `Skip` | `_SKIPPED_POISONED` | bỏ qua vì thuật toán đã hỏng ở op TRƯỚC (pha gia tăng) |

  → Trên hình: mỗi cấu hình không-OK được dán nhãn **đúng trạng thái** (OT/OOM/Fail/Skip),
  **màu riêng** (OT=vàng, OOM=đỏ, Fail=đen, Skip=xám). Loại khỏi mean.
  → `Skip`/`MemSkip` là HỆ QUẢ của lỗi trước (không phải lỗi độc lập của cấu hình đó).
- **Khử trùng append-mode**: `ResultCollector` ghi nối → chạy lại 1 phần sinh dòng trùng;
  loader giữ bản **mới nhất** (cảnh báo `[dedup]`).
- **Aggregate**: mean/std cho `runtime_ms, peak_heap_mb, candidate_count, explored_nodes,
  rescan_count` (runner chạy 1 lần/cấu hình ⇒ std≈0; cơ chế giữ sẵn nếu sau lặp seed).

## Bộ hình RÚT GỌN (do giới hạn trang) - `main()`

**Nguyên tắc:** bảng đã rõ thì KHÔNG vẽ hình; mỗi hình đúng **3 subplot** =
`FIG_DATASETS = {SIGN, LEVIATHAN, KOSARAK}` (đại diện dày+thưa, = SUBSET_3). Dữ liệu
đủ 6 dataset nằm trong **bảng**.

| # | File | Kịch bản | Nội dung |
|---|---|---|---|
| 1 | `fig_scalability_runtime_ms` | SC3 | line Runtime vs $su$ |
| 2 | `fig_scalability_explored_nodes` | SC3 | line Explored vs $su$ - **bằng chứng C1** (Pre-HUSPM materialize tất cả) |
| 3 | `fig_incremental_runtime_ms` | SC2 | clustered bar Runtime qua các lô (IncUSP/Pre-HUSPM/Proposed) |
| 4 | `fig_C5_ablation` | SC7-C5 | 2 subplot: Runtime + #Rescans, C5 on vs off (BMS1 C5_off **OT**) |
| 5 | `fig_sl_sensitivity_prelarge_count` | SC4 | grouped bar kích thước tập pre-large theo $s_l/s_u$ (dilemma + OOM/OT) |
| 6 | `fig_batch_heatmap_runtime_heatmap` | SC5 | heatmap K × strategy (tổng runtime gia tăng) |
| 7 | `fig_memory` | SC1 | bar Memory 5 thuật toán ở $su$ mặc định - **ưu thế bộ nhớ** (vd 57MB vs 9.5GB) |

> Toàn bộ hình **memory-vs-su**, hình **SC1 scalability**, hình **ablation single-mine**,
> và **SC6** đã được BỎ (bảng/text rõ hơn). Muốn sinh lại đầy đủ: gọi `main_all()`.

## Bảng (đủ 6 dataset, số chính xác - `main()` xuất)
- `table_SC1_summary.csv` / `table_SC1_runtime.tex` - SC1 tĩnh: Runtime/Memory/Candidate/Explored/Patterns + **status**; ô lỗi LaTeX hiện status cụ thể (OT/OOM/Fail).
- `table_SC1_speedup.csv` - speedup Proposed vs từng baseline.
- `table_SC7_ablation.csv` / `.tex` - **ablation single-mine** (Full/-EUCS/-IUB/-Block/Pre-HUSPM × 6 ds): Runtime + Explored + status.
- `table_status_summary.csv` - **đếm OK/OT/OOM/Fail/MemSkip/Skip mỗi (scenario × dataset)**.
  Vd: *"SC7/BMS1: 5 OT, 1 OOM, 1 OK"* (KHÔNG nói "đều OOM").

## Ghi chú diễn giải (điểm nhấn paper)
- **`explored_nodes`** là cột chốt: Pre-HUSPM `explored == candidate` (materialize MỌI
  ứng viên) trong khi Proposed `explored ≪ candidate` (EUCS lọc trước materialize = C1).
  Xem `set1_*_explored_nodes` và `set3_SC7_explored_nodes`.
- Trung thực: trên dataset cực thưa (BMS1) Proposed **chậm hơn** HUSP-ULL/IncUSP về *thời
  gian* nhưng thắng lớn về *bộ nhớ* (95MB vs ~8GB) và ở pha *gia tăng* (SC2) + độ bền OOM.
