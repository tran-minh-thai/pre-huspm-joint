#!/bin/sh
# SC2 at K=100, one repeat, one JVM per algorithm. The POSIX twin of campaign-sc2.ps1;
# see that file for why the arms are isolated and why BMS1 is absent.
#
#   ./campaign-sc2.sh                  # all seven databases
#   ./campaign-sc2.sh FIFA             # one, to split the work across machines
#   ./campaign-sc2.sh KOSARAK FIFA     # or several
set -e
CAP=${CAP:-28800}
ALGOS="Adaptive-Full Pre-HUSPM Pre-HUSPM-TSU Remine-from-scratch IncUSP-Miner+"
[ $# -gt 0 ] && SETS="$*" || SETS="SIGN LEVIATHAN TAFENG BIBLE C8T1S5I8N5K KOSARAK FIFA"

J="${JAVA_HOME:+$JAVA_HOME/bin/java}"
[ -x "$J" ] || J=$(command -v java)
VER=$("$J" -version 2>&1 | head -1)
echo "java: $J"; echo "      $VER"
case "$VER" in *'"21.'*) ;; *) echo "This study is pinned to JDK 21. Found: $VER" >&2; exit 1;; esac
[ -f datasets/SIGN_seq.txt ] || { echo "Databases are missing. Run ./fetch-datasets.sh" >&2; exit 1; }

echo "compiling..."
rm -rf build && mkdir build
"$(dirname "$J")/javac" -encoding UTF-8 -d build $(find src -name '*.java')

echo "=== SC2 K=100, repeats=1, isolated arms - $(date '+%F %H:%M:%S') ==="
for D in $SETS; do
  for A in $ALGOS; do
    DIR="results/campaign/$D/$A"; mkdir -p "$DIR"; S=$(date +%s)
    echo "#### $D / $A bat dau $(date +%H:%M:%S)"
    "$J" -Xmx24g -Xss64m -cp build \
         -Ddataset.only="$D" -Dalgo.only="$A" -Drepeats=1 \
         -Dresults.dir="$DIR/" AutomatedExperimentRunner SC2 > "$DIR/run.log" 2>&1 &
    JPID=$!
    ( sleep "$CAP"; kill $JPID 2>/dev/null ) & WPID=$!
    set +e; wait $JPID; RC=$?; set -e
    kill $WPID 2>/dev/null || true
    echo "#### $D / $A xong $(date +%H:%M:%S)  rc=$RC  $(( $(date +%s) - S )) s"
  done
  echo "======== $D XONG $(date '+%F %H:%M:%S') ========"
done
echo "======== CHIEN DICH SC2 XONG $(date '+%F %H:%M:%S') ========"
