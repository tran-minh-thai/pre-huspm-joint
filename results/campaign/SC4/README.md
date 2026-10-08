# SC4, the pre-large floor

A single mine of the whole database at four floors, `r_l` in {0.3, 0.5, 0.7, 0.9}, for
Pre-HUSPM and the proposal side by side at the same `r_l`. SUBSET_3 only: SIGN, LEVIATHAN,
C8T1S5I8N5K. Two arms per database, one per algorithm, each holding its four floors.
Measured on `PC-THAI`, JDK 25, `-Xmx48g`, one JVM per arm, one repeat.

## Result

The answer does not depend on the floor: 141 HUSPs on SIGN, 135 on LEVIATHAN and 3438 on
C8T1S5I8N5K at every `r_l` and for both algorithms. What changes is what each has to hold.

| database | `r_l` | Adaptive: pre-large / heap / s | Pre-HUSPM: pre-large / heap / s |
|---|---|---|---|
| SIGN | 0.3 | 38,925 / 62 MB / 3.4 | 718,145 / 1,448 MB / 12.2 |
| SIGN | 0.5 | 4,928 / 44 MB / 1.1 | 143,963 / 444 MB / 4.2 |
| SIGN | 0.7 | 971 / 31 MB / 0.5 | 39,275 / 312 MB / 1.9 |
| SIGN | 0.9 | 126 / 25 MB / 0.3 | 7,519 / 310 MB / 1.1 |
| LEVIATHAN | 0.3 | 7,440 / 129 MB / 5.0 | out of memory at 48 GB |
| LEVIATHAN | 0.5 | 1,417 / 181 MB / 2.4 | out of memory at 48 GB |
| LEVIATHAN | 0.7 | 361 / 91 MB / 1.5 | 23,493 / 8,553 MB / 18.7 |
| LEVIATHAN | 0.9 | 73 / 123 MB / 1.1 | 5,011 / 3,155 MB / 12.1 |
| C8T1S5I8N5K | 0.3 | being re-measured | out of memory at 48 GB |
| C8T1S5I8N5K | 0.5 | being re-measured | out of memory at 48 GB |
| C8T1S5I8N5K | 0.7 | 20,694 / 239 MB / 1.7 | 597,040 / 1,046 MB / 6.2 |
| C8T1S5I8N5K | 0.9 | 3,357 / 252 MB / 0.7 | 71,278 / 842 MB / 2.4 |

Heap is `peak_heap_mb`, used heap sampled during the run; it includes garbage not yet
collected, so it orders the two algorithms but is not a live-set figure
(`docs/result-columns.md`).

Where both complete, Pre-HUSPM's pre-large set is 18 to 69 times the proposal's, and it runs
out of 48 GB at the two lowest floors on LEVIATHAN and C8T1S5I8N5K. The proposal's cells for
C8T1S5I8N5K at `r_l` 0.3 and 0.5 are being re-measured (`results/invariant-result-cap`).

The two pre-large sets are not the same set measured twice. Pre-HUSPM admits a pattern to its
band by the upper bound `SWU_proj`; the proposal admits it by its utility. The comparison is of
what each algorithm must hold, not of one set held more compactly.

## What it means for SC2

The safety budget of an insertion is proportional to `1 - r_l`, so a lower floor widens it:
0.5 instead of 0.8 makes it 2.5 times larger. On LEVIATHAN, where SC2 runs outside the operating
region at `r_l = 0.8`, this table shows the proposal can hold the lower floor there - 1,417
patterns - where Pre-HUSPM cannot run at it at all.

KOSARAK is not in SUBSET_3 and is not measured here, so nothing in this table bears on it.
