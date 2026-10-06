# Probe: SC2 at a lower pre-large floor

SC2 put LEVIATHAN outside its operating region at `r_l = 0.8`: 98 of 100 increments rescan,
and the proposal runs 3 percent slower than remining. The insertion budget is proportional
to `1 - r_l`, so this probe re-runs the same arm at `r_l = 0.5`, a budget 2.5 times wider,
to see whether the region can be entered and whether entering it pays.

    java -Xmx48g -Xss64m "-Dsl.ratio=0.5" "-Ddataset.only=LEVIATHAN" "-Dalgo.only=Adaptive-Full" `
         "-Drepeats=1" "-Dresults.dir=results/probe-rl/LEVIATHAN-rl0.50/Adaptive-Full/" `
         -cp build AutomatedExperimentRunner SC2

`PC-THAI`, JDK 25, `-Xmx48g`, one repeat. Every row records `sl_min_eff = 0.5`. The reference
is `results/campaign/SC2/LEVIATHAN/Adaptive-Full`, identical but for the floor.

## Result

| | `r_l = 0.8` | `r_l = 0.5` |
|---|---:|---:|
| median `iauMax / SFT_ins` | 2.08 | 1.14 |
| increments within budget | 2 / 100 | 37 / 100 |
| rescans | 98 | 63 |
| runtime over 100 increments | 92.9 s | 97.9 s |
| mean pre-large set | 170 | 1,298 |
| peak heap | 103 MB | 156 MB |

The answer is unchanged: the HUSP count agrees on all 101 rows. The floor changes the cost of
reaching the answer, not the answer.

## Where the time goes

| | `r_l = 0.8` | `r_l = 0.5` |
|---|---|---|
| increments without a rescan | 2 at 3 ms each | 37 at 4 ms each |
| increments with a rescan | 98 at 950 ms each | 63 at 1,550 ms each |

An increment that does not rescan costs a few milliseconds at either floor, so the entire
runtime is rescans. The lower floor cuts their number by 36 percent and raises the price of
each by 64 percent, because the rescan mines down to a lower threshold and builds a pre-large
set 7.7 times larger. The two effects cancel and a little more: 0.64 x 1.64 is 1.05.

Total cost is the number of rescans times the cost of one, and `r_l` moves those two factors
in opposite directions. On LEVIATHAN they balance or worse, so no floor makes the proposal
faster than remining. The lever is not the floor but the price of a rescan.

## Two things this corrects

The widened budget does not scale the ratio by 2.5. `iauMax` accumulates from the last rescan,
so fewer rescans mean longer accumulation windows and a larger `iauMax` at each check; the
median fell by a factor of 1.8.

Holding a lower floor cheaply is necessary for entering the region and not sufficient for
gaining from it. The proposal holds `r_l = 0.5` on LEVIATHAN in 156 MB, where Pre-HUSPM
exhausts 48 GB on `D_init` at 0.8 already; but entering the region on 37 increments of 100
bought nothing, because the rescans that remain cost more.

## The price of a rescan

A rescan at `r_l = 0.8` takes 950 ms against remine-from-scratch's 910 ms per increment, so a
rescan is, in effect, a remine. `PreHUSPMAdaptiveAlgorithm` re-mines the whole of `D'` on a
rescan, and its own comment marks that as a stand-in for a selective reload through the block
index. Whether a selective rescan would change the balance above has not been measured.
