# Pre-HUSPM-Adaptive

Incremental high-utility sequential pattern mining over evolving databases: a framework
that maintains the pattern set under insertion, deletion and modification instead of
re-mining, together with the baselines and the harness used to measure it.

This repository holds code, the experiment harness, and results. It does not hold the
manuscript, and it does not hold the databases — those are shared across several studies
and are fetched from a pinned release (see [Databases](#databases)).

## Layout

```
src/
  AutomatedExperimentRunner.java   scenario driver, measurement, CSV output
  ExperimentMetrics.java           one result row: every recorded column
  ResultCollector.java             CSV writer
  algorithms/                      the framework and every baseline
  common/                          sequence representation, dataset loader, patterns
  experiments/                     standalone studies (batch-size sweep, calibration)
  verify/                          correctness checks, run before measuring anything
analysis/
  aggregate.py                     mean and standard deviation over repeated runs
  make_figures.py                  figures and tables from result CSVs
datasets/                          fetched, not tracked; see Databases
examples/                          the worked example from the manuscript, four sequences
docs/
  experiments.md                   protocol: algorithms, scenarios, measurement, machine
  result-columns.md                every CSV column, column by column
  figures.md                       which figure comes from which file
results/
  v1-submitted/                    archived, read-only; no number in it enters this study
  run-<timestamp>-<commit>/        one directory per run, each with its own manifest
figures/                           generated, one directory per run
fetch-datasets.ps1 / .sh           fetch and verify the databases
run.ps1 / run.sh                   build, run scenarios, write the manifest, plot
```

## Requirements

| | |
|---|---|
| JDK | **25**. The code builds on 17 or later, but a measurement campaign is pinned to one version because runtimes from two versions do not belong in one table; `campaign.ps1` refuses to run on anything else. |
| Python | 3.9 or later, for `analysis/`. `matplotlib` and `pandas` for figures; `aggregate.py` needs only the standard library. |
| Disk | about 1 GB: 100 MB of databases, the rest results. |
| Memory | the heap must fit in physical RAM. `run.ps1` refuses to start otherwise, because a heap larger than RAM does not fail fast — it thrashes the page file, inflates every timing, and eventually dies with a native allocation failure. |
| OS | Windows through `run.ps1`, Linux and macOS through `run.sh`. |

## Databases

The databases are not kept here. They are shared across several studies, so they live in
one place and each study pins the exact release it measured on:

    https://github.com/tran-minh-thai/huspm-datasets

```powershell
.\fetch-datasets.ps1                    # Windows
```
```bash
./fetch-datasets.sh                     # Linux, macOS
```

This downloads the release pinned in the script, extracts it into `datasets/`, and checks
every file against the SHA-256 manifest attached to that release. A mismatch stops the
script rather than letting numbers be produced on the wrong bytes. Re-check at any time
with `-VerifyOnly` (`VERIFY_ONLY=1` for the shell script).

**Pinned release: `v1.2.1-measured-quantity`.** Changing it changes the data and therefore
every result; re-run everything after changing it, and record the tag beside the numbers.

| Database | Sequences | Items | Avg. length | Items/event | Total utility | Utilities | `D_init` | `r_l` | Default UUT |
|---|---:|---:|---:|---:|---:|---|---:|---:|---:|
| SIGN | 730 | 267 | 52.00 | 1.00 | 1,796,483 | generated | 230 | 0.80 | 0.065911 |
| LEVIATHAN | 5,834 | 9,025 | 33.81 | 1.00 | 10,502,159 | generated | 2,834 | 0.80 | 0.015530 |
| FIFA | 20,450 | 2,990 | 36.24 | 1.00 | 42,781,841 | generated | 10,450 | 0.80 | 0.059637 |
| BIBLE | 36,369 | 13,905 | 21.64 | 1.00 | 35,550,162 | generated | 21,369 | 0.80 | 0.022895 |
| BMS1 | 59,601 | 497 | 2.51 | 1.00 | 7,229,030 | generated | 39,601 | 0.80 | 0.013597 |
| KOSARAK | 990,002 | 41,270 | 8.10 | 1.00 | 385,921,994 | generated | 90,002 | **0.85** | 0.025000 |
| C8T1S5I8N5K | 47,132 | 68,240 | 18.83 | 7.97 | 47,416,411 | generated | 27,133 | 0.80 | 0.001132 |
| TAFENG | 60,665 | 23,812 | 13.40 | 1.00 | 106,638,996 | **measured** | 40,665 | 0.80 | 0.003557 |

The table is a copy for reading. `common.Datasets` is the single place these values live, and
`java -cp build AutomatedExperimentRunner STATUS` prints them from there; a tool that needs a
threshold, a `D_init` or an `r_l` reads that class rather than carrying its own table.

`D_init` is a count of sequences, not a fraction, and comes from Yan et al. (J Supercomputing
2025, 81:81) Table 16 `OD.Num` for the five databases shared with them — 230 of SIGN's 730,
not half of anything. The incremental stream is the remainder, always a round number. A flat
50 percent instead makes every database merely double, and on KOSARAK it turns a tenfold
growth into a twofold one, which is enough to leave `rescan_count` at zero across most of the
grid; a quantity that is zero almost everywhere is not being measured.

`r_l` sets the pre-large floor, `LUT_min = r_l * UUT`. It is 0.80 for seven of the eight.
KOSARAK needs 0.85: at 0.80 its lowest threshold exceeds three billion candidates and is
abandoned after 103 s, while 0.85 returns 16 patterns in 29 s. Yan et al. treat `Sl` as a
per-database choice throughout their section 6.3.1, and both the proposal and the baseline
read the same value here.

All eight are measured. Two more columns carry most of the weight here.

Items per event: seven of the eight hold a single item, so they exercise across-event
extension only; `C8T1S5I8N5K` alone reaches within-event extension, at 7.97 items per event.

Item ids: `C8T1S5I8N5K` draws its 68,240 items from ids up to 4,999,999, and every miner here
sizes its per-item arrays by the largest id. The loader therefore replaces each of its ids by
its rank among the ids of the `_eui` file (`common.Datasets.compactItemIds`). The order of the
items is kept, so every miner returns the same patterns under the new labels; what changes is
memory, which on `D_init` falls from 784 MB to 46 for the proposal and from 357 MB to 162 for
Pre-HUSPM. The files themselves are untouched and still match the release checksums. The other
seven databases are loaded as they are.

Utilities: seven are generated by the shared converter (`log-normal, seed 42`), and only
`TAFENG` is measured on both sides — quantities are Ta-Feng's `AMOUNT`, profits its
`SALES_PRICE / AMOUNT`. A suite drawn from generated utilities alone cannot answer whether
internal utility matters at all, which is why one measured database is worth the trouble.
`TAFENG` is also the one database here that a compared algorithm, IncUSP-Miner+, already
measured on, so it is not a dataset chosen to suit this work.

Select any one with `-Ddataset.only=NAME`.

The release also carries `ONLINE_RETAIL_II_ALL` and `ONLINE_RETAIL_II_QTY`, which this study
measured and then dropped; the runner records why.

### TAFENG is built here, not downloaded

`TAFENG` is the one database not in the pinned release. Ta-Feng is distributed under terms
this project does not rely on, so the raw file is not redistributed and the database is built
locally instead. That costs one step and gives up nothing: the build is deterministic, so the
two files land on the same bytes on any machine, and `tafeng/TAFENG.sha256` pins them exactly
as the release manifest pins the other eighteen. `fetch-datasets` checks them and
`-VerifyOnly` fails if they are missing or wrong.

Get `ta_feng_all_months_merged.csv` — 817,741 rows, November 2000 to February 2001 — from
[Kaggle](https://www.kaggle.com/datasets/chiranjivdas09/ta-feng-grocery-dataset), then:

```bash
python tafeng/build_tafeng.py --zip archive.zip --out-dir datasets --name TAFENG
sha256sum -c tafeng/TAFENG.sha256          # Windows: .\fetch-datasets.ps1 -VerifyOnly
```

What the build does, and why each part is not a detail:

- **One sequence per customer per month**, events ordered by date. Ta-Feng gives each
  transaction a date but no time, so ordering *between* days is real and ordering within a
  day is file order. Gathering a customer's whole history into one sequence instead would
  produce the shape that makes `ONLINE_RETAIL_II_QTY` unusable.
- **Unit price is `SALES_PRICE / AMOUNT`.** `SALES_PRICE` is the line total, not a unit price:
  across 4,378 products appearing with both `AMOUNT=1` and `AMOUNT=2`, the ratio of median
  `SALES_PRICE` is 2.000. Reading it as a unit price inflates every utility by the `AMOUNT`
  factor, and nothing in the pipeline would report an error.
- **Profit per item is the median of its unit prices**, and item ids are assigned by sorted
  product id. Both are what make the output reproducible rather than dependent on row order.
- Sequences shorter than two events are dropped; they cannot contribute a sequential pattern.

### Thresholds are calibrated per release, on `D_init`

Thresholds are ratios of total utility, so they remain *well defined* across releases. They
do not remain *comparable*: the same ratio selects a different number of patterns once the
shape of the utility distribution changes, and the shift has no common direction. On this
release SIGN needs thresholds about 2.5 times higher than LEVIATHAN needs lower, and BIBLE
barely moves — BIBLE being one database whose bytes the release left unchanged, which is a
useful check on the procedure itself. No single factor could have been applied by hand.

`experiments.ThresholdScan` chooses them. It searches for a level that is a usable operating
point: enough patterns that the answer is not trivial, cheap enough that a scenario grid
fits in the measurement budget.

```bash
java -Xmx48g -Xss64m -cp build experiments.ThresholdScan ALL --out results/thresholds
```

Calibrated on `D_init`, with the search output kept per machine in
`results/thresholds/thresholds-<host>.csv`. Pattern counts do not depend on the machine, so
this can be run anywhere; the runtimes the tool prints are for impatience only and are not
reportable.

| Database | Calibrated sweep | Patterns obtained on `D_init` |
|---|---|---|
| SIGN | **0.049134 0.065911 0.083371 0.091052** | 2814 583 140 79 |
| LEVIATHAN | **0.014644 0.015530 0.016470 0.017987** | 136 107 89 63 |
| BIBLE | **0.018100 0.022895 0.028960 0.032570** | 376 192 96 58 |
| BMS1 | **0.012821 0.013597 0.015293 0.016218** | 22 21 18 15 |
| FIFA | **0.056234 0.059637 0.067073 0.075436** | 42 18 5 1 |
| KOSARAK | **0.022228 0.025000 0.028117 0.031623** | 11 10 7 5 |
| C8T1S5I8N5K | **0.001099 0.001132 0.001200 0.001236** | 6101 5082 3427 2726 |
| TAFENG | **0.002812 0.003557 0.004499 0.005691** | 10 6 5 3 |

A level has to hold up on the whole database as well as on `D_init`, because SC1 is the only
scenario that stays on `D_init`. C8T1S5I8N5K shows why: an earlier sweep matched its targets
on the half and collapsed on the whole, 153 patterns becoming 1, so three scenarios would
have been comparing algorithms against an answer of one pattern. Every level above keeps at
least 23 percent of its `D_init` count when the database grows. BMS1 fails this outright — no
threshold mines the whole database, 0.030 exceeding three billion candidates while 0.032
returns the empty set — so it carries `fullScopeOk = false` and appears in SC1 alone.

Two databases are bounded from below rather than freely chosen, and the difference is worth
stating. KOSARAK's ladder is the widest spread it allows: 0.019 already exceeds three billion
candidates, so eleven patterns is a ceiling and not a preference. TAFENG is the same shape —
ten patterns at 0.0029 is the most it yields before 0.0028 exceeds the budget — and its sweep
sits just above that, at the values `ThresholdScan` converges to on its own, so re-running the
tool reproduces the row rather than standing a second ladder beside it.

Every database the runner names is calibrated. One that is not would be **skipped** with a
message naming the command to calibrate it, rather than measured at an operating point
chosen for different data; `-Duncalibrated=true` overrides that for exploration.

## Build and run

`run.ps1` and `run.sh` compile, write a manifest, run the scenarios, and plot.

**Rebuild after every pull.** These scripts are what compiles, so a pull leaves `build/`
holding the previous checkout, and a class added by that pull reports `could not find or
load main class` when invoked directly.

```powershell
.\run.ps1 -BuildOnly                                     # compile and stop
.\run.ps1                                                # every scenario
.\run.ps1 SC1 SC2                                        # selected scenarios
.\run.ps1 TEST SC1                                       # smoke test on examples/, minutes
.\run.ps1 -Heap 48g -TimeoutMin 120 -ExpectMachine 'PC'  # a measurement run
.\campaign.ps1 -Scenario SC1 -MemMode live            # the memory campaign
```

To run a single class directly, compile first:

```powershell
.\run.ps1 -BuildOnly
java -Xmx48g -Xss64m -cp build AutomatedExperimentRunner SC1
```
```bash
javac -encoding UTF-8 -d build $(find src -name '*.java')
java -Xmx48g -Xss64m -cp build AutomatedExperimentRunner SC1
```

Verify correctness before measuring anything:

```bash
.\fetch-datasets.ps1 -VerifyOnly                          # databases still intact
java -cp build verify.WP1AcceptanceTest                   # must report 15 passed, 0 failed
java -cp build verify.BaselineAgreement SIGN              # static baselines must agree exactly
java -Xmx48g -cp build verify.CorrectnessVerifier LEVIATHAN --rule iaumax
```

### Scenarios

| Scenario | What it does | Output |
|---|---|---|
| SC1 | static mining on `D_init`, over four thresholds | `SC1_static.csv` |
| SC2 | `D_init` plus **100 equal increments** of the remainder | `SC2_incremental.csv` |
| SC3 | threshold sweep on the full database | `SC3_su.csv` |
| SC4 | lower-threshold ratio sweep, `r_l` in {0.3, 0.5, 0.7, 0.9} | `SC4_sl_sensitivity.csv` |
| SC5 | increment count `K` in **{100, 67, 50, 25}** crossed with four partitioning strategies | `SC5_batch_strategy.csv` |
| SC6 | twenty mixed operations, exactly 14 insertions, 3 deletions and 3 modifications in a seeded order, each one hundredth of the stream, incremental against remine-from-scratch | `SC6_idm_mixed.csv` |
| SC7 | ablation: C3 on one mine of the whole database, against Pre-HUSPM; C2, C4 and C5 on `D_init` plus 100 increments, one row per increment, on SIGN and C8T1S5I8N5K | `SC7_ablation.csv` |

One hundred increments, not a handful. Yan et al. section 6.1 divide each database into "an
original dataset and 100 new datasets", and their section 6.3.1 confirms the arithmetic:
SIGN's 500 incremental sequences arrive as "5 sequences per increment". Their Fig. 7f reads
rescans straight off the curve — "each inflection point on the curve corresponds to a
database rescan" — which needs a curve with enough points to have inflections.

The count is also what makes the comparison say anything. An increment that fits inside the
insertion safety budget needs no rescan, and a finer cut puts more increments inside it: on
SIGN the share of increments that rescan falls from 96 percent at `K=25` to 52 percent at
`K=100`. With it the proposal's time falls from 0.97 of remine-from-scratch's to 0.50. At a
handful of increments every one of them rescans and the proposal is a remine, so there is
nothing to see (`results/campaign/SC5`). `common.Datasets.BATCH_COUNT`
holds the 100 and `-Dbatch.count` overrides it for cost probes; `BATCH_COUNT_SWEEP` holds
SC5's ladder, which keeps the total incremental data fixed and varies only how it is cut,
as in their Figs. 5-7.

Standalone studies, run directly rather than through `run.ps1`:

| Class | What it answers | Output |
|---|---|---|
| `experiments.BatchSizeSweep` | rescan rate against batch size, for all three triggers | `SC_batchsize.csv` |
| `experiments.PreHuspmVariantCompare` | the two Pre-HUSPM triggers under the protocol of the original paper | `prehuspm_variants.csv` |
| `experiments.MachineCalibration` | how much slower this machine is than a reference, which sets the per-call limit | console |
| `verify.CorrectnessVerifier` | pattern sets against remine-from-scratch after every operation | `verification.csv` |
| `verify.BaselineAgreement` | static baselines return identical pattern sets | console |
| `verify.WP1AcceptanceTest` | the safety-bound counterexamples from review | console |

### Reading the results

Every scenario writes one row per measured call. `analysis/aggregate.py` groups rows by
configuration and reports mean and sample standard deviation:

```bash
python analysis/aggregate.py results/run-.../ --csv aggregate.csv
```

`docs/result-columns.md` defines every column. The parts that are easy to misread:

* `runtime_ms` carries status in negative values: `-1` failed, `-2` overtime, `-3` a
  live-heap run whose timing is void by construction.
* `peak_heap_mb` is used heap and includes uncollected garbage, so it grows with `-Xmx`
  and depends on what ran earlier in the same JVM. `mem_live_mb`, produced by
  `-Dmem.mode=live`, is the live set and is the memory figure to use.
* `candidate_count` counts extensions before any pruning, so it is comparable across
  algorithms. `explored_nodes` counts after pruning and is what measures pruning.

## Parameters

Everything below changes results. Defaults are what the scenarios use unless overridden.

| Symbol in the paper | Flag | Default | Meaning |
|---|---|---|---|
| UUT | per dataset, table above | see table | upper utility threshold, as a ratio of total utility |
| r_l | `DEFAULT_SL_MIN_RATIO` in the runner | 0.8 | lower-threshold ratio; `LUT_min = r_l * UUT` |
| kappa | `-Dkappa` | 0.5 | coefficient of the adaptive term |
| Delta_0 | `-Ddelta0` | 1e-4 | initial margin |
| B_sz | `-Dblock.size` | 100 | block size of the block-level inverted index |
| — | `-Dadapt.trigger` | `iaumax` | insertion rescan trigger: `swumax` (the unsound bound of the original method, kept for reference), `cub`, `iaumax` |
| — | `-Dadapt.strictModify` | `true` | a modification spends `u(old) + u(new)` of the safety budget; `false` reproduces the earlier, unsound accounting |
| — | `-Dtimeout.min` | 120 | wall-clock limit per call, in minutes |
| — | `-Drepeats` | 3 | reruns of an identical configuration, for the standard deviation |
| — | `-Dseeds` | 1 | how many entries of `SEEDS` to sweep; only SC2 (above 1), SC5 `BURST` and SC6 are affected |
| — | `-Dmem.mode` | `used` | `live` measures the live set instead of used heap |
| — | `-Dmem.interval.ms` | 20 | shortest wait between forced collections in live mode; stretched to four times the last collection |
| — | `-Dalgo.only` | all | run one algorithm only; required in live mode |
| — | `-Ddataset.only` | all | run one database only |
| — | `-Dresults.dir` | `results/` | where CSVs are written |
| — | `-Xmx`, `-Xss` | `24g`, `64m` | heap and thread stack; deep recursion needs the stack |

Algorithm names for `-Dalgo.only` and `-MemArms`: `USpan`, `HUSP-ULL`, `HUSP-SP`,
`IncUSP-Miner+`, `Pre-HUSPM`, `Pre-HUSPM-TSU`, `Adaptive-Full`, `Remine-from-scratch`.
`HUSP-SP` is static and appears in SC1 and SC3 only.

## Data format

Two files per database. `NAME_seq.txt` holds the sequences:

```
1[2] 2[1] -1 3[5] -1 1[3] 4[2] -1 5[1] -2
```

`item[quantity]` is one item, `-1` ends an event, `-2` ends a sequence. `NAME_eui.txt`
holds one profit per item as `item:profit`. Lines starting with `#`, `%` or `@` are
comments. The utility of an item is quantity times profit.

## Provenance

Each run writes `results/run-<timestamp>-<commit>/manifest.json` recording the commit, whether
the working tree was clean, the JDK, the JVM flags, the machine and its RAM, the timeout, the
memory mode, the power plan, and the SHA-256 of every database file. Numbers without a
manifest are not used.

`results/v1-submitted/` is an archived directory kept read-only. No number in it belongs to
this study and none of its rows enters a table or a figure. `results/campaign-jdk21/` is the
same: arms measured under an earlier JDK and mixed heap ceilings, kept for their cost figures
and failure modes.

## License

MIT, see `LICENSE`. The databases are distributed under the terms of the release they come
from. Cite the accompanying paper if you use this; see `CITATION.cff`.
