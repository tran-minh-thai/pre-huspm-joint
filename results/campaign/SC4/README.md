# SC4, the pre-large floor

One mine of the whole database at four floors, `r_l` in {0.3, 0.5, 0.7, 0.9}, for Pre-HUSPM and the proposal at the same `r_l`, on SIGN, LEVIATHAN and C8T1S5I8N5K. Measured on `PC-THAI` (see `results/machine/PC-THAI.json` and `environment.lock.json`), `-Xmx48g`, one JVM per arm. Written by `analysis/scenario_readmes.py` from the arms' CSVs; rerun it rather than editing numbers here.

## Result

The answer does not depend on the floor: every completed run reports the same HUSP count per database (141 on SIGN, 135 on LEVIATHAN, 3438 on C8T1S5I8N5K).

| database | `r_l` | Pre-HUSPM-Joint: pre-large / heap / s | Pre-HUSPM: pre-large / heap / s |
|---|---|---|---|
| SIGN | 0.3 | 38,925 / 50 MB / 2.1 | 668,155 / 1,109 MB / 11.2 |
| SIGN | 0.5 | 4,928 / 32 MB / 0.7 | 128,560 / 464 MB / 3.7 |
| SIGN | 0.7 | 971 / 29 MB / 0.3 | 33,681 / 302 MB / 1.6 |
| SIGN | 0.9 | 126 / 43 MB / 0.4 | 6,171 / 208 MB / 1.0 |
| LEVIATHAN | 0.3 | 7,440 / 113 MB / 2.0 | 208,749 / 1,007 MB / 9.0 |
| LEVIATHAN | 0.5 | 1,417 / 79 MB / 1.0 | 48,314 / 563 MB / 3.9 |
| LEVIATHAN | 0.7 | 361 / 66 MB / 0.7 | 14,443 / 339 MB / 2.2 |
| LEVIATHAN | 0.9 | 73 / 65 MB / 0.7 | 2,877 / 387 MB / 1.5 |
| C8T1S5I8N5K | 0.3 | out of memory | out of memory |
| C8T1S5I8N5K | 0.5 | 31,767,337 / 9,252 MB / 46.8 | out of memory |
| C8T1S5I8N5K | 0.7 | 20,694 / 160 MB / 1.0 | 275,156 / 639 MB / 1.9 |
| C8T1S5I8N5K | 0.9 | 3,357 / 156 MB / 0.6 | 11,473 / 170 MB / 0.5 |

Heap is `peak_heap_mb`, used heap including garbage not yet collected; it orders the two
algorithms but is not a live-set figure (`docs/result-columns.md`).

Where both complete, Pre-HUSPM's pre-large set is 3 to 49 times the proposal's, over 10 cells. The two sets are not one set measured
twice: Pre-HUSPM admits a pattern by an upper bound on its utility, the proposal by the utility itself.
