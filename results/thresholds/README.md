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
