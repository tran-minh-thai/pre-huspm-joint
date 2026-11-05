#!/bin/sh
# Six alternating runs of SC9 on DUNNHUMBY, seed 1337, one arm per JVM, each through the run recorder.
set -e
PY=${PY:-python3}
O=results-probe/floor-policy/2026-11-05-sc9-ab-dunnhumby
n=0
for arm in Pre-HUSPM-Joint-fixed-threshold Pre-HUSPM-Joint Pre-HUSPM-Joint Pre-HUSPM-Joint-fixed-threshold \
           Pre-HUSPM-Joint-fixed-threshold Pre-HUSPM-Joint; do
  n=$((n + 1))
  d="$O/run$n-$arm"
  mkdir -p "$d"
  echo "== run $n: $arm"
  PYTHONDONTWRITEBYTECODE=1 $PY analysis/record_run.py --out "$d" --build build-ab -- \
    java -Xmx16g -Xss64m -cp build-ab -Ddataset.only=DUNNHUMBY -Dalgo.only=$arm -Drepeats=1 \
    -Dresults.dir="$d/" -Drepeat.index=0 -Dseed.index=1 AutomatedExperimentRunner SC9
done
