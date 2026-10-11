# SC2 memory: the state an incremental method holds over 100 increments

The memory campaign's SC2: the same streams as SC2, measured in live mode (a full collection is
forced at every sample and the heap read right after), so the runtimes of these rows are void.
`mem_retained_mb` is what the algorithm holds after an increment, against a baseline taken before
the algorithm exists; `mem_live_mb` the peak during it. Measured on `PC-THAI` (see `results/machine/PC-THAI.json` and `environment.lock.json`), `-Xmx48g`, one JVM per arm. Written by `analysis/scenario_readmes.py` from the arms' CSVs; rerun it rather than editing numbers here.

A single cell's live set can move between two measurements (`results/jdk-comparison`), so these
tables support comparisons of orders of magnitude, not of a few percent.

## State held after the last increment

MB.

| database | Pre-HUSPM-Joint | Pre-HUSPM-SWUmax | Pre-HUSPM-TSU | Remine | IncUSP-Miner+ |
|---|---:|---:|---:|---:|---:|
| SIGN | 13 | 32 | 32 | 17 | 861 |
| LEVIATHAN | 21 | 79 | 79 | 30 | 1,238 |
| FIFA | 34 | 283 | 269 | 42 | 44,081 at 25, then out of memory |
| BIBLE | 36 | 202 | 198 | 50 | 1,451 |
| KOSARAK | 349 | 2,848 | 2,857 | 353 | 4,957 |
| C8T1S5I8N5K | 58 | 84 | 85 | 79 | 1,587 |
| TAFENG | 55 | 74 | 85 | 57 | 87 |

## Peak live set over the stream

MB.

| database | Pre-HUSPM-Joint | Pre-HUSPM-SWUmax | Pre-HUSPM-TSU | Remine | IncUSP-Miner+ |
|---|---:|---:|---:|---:|---:|
| SIGN | 16 | 63 | 57 | 20 | 865 |
| LEVIATHAN | 35 | 130 | 130 | 44 | 1,267 |
| FIFA | 112 | 510 | 509 | 127 | 44,104 before running out of memory |
| BIBLE | 99 | 390 | 412 | 98 | 1,659 |
| KOSARAK | 1,070 | 4,753 | 4,837 | 975 | 6,791 |
| C8T1S5I8N5K | 58 | 155 | 152 | 80 | 1,590 |
| TAFENG | 56 | 78 | 87 | 61 | 118 |
