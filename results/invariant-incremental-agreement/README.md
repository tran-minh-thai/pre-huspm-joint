# The incremental algorithms report the same HUSPs after every operation

Kind: invariant (pattern counts read from the timing campaign; machine-independent).
`python3 analysis/incremental_agreement.py`; `run/` holds its `run_meta.json` and console.

Each incremental algorithm's `total_patterns_found` is compared with Pre-HUSPM-Joint's
(`Adaptive-Full`), row by row, over SC2 and SC5, on the rows both finished. Per database:
`agreement.csv`.

| algorithm | rows compared (SC2 + SC5) | rows with fewer HUSPs | rows with more |
|---|---:|---:|---:|
| Remine-from-scratch | 3,659 | 0 | 0 |
| Pre-HUSPM-SWUmax | 3,659 | 0 | 0 |
| Pre-HUSPM-TSU | 3,659 | 0 | 0 |
| IncUSP-Miner+ | 3,584 | 0 | 0 |

IncUSP-Miner+ on FIFA is compared on the 26 rows it finished before running out of memory.
Pre-HUSPM-Joint itself is checked against remining from scratch in `results/verify-joint`.
