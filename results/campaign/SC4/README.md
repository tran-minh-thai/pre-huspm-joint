# SC4, the pre-large floor

A single mine of the whole database at four floors, `r_l` in {0.3, 0.5, 0.7, 0.9}, for
Pre-HUSPM and the proposal side by side at the same `r_l`. SUBSET_3 only: SIGN, LEVIATHAN,
C8T1S5I8N5K. Measured on `PC-THAI`, JDK 25, `-Xmx48g`.

## Five copies of each configuration

Each database holds five arm directories and every one of them contains the whole scenario,
both algorithms at all four floors, eight rows. SC4 did not honour `-Dalgo.only` at the time,
so each arm name ran everything. The directory names therefore say nothing about content:
`IncUSP-Miner+/` holds Pre-HUSPM and Adaptive rows like the others.

They are kept because they are five genuine repeats — five separate JVMs, run one after
another, never concurrently. Pattern counts agree exactly across all five, and runtimes agree
to within about half a percent: 2706 s to 2726 s for the most expensive configuration. That is
also the evidence that one repeat per configuration is enough elsewhere in the campaign.

The gate has since been added, and `campaign.ps1` lists SC4's arms as Pre-HUSPM and
Adaptive-Full on SUBSET_3, so a re-run produces one copy per arm.

## Result

The answer does not depend on the floor: 141 HUSPs on SIGN, 135 on LEVIATHAN and 3438 on
C8T1S5I8N5K at every `r_l` and for both algorithms. What changes is what each has to hold.

| database | `r_l` | Adaptive: pre-large / heap / s | Pre-HUSPM: pre-large / heap / s |
|---|---|---|---|
| SIGN | 0.3 | 38,925 / 160 MB / 3 | 718,145 / 1,446 MB / 12 |
| SIGN | 0.5 | 4,928 / 55 MB / 1 | 143,963 / 552 MB / 4 |
| SIGN | 0.7 | 971 / 34 MB / <1 | 39,275 / 304 MB / 1 |
| SIGN | 0.9 | 126 / 26 MB / <1 | 7,519 / 307 MB / 1 |
| LEVIATHAN | 0.3 | 7,440 / 384 MB / 5 | out of memory at 48 GB |
| LEVIATHAN | 0.5 | 1,417 / 192 MB / 2 | out of memory at 48 GB |
| LEVIATHAN | 0.7 | 361 / 133 MB / 1 | 23,493 / 10,393 MB / 19 |
| LEVIATHAN | 0.9 | 73 / 105 MB / 1 | 5,011 / 3,228 MB / 12 |
| C8T1S5I8N5K | 0.3 | 1,227,036 / 2,871 MB / 2713 | out of memory at 48 GB |
| C8T1S5I8N5K | 0.5 | 1,017,337 / 1,826 MB / 199 | out of memory at 48 GB |
| C8T1S5I8N5K | 0.7 | 20,694 / 1,566 MB / 1 | 597,040 / 1,823 MB / 5 |
| C8T1S5I8N5K | 0.9 | 3,357 / 1,500 MB / <1 | 71,278 / 1,158 MB / 2 |

The two pre-large sets are not the same set measured twice. Pre-HUSPM admits a pattern to its
band by the upper bound `SWU_proj`; the proposal admits it by its utility. The comparison is of
what each algorithm must hold, not of one set held more compactly.

## What it means for SC2

The safety budget of an insertion is proportional to `1 - r_l`, so a lower floor widens it:
0.5 instead of 0.8 makes it 2.5 times larger. SC2 found LEVIATHAN outside its operating region,
with a median `iauMax / SFT_ins` of 2.08 at `r_l = 0.8`; the same increments against a budget
2.5 times wider would sit at about 0.83. This table says the proposal can afford that floor on
LEVIATHAN — 1,417 patterns and 192 MB — where Pre-HUSPM cannot run at it at all.

That is a prediction, not a measurement: SC4 mines once and does not rescan. SC2 on LEVIATHAN
at `r_l = 0.5` is the run that tests it.

KOSARAK is not in SUBSET_3 and is not measured here, so nothing in this table bears on it.
