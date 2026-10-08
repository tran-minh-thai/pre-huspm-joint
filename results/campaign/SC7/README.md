# SC7, what each component contributes

The IUB bound acts inside the miner, so it is ablated on one mine of the whole database, with
Pre-HUSPM beside it. The block index and the adaptive rescan decision act only while the
database grows, so they are ablated on `D_init` and 100 increments, one row per increment, on
SIGN and C8T1S5I8N5K. Measured on `PC-THAI` (see `results/machine/PC-THAI.json` and `environment.lock.json`), `-Xmx48g`, one JVM per arm. Written by `analysis/scenario_readmes.py` from the arms' CSVs; rerun it rather than editing numbers here.

Switching a component off changes no answer: the HUSP count of every increment is the same with
the adaptive decision or the block index off as with both on (yes).

## The IUB bound

| database | HUSPs | full | IUB off | Pre-HUSPM | explored, full | explored, IUB off | IUB cuts |
|---|---:|---:|---:|---:|---:|---:|---:|
| SIGN | 141 | 0.68 s | 0.52 s | 1.57 s | 38,372 | 38,372 | 152 |
| LEVIATHAN | 135 | 1.82 s | 1.87 s | 15.45 s | 39,313 | 39,313 | 8,830 |
| FIFA | 17 | 101.44 s | 99.04 s | 630.45 s | 473,004 | 473,004 | 2,889 |
| BIBLE | 129 | 2.33 s | 2.26 s | 31.76 s | 18,939 | 18,939 | 13,805 |
| KOSARAK | 9 | 81.30 s | 80.75 s | 391.22 s | 84,913 | 84,913 | 40,948 |
| C8T1S5I8N5K | 3438 | 1.96 s | 2.16 s | 3.97 s | 383,747 | 383,747 | 66,925 |
| TAFENG | 9 | 0.48 s | 0.54 s | 0.38 s | 23,847 | 23,847 | 22,911 |

## The adaptive rescan decision

| database | adaptive decision | rescan at every increment | time saved, factor |
|---|---:|---:|---:|
| SIGN | 17.4 s, 59 rescans | 29.4 s, 100 rescans | 1.70 |
| C8T1S5I8N5K | 58.9 s, 77 rescans | 73.5 s, 100 rescans | 1.25 |

## The block index

Over the increments that do not rescan, the only ones on which the index is read.

| database | increments without a rescan | with the index | without it | block reads with | block reads without |
|---|---:|---:|---:|---:|---:|
| SIGN | 41 | 51 ms | 50 ms | 147,405 | 183,010 |
| C8T1S5I8N5K | 23 | 266 ms | 764 ms | 6,822,900 | 64,221,200 |
