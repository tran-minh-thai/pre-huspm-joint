#!/usr/bin/env bash
# The offline optimum in explored nodes on the long streams (SC8, SC8P, SC9, SC10, SC10H), by
# analysis/floor_policy_probe.py --long --nodes over the campaign's rows. The operation plans are
# those of verify.LongPlanDump: SC8 to SC10 of the four databases other than DUNNHUMBY and every
# scenario of DUNNHUMBY from results-probe/floor-policy/long-plans*, SC10H of the four other
# databases dumped here. Run through analysis/record_run.py:
#   python3 analysis/record_run.py --out results-invariant/offline-optimum-nodes-long/<date> -- \
#       bash analysis/offline_optimum_long.sh results-invariant/offline-optimum-nodes-long/<date> build
set -e
D="$1"; BUILD="${2:-build}"; PY="${PY:-python3}"
mkdir -p "$D/plans" "$D/others" "$D/dunnhumby"
java -Xmx16g -cp "$BUILD" verify.LongPlanDump --out "$D/plans/sc10h_plans.tsv" --scenarios SC10H \
     --db SIGN,LEVIATHAN,C8T1S5I8N5K,TAFENG
{ cat results-probe/floor-policy/long-plans/long_plans.tsv; tail -n +2 "$D/plans/sc10h_plans.tsv"; } \
    > "$D/plans/long_plans_others.tsv"
cp results-probe/floor-policy/long-plans-dunnhumby/long_plans.tsv "$D/plans/long_plans_dunnhumby.tsv"
shasum -a 256 "$D"/plans/*.tsv results-probe/floor-policy/long-plans/long_plans.tsv \
    results-probe/floor-policy/long-plans-dunnhumby/long_plans.tsv
"$PY" analysis/floor_policy_probe.py --out "$D/others" --long --no-sc2 --db SIGN LEVIATHAN C8T1S5I8N5K TAFENG \
      --nodes --long-plans "$D/plans/long_plans_others.tsv"
"$PY" analysis/floor_policy_probe.py --out "$D/dunnhumby" --long --no-sc2 --db DUNNHUMBY \
      --nodes --long-plans "$D/plans/long_plans_dunnhumby.tsv"
