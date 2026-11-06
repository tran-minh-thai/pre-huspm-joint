# Probe: the two own-miner remining arms alternated on DUNNHUMBY, SC2 (2026-11-06)

PROBE (development machine, arm64 macOS): the times are not results; the probe answers only the sign of
the difference between Remine-own-UUT-rebuild and Remine-own-UUT within one session.

    python3 analysis/record_run.py --out <this dir> --build <tmp> -- bash run_alternate.sh <tmp> <this dir>/run 3

Three rounds, each arm in its own JVM, old arm first in every round. Sum of runtime_ms over the 100
insertions (iteration > 0):

| round | Remine-own-UUT | Remine-own-UUT-rebuild | rebuild / old |
|---|---|---|---|
| r1 | 125123 | 137479 | 1.099 |
| r2 | 129892 | 130898 | 1.008 |
| r3 | 124790 | 139335 | 1.117 |

Both arms report the same patterns (1630) and explored nodes (1463534) in every round.
Within one session the rebuilding arm is slower in 3 of 3 rounds, as the code predicts.
