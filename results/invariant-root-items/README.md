# Root items stopped at once by the node bound

Counts computed from the data files by `analysis/root_items.py`; no timing. `run_meta.json`
holds the command and the commit, `console.txt` the output.

At the root the proposal builds an occurrence list for every item that the irrelevant item
pruning keeps, and the node bound then stops each item whose summed prefix extension utility is
below the cutoff. `explored_nodes` of the proposal counts these items. The baselines count a node
only when it passes their bound, so the proposal's count under their convention is
`explored_nodes - root_stopped`. `analysis/scenario_tables.py` (`table_explored_nodes.csv`), the
SC3 explored-node figure and the SC7 ablation table use that value.

One row per static configuration of the campaign: SC1 (`D_init`, four thresholds, eight
databases), SC3 (whole database, four thresholds, SIGN, LEVIATHAN, C8T1S5I8N5K) and SC7 (whole
database, default threshold, with the pruning as in `proposed_full` and without it as in
`proposed_no_IPS`); 58 rows.

Checked against the miner: a counter of the root lists and of those stopped at once was added
to a copy of `PreHUSPMAdaptiveAlgorithm` outside the repository, and the copy mined all 58
configurations. Root lists and stopped items agree on 58 of 58 configurations, and the copy's
`explored_nodes` equal the campaign's on 58 of 58. With the tool changed to keep the removed
items in the remaining utility, 49 of the 58 configurations disagree; the other nine have no
removed item that changes a root bound (the seven without the pruning among them).
