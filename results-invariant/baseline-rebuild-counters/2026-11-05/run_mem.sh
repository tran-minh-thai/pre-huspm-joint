#!/usr/bin/env bash
# SC1 live-set run, one JVM per (database, arm), with the flags of the memory campaign
# (campaign.ps1 -MemMode live -MemGeneration sampled). Usage: run_mem.sh BUILD OUT
set -u
BUILD="$1"; OUT="$2"
for db in SIGN TAFENG; do
  for arm in Pre-HUSPM-Joint Pre-HUSPM IncUSP-Miner+ HUSP-SP; do
    d="$OUT/SC1/$db/$arm"; mkdir -p "$d"
    java -Xmx16g -Xss64m -cp "$BUILD" -Ddataset.only="$db" -Dalgo.only="$arm" -Drepeats=1 \
         -Dresults.dir="$d/" -Dmem.mode=live -Dmem.interval.ms=20 -Drepeat.index=0 -Dseed.index=0 \
         AutomatedExperimentRunner SC1 > "$d/run.log" 2> "$d/run.err"
    echo "SC1 $db $arm exit $?"
  done
done
