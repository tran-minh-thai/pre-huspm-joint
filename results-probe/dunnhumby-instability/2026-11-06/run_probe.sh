#!/usr/bin/env bash
# Probe: why do SC2 runtimes on DUNNHUMBY differ by up to 10% between JVMs with the same code, data
# and seed? Runs Pre-HUSPM-Joint on DUNNHUMBY SC2 under three JVM settings, interleaved A B C A B C...,
# each run in its own JVM:
#   A  the campaign's flags (heap ceiling only)
#   B  A plus -Xbatch: just-in-time compilation in the foreground, no race with background compiler threads
#   C  A plus a fixed, pre-touched heap (-Xms equal to -Xmx, -XX:+AlwaysPreTouch)
# Usage: run_probe.sh BUILD_DIR OUT_DIR ROUNDS
set -euo pipefail
build="$1"; out="$2"; rounds="$3"
heap=16g
for r in $(seq 1 "$rounds"); do
  for cfg in A B C; do
    case "$cfg" in
      A) extra=() ;;
      B) extra=(-Xbatch) ;;
      C) extra=(-Xms$heap -XX:+AlwaysPreTouch) ;;
    esac
    d="$out/r$r/$cfg"; mkdir -p "$d"
    java -Xmx$heap ${extra[@]+"${extra[@]}"} -Xss64m -cp "$build" -Ddataset.only=DUNNHUMBY -Dalgo.only=Pre-HUSPM-Joint \
      -Drepeats=1 -Dresults.dir="$d/" AutomatedExperimentRunner SC2 > "$d/run.log" 2>&1
    echo "round $r config $cfg done $(date +%H:%M:%S)"
  done
done
