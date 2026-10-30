# Probes

Runs made to decide what to measure, not measurements. Their timings come from the development
machine, except two records run on the measurement machine (`results/machine/`) in probe mode:
`same-session/SC2-PC-THAI-2026-10-30` and `remine-instance-overhead/SIGN-PC-THAI`. The tables and
figures read `results/` only. `analysis/scenario_tables.py` reads these two records for the 12 keys
of `paper_numbers.csv` prefixed `probe_`, whose source is marked "(probe)". Counts and pattern
comparisons recorded here do not depend on the machine.

| directory | tool | question |
|---|---|---|
| `remine-cost/<date>` | `verify.RemineCostProbe` | where the time of remining goes against rescanning after every increment, step by step, on the SC2 stream; and whether both mine with the same cutoff and return the same patterns |
| `floor-policy/<date>` | `analysis/floor_policy_probe.py` | the rescan decisions of a floor policy replayed from the data alone (checked against every measured decision of the proposal and of the fixed lower threshold), priced with SC2 costs measured on the measurement machine: the current window rule, remining at the threshold, two cost rules and the offline optimum |
