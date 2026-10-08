# Console lines translated in the logs

Six fixed console lines that earlier versions of the runner and of the calibration script
printed in Vietnamese were replaced, in the `.log` files under `results/`, by the English
lines the current code prints. Numbers in those lines were kept. No result value, CSV file,
`DONE` or `DEFERRED` file was changed.

| line | replacements |
|---|---:|
| `### runtime_ms is written as -3 (perturbed by forced GC); the wall time is in wall_ms_perturbed. NOT a performance figure.` | 117 |
| `### LIVE HEAP: forced collection, interval >= <n> ms \| arm = <name>` | 117 |
| `==== <database> start <time> ====` | 5 |
| `==== done <time> ====` | 2 |
| `grid measured earlier (D_init): ...` | 1 |
| `each level runs in its own process: seed back to <x>, hi back to <y>` | 1 |
