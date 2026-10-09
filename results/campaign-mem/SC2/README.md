# SC2 memory: the state an incremental method holds over 100 increments

The memory campaign's SC2: the same streams as SC2, measured in live mode (a full collection is
forced at every sample and the heap read right after), so the runtimes of these rows are void.
`mem_retained_mb` is what the algorithm holds after an increment, against a baseline taken before
the algorithm exists; `mem_live_mb` the peak during it. Measured on `PC-THAI` (see `results/machine/PC-THAI.json` and `environment.lock.json`), `-Xmx48g`, one JVM per arm. Written by `analysis/scenario_readmes.py` from the arms' CSVs; rerun it rather than editing numbers here.

A single cell's live set can move between two measurements (`results/jdk-comparison`), so these
tables support comparisons of orders of magnitude, not of a few percent.

## State held after the last increment

MB.

| database | Pre-HUSPM-Adaptive | Pre-HUSPM-SWUmax | Pre-HUSPM-TSU | Remine | IncUSP-Miner+ |
|---|---:|---:|---:|---:|---:|
| SIGN | 13 | not run | not run | 17 | not run |
| LEVIATHAN | 47 | not run | not run | 34 | not run |
| FIFA | 42 | not run | not run | 45 | not run |
| BIBLE | 84 | not run | not run | 53 | not run |
| KOSARAK | 532 | not run | not run | 400 | not run |
| C8T1S5I8N5K | 81 | not run | not run | 98 | not run |
| TAFENG | 90 | not run | not run | 65 | not run |

## Peak live set over the stream

MB.

| database | Pre-HUSPM-Adaptive | Pre-HUSPM-SWUmax | Pre-HUSPM-TSU | Remine | IncUSP-Miner+ |
|---|---:|---:|---:|---:|---:|
| SIGN | 17 | not run | not run | 21 | not run |
| LEVIATHAN | 70 | not run | not run | 61 | not run |
| FIFA | 153 | not run | not run | 163 | not run |
| BIBLE | 183 | not run | not run | 170 | not run |
| KOSARAK | 1,675 | not run | not run | 1,539 | not run |
| C8T1S5I8N5K | 189 | not run | not run | 213 | not run |
| TAFENG | 96 | not run | not run | 69 | not run |
