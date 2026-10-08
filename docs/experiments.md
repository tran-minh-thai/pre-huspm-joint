# Experimental protocol

How the experiments are configured, measured and recorded. The commands and the cost of each
scenario are in `docs/campaign-parameters.md`; column definitions are in
`docs/result-columns.md`; figures and tables in `docs/figures.md`.

## 1. Algorithms

| Name in results | Role | Reference | Implementation |
|---|---|---|---|
| `Adaptive-Full` | proposed method | this repository | `PreHUSPMAdaptiveAlgorithm` |
| `Pre-HUSPM` | pre-large incremental baseline, rescan test on `SWUmax(d)`, the largest sequence utility of a batch (Yan et al., 2025, Theorem 2) | Yan et al., J. Supercomputing 2025 | `PreHUSPMAlgorithm`, `Variant.SWUMAX` |
| `Pre-HUSPM-TSU` | the same baseline with the rescan test on `TSU(d)`, the total utility of a batch (Yan et al., 2025, Theorem 1) | Yan et al., 2025 | `PreHUSPMAlgorithm`, `Variant.TSU` |
| `IncUSP-Miner+` | candidate-tree incremental baseline | Wang et al., ACM TIST 2018 | `IncUSPMinerPlusAlgorithm` |
| `Remine-from-scratch` | mines the whole current database after every operation | - | the proposal's own static miner, `runInitialMining`, on a new instance each time |
| `USpan` | static baseline, SWU bound | Yin et al., KDD 2012 | `USpanAlgorithm` |
| `HUSP-ULL` | static baseline, LAR and IIP pruning | Gan et al., IEEE TCYB 2021 | `HUSPULLAlgorithm` |
| `HUSP-SP` | static baseline, seqPro and TRSU bound | Zhang et al., ACM TKDD 2023 | `HUSPSPAlgorithm` |

Every algorithm is implemented in Java in `src/algorithms/` and runs inside the same harness,
so runtime, memory and the counters are measured under identical conditions. Numbers copied
from other publications are never mixed into the tables.

Both Pre-HUSPM variants compare the quantity accumulated since the last rescan with the bound
`((UUT - LUT_min) / (1 - UUT)) * TSU_ref`, not the quantity of the current batch alone. The
`TSU(d)` test is sound. The `SWUmax(d)` test is not: several inserted sequences that all
contain a pattern can lift it above the threshold while the largest of them stays under the
bound, so no rescan is made.

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

Counted from the files as `DatasetLoader` reads them. C8T1S5I8N5K's `_seq` file ends with an
empty sequence, which the loader skips.

Generated utilities (seven databases): the sequence structure comes from SPMF; the internal
utility (quantity) is drawn 70 percent from [1, 2], 20 percent from [3, 5] and 10 percent from
[6, 10]; the external utility (unit profit) is `exp(N(2.5, 1.0))`, rounded and clamped to
[1, 1000]; seed 42, one generator per input file. The generator is
`tran-minh-thai/convert-spmf-to-quantitative`.

Measured utilities (TAFENG): the internal utility is Ta-Feng's `AMOUNT`, the external utility
the median of `SALES_PRICE / AMOUNT` per product; one sequence per customer and month, kept
when it holds at least two events.

C8T1S5I8N5K is the only database whose events hold more than one item, so it is the only one
that exercises within-event extension. Its item ids reach 4,999,999 and are replaced at load
by their rank in the `_eui` file (`Datasets.compactItemIds`), which keeps the item order and
so the patterns.

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
file order, into `K` increments. `LUT_min = r_l * UUT`. Both the proposal and Pre-HUSPM read
`r_l` from `common.Datasets`.

| Symbol | Name in code | Value |
|---|---|---|
| `K` | `Datasets.BATCH_COUNT`, `-Dbatch.count` | 100 (SC5: 100, 67, 50, 25) |
| `B_sz` | `-Dblock.size`, `DEFAULT_BLOCK_SIZE` | 100 sequences per block |
| rescan rule | `-Dadapt.trigger` | `joint` |
| repeats | `-Drepeats` | 1 per arm in `campaign.ps1`; the runner's default is 3 |

The rescan rule `joint`: with `I` and `D` the utility inserted and deleted since the last
rescan, `A_i` the inserted utility of the sequences containing item `i`, and `TSU_ref` the
total utility at the last rescan, the proposal rescans when
`max_i A_i - UUT*I + UUT*D > (UUT - LUT_min) * TSU_ref`, and on an operation that does not
rescan it drops a tracked pattern `X` only when `u(X) < LUT_min * TSU_ref + min_{i in X} A_i`.
The rules `iaumax`, `cub` and `swumax` remain selectable for comparison only; `iaumax` and
`swumax` can miss patterns on a direct update.

## 4. Scenarios

| Scenario | Setup | Databases | Output file |
|---|---|---|---|
| SC1 | static mining of `D_init`, four UUT levels, six algorithms | all eight | `SC1_static.csv` |
| SC2 | `D_init` then `K = 100` equal increments; the proposal, remining, both Pre-HUSPM variants and IncUSP-Miner+ | seven (not BMS1) | `SC2_incremental.csv` |
| SC3 | static mining of the whole database, four UUT levels, six algorithms | SIGN, LEVIATHAN, C8T1S5I8N5K | `SC3_su.csv` |
| SC4 | static mining of the whole database at `r_l` in {0.3, 0.5, 0.7, 0.9}, Pre-HUSPM and the proposal at the same `r_l` | SIGN, LEVIATHAN, C8T1S5I8N5K | `SC4_sl_sensitivity.csv` |
| SC5 | the increments cut EVEN, INCREASING, DECREASING and BURST at `K` in {100, 67, 50, 25}; the SC2 arms | SIGN, LEVIATHAN, C8T1S5I8N5K | `SC5_batch_strategy.csv` |
| SC6 | twenty operations, 14 insertions, 3 deletions and 3 modifications in a seeded order, each a hundredth of the incremental stream; the proposal and remining replay the same plan | SIGN, LEVIATHAN, C8T1S5I8N5K | `SC6_idm_mixed.csv` |
| SC7 | ablation. The IUB bound (C3) on one mine of the whole database, with Pre-HUSPM for reference; the block index (C4) and the adaptive rescan decision (C5) on `D_init` and 100 increments, one row per increment | C3: seven (not BMS1); C4, C5: SIGN, C8T1S5I8N5K | `SC7_ablation.csv` |

BMS1 takes part in SC1 only: no threshold that returns patterns can be mined on the whole
database. In SC5 only BURST draws from the seed; EVEN, INCREASING and DECREASING are
deterministic. SC7's arms are `proposed_full`, `proposed_no_IUB`, `Pre-HUSPM` (one mine each)
and `proposed_C5_on`, `proposed_C5_off`, `proposed_no_block_index` (over the increments).

The proposal's SC4 cells on C8T1S5I8N5K at `r_l` = 0.3 and 0.5 are being re-measured.

## 5. Measurement

Each arm (one algorithm on one database in one scenario) runs in its own JVM. Runtime is the
wall-clock time of the mining call, excluding loading. Each mining call has a limit of 120
minutes (`-Dtimeout.min`), and each arm a limit of 8 hours (`campaign.ps1 -CapSeconds`).
Before each increment the harness forces a collection and skips the increment, and the rest of
the arm, when less than 10 percent of the heap is free.

* `candidate_count` counts every extension generated, before any pruning.
* `explored_nodes` counts the patterns actually materialised and recursed into, after the
  extension filter; it measures pruning.
* The miners keep every pattern that reaches their floor; a run that exhausts the heap is
  recorded as out of memory.
* The memory campaign (`results/campaign-mem`) measures the live set: a full collection is
  forced at every sample and the heap read right after, so its runtimes are void. See
  `docs/result-columns.md` for `mem_live_mb` and `mem_retained_mb`.

Status is encoded in `runtime_ms`: 0 or more is a completed run in milliseconds, -2 over the
time limit, -1 a failure or a skipped call, -3 a memory-campaign row.

## 6. Repetition

Every replicate is an arm of its own, measured in a fresh JVM like every arm, so that no
replicate runs on a JIT warmed by the one before it. `campaign.ps1 -Repeat n -SeedIndex k`
writes replicate `s<k>r<n>` to `results/campaign-replicates/s<k>r<n>/` (timing) or
`results/campaign-mem-replicates/s<k>r<n>/` (with `-MemMode live`); the main trees
`results/campaign` and `results/campaign-mem` are replicate `s0r0`. Rows carry the repeat and
the seed in their own columns.

| Scenario | Replicates | Spread reported for |
|---|---|---|
| SC1 | three repeats (`r0`, `r1`, `r2`), all algorithms, all eight databases | runtime at the default threshold |
| SC2 | three repeats of the proposal and remining on SIGN, TAFENG, BIBLE and C8T1S5I8N5K; one run elsewhere | runtime summed over the increments |
| SC6 | five seeds (`s0` to `s4`: 42, 1337, 2024, 31415, 271828), both arms | the proposal-to-remining ratio, paired by seed |
| memory | `s0r1`: SC1 on SIGN, TAFENG, C8T1S5I8N5K, BMS1 and KOSARAK, all six algorithms; SC2 baselines on SIGN and TAFENG | used by `results/jdk-comparison` |
| others | one run | - |

Counters (pattern counts, rescans, explored nodes) are deterministic for a given stream, so
their spread over repeats is zero by construction and is not reported. SC2 splits the stream
in file order when one seed is active (with `-Dseeds` above 1 it shuffles the stream per
seed); SC6 draws its operation order and the sequences it deletes and modifies from
`Random(seed + name.hashCode())`. `analysis/make_figures.py` reports the sample standard
deviation over the replicates that completed and counts those that did not.

## 7. Measurement machine

Every runtime and memory figure comes from one machine, `PC-THAI` (`results/machine/PC-THAI.json`):
AMD Ryzen 9 9950X, 16 cores and 32 threads, 64 GB, Windows 11, `-Xmx48g -Xss64m`, one JVM per
arm. The arms ran on two builds of OpenJDK 25.0.4, Microsoft OpenJDK 25.0.4 and Eclipse Temurin
25.0.4.1+1; `results/jdk-comparison` compares the two builds on the same configurations.
Each arm's `run.log` names its JVM where it was recorded. `environment.lock.json` pins
Temurin 25.0.4.1+1, and `campaign.ps1` refuses any JVM whose `java.runtime.version` or
`java.vendor` differs. Numbers measured on different machines are never compared.

Counters (pattern counts, rescans, explored nodes) do not depend on the machine. The
correctness checks under `results/verify-joint` are counters only and run on a development
machine.

## 8. Provenance

`campaign.ps1` compiles the checked-out `src/` at the start of each invocation, runs each arm,
and writes `DONE` with the host, finish time, duration, exit code, row count, whether the arm
hit its cap and, where recorded, the commit that last changed `src/`, whether `src/` was clean,
and the operating system. The script then commits and pushes the arm's directory. Runs outside
the campaign go through `analysis/record_run.py`, which refuses a `src/` with uncommitted
changes and writes `console.txt` and `run_meta.json` (command, commit, source digest, JVM,
operating system, machine) next to the output.
