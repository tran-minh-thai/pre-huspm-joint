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
| Pre-HUSPM-TSU | 4.92 | 48 | 0 |
| Pre-HUSPM-SWUmax | 4.65 | 48 | 0 |
| IncUSP-Miner+ | 2.88 | 48 | 0 |
| Remine | 1.40 | 48 | 0 |

## The finer the cut, the wider the gap

Over the four strategies together: the share of increments on which the proposal rescans, and
its time over remining's.

| database | K | increments that rescan | proposal's time / remine's |
|---|---:|---:|---:|
| SIGN | 25 | 96 % | 0.88 |
| SIGN | 50 | 82 % | 0.75 |
| SIGN | 67 | 72 % | 0.65 |
| SIGN | 100 | 52 % | 0.47 |
| LEVIATHAN | 25 | 99 % | 0.97 |
| LEVIATHAN | 50 | 96 % | 0.93 |
| LEVIATHAN | 67 | 94 % | 0.92 |
| LEVIATHAN | 100 | 85 % | 0.81 |
| C8T1S5I8N5K | 25 | 97 % | 0.71 |
| C8T1S5I8N5K | 50 | 88 % | 0.65 |
| C8T1S5I8N5K | 67 | 79 % | 0.59 |
| C8T1S5I8N5K | 100 | 66 % | 0.50 |

## How the stream is cut, at `K = 100`

| database | strategy | proposal | remine | ratio | rescans |
|---|---|---:|---:|---:|---:|
| SIGN | EVEN | 9.7 s | 18.3 s | 0.53 | 59 |
| SIGN | INCREASING | 8.5 s | 16.8 s | 0.50 | 53 |
| SIGN | DECREASING | 8.7 s | 19.6 s | 0.44 | 52 |
| SIGN | BURST | 7.5 s | 18.4 s | 0.41 | 45 |
| LEVIATHAN | EVEN | 35.0 s | 36.7 s | 0.95 | 98 |
| LEVIATHAN | INCREASING | 29.4 s | 32.0 s | 0.92 | 91 |
| LEVIATHAN | DECREASING | 28.7 s | 41.4 s | 0.69 | 76 |
| LEVIATHAN | BURST | 26.6 s | 37.3 s | 0.71 | 76 |
| C8T1S5I8N5K | EVEN | 9.0 s | 15.7 s | 0.57 | 77 |
| C8T1S5I8N5K | INCREASING | 7.8 s | 14.4 s | 0.54 | 69 |
| C8T1S5I8N5K | DECREASING | 7.8 s | 16.6 s | 0.47 | 65 |
| C8T1S5I8N5K | BURST | 6.4 s | 15.6 s | 0.41 | 54 |
