#!/bin/bash
# Runs the HUSP-SP code its authors publish, unchanged, on the counterexample, and reports the HUSPs it
# returns and the bound it computes for the S-extensions of <(1)(2)>.
#   REF=<checkout of github.com/DSI-Lab1/HUSPM> bash results/invariant-husp-sp-counterexample/tools/run.sh
set -euo pipefail
T=$(dirname "$0"); B=$(mktemp -d)
echo "github.com/DSI-Lab1/HUSPM commit $(git -C "$REF" rev-parse HEAD)"
echo "java: $(java -version 2>&1 | head -1)"
# the published test class does not compile (input is declared twice); RunHuspSp calls the algorithm as it does
javac -g -nowarn -d "$B" "$REF"/HUSP-SP/UPosition.java "$REF"/HUSP-SP/UItem.java "$REF"/HUSP-SP/MemoryLogger.java \
      "$REF"/HUSP-SP/ULinkList.java "$REF"/HUSP-SP/ProjectULinkList.java "$REF"/HUSP-SP/HUSP_SP_Algo.java
javac -nowarn -cp "$B" -d "$B" "$T"/RunHuspSp.java "$T"/WatchHusps.java "$T"/WatchBounds.java
for t in "52 0.9455" "50 0.91"; do set -- $t
  echo; echo "== threshold $1 (ratio $2 of the total utility 55)"
  java -cp "$B" RunHuspSp "$T"/counterexample.txt "$2" | grep -E "HUSPs|Candidates"
  java -cp "$B" WatchHusps "$B" "$T"/counterexample.txt "$2" > "$B/watch.txt"
  python3 -I "$T"/expected.py "$1" "$B/watch.txt"
  java -cp "$B" WatchBounds "$B" "$T"/counterexample.txt "$2" | grep 'prefix "\[1, -1, 2\]"'
done
