# Running the measurement campaign

What the campaign costs, how it is run, and where it breaks. The costs are those measured on
`PC-THAI` (AMD Ryzen 9 9950X, 64 GB, JDK 25, `-Xmx48g`), the machine every result in
`results/campaign` and `results/campaign-mem` comes from.

## The command

    cd <repo>; git pull; .\fetch-datasets.ps1        # first time only
    .\campaign.ps1                                   # SC2, the default
    .\campaign.ps1 -Scenario SC1                     # and so on
    .\campaign.ps1 -List                             # what is left, without running anything

One JVM per arm. Each arm writes `DONE` when it finishes and `CLAIM` while it runs, both
committed and pushed, so several machines can run the same command and divide the work
without being told who does what. `DEFERRED` marks an arm set aside on purpose, with the
reason in the file; deleting the file requeues it. An arm whose directory is removed is
queued again, which is how an arm is re-measured after a change to the code it runs.

`results/campaign-jdk21` holds arms measured under JDK 21 with mixed 24 and 48 GB heap
ceilings. They are kept for the failure modes they document, not as results, and no table
draws on them.

## Running it in sessions

    .\campaign.ps1 -Scenario SC2 -SessionHours 10

The loop stops taking new arms once the budget is spent, prints how many arms it finished
and how many are left, and exits. The same command later continues where it stopped: an arm
is the unit of progress, every finished arm is already committed and pushed, and an arm
interrupted mid-run leaves only its `CLAIM`, which is offered again after `CapSeconds` plus
an hour.

The session stops between arms, never inside one. An arm killed on a wall-clock boundary is
not a measurement - how far it got would depend on when the sitting began - so a session
overruns by however long its last arm takes, bounded by `CapSeconds`. The longest arm that
finishes is FIFA/Remine-from-scratch: 2.2 h in timing mode, 2.7 h in live memory mode.

Several scenarios can share one sitting by chaining the commands; each takes its own
`-SessionHours` budget, so give the later ones a smaller budget to keep the sitting bounded.

## The memory campaign

    .\campaign.ps1 -Scenario SC1 -MemMode live -SessionHours 10

A separate campaign, run after the timing campaign and never alongside it. The runner forces
a full collection at every sample and reads the heap right after, so its runtimes are void
and recorded as -3; what it measures is the live set. It writes to its own tree,
`results/campaign-mem`, with its own DONE and CLAIM files and the same arms per scenario, so
timing rows and memory rows never share a directory. SC1 and SC2 are measured this way.

The sampling interval stretches with the size of the live set - see
`docs/result-columns.md` - and every row records how many samples it rests on in
`gc_forced`. In the incremental scenarios every row of an arm is read against one baseline
taken before the arm's algorithm exists, so `mem_retained_mb` is the state the algorithm holds
after each increment.

## Settled parameters

| parameter | value | why |
|---|---|---|
| JDK | **25** | One version for the whole campaign, checked by `campaign.ps1` before it measures anything. The source uses no API removed since 17, so this is about comparability rather than compilation: the arms are spread over several machines, and runtimes from two JVM versions do not belong in one table. Changing the number invalidates every arm already measured. |
| heap | `-Xmx48g` | One ceiling for the whole campaign. It decides results: IncUSP-Miner+ reaches batch 7 on KOSARAK at 24 GB and batch 12 at 48. 48 leaves the OS 16 GB of 64. Above 32 GB the JVM turns compressed object pointers off and G1 uses 32 MB regions, so every reference costs 8 bytes and an array over 16 MB occupies whole regions; this holds for every algorithm alike. |
| item ids | compacted on C8T1S5I8N5K only | Its 68,240 items carry ids up to 4,999,999 and every miner sizes per-item arrays by the largest id. Each id is replaced at load by its rank in the `_eui` file (`Datasets.compactItemIds`), which keeps the item order and so the patterns. The other seven databases are close to dense and are loaded as they are. |
| `D_init` | per database, `Datasets.Entry.dInitCount` | Yan et al. Table 16 OD.Num, verbatim for the five shared databases. A flat 50% leaves rescan_count at zero in 15 of 18 cells. |
| increments | `K = 100` | Yan et al. section 6.1. At K = 5 every increment rescans, and Adaptive and Pre-HUSPM do not separate. |
| `r_l` | 0.80, KOSARAK 0.85 | 0.8 x KOSARAK's lowest threshold exceeds three billion candidates; 0.85 returns 16 patterns in 29 s. Both proposal and baseline read the same value. |
| repeats | 1 per arm; replicates as separate arms | `-Repeat n` and `-SeedIndex k` measure a replicate in a fresh JVM, into `results/campaign-replicates/s<k>r<n>`. Three repeats for SC1 and for SC2's proposal and remine on SIGN, TAFENG, BIBLE and C8T1S5I8N5K; five seeds for SC6. See `docs/experiments.md` section 6. |
| `SUBSET_3` | SIGN, LEVIATHAN, C8T1S5I8N5K | KOSARAK's SC5 grid alone is about fifty hours. Used by SC3, SC4, SC5 and SC6. |
| `C5_ABLATION_SET` | SIGN, C8T1S5I8N5K (BMS1 listed, filtered) | The databases of SC7's incremental ablations (C2, C4, C5). `proposed_C5_off` rescans every batch, so it costs a hundred full mines. |
| BMS1 | SC1 only | No threshold mines the whole database: 0.030 exceeds three billion candidates, 0.032 returns nothing. |

## What each scenario costs

Machine time on `PC-THAI`, summed over the arms that finished, one repeat.

| scenario | arms | timing campaign | longest arm |
|---|---:|---:|---|
| SC1 | 48 | 2.9 h | FIFA/IncUSP-Miner+, 0.7 h |
| SC2 | 33 + 2 deferred | 9.0 h | FIFA/Remine-from-scratch, 2.2 h |
| SC3 | 18 | 0.3 h | LEVIATHAN/IncUSP-Miner+, 3 min |
| SC4 | 6 | 1.1 h | C8T1S5I8N5K/Adaptive-Full, 0.8 h |
| SC5 | 15 | 9.1 h | LEVIATHAN/IncUSP-Miner+, 2.1 h |
| SC6 | 6 | 2 min | |
| SC7 | 27 | 0.6 h | FIFA/Pre-HUSPM, 0.2 h |
| SC1, live memory | 48 | 5.6 h | LEVIATHAN/Pre-HUSPM, 2.0 h |
| SC2, live memory | 31 + 4 deferred | 11.1 h | FIFA/Remine-from-scratch, 2.7 h |

About 23 hours for the timing campaign and 17 for the memory campaign. Live mode costs about
twice timing mode on SC1 and 1.2 times on SC2, where the long arms are dominated by work the
forced collections do not slow in proportion.

SC5 costs about what SC2 costs although it runs four strategies at four K: the grid does not
get cheap as K falls, because each increment carries a fixed cost and the rescans do not thin
out in proportion.

## Known failures - these are results, not faults

| arm | what happens |
|---|---|
| IncUSP-Miner+ on BIBLE, KOSARAK, LEVIATHAN, FIFA | Exhausts 48 GB. Reaches increment 41, 12 and 75 respectively; on FIFA it does not finish the first mine. |
| Pre-HUSPM and Pre-HUSPM-TSU on LEVIATHAN in SC2 | Exhaust 48 GB on D_init. They succeed in SC4 on the whole database at r_l = 0.7, which is not a contradiction: the threshold is a ratio of total utility, and TSU(D_init) is 4,960,437 against 10,502,159 for the whole database, 0.47 of it, so SC2's absolute floor is the lower of the two. |
| Pre-HUSPM on LEVIATHAN and C8T1S5I8N5K in SC4 at r_l = 0.3 and 0.5 | Exhausts 48 GB. Where both complete, its pre-large set is 18 to 69 times Adaptive's at the same r_l. |
| Pre-HUSPM-TSU on FIFA and KOSARAK in SC2 | Does not finish within the eight-hour cap: under JDK 21 it reached 95 and 89 of 101 increments in 13.1 h and 8 h. Deferred in both campaigns, with the numbers in the DEFERRED files. |
