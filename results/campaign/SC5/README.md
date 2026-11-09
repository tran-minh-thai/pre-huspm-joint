# SC5, how the stream is cut

The same incremental stream cut four ways - EVEN, INCREASING, DECREASING, BURST - at four
increment counts, `K` in {100, 67, 50, 25}, the total incremental data fixed. SIGN, LEVIATHAN and
C8T1S5I8N5K, `D_init` per `common.Datasets`, each time the mean of the arm's runs. Measured on `PC-THAI` (see `results/machine/PC-THAI.json` and `environment.lock.json`), `-Xmx48g`, one JVM per arm. Written by `analysis/scenario_readmes.py` from the arms' CSVs; rerun it rather than editing numbers here.

## Against each baseline

Geometric mean of the baseline's time over the proposal's, summed over the increments of each
(database, `K`, strategy) cell where both completed; and the cells where the baseline is faster.
The proposal is the fastest arm in 48 of 48 cells.

| baseline | baseline time / proposal time | cells | cells where the baseline is faster |
|---|---:|---:|---:|
| Pre-HUSPM-TSU | 6.70 | 48 | 0 |
| Pre-HUSPM-SWUmax | 6.34 | 48 | 0 |
| IncUSP-Miner+ | 3.93 | 48 | 0 |
| Remine | 1.76 | 48 | 0 |
| Remine-HUSP-ULL | 1.73 | 48 | 0 |
| Remine-HUSP-SP | 1.75 | 48 | 0 |
| Remine-own-UUT | 1.11 | 48 | 0 |

## The finer the cut, the wider the gap

Over the four strategies together: the share of increments on which the proposal rescans, and
its time over remining's.

| database | K | increments that rescan | proposal's time / remine's |
|---|---:|---:|---:|
| SIGN | 25 | 99 % | 0.55 |
| SIGN | 50 | 99 % | 0.55 |
| SIGN | 67 | 99 % | 0.55 |
| SIGN | 100 | 94 % | 0.52 |
| LEVIATHAN | 25 | 100 % | 0.72 |
| LEVIATHAN | 50 | 98 % | 0.72 |
| LEVIATHAN | 67 | 97 % | 0.70 |
| LEVIATHAN | 100 | 91 % | 0.67 |
| C8T1S5I8N5K | 25 | 99 % | 0.49 |
| C8T1S5I8N5K | 50 | 98 % | 0.48 |
| C8T1S5I8N5K | 67 | 97 % | 0.48 |
| C8T1S5I8N5K | 100 | 95 % | 0.47 |

## How the stream is cut, at `K = 100`

| database | strategy | proposal | remine | ratio | rescans |
|---|---|---:|---:|---:|---:|
| SIGN | EVEN | 9.9 s | 17.9 s | 0.55 | 100 |
| SIGN | INCREASING | 8.7 s | 16.6 s | 0.52 | 90 |
| SIGN | DECREASING | 9.1 s | 19.2 s | 0.47 | 86 |
| SIGN | BURST | 9.9 s | 18.0 s | 0.55 | 100 |
| LEVIATHAN | EVEN | 26.5 s | 36.7 s | 0.72 | 100 |
| LEVIATHAN | INCREASING | 22.1 s | 32.0 s | 0.69 | 91 |
| LEVIATHAN | DECREASING | 24.4 s | 41.3 s | 0.59 | 82 |
| LEVIATHAN | BURST | 25.8 s | 37.3 s | 0.69 | 91 |
| C8T1S5I8N5K | EVEN | 6.1 s | 12.6 s | 0.48 | 100 |
| C8T1S5I8N5K | INCREASING | 5.3 s | 11.5 s | 0.46 | 84 |
| C8T1S5I8N5K | DECREASING | 6.1 s | 13.3 s | 0.46 | 96 |
| C8T1S5I8N5K | BURST | 6.0 s | 12.5 s | 0.48 | 100 |
