# Old and new arithmetic: same search, same time (pre-registered)

Written on 2026-11-26, before any run of this measurement. The rule below is fixed now and is not
changed after the runs are read.

## Why

Commit 22d3a8fd9 holds every utility as an integer (external utilities scaled by 10^d) and forms
thresholds and rescan decisions in decimal (`common.Exact`). The result can change only where a
pattern's utility equals a threshold. The timings of the campaign were measured on the code of
release `v2.7-submission` (commit 56921b6e5). This measurement asks whether they still describe the
new code, so that they are kept rather than measured again.

## What is run

- Code: `v2.7-submission` (old) and the commit of this file or a later one with the same `src/`
  (new), each in its own checkout on the measurement machine, under `environment.lock.json` (the JDK
  pinned there, `-Xmx48g`), through the `campaign.ps1` of each checkout.
- Arms: `Pre-HUSPM-Joint` (the proposed method) and `Remine-own-UUT-rebuild` (the rival that runs
  the same miner, read for the comparisons). Both run the threshold code that changed.
- Streams: the eight SC2 streams (BIBLE, C8T1S5I8N5K, DUNNHUMBY, FIFA, KOSARAK, LEVIATHAN, SIGN,
  TAFENG), seed index 0, as in the campaign.
- Trees: `results/campaign-exact-old/s0r<k>` and `results/campaign-exact-new/s0r<k>`, k = 0..3.
- Pairs: for k = 0..3 and for each stream in the order above, both arms are run with the old code
  and with the new code back to back, old first when k is even and new first when k is odd. Each run
  is its own JVM. A pair is (stream, arm, k): 8 x 2 x 4 = 64 pairs.

## The quantities

- For one run: the time summed over the increments (rows with `op_type` `*_INC`), as in
  `docs/aa-tie-band.md`. A run with an increment that did not finish is not used and its pair is
  reported as missing.
- For a pair with totals a (old) and b (new): q = max(a/b, b/a).
- The search: at every increment of a run, `candidate_count`, `explored_nodes`,
  `total_patterns_found` and `rescan_count` of the new code against the old code of the same pair.
  For information, the same counters of the new code against the row of the same increment in
  `results/campaign/SC2/<stream>/<arm>`, the runs the tables read.

## The rule

- r = the A/A bound of `docs/aa-tie-band.md` (the largest q over its 80 pairs, read by
  `analysis/aa_band.py` from `results/campaign-aa`).
- A (stream, arm) keeps its campaign timings when all four of its pairs have both runs, every pair
  has q <= r, and every counter of every increment is equal between old and new.
- A (stream, arm) that fails any of these is measured again with the new code in the campaign
  (repeats 0 to 2), and its tables use the new runs; the comparisons it enters are classified
  again with the band in use.
- Reported with it, for information only: the median of b/a per arm, the largest q per arm, and the
  number of rows compared and found equal.

## Commands

The measurement machine runs the block of the project's run list; the analysis is
`python analysis/exact_timing.py` on the development machine after the branch is fetched.
