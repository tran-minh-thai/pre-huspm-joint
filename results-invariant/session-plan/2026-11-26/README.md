# DUNNHUMBY SC2: cells measured again on the new arithmetic, in the method's session (2026-11-26)

`plan.tsv` lists the SC2 timing cells of DUNNHUMBY measured again after the old against new timing
pairs (`docs/exact-arithmetic-timing.md`, Outcome) found that `Pre-HUSPM-Joint` on DUNNHUMBY does not
keep its campaign timings (one pair of four at q = 1.1097, bound 1.098296). Written by

    python analysis/session_cells.py --db DUNNHUMBY --drift 1.664 --new-session SC2 --plan-out plan.tsv --remove

(`console.txt`, `run_meta.json`), which then deleted the run directories of the listed cells, so
that `campaign.ps1` measures them again. Columns as in `../2026-11-10/README.md`.

| kind | cells | what |
|---|---|---|
| method | 5 | `Pre-HUSPM-Joint` in `results/campaign` and in `results/campaign-replicates/s0r1` to `s0r4`, the five runs the SC2 tables pool |
| near | 82 | every arm whose time is within a factor 1.664 of the method's in at least one tree: the remining rivals (`Remine-from-scratch`, `Remine-own-UUT` and their `-rebuild` arms), `Joint-miner+SWUmax`, `Joint-miner+TSU`, `Joint-rule+min-floor`, `Joint-rule+plain-floor`, `Pre-HUSPM-Joint-fixed-threshold`, `Pre-HUSPM-Joint-time`, the window arms `-w1` to `-w10` and the break-even arms `-be1` to `-be20` |

21 cells in each of the main tree and replicates 1 and 2, 12 in each of replicates 3 and 4;
5.63 hours by their DONE files (an estimate of the next run, not a measurement).

Left as measured: 34 far cells (`IncUSP-Miner+`, `Pre-HUSPM-SWUmax`, `Pre-HUSPM-TSU`, `Remine-HUSP-SP`,
`Remine-HUSP-ULL` and the two `PreHUSPM-tracking` arms), whose time is outside that factor in every
tree, so the side of the comparison cannot change; and the cells of the other scenarios.

Until the cells are measured again, the SC2 DUNNHUMBY rows of these arms are absent from `results/`.
