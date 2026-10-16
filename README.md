# Pre-HUSPM-Joint

**Problem.** Keep the exact set of high-utility sequential patterns (HUSPs) of a database
while sequences are inserted, deleted and modified, without mining the whole database
again after every change.

**Method.** After a mine, Pre-HUSPM-Joint tracks every pattern whose utility is at least
`LUT_min * TU` (`LUT_min = r_l * UUT`), with its exact utility. Each operation updates
three counters: `U+` (utility inserted), `U-` (utility deleted) and `A_i` (utility inserted
in sequences that contain item `i`). The database is mined again only when

    max_i A_i - UUT * U+ + UUT * U-  >  (UUT - lambda) * TU_ref

Otherwise every tracked pattern is updated directly. One budget covers insertions and
deletions together. In the incremental scenarios a rescan mines at `lambda = UUT` when
each of the last `w = 5` batches exceeded the low budget on its own, and at
`lambda = LUT_min` otherwise. The reported set is exact for either choice.

The repository holds the method, five baselines, the experiment harness, the correctness
checks and every measured result.

## Quick start

Requirements: JDK 17 or later, Python 3.9 or later (`pip install -r analysis/requirements.txt`
for figures and tables).

```bash
./fetch-datasets.sh                                      # databases, checked by SHA-256 (Windows: .\fetch-datasets.ps1)
javac -encoding UTF-8 -d build $(find src -name '*.java')
HEAP=2g ./run.sh TEST SC1                                # smoke test on examples/, about 20 s (Windows: .\run.ps1 TEST SC1)
```

`fetch-datasets` downloads release `v1.2.1-measured-quantity` of
[huspm-datasets](https://github.com/tran-minh-thai/huspm-datasets). TAFENG is not
redistributed; build it from the Ta-Feng file
([Kaggle](https://www.kaggle.com/datasets/chiranjivdas09/ta-feng-grocery-dataset)):

```bash
python tafeng/build_tafeng.py --zip archive.zip --out-dir datasets --name TAFENG
(cd datasets && shasum -a 256 -c ../tafeng/TAFENG.sha256)
```

## Reproduce the experiments

`./run.sh SC2` (or `.\run.ps1 SC2`) runs one scenario into `results/run-<time>-<commit>/` with a
manifest. The published results come from `campaign.ps1`. It runs one JVM per arm
(algorithm, database, scenario) and commits each finished arm. Before measuring, it refuses
any JVM other than the one pinned in `environment.lock.json`.

```powershell
.\campaign.ps1 -Scenario SC2                   # timing
.\campaign.ps1 -Scenario SC2 -MemMode live     # live-set memory
.\campaign.ps1 -Scenario SC2 -Repeat 1         # a replicate
.\campaign.ps1 -List                           # arms not yet measured
```

| Scenario | What runs | Databases |
|---|---|---|
| SC1 | static mining of `D_init` at four thresholds, six algorithms | all eight |
| SC2 | `D_init`, then 100 equal insertion batches | all but BMS1 |
| SC3 | static mining of the whole database at four thresholds | SIGN, LEVIATHAN, C8T1S5I8N5K |
| SC4 | `r_l` in {0.3, 0.5, 0.7, 0.9} | same three |
| SC5 | 25 to 100 batches cut in four shapes | same three |
| SC6, SC6D, SC6B | 20 mixed insertions, deletions and modifications; SC6D mostly deletions; SC6B with one deletion that exceeds the budget alone; five seeds | same three and TAFENG |
| SC7 | ablations of the miner and of the maintenance | all but BMS1 |
| FIDELITY | the Pre-HUSPM implementation under its paper's setting, on SPMF's files (`./fetch-spmf-husp.sh` or `.\fetch-spmf-husp.ps1` first) | SIGN, FIFA, BIBLE, Kosarak10k, BMS |

**Arm names** (`algorithm` column):
- `Adaptive-Full` is the method at the fixed floor `r_l`.
- `Adaptive-FloorSelect` is the method with the rescan floor selection.
- In SC2, SC5 and SC6* the analysis reads `Adaptive-FloorSelect` as the method and
  `Adaptive-Full` as `Adaptive-FixedFloor`.
- The other arms are the baselines and controls:
  - `Pre-HUSPM-SWUmax`, `Pre-HUSPM-TSU` (`Pre-HUSPM` in the static scenarios)
  - `IncUSP-Miner+`, `USpan`, `HUSP-ULL`, `HUSP-SP`
  - `Remine-from-scratch`, `Remine-HUSP-ULL`, `Remine-HUSP-SP`
  - `Joint-miner+SWUmax`, `Joint-miner+TSU`, `Joint-rule+PreHUSPM-tracking`, `Joint-miner+SWUmax+PreHUSPM-tracking`, `Joint-rule+min-floor`, `Joint-rule+plain-floor`

Each arm is described in `docs/experiments.md`.

**Correctness checks** (counts only, any machine; outputs in `results/verify-joint/`):

```bash
java -cp build verify.RescanRuleAcceptanceTest                       # rescan rule, hand-built cases
java -cp build verify.MixedBudgetFuzz joint 5000 11                  # random insert/delete/modify streams
java -Xmx24g -Xss64m -cp build verify.DirectPathVerifier ALL --out results/verify-joint/mine   # direct updates on the real databases
java -Xmx48g -Xss64m -cp build verify.BaselineAgreement ALL          # every miner returns the same patterns
java -Xmx4g -cp build verify.ItemRemovalCheck random 2000 1          # the miner against brute force
```

`-Dverify.husps=true` makes the runner compare every arm's patterns with the method's after
every operation.

## Results

| Path | Content |
|---|---|
| `results/campaign/<SC>/<DB>/<ARM>/` | timing: `<SC>_*.csv` (one row per measured call), `run.log` (platform line), `DONE` (machine, commit, exit code, rows) |
| `results/campaign-mem*/` | live-set memory, same layout |
| `results/campaign*-replicates/s<seed>r<repeat>/` | replicates |
| `results/<SC>/README.md` in each tree | the scenario's tables |
| `results/verify-joint/`, `results/invariant-*/` | correctness checks and counted invariants, each with `run_meta.json` |
| `results/calibration/`, `results/machine/` | settings fixed before the campaign; the measurement machine |
| `results/v1-submitted/` | results of the first submitted version, kept unchanged |
| `figures/campaign/` | figures, tables and `paper_numbers.csv`, generated by `python3 analysis/make_figures.py` |

In `runtime_ms`, `-1` marks a failed call, `-2` a call over the time limit and `-3` a
memory row. Every column is defined in `docs/result-columns.md`. `COMMIT_MAP.tsv` maps
older commit identifiers recorded in some result files to this repository's history.
`analysis/check_generated.py DIR` regenerates every table and figure and compares them
with `DIR`.

## Parameters

| Parameter | Value | Set by |
|---|---|---|
| `UUT` (threshold ratio) | per database, below | `src/common/Datasets.java` |
| `r_l` (`LUT_min = r_l * UUT`) | 0.80 on every database | `Datasets.java` |
| rescan rule | `joint` | `-Dadapt.trigger` |
| rescan floor window `w` | 5 for `Adaptive-FloorSelect` (`Adaptive-FloorSelect-w<n>`: n = 1, 2, 3, 10); 0 (fixed floor) otherwise | `-Dadapt.armFloorWindow`, `-Dadapt.floorWindow` |
| irrelevant item pruning | `once` per node | `-Dadapt.iip` |
| batches `K` | 100 (SC5: 100, 67, 50, 25) | `-Dbatch.count` |
| block size | 100 sequences, never across two batches | `-Dblock.size` |
| one-at-a-time child lists | above 100,000 sequences per node | `-Dadapt.singlePassCap` |
| seeds (SC6*) | 42, 1337, 2024, 31415, 271828 | `AutomatedExperimentRunner.SEEDS` |
| time limit | 120 min per call, 8 h per arm | `-Dtimeout.min`, `campaign.ps1 -CapSeconds` |
| memory guard | stop an arm when less than 10% of the heap is free before a batch | `AutomatedExperimentRunner` |
| live-set sampling | at least 20 ms apart, longer as the live set grows; short mines repeated until 5 samples or 120 s | `-Dmem.interval.ms`, `-Dmem.minSamples`, `-Dmem.repeatMaxMs` |
| JVM | Eclipse Temurin 25.0.4.1+1, `-Xmx48g -Xss64m` | `environment.lock.json`, `campaign.ps1` |
| machine | AMD Ryzen 9 9950X, 64 GB, Windows 11 | `results/machine/PC-THAI.json` |

| Database | Sequences | `D_init` | Default UUT | Utilities |
|---|---:|---:|---:|---|
| SIGN | 730 | 230 | 0.065911 | generated |
| LEVIATHAN | 5,834 | 2,834 | 0.015530 | generated |
| FIFA | 20,450 | 10,450 | 0.059637 | generated |
| BIBLE | 36,369 | 21,369 | 0.022895 | generated |
| BMS1 | 59,601 | 39,601 | 0.013597 | generated |
| KOSARAK | 990,002 | 90,002 | 0.025000 | generated |
| C8T1S5I8N5K | 47,132 | 27,133 | 0.001132 | generated |
| TAFENG | 60,665 | 40,665 | 0.003557 | measured |

The first `D_init` sequences are mined first; the rest form the update stream. The four
threshold levels of SC1 and SC3, and the remaining settings, are listed in
`docs/experiments.md` and `docs/campaign-parameters.md`.

## Layout

```
src/algorithms/   the method (PreHUSPMAdaptiveAlgorithm) and the baselines
src/common/       sequences, loader, database table, mixed-operation plans
src/verify/       correctness checks
src/experiments/  calibration and threshold tools
src/AutomatedExperimentRunner.java   scenario driver
analysis/         figures, tables, run records
docs/             protocol, parameters, result columns, figures
tafeng/           builds TAFENG from the public Ta-Feng file
examples/         a four-sequence database for the smoke test
```

## License

MIT for the code in this repository (`LICENSE`). The databases keep the terms of their own
release. Citation: `CITATION.cff`.
