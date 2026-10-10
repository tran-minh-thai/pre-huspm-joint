# Pre-HUSPM-Adaptive

Incremental high-utility sequential pattern mining. Pre-HUSPM-Adaptive maintains the set of
high-utility sequential patterns of a database under insertions, deletions and
modifications, and remines only when a joint utility budget is exceeded. The repository
contains the algorithm, five baselines, the experiment harness, the correctness checks and
the measured results.

## Repository layout

```
src/
  algorithms/                 Pre-HUSPM-Adaptive and the baselines
  common/                     sequences, loader, patterns, dataset table (Datasets.java)
  experiments/                standalone studies (threshold calibration, batch-size sweep)
  verify/                     correctness checks against remining from scratch
  AutomatedExperimentRunner.java   scenario driver; writes one CSV row per measured call
analysis/                     aggregation, figures, tables, run manifests
tafeng/                       builds the TAFENG database from the public Ta-Feng file
examples/                     a four-sequence example database
docs/                         experiment protocol and the definition of every result column
results/                      measured results, one directory per arm (see Results)
figures/                      figures and tables generated from results/
run.ps1, run.sh               build, run scenarios, write a manifest
campaign.ps1                  run the full measurement campaign, one JVM per arm
fetch-datasets.ps1, .sh       download and verify the databases
environment.lock.json         pinned JDK, JVM flags and dataset release
```

## Requirements

| | |
|---|---|
| JDK | 25. Results were measured on Eclipse Temurin 25.0.4.1+1 (`environment.lock.json`); `campaign.ps1` refuses any other build. The code compiles on JDK 17 or later. |
| Python | 3.9 or later, with `pip install -r analysis/requirements.txt` for figures and tables. |
| Memory | the Java heap must fit in physical RAM; measurements use `-Xmx48g` on a 64 GB machine. |
| OS | Windows (`run.ps1`, `campaign.ps1`), Linux and macOS (`run.sh`). |

## Data

The databases are kept in a shared repository, https://github.com/tran-minh-thai/huspm-datasets,
pinned to release `v1.2.1-measured-quantity`. The fetch script downloads the release into
`datasets/` and checks every file against its SHA-256 manifest.

```powershell
.\fetch-datasets.ps1                # Windows; -VerifyOnly re-checks existing files
```
```bash
./fetch-datasets.sh                 # Linux, macOS; VERIFY_ONLY=1 re-checks existing files
```

TAFENG is built locally from the Ta-Feng grocery file
(`ta_feng_all_months_merged.csv`, available on
[Kaggle](https://www.kaggle.com/datasets/chiranjivdas09/ta-feng-grocery-dataset)). The build
is deterministic and `tafeng/TAFENG.sha256` pins its output:

```bash
python tafeng/build_tafeng.py --zip archive.zip --out-dir datasets --name TAFENG
(cd datasets && shasum -a 256 -c ../tafeng/TAFENG.sha256)
```

It forms one sequence per customer and month, uses `AMOUNT` as quantity and
`SALES_PRICE / AMOUNT` as unit profit, and drops sequences with fewer than two events.

| Database | Sequences | Items | Avg. length | Items/event | Utilities | `D_init` | `r_l` | Default UUT |
|---|---:|---:|---:|---:|---|---:|---:|---:|
| SIGN | 730 | 267 | 52.00 | 1.00 | generated | 230 | 0.80 | 0.065911 |
| LEVIATHAN | 5,834 | 9,025 | 33.81 | 1.00 | generated | 2,834 | 0.80 | 0.015530 |
| FIFA | 20,450 | 2,990 | 36.24 | 1.00 | generated | 10,450 | 0.80 | 0.059637 |
| BIBLE | 36,369 | 13,905 | 21.64 | 1.00 | generated | 21,369 | 0.80 | 0.022895 |
| BMS1 | 59,601 | 497 | 2.51 | 1.00 | generated | 39,601 | 0.80 | 0.013597 |
| KOSARAK | 990,002 | 41,270 | 8.10 | 1.00 | generated | 90,002 | 0.80 | 0.025000 |
| C8T1S5I8N5K | 47,132 | 68,240 | 18.83 | 7.97 | generated | 27,133 | 0.80 | 0.001132 |
| TAFENG | 60,665 | 23,812 | 13.40 | 1.00 | measured | 40,665 | 0.80 | 0.003557 |

Items counts the profit table (`_eui`); TAFENG has 23,789 of its 23,812 items in some sequence.
Generated utilities are log-normal with seed 42. `D_init` is the number of sequences mined
first; the remaining sequences form the update stream. `src/common/Datasets.java` is the
single source of these values and of the threshold sweeps;
`java -cp build AutomatedExperimentRunner STATUS` prints them.

File format, two files per database: `NAME_seq.txt` holds one sequence per line, written as
`item[quantity]`, with `-1` closing an event and `-2` closing the sequence; `NAME_eui.txt`
holds one `item:profit` per line. The utility of an item occurrence is quantity times profit.

## Build

```powershell
.\run.ps1 -BuildOnly
```
```bash
javac -encoding UTF-8 -d build $(find src -name '*.java')
```

Rebuild after every pull.

## Run

A smoke test on `examples/` takes under a minute:

```powershell
.\run.ps1 TEST SC1
```
```bash
HEAP=8g ./run.sh TEST SC1
```

Correctness checks, each against remining from scratch:

```bash
java -cp build verify.RescanRuleAcceptanceTest                              # safety-bound cases for every rescan rule
java -cp build verify.BaselineAgreement SIGN                                # static miners return identical pattern sets
java -Xmx12g -Xss64m -cp build verify.DirectPathVerifier ALL --adversarial --ops 16 --out results/verify-joint/adversarial
java -cp build verify.MixedBudgetFuzz joint 5000 11                         # random insert, delete and modify streams: rule, trials, seed
java -Xmx24g -Xss64m -cp build verify.IncUspVerifier random 300 7           # IncUSP-Miner+ after every insertion batch
java -Xmx24g -Xss64m -cp build verify.IncUspVerifier real SIGN 100          # the same on a real database, 100 batches
java -Xmx4g -cp build verify.ItemRemovalCheck random 2000 1                 # item removal in the proposal's miner against brute force
java -Xmx24g -Xss64m -cp build verify.ItemRemovalCheck real SIGN whole      # off, once and fixpoint return the same patterns
```

Scenarios, one at a time or all:

```powershell
.\run.ps1 SC1 SC2                                         # selected scenarios
.\campaign.ps1 -Scenario SC2                              # full campaign of one scenario, one JVM per arm
.\campaign.ps1 -Scenario SC1 -MemMode live                # memory campaign (live set)
.\campaign.ps1 -Scenario SC2 -Repeat 1                    # a replicate, in a fresh JVM
.\campaign.ps1 -List                                      # arms not yet measured
.\campaign.ps1 -Calibrate                                 # settings fixed by measurement before the campaign
```

| Scenario | What it measures | Databases | Output file |
|---|---|---|---|
| SC1 | static mining of `D_init` at four thresholds | all eight | `SC1_static.csv` |
| SC2 | `D_init` followed by 100 equal insertion batches | all but BMS1 | `SC2_incremental.csv` |
| SC3 | static mining of the whole database at four thresholds | SIGN, LEVIATHAN, C8T1S5I8N5K | `SC3_su.csv` |
| SC4 | floor ratio `r_l` in {0.3, 0.5, 0.7, 0.9} on the whole database | SIGN, LEVIATHAN, C8T1S5I8N5K | `SC4_sl_sensitivity.csv` |
| SC5 | batch count K in {100, 67, 50, 25} under four batch-size strategies | SIGN, LEVIATHAN, C8T1S5I8N5K | `SC5_batch_strategy.csv` |
| SC6 | 20 operations (14 insertions, 3 deletions, 3 modifications) in seeded order | SIGN, LEVIATHAN, C8T1S5I8N5K | `SC6_idm_mixed.csv` |
| SC7 | ablations: IUB pruning and irrelevant item pruning on one mine of the whole database; block index and adaptive rescan over `D_init` and 100 batches | all but BMS1; SIGN and C8T1S5I8N5K for the last two | `SC7_ablation.csv` |

Algorithm names, as used by `-Dalgo.only` and in the `algorithm` column: `Adaptive-Full`
(the proposed method), `Remine-from-scratch` (the same miner run on the whole database after
every operation), `Pre-HUSPM-SWUmax` and `Pre-HUSPM-TSU` (the two variants of Yan et al., 2025:
the rescan test of their Theorem 2, on the largest sequence-weighted utility of an item in the
inserted sequences, and of their Theorem 1, on the total utility of the inserted sequences, both
accumulated since the last rescan), `IncUSP-Miner+`, `USpan`, `HUSP-ULL`, `HUSP-SP`. In the
static scenarios the two Pre-HUSPM variants mine identically and run as one arm, `Pre-HUSPM`.

## Results

```
results/
  campaign/<SC>/<DATABASE>/<ALGORITHM>/        timing campaign, one directory per arm
  campaign-mem/<SC>/<DATABASE>/<ALGORITHM>/    memory campaign (live set), same layout
  campaign-replicates/s<seed>r<repeat>/...     replicates of the timing campaign
  campaign-mem-replicates/s<seed>r<repeat>/... replicates of the memory campaign
  verify-joint/                                correctness checks of the rescan rule
  invariant-*/                                 machine-independent checks (counts only)
  jdk-comparison/                              runtime and memory of two JDK 25 builds compared
  thresholds/                                  threshold calibration on D_init
  machine/                                     description of the measurement machine
  v1-submitted/                                results of the first submitted version, unchanged
```

Each arm directory holds:

| File | Content |
|---|---|
| `<scenario>.csv` | one row per measured call; every column is defined in `docs/result-columns.md` |
| `run.log`, `run.err` | console output; `run.log` begins with the platform line (OS, JVM build) |
| `DONE` | end time, machine, operating system, duration, row count and the commit of `src/` that ran |
| `DEFERRED` | present instead of `DONE` when the arm did not finish within its cap, with the reason |

Columns that are easy to misread:

- `runtime_ms` uses negative values for status: `-1` failed, `-2` exceeded the time limit,
  `-3` a memory-campaign row, whose timing is not valid.
- `mem_live_mb` (memory campaign) is the live set after a forced collection and is the
  memory figure to report; `peak_heap_mb` includes uncollected garbage.
- `candidate_count` counts extensions before pruning; `explored_nodes` counts the nodes that
  receive an occurrence list.

Result files name the commit of `src/` they ran on (`DONE`, `run_meta.json`). Commits recorded in
them before the history was rewritten to its present form are mapped to the current commits in
`COMMIT_MAP.tsv` (old commit, new commit; same source tree).

Figures and tables are regenerated from the result files:

```bash
python3 analysis/make_figures.py          # writes figures/campaign/
python3 analysis/aggregate.py results/campaign-replicates --csv aggregate.csv   # mean and sample std over replicates
```

## Parameters

Every constant that changes a result, with the value the scenarios use.

| Parameter | Where | Default | Meaning |
|---|---|---|---|
| UUT | `Datasets.java` | per database, table above | upper utility threshold, as a fraction of total utility |
| `r_l` | `Datasets.java` | 0.80 | floor ratio, `LUT_min = r_l * UUT`; SC4 overrides it |
| `D_init` | `Datasets.java` | per database, table above | sequences mined before the update stream |
| batch count | `Datasets.BATCH_COUNT`, `-Dbatch.count` | 100 | equal insertion batches in SC2 |
| batch-count sweep | `Datasets.BATCH_COUNT_SWEEP` | 100, 67, 50, 25 | SC5 |
| SC6 composition | `AutomatedExperimentRunner` | 14 / 3 / 3 | insertions / deletions / modifications, each 1/100 of the stream |
| rescan rule | `-Dadapt.trigger` | `joint` | `iaumax`, `cub` and `swumax` are kept for comparison; `iaumax` and `swumax` can miss patterns on a direct update |
| item removal | `-Dadapt.iip` | `once` | `off`, `once` or `fixpoint`; removes, for a subtree, items that cannot complete a pattern above the cutoff (the irrelevant item pruning of HUSP-ULL) |
| block size `B_sz` | `-Dblock.size` | 100 | sequences per block of the block index |
| seeds | `AutomatedExperimentRunner.SEEDS` | 42, 1337, 2024, 31415, 271828 | batch orders in SC2, SC5 and SC6; `-Dseeds=n` uses the first n |
| repeats | `-Drepeats` | 3 (`run.ps1`), 1 per arm (`campaign.ps1`) | identical reruns; campaign replicates use `-Repeat` |
| time limit per call | `-Dtimeout.min` | 120 min | a mining call past this is recorded as `-2` |
| time limit per arm | `campaign.ps1 -CapSeconds` | 28,800 s | an arm past this is recorded as `DEFERRED` |
| heap, stack | `-Xmx`, `-Xss` | 48g, 64m | from `environment.lock.json` |
| memory mode | `-Dmem.mode` | `used` | `live` measures the live set |
| memory sampling | `-Dmem.interval.ms` | 20 ms | shortest interval between forced collections in live mode |
| `kappa`, `delta0` | `-Dkappa`, `-Ddelta0` | 0.5, 1e-4 | read only by the comparison rules; no effect under `joint` |

## License

MIT, see `LICENSE`. The databases are distributed under the terms of their own release.
Citation information is in `CITATION.cff`.
