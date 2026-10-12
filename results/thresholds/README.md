# Threshold calibration

One file per machine, `thresholds-<host>.csv`, written by `experiments.ThresholdScan`.

Each machine owns its own file on purpose. Two machines appending to one path collide on
the next pull: git refuses to overwrite an untracked file, and once the path is tracked the
two sets of rows conflict. Merging is a deliberate step here, not an accident of whoever
pushed last.

Pattern counts do not depend on the machine, so a row measured anywhere is valid anywhere.
The `mine_ms` column is not: it is there to explain why a search took as long as it did,
never to be reported.

## Columns

| Column | Meaning |
|---|---|
| `level` | which sweep level: `low` is the lowest threshold and the most patterns |
| `target_patterns` | the pattern count each level is calibrated to |
| `uut` | the threshold found; `0` when none was |
| `patterns` | the count it actually produced; `-1` when none was found |
| `probes` | mining runs the search needed |
| `status` | see below |

| Status | Meaning |
|---|---|
| `ok` | within ten percent of the target |
| `closest` | the search converged but the count moves in jumps and never landed in the band |
| `closest-budget-spent` | the level's budget ran out first; the value is the best measured |
| `no-patterns-within-budget` | every threshold that finished returned nothing, and every threshold low enough to return something was abandoned. Not a calibration; the database needs wider budgets |
| `unreachable-within-budget` | no probe finished at all |

A database whose thresholds are not calibrated is skipped by the runner rather than measured
at an operating point chosen for different data.

## `bms1-whole`

BMS1 on the whole database at three thresholds, run on the measurement machine from commit
`2050fabc` (the parent of the commit that added this directory):

```
java -Xmx48g -Xss64m -cp build experiments.ThresholdScan BMS1 --full --grid 0.030,0.025,0.020 --max-candidates 3000000000 --budget-sec 3600 --out results\thresholds\bms1-whole
```

| UUT | patterns |
|---|---|
| 0.030 | 1 |
| 0.025 | 2 |
| 0.020 | over three billion candidates, abandoned |

The four SC1 levels of BMS1 (0.012821 to 0.016218) lie below 0.020, and a lower threshold only
explores more, so none of them can be mined on the whole database. This is why BMS1 is measured
in SC1 alone. The counts do not depend on the machine; `mine_ms` is not reported.
