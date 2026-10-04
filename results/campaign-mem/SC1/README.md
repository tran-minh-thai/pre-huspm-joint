# SC1 memory: the live set of a static mine on `D_init`

The same 192 runs as `results/campaign/SC1`, measured for memory instead of time. At every
sample the runner forces a full collection and reads the heap immediately after, so what is
recorded is the live set, not used heap; runtimes are void and written as -3. The wait between
samples is the larger of 20 ms and four times the last collection. `PC-THAI`, JDK 25,
`-Xmx48g`, one JVM per arm. 5.6 hours of machine time, against 2.9 in timing mode.

The answers agree: for all 32 (database, threshold) pairs, every algorithm that completed
reports the same HUSP count.

## What did not complete, and one cell that was lost

| database | algorithm | outcome |
|---|---|---|
| FIFA | IncUSP-Miner+ | out of memory at 48 GB at the two lowest thresholds |
| LEVIATHAN | Pre-HUSPM | out of memory at 0.01553 and 0.01647 |
| LEVIATHAN | Pre-HUSPM | at the heap ceiling for the whole 120-minute limit at 0.014644 |

At 0.014644 Pre-HUSPM sat at the 48 GB ceiling - 48,747 MB live, over 33,840 samples - until the
time limit stopped it. In timing mode the same cell runs out of memory within seconds; here the
forced collections kept it just alive. Both modes say the same thing: it cannot be mined within
48 GB.

The first time this arm ran, the cell left no row at all. `runSingle`, which SC1 and SC3 call,
answered any exception after a run began with null, and those scenarios log only non-null rows;
with the worker still holding the heap after the time limit, the first allocation after the
overtime row threw `OutOfMemoryError` into that catch. `runSingle` now builds a failure row before
it measures and returns it from the catch. The arm was run again and the row is there - written
this time by the ordinary path, because the allocation succeeded, so the new catch has not yet
been exercised by a real failure.

## How many samples a figure rests on

187 of the 192 cells completed. Sixteen of them were read only twice, at the start and after the end,
because the run finished before the first wait was over; for those the peak is what the run
kept. They fall on both sides - nine of the proposal's, six of Pre-HUSPM's, one of
IncUSP-Miner+'s - on TAFENG, BMS1 and KOSARAK at their higher thresholds, where a run takes a
few milliseconds. They are marked below, and the summary is given with and without them.

## Live set at the default threshold

Megabytes. A dagger marks a cell read at its start and end only.

| database | USpan | HUSP-ULL | HUSP-SP | IncUSP-Miner+ | Pre-HUSPM | Adaptive |
|---|---:|---:|---:|---:|---:|---:|
| SIGN | 6 | 7 | 8 | 420 | 43 | 4 |
| LEVIATHAN | 9 | 1462 | 24 | 4464 | OOM | 18 |
| TAFENG | 7 | 7 | 85 | 167 | 11 † | 21 † |
| BIBLE | 23 | 1558 | 79 | 15844 | 1379 | 67 |
| BMS1 | 6 | 2 | 30 | 57 | 24 | 18 † |
| C8T1S5I8N5K | 23 | 15 | 87 | 1118 | 421 | 1154 |
| KOSARAK | 33 | 56 | 134 | 648 | 256 | 88 |
| FIFA | 19 | 41 | 65 | OOM | 553 | 73 |

## Across all cells

Geometric mean of the baseline's live set over the proposal's, on the cells both completed.
Above one, the proposal holds less.

| baseline | all cells | cells where the proposal holds more | well-sampled cells only |
|---|---:|---:|---:|
| IncUSP-Miner+ | 21.7 | 3 of 30 | 38.5 |
| Pre-HUSPM | 2.7 | 10 of 29 | 4.3 |
| HUSP-SP | 1.09 | 10 of 32 | 0.77 |
| HUSP-ULL | 1.07 | 18 of 32 | 1.53 |
| USpan | 0.29 | 28 of 32 | 0.27 |

The claim the numbers support is a scoped one. Against the two incremental baselines, the
methods it would replace, the proposal holds far less: about 22 times less than IncUSP-Miner+
and 2.7 times less than Pre-HUSPM. Against the static miners it holds about as much as HUSP-ULL
and HUSP-SP and about 3.4 times more than USpan. That is the cost of being incremental: a static
miner keeps its answer, and an incremental one also keeps what it needs to avoid mining again.

## An open question: C8T1S5I8N5K

On C8T1S5I8N5K the proposal holds 1154 MB against Pre-HUSPM's 421, although its pre-large set is
27 times smaller - 10,562 patterns against 289,480 at the default threshold, from the timing
run. Its memory there is therefore not its pre-large set. Its used-heap peak in timing mode was
1228 MB, close to this live set, so it is state and not uncollected garbage. C8T1S5I8N5K is the
one database with several items per event, which is where to look first; what the proposal
holds there has not been measured.
