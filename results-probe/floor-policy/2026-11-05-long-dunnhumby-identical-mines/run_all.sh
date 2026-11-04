#!/bin/sh
# Probe on measured rows: time of identical mines (same operation, same explored nodes) across arms.
set -e
PY=${PY:-python3}
D=$(dirname "$0")
for db in DUNNHUMBY TAFENG LEVIATHAN C8T1S5I8N5K SIGN; do
  echo "== paired method/fixed $db"; $PY "$D/paired.py" . $db
done
echo "== against remining, DUNNHUMBY"; $PY "$D/vs_remine.py" . DUNNHUMBY
