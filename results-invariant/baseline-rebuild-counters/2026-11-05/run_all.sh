#!/usr/bin/env bash
# Reruns the arms whose timed code changed (USpan, IncUSP-Miner+, Pre-HUSPM) on small and
# medium databases, one JVM per (scenario, database, arm) as campaign.ps1 does, so that
# compare.py can check their counters against the campaign's. Usage: run_all.sh BUILD OUT
set -u
BUILD="$1"; OUT="$2"
run() {  # scenario database arm
  local d="$OUT/$1/$2/$3"; mkdir -p "$d"
  java -Xmx16g -Xss64m -cp "$BUILD" -Ddataset.only="$2" -Dalgo.only="$3" -Drepeats=1 \
       -Dresults.dir="$d/" AutomatedExperimentRunner "$1" > "$d/run.log" 2> "$d/run.err"
  echo "$1 $2 $3 exit $?"
}
for db in SIGN LEVIATHAN TAFENG C8T1S5I8N5K BIBLE; do
  for arm in USpan IncUSP-Miner+ Pre-HUSPM; do run SC1 "$db" "$arm"; done
done
for db in SIGN LEVIATHAN TAFENG; do
  for arm in Pre-HUSPM-SWUmax Pre-HUSPM-TSU IncUSP-Miner+; do run SC2 "$db" "$arm"; done
done
run SC4 SIGN Pre-HUSPM
run SC7 SIGN Pre-HUSPM
