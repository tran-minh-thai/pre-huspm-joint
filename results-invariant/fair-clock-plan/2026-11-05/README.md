# Fair timed region: cells measured again or for the first time (2026-11-05)

`plan.tsv` lists every campaign cell the fair timed region changes, written by

    python3 analysis/affected_runs.py --fair-clock \
        --extra-arm Remine-from-scratch-rebuild:Remine-from-scratch \
        --extra-arm Remine-own-UUT-rebuild:Remine-own-UUT --plan-out plan.tsv

before `--remove` deleted the cells of kind `again`. Columns: tree, replicate (`s<seed>r<repeat>`,
empty for a main tree), scenario, database, arm, kind.

| kind | cells | what |
|---|---|---|
| again | 206 | USpan, IncUSP-Miner+ and Pre-HUSPM timing in SC1, SC3, SC4 and SC7; IncUSP-Miner+, Pre-HUSPM-SWUmax and Pre-HUSPM-TSU in SC5; the sampled SC1 live sets of the method, Joint-miner@UUT, Pre-HUSPM and IncUSP-Miner+ |
| new | 315 | Remine-from-scratch-rebuild and Remine-own-UUT-rebuild in every tree, scenario and database where the arm they rebuild was measured |
| undeferred | 15 | DUNNHUMBY replicates a statement of the paper depends on (the two Pre-HUSPM-tracking arms and Pre-HUSPM-SWUmax in SC2, USpan's sampled SC1 live set, Pre-HUSPM in SC7) |

Cells not listed keep their measurement: their timed code is unchanged (compared on bytecode
compiled without debug information), or, for Pre-HUSPM-SWUmax and Pre-HUSPM-TSU in SC2, the only
change is computing the utility of the inserted sequences, bounded below 0.2% of their time.
The 22 DUNNHUMBY replicates still set aside give the measured reason in their DEFERRED file.

After the removal, `affected_runs.py --fair-clock` lists no cell to measure again, and every planned
cell has neither a DONE nor a DEFERRED file, so `campaign.ps1` runs each of them.
