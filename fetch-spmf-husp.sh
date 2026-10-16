#!/usr/bin/env bash
# Fetch SPMF's high-utility sequence files (SIGN, FIFA, BIBLE, Kosarak10k, BMS), used only by the
# FIDELITY scenario, into datasets/spmf-husp, check them against tools/spmf-husp.sha256, and
# convert them to this repository's format in datasets/spmf-husp/converted.
#
#     ./fetch-spmf-husp.sh
#
# Source: https://www.philippe-fournier-viger.com/spmf/index.php?link=datasets.php, section
# "Datasets for high-utility sequential rule mining or high-utility sequential pattern mining".
# The files are not redistributed here. A file whose SHA-256 differs stops the script.
set -euo pipefail
cd "$(dirname "$0")"
base=https://www.philippe-fournier-viger.com/spmf/publicdatasets/husp
dir=datasets/spmf-husp
mkdir -p "$dir"
while read -r want name; do
  [ -n "${name:-}" ] || continue
  [ -f "$dir/$name" ] || { echo "==> $name"; curl -sSfL -o "$dir/$name" "$base/$name"; }
  have=$(shasum -a 256 "$dir/$name" | cut -d' ' -f1)
  [ "$have" = "$want" ] || { echo "$name has SHA-256 $have, expected $want" >&2; exit 1; }
done < tools/spmf-husp.sha256
mkdir -p build
javac -encoding UTF-8 -nowarn -d build $(find src -name '*.java')
java -cp build experiments.SpmfHuspConvert "$dir" "$dir/converted"
echo "==> SPMF files checked and converted into $dir/converted"
