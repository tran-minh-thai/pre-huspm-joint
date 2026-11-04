# Probe: explored nodes of the floor policies on the DUNNHUMBY long streams

Kind: probe on the development machine. Explored nodes are counts and do not depend on the machine;
no time in this directory is a measurement.

Command (run_meta.json): `analysis/floor_policy_probe.py --long --no-sc2 --db DUNNHUMBY --nodes`
with the plans of `results-probe/floor-policy/long-plans-dunnhumby/` (verify.LongPlanDump, checksum
beside the plan file). Rows: `results/campaign/{SC8,SC8P,SC9,SC10,SC10H}/DUNNHUMBY`, one run per seed.

Replay checks, 15 streams of 500 operations: the replay reproduces the measured rescans of every
operation for window 5 (7500/7500), the fixed lower threshold (7500/7500), the method replayed with its
logged ratios (7500/7500) and the method's rule fed by remining's node counts (7500/7500). The dynamic
programme equals the enumeration of every floor-choice sequence on a prefix of 10 to 13 units
(576 to 864 sequences) on 15/15 streams.

Nodes per policy, summed over the three seeds, over the offline optimum:

| stream | method | fixed lower threshold | window 5 | always the threshold | method / fixed |
|---|---|---|---|---|---|
| SC8 | 1.2575 | 1.1967 | 1.2036 | 1.3080 | 1.0508 |
| SC8P | 1.1988 | 1.1820 | 1.1844 | 1.8833 | 1.0142 |
| SC9 | 1.0023 | 1.0000 | 1.0000 | 1.7266 | 1.0023 |
| SC10 | 1.2453 | 1.2022 | 1.2088 | 1.2746 | 1.0358 |
| SC10H | 1.2239 | 1.1857 | 1.1922 | 1.3535 | 1.0322 |

The method's totals were also obtained a second way, from the method's own counters with the deletion
rows corrected (below): equal on 5/5 streams, and equal for the fixed lower threshold on 5/5.

Counter of a deletion row. `PreHUSPMAdaptiveAlgo.deletionPhase` does not reset `exploredNodes` and
`candidateCount`, so the `explored_nodes` and `candidate_count` of a deletion row add the mine of that
deletion (if any) to the value of the row before it; a direct deletion repeats it. On these streams the
method's raw sum exceeds its own nodes by 1,422,366 (SC10H) to 2,238,742 (SC8P) on four streams and by 43,779 on SC9. The rule is not
affected: `timedMine` takes the difference of the counter around the mine. The own count of a deletion
row is its value minus the previous row's value (never negative here; 0 on every direct deletion).
