#!/bin/sh
# The two anchors the cost table rests on and nothing has measured.
#   SC1/FIFA       - SC1 runs 24 mines on D_init and FIFA's D_init mine is 305 s, so this
#                    one database may be most of SC1's cost. Nothing else in SC1 comes close.
#   SC4/LEVIATHAN  - SC4 sweeps sl down to 0.3. Pre-HUSPM already exhausts 48 GB on LEVIATHAN
#                    at sl=0.8, and on SIGN dropping sl from 0.9 to 0.3 grew its pre-large set
#                    ninety-five fold, so its four arms here are expected to fail. That is a
#                    result, not a fault; measuring it is how we learn whether SC4 finishes.
cd /d/pre-huspm-adaptive
J="/c/Program Files/Microsoft/jdk-21.0.12.101-hotspot/bin/java"
run() {
  DIR="results/probe-rest/$1-$2"; rm -rf "$DIR"; mkdir -p "$DIR"; S=$(date +%s)
  echo "######## $1 / $2 bat dau $(date +%H:%M:%S)  (tran $3 s)"
  "$J" -Xmx48g -Xss64m -cp build -Ddataset.only="$2" -Drepeats=1 \
       -Dresults.dir="$DIR/" AutomatedExperimentRunner "$1" > "$DIR/run.log" 2>&1 &
  P=$!; ( sleep "$3"; kill $P 2>/dev/null ) & W=$!
  wait $P; R=$?; kill $W 2>/dev/null
  echo "######## $1 / $2 xong $(date +%H:%M:%S)  rc=$R  $(( $(date +%s) - S )) s"
}
run SC4 LEVIATHAN 7200
run SC1 FIFA 21600
echo "======== HAI MOC XONG $(date +%H:%M:%S) ========"
