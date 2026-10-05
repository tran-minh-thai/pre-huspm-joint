# SC2 memory: the state an incremental method holds over 100 increments

The same arms as `results/campaign/SC2` - seven databases, `D_init` then K = 100 increments -
measured for memory instead of time. At every sample the runner forces a full collection and
reads the heap immediately after, so what is recorded is the live set; runtimes are void and
written as -3. The wait between samples is the larger of 20 ms and four times the last
collection. `PC-THAI`, JDK 25, `-Xmx48g`, one JVM per arm, two sittings: 8.0 hours for 29
arms, then about 3 hours for FIFA's Remine-from-scratch (9,757 s) and IncUSP-Miner+ (1,063 s).

Every row of an arm is read against one baseline, taken after a full collection before the
arm's algorithm exists (`docs/result-columns.md`). A row's `mem_retained_mb` is therefore what
the algorithm holds after that increment, and `mem_live_mb` the peak during it with that state
included. Remine-from-scratch starts a new miner at every increment, so its rows are those of a
fresh mine of the database seen so far. The harness's own list of the sequences seen so far
grows along the stream and is counted alike in every arm.

31 of 35 arms ran; every increment of every arm that got past `D_init` is recorded, except
where IncUSP-Miner+ ran out of memory.

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
| TAFENG | 108 | 52 | 58 | 71 | 1,067 |
| LEVIATHAN | 67 | ≥ 48 GB on `D_init` | ≥ 48 GB on `D_init` | 35 | 44,645 at 74, then OOM |
| BIBLE | 308 | 379 | 1,199 | 73 | 43,566 at 40, then OOM |
| C8T1S5I8N5K | 1,316 | 368 | 352 | 2,224 | 15,788 |
| KOSARAK | 3,161 | 874 | deferred | 542 | 44,006 at 11, then OOM |
| FIFA | 79 | 503 | deferred | 60 | OOM on `D_init` |

## Peak live set over the stream

The largest `mem_live_mb` of any increment, state included; in megabytes.

| database | Adaptive | Pre-HUSPM | Pre-HUSPM-TSU | Remine-from-scratch |
|---|---:|---:|---:|---:|
| SIGN | 17 | 89 | 95 | 21 |
| TAFENG | 108 | 167 | 182 | 74 |
| LEVIATHAN | 88 | - | - | 61 |
| BIBLE | 390 | 2,105 | 2,527 | 178 |
| C8T1S5I8N5K | 1,532 | 633 | 644 | 2,421 |
| KOSARAK | 4,216 | 2,811 | - | 1,557 |
| FIFA | 178 | 946 | - | 171 |

## What the numbers say

**Against IncUSP-Miner+** the proposal holds less on every database where both reach the end:
about 10 times less on TAFENG (108 against 1,067), 12 times on C8T1S5I8N5K (1,316 against
15,788) and nearly a thousand times on SIGN (13 against 12,836). IncUSP-Miner+ does not reach
the end of the stream within 48 GB on the other four.

**Against Pre-HUSPM the comparison is mixed**, and the paper should say so. The proposal holds
less on SIGN (13 against 36), BIBLE (308 against 379) and FIFA (79 against 503), and Pre-HUSPM
cannot start on LEVIATHAN within 48 GB while the proposal ends at 67 MB. It holds more on
TAFENG (108 against 52, 2.1 times), C8T1S5I8N5K (1,316 against 368, 3.6 times) and KOSARAK
(3,161 against 874, 3.6 times). By peak live set the picture is the same: lower on SIGN, TAFENG,
BIBLE and FIFA, higher on C8T1S5I8N5K and KOSARAK.

**Against Remine-from-scratch** the proposal holds more on five of the seven databases, which is
what an incremental method is: Remine keeps nothing between increments but its answer, while
the proposal also keeps the pre-large state that lets it skip the rescans. The exceptions are
SIGN and C8T1S5I8N5K, where a fresh mine of the grown database needs more than the proposal
keeps.

## Where the proposal's state grows: KOSARAK and C8T1S5I8N5K

On KOSARAK the proposal's state grows from 41 MB after `D_init` to 3,161 MB after the last
increment, 77 times; on the others it grows at most eleven times. On C8T1S5I8N5K it grows by a
third but starts high: a live set of 1,166 MB on `D_init`, matching the static 1,154 MB in
`results/campaign-mem/SC1` and already larger than Pre-HUSPM's 435 MB there, although the
proposal's pre-large set is 27 times smaller. On both databases the memory is therefore not
the pre-large set, and what it is has not been established. These are the two databases where
the proposal's memory claim does not hold against Pre-HUSPM, and the question SC1 left open is
the same one.

## How many samples a figure rests on

Rows are taken at the sampling rate their duration allows, so the fast increments of the small
databases are read at their start and end only (`gc_forced` of 2 or less): all 100 increments
of TAFENG's Adaptive and Remine arms, 41 of SIGN's Adaptive increments, and nearly every row of
IncUSP-Miner+. For those rows the peak is what
the increment kept. `mem_retained_mb` is read after a full collection at the end of every row
whatever the sample count, so the state-held table does not depend on sample density; the
peak table does, and on those rows is a floor.
