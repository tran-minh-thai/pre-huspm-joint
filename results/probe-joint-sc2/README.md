# Probe: rescans of the joint rule on SC2's stream

Kind: **probe** on the development machine. Counters only; the runtimes of this run were
measured on the development machine and are not kept. The measured SC2 arms of the proposal are
being re-run on `PC-THAI`; this probe exists to know, before that run, how the joint rule's
rescans compare with the superseded `iaumax` rule's on the same stream.

    java -Xmx12g -Xss64m -cp build -Ddataset.only=<DB> -Dalgo.only=Adaptive-Full -Drepeats=1 \
         -Dresults.dir=results/probe-joint-sc2/raw/<DB>/ AutomatedExperimentRunner SC2

for the seven SC2 databases; `run_meta.json` holds the commit and the environment.
`counters.csv` keeps, per increment, the rescan decision, the test quantity (`iau_max`), its
bound (`sft_val`), the rescan counter, the HUSP count and the pre-large count. The `iaumax`
column is read from the campaign's SC2 rows at commit 4e3f256.

<!-- generated: python3 analysis/compare_rescans.py 'results/probe-joint-sc2/raw/*/SC2_incremental.csv' '<4e3f256 SC2 Adaptive-Full CSVs>' -->
| database | increments | joint: ratio median | min | max | within budget | joint: rescans | iaumax: rescans | difference | check |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---|
| SIGN | 100 | 1.103 | 0.408 | 2.508 | 41 | 59 | 59 | 0 | ok / ok |
| LEVIATHAN | 100 | 2.077 | 0.942 | 4.036 | 2 | 98 | 98 | 0 | ok / ok |
| FIFA | 100 | 0.762 | 0.180 | 1.513 | 73 | 27 | 28 | -1 | ok / ok |
| BIBLE | 100 | 1.201 | 0.560 | 2.136 | 37 | 63 | 63 | 0 | ok / ok |
| KOSARAK | 100 | 3.775 | 1.916 | 21.390 | 0 | 100 | 100 | 0 | ok / ok |
| C8T1S5I8N5K | 100 | 1.442 | 0.378 | 5.048 | 23 | 77 | 80 | -3 | ok / ok |
| TAFENG | 100 | 1.008 | 0.315 | 6.519 | 48 | 52 | 52 | 0 | ok / ok |

The ratio is the test quantity over its bound; an increment rescans exactly when it exceeds 1,
and `check` confirms that the increments above 1, the triggered flags and the rescan counter
agree, for both rules.

The HUSP count of every row equals remine-from-scratch's in the campaign: 707 of 707 rows.

At one and the same state, with insertions only, the joint test quantity never exceeds the
`iaumax` one (`max A_i - UUT*I <= (1 - UUT) max A_i`, since `I >= max A_i`), so an increment
`iaumax` would not rescan, `joint` does not either. That does not order the totals: once the two
rules decide an increment differently their states differ.
