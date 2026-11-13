# A/A calibration of the tie band (pre-registered)

Written on 2026-11-13, before any run of this measurement. The rule below is fixed now and is not
changed after the runs are read.

## Why

The tie band in use is the largest time ratio between the proposed method and Remine-UUT-R on the
SC2 streams where both make the same search at every increment. It is therefore taken from runs that
it also classifies. This measurement estimates the run-to-run spread of one arm in runs that no
comparison uses.

## What is run

- Arm: `Pre-HUSPM-Joint`, code of release `v2.6-submission` (commit `4bc552331`), on the
  measurement machine, under `environment.lock.json` (the JDK pinned there, `-Xmx48g`), through
  `campaign.ps1` unchanged.
- Streams: the eight SC2 streams (BIBLE, C8T1S5I8N5K, DUNNHUMBY, FIFA, KOSARAK, LEVIATHAN, SIGN,
  TAFENG), seed index 0, as in the campaign.
- Tree: `results/campaign-aa/s0r<n>`, pushed to the branch `campaign-aa`; no other tree is touched.
- Pairs: for k = 0..9 and for each stream in the order above, run `-Repeat 2k` and then
  `-Repeat 2k+1` back to back. Each run is its own JVM. A pair is the two runs of one stream with
  repeats 2k and 2k+1, so its two members run one after the other, and the ten pairs of a stream are
  spread over the whole session. 80 pairs in all.

## The quantity

For one run: the time summed over the 100 increments of the stream (rows with `iteration > 0`), the
quantity every SC2 comparison uses (`analysis/scenario_tables.py`, `_run_totals`). A run that did not
finish every increment is not used, and its pair is reported as missing.

## The rule

- For a pair with totals a and b: q = max(a/b, b/a).
- r = the largest q over all pairs that have both runs. The band is [1/r, r], the same definition as
  the band in use (the largest ratio between two runs that make the same search).
- Reported with it, for information only: the number of pairs, the largest q of each stream, and the
  95th and 99th percentiles of q (linear interpolation). With 80 pairs the 99th percentile lies
  between the two largest values, so it is not used as the band.

## What follows from it

- Every comparison of the summary table and every `classes_*` key is classified again with the new
  band. The keys of the band in use are kept.
- Reported: the new band; the number of comparisons that change class, in each direction; the
  sentences whose counts change.
- If fewer than 80 pairs have both runs, the band is computed from the pairs there are and the count
  is reported.

## Commands

The measurement machine runs the block of the project's run list; the analysis is
`python analysis/aa_band.py --results results/campaign-aa` on the development machine after the
branch is fetched.
