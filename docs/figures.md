# Figures and tables - `analysis/make_figures.py`

    python3 analysis/make_figures.py

Reads the campaign results and writes vector PDFs, CSV and LaTeX tables to
`figures/campaign/`. Needs `pandas`, `numpy` and `matplotlib` (`analysis/requirements.txt`).
The environment variables `HUSP_DATA_DIR` and `HUSP_OUT_DIR` override the input directory
(`results/`) and the output directory.

## What it reads

- `results/campaign/<SC>/<DATABASE>/<ARM>/*.csv` for runtimes and counters.
- `results/campaign-mem/<SC>/<DATABASE>/<ARM>/*.csv` for memory: the live set
  (`mem_live_mb`) and the state held after each increment (`mem_retained_mb`). The SC1 live
  set is read from `results/campaign-mem-sampled/SC1/` instead (`docs/experiments.md`).
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

## Style

Every figure follows the artwork rules of Data Mining and Knowledge Discovery: drawn at the
journal's figure width of 119 mm (the journal prints at 155 x 235 mm), at most 195 mm high,
Arial at 9 pt (8 pt only where a legend would not fit otherwise), lines of at least 0.3 pt,
fonts embedded. `save()` refuses a figure larger than 119 x 195 mm.
Multi-panel figures use a grid of three equal columns, one legend placed where no data is drawn (in the empty grid cells when there are any, else inside a panel where a measured check finds no point, segment or bar under it, else above the panels), panel names
under the axes as "(a) NAME", and the y label on the first column of each row; a two-panel figure
is centred and keeps the full width. Each method has one line colour in every figure (Okabe-Ito):
Pre-HUSPM-Joint `#D55E00`, Pre-HUSPM and Pre-HUSPM-SWUmax `#0072B2`, Pre-HUSPM-TSU `#56B4E9`,
Remine-from-scratch `#000000`, IncUSP-Miner+ `#009E73`, HUSP-ULL `#E69F00`, HUSP-SP `#CC79A7`,
USpan `#7F7F7F`. Series that are not methods (variants, stream cuts, databases) take
`SERIES_COLORS` in drawing order, starting with the proposal's colour. Nothing is filled: markers
are hollow, bars are white with a coloured outline and hatch, and every series keeps its own dash
pattern and marker, so a black-and-white print still reads.

## Figures (`main()`)

| file | scenario | content |
|---|---|---|
| `fig_scalability_SC3_runtime_ms.pdf` | SC3 | runtime against UUT, six algorithms, SIGN / LEVIATHAN / C8T1S5I8N5K |
| `fig_scalability_SC3_explored_nodes.pdf` | SC3 | explored nodes against UUT, same panels; a node counts when its candidate extensions are scanned, so the proposal's root items stopped at once by its node bound are subtracted (`results/invariant-root-items`, `docs/result-columns.md`) |
| `fig_incremental_cumulative.pdf` | SC2 | cumulative runtime over the 100 increments (initial mine excluded), five arms, seven databases; a curve that stops early ends in a cross, and arms with no curve are named under the panels |
| `fig_C5_ablation.pdf` | SC7 | runtime summed over the 100 increments and number of rescans, adaptive rescan decision against a rescan at every increment, SIGN and C8T1S5I8N5K |
| `fig_block_size.pdf` | SC7 | against the block size `B_sz` (the default marked): (a) sequences read on the increments without a rescan, (b) their time, (c) the time of the whole stream; SIGN and C8T1S5I8N5K |
| `fig_sl_sensitivity_prelarge_count.pdf` | SC4 | pre-large set size against `r_l`, Pre-HUSPM and the proposal, SIGN / LEVIATHAN / C8T1S5I8N5K |
| `fig_batch_ratio.pdf` | SC5 | the proposal's runtime over remining's, summed over the increments, against K, one line per strategy; below one the proposal is faster |
| `fig_memory_static.pdf` | memory SC1 | live set at each database's default threshold, six algorithms, eight databases; a live set read as 0 MB (below the column's 1 MB resolution) is drawn at 1 MB |
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
| `table_SC2_operating_region.csv` | SC2, the proposal's rescan test per database: `iau_max / sft_val` median, minimum and maximum, increments within the budget, rescans, and a check that the rescans equal the increments above 1; alpha = 1 / `iau_tightness` at the decisions (median, minimum, maximum, increments with alpha <= UUT), alpha over the whole insertion stream and a check against `results/invariant-insertion-alpha`; the budget factor (1 - UUT) / (alpha - UUT) and the rescans of Pre-HUSPM-TSU and Pre-HUSPM-SWUmax beside the proposal's, read against the prediction registered in `make_figures.rescan_hypothesis` |
| `table_SC2_replicates.csv` | SC2 runtime summed over the increments, spread over the replicates, per database and arm that has replicates |
| `table_SC6_seeds.csv`, `table_SC6_seed_summary.csv` | SC6 per database and seed: the proposal's and remining's time over the twenty operations, their ratio, and whether every pattern count agreed; then the ratio's spread over the seeds |
| `table_SC7_static.csv`, `table_SC7_ablation.tex` | SC7 on one mine: full, IUB off, Pre-HUSPM - runtime, explored nodes, IUB cuts |
| `table_SC7_incremental.csv` | SC7 over the increments: full, adaptive rescan off, block index off - increments completed, runtime, rescans, `blocks_read` summed, mean pre-large set, final pattern count |
| `table_status_summary.csv` | rows per outcome for every tree, scenario and database |
| `platform.tex` | one sentence describing the measurement platform, generated from the machine record and `environment.lock.json`; not written when the `DONE` files name more than one host or the host has no record |

The scenario READMEs under `results/campaign/<SC>/` and `results/campaign-mem/<SC>/` explain
each result. `analysis/scenario_readmes.py` regenerates the READMEs of SC2, SC5, SC6,
SC7 and the SC2 memory campaign from the result files.
