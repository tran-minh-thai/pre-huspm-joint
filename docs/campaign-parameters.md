# Running the measurement campaign

How the campaign is run, what it costs, and which arms do not finish. The costs are those
measured on `PC-THAI` (AMD Ryzen 9 9950X, 64 GB, OpenJDK 25.0.4, `-Xmx48g`), the machine every
result in `results/campaign` and `results/campaign-mem` comes from.

## The command

    cd <repo>; git pull; .\fetch-datasets.ps1        # first time only
    .\campaign.ps1                                   # SC2, the default
    .\campaign.ps1 -Scenario SC1                     # and so on
    .\campaign.ps1 -List                             # what is left, without running anything

One JVM per arm. Each arm writes `CLAIM` while it runs and `DONE` when it finishes, both
committed and pushed, so several machines can run the same command and divide the work
without being told who does what. `DEFERRED` marks an arm set aside on purpose, with the
reason in the file; deleting the file requeues it. `EMPTY` marks an arm that ran and wrote no
rows, because the scenario has no such arm on that database; `-List` names it. An arm whose
directory is removed is queued again, which is how an arm is re-measured after a change to
the code it runs.

`campaign.ps1` checks the JVM against `environment.lock.json` (runtime version and vendor)
before it measures anything, and compiles `src/` once per invocation.

## Running it in sessions

    .\campaign.ps1 -Scenario SC2 -SessionHours 10

The loop stops taking new arms once the budget is spent, prints how many arms it finished
and how many are left, and exits. The same command later continues where it stopped: an arm
is the unit of progress, every finished arm is already committed and pushed, and an arm
interrupted mid-run leaves only its `CLAIM`, which is offered again after `CapSeconds` plus
an hour.

The session stops between arms, never inside one, so a session overruns by however long its
last arm takes, bounded by `CapSeconds`. The longest arm that finishes is
FIFA/Remine-from-scratch: 2.2 h in timing mode, 2.7 h in live memory mode.

Several scenarios can share one sitting by chaining the commands; each takes its own
`-SessionHours` budget, so give the later ones a smaller budget to keep the sitting bounded.

## The memory campaign

    .\campaign.ps1 -Scenario SC1 -MemMode live -SessionHours 10

A separate campaign, run apart from the timing campaign. The runner forces a full collection
at every sample and reads the heap right after, so its runtimes are void and recorded as -3;
what it measures is the live set. It writes to its own tree, `results/campaign-mem`, with its
own `DONE` and `CLAIM` files and the same arms per scenario, so timing rows and memory rows
never share a directory. SC1 and SC2 are measured this way.

The sampling interval stretches with the size of the live set (see
`docs/result-columns.md`), and every row records how many samples it rests on in
`gc_forced`. In the incremental scenarios every row of an arm is read against one baseline
taken before the arm's algorithm exists, so `mem_retained_mb` is the state the algorithm holds
after each increment.

## Settled parameters

| parameter | value | why |
|---|---|---|
| JDK | Eclipse Temurin 25.0.4.1+1 | One exact build, pinned in `environment.lock.json` and checked by `campaign.ps1`, because runtimes from two JVMs do not belong in one table. The source compiles on JDK 17 or later. |
| heap | `-Xmx48g` (`-HeapGb`) | One ceiling for the whole campaign, since it decides how far a memory-bound arm gets; 48 GB leaves the operating system 16 GB of 64. |
| item ids | compacted on C8T1S5I8N5K only | Its 68,240 items carry ids up to 4,999,999 and every miner sizes per-item arrays by the largest id. Each id is replaced at load by its rank in the `_eui` file (`Datasets.compactItemIds`), which keeps the item order and so the patterns. The other seven databases are close to dense and are loaded as they are. |
| `D_init` | per database, `Datasets.Entry.dInitCount` | Yan et al. (2025), Table 16 (OD.Num), for the databases both studies use; KOSARAK takes their Kosarak10k split scaled by a hundred; C8T1S5I8N5K and TAFENG keep an incremental stream of about 20,000 sequences. |
| increments | `K = 100` | equal increments in SC2 and the SC7 incremental ablations. |
| `r_l` | 0.80, KOSARAK 0.85 | At KOSARAK's lowest threshold 0.80 exceeds three billion candidates, while 0.85 returns 16 patterns in 29 s. The proposal and Pre-HUSPM read the same value. |
| repeats | 1 per arm; replicates as separate arms | `-Repeat n` and `-SeedIndex k` measure a replicate in a fresh JVM, into `results/campaign-replicates/s<k>r<n>`. Three repeats for SC1 and for SC2's proposal and remining on SIGN, TAFENG, BIBLE and C8T1S5I8N5K; five seeds for SC6. See `docs/experiments.md`, "Repetition". |
| `SUBSET_3` | SIGN, LEVIATHAN, C8T1S5I8N5K | Small and dense, medium, and the one database with more than one item per event. Used by SC3, SC4, SC5 and SC6. |
| `C5_ABLATION_SET` | SIGN, C8T1S5I8N5K (BMS1 listed, filtered out by scope) | The databases of SC7's incremental ablations (block index and adaptive rescan). `proposed_C5_off` rescans every increment, so it costs a hundred full mines. |
| BMS1 | SC1 only | No threshold that returns patterns can be mined on the whole database. |

## What each scenario costs

Machine time on `PC-THAI`, summed over the arms that finished (from the `seconds` field of
each `DONE`), one repeat.

| scenario | arms | timing campaign | longest arm |
|---|---:|---:|---|
| SC1 | 48 | 2.9 h | FIFA/IncUSP-Miner+, 0.7 h |
| SC2 | 33 + 2 deferred | 9.0 h | FIFA/Remine-from-scratch, 2.2 h |
| SC3 | 18 | 0.3 h | LEVIATHAN/IncUSP-Miner+, 3 min |
| SC4 | 6 | 0.3 h for the five arms other than C8T1S5I8N5K/Adaptive-Full, which is being re-measured | C8T1S5I8N5K/Pre-HUSPM, 17 min |
| SC5 | 15 | 9.1 h | LEVIATHAN/IncUSP-Miner+, 2.1 h |
| SC6 | 6 | 2 min | |
| SC7 | 27 | 0.6 h | FIFA/Pre-HUSPM, 0.2 h |
| SC1, live memory | 48 | 5.6 h | LEVIATHAN/Pre-HUSPM, 2.0 h |
| SC2, live memory | 31 + 4 deferred | 11.1 h | FIFA/Remine-from-scratch, 2.7 h |

About 22 hours for the timing campaign without that SC4 arm, and 17 for the memory campaign.
Live mode costs about twice timing mode on SC1 and 1.2 times on SC2, where the long arms are
dominated by work the forced collections do not slow in proportion.

SC5 costs about what SC2 costs although it runs four strategies at four K: the grid does not
get cheap as K falls, because each increment carries a fixed cost and the rescans do not thin
out in proportion.

## Arms that do not finish

These are results, not faults.

| arm | what happens |
|---|---|
| IncUSP-Miner+ in SC2 on BIBLE, KOSARAK, LEVIATHAN, FIFA | Exhausts 48 GB. On BIBLE it completes 40 increments and runs out of memory on the 41st; on KOSARAK and LEVIATHAN the memory guard stops it before increments 12 and 75; on FIFA it does not finish the initial mine. The same holds in the memory campaign. |
| Pre-HUSPM and Pre-HUSPM-TSU on LEVIATHAN in SC2 | Exhaust 48 GB on `D_init`. Pre-HUSPM completes SC4 on the whole database at `r_l` = 0.7 because the threshold is a ratio of total utility: `TSU(D_init)` is 4,960,437 against 10,502,159 for the whole database, so SC2's absolute floor (at `r_l` = 0.8) is the lower of the two. Deferred in the memory campaign. |
| Pre-HUSPM on LEVIATHAN and C8T1S5I8N5K in SC4 at `r_l` = 0.3 and 0.5 | Exhausts 48 GB. Where both complete, its pre-large set is 18 to 69 times the proposal's at the same `r_l`. |
| Pre-HUSPM-TSU on FIFA and KOSARAK in SC2 | Does not finish its 100 increments within the eight-hour cap: the `TSU(d)` test rescans far more often than the `SWUmax(d)` test, as Yan et al. (2025) report for it. Deferred in both campaigns; the `DEFERRED` file gives the reason. |
