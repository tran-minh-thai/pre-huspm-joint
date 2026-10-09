# Figures and tables - `analysis/make_figures.py`

    python3 analysis/make_figures.py

Reads the campaign results and writes vector PDFs, CSV and LaTeX tables to
`figures/campaign/`. Needs `pandas`, `numpy` and `matplotlib` (`analysis/requirements.txt`).
The environment variables `HUSP_DATA_DIR` and `HUSP_OUT_DIR` override the input directory
(`results/`) and the output directory.

## What it reads

- `results/campaign/<SC>/<DATABASE>/<ARM>/*.csv` for runtimes and counters.
- `results/campaign-mem/<SC>/<DATABASE>/<ARM>/*.csv` for memory: the live set
  (`mem_live_mb`) and the state held after each increment (`mem_retained_mb`).
- `results/campaign-replicates/` for the replicate tables, together with the main tree.
- `src/common/Datasets.java` for each database's default threshold, `D_init` and sweep.
  The script has no table of its own, so a threshold changed there is picked up here.
- `results/machine/<host>.json` and `environment.lock.json` for the platform sentence.

An arm is read only when its directory holds `DONE`. A `DEFERRED` arm keeps a partial CSV and
is left out; the figures name it under the panels instead.

Every row is classified from its `op_type` suffix, then its runtime: `OK`, `OT` (`_OT` or
runtime -2), `OOM` (`_OOM`), `Fail` (`_FAILED` or runtime -1), `MemSkip` (`_BUDGET`) and `Skip`
(`_SKIPPED_POISONED`: not run after an earlier failure of the same arm or a refusal by the
memory guard). A runtime of -3 is a completed run of the memory campaign, whose time is void.
A configuration that did not finish is marked with its outcome on the figure and left out of
every sum.

## Figures (`main()`)

| file | scenario | content |
|---|---|---|
| `fig_scalability_SC3_runtime_ms.pdf` | SC3 | runtime against UUT, six algorithms, SIGN / LEVIATHAN / C8T1S5I8N5K |
| `fig_scalability_SC3_explored_nodes.pdf` | SC3 | explored nodes against UUT, same panels; each algorithm counts the nodes of its own search (`docs/result-columns.md`, "Work counters by algorithm") |
| `fig_incremental_cumulative.pdf` | SC2 | cumulative runtime over the 100 increments (initial mine excluded), five arms, seven databases; a curve that stops early ends in a cross, and arms with no curve are named under the panels |
| `fig_C5_ablation.pdf` | SC7 | runtime summed over the 100 increments and number of rescans, adaptive rescan decision against a rescan at every increment, SIGN and C8T1S5I8N5K |
| `fig_sl_sensitivity_prelarge_count.pdf` | SC4 | pre-large set size against `r_l`, Pre-HUSPM and the proposal, SIGN / LEVIATHAN / C8T1S5I8N5K |
| `fig_batch_ratio.pdf` | SC5 | the proposal's runtime over remining's, summed over the increments, against K, one line per strategy; below one the proposal is faster |
| `fig_memory_static.pdf` | memory SC1 | live set at each database's default threshold, six algorithms, eight databases |
| `fig_memory_incremental.pdf` | memory SC2 | state held after each increment (`mem_retained_mb`), five arms, seven databases |

`main_all()` adds `fig_scalability_SC1_runtime_ms.pdf` and
`fig_scalability_SC1_explored_nodes.pdf` (SC1 on all eight databases) and
`fig_sl_sensitivity_runtime_ms.pdf` (SC4 runtime against `r_l`).

## Tables

| file | content |
|---|---|
| `table_SC1_summary.csv` | SC1 at the default threshold: runtime, live set, explored nodes, patterns and status per algorithm |
| `table_SC1_speedup.csv` | each algorithm's runtime over the proposal's, per database |
| `table_SC1_memory_ratios.csv` | SC1 live set, each baseline over the proposal: geometric mean over the cells where both completed, at all four thresholds and at the default one, with the cells left out and why |
| `table_SC1_runtime_replicates.csv`, `table_SC1_runtime.tex` | SC1 runtime at the default threshold over the replicates: n, mean, sample standard deviation, minimum, maximum, replicates that did not finish |
| `table_SC2_totals.csv` | SC2 per arm: increments completed, seconds over them, rescans, first failure |
| `table_SC2_operating_region.csv` | SC2, the proposal's rescan test per database: `iau_max / sft_val` median, minimum and maximum, increments within the budget, rescans, and a check that the rescans equal the increments above 1 |
| `table_SC2_replicates.csv` | SC2 runtime summed over the increments, spread over the replicates, per database and arm that has replicates |
| `table_SC6_seeds.csv`, `table_SC6_seed_summary.csv` | SC6 per database and seed: the proposal's and remining's time over the twenty operations, their ratio, and whether every pattern count agreed; then the ratio's spread over the seeds |
| `table_SC7_static.csv`, `table_SC7_ablation.tex` | SC7 on one mine: full, IUB off, Pre-HUSPM - runtime, explored nodes, IUB cuts |
| `table_SC7_incremental.csv` | SC7 over the increments: full, adaptive rescan off, block index off - increments completed, runtime, rescans, `blocks_read` summed, mean pre-large set, final pattern count |
| `table_status_summary.csv` | rows per outcome for every tree, scenario and database |
| `platform.tex` | one sentence describing the measurement platform, generated from the machine record and `environment.lock.json`; not written when the `DONE` files name more than one host or the host has no record |

The scenario READMEs under `results/campaign/<SC>/` and `results/campaign-mem/<SC>/` explain
each result. `analysis/scenario_readmes.py` regenerates the READMEs of SC2, SC5, SC6,
SC7 and the SC2 memory campaign from the result files.
