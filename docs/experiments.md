# Experimental protocol

This document defines how the experiments are configured, measured, and recorded.
It is the reference for anyone reproducing the numbers from a clean clone.

## 1. Algorithms

| Name in results | Role | Reference |
|---|---|---|
| `Adaptive` | proposed framework | this repository |
| `Pre-HUSPM` | pre-large incremental baseline | Yan et al. |
| `IncUSP-Miner+` | candidate-tree incremental baseline | Wang et al. |
| `HUSP-ULL` | static baseline, LAR and IIP pruning | Gan et al. |
| `USpan` | static baseline, SWU bound | Yin et al. |
| `HUSP-SP` | static baseline, seqPro and TRSU bound | Zhang et al., TKDD 2023 |

All implementations live in `src/algorithms/` and run inside the same harness, so
runtime, peak heap, and the two counters are measured under identical conditions.
Numbers copied from other papers are never mixed into the tables.

## 2. Datasets

Six SPMF benchmark datasets, redistributed in quantitative form in `datasets/`.
Sequence structure comes from SPMF; internal and external utilities are generated
synthetically:

* internal utility (quantity): 70 percent from [1,2], 20 percent from [3,5], 10 percent from [6,10]
* external utility (unit profit): log-normal exp(N(2.5,1)), clamped to [1,1000]
* generator seeded per input file

The generator is a separate repository, `convert-spmf-to-quantitative`. It reproduces the
files in `datasets/` byte for byte from the raw SPMF inputs, so the utility draw can be
regenerated under a different seed without touching this repository.

The datasets are **not** tracked in this repository. They are published as release assets
of the shared repository `huspm-datasets` and fetched at run time, pinned to one exact
release tag, then verified against the `MANIFEST.sha256` of that same release. The release
tag and the per-file checksums are recorded in the manifest of every run, which is what
ties a set of numbers to a specific set of bytes. The only data files kept here are
`paper_seq.txt` and `paper_eui.txt`, the small worked example, which are specific to this
paper and are not part of the shared collection.

| Dataset | Sequences | Items | Avg length | Total utility | Default threshold |
|---|---:|---:|---:|---:|---:|
| SIGN | 730 | 267 | 52.00 | 1,668,652 | 0.025 |
| LEVIATHAN | 5,834 | 9,025 | 33.80 | 14,156,241 | 0.060 |
| FIFA | 20,450 | 2,990 | 34.74 | 35,721,991 | 0.050 |
| BIBLE | 36,369 | 13,905 | 21.60 | 35,550,162 | 0.025 |
| BMS1 | 59,601 | 497 | 2.42 | 7,229,030 | 0.014 |
| KOSARAK | 990,000 | 41,270 | 8.10 | 400,890,653 | 0.030 |

`paper_seq.txt` and `paper_eui.txt` hold the small worked example used in the manuscript.

SIGN, LEVIATHAN, and KOSARAK form the three-dataset subset used by the scenarios that
cross several parameters at once. The subset mixes dense long sequences with sparse short
ones and keeps the incremental grids inside the time budget.

## 3. Parameters

| Symbol in the paper | Name in code | Default | Meaning |
|---|---|---|---|
| UUT | `su` | per dataset, table above | upper utility threshold ratio |
| r_l | `DEFAULT_SL_MIN_RATIO` | 0.8 | lower-threshold ratio; LUT_min = r_l * UUT |
| kappa | `DEFAULT_ALPHA` | 0.5 | coefficient of the adaptive term |
| Delta_0 | `DEFAULT_DELTA0` | 1e-4 | initial margin |
| B_sz | `BlockIndex.blockSize` | 100 | block size of the block-level inverted index |

## 4. Scenarios

| Scenario | Setup | Output file |
|---|---|---|
| SC1 | static mining on D_init, the first 50 percent of sequences, four threshold levels | `SC1_static.csv` |
| SC2 | D_init plus five uniform batches of 10 percent | `SC2_incremental.csv` |
| SC3 | threshold sweep on the full database | `SC3_su.csv` |
| SC4 | lower-threshold ratio sweep, r_l in {0.3, 0.5, 0.7, 0.9} | `SC4_sl_sensitivity.csv` |
| SC5 | batch count K in {3, 5, 10} crossed with four partitioning strategies | `SC5_batch_strategy.csv` |
| SC6 | twenty mixed operations, exactly 14 insertions, 3 deletions and 3 modifications in a seeded order, each one hundredth of the incremental stream | `SC6_idm_mixed.csv` |
| SC7 | ablation of C3, C4, and C5 against Pre-HUSPM | `SC7_ablation.csv` |

SC1, SC2, and SC7 run on all six datasets; SC3 to SC6 run on the three-dataset subset.

## 5. Measurement

Each mining call runs in its own scope with a full GC before measurement, so one
algorithm cannot contaminate the next. Runtime is wall-clock time of the mining task
only, excluding dataset loading. Peak heap is sampled every 50 ms and reported net of the
baseline heap measured after the preceding GC. Every call has a wall-clock limit,
60 minutes by default, configurable with `-Dtimeout.min`.

Two counters are defined differently on purpose:

* `candidate_count` counts every distinct extension generated, before any pruning, so it
  is identical across algorithms inside the same threshold family
* `explored_nodes` counts the patterns actually materialized and recursed into, after the
  extension filter, so it is the number that measures pruning effectiveness

Status is encoded in `runtime_ms`: a value of 0 or more is a completed run in
milliseconds, -2 is overtime, and -1 is a failure such as out of memory, candidate budget
exceeded, or an exception. The `op_type` field carries the scenario tag and may end in
`_OT`, `_OOM`, `_BUDGET`, `_FAILED`, or `_SKIPPED_POISONED`. Column by column definitions
are in `docs/result-columns.md`.

## 6. Repetition and variance

Three independent sources of variation are separated:

1. **Measurement noise** from JIT compilation and garbage collection. Handled by repeating
   an identical configuration three times. This is what the reported standard deviation of
   runtime and peak heap refers to.
2. **Batch and operation randomness** in SC2, SC5, and SC6, driven by the `SEEDS` array
   in the runner. Five seeds are used.
3. **The synthetic utility draw**, fixed inside the dataset files. Varying it requires
   regenerating `datasets/` with the converter under a different seed. Three variants are
   used for a robustness table on SC1 and SC2 over the three-dataset subset.

Running the full grid under all three at once is not affordable, so the budget is spent as
follows. SC1 and SC3 have no batch randomness and use three repetitions at a single
dataset variant. SC2, SC5, and SC6 use five seeds, with three repetitions added at the
default configuration. The utility-draw robustness check is reported as a separate small
table rather than folded into the main results.

Results are reported as mean plus or minus standard deviation. Overtime and out-of-memory
calls are excluded from the means and reported separately as resource-limit evidence.

## 7. Measurement machine

All numbers in a given table come from one machine, and every algorithm in that table is
run on it under identical JVM settings. Numbers measured on different machines are never
compared against each other.

The machine used for the revised experiments is a Windows laptop with an Intel Core
i7-10750H, six cores and twelve threads at 2.60 GHz, and 64 GB of RAM. The mining task
runs on a single worker thread, so single-core sustained clock is what matters; the
discrete GPU is irrelevant to this workload.

Two properties of this machine affect the protocol:

* **Sustained load and thermal behaviour.** An H-series laptop processor reduces its clock
  under a multi-hour load, which inflates the variance of runtime measurements. Runs are
  done with the machine on mains power, the Windows power plan set to high performance,
  sleep and hibernation disabled, and the repository folder excluded from real-time
  antivirus scanning. The active power plan is recorded in each manifest.
* **Memory.** 64 GB of RAM would allow a much larger heap, but the heap stays at 24 GB.
  Out-of-memory outcomes are evidence in the comparison, so raising the heap would change
  what the failures mean rather than remove a limitation.

The wall-clock limit per call stays at 60 minutes. This machine is slower per core than
the one used for the first submission, so more calls are expected to end in overtime;
overtime is reported rather than hidden, and the affected calls are excluded from the
means.

## 8. Provenance

`run.sh` writes each run into `results/run-<timestamp>-<commit>/` together with a
`manifest.json` recording the commit, whether the working tree was clean, the JDK, the JVM
flags, the machine, the timeout, and the SHA-256 of every dataset file. A set of numbers
that is not accompanied by a manifest is not used in the paper.

`results/v1-submitted/` holds the frozen numbers of the first submission and is never
overwritten.
