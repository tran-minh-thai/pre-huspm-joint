#!/usr/bin/env bash
# Build and run the selected scenarios.
#
#   ./run.sh                      every scenario
#   ./run.sh SC1 SC2              selected scenarios
#   HEAP=8g ./run.sh TEST SC1     reduced grid
#
# Output goes to results/run-<timestamp>-<commit>/ with a manifest describing the
# code version and the machine. Figures are built from the campaign trees by
# analysis/make_figures.py, not from a single run.

set -euo pipefail
cd "$(dirname "$0")"

HEAP="${HEAP:-24g}"
STACK="${STACK:-64m}"
TIMEOUT_MIN="${TIMEOUT_MIN:-120}"
SCENARIOS=("$@")
[ ${#SCENARIOS[@]} -eq 0 ] && SCENARIOS=(ALL)

COMMIT="$(git rev-parse --short HEAD 2>/dev/null || echo nogit)"
DIRTY=""
git diff --quiet 2>/dev/null || DIRTY="-dirty"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
RUN_ID="run-${STAMP}-${COMMIT}${DIRTY}"
RUN_DIR="results/${RUN_ID}"
mkdir -p "$RUN_DIR"

echo "==> compiling"
rm -rf build && mkdir -p build
javac -encoding UTF-8 -d build $(find src -name '*.java')

echo "==> writing manifest"
{
  echo "{"
  echo "  \"run_id\": \"${RUN_ID}\","
  echo "  \"commit\": \"$(git rev-parse HEAD 2>/dev/null || echo nogit)\","
  echo "  \"working_tree_clean\": $([ -z "$DIRTY" ] && echo true || echo false),"
  echo "  \"started_utc\": \"${STAMP}\","
  echo "  \"scenarios\": \"${SCENARIOS[*]}\","
  echo "  \"jvm_flags\": \"-Xmx${HEAP} -Xss${STACK} -Dtimeout.min=${TIMEOUT_MIN}\","
  echo "  \"timeout_minutes\": ${TIMEOUT_MIN},"
  echo "  \"java_version\": \"$(java -version 2>&1 | head -1 | tr -d '\"')\","
  echo "  \"os\": \"$(uname -srm)\","
  echo "  \"cpu\": \"$(sysctl -n machdep.cpu.brand_string 2>/dev/null || (lscpu 2>/dev/null | sed -n 's/^Model name: *//p' | head -1))\","
  echo "  \"memory_bytes\": \"$(sysctl -n hw.memsize 2>/dev/null || (free -b 2>/dev/null | awk '/^Mem:/{print $2}'))\","
  echo "  \"dataset_sha256\": {"
  first=1
  for f in datasets/*.txt; do
    [ -e "$f" ] || continue      # no databases fetched yet (the TEST grid reads examples/)
    sum="$( (shasum -a 256 "$f" 2>/dev/null || sha256sum "$f") | cut -d' ' -f1)"
    [ $first -eq 0 ] && echo ","
    printf '    "%s": "%s"' "$(basename "$f")" "$sum"
    first=0
  done
  echo ""
  echo "  }"
  echo "}"
} > "$RUN_DIR/manifest.json"

echo "==> running: ${SCENARIOS[*]}"
java -Xmx"$HEAP" -Xss"$STACK" \
     -Dtimeout.min="$TIMEOUT_MIN" \
     -Dresults.dir="$RUN_DIR/" \
     -cp build AutomatedExperimentRunner "${SCENARIOS[@]}" \
     2>&1 | tee "$RUN_DIR/console.log"


echo
echo "Results:  $RUN_DIR"
echo "Summary:  python3 analysis/aggregate.py $RUN_DIR"
echo "Commit these together to keep the numbers tied to commit ${COMMIT}."
