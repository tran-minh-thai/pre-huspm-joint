# Pre-HUSPM-Joint

Exact incremental high-utility sequential pattern mining under insertions, deletions and
modifications. Pre-HUSPM-Joint keeps the high-utility and pre-large patterns of a sequence
database with their utilities, updates them directly after each operation, and mines the
database again only when a utility budget shared by insertions and deletions is used up.

This repository holds the algorithm, five baselines, the experiment harness, the correctness
checks and every measured result. In the code and the result files the method is the class
`PreHUSPMAdaptiveAlgorithm` and the arm `Adaptive-Full`.

## Layout

```
src/algorithms/     Pre-HUSPM-Joint and the baselines (USpan, HUSP-ULL, HUSP-SP, IncUSP-Miner+, Pre-HUSPM)
src/common/         sequences, loader, patterns, dataset table (Datasets.java)
src/verify/         correctness checks
src/experiments/    calibration and threshold tools
src/AutomatedExperimentRunner.java   scenario driver, one CSV row per measured call
analysis/           figures, tables, scenario READMEs, run manifests
docs/               experiment protocol and the meaning of every result column
results/            measured results (see below)
figures/            figures and tables generated from results/
tafeng/             builds the TAFENG database from the public Ta-Feng file
examples/           a small example database
```

## Requirements

- JDK 17 or later to build. Results were measured on Eclipse Temurin 25.0.4.1+1, pinned in
  `environment.lock.json`; `campaign.ps1` refuses any other build.
- Python 3.9 or later for figures and tables: `pip install -r analysis/requirements.txt`.
- Memory: measurements use `-Xmx48g` on a 64 GB machine.

## Data

The databases come from https://github.com/tran-minh-thai/huspm-datasets, release
`v1.2.1-measured-quantity`, checked against its SHA-256 manifest:

```powershell
.\fetch-datasets.ps1        # Windows
```
```bash
./fetch-datasets.sh         # Linux, macOS
```

TAFENG is built from the Ta-Feng grocery file (`ta_feng_all_months_merged.csv`,
[Kaggle](https://www.kaggle.com/datasets/chiranjivdas09/ta-feng-grocery-dataset)); the output is
pinned by `tafeng/TAFENG.sha256`:

```bash
python tafeng/build_tafeng.py --zip archive.zip --out-dir datasets --name TAFENG
(cd datasets && shasum -a 256 -c ../tafeng/TAFENG.sha256)
```

| Database | Sequences | Items | Avg. length | Items/event | Utilities | `D_init` | Default UUT |
|---|---:|---:|---:|---:|---|---:|---:|
| SIGN | 730 | 267 | 52.00 | 1.00 | generated | 230 | 0.065911 |
| LEVIATHAN | 5,834 | 9,025 | 33.81 | 1.00 | generated | 2,834 | 0.015530 |
| FIFA | 20,450 | 2,990 | 36.24 | 1.00 | generated | 10,450 | 0.059637 |
| BIBLE | 36,369 | 13,905 | 21.64 | 1.00 | generated | 21,369 | 0.022895 |
| BMS1 | 59,601 | 497 | 2.51 | 1.00 | generated | 39,601 | 0.013597 |
| KOSARAK | 990,002 | 41,270 | 8.10 | 1.00 | generated | 90,002 | 0.025000 |
| C8T1S5I8N5K | 47,132 | 68,240 | 18.83 | 7.97 | generated | 27,133 | 0.001132 |
| TAFENG | 60,665 | 23,812 | 13.40 | 1.00 | measured | 40,665 | 0.003557 |

`D_init` sequences are mined first and the rest form the update stream. `src/common/Datasets.java`
holds these values and the threshold sweeps. Each database is two files: `NAME_seq.txt`, one
sequence per line as `item[quantity]` with `-1` closing an event and `-2` the sequence, and
`NAME_eui.txt`, one `item:profit` per line.

## Build and run

```powershell
.\run.ps1 -BuildOnly        # Windows
.\run.ps1 TEST SC1          # smoke test on examples/, under a minute
```
```bash
javac -encoding UTF-8 -d build $(find src -name '*.java')
HEAP=8g ./run.sh TEST SC1
```

Correctness checks:

```bash
java -cp build verify.RescanRuleAcceptanceTest                         # rescan rule on hand-built cases
java -cp build verify.MixedBudgetFuzz joint 5000 11                    # random insert/delete/modify streams
java -Xmx48g -Xss64m -cp build verify.BaselineAgreement ALL            # every miner returns the same patterns
java -Xmx4g -cp build verify.ItemRemovalCheck random 2000 1            # the proposal's miner against brute force
java -Xmx24g -Xss64m -cp build verify.IncUspVerifier random 300 7      # IncUSP-Miner+ against brute force
```

The measurement campaign runs one JVM per arm and commits each finished arm:

```powershell
.\campaign.ps1 -Scenario SC2                  # one scenario
.\campaign.ps1 -Scenario SC1 -MemMode live    # live-set measurement
.\campaign.ps1 -Scenario SC1 -Repeat 1        # a replicate
.\campaign.ps1 -List                          # arms not yet measured
```

| Scenario | Measures | Databases |
|---|---|---|
| SC1 | static mining of `D_init` at four thresholds | all eight |
| SC2 | `D_init`, then 100 equal insertion batches | all but BMS1 |
| SC3 | static mining of the whole database at four thresholds | SIGN, LEVIATHAN, C8T1S5I8N5K |
| SC4 | floor ratio `r_l` in {0.3, 0.5, 0.7, 0.9} | SIGN, LEVIATHAN, C8T1S5I8N5K |
| SC5 | 25 to 100 batches under four batch-size shapes | SIGN, LEVIATHAN, C8T1S5I8N5K |
| SC6 | 14 insertions, 3 deletions and 3 modifications in seeded order | SIGN, LEVIATHAN, C8T1S5I8N5K |
| SC7 | ablations of the miner and of the maintenance | all but BMS1 |

Arm names (`algorithm` column): `Adaptive-Full` (Pre-HUSPM-Joint), `Remine-from-scratch`
(the same miner on the whole database after every operation), `Pre-HUSPM-SWUmax` and
`Pre-HUSPM-TSU` (the two rescan tests of Yan et al., 2025; one arm, `Pre-HUSPM`, in static
scenarios), `IncUSP-Miner+`, `USpan`, `HUSP-ULL`, `HUSP-SP`.

## Results

```
results/campaign/<SC>/<DATABASE>/<ALGORITHM>/    timing, one directory per arm
results/campaign-mem/...                         live set, same layout
results/campaign-replicates/s<seed>r<repeat>/... replicates
results/campaign-mem-replicates/...              replicates of the live-set measurement
results/calibration/                             settings fixed by measurement before the campaign
results/verify-joint/, results/invariant-*/      correctness checks, counts only
results/machine/                                 the measurement machine
results/v1-submitted/                            results of the first submitted version
```

Each arm directory holds the CSV (one row per measured call, columns defined in
`docs/result-columns.md`), `run.log` with the platform line, and `DONE` with the machine, the
commit of `src/`, the exit code and the row count. Each scenario directory has a `README.md`
with its tables. `runtime_ms` uses `-1` for a failed call, `-2` for one over the time limit
and `-3` for a live-set row, whose time is not valid; `mem_live_mb` is the memory figure to
report. `COMMIT_MAP.tsv` maps commit identifiers that some result files record to the commits
of this repository.

Figures and tables:

```bash
python3 analysis/make_figures.py        # writes figures/campaign/
python3 analysis/scenario_readmes.py    # writes the scenario READMEs
```

## Main parameters

| Parameter | Where | Value |
|---|---|---|
| UUT | `Datasets.java` | per database, table above |
| floor ratio `r_l` | `Datasets.java` | 0.80 (`LUT_min = r_l * UUT`) |
| batches | `-Dbatch.count` | 100 |
| rescan rule | `-Dadapt.trigger` | `joint` (`iaumax`, `cub`, `swumax` kept for comparison) |
| irrelevant item pruning | `-Dadapt.iip` | `once` (`off`, `fixpoint`) |
| block size | `-Dblock.size` | 100 sequences |
| time limit per call / per arm | `-Dtimeout.min` / `campaign.ps1 -CapSeconds` | 120 min / 8 h |
| heap, stack | `-Xmx`, `-Xss` | 48g, 64m |

The full list is in `docs/experiments.md` and `docs/campaign-parameters.md`.

## License

MIT, see `LICENSE`. The databases are distributed under the terms of their own release;
citation information is in `CITATION.cff`.
