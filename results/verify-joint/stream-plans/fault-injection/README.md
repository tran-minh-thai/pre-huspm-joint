# Fault injection of the stream-plan check

`inject.sh` changes one line of `src/common/StreamPlans.java` in a copy of `src/`, compiles the copy
and runs `verify.StreamPlansCheck` on it; `console.txt` holds the commit, each change as a diff, the
exit code, the check's last line, and the scenarios of the violations it printed. Command:

    bash results/verify-joint/stream-plans/fault-injection/inject.sh <repository> <work directory>

| case | change | exit | violations | scenario of the violations printed |
|---|---|---|---|---|
| heavy-x9 | heavy insertions of SC10H scaled by 9 instead of 10 | 1 | 2100 | SC10H |
| no-alternation | SC9 inserts twice instead of insert then delete | 1 | 6000 | SC9 |
| old-id | a changed sequence of SC10 keeps the id of the one it replaces | 1 | 9675 | SC10 |
| factor-one | a quantity change of SC10 multiplies by 1 | 1 | 3506 | SC10 |

The unchanged code passes the same check with 0 violations (`../console.txt`).
