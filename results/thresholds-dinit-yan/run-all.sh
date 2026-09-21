#!/bin/sh
J="/c/Program Files/Microsoft/jdk-21.0.12.101-hotspot/bin/java"
OUT=results/thresholds-dinit-yan
for D in SIGN LEVIATHAN TAFENG BIBLE BMS1 C8T1S5I8N5K KOSARAK FIFA; do
  echo "######## $D bat dau $(date +%H:%M:%S) ########"
  "$J" -Xmx24g -Xss64m -cp build experiments.ThresholdScan "$D" \
       --out "$OUT" --budget-sec 900 --level-budget-sec 2700 2>&1
  echo "######## $D xong $(date +%H:%M:%S) ########"
done
echo "======== TAT CA XONG $(date +%H:%M:%S) ========"
