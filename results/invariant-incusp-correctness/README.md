# IncUSP-Miner+ returns the exact HUSPs after every insertion batch

Kind: invariant (pattern sets and utilities; machine-independent). Each subdirectory holds
`run_meta.json` (command, commit, source digest, JVM, machine) and `console.txt`.

`verify.IncUspVerifier` compares the HUSP set of `algorithms.IncUSPMinerPlusAlgorithm`, and
the utility of every HUSP, with the exact answer after the initial mine and after every
batch. It also reports how often the incremental phase rescanned the old sequences
(Strategy 6 of Wang and Huang), built a new tree node (HandleCase-2) and re-ran USP-Miner on a
node, so that zero mismatches is not read over paths that did not run.

## Random streams - `random/`

15 to 29 random sequences, ten insertion batches of one to three sequences, 300 trials per
seed. References: remining with the proposal's static miner, and a brute-force enumeration
that takes u(t,s) from its definition on the itemsets.

| seed | comparisons | mismatches (static) | mismatches (brute force) | NDB rescans | HandleCase-2 | USP-Miner reruns |
|---:|---:|---:|---:|---:|---:|---:|
| 7 | 3300 | 0 | 0 | 11273 | 5082 | 5698 |
| 11 | 3300 | 0 | 0 | 11017 | 5150 | 5695 |
| 23 | 3300 | 0 | 0 | 10798 | 4911 | 5395 |
| 101 | 3300 | 0 | 0 | 10054 | 4304 | 5142 |

## Real databases - `real-<DATABASE>/`

`D_init` and the default threshold of `common.Datasets`, the rest of the database in 100
equal insertion batches in file order; reference: remining with the static miner.

| database | comparisons | mismatches | NDB rescans | HandleCase-2 | USP-Miner reruns |
|---|---:|---:|---:|---:|---:|
| SIGN | 101 | 0 | 83121 | 17151 | 65079 |
| TAFENG | 101 | 0 | 432 | 82 | 306 |
| C8T1S5I8N5K | 101 | 0 | 29527 | 27735 | 91591 |
| LEVIATHAN | 101 | 0 | 90384 | 8689 | 31219 |

## The check fails a wrong implementation

Raw output: `fault-injection/console.txt`, produced by `fault-injection/inject.py`.

The implementation before commit 899dd59 (no rescan of the old sequences, no USP-Miner rerun,
TSU from the first stored instance), run through the same check:

| run | comparisons | mismatches |
|---|---:|---:|
| random, seed 7 | 3300 | 414 |
| real TAFENG | 101 | 19 |
| real C8T1S5I8N5K | 101 | 31 |

Faults injected one at a time into the current implementation, random streams, seed 7:
no NDB rescan 376 mismatches; HandleCase-2 built from the inserted sequences only 376; TSU
without the remaining utility 526; no USP-Miner rerun 1 (3, 3 and 5 at seeds 11, 23 and 101).
