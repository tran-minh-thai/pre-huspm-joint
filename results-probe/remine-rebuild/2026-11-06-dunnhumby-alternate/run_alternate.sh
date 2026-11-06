#!/usr/bin/env bash
# Probe: SC2 on DUNNHUMBY, Remine-own-UUT and Remine-own-UUT-rebuild alternated in one session,
# each in its own JVM, to tell a session effect from an effect of the code.
# Usage: run_alternate.sh BUILD_DIR OUT_DIR ROUNDS
set -euo pipefail
build="$1"; out="$2"; rounds="$3"
for r in $(seq 1 "$rounds"); do
  for arm in Remine-own-UUT Remine-own-UUT-rebuild; do
    d="$out/r$r/$arm"; mkdir -p "$d"
    java -Xmx16g -Xss64m -cp "$build" -Ddataset.only=DUNNHUMBY -Dalgo.only="$arm" -Drepeats=1 \
      -Dresults.dir="$d/" AutomatedExperimentRunner SC2 > "$d/run.log" 2>&1
  done
done
