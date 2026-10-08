# SC1, static mining on `D_init`

> IncUSP-Miner+ is being re-measured after its reimplementation (commit 0cad934, checked in
> `results/invariant-incusp-correctness`). Its columns below come from the previous
> implementation and are replaced when the re-measurement is in.

Eight databases, six algorithms, four thresholds each: 192 runs. `D_init` and the thresholds
come from `common.Datasets`. Measured on `PC-THAI`, JDK 25, `-Xmx48g`, one JVM per arm, one
repeat, the four thresholds run lowest first in the same JVM for every algorithm alike.

## Every algorithm returns the same answer

For each of the 32 (database, threshold) pairs, every algorithm that completed reports the same
HUSP count. Counts alone do not prove the sets are equal; that is what `verify.BaselineAgreement`
establishes for the static baselines and `verify.CorrectnessVerifier` for the proposal
(`results/verify`).

Two arms did not complete everywhere:

| database | algorithm | outcome |
|---|---|---|
| FIFA | IncUSP-Miner+ | out of memory at 48 GB at the two lowest thresholds |
| LEVIATHAN | Pre-HUSPM | out of memory at 48 GB at three of the four |

## Runtime at the default threshold

Seconds.

| database | HUSPs | USpan | HUSP-ULL | HUSP-SP | IncUSP-Miner+ | Pre-HUSPM | Adaptive |
|---|---:|---:|---:|---:|---:|---:|---:|
| SIGN | 583 | 2.7 | 1.1 | 1.5 | 1.7 | 0.8 | **0.2** |
| LEVIATHAN | 107 | 7.1 | 18.4 | 3.3 | 20.7 | OOM | **0.6** |
| TAFENG | 6 | 19.0 | 14.2 | 1.6 | 0.7 | 0.2 | **0.2** |
| BIBLE | 192 | 13.9 | 93.5 | 5.1 | 96.2 | 31.0 | **1.4** |
| BMS1 | 21 | 1.1 | 0.4 | 0.3 | 0.3 | 0.5 | **0.1** |
| C8T1S5I8N5K | 5067 | 16.3 | 11.1 | 2.9 | 2.9 | 2.7 | **0.8** |
| KOSARAK | 10 | 46.0 | 17.3 | 10.6 | 11.4 | 5.6 | **1.1** |
| FIFA | 18 | 658.2 | 313.6 | 364.3 | OOM | 251.6 | **53.9** |

## Across all 32 cells

The proposal is the fastest in 29 of 32. Against the fastest baseline in each cell it is
3.57 times faster at the median, 5.56 at best, and 0.81 at worst.

Geometric mean speedup over each baseline, on the cells where both completed:

| baseline | speedup | cells |
|---|---:|---:|
| USpan | 16.9 | 32 |
| HUSP-ULL | 14.6 | 32 |
| IncUSP-Miner+ | 8.7 | 30 |
| HUSP-SP | 5.0 | 32 |
| Pre-HUSPM | 4.0 | 29 |

HUSP-SP is the strongest of the static miners, as its authors report, and the margin over it is
the narrowest among them.

The three cells it loses are all TAFENG against Pre-HUSPM: at the lowest threshold, 0.002812,
by 370 ms to 301 ms; at 0.004499 by 172 ms to 169 ms and at 0.005691 by 150 ms to 145 ms, both
inside the noise of a run that short. TAFENG yields ten patterns or fewer at every level, so
there is little for the proposal's pruning to remove.

## Memory is not measured here

`peak_heap_mb` is used heap sampled every 50 ms. It counts garbage the collector has not reached
yet, so it grows with `-Xmx` and depends on what ran earlier in the same JVM
(`docs/result-columns.md`). It is recorded, but it is not the memory result. The live-set
figures come from the memory campaign, run separately with `-MemMode live`.

For that reason no memory claim is drawn from this file; `results/campaign-mem/SC1` holds the
live-set figures.
