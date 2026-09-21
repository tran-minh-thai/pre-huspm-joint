#!/bin/sh
# SC7 cost probe. Two databases, chosen for what they exercise rather than for size:
#   TAFENG is not in C5_ABLATION_SET, so it runs the light path - three single full mines
#          plus the Pre-HUSPM reference.
#   SIGN is in it, so it also runs the C5 ablation, where proposed_C5_off rescans on EVERY
#          batch. At BATCH_COUNT=100 that is a hundred mines from scratch, and it is the
#          only part of SC7 whose cost is not obvious from SC1.
# KOSARAK is the third member and is extrapolated, not measured: its full-scope mine is
# 438 s, so its C5_off arm alone is of the order of twelve hours.
cd /d/pre-huspm-adaptive
J="/c/Program Files/Microsoft/jdk-21.0.12.101-hotspot/bin/java"
for D in TAFENG SIGN; do
  echo "######## SC7 $D bat dau $(date +%H:%M:%S)"
  mkdir -p "results/probe-sc7/$D"
  "$J" -Xmx48g -Xss64m -cp build -Ddataset.only=$D -Drepeats=1 \
       -Dresults.dir="results/probe-sc7/$D/" AutomatedExperimentRunner SC7 > "results/probe-sc7/$D/run.log" 2>&1
  echo "######## SC7 $D xong $(date +%H:%M:%S)"
done
echo "======== PROBE SC7 XONG $(date +%H:%M:%S) ========"
