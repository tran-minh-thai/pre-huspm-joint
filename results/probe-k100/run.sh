#!/bin/sh
J="/c/Program Files/Microsoft/jdk-21.0.12.101-hotspot/bin/java"
for D in SIGN LEVIATHAN; do
  echo "######## $D SC2 K=100 bat dau $(date +%H:%M:%S) ########"
  "$J" -Xmx24g -Xss64m -cp build -Ddataset.only=$D \
       -Dresults.dir=results/probe-k100/$D/ AutomatedExperimentRunner SC2 2>&1
  echo "######## $D xong $(date +%H:%M:%S) ########"
done
echo "======== PROBE XONG $(date +%H:%M:%S) ========"
