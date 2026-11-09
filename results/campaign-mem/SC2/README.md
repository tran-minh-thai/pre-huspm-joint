# SC2 memory: the state an incremental method holds over 100 increments

The memory campaign's SC2: the same streams as SC2, measured in live mode (a full collection is
forced at every sample and the heap read right after), so the runtimes of these rows are void.
`mem_retained_mb` is what the algorithm holds after an increment, against a baseline taken before
the algorithm exists; `mem_live_mb` the peak during it. Measured on `PC-THAI` (see `results/machine/PC-THAI.json` and `environment.lock.json`), `-Xmx48g`, one JVM per arm. Written by `analysis/scenario_readmes.py` from the arms' CSVs; rerun it rather than editing numbers here.

A single cell's live set can move between two measurements (`results/campaign-mem-replicates`), so these
tables support comparisons of orders of magnitude, not of a few percent.

## State held after the last increment

MB.

| database | Pre-HUSPM-Joint | Pre-HUSPM-SWUmax | Pre-HUSPM-TSU | Remine | IncUSP-Miner+ |
|---|---:|---:|---:|---:|---:|
| SIGN | 13 | 32 | 32 | 17 | 861 |
| LEVIATHAN | 21 | 79 | 79 | 29 | 1,238 |
| FIFA | 35 | 283 | 269 | 41 | 44,081 at 25, then stopped by the memory guard |
| BIBLE | 38 | 202 | 198 | 45 | 1,451 |
| KOSARAK | 358 | 2,848 | 2,857 | 297 | 4,957 |
| C8T1S5I8N5K | 56 | 84 | 85 | 73 | 1,587 |
| TAFENG | 50 | 74 | 85 | 53 | 87 |
| DUNNHUMBY | 169 | 456 | 457 | 125 | 10,643 |

## Peak live set over the stream

MB.

| database | Pre-HUSPM-Joint | Pre-HUSPM-SWUmax | Pre-HUSPM-TSU | Remine | IncUSP-Miner+ |
|---|---:|---:|---:|---:|---:|
| SIGN | 15 | 63 | 57 | 20 | 865 |
| LEVIATHAN | 32 | 130 | 130 | 49 | 1,267 |
| FIFA | 105 | 510 | 509 | 126 | 44,104, then stopped by the memory guard |
| BIBLE | 84 | 390 | 412 | 99 | 1,659 |
| KOSARAK | 1,013 | 4,753 | 4,837 | 1,001 | 6,791 |
| C8T1S5I8N5K | 56 | 155 | 152 | 73 | 1,590 |
| TAFENG | 59 | 78 | 87 | 57 | 118 |
| DUNNHUMBY | 383 | 11,999 | 11,998 | 365 | 10,940 |
