# SC5, how the stream is cut

The same incremental stream cut four ways - EVEN, INCREASING, DECREASING, BURST - at four
increment counts, `K` in {100, 67, 50, 25}, the total incremental data fixed. SIGN, LEVIATHAN and
C8T1S5I8N5K, `D_init` per `common.Datasets`, each time the mean of the arm's runs. Measured on `PC-THAI` (see `results/machine/PC-THAI.json` and `environment.lock.json`), `-Xmx48g`, one JVM per arm. Written by `analysis/scenario_readmes.py` from the arms' CSVs; rerun it rather than editing numbers here.

## Against each baseline

Geometric mean of the baseline's time over the proposal's, summed over the increments of each
(database, `K`, strategy) cell where both completed; and the cells where the baseline is faster.
The proposal is the fastest arm in 44 of 48 cells.

| baseline | baseline time / proposal time | cells | cells where the baseline is faster |
|---|---:|---:|---:|
| Pre-HUSPM-TSU | 6.32 | 48 | 0 |
| Pre-HUSPM-SWUmax | 5.98 | 48 | 0 |
| IncUSP-Miner+ | 3.71 | 48 | 0 |
| Remine | 1.80 | 48 | 0 |
| Remine-HUSP-ULL | 1.63 | 48 | 0 |
| Remine-HUSP-SP | 1.65 | 48 | 0 |
| Remine-own-UUT | 1.20 | 48 | 4 |

## The finer the cut, the wider the gap

Over the four strategies together: the share of increments on which the proposal rescans, and
its time over remining's.

| database | K | increments that rescan | proposal's time / remine's |
|---|---:|---:|---:|
| SIGN | 25 | 96 % | 0.60 |
| SIGN | 50 | 84 % | 0.57 |
| SIGN | 67 | 73 % | 0.53 |
| SIGN | 100 | 53 % | 0.47 |
| LEVIATHAN | 25 | 99 % | 0.74 |
| LEVIATHAN | 50 | 96 % | 0.71 |
| LEVIATHAN | 67 | 95 % | 0.70 |
| LEVIATHAN | 100 | 87 % | 0.67 |
| C8T1S5I8N5K | 25 | 98 % | 0.43 |
| C8T1S5I8N5K | 50 | 90 % | 0.46 |
| C8T1S5I8N5K | 67 | 82 % | 0.46 |
| C8T1S5I8N5K | 100 | 69 % | 0.46 |

## How the stream is cut, at `K = 100`

| database | strategy | proposal | remine | ratio | rescans |
|---|---|---:|---:|---:|---:|
| SIGN | EVEN | 9.5 s | 17.9 s | 0.53 | 60 |
| SIGN | INCREASING | 8.6 s | 16.5 s | 0.52 | 53 |
| SIGN | DECREASING | 7.8 s | 19.2 s | 0.41 | 53 |
| SIGN | BURST | 7.5 s | 18.0 s | 0.42 | 45 |
| LEVIATHAN | EVEN | 28.0 s | 37.2 s | 0.75 | 100 |
| LEVIATHAN | INCREASING | 22.4 s | 32.5 s | 0.69 | 91 |
| LEVIATHAN | DECREASING | 25.0 s | 41.9 s | 0.60 | 78 |
| LEVIATHAN | BURST | 24.8 s | 37.8 s | 0.66 | 78 |
| C8T1S5I8N5K | EVEN | 8.8 s | 15.8 s | 0.56 | 80 |
| C8T1S5I8N5K | INCREASING | 6.6 s | 14.5 s | 0.45 | 72 |
| C8T1S5I8N5K | DECREASING | 6.8 s | 16.8 s | 0.41 | 68 |
| C8T1S5I8N5K | BURST | 6.3 s | 15.7 s | 0.40 | 55 |
