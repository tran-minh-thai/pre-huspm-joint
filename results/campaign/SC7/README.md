# SC7, what each component contributes

The IUB bound and the irrelevant item pruning (IPS) act inside the miner, so each is ablated on
one mine of the whole database, with Pre-HUSPM beside them. The block index and the adaptive rescan decision act only while the
database grows, so they are ablated on `D_init` and 100 increments, one row per increment, on
SIGN and C8T1S5I8N5K. Measured on `PC-THAI` (see `results/machine/PC-THAI.json` and `environment.lock.json`), `-Xmx48g`, one JVM per arm. Written by `analysis/scenario_readmes.py` from the arms' CSVs; rerun it rather than editing numbers here.

Switching a component off changes no answer: the HUSP count of every increment is the same with
the adaptive decision or the block index off as with both on (yes).

## The IUB bound and the irrelevant item pruning

Every finished arm of a database reports the same HUSP count: yes, over
21 comparisons.

| database | HUSPs | full | IUB off | IPS off | Pre-HUSPM | explored, full | explored, IUB off | explored, IPS off |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| SIGN | 141 | 0.56 s | 0.44 s | 0.73 s | 1.30 s | 8,071 | 8,071 | 38,372 |
| LEVIATHAN | 135 | 0.73 s | 0.80 s | 1.64 s | 1.74 s | 3,796 | 3,796 | 39,313 |
| FIFA | 17 | 9.66 s | 9.46 s | 102.19 s | 267.47 s | 13,738 | 13,738 | 473,004 |
| BIBLE | 129 | 1.38 s | 1.64 s | 2.20 s | 1.90 s | 1,025 | 1,025 | 18,939 |
| KOSARAK | 9 | 12.70 s | 13.57 s | 111.90 s | 15.06 s | 1,077 | 1,077 | 124,081 |
| C8T1S5I8N5K | 3,438 | 0.85 s | 0.54 s | 1.99 s | 0.94 s | 26,813 | 26,813 | 383,747 |
| TAFENG | 9 | 0.43 s | 0.50 s | 0.56 s | 0.25 s | 1,984 | 1,984 | 23,847 |

## The adaptive rescan decision

| database | adaptive decision | rescan at every increment | time saved, factor |
|---|---:|---:|---:|
| SIGN | 9.8 s, 59 rescans | 16.7 s, 100 rescans | 1.71 |
| C8T1S5I8N5K | 9.0 s, 77 rescans | 11.3 s, 100 rescans | 1.26 |

## The block index

Over the increments that do not rescan, the only ones on which the index is read.

| database | increments without a rescan | with the index | without it | sequences read with | sequences read without |
|---|---:|---:|---:|---:|---:|
| SIGN | 41 | 47 ms | 47 ms | 147,405 | 183,010 |
| C8T1S5I8N5K | 23 | 258 ms | 750 ms | 6,822,900 | 64,221,200 |

## Block size

The same stream with blocks of n sequences. A block never spans two increments, so the effective
block size is the smaller of n and the increment size (5 sequences on SIGN, 199 or 200 on
C8T1S5I8N5K). The expectations were stated in
`docs/experiments.md` before the measurement. Sequences read do not decrease from one block size
to the next where the smaller divides the larger: 10 of
10 such pairs.

| database | block size | sequences read | direct updates | whole stream | counts equal to the default | same increments without a rescan |
|---|---:|---:|---:|---:|---:|---:|
| SIGN | 1 | 45,596 | 47 ms | 10.32 s | 101/101 | yes |
| SIGN | 5 | 147,405 | 51 ms | 10.01 s | 101/101 | yes |
| SIGN | 10 | 147,405 | 52 ms | 10.04 s | 101/101 | yes |
| SIGN | 50 | 147,405 | 45 ms | 10.00 s | 101/101 | yes |
| SIGN | 100 (default) | 147,405 | 47 ms | 9.96 s | 101/101 | yes |
| SIGN | 200 | 147,405 | 48 ms | 9.93 s | 101/101 | yes |
| C8T1S5I8N5K | 1 | 76,037 | 346 ms | 9.61 s | 101/101 | yes |
| C8T1S5I8N5K | 5 | 380,140 | 212 ms | 9.45 s | 101/101 | yes |
| C8T1S5I8N5K | 10 | 760,170 | 223 ms | 9.64 s | 101/101 | yes |
| C8T1S5I8N5K | 50 | 3,799,150 | 224 ms | 9.33 s | 101/101 | yes |
| C8T1S5I8N5K | 100 (default) | 6,822,900 | 258 ms | 9.26 s | 101/101 | yes |
| C8T1S5I8N5K | 200 | 12,407,800 | 305 ms | 9.50 s | 101/101 | yes |
