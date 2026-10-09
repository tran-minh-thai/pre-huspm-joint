# Running the measurement campaign

How the campaign is run, which arms each scenario has, and what the arms of the proposal and
of remining cost. The costs are those measured on `PC-THAI` (AMD Ryzen 9 9950X, 64 GB, OpenJDK
25.0.4, `-Xmx48g`), the machine every result in `results/campaign` and `results/campaign-mem`
comes from.

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
last arm takes, bounded by `CapSeconds`. Among the arms of the proposal and of remining, the
longest is FIFA/Remine-from-scratch in SC2 (see "What each scenario costs").

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

| scenario | arms | arms per database |
|---|---:|---|
| SC1 | 48 | the six static algorithms on eight databases |
| SC2 | 35 | `Adaptive-Full`, `Pre-HUSPM-SWUmax`, `Pre-HUSPM-TSU`, `Remine-from-scratch`, `IncUSP-Miner+` on seven databases |
| SC3 | 18 | the six static algorithms on SIGN, LEVIATHAN, C8T1S5I8N5K |
| SC4 | 6 | `Pre-HUSPM` and `Adaptive-Full` on the same three |
| SC5 | 15 | SC2's five arms on the same three |
| SC6 | 6 | `Adaptive-Full` and `Remine-from-scratch` on the same three |
| SC7 | 27 | `proposed_full`, `proposed_no_IUB`, `Pre-HUSPM` on seven databases; `proposed_C5_on`, `proposed_C5_off`, `proposed_no_block_index` on SIGN and C8T1S5I8N5K |
| SC1, live memory | 48 | as SC1 |
| SC2, live memory | 35 | as SC2 |
