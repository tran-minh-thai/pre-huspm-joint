#!/bin/bash
# The one comparison where the published code misses a pattern that the TRSU of Definition 4.11 keeps:
# database 1402 (zero-to-one), threshold 58, <(1)(9)(1 4)> of utility 58. Lists the comparisons where the
# definition misses patterns, prints the bound the published code computes for the I-extension by 4 of
# <(1)(9)(1)>, and the trace of the definition at the same node.
#   CLASSES=<our compiled classes> bash results/invariant-husp-sp-counterexample/tools/run_trace_1402.sh
# Needs build-authors/classes and build-authors/databases from run_random.sh.
set -euo pipefail
T=$(dirname "$0"); A=build-authors/classes; P=build-authors/probe; F=build-authors/databases/zero-to-one/trial1402-0.txt
rm -rf "$P"; mkdir -p "$P"
javac -nowarn -cp "$CLASSES" -d "$P" "$T"/CompareCounts.java "$T"/TraceNode.java
javac -nowarn -cp "$A" -d "$P" "$T"/WatchBoundsBoth.java
echo "== comparisons where the TRSU of Definition 4.11 returns fewer patterns (zero-to-one)"
java -Xss64m -cp "$CLASSES:$P" verify.CompareCounts 2000 1
tu=$(awk -F'SUtility:' '{s+=$2} END{print s}' "$F")
echo; echo "== published code, database 1402, threshold 58 (TU $tu)"
java -cp "$A:$P" WatchBoundsBoth "$A" "$F" "$(python3 -c "print(58.5/$tu)")" | grep -E 'prefix "\[1, -1, 9, -1, 1\]" item 4 '
echo; echo "== Definition 4.11 at the same node"
java -cp "$CLASSES:$P" verify.TraceNode "$F" 58 "1 9 1"
