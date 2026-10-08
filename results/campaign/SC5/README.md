# SC5, how the stream is cut

The same incremental stream cut four ways - EVEN, INCREASING, DECREASING, BURST - at four
increment counts, `K` in {100, 67, 50, 25}, the total incremental data fixed. SIGN, LEVIATHAN and
C8T1S5I8N5K, `D_init` per `common.Datasets`, one run per arm. Measured on `PC-THAI` (see `results/machine/PC-THAI.json` and `environment.lock.json`), `-Xmx48g`, one JVM per arm. Written by `analysis/scenario_readmes.py` from the arms' CSVs; rerun it rather than editing numbers here.

## Against each baseline

Geometric mean of the baseline's time over the proposal's, summed over the increments of each
(database, `K`, strategy) cell where both completed; and the cells where the baseline is faster.
The proposal is the fastest arm in 46 of 48 cells.

| baseline | baseline time / proposal time | cells | cells where the baseline is faster |
|---|---:|---:|---:|
| Pre-HUSPM-TSU | 3.88 | 32 | 0 |
| Pre-HUSPM-SWUmax | 2.98 | 32 | 0 |
| IncUSP-Miner+ | nan | 0 | 0 |
| Remine | 1.30 | 48 | 2 |

## The finer the cut, the wider the gap

Over the four strategies together: the share of increments on which the proposal rescans, and
its time over remining's.

| database | K | increments that rescan | proposal's time / remine's |
|---|---:|---:|---:|
| SIGN | 25 | 96 % | 0.97 |
| SIGN | 50 | 82 % | 0.83 |
| SIGN | 67 | 72 % | 0.72 |
| SIGN | 100 | 52 % | 0.52 |
| LEVIATHAN | 25 | 99 % | 0.93 |
| LEVIATHAN | 50 | 96 % | 0.90 |
| LEVIATHAN | 67 | 94 % | 0.88 |
| LEVIATHAN | 100 | 85 % | 0.78 |
| C8T1S5I8N5K | 25 | 97 % | 0.84 |
| C8T1S5I8N5K | 50 | 88 % | 0.77 |
| C8T1S5I8N5K | 67 | 79 % | 0.70 |
| C8T1S5I8N5K | 100 | 66 % | 0.58 |

## How the stream is cut, at `K = 100`

| database | strategy | proposal | remine | ratio | rescans |
|---|---|---:|---:|---:|---:|
| SIGN | EVEN | 17.5 s | 29.7 s | 0.59 | 59 |
| SIGN | INCREASING | 15.4 s | 28.5 s | 0.54 | 53 |
| SIGN | DECREASING | 15.5 s | 30.8 s | 0.51 | 52 |
| SIGN | BURST | 13.4 s | 29.7 s | 0.45 | 45 |
| LEVIATHAN | EVEN | 87.6 s | 95.6 s | 0.92 | 98 |
| LEVIATHAN | INCREASING | 73.8 s | 83.8 s | 0.88 | 91 |
| LEVIATHAN | DECREASING | 72.4 s | 108.3 s | 0.67 | 76 |
| LEVIATHAN | BURST | 66.9 s | 97.6 s | 0.69 | 76 |
| C8T1S5I8N5K | EVEN | 55.9 s | 81.6 s | 0.68 | 77 |
| C8T1S5I8N5K | INCREASING | 47.7 s | 75.4 s | 0.63 | 69 |
| C8T1S5I8N5K | DECREASING | 48.5 s | 88.7 s | 0.55 | 65 |
| C8T1S5I8N5K | BURST | 39.3 s | 81.7 s | 0.48 | 54 |
