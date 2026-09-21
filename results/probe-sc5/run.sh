#!/bin/sh
# SC5 cost probe. SC5 is K in {100,67,50,25} x 4 strategies x 5 arms x 3 databases = 240
# runs, and nothing has ever measured one. Two cheap databases, one arm each, give the
# shape of the K scaling; KOSARAK is extrapolated from its SC2 cost rather than measured,
# because measuring it is the thing we are trying to decide whether to pay for.
cd /d/pre-huspm-adaptive
J="/c/Program Files/Microsoft/jdk-21.0.12.101-hotspot/bin/java"
while [ "$(tasklist 2>/dev/null | grep -c java.exe)" -gt 0 ]; do sleep 60; done
for D in SIGN LEVIATHAN; do
  echo "######## SC5 $D / Adaptive-Full bat dau $(date +%H:%M:%S)"
  mkdir -p "results/probe-sc5/$D"
  "$J" -Xmx48g -Xss64m -cp build -Ddataset.only=$D -Dalgo.only=Adaptive-Full -Drepeats=1 \
       -Dresults.dir="results/probe-sc5/$D/" AutomatedExperimentRunner SC5 > "results/probe-sc5/$D/run.log" 2>&1
  echo "######## SC5 $D xong $(date +%H:%M:%S)"
done
echo "======== PROBE SC5 XONG $(date +%H:%M:%S) ========"
