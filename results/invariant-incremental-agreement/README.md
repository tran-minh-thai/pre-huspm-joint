# Do the incremental algorithms report the same HUSPs after every operation?

> This check describes the implementations of IncUSP-Miner+ and Pre-HUSPM before they were
> implemented as published; rerun `analysis/incremental_agreement.py` on the re-measured arms.

Kind: invariant (pattern counts read from the timing campaign; machine-independent).

`analysis/incremental_agreement.py` compares `total_patterns_found` of each incremental
algorithm with Adaptive-Full, row by row, over SC2 and SC5, on rows both finished.
Adaptive-Full is checked against remining from scratch in `results/verify-joint`.
Output: `agreement.csv`.

| algorithm | rows compared (SC2 + SC5) | rows with fewer HUSPs | rows with more |
|---|---:|---:|---:|
| Remine-from-scratch | 3,659 | 0 | 0 |
| Pre-HUSPM | 2,574 | 0 | 0 |
| Pre-HUSPM-TSU | 2,372 | 0 | 0 |
| IncUSP-Miner+ | 3,104 | 927 | 0 |

IncUSP-Miner+ reports fewer HUSPs than the exact answer after some insertion batches on
LEVIATHAN (up to 13.9% missing), TAFENG (12.5%) and C8T1S5I8N5K (2.1%); never on the initial
mine, never more. Per database: `agreement.csv`.
