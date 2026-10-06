# Figures and tables - `analysis/make_figures.py`

    python analysis/make_figures.py

Reads the campaign results and writes vector PDFs and CSV/LaTeX tables to
`figures/campaign/`. Needs `pandas`, `numpy` and `matplotlib`; `seaborn` is used for the
heatmap when present. `HUSP_DATA_DIR` and `HUSP_OUT_DIR` override the input and output
directories, which is how the Colab notebook built by `analysis/build_colab_notebook.py`
points the same script at a Drive copy.

## What it reads

- `results/campaign/<SC>/<DATABASE>/<ARM>/*.csv` for runtimes and pattern counts.
- `results/campaign-mem/<SC>/<DATABASE>/<ARM>/*.csv` for memory: the live set
  (`mem_live_mb`) and the state held after each increment (`mem_retained_mb`).
- `src/common/Datasets.java` for each database's default threshold, `D_init` and sweep.
  The script has no table of its own, so a threshold changed there is picked up here.

An arm is read only when its directory holds `DONE`. A `DEFERRED` arm keeps a partial CSV,
and drawing it would set a curve that stops part way beside ones that cover the whole stream;
the figures name it as deferred instead.

Every row is classified from its `op_type` suffix and runtime: `OK`, `OOM`, `OT` (over the
time limit), `Fail`, `MemSkip` (refused by the memory guard) and `Skip` (an earlier increment
of the same arm failed). A runtime of -3 is a completed run in the memory campaign, whose time
is void. A configuration that did not finish is labelled with its outcome on the figure and
left out of every sum.

## Figures (`main()`)

| file | scenario | content |
|---|---|---|
| `fig_scalability_SC3_runtime_ms.pdf` | SC3 | runtime against threshold, six algorithms, SIGN / LEVIATHAN / C8T1S5I8N5K |
| `fig_scalability_SC3_explored_nodes.pdf` | SC3 | explored nodes against threshold, same panels |
| `fig_incremental_cumulative.pdf` | SC2 | cumulative runtime over the 100 increments, five arms, seven databases; an arm that runs out of memory ends in a cross, and arms with no curve are named under the panel title |
| `fig_C5_ablation.pdf` | SC7 | runtime over the increments and rescans, C5 on against C5 off, SIGN and C8T1S5I8N5K |
| `fig_sl_sensitivity_prelarge_count.pdf` | SC4 | pre-large set size against `r_l`, Pre-HUSPM and the proposal |
| `fig_batch_heatmap.pdf` | SC5 | the proposal's time over remine-from-scratch's, K x strategy; below one the proposal is faster |
| `fig_memory_static.pdf` | memory SC1 | live set at each database's default threshold, six algorithms, eight databases |
| `fig_memory_incremental.pdf` | memory SC2 | state held after each increment, five arms, seven databases |

`main_all()` adds SC1 runtime and explored nodes against threshold on all eight databases, and
SC4 runtime against `r_l`.

## Tables

| file | content |
|---|---|
| `table_SC1_summary.csv`, `table_SC1_runtime.tex` | SC1 at the default threshold: runtime, live set, explored nodes, patterns and status per algorithm |
| `table_SC1_speedup.csv` | each algorithm's runtime over the proposal's, per database |
| `table_SC2_totals.csv` | SC2 per arm: increments completed, seconds over them, rescans, first failure |
| `table_SC7_static.csv`, `table_SC7_ablation.tex` | SC7 on one mine: full, IUB off, Pre-HUSPM - runtime, explored nodes, IUB cuts |
| `table_SC7_incremental.csv` | SC7 over the increments: full, C5 off, C4 off - runtime, rescans, block reads |
| `table_status_summary.csv` | rows per outcome for every tree, scenario and database |

The figures carry no number that the tables and the scenario READMEs under `results/` do not
also state; those READMEs are where each result is explained.
