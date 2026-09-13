# Pre-HUSPM-Adaptive

Reference implementation and experimental harness for Pre-HUSPM-Adaptive, a bound-first
framework for incremental high-utility sequential pattern mining over databases that
change through insertions, deletions, and modifications.

The repository holds the framework, the baseline implementations, the benchmark datasets
in quantitative form, the experiment runner, the raw result files, and the scripts that
build the figures and summary tables.

## Layout

```
.
├── run.sh                  entry point on macOS and Linux: build, run, plot
├── run.ps1                 the same on Windows
├── src/                    Java sources
│   ├── AutomatedExperimentRunner.java   defines scenarios SC1 to SC7
│   ├── ExperimentMetrics.java           per-run measurement record
│   ├── ResultCollector.java             CSV writer
│   ├── algorithms/         framework and baselines
│   └── common/             dataset loader, sequence and pattern types
├── datasets/               quantitative benchmark data
├── analysis/               figure and table pipeline
├── docs/
│   ├── experiments.md      protocol: scenarios, parameters, seeds, measurement
│   ├── result-columns.md   codebook for every CSV column
│   └── figures.md          how the figure pipeline works
├── results/
│   └── v1-submitted/       frozen numbers of the first submission
└── figures/
    └── v1-submitted/       frozen figures of the first submission
```

## Requirements

| Component | Used for the reported results | Minimum |
|---|---|---|
| JDK | 26 | 17 |
| Heap | `-Xmx24g -Xss64m` | 8 GB for the three-dataset subset |
| Python | 3.11 | 3.9 |
| Python packages | `pandas`, `numpy`, `matplotlib`, optionally `seaborn` | same |

## Running from a clean clone

On macOS and Linux:

```bash
git clone <repository url>
cd pre-huspm-adaptive

./run.sh                       # every scenario, default settings
./run.sh SC1 SC2               # selected scenarios
./run.sh SC1,SC7               # comma syntax also works
HEAP=8g ./run.sh TEST SC1      # reduced grid, finishes in minutes
```

On Windows, in PowerShell:

```powershell
git clone <repository url>
cd pre-huspm-adaptive

.\run.ps1                      # every scenario, default settings
.\run.ps1 SC1 SC2              # selected scenarios
.\run.ps1 -Heap 8g TEST SC1    # reduced grid, finishes in minutes
```

The two scripts are equivalent and write the same directory layout.

Each run writes to `results/run-<timestamp>-<commit>/` together with a `manifest.json`
recording the commit, the JDK, the JVM flags, the machine, the timeout, and the SHA-256 of
every dataset file, so a set of numbers can always be traced back to the code that
produced it. Figures for that run land in the same directory.

Useful switches:

```bash
TIMEOUT_MIN=120 ./run.sh SC1          # raise the per-call wall-clock limit
HEAP=48g STACK=128m ./run.sh          # larger machine
```

```powershell
.\run.ps1 -TimeoutMin 120 SC1
.\run.ps1 -Heap 48g -Stack 128m
```

## Data format

Each dataset is a pair of files:

* `<NAME>_seq.txt`: quantitative sequences. Each item carries its quantity in brackets,
  `-1` separates elements, `-2` terminates a sequence, as in `1[2] 2[1] -1 3[5] -1 -2`
* `<NAME>_eui.txt`: external utility table, one `itemID:profit` per line

Utilities are synthetic and fixed, so results are reproducible without rerunning any
generator. The generator itself is a separate repository,
`convert-spmf-to-quantitative`, which reproduces `datasets/` byte for byte from the raw
SPMF inputs. Dataset characteristics, default thresholds, and the distributions used are
listed in `docs/experiments.md`.

## Results and figures

Scenario definitions, algorithm parameters, the repetition protocol, and how runtime and
peak heap are measured are all in `docs/experiments.md`. The meaning of every CSV column,
including the status sentinels, is in `docs/result-columns.md`.

`run.sh` calls the figure pipeline at the end. To rebuild figures from an existing result
directory without rerunning the experiments:

```bash
HUSP_DATA_DIR=results/run-20260913T090000Z-a1b2c3d \
HUSP_OUT_DIR=figures/run-20260913T090000Z-a1b2c3d \
python3 analysis/make_figures.py
```

## License

MIT, see `LICENSE`. The datasets originate from the SPMF library and remain subject to
their original terms.
