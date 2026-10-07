# SC2 memory: the state an incremental method holds over 100 increments

> **Being re-measured.** The proposal's arms here are queued again under the `joint` rescan
> rule (commit d5b47ab and later). Every figure for the proposal below - Adaptive-Full and the
> proposed_* ablations of the incremental part - comes from the superseded `iaumax` rule at
> commit 4e3f256 and is not to be cited. The baselines' figures stand.

The same arms as `results/campaign/SC2` - seven databases, `D_init` then K = 100 increments -
measured for memory instead of time. At every sample the runner forces a full collection and
reads the heap immediately after, so what is recorded is the live set; runtimes are void and
written as -3. The wait between samples is the larger of 20 ms and four times the last
collection. `PC-THAI`, JDK 25, `-Xmx48g`, one JVM per arm, 11.1 hours of machine time.

Every row of an arm is read against one baseline, taken after a full collection before the
arm's algorithm exists (`docs/result-columns.md`). A row's `mem_retained_mb` is therefore what
the algorithm holds after that increment, and `mem_live_mb` the peak during it with that state
included. Remine-from-scratch starts a new miner at every increment, so its rows are those of a
fresh mine of the database seen so far. The harness's own list of the sequences seen so far
grows along the stream and is counted alike in every arm.

31 of 35 arms ran; every increment of every arm that got past `D_init` is recorded, except
where IncUSP-Miner+ ran out of memory. Every arm reports the same pattern counts as its
timing-campaign counterpart.

## What was not measured

| database | algorithm | outcome |
|---|---|---|
| LEVIATHAN | Pre-HUSPM, Pre-HUSPM-TSU | deferred: out of memory on `D_init` in the timing campaign, so at least 48 GB |
| FIFA, KOSARAK | Pre-HUSPM-TSU | deferred: does not finish its 100 increments within the eight-hour cap in timing mode |
| FIFA | IncUSP-Miner+ | out of memory on `D_init` (47,830 MB retained, 48,164 MB live); 100 increments skipped |
| BIBLE, KOSARAK, LEVIATHAN | IncUSP-Miner+ | out of memory at increment 41, 12 and 75 |

The deferred arms carry a `DEFERRED` file with the reason. The IncUSP-Miner+ failures are the
same ones the timing campaign records, at the same increments.

## State held after the last increment

`mem_retained_mb` of the last increment, in megabytes. For IncUSP-Miner+ where it fails, the
last increment it completed.

| database | Adaptive | Pre-HUSPM | Pre-HUSPM-TSU | Remine-from-scratch | IncUSP-Miner+ |
|---|---:|---:|---:|---:|---:|
| SIGN | 13 | 36 | 37 | 17 | 12,836 |
| TAFENG | 90 | 52 | 58 | 65 | 1,067 |
| LEVIATHAN | 49 | ≥ 48 GB on `D_init` | ≥ 48 GB on `D_init` | 34 | 44,645 at 74, then OOM |
| BIBLE | 77 | 379 | 1,199 | 53 | 43,566 at 40, then OOM |
| C8T1S5I8N5K | 82 | 163 | 161 | 98 | 15,790 |
| KOSARAK | 529 | 874 | deferred | 400 | 44,006 at 11, then OOM |
| FIFA | 42 | 503 | deferred | 45 | OOM on `D_init` |

## Peak live set over the stream

The largest `mem_live_mb` of any increment, state included; in megabytes.

| database | Adaptive | Pre-HUSPM | Pre-HUSPM-TSU | Remine-from-scratch |
|---|---:|---:|---:|---:|
| SIGN | 17 | 89 | 95 | 21 |
| TAFENG | 94 | 167 | 182 | 69 |
| LEVIATHAN | 72 | - | - | 61 |
| BIBLE | 185 | 2,105 | 2,527 | 170 |
| C8T1S5I8N5K | 191 | 441 | 448 | 213 |
| KOSARAK | 1,686 | 2,811 | - | 1,539 |
| FIFA | 148 | 946 | - | 163 |

## What the numbers say

**Against IncUSP-Miner+** the proposal holds less on every database where both reach the end:
12 times less on TAFENG (90 against 1,067), 193 times on C8T1S5I8N5K (82 against 15,790) and
nearly a thousand times on SIGN (13 against 12,836). IncUSP-Miner+ does not reach the end of
the stream within 48 GB on the other four.

**Against Pre-HUSPM** the proposal holds less on five of the six databases where both run:
SIGN (13 against 36), BIBLE (77 against 379), C8T1S5I8N5K (82 against 163), KOSARAK (529
against 874) and FIFA (42 against 503, twelve times less). Pre-HUSPM cannot start on LEVIATHAN
within 48 GB, where the proposal ends at 49 MB. The exception is TAFENG, 90 against 52, where
every row of the proposal's arm was read at its start and end only and the state is small on
both sides. By peak live set the proposal is lower on all six.

**Against Remine-from-scratch** the proposal holds more on four of the seven databases -
TAFENG, LEVIATHAN, BIBLE and KOSARAK - and less on SIGN, C8T1S5I8N5K and FIFA. That is what an
incremental method is: Remine keeps nothing between increments but its answer, while the
proposal also keeps the pre-large state that lets it skip the rescans. The difference is at
most 129 MB, on KOSARAK.

## Where the proposal's state grows: KOSARAK

On KOSARAK the proposal's state grows from 33 MB after `D_init` to 529 MB after the last
increment, as the database grows elevenfold; on the other databases it grows less than four
times. Little of it is the pre-large state, which on KOSARAK is 12 patterns. Most of it is the
flat copy the miner keeps of every sequence it has seen, about 240 MB at the end, which
Remine-from-scratch builds too and which therefore sits in its 400 MB as well; the rest is the
miner's per-node buffers.

## How many samples a figure rests on

Rows are taken at the sampling rate their duration allows, so the fast increments of the small
databases are read at their start and end only (`gc_forced` of 2 or less): every row of
TAFENG's Adaptive and Remine arms, 41 of SIGN's Adaptive rows, and nearly every row of
IncUSP-Miner+. For those rows the peak is what the increment kept. `mem_retained_mb` is read
after a full collection at the end of every row whatever the sample count, so the state-held
table does not depend on sample density; the peak table does, and on those rows is a floor.
