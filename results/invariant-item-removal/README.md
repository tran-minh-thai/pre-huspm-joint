# Irrelevant-item removal in the proposal's miner returns the same patterns

Kind: invariant (pattern sets, utilities and work counters; machine-independent). Measured on
the development machine; no timing is reported here. Each subdirectory holds `run_meta.json`
(command, commit, source digest, JVM, machine) and `console.txt`.

`-Dadapt.iip` selects `off` (the miner without removal), `once` (one removal pass at the root
and at each node) or `fixpoint` (each pass repeated until nothing more is removed).

## Against brute force - `random-seed1/`, `random-seed2-lazy/`

`verify.ItemRemovalCheck random 2000 <seed>`: 2000 small random databases per seed, with
frequent and rare items, several items per itemset and items repeated within a sequence, at
seven thresholds and three floor ratios. Each database is mined in all three modes and the
pattern set at the cutoff and at the threshold, with every utility, is compared with a
brute-force enumeration that takes utilities from their definition. `random-seed2-lazy` forces
the strategy that builds one child at a time (`-Dadapt.singlePassCap=0`). In both runs every
mode matches the brute force on every database, the removal path ran (`once` removed items at
the root in 820 and 841 databases and below the root in 1951 and 1947), and mining left every
sequence unchanged.

## Real databases - `real/`

`verify.ItemRemovalCheck real <DATABASE> init|whole floor`: `D_init` and the whole database at
the default threshold, mined down to the floor `r_l * UUT`, in all three modes. BMS1 and FIFA
are mined on `D_init` only, as in the campaign.

| database | scope | patterns (HUSP + pre-large) | explored: off / once / fixpoint | candidates: off / once / fixpoint | items removed: once / fixpoint | once, fixpoint vs off |
|---|---|---:|---|---|---|---|
| SIGN | `D_init` | 1951 | 69,792 / 12,273 / 8,784 | 9,425,036 / 70,528 / 15,950 | 654,289 / 511,043 | identical, identical |
| SIGN | whole | 533 | 38,372 / 8,071 / 5,935 | 7,294,276 / 51,598 / 11,656 | 549,909 / 451,973 | identical, identical |
| LEVIATHAN | `D_init` | 272 | 34,820 / 3,807 / 2,531 | 16,362,407 / 15,377 / 4,187 | 180,596 / 129,999 | identical, identical |
| LEVIATHAN | whole | 314 | 39,313 / 3,796 / 2,533 | 29,437,666 / 15,139 / 4,106 | 184,796 / 135,910 | identical, identical |
| TAFENG | `D_init` | 10 | 22,832 / 2,068 / 302 | 1,986,136 / 2,123 / 302 | 487,840 / 53,978 | identical, identical |
| TAFENG | whole | 18 | 23,847 / 1,984 / 317 | 2,469,476 / 2,035 / 317 | 524,249 / 60,693 | identical, identical |
| BIBLE | `D_init` | 364 | 17,313 / 1,298 / 1,140 | 10,238,341 / 2,918 / 1,610 | 42,908 / 33,939 | identical, identical |
| BIBLE | whole | 259 | 18,939 / 1,025 / 871 | 11,691,939 / 2,392 / 1,221 | 44,479 / 34,537 | identical, identical |
| BMS1 | `D_init` | 34 | 22,745 / 652 / 257 | 5,454,171 / 6,978 / 274 | 58,708 / 23,891 | identical, identical |
| C8T1S5I8N5K | `D_init` | 15629 | 464,852 / 31,495 / 26,380 | 31,050,488 / 47,013 / 39,403 | 79,598 / 69,295 | identical, identical |
| C8T1S5I8N5K | whole | 12682 | 383,747 / 26,813 / 21,992 | 32,211,305 / 40,109 / 33,026 | 81,101 / 72,601 | identical, identical |
| KOSARAK | `D_init` | 13 | 33,355 / 916 / 35 | 37,941,354 / 1,936 / 41 | 88,560 / 22,897 | identical, identical |
| KOSARAK | whole | 12 | 84,913 / 961 / 33 | 424,629,084 / 2,045 / 39 | 114,456 / 41,371 | identical, identical |
| FIFA | `D_init` | 212 | 571,028 / 15,099 / 13,917 | 441,827,778 / 122,154 / 48,782 | 405,814 / 380,620 | identical, identical |

## Removal off is the previous miner - `off-equals-previous-miner/`

`verify.MinerCounters` prints the work counters and a digest of the pattern set of one mine.
Built from commit `9b8a922` (before removal) and run with `-Dadapt.iip=off` on the current
code, it prints the same line on all 14 mines of the table above.

## The checks can fail - `fault-injection/`

`python3 analysis/fault_injection.py --item-removal --trials 400 --seed 3` compiles copies of
the code with one fault each and runs the brute-force check with both child-building
strategies. The unmodified copy passes; each of the four faults (removal threshold raised by
30 percent, removals not undone on return from a node, the bound computed with half the
remaining utility, the bound taken from the first occurrence only) is caught.
