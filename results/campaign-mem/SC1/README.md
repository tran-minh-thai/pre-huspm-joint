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

187 of the 192 cells completed. Fifteen of them were read only twice, at the start and after the
end, because the run finished before the first wait was over; for those the peak is what the run
kept. They fall on both sides - eight of the proposal's, six of Pre-HUSPM's, one of
IncUSP-Miner+'s - on TAFENG, BMS1 and KOSARAK at their higher thresholds, where a run takes a
few milliseconds. They are marked below, and the summary is given with and without them.

## Live set at the default threshold

Megabytes. A dagger marks a cell read at its start and end only.

| database | USpan | HUSP-ULL | HUSP-SP | IncUSP-Miner+ | Pre-HUSPM | Adaptive |
|---|---:|---:|---:|---:|---:|---:|
| SIGN | 6 | 7 | 8 | 420 | 43 | 4 |
| LEVIATHAN | 9 | 1462 | 24 | 4464 | OOM | 18 |
| TAFENG | 7 | 7 | 85 | 167 | 11 † | 21 † |
| BIBLE | 23 | 1558 | 79 | 15844 | 1379 | 60 |
| BMS1 | 6 | 2 | 30 | 57 | 24 | 15 † |
| C8T1S5I8N5K | 23 | 13 | 87 | 1115 | 222 | 95 |
| KOSARAK | 33 | 56 | 134 | 648 | 256 | 84 |
| FIFA | 19 | 41 | 65 | OOM | 553 | 71 |

## Across all cells

Geometric mean of the baseline's live set over the proposal's, on the cells both completed.
Above one, the proposal holds less.

| baseline | all cells | cells where the proposal holds more | well-sampled cells only |
|---|---:|---:|---:|
| IncUSP-Miner+ | 32.6 | 0 of 30 | 55.2 |
| Pre-HUSPM | 3.7 | 6 of 29 | 6.2 |
| HUSP-SP | 1.59 | 8 of 32 | 1.23 |
| HUSP-ULL | 1.55 | 18 of 32 | 2.14 |
| USpan | 0.43 | 26 of 32 | 0.40 |

Against the two incremental baselines, the methods it would replace, the proposal holds far
less: about 33 times less than IncUSP-Miner+, in every cell both complete, and 3.7 times less
than Pre-HUSPM. The six cells where Pre-HUSPM holds less are all on TAFENG and BMS1, where both
live sets are a few tens of megabytes and most cells were read twice only. Against the static
miners the proposal holds less than HUSP-SP and HUSP-ULL by geometric mean, though HUSP-ULL
holds less in 18 of the 32 cells, and about 2.3 times more than USpan. That is the cost of
being incremental: a static miner keeps its answer, and an incremental one also keeps what it
needs to avoid mining again.

## C8T1S5I8N5K: item ids, not state

An earlier measurement put the proposal at 1154 MB on C8T1S5I8N5K against Pre-HUSPM's 421,
although its pre-large set is 27 times smaller. A heap probe showed that almost none of it was
the algorithm's state. The database draws its 68,240 items from ids up to 4,999,999, and every
miner here sizes its per-item arrays by the largest id: the proposal's 18 such arrays took about
700 MB, and a per-block item set that nothing read took most of the rest. That set has been
removed, and the database's ids are compacted at load, in order (`common.Datasets.compactItemIds`).
The table above is measured that way, for every algorithm: the proposal holds 95 MB and
Pre-HUSPM 222, with the same patterns as before.
