#!/bin/bash
# Runs the HUSP-SP code its authors publish, unchanged, on the small random databases of the exhaustive
# enumeration check, as generated and with every utility 0 set to 1, and compares its HUSP counts with
# the enumeration (RandomDatabases.java).
#   REF=<checkout of the HUSP-SP directory of github.com/DSI-Lab1/HUSPM> CLASSES=<our compiled classes> \
#     bash results/invariant-husp-sp-counterexample/tools/run_random.sh TRIALS SEED
set -euo pipefail
T=$(dirname "$0"); B=build-authors/classes; D=build-authors/databases
rm -rf "$B" "$D"; mkdir -p "$B" "$D"
echo "github.com/DSI-Lab1/HUSPM commit: ${REF_COMMIT:-see run_meta}"
echo "java: $(java -version 2>&1 | head -1)"
javac -g -nowarn -d "$B" "$REF"/UPosition.java "$REF"/UItem.java "$REF"/MemoryLogger.java \
      "$REF"/ULinkList.java "$REF"/ProjectULinkList.java "$REF"/HUSP_SP_Algo.java
javac -nowarn -cp "$CLASSES:$B" -d "$B" "$T"/RandomDatabases.java
for mode in as-generated zero-to-one; do
  echo; echo "== utilities $mode"
  extra=""; [ "$mode" = zero-to-one ] && extra="zero-to-one"
  java -Xss64m -cp "$CLASSES:$B" verify.RandomDatabases "$1" "$2" "$D/$mode" $extra
done
