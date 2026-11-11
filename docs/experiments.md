# Experimental protocol

How the experiments are configured, measured and recorded. The commands and the cost of each
scenario are in `docs/campaign-parameters.md`; column definitions are in
`docs/result-columns.md`; figures and tables in `docs/figures.md`.

## 1. Algorithms

| Name in results | Role | Reference | Implementation |
|---|---|---|---|
| `Pre-HUSPM-Joint` | the method: in the static scenarios its static mine, in SC2, SC5 and the mixed streams its maintenance with the break-even floor rule and merged modifications (below) | this repository | `PreHUSPMAdaptiveAlgorithm` |
| `Pre-HUSPM-Joint-fixed-threshold` | the method at the fixed lower threshold `r_l`, without the threshold selection; an ablation row of SC2, SC5 and the mixed streams | this repository | `PreHUSPMAdaptiveAlgorithm` |
| `Pre-HUSPM-SWUmax` | pre-large incremental baseline, rescan test on `SWUmax` (Theorem 2 of Yan et al.); SC2 and SC5 | Yan et al., J. Supercomputing 2025 | `PreHUSPMAlgorithm`, `Variant.SWUMAX` |
| `Pre-HUSPM-TSU` | the same baseline with the rescan test on `TSU(d)` (Theorem 1 of Yan et al.); SC2 and SC5 | Yan et al., J. Supercomputing 2025 | `PreHUSPMAlgorithm`, `Variant.TSU` |
| `Pre-HUSPM` | the same baseline in the static scenarios (SC1, SC3, SC4, SC7), where both tests mine alike | Yan et al., J. Supercomputing 2025 | `PreHUSPMAlgorithm`, `runInitialMining` |
| `IncUSP-Miner+` | candidate-pattern-tree incremental baseline (Algorithms 1 and 2, HandleCase-1 and HandleCase-2, Strategies 1 to 6); insertions only | Wang and Huang, ACM TIST 2018 | `IncUSPMinerPlusAlgorithm` |
| `Remine-from-scratch` | mines the whole current database after every operation | - | the proposal's own static miner on a new instance each time: `runInitialMining` on `D_init`, then `runRemine`, which does not rebuild what depends on the sequences alone (block index, density measure, flat arrays of sequences already seen), as a rescan of the proposal does not |
| `Remine-HUSP-ULL`, `Remine-HUSP-SP` | mine the whole current database after every operation with HUSP-ULL or HUSP-SP at UUT, which stop at the threshold where the proposal's miner goes down to `r_l * UUT`; SC2 and the mixed streams | - | `HUSPULLAlgorithm`, `HUSPSPAlgorithm`, a new instance each time |
| `Joint-miner+SWUmax`, `Joint-miner+TSU` | the proposal with its rescan rule replaced by Pre-HUSPM's test on `SWUmax` or on `TSU(d)`, as `PreHUSPMAlgorithm` applies it; miner, tracking by utility and drop floor unchanged; SC2 (insertions only) | Yan et al., Theorems 2 and 1 | `PreHUSPMAdaptiveAlgorithm`, `TriggerRule.PRE_SWUMAX`, `PRE_TSU` |
| `Pre-HUSPM-Joint` (incremental scenarios) | the proposal with the break-even floor rule: the first rescan after the initial mine is at UUT (floor ratio 1, budget 0); every later rescan is at UUT iff `a < (rho - 1) / (1 - delta)`, where `rho` is the number of nodes the last mine at `r_l` explored over the number the last mine at UUT explored, `delta = 0`, and `a` the mean of the last 5 absorptions (units taken directly between a rescan and the next; after a rescan at UUT counted by a shadow of the joint counters at `r_l`). A modification is one unit and mines at most once: when its deletion would trigger, the deletion is applied directly and the insertion mines the database after it. Exact for every floor in `[r_l, 1]`; SC2, SC5 and the mixed streams. `analysis/check_break_even.py` replays the decisions from the data files and checks the logged inputs `floor_absorption`, `floor_rho`, `floor_delta` | - | `PreHUSPMAdaptiveAlgorithm`, `FloorRule.BREAK_EVEN` (`-Dadapt.floorRule=breakEven`), `mergeModification` (`-Dadapt.mergeModification=false` turns it off) |
| `Pre-HUSPM-Joint-w<n>`, n in {1, 2, 3, 5, 10} | the proposal with the window floor selection instead of the break-even rule: a rescan mines at UUT when the last `w = n` operations each charged more than the low budget `(1 - r_l) * UUT * TU` on their own, and at `r_l` otherwise; modifications merged as in the method. `w = 5` (`Pre-HUSPM-Joint-w5`) was the method's rule before the break-even rule and is its comparison arm; the others are the sensitivity to `w`. Run only when named (`-Dalgo.only`, or `campaign.ps1 -WindowSweep`); SC2, SC5 and the mixed streams. The window decisions are reproduced from the data files by `analysis/insertion_alpha.py --floor-windows` | - | `PreHUSPMAdaptiveAlgorithm`, `floorWindow` (`-Dadapt.floorWindow=w`) |
| `Pre-HUSPM-Joint-be<n>`, n in {1, 2, 3, 10, 20} | the method with `a` the mean of the last `n` absorptions instead of 5 (sensitivity to the window of the mean); SC2, SC5 and the mixed streams | - | `PreHUSPMAdaptiveAlgorithm`, `breakEvenWindow` (`-Dadapt.breakEvenWindow=n`) |
| `Pre-HUSPM-Joint-time` | the method with `rho` the time of the last mine at `r_l` over the time of the last mine at UUT and `delta` the time of the last unit taken directly over the time of the last mine at UUT, measured; two runs of a stream may take different decisions. The method's rule until commit 0e95c6c2; SC2, SC5 and the mixed streams | - | `PreHUSPMAdaptiveAlgorithm`, `BreakEvenCost.TIME` (the class default when `-Dadapt.breakEvenCost` is not `nodes`) |
| `Remine-own-UUT` | the proposal's own miner on the whole current database after every operation at UUT (lower threshold ratio 1, no pre-large patterns), as the proposal's rescans at `lambda = UUT` mine; a new instance each time; SC2, SC5 and the mixed streams | - | `PreHUSPMAdaptiveAlgorithm` with ratio 1, `runInitialMining` on `D_init`, then `runRemine` |
| `Remine-from-scratch-rebuild`, `Remine-own-UUT-rebuild` | the same two remining arms, rebuilding at every operation and inside the timed call what the mine reads from each sequence (`runRemineRebuild`), as the static miners of `Remine-HUSP-ULL` and `Remine-HUSP-SP` do. They are the remining rivals: every table and figure that compares the method with remining reads `Remine-from-scratch` and `Remine-own-UUT` from these arms (`make_figures.RIVAL_SOURCE`). The two arms without the suffix reuse what an earlier operation built and measure the mine alone; they stay in two analyses only: the tie band (`Remine-own-UUT` makes the method's search, so the time ratio measures how two JVMs compile it) and the price of the method's own rescans (`analysis/floor_policy_probe.py`, the warm rho of `break_even_floor_estimate`), which reuse the per-sequence state as they do. `analysis/check_rival_rebuild.py` checks that the two arms of a pair make the same search, row by row | - | `PreHUSPMAdaptiveAlgorithm`, `runRemineRebuild` (`AutomatedExperimentRunner.rebuildsSequences`) |
| `Joint-miner@UUT` | the proposal's miner in one static mine with its cutoff at UUT, the cutoff of HUSP-ULL and HUSP-SP; SC1 and SC3 | - | `PreHUSPMAdaptiveAlgorithm`, `runInitialMining` with ratio 1 |
| `Joint-rule+min-floor` | the proposal at the fixed lower threshold with the discard floor `L_ref + min_{i in X} A_i` instead of `L_ref + max_{i in X} A_i`; exact, since it drops no pattern the proposal keeps; SC2 and SC6 | - | `PreHUSPMAdaptiveAlgorithm`, `Floor.MIN` (`-Dadapt.floor=min`) |
| `Joint-rule+plain-floor` | the proposal at the fixed lower threshold with the discard floor `L_ref` alone, without a counter term; exact, since it drops no pattern the proposal keeps; SC2 and SC6 | - | `PreHUSPMAdaptiveAlgorithm`, `Floor.PLAIN` (`-Dadapt.floor=plain`) |
| `Joint-rule+PreHUSPM-tracking` | the proposal with its tracking by utility replaced by Pre-HUSPM's tracking: a mine first drops every item whose Definition 8 SWU is below `L_r` (Algorithm 3, line 13, of Yan et al.), then mines without irrelevant item pruning and tracks every pattern whose SWU on the projected database reaches `L_r`, with that bound; a direct insertion adds the pattern's utility and projected SWU in the batch, and a pattern is dropped when its bound falls below the discard floor; miner, search and rule unchanged; SC2 (insertions only). `verify.PreHuspmTrackingCheck` checks that it tracks the patterns and bounds of `PreHUSPMAlgorithm`'s own buffer | Yan et al., Definition 12 and the worked example of their Section 5 | `PreHUSPMAdaptiveAlgorithm`, `Tracking.PREHUSPM` (`-Dadapt.tracking=prehuspm`) |
| `Joint-miner+SWUmax+PreHUSPM-tracking` | the same tracking with Pre-HUSPM's SWUmax test in place of the joint rule: Pre-HUSPM's maintenance on the proposal's miner; SC2 (insertions only) | Yan et al., Theorem 2 | `PreHUSPMAdaptiveAlgorithm`, `TriggerRule.PRE_SWUMAX`, `Tracking.PREHUSPM` |
| `USpan` | static baseline, width pruning by SWU and depth pruning by a sound bound | Yin et al., KDD 2012 | `USpanAlgorithm` |
| `HUSP-ULL` | static baseline, UL-lists with irrelevant item pruning (IPS) and the look-ahead strategy (LAS), Algorithms 1 to 3 | Gan et al., IEEE Transactions on Cybernetics 2021 | `HUSPULLAlgorithm` |
| `HUSP-SP` | static baseline, seq-array and seqPro with IIP, early pruning by a sound bound, and the PEU test | Zhang et al., ACM TKDD 2024 | `HUSPSPAlgorithm` |

**How the window `w = 5` of the comparison arm was chosen** (when it was the method's rule). From the rescans the selection adds to the fixed floor on the seven
SC2 streams at `w` in {1, 2, 3, 5}, simulated from the data files with no time measured
(`results/invariant-insertion-alpha/floor-selection`), by inspection: no rule was written before
the choice. The rule that reproduces it, written afterwards, is: the smallest `w` whose selection
adds at most 3 rescans to the fixed floor on every stream. Every bound from 3 to 8 gives the same
`w`. `analysis/window_rule.py` applies it to the counts at `w` in {1, 2, 3, 5, 10}
(`results/invariant-insertion-alpha/floor-selection-windows`), on all seven streams and leaving
each stream out in turn; the windows it picks were fixed before the window arms were timed.
DUNNHUMBY, the eighth SC2 stream, was simulated after its window arms had been timed
(`results-invariant/floor-selection-windows/2026-11-11`, which repeats the seven streams with the
same counts). The rule picks the same `w` on the eight streams, and the window picked for DUNNHUMBY
when it is left out is the one fixed on the seven.

**The method in the incremental scenarios** is the proposal with the rescan floor selection, arm
`Pre-HUSPM-Joint`; the arm at the fixed floor is `Pre-HUSPM-Joint-fixed-threshold`. The static
scenarios mine once, which the selection does not change, and their arm `Pre-HUSPM-Joint` is that mine.
The arms were named `Adaptive-FloorSelect` and `Adaptive-Full` until commit 88f3017c
(`docs/arm-names.md`). The rescan-rule table compares the
three rules at the fixed floor and gives the method's rescans in its own column; the isolation table
isolates the components at the fixed floor.

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

Nine databases. The data are not kept in this repository. The sequences of seven come from
the SPMF dataset collection; their generated quantities and profits are release
`v2.0.0-utilities` of `tran-minh-thai/huspm-datasets`. `fetch-datasets.ps1` / `fetch-datasets.sh`
download both, assemble the databases with the release's `tools/assemble.py`, and verify them
against `datasets.sha256`. These are the bytes every result was measured on; the measurements
read them from release `v1.2.1-measured-quantity`, which holds the same 14 files. TAFENG is built
locally from the raw Ta-Feng file by `tafeng/build_tafeng.py` and pinned by `tafeng/TAFENG.sha256`.
DUNNHUMBY is built locally from dunnhumby "The Complete Journey"
(<https://www.dunnhumby.com/source-files/>), which is not redistributed, by
`dunnhumby/build_dunnhumby.py`; `dunnhumby/SOURCE.sha256` pins the two source files and
`dunnhumby/DUNNHUMBY.sha256` the result.

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
| DUNNHUMBY | 38,790 | 89,038 | 6.28 | 9.98 | 7,062,219 | measured |

Counted from the files as `DatasetLoader` reads them (`analysis/database_stats.py`). C8T1S5I8N5K's `_seq` file ends with an
empty sequence, which the loader skips.

Generated utilities (seven databases): the sequence structure comes from SPMF; the internal
utility (quantity) is drawn 70 percent from [1, 2], 20 percent from [3, 5] and 10 percent from
[6, 10]; the external utility (unit profit) is `exp(N(2.5, 1.0))`, rounded and clamped to
[1, 1000]; seed 42, one generator per input file. The generator is
`tran-minh-thai/convert-spmf-to-quantitative`.

Measured utilities (TAFENG): the internal utility is Ta-Feng's `AMOUNT`, the external utility
the median of `SALES_PRICE / AMOUNT` per product; one sequence per customer and month, kept
when it holds at least two events.

Measured utilities (DUNNHUMBY): the internal utility is `QUANTITY`, the units bought, summed per
product within a shopping trip; the external utility is the median of `SALES_VALUE / QUANTITY`
per product. One sequence is one household in one block of four weeks, one event one shopping
trip (`BASKET_ID`); a sequence is kept when it holds at least two events. Lines of the commodity
COUPON/MISC ITEMS and lines with a quantity or a sales value of zero or less are dropped.

C8T1S5I8N5K and DUNNHUMBY are the two databases whose events hold more than one item, so they
are the ones that exercise within-event extension. The item ids of C8T1S5I8N5K reach 4,999,999 and are replaced at load
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
rescan it drops a tracked pattern `X` only when `u(X) < LUT_min * TSU_ref + max_{i in X} A_i`
(`-Dadapt.floor=max`; `min` and `plain` select the ablation floors).
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
| SC1 | static mining of `D_init`, four UUT levels, six algorithms | all nine | `SC1_static.csv` |
| SC2 | `D_init` then `K = 100` equal increments; the proposal, the three remining arms, `Pre-HUSPM-SWUmax`, `Pre-HUSPM-TSU`, IncUSP-Miner+, and the isolation arms `Joint-miner+SWUmax`, `Joint-miner+TSU`, `Joint-rule+PreHUSPM-tracking`, `Joint-miner+SWUmax+PreHUSPM-tracking`, `Joint-rule+min-floor`, `Joint-rule+plain-floor` | eight (not BMS1) | `SC2_incremental.csv` |
| SC3 | static mining of the whole database, four UUT levels, six algorithms | SIGN, LEVIATHAN, C8T1S5I8N5K | `SC3_su.csv` |
| SC4 | static mining of the whole database at `r_l` in {0.3, 0.5, 0.7, 0.9}, Pre-HUSPM and the proposal at the same `r_l` | SIGN, LEVIATHAN, C8T1S5I8N5K | `SC4_sl_sensitivity.csv` |
| SC5 | the increments cut EVEN, INCREASING, DECREASING and BURST at `K` in {100, 67, 50, 25}; the SC2 arms | SIGN, LEVIATHAN, C8T1S5I8N5K | `SC5_batch_strategy.csv` |
| SC6 | twenty operations, 14 insertions, 3 deletions and 3 modifications in a seeded order, each a hundredth of the incremental stream; the proposal and the three remining arms replay the same plan (`common.MixedPlan`) | SIGN, LEVIATHAN, C8T1S5I8N5K, TAFENG, DUNNHUMBY | `SC6_idm_mixed.csv` |
| SC6D | as SC6 with 6 insertions, 10 deletions and 4 modifications | SIGN, LEVIATHAN, C8T1S5I8N5K, TAFENG, DUNNHUMBY | `SC6D_idm_mixed.csv` |
| SC6B | SC6's schedule; one of its deletions, at a position a stream of its own draws from the seed, takes random current sequences until their utility exceeds `(1 - r_l)` times the largest total utility the database has had, so that it alone exceeds the joint rule's budget; its row is `SC6_DEL_BIG`. The operations before it are SC6's for the same seed | SIGN, LEVIATHAN, C8T1S5I8N5K, TAFENG, DUNNHUMBY | `SC6B_idm_mixed.csv` |
| SC8 | a long stream of 500 operations, each a thousandth of the sequences after `D_init`: 70% insertions, 15% deletions and 15% modifications as exact counts in a seeded order, three seeds; the method, its fixed-threshold and window-5 versions and the remining arms replay the same plan (`common.StreamPlans`) | SIGN, LEVIATHAN, C8T1S5I8N5K, TAFENG, DUNNHUMBY | `SC8_long.csv` |
| SC8P | as SC8 with a phase change: the first half 80% insertions, 10% deletions and 10% modifications, the second half 10%, 80% and 10% | the same five | `SC8P_long.csv` |
| SC9 | as SC8 with insertions and deletions of the same size alternating, so the database keeps about its size; also `Pre-HUSPM-Joint-index-rebuild`, the method with its block index rebuilt from the current database at every rescan | the same five | `SC9_long.csv` |
| SC10 | SC8's proportions; a modification changes the sequences it picks instead of replacing them by others, and half of the modifications and of the deletions pick among the sequences of the last 20 insertions and modifications | the same five | `SC10_long.csv` |
| SC10H | SC8's schedule with every tenth insertion ten times as large | LEVIATHAN, C8T1S5I8N5K, TAFENG, DUNNHUMBY; SIGN holds fewer sequences after `D_init` than the stream draws | `SC10H_long.csv` |
| FIDELITY | the Pre-HUSPM implementation under the setting of its paper's runtime figure: the first OD.Num sequences mined, then 100 increments of a fixed number of sequences, Su and Sl as fractions of the total utility (SIGN 15%, 9%, 230, 5; FIFA 21%, 15%, 10,450, 80; BIBLE 16%, 10%, 21,369, 90; Kosarak10k 14%, 8%, 1,000, 90; BMS 3.6%, 2.7%, 39,601, 200), on SPMF's files with utilities (`fetch-spmf-husp`); LEVIATHAN is left out, SPMF publishes no file of it with utilities; one row per increment | the five SPMF files | `fidelity_increments.csv` |
| SC7 | ablation. The IUB bound (C3) and the irrelevant item pruning (`-Dadapt.iip=off`) on one mine of the whole database, with Pre-HUSPM for reference; the block index (C4) and the adaptive rescan decision (C5) on `D_init` and 100 increments, one row per increment | C3: eight (not BMS1); C4, C5: SIGN, C8T1S5I8N5K | `SC7_ablation.csv` |

BMS1 takes part in SC1 only: on the whole database 0.025 returns two patterns and 0.020
exceeds three billion candidates (`results/thresholds/bms1-whole`), and its four levels lie
below 0.020. In SC5 only BURST draws from the seed; EVEN, INCREASING and DECREASING are
deterministic. SC7's arms are `Pre-HUSPM-Joint`, `Pre-HUSPM-Joint-no-IUB`, `Pre-HUSPM-Joint-no-IPS`, `Pre-HUSPM` (one mine each)
and `Pre-HUSPM-Joint-rescan-rule`, `Pre-HUSPM-Joint-rescan-every-batch`, `Pre-HUSPM-Joint-no-block-index`, `Pre-HUSPM-Joint-bsz-<n>` (over the increments).

### Block size, stated before the measurement

The `Pre-HUSPM-Joint-bsz-<n>` arms repeat `Pre-HUSPM-Joint-rescan-rule` with blocks of n sequences, n in
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
* The SC1 live set is read from `results/campaign-mem-sampled` (`campaign.ps1 -MemMode live
  -MemGeneration sampled`): a static mine that ends before the sampler has taken
  `mem.minSamples` (5) readings while it runs is run again, as a fresh instance, until it has
  or `mem.repeatMaxMs` (120 s) has passed; `live_repeats` records the mines a row rests on.
  The SC1 rows of `results/campaign-mem` predate this and are not read; SC2 is read from
  `results/campaign-mem`, since an incremental stream is not repeated.

**What the clock covers.** `runtime_ms` is `System.nanoTime` around one call of the algorithm
(`AutomatedExperimentRunner.measure`); the call of each arm is named in the table. Outside the
clock for every arm: reading and parsing the two data files into sequences, the untimed warm-up
mines, and what the harness does between calls (the forced collection, building the list of
sequences an arm is handed, creating the algorithm object, the pattern check).

The data derived from a sequence's items and cached on the sequence object, its remaining
utilities and flat arrays ("derived data" below), follows one rule. A static mine builds,
inside its timed call, everything it reads from the loaded sequences, and keeps nothing for a
later call. Only an incremental algorithm carries state from one operation to the next, and it
builds that state inside its own earlier timed calls. The sequence utility, which the harness
reads to set the thresholds, stays on the sequence.

| Arms | Timed call | Derived data | Carried to the next call |
|---|---|---|---|
| `Pre-HUSPM-Joint`, `Joint-miner@UUT` in a static mine | `runInitialMining` | built for every sequence inside the call | nothing |
| `HUSP-ULL`, `HUSP-SP`; `Remine-HUSP-ULL`, `Remine-HUSP-SP` after every operation | `run` on a new instance | not read: each builds its own structures from the items inside the call | nothing |
| `USpan`, `IncUSP-Miner+`, `Pre-HUSPM` in a static mine | `run`, `runInitialMining` | built only when missing, so the harness drops it before the timed call and after the warm-up (`freshSequences`); it is then built inside the call | nothing |
| the method and its versions in a stream (`Pre-HUSPM-Joint`, `-fixed-threshold`, `-w<n>`, `-be<n>`, `-time`, the `Joint-rule+...` and `Joint-miner+...` arms) | `runInitialMining`, then one call per operation (`runIncrementalMining`, the deletion call, `runModification`) | the sequences of `D_init` inside the initial mine; an inserted sequence inside the call that inserts it | the derived data of the sequences already seen, the pattern sets and the block index: the state the maintenance keeps |
| `IncUSP-Miner+`, `Pre-HUSPM-SWUmax`, `Pre-HUSPM-TSU` in a stream | `runInitialMining`, then `runIncrementalMining` per operation | dropped for `D_init` and every batch before the arm's first call (`freshStream`), then built at first use inside the timed calls | their trees and pattern sets, and the derived data of the sequences already seen |
| `Remine-from-scratch-rebuild`, `Remine-own-UUT-rebuild`: the remining rivals of every table and figure | `runRemineRebuild` on a new instance after every operation | rebuilt for every sequence of the current database inside each call | nothing |
| `Remine-from-scratch`, `Remine-own-UUT` | `runRemine` on a new instance after every operation | built only for the sequences not seen before | the derived data of the sequences already seen, as a rescan of the method reuses it |

The last row is why those two arms are not the remining rivals: they reuse per-sequence data
built in earlier calls, which a static miner run after every operation would have to build
again. They are read in two other roles only, the control that measures the tie band and the
price of one rescan of the method (`make_figures.RIVAL_SOURCE`, `load_scenario(rivals=False)`).
`analysis/check_rival_rebuild.py` checks that each of them and its `-rebuild` arm make the same
search, counter by counter, and with `--direction` that no `-rebuild` arm is faster than the arm it
replaces by more than the tie band. In a memory run (`-Dmem.mode=live`) the harness drops the flat
arrays before every static mine of every arm, so that an arm's arrays count towards that arm,
and leaves the remaining utilities the loader computed, which count as part of the loaded
database for every arm.

**Static warm-up.** A static scenario (SC1, SC3, and the mines of SC7's static arms and of its
Pre-HUSPM reference) runs one arm on one database in a JVM, its thresholds in ascending order.
Without a warm-up the first cells of the JVM also pay for compiling the miner: on
C8T1S5I8N5K the proposal's miner at UUT took 431-455 ms at the first threshold and 69-74 ms at
the last. So before the first measured mine of an arm on a database, the same mine (same arm,
database and threshold, the lowest of the sweep) is run untimed and discarded, by a rule fixed
before the re-measurement: until two consecutive untimed mines differ by less than 10 percent,
at most 10 of them, and a single untimed mine longer than 10 s is enough (the transient is
under a second). The rule comes from a probe on the development machine, not a measurement:
the first cell of C8T1S5I8N5K took 641-650 ms with no untimed mine, 348-524 ms after one and
130-139 ms after three or five. `run.log` lists the untimed mines of each JVM ("warm-up mines").
A warm-up that runs out of memory or time is not repeated; its row is the cell's outcome. The
memory campaign has no warm-up (`-Dmem.mode=live`), and `-Dstatic.warmup=false` turns it off.
Every static timing run was measured again under the warm-up; `analysis/affected_runs.py
--static-warmup` lists them, and the `commit` of an arm's `DONE` file names the code it ran.

Status is encoded in `runtime_ms`: 0 or more is a completed run in milliseconds, -2 over the
time limit, -1 a failure or a skipped call, -3 a memory-campaign row.

**Pattern check.** With `-Dverify.husps=true` the runner compares, after every operation and
outside the timed call, every arm's patterns and utilities with the proposal's on the same
stream (the proposal runs first), writes `verify_husps.csv` and exits 1 on a mismatch, on a
comparison with no reference, or when nothing was compared. Such a run is a correctness check,
not a measurement, and the runner refuses to write it into a campaign tree.

## 6. Repetition

Every replicate is an arm of its own, measured in a fresh JVM like every arm, so that no
replicate runs on a JIT warmed by the one before it. `campaign.ps1 -Repeat n -SeedIndex k`
writes replicate `s<k>r<n>` to `results/campaign-replicates/s<k>r<n>/` (timing) or
`results/campaign-mem-replicates/s<k>r<n>/` (with `-MemMode live`); the main trees
`results/campaign` and `results/campaign-mem` are replicate `s0r0`. Rows carry the repeat and
the seed in their own columns.

| Scenario | Replicates | Spread reported for |
|---|---|---|
| SC1 | three repeats (`r0`, `r1`, `r2`) of every algorithm on the nine databases at the four thresholds; on DUNNHUMBY USpan, IncUSP-Miner+ and Pre-HUSPM have one run, their replicates being deferred | runtime at the default threshold; every SC1 comparison (fastest counts, geometric means) uses the mean of the runs |
| SC2 | five repeats (`r0` to `r4`) of every arm on the eight databases; three of the window arms other than `-w5` and of the `-be<n>` arms; one run of IncUSP-Miner+ on FIFA and DUNNHUMBY and of Pre-HUSPM-TSU on DUNNHUMBY | runtime summed over the increments; the SC2 tables print mean and deviation |
| SC3 | three repeats, every algorithm, the three databases | runtime and every SC3 comparison |
| SC5 | three repeats of the method, its time-rule and window-5 versions and the remining arms, and of its fixed-threshold version on SIGN and LEVIATHAN; one run of the other arms | runtime summed over the increments |
| SC7 | three repeats of the four one-mine arms (full, IUB off, IPS off, Pre-HUSPM) on the eight databases; one run of the incremental ablations | runtime of one mine |
| SC6, SC6D, SC6B | five seeds (`s0` to `s4`: 42, 1337, 2024, 31415, 271828), every arm | each arm's time over the method's per kind of operation and over the stream, paired by seed (`table_SC6_by_op_arms.csv`, `table_SC6D_by_op.csv`, `table_SC6B_by_op.csv`) |
| SC8, SC8P, SC9, SC10, SC10H | three seeds inside every run; on DUNNHUMBY also repeats of the whole run: five of the method, three of its fixed-threshold and window-5 versions, of `Remine-own-UUT` and of both rebuild arms | time summed over the three seeds; on DUNNHUMBY the ratio of two arms is taken inside each repeat they share a session in (`analysis/long_streams.py`) |
| memory | a second run (`s0r1`) of most SC1 cells and of some SC2 cells | the live set between two runs |
| others | one run | - |

The runs of every arm on every database, the replicates they are and the deferred ones are
listed in `figures/campaign/table_replicate_layout.csv`, generated from the `DONE` and `DEFERRED`
files of the result trees.

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
ablation the lowest runtime and the fewest explored nodes of each database; in the window
table the window with the lowest time). Runtimes are compared on their unrounded means, so two
means that only print alike are not tied; a cell measured three times whose runs overlap those
of the lowest is a tie and is bold as well. Counts are compared as printed. A run that did not
finish takes no part. Every table, figure and number on runtime reads the mean of the replicates
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
