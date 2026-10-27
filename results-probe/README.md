# Probes on the development machine

Runs made to decide what to measure, not measurements. Every timing here comes from the
development machine, not from the measurement machine (`results/machine/`), and no table, figure
or key of the paper reads this tree: `analysis/make_figures.py` and `analysis/scenario_tables.py`
read `results/` only. Counts and pattern comparisons recorded here do not depend on the machine.

| directory | tool | question |
|---|---|---|
| `remine-cost/<date>` | `verify.RemineCostProbe` | where the time of remining goes against rescanning after every increment, step by step, on the SC2 stream; and whether both mine with the same cutoff and return the same patterns |
| `floor-policy/<date>` | `analysis/floor_policy_probe.py` | the rescan decisions of a floor policy replayed from the data alone (checked against every measured decision of the proposal and of the fixed lower threshold), priced with SC2 costs measured on the measurement machine: the current window rule, remining at the threshold, two cost rules and the offline optimum |
