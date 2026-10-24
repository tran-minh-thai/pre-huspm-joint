#!/bin/bash
set -u
run() { # $1 repo $2 build $3 out $4 arms(static) $5 arms(incremental) $6 sc7 arms
  cd "$1"; O="$3"; rm -rf "$O"; mkdir -p "$O"
  J="java -Xmx20g -Xss64m -cp $2 -Drepeats=1 -Ddataset.only=SIGN"
  $J -Dalgo.only=$4 -Dresults.dir=$O/static/ AutomatedExperimentRunner SC1 SC4 > $O/static.log 2>&1; echo "static $?"
  $J -Dalgo.only=$5 -Dresults.dir=$O/inc/ AutomatedExperimentRunner SC2 SC6 > $O/inc.log 2>&1; echo "inc $?"
  $J -Dalgo.only=$6 -Dresults.dir=$O/sc7/ AutomatedExperimentRunner SC7 > $O/sc7.log 2>&1; echo "sc7 $?"
}
run <scratch>/wt-12db build-old <scratch>/rc-old "Adaptive-Full,Pre-HUSPM" "Adaptive-Full,Adaptive-FloorSelect,Adaptive-FloorSelect-w1" "proposed_full,proposed_no_IUB,proposed_no_IPS,proposed_C5_on,proposed_C5_off,proposed_no_block_index,proposed_bsz_5"
run <repo> build-rn <scratch>/rc-new "Pre-HUSPM-Joint,Pre-HUSPM" "Pre-HUSPM-Joint-fixed-threshold,Pre-HUSPM-Joint,Pre-HUSPM-Joint-w1" "Pre-HUSPM-Joint,Pre-HUSPM-Joint-no-IUB,Pre-HUSPM-Joint-no-IPS,Pre-HUSPM-Joint-rescan-rule,Pre-HUSPM-Joint-rescan-every-batch,Pre-HUSPM-Joint-no-block-index,Pre-HUSPM-Joint-bsz-5"
echo ALL_DONE
