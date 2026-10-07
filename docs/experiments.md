# Experimental protocol

How the experiments are configured, measured and recorded. The commands and the cost of each
scenario are in `docs/campaign-parameters.md`; column definitions are in
`docs/result-columns.md`.

## 1. Algorithms

| Name in results | Role | Reference | Implementation |
|---|---|---|---|
| `Adaptive-Full` | proposed method | this repository | `PreHUSPMAdaptiveAlgorithm` |
| `Pre-HUSPM` | pre-large incremental baseline, rescan test on `SWUmax(d)` (Yan et al., Theorem 2) | Yan et al., J. Supercomputing 2025 | `PreHUSPMAlgorithm`, `Variant.SWUMAX` |
| `Pre-HUSPM-TSU` | the same baseline with the rescan test on `TSU(d)` (Yan et al., Theorem 1) | Yan et al. | `PreHUSPMAlgorithm`, `Variant.TSU` |
| `IncUSP-Miner+` | candidate-tree incremental baseline | Wang et al., ACM TIST 2018 | `IncUSPMinerPlusAlgorithm` |
| `Remine-from-scratch` | mines the whole current database after every increment | - | the proposal's own static miner, `runInitialMining` |
| `USpan` | static baseline, SWU bound | Yin et al., KDD 2012 | `USpanAlgorithm` |
| `HUSP-ULL` | static baseline, LAR and IIP pruning | Gan et al., IEEE TCYB 2021 | `HUSPULLAlgorithm` |
| `HUSP-SP` | static baseline, seqPro and TRSU bound | Zhang et al., ACM TKDD 2023 | `HUSPSPAlgorithm` |

Every algorithm is implemented in Java in `src/algorithms/` and runs inside the same harness,
so runtime, heap and the counters are measured under identical conditions. Numbers copied from
other papers are never mixed into the tables.

Both Pre-HUSPM variants compare the quantity accumulated since the last rescan with the bound,
not the quantity of the current batch alone.

## 2. Datasets

Eight databases. The data are not kept in this repository: seven are release assets of
`tran-minh-thai/huspm-datasets`, pinned to `v1.2.1-measured-quantity` and verified against
that release's `MANIFEST.sha256` by `fetch-datasets.ps1` / `fetch-datasets.sh`; TAFENG is
built locally from the raw Ta-Feng file by `tafeng/build_tafeng.py` and pinned by
`tafeng/TAFENG.sha256`.

| Database | Sequences | Distinct items | Events / sequence | Items / event | Total utility | Utilities |
|---|---:|---:|---:|---:|---:|---|
| SIGN | 730 | 267 | 52.00 | 1.00 | 1,796,483 | generated |
| LEVIATHAN | 5,834 | 9,025 | 33.81 | 1.00 | 10,502,159 | generated |
| FIFA | 20,450 | 2,990 | 36.24 | 1.00 | 42,781,841 | generated |
| BIBLE | 36,369 | 13,905 | 21.64 | 1.00 | 35,550,162 | generated |
| BMS1 | 59,601 | 497 | 2.51 | 1.00 | 7,229,030 | generated |
| C8T1S5I8N5K | 47,132 | 68,240 | 2.36 | 7.97 | 47,416,411 | generated |
| KOSARAK | 990,002 | 41,270 | 8.10 | 1.00 | 385,921,994 | generated |
| TAFENG | 60,665 | 23,789 | 13.40 | 1.00 | 106,639,001 | measured |

Counted from the files as the loader reads them. C8T1S5I8N5K's `_seq` file ends with an empty
sequence, which the loader skips, so it holds 47,132 sequences.

Generated utilities (seven databases): the sequence structure comes from SPMF; the internal
utility (quantity) is drawn 70 percent from [1,2], 20 percent from [3,5] and 10 percent from
[6,10]; the external utility (unit profit) is `exp(N(2.5, 1.0))`, rounded and clamped to
[1, 1000]; seed 42, one generator per input file. The generator is
`tran-minh-thai/convert-spmf-to-quantitative`.

Measured utilities (TAFENG): the internal utility is Ta-Feng's `AMOUNT`, the external utility
the median of `SALES_PRICE / AMOUNT` per product; one sequence per customer and month,
kept when it holds at least two events.

C8T1S5I8N5K is the only database whose events hold more than one item, so it is the only one
that exercises within-event extension. Its item ids reach 4,999,999 and are replaced at load
by their rank in the `_eui` file (`Datasets.compactItemIds`), which keeps the item order.

## 3. Parameters

Per database, from `common.Datasets`:

| Database | `D_init` (sequences) | Default UUT | UUT levels (SC1, SC3) | `r_l` |
|---|---:|---:|---|---:|
| SIGN | 230 | 0.065911 | 0.049134, 0.065911, 0.083371, 0.091052 | 0.80 |
| LEVIATHAN | 2,834 | 0.015530 | 0.014644, 0.015530, 0.016470, 0.017987 | 0.80 |
| BIBLE | 21,369 | 0.022895 | 0.018100, 0.022895, 0.028960, 0.032570 | 0.80 |
| BMS1 | 39,601 | 0.013597 | 0.012821, 0.013597, 0.015293, 0.016218 | 0.80 |
| FIFA | 10,450 | 0.059637 | 0.056234, 0.059637, 0.067073, 0.075436 | 0.80 |
| KOSARAK | 90,002 | 0.025000 | 0.022228, 0.025000, 0.028117, 0.031623 | 0.85 |
| C8T1S5I8N5K | 27,133 | 0.001132 | 0.001099, 0.001132, 0.001200, 0.001236 | 0.80 |
| TAFENG | 40,665 | 0.003557 | 0.002812, 0.003557, 0.004499, 0.005691 | 0.80 |

`D_init` is the first `D_init` sequences in file order; the rest of the database is split, in
file order, into `K` increments. `LUT_min = r_l * UUT`.

| Symbol | Name in code | Value |
|---|---|---|
| `K` | `Datasets.BATCH_COUNT` | 100 (SC5: 100, 67, 50, 25) |
| `B_sz` | `block.size`, `DEFAULT_BLOCK_SIZE` | 100 sequences per block |
| rescan rule | `adapt.trigger` | `joint` |
| repeats | `repeats` | 1 |

The rescan rule `joint`: with `I` and `D` the utility inserted and deleted since the last
rescan, `A_i` the inserted utility of the sequences containing item `i`, and
`TSU_ref` the total utility at the last rescan, the proposal rescans when
`max_i A_i - UUT*I + UUT*D > (UUT - LUT_min) * TSU_ref`, and on an operation that does not
rescan it drops a tracked pattern `X` only when `u(X) < LUT_min * TSU_ref + min_{i in X} A_i`.
`iaumax`, `cub` and `swumax` remain selectable for comparison only.

## 4. Scenarios

| Scenario | Setup | Databases | Output file |
|---|---|---|---|
| SC1 | static mining of `D_init`, four UUT levels | all eight | `SC1_static.csv` |
| SC2 | `D_init` then `K = 100` equal increments | seven (not BMS1) | `SC2_incremental.csv` |
| SC3 | static mining of the whole database, four UUT levels | SIGN, LEVIATHAN, C8T1S5I8N5K | `SC3_su.csv` |
| SC4 | static mining of the whole database at `r_l` in {0.3, 0.5, 0.7, 0.9}, Pre-HUSPM and the proposal | SIGN, LEVIATHAN, C8T1S5I8N5K | `SC4_sl_sensitivity.csv` |
| SC5 | the increments cut EVEN, INCREASING, DECREASING and BURST at `K` in {100, 67, 50, 25} | SIGN, LEVIATHAN, C8T1S5I8N5K | `SC5_batch_strategy.csv` |
| SC6 | twenty operations, 14 insertions, 3 deletions and 3 modifications in a seeded order, each a hundredth of the incremental stream; the proposal and remining | SIGN, LEVIATHAN, C8T1S5I8N5K | `SC6_idm_mixed.csv` |
| SC7 | ablation. C3 (the IUB bound) on one mine of the whole database, with Pre-HUSPM for reference; C4 (block index) and C5 (adaptive rescan decision) on `D_init` and 100 increments, one row per increment | C3: seven; C4, C5: SIGN, C8T1S5I8N5K | `SC7_ablation.csv` |

BMS1 takes part in SC1 only: no threshold mines the whole database.

## 5. Measurement

Each arm - one algorithm on one database in one scenario - runs in its own JVM. Runtime is the
wall-clock time of the mining call, excluding loading. Each mining call has a limit of 120
minutes (`-Dtimeout.min`), and each arm a limit of 8 hours (`campaign.ps1 -CapSeconds`).

* `candidate_count` counts every extension generated, before any pruning.
* `explored_nodes` counts the patterns actually materialised and recursed into, after the
  extension filter; it measures pruning.
* The memory campaign (`results/campaign-mem`) measures the live set: a full collection is
  forced at every sample and the heap read right after, so its runtimes are void. See
  `docs/result-columns.md` for `mem_live_mb` and `mem_retained_mb`.

Status is encoded in `runtime_ms`: 0 or more is a completed run in milliseconds, -2 overtime,
-1 a failure (out of memory, candidate budget, exception), -3 a memory-campaign row.

## 6. Repetition

One repeat per configuration. SC2 splits the stream in file order and so has no seed; SC6 draws
its operation order from `Random(42 + name.hashCode())`, one fixed order per database. No table reports a standard deviation.

## 7. Measurement machine

Every runtime and memory figure comes from one machine, `PC-THAI`: AMD Ryzen 9 9950X, 64 GB,
Windows, JDK 25, `-Xmx48g -Xss64m`, one JVM per arm. `campaign.ps1` refuses to run under any
other JDK major version. Numbers measured on different machines are never compared.

Counters - pattern counts, rescans, explored nodes - do not depend on the machine, and the
correctness evidence under `results/verify*` is measured on the development machine.

## 8. Provenance

`campaign.ps1` runs each arm from a fresh compile of the checked-out commit, records `DONE`
with the host, the duration and the row count, and commits and pushes the arm's directory.
Runs outside the campaign go through `analysis/record_run.py`, which refuses an uncommitted
`src/` and writes `run_meta.json` (command, commit, source digest, JVM, operating system) next
to the output.
