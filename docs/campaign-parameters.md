# Running the measurement campaign

Everything below was settled by measurement on an i7-10750H / 64 GB laptop between
2026-09-18 and 2026-09-21. None of those numbers belong in the paper: they were taken
under two different heap ceilings and on hardware that may not be the one used in the end.
They are here to say what the campaign costs and where it breaks.

## The command

    cd <repo>; git pull; .\fetch-datasets.ps1        # first time only
    .\campaign.ps1                                   # SC2, the default
    .\campaign.ps1 -Scenario SC1                     # and so on
    .\campaign.ps1 -List                             # what is left, without running anything

One JVM per arm. Each arm writes `DONE` when it finishes and `CLAIM` while it runs, both
committed and pushed, so several machines can run the same command and divide the work
without being told who does what. `DEFERRED` marks an arm set aside on purpose; deleting
the file requeues it.

Start from an empty `results/campaign`. The earlier arms are archived under
`results/campaign-jdk21`: they mix 24 GB and 48 GB heap ceilings and were measured on
JDK 21, so none of them can go in a table beside the ones measured now. They are kept for
the cost figures below and for the failure modes, not as results.

## Running it in sessions

The campaign is tens of hours, so it runs in sittings of eight to ten:

    .\campaign.ps1 -Scenario SC2 -SessionHours 8

The loop stops taking new arms once the budget is spent, prints how many arms it finished
and how many are left, and exits. The same command tomorrow continues where it stopped: an
arm is the unit of progress, every finished arm is already committed and pushed, and an arm
interrupted mid-run leaves only its `CLAIM`, which is offered again after `CapSeconds` plus
an hour.

The session stops between arms, never inside one. An arm killed on a wall-clock boundary is
not a measurement - how far it got would depend on when the sitting began - so a session
overruns by however long its last arm takes, bounded by `CapSeconds`. Eight hours of budget
with the default eight-hour cap therefore lands inside ten in every case seen so far, where
the longest arm that finishes is FIFA/Remine-from-scratch at 4.9 h.

Order, heaviest first, so the numbers the paper leans on exist before the cheap ones:

| sitting | command | measured on the laptop |
|---|---|---|
| 1-3 | `-Scenario SC2 -SessionHours 8` | ~41 h, three quarters of the campaign |
| 4 | `-Scenario SC5 -SessionHours 8` | ~7.3 h |
| 5 | `-Scenario SC1 -SessionHours 8` | 7 h and up; FIFA is the part that hurts |
| 6 | `-Scenario SC3`, then SC4, SC6, SC7 | ~2.6 h together |

On a desktop Zen 5 expect roughly half of each figure, and less again when two machines take
arms from the same queue.

Two arms are known not to finish: FIFA/Pre-HUSPM-TSU and KOSARAK/Pre-HUSPM-TSU, which reached
95 and 89 of 101 batches in 13.1 h and 8 h. Left in the queue they spend `CapSeconds` each
proving it again, which is a whole sitting. Their `DEFERRED` files are in
`results/campaign-jdk21`; copy them into the new tree before the first SC2 sitting to keep
them out, or leave them queued if the point is to confirm the behaviour under this JDK.

## The memory campaign

    .\campaign.ps1 -Scenario SC1 -MemMode live -SessionHours 8

A separate campaign, run after the timing campaign and never alongside it. The runner forces
a full collection at every sample and reads the heap right after, so its runtimes are void
and recorded as -3; what it measures is the live set. It writes to its own tree,
`results/campaign-mem`, with its own DONE and CLAIM files and the same arms per scenario, so
timing rows and memory rows never share a directory.

The sampling interval stretches with the size of the live set - see
`docs/result-columns.md` - and every row records how many samples it rests on in
`gc_forced`. SC1 first: it is the static table the memory comparison rests on, and it took
2.9 hours in timing mode on the measurement machine. SC2 took 8.9 hours in timing mode;
decide on it once SC1 shows what live mode costs.

## Settled parameters

| parameter | value | why |
|---|---|---|
| JDK | **25** | One version for the whole campaign, checked by `campaign.ps1` before it measures anything. The source uses no API removed since 17, so this is about comparability rather than compilation: the arms are spread over several machines, and runtimes from two JVM versions do not belong in one table. Changing the number invalidates every arm already measured. |
| heap | `-Xmx48g` | One ceiling for the whole campaign. It decides results: IncUSP-Miner+ reaches batch 7 on KOSARAK at 24 GB and batch 12 at 48. 48 leaves the OS 16 GB of 64. |
| `D_init` | per database, `Datasets.Entry.dInitCount` | Yan et al. Table 16 OD.Num, verbatim for the five shared databases. A flat 50% had left rescan_count at zero in 15 of 18 cells. |
| increments | `K = 100` | Yan et al. section 6.1. At the K=5 this study used, Adaptive and Pre-HUSPM do not separate. |
| `r_l` | 0.80, KOSARAK 0.85 | 0.8 x KOSARAK's lowest threshold exceeds three billion candidates; 0.85 returns 16 patterns in 29 s. Both proposal and baseline read the same value. |
| repeats | 1 | SC2 takes one seed and splits in file order, so repeats measured clock jitter at three times the cost. |
| `SUBSET_3` | SIGN, LEVIATHAN, C8T1S5I8N5K | KOSARAK's SC5 grid alone is about fifty hours. Used by SC3, SC4, SC5 and SC6. |
| `C5_ABLATION_SET` | SIGN, C8T1S5I8N5K (BMS1 listed, filtered) | `proposed_C5_off` rescans every batch, so it costs a hundred full mines. |
| BMS1 | SC1 only | No threshold mines the whole database: 0.030 exceeds three billion candidates, 0.032 returns nothing. |

## What each scenario costs

Measured on the machine above, one repeat, 48 GB unless noted.

| scenario | measured | whole scenario |
|---|---|---|
| SC1 | SIGN 82 s, TAFENG 237 s, **FIFA >7978 s and unfinished** | 7 h and up |
| SC2 | all seven databases | ~41 h |
| SC3 | SIGN 161 s | ~1 h |
| SC4 | SIGN 65 s, LEVIATHAN 244 s | ~0.5 h |
| SC5 | SIGN 421 s, LEVIATHAN 1594 s, both full grids | ~7.3 h |
| SC6 | SIGN 19 s | ~10 min |
| SC7 | TAFENG 7.4 s, SIGN 112 s | ~1 h |

About 58 hours in total, three quarters of it SC2. On a desktop Zen 5 expect roughly half.

SC5 costs 9 to 10.5 times what SC2 costs for the same arm, and the grid does not get cheap
as K falls: halving K saves 24% on SIGN and 26% on LEVIATHAN, not 50%, because each batch
carries a fixed cost and the rescans do not thin out in proportion.

## Known failures - these are results, not faults

| arm | what happens |
|---|---|
| IncUSP-Miner+ on BIBLE, KOSARAK, LEVIATHAN, FIFA | Exhausts 48 GB. Reaches batch 41, 12 and 75 respectively; on FIFA it does not finish the first mine. It fails the same way in the submitted results, on five databases. |
| Pre-HUSPM and Pre-HUSPM-TSU on LEVIATHAN in SC2 | Exhaust 48 GB on D_init. Note they succeed in SC4 on the WHOLE database at sl=0.7, which is not a contradiction: the threshold is a ratio of TSU, and TSU(D_init) is about half TSU(full), so SC2's absolute floor is the lower of the two. |
| Pre-HUSPM on LEVIATHAN in SC4 at sl=0.3 and 0.5 | Exhausts 48 GB. Its pre-large set is 18 to 60 times Adaptive's at the same sl. |
| Pre-HUSPM-TSU on FIFA and KOSARAK in SC2 | Does not finish. FIFA reached 95 of 101 batches in 13.1 h; KOSARAK 89 of 101 in 8 h. Marked DEFERRED with the numbers. |

## Open before a final run

1. **SC7 cannot be split by arm.** `logAblation` does not call `algoEnabled`, so
   `campaign.ps1 -Scenario SC7` repeats every arm once per algorithm name - five times the
   work. Either add the check or run SC7 one JVM per database.
2. **SC1 on FIFA.** Four hours produced 8 of 24 runs and two OOMs. Its four IncUSP-Miner+
   runs each spend about 43 minutes reaching the same OutOfMemoryError; that is one line of
   result bought four times over.
3. **The LEVIATHAN TSU figure** in the table above is reasoned, not measured. TSU(D_init) is
   4,960,437; TSU(full) has not been read off.
