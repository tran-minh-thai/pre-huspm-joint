#!/bin/sh
# Cost probe for the four scenarios nothing has measured at the new D_init.
#   SC1 covers all eight databases; SC3, SC4 and SC6 cover SUBSET_3 only.
# One cheap database each, then extrapolate by the SC2 ratios already known.
# SC4 goes last: it sweeps sl down to 0.3, a pre-large floor far below the 0.8 at which
# LEVIATHAN already exhausts 48 GB, so it is the one that may not finish at all.
cd /d/pre-huspm-adaptive
J="/c/Program Files/Microsoft/jdk-21.0.12.101-hotspot/bin/java"
CAP=2700
run() {                                  # run <scenario> <database>
  DIR="results/probe-rest/$1-$2"; mkdir -p "$DIR"; S=$(date +%s)
  echo "######## $1 / $2 bat dau $(date +%H:%M:%S)"
  "$J" -Xmx48g -Xss64m -cp build -Ddataset.only="$2" -Drepeats=1 \
       -Dresults.dir="$DIR/" AutomatedExperimentRunner "$1" > "$DIR/run.log" 2>&1 &
  P=$!; ( sleep $CAP; kill $P 2>/dev/null ) & W=$!
  wait $P; R=$?; kill $W 2>/dev/null
  echo "######## $1 / $2 xong $(date +%H:%M:%S)  rc=$R  $(( $(date +%s) - S )) s"
}
run SC1 TAFENG
run SC1 SIGN
run SC3 SIGN
run SC6 SIGN
run SC4 SIGN
echo "======== PROBE SC1/SC3/SC4/SC6 XONG $(date +%H:%M:%S) ========"
