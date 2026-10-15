# SC5, how the stream is cut

The same incremental stream cut four ways - EVEN, INCREASING, DECREASING, BURST - at four
increment counts, `K` in {100, 67, 50, 25}, the total incremental data fixed. SIGN, LEVIATHAN and
C8T1S5I8N5K, `D_init` per `common.Datasets`, one run per arm. Measured on `PC-THAI` (see `results/machine/PC-THAI.json` and `environment.lock.json`), `-Xmx48g`, one JVM per arm. Written by `analysis/scenario_readmes.py` from the arms' CSVs; rerun it rather than editing numbers here.

## Against each baseline

Geometric mean of the baseline's time over the proposal's, summed over the increments of each
(database, `K`, strategy) cell where both completed; and the cells where the baseline is faster.
The proposal is the fastest arm in 48 of 48 cells.

| baseline | baseline time / proposal time | cells | cells where the baseline is faster |
|---|---:|---:|---:|
| Pre-HUSPM-TSU | 6.35 | 48 | 0 |
| Pre-HUSPM-SWUmax | 6.01 | 48 | 0 |
| IncUSP-Miner+ | 3.72 | 48 | 0 |
| Remine | 1.80 | 48 | 0 |

## The finer the cut, the wider the gap

Over the four strategies together: the share of increments on which the proposal rescans, and
its time over remining's.

| database | K | increments that rescan | proposal's time / remine's |
|---|---:|---:|---:|
| SIGN | 25 | 96 % | 0.59 |
| SIGN | 50 | 84 % | 0.57 |
| SIGN | 67 | 73 % | 0.52 |
| SIGN | 100 | 53 % | 0.46 |
| LEVIATHAN | 25 | 99 % | 0.73 |
| LEVIATHAN | 50 | 96 % | 0.70 |
| LEVIATHAN | 67 | 95 % | 0.70 |
| LEVIATHAN | 100 | 87 % | 0.67 |
| C8T1S5I8N5K | 25 | 98 % | 0.44 |
| C8T1S5I8N5K | 50 | 90 % | 0.47 |
| C8T1S5I8N5K | 67 | 82 % | 0.47 |
| C8T1S5I8N5K | 100 | 69 % | 0.46 |

## How the stream is cut, at `K = 100`

| database | strategy | proposal | remine | ratio | rescans |
|---|---|---:|---:|---:|---:|
| SIGN | EVEN | 9.4 s | 17.9 s | 0.53 | 60 |
| SIGN | INCREASING | 8.4 s | 16.5 s | 0.51 | 53 |
| SIGN | DECREASING | 7.7 s | 19.2 s | 0.40 | 53 |
| SIGN | BURST | 7.4 s | 18.0 s | 0.41 | 45 |
| LEVIATHAN | EVEN | 28.0 s | 37.2 s | 0.75 | 100 |
| LEVIATHAN | INCREASING | 22.4 s | 32.5 s | 0.69 | 91 |
| LEVIATHAN | DECREASING | 24.9 s | 41.9 s | 0.59 | 78 |
| LEVIATHAN | BURST | 24.7 s | 37.8 s | 0.65 | 78 |
| C8T1S5I8N5K | EVEN | 8.9 s | 15.7 s | 0.57 | 80 |
| C8T1S5I8N5K | INCREASING | 6.6 s | 14.4 s | 0.46 | 72 |
| C8T1S5I8N5K | DECREASING | 6.9 s | 16.6 s | 0.41 | 68 |
| C8T1S5I8N5K | BURST | 6.4 s | 15.6 s | 0.41 | 55 |
