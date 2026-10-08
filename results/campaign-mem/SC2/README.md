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
| SIGN | 13 | 36 | 37 | 17 | not run |
| LEVIATHAN | 47 | deferred, out of memory on `D_init` | deferred, out of memory on `D_init` | 34 | not run |
| FIFA | 42 | 503 | deferred, not finished within 8 h | 45 | not run |
| BIBLE | 84 | 379 | 1,199 | 53 | not run |
| KOSARAK | 532 | 874 | deferred, not finished within 8 h | 400 | not run |
| C8T1S5I8N5K | 81 | 163 | 161 | 98 | not run |
| TAFENG | 90 | 52 | 58 | 65 | not run |

## Peak live set over the stream

MB.

| database | Pre-HUSPM-Adaptive | Pre-HUSPM-SWUmax | Pre-HUSPM-TSU | Remine | IncUSP-Miner+ |
|---|---:|---:|---:|---:|---:|
| SIGN | 17 | 89 | 95 | 21 | not run |
| LEVIATHAN | 70 | deferred, out of memory on `D_init` | deferred, out of memory on `D_init` | 61 | not run |
| FIFA | 153 | 946 | deferred, not finished within 8 h | 163 | not run |
| BIBLE | 183 | 2,105 | 2,527 | 170 | not run |
| KOSARAK | 1,675 | 2,811 | deferred, not finished within 8 h | 1,539 | not run |
| C8T1S5I8N5K | 189 | 441 | 448 | 213 | not run |
| TAFENG | 96 | 167 | 182 | 69 | not run |
