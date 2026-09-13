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
  v1-submitted/                    results of the submitted version; never overwritten
  run-<timestamp>-<commit>/        one directory per run, each with its own manifest
figures/                           generated, one directory per run
fetch-datasets.ps1 / .sh           fetch and verify the databases
run.ps1 / run.sh                   build, run scenarios, write the manifest, plot
run.txt                            operating procedure for the measurement machine
```

## Requirements

| | |
|---|---|
| JDK | 17 or later. Pin one version for a measurement campaign; changing it changes the conditions. |
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

| Database | Sequences | Items | Avg. length | Items/event | Total utility | Default UUT |
|---|---:|---:|---:|---:|---:|---:|
| SIGN | 730 | 267 | 52.00 | 1.00 | 1,796,483 | 0.025 |
| LEVIATHAN | 5,834 | 9,025 | 33.81 | 1.00 | 10,502,159 | 0.060 |
| FIFA | 20,450 | 2,990 | 36.24 | 1.00 | 42,781,841 | 0.050 |
| BIBLE | 36,369 | 13,905 | 21.64 | 1.00 | 35,550,162 | 0.025 |
| BMS1 | 59,601 | 497 | 2.51 | 1.00 | 7,229,030 | 0.014 |
| KOSARAK | 990,002 | 41,270 | 8.10 | 1.00 | 385,921,994 | 0.030 |
| C8T1S5I8N5K | 47,132 | 68,240 | 18.83 | 7.97 | 47,416,411 | not in the default set |
| ONLINE_RETAIL_II_ALL | 4,383 | 41,431 | 122.06 | 22.78 | 29,545,643 | not in the default set |
| ONLINE_RETAIL_II_QTY | 5,767 | 4,631 | 139.66 | 1.00 | 19,677,989 | not in the default set |

The scenarios run on the first six. Note the items-per-event column: in those six every
event holds a single item, so they exercise across-event extension only. The three
remaining databases are the ones that reach within-event extension, and
`ONLINE_RETAIL_II_QTY` is the only one whose internal utility is measured rather than
generated. Select any of them with `-Ddataset.only=NAME`.

Thresholds are ratios of total utility, so they carry across releases unchanged.

## Build and run

`run.ps1` and `run.sh` compile, write a manifest, run the scenarios, and plot. There is no
separate build step.

```powershell
.\run.ps1                                                # every scenario
.\run.ps1 SC1 SC2                                        # selected scenarios
.\run.ps1 TEST SC1                                       # smoke test on examples/, minutes
.\run.ps1 -Heap 24g -TimeoutMin 120 -ExpectMachine 'PC'  # a measurement run
.\run.ps1 -MemMode live                                  # the memory campaign
```

To compile and run a single class directly:

```bash
javac -encoding UTF-8 -d build $(find src -name '*.java')
java -Xmx24g -Xss64m -cp build AutomatedExperimentRunner SC1
```

Verify correctness before measuring anything:

```bash
java -cp build verify.WP1AcceptanceTest                   # must report 15 passed, 0 failed
java -cp build verify.BaselineAgreement SIGN              # static baselines must agree exactly
java -Xmx24g -cp build verify.CorrectnessVerifier LEVIATHAN --rule iaumax
```

### Scenarios

| Scenario | What it does | Output |
|---|---|---|
| SC1 | static mining on `D_init`, the first 50 percent, over four thresholds | `SC1_static.csv` |
| SC2 | `D_init` plus five equal batches of 10 percent | `SC2_incremental.csv` |
| SC3 | threshold sweep on the full database | `SC3_su.csv` |
| SC4 | lower-threshold ratio sweep, `r_l` in {0.3, 0.5, 0.7, 0.9} | `SC4_sl_sensitivity.csv` |
| SC5 | batch count `K` in {3, 5, 10} crossed with four partitioning strategies | `SC5_batch_strategy.csv` |
| SC6 | ten mixed operations at 70:15:15, incremental against remine-from-scratch | `SC6_idm_mixed.csv` |
| SC7 | ablation of C3, C4 and C5 against Pre-HUSPM | `SC7_ablation.csv` |

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
| — | `-Dmem.interval.ms` | 1000 | forced-collection interval in live mode |
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

`results/v1-submitted/` holds the results of the submitted version and is never overwritten.

## License

MIT, see `LICENSE`. The databases are distributed under the terms of the release they come
from. Cite the accompanying paper if you use this; see `CITATION.cff`.
