# Experimental protocol

How the experiments are configured, measured and recorded. The commands and the cost of each
scenario are in `docs/campaign-parameters.md`; column definitions are in
`docs/result-columns.md`; figures and tables in `docs/figures.md`.

## 1. Algorithms

| Name in results | Role | Reference | Implementation |
|---|---|---|---|
| `Adaptive-Full` | proposed method | this repository | `PreHUSPMAdaptiveAlgorithm` |
| `Pre-HUSPM-SWUmax` | pre-large incremental baseline, rescan test on `SWUmax` (Theorem 2 of Yan et al.); SC2 and SC5 | Yan et al., J. Supercomputing 2025 | `PreHUSPMAlgorithm`, `Variant.SWUMAX` |
| `Pre-HUSPM-TSU` | the same baseline with the rescan test on `TSU(d)` (Theorem 1 of Yan et al.); SC2 and SC5 | Yan et al., J. Supercomputing 2025 | `PreHUSPMAlgorithm`, `Variant.TSU` |
| `Pre-HUSPM` | the same baseline in the static scenarios (SC1, SC3, SC4, SC7), where both tests mine alike | Yan et al., J. Supercomputing 2025 | `PreHUSPMAlgorithm`, `runInitialMining` |
| `IncUSP-Miner+` | candidate-pattern-tree incremental baseline (Algorithms 1 and 2, HandleCase-1 and HandleCase-2, Strategies 1 to 6); insertions only | Wang and Huang, ACM TIST 2018 | `IncUSPMinerPlusAlgorithm` |
| `Remine-from-scratch` | mines the whole current database after every operation | - | the proposal's own static miner, `runInitialMining`, on a new instance each time |
| `USpan` | static baseline, width pruning by SWU and depth pruning by a sound bound | Yin et al., KDD 2012 | `USpanAlgorithm` |
| `HUSP-ULL` | static baseline, UL-lists with irrelevant item pruning (IPS) and the look-ahead strategy (LAS), Algorithms 1 to 3 | Gan et al., IEEE Transactions on Cybernetics 2021 | `HUSPULLAlgorithm` |
| `HUSP-SP` | static baseline, seq-array and seqPro with IIP, early pruning by a sound bound, and the PEU test | Zhang et al., ACM TKDD 2024 | `HUSPSPAlgorithm` |

Every algorithm is implemented in Java in `src/algorithms/` and runs inside the same harness,
so runtime, memory and the counters are measured under identical conditions. Numbers copied
from other publications are never mixed into the tables.

**Pre-HUSPM.** Both incremental arms follow Algorithm 1 of Yan et al.: the quantity `q` of
each inserted batch `d` is added to a buffer `buf` accumulated since the last rescan, and the
database is rescanned when `buf + q > f`, with `f = ((UUT - LUT_min) / (1 - UUT)) * TSU_ref` and `TSU_ref` the total utility at
the last rescan;
otherwise the batch goes through the safe update and `buf` grows by `q`. `Pre-HUSPM-TSU` takes
`q = TSU(d)`, the total utility of the inserted sequences (Theorem 1). `Pre-HUSPM-SWUmax` takes
`q = SWUmax`, the largest sequence-weighted utility (Definition 8) of an item in the inserted
sequences (Theorem 2). Both tests are sound: by Eq. (14), `SWU_d(X) <= SWUmax <= TSU(d)` for
every pattern `X`, so over the batches absorbed since the last rescan no pattern gains more
than `buf <= f`. The buffer holds every pattern whose projected sequence-weighted utility
(`SWU_proj`, the PEU computed on the projected database as in the worked example of Yan et
al.) reaches `LUT_min * TSU`, `TSU` being the total utility of the current database, as a
prefix tree. Occurrence lists keep one entry per position
with the largest utility of the pattern ending there. A rescan removes the items whose
sequence-weighted utility (Definition 8) is below `LUT_min * TSU` (Algorithm 3, line 13) and
mines the reduced database. Between rescans the buffer is updated as in Algorithm 2: every
buffered pattern adds its values in `d` and is discarded, with its extensions, when its
`SWU_proj` falls below `LUT_min * TSU`; a pattern not already buffered is not added.

**USpan.** The depth pruning of Yin et al. (Eq. (21): the utility at the pivot plus the
remaining utility after it) is not an upper bound (Truong-Chi and Fournier-Viger, 2019).
`USpanAlgorithm` replaces it, as ProUM (2020) does, by the sum over the sequences of the
largest `u(t, p) + rest(p)` over the ending positions `p` of the pattern `t`.

**HUSP-ULL.** Algorithms 1 to 3 of Gan et al. with both pruning strategies, IPS and LAS. A
projected sequence keeps one entry per concatenation point, holding the largest utility of the
pattern ending there.

**HUSP-SP.** HUSP-SP of Zhang et al.: the database is reduced by SWU, IIP removes irrelevant
items and reduces the remaining-utility array, early pruning is applied at the root and at
every node, and the PEU test follows Eq. (4). Early pruning uses a sound bound, the largest
`acu_j + remUtil[q - 1]` over the prefix instances `j` and the valid positions `q` of the
extension item, in place of TRSU (Definition 4.11), because Theorem 4.12 does not hold: for the
sequence `<{a}:1, {b}:1, {a}:50, {e}:1, {b}:1, {e}:1>` and `t = <a, b>`, the TRSU of
`<a, b, e>` is 5 while its utility is 52. Everything else follows the paper.

**IncUSP-Miner+.** Algorithms 1 and 2 of Wang and Huang with HandleCase-1, HandleCase-2 and
Strategies 1 to 6, including `LM_TSU` and the rescan of the old sequences where a strategy
requires it. `min_util` is the threshold ratio times the total utility of the current
database, so it follows the database as it grows.

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
| KOSARAK | 90,002 | 0.025000 | 0.022228, 0.025000, 0.028117, 0.031623 | 0.80 |
| C8T1S5I8N5K | 27,133 | 0.001132 | 0.001099, 0.001132, 0.001200, 0.001236 | 0.80 |
| TAFENG | 40,665 | 0.003557 | 0.002812, 0.003557, 0.004499, 0.005691 | 0.80 |

`D_init` is the first `D_init` sequences in file order; the rest of the database is split, in
file order, into `K` increments. `LUT_min = r_l * UUT`. Both the proposal and Pre-HUSPM read
`r_l` from `common.Datasets`.

| Symbol | Name in code | Value |
|---|---|---|
| `K` | `Datasets.BATCH_COUNT`, `-Dbatch.count` | 100 (SC5: 100, 67, 50, 25) |
| `B_sz` | `-Dblock.size`, `DEFAULT_BLOCK_SIZE` | 100 sequences per block. A block never spans two increments, so the effective size is min(`B_sz`, increment size). SC7 also runs 1, 5, 10, 50 and 200 (`B_SZ_SWEEP`) |
| rescan rule | `-Dadapt.trigger` | `joint` |
| item removal in the proposal's miner | `-Dadapt.iip` | `off`, `once` or `fixpoint`; the irrelevant item pruning of HUSP-ULL (Theorem 9), at the mining cutoff; `once`, set by calibration |
| repeats | `-Drepeats` | 1 per arm in `campaign.ps1`; the runner's default is 3 |

The rescan rule `joint`: with `I` and `D` the utility inserted and deleted since the last
rescan, `A_i` the inserted utility of the sequences containing item `i`, and `TSU_ref` the
total utility at the last rescan, the proposal rescans when
`max_i A_i - UUT*I + UUT*D > (UUT - LUT_min) * TSU_ref`, and on an operation that does not
rescan it drops a tracked pattern `X` only when `u(X) < LUT_min * TSU_ref + min_{i in X} A_i`.
The rules `iaumax`, `cub` and `swumax` remain selectable for comparison only; `iaumax` and
`swumax` can miss patterns on a direct update. The proposal's `swumax` rule compares the
utility of the largest single inserted sequence; it is not the `SWUmax` of Yan et al., which
`Pre-HUSPM-SWUmax` uses.

Two settings are fixed on the measurement machine before the campaign, by rules set before
the measurement (`experiments.CampaignCalibration`, run by `.\campaign.ps1 -Calibrate`, output
in `results/calibration`): the item-removal variant of the proposal's miner, `once` or
`fixpoint`, by the total time of the SC1 sweep and of a whole-database mine on every database;
and KOSARAK's `r_l`, 0.80 if the proposal mines KOSARAK at that floor within 120 minutes and
0.85 otherwise. The rules are stated in the class comment and the outcome in
`results/calibration/decision.txt`: `once`, and 0.80 for KOSARAK.

## 4. Scenarios

| Scenario | Setup | Databases | Output file |
|---|---|---|---|
| SC1 | static mining of `D_init`, four UUT levels, six algorithms | all eight | `SC1_static.csv` |
| SC2 | `D_init` then `K = 100` equal increments; the proposal, remining, `Pre-HUSPM-SWUmax`, `Pre-HUSPM-TSU` and IncUSP-Miner+ | seven (not BMS1) | `SC2_incremental.csv` |
| SC3 | static mining of the whole database, four UUT levels, six algorithms | SIGN, LEVIATHAN, C8T1S5I8N5K | `SC3_su.csv` |
| SC4 | static mining of the whole database at `r_l` in {0.3, 0.5, 0.7, 0.9}, Pre-HUSPM and the proposal at the same `r_l` | SIGN, LEVIATHAN, C8T1S5I8N5K | `SC4_sl_sensitivity.csv` |
| SC5 | the increments cut EVEN, INCREASING, DECREASING and BURST at `K` in {100, 67, 50, 25}; the SC2 arms | SIGN, LEVIATHAN, C8T1S5I8N5K | `SC5_batch_strategy.csv` |
| SC6 | twenty operations, 14 insertions, 3 deletions and 3 modifications in a seeded order, each a hundredth of the incremental stream; the proposal and remining replay the same plan | SIGN, LEVIATHAN, C8T1S5I8N5K | `SC6_idm_mixed.csv` |
| SC7 | ablation. The IUB bound (C3) and the irrelevant item pruning (`-Dadapt.iip=off`) on one mine of the whole database, with Pre-HUSPM for reference; the block index (C4) and the adaptive rescan decision (C5) on `D_init` and 100 increments, one row per increment | C3: seven (not BMS1); C4, C5: SIGN, C8T1S5I8N5K | `SC7_ablation.csv` |

BMS1 takes part in SC1 only: on the whole database 0.025 returns two patterns and 0.020
exceeds three billion candidates (`results/thresholds/bms1-whole`), and its four levels lie
below 0.020. In SC5 only BURST draws from the seed; EVEN, INCREASING and DECREASING are
deterministic. SC7's arms are `proposed_full`, `proposed_no_IUB`, `proposed_no_IPS`, `Pre-HUSPM` (one mine each)
and `proposed_C5_on`, `proposed_C5_off`, `proposed_no_block_index`, `proposed_bsz_<n>` (over the increments).

### Block size, stated before the measurement

The `proposed_bsz_<n>` arms repeat `proposed_C5_on` with blocks of n sequences, n in
{1, 5, 10, 50, 200}, beside the default 100. Blocks are cut inside each increment and never
span two, so the effective block size is min(n, increment size): 5 sequences on SIGN, 199 or 200
on C8T1S5I8N5K. Expected, for every n:

1. The same increments take the direct update as with the default, since the rescan decision
   does not read the index (`same_direct_increments`).
2. The HUSP and pre-large counts equal those of the default on every row (`counts_equal`):
   the index skips only sequences that miss an item of the pattern.
3. The sequences read on direct updates do not decrease along 1, 5, 10, 50, 100, 200. Each
   value divides the next, so the smaller blocks refine the larger ones: a block that holds
   every item of a pattern lies inside a larger block that holds them too.
4. On each database the sequences read are the same for every n at least the increment size
   (SIGN: 5 and above; C8T1S5I8N5K: 200).

No direction is stated for time. Fewer sequences are read with smaller blocks, but each
pattern intersects one bitmap per item whose length is the number of blocks of the whole
database, which grows as the blocks shrink.

`analysis/scenario_tables.py` (`sc7_block_size`) reports the four checks with their counts.

## 5. Measurement

Each arm (one algorithm on one database in one scenario) runs in its own JVM. Runtime is the
wall-clock time of the mining call, excluding loading. Each mining call has a limit of 120
minutes (`-Dtimeout.min`), and each arm a limit of 8 hours (`campaign.ps1 -CapSeconds`).
Before each increment the harness forces a collection and skips the increment, and the rest of
the arm, when less than 10 percent of the heap is free.

* `candidate_count` counts the candidates an algorithm generates on its own search, before its
  pruning rules; `explored_nodes` counts the nodes it expands. What each algorithm counts is
  given per algorithm in `docs/result-columns.md`.
* The miners keep every pattern that reaches their floor; a run that runs out of heap is
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
| SC1 | three repeats (`r0`, `r1`, `r2`), all algorithms, all eight databases, all four thresholds | runtime at the default threshold; every SC1 comparison (fastest counts, geometric means) uses the mean of the three |
| SC2 | three repeats of the proposal and remining on SIGN, TAFENG, BIBLE and C8T1S5I8N5K, and of the proposal and the runner-up on LEVIATHAN and KOSARAK (remining) and BIBLE (IncUSP-Miner+); one run elsewhere | runtime summed over the increments; the SC2 table prints mean and deviation where three runs exist |
| SC3 | three repeats, all six algorithms, the three databases | runtime and every SC3 comparison |
| SC5 | three repeats of the proposal and remining on LEVIATHAN and SIGN, every K and shape; one run on C8T1S5I8N5K, whose closest margin is 1.36 | runtime summed over the increments |
| SC7 | three repeats of the four one-mine arms (full, IUB off, IPS off, Pre-HUSPM) on the seven databases | runtime of one mine |
| SC6 | five seeds (`s0` to `s4`: 42, 1337, 2024, 31415, 271828), both arms | the proposal-to-remining ratio, paired by seed |
| memory | `s0r1`: SC1 on SIGN, TAFENG, C8T1S5I8N5K, BMS1 and KOSARAK, all six algorithms; SC2, all five arms, on SIGN and TAFENG | the live set between two runs |
| others | one run | - |

Counters (pattern counts, rescans, explored nodes) are deterministic for a given stream, so
their spread over repeats is zero by construction and is not reported. SC2 splits the stream
in file order when one seed is active (with `-Dseeds` above 1 it shuffles the stream per
seed); SC6 draws its operation order and the sequences it deletes and modifies from
`Random(seed + name.hashCode())`. `analysis/make_figures.py` reports the sample standard
deviation over the replicates that completed and counts those that did not.

Generated tables span the full text width (`tabular*` with `\extracolsep\fill`, as the
Springer Nature template does) in the class's table font; a mean and its standard deviation
share one line, except in the SC1 table, whose six algorithms only fit with the deviation
below the mean. Generated tables set the lowest value of each row in bold (runtime, rescans; in the SC7
ablation the lowest runtime and the fewest explored nodes of each database), comparing the
values as printed, so two values that print alike are both bold; a cell measured three times
whose runs overlap those of the lowest is a tie and is bold as well; a run that did not finish
takes no part. Every table, figure and number on runtime reads the mean of the replicates
(`load_mean` in `analysis/make_figures.py`); SC1, SC3, SC5 and SC7 count a win as separated
only when every run of the proposal beats every run of the closest arm. An SC2 arm that the runner's memory guard stopped (less than a tenth of the
heap free after a full collection, before the next increment) is printed as "Stopped
(done/100)"; its increments after the stop are recorded as `SKIPPED_POISONED`.

## 7. Measurement machine

Every runtime and memory figure comes from one machine, `PC-THAI` (`results/machine/PC-THAI.json`):
AMD Ryzen 9 9950X, 16 cores and 32 threads, 64 GB, Windows 11, `-Xmx48g -Xss64m`, one JVM per
arm, Eclipse Temurin 25.0.4.1+1. Each arm's `run.log` names its JVM. `environment.lock.json` pins
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
