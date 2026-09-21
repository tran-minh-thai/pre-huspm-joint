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

Start from an empty `results/campaign`. The measurements on this machine mix 24 GB and
48 GB ceilings and cannot be mixed into one table.

## Settled parameters

| parameter | value | why |
|---|---|---|
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
