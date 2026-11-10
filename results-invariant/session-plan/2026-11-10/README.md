# DUNNHUMBY: cells measured again in the method's session (2026-11-10)

`plan.tsv` lists the timing cells of DUNNHUMBY that are measured again so that the method and
every arm compared with it within a factor 1.664 ran in one session. Written by

    python3 analysis/session_cells.py --db DUNNHUMBY --drift 1.664 --plan-out plan.tsv

before `--remove` deleted the run directories of the listed cells (`console.txt`, `run_meta.json`).
Columns: tree, replicate (`s<seed>r<repeat>`, empty for the main tree), scenario, database, arm,
kind, seconds (wall time of the cell's previous run, from its DONE file).

Why. The times of DUNNHUMBY move between sessions: the same arm, seed and source commit measured
on two days differ by up to a factor 1.664 (`results-invariant/session-drift/2026-11-06`), and
pinning the JVM to one L3 cache does not narrow the spread
(`results-probe/core-placement/SC2-DUNNHUMBY-PC-THAI-2026-11-09`). A ratio between two arms measured
in different sessions therefore measures the sessions as well. Two arms count as measured in one
session when their DONE files are at most 6 h apart.

| kind | cells | what |
|---|---|---|
| method | 21 | the method's run of each (tree, scenario) that holds a near cell |
| near | 52 | arms measured in another session whose time is within a factor 1.664 of the method's in at least one tree: the two remining rivals in SC2, SC6, SC6D and SC6B, Joint-miner+SWUmax and Joint-miner+TSU in SC2, HUSP-ULL and Joint-miner@UUT in the main tree of SC1 |
| same | 247 | arms that shared the method's session; measured again because the method is |

320 cells in 21 (tree, scenario) pairs, 10.33 hours by their DONE files (an estimate of the next
run, not a measurement).

Left as measured: 24 far cells (Pre-HUSPM, USpan, IncUSP-Miner+, the two Pre-HUSPM-tracking arms,
Pre-HUSPM-SWUmax, Pre-HUSPM-TSU, HUSP-SP in the main tree of SC1), whose time is outside that
factor in every tree, so the side of the comparison cannot change and only its value is
approximate; and the 17 cells of the (tree, scenario) pairs without a near cell (SC7, and the
replicates of SC1). The long streams are not part of the plan: `analysis/long_streams.py` takes
their ratios inside the runs measured in one session.

Until the cells are measured again, the DUNNHUMBY rows of SC1 (main tree), SC2, SC6, SC6D and
SC6B are absent from `results/`, and `figures/campaign` holds the tables of the previous
measurement.
