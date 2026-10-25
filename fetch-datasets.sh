#!/usr/bin/env bash
#
# Assemble the quantitative sequence databases into datasets/.
#
# The sequences come from the SPMF dataset collection and are not kept or redistributed
# here. The generated utilities are release v2.0.0-utilities of
#
#     https://github.com/tran-minh-thai/huspm-datasets
#
# This script downloads that release, downloads the SPMF files it names, and runs the
# release's tools/assemble.py, which checks each SPMF file and each assembled file against
# the checksums of the release. The assembled files are then checked once more against
# datasets.sha256 in this repository, the checksums of the files every result was measured on.
#
#     ./fetch-datasets.sh                 assemble what is missing
#     FORCE=1 ./fetch-datasets.sh         assemble again
#     VERIFY_ONLY=1 ./fetch-datasets.sh   check what is there
#
# Needs curl, tar and Python 3. TAFENG is not part of the release; it is built locally from
# the Ta-Feng file (see tafeng/ and the README). This script reports whether it is present
# and correct, and VERIFY_ONLY=1 fails if it is not.

set -euo pipefail
cd "$(dirname "$0")"

RELEASE="v2.0.0-utilities"
# The release archive, pinned: a release asset can be replaced, this checksum cannot.
ARCHIVE_SHA256="5d3ea0463ec1570b2b0ff530fbbd40ca204744443758ac620b6a3a777b31cad5"
BASE="${BASE:-https://github.com/tran-minh-thai/huspm-datasets/releases/download/${RELEASE}}"
ARCHIVE="huspm-datasets-${RELEASE}.tar.gz"
DIR="${DIR:-datasets}"
FORCE="${FORCE:-0}"
VERIFY_ONLY="${VERIFY_ONLY:-0}"
PINNED="datasets.sha256"
PYTHON="${PYTHON:-python3}"

mkdir -p "$DIR"

sha_of() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | awk '{print $1}'
  else shasum -a 256 "$1" | awk '{print $1}'; fi
}

# Prints "missing wrong total" for the files listed in a checksum file.
verify() {
  local missing=0 wrong=0 total=0 want name
  while read -r want name; do
    [ -n "${name:-}" ] || continue
    total=$((total + 1))
    if [ ! -f "${DIR}/${name}" ]; then missing=$((missing + 1)); continue; fi
    [ "$(sha_of "${DIR}/${name}")" = "$want" ] || wrong=$((wrong + 1))
  done < "$1"
  echo "$missing $wrong $total"
}

# TAFENG is built locally rather than assembled, so its checksums live in tafeng/.
# Returns 0 when both files are present and correct.
check_tafeng() {
  read -r m w t <<< "$(verify tafeng/TAFENG.sha256)"
  if [ "$m" -eq 0 ] && [ "$w" -eq 0 ]; then echo '==> TAFENG present and verified'; return 0; fi
  echo "==> TAFENG not ready: ${m} missing, ${w} not matching tafeng/TAFENG.sha256"
  echo '    Ta-Feng is not redistributed here. Get ta_feng_all_months_merged.csv'
  echo '    (Kaggle: chiranjivdas09/ta-feng-grocery-dataset), then build it:'
  echo "        python tafeng/build_tafeng.py --zip <archive.zip> --out-dir ${DIR} --name TAFENG"
  echo '    The build is deterministic; tafeng/TAFENG.sha256 pins the result.'
  return 1
}

read -r MISSING WRONG TOTAL <<< "$(verify "$PINNED")"
[ "$TOTAL" -gt 0 ] || { echo "No checksum listed in ${PINNED}" >&2; exit 1; }

if [ "$VERIFY_ONLY" = "1" ]; then
  echo "==> ${TOTAL} files: $((TOTAL - MISSING - WRONG)) verified, ${MISSING} missing, ${WRONG} not matching ${PINNED}"
  TAFENG_OK=0; check_tafeng || TAFENG_OK=1
  [ "$MISSING" -eq 0 ] && [ "$WRONG" -eq 0 ] && [ "$TAFENG_OK" -eq 0 ]
  exit $?
fi

if [ "$FORCE" != "1" ] && [ "$MISSING" -eq 0 ] && [ "$WRONG" -eq 0 ]; then
  echo "==> ${TOTAL} files already present and verified; nothing to do"
  check_tafeng
  exit $?
fi

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
echo "==> release ${RELEASE}"
curl -fsSL -o "${TMP}/${ARCHIVE}" "${BASE}/${ARCHIVE}"
HAVE="$(sha_of "${TMP}/${ARCHIVE}")"
if [ "$HAVE" != "$ARCHIVE_SHA256" ]; then
  echo "${ARCHIVE} has SHA-256 ${HAVE}, expected ${ARCHIVE_SHA256}. Not using it." >&2
  exit 1
fi
tar -xzf "${TMP}/${ARCHIVE}" -C "$TMP"
REL="${TMP}/huspm-datasets-${RELEASE}"

echo "==> assembling from the SPMF files (kept in ${DIR}/spmf/)"
"$PYTHON" "${REL}/tools/assemble.py" --release "$REL" --spmf "${DIR}/spmf" --out "$DIR" --download

echo "==> verifying against ${PINNED}"
read -r MISSING WRONG TOTAL <<< "$(verify "$PINNED")"
if [ "$MISSING" -ne 0 ] || [ "$WRONG" -ne 0 ]; then
  echo "Verification failed: ${MISSING} missing, ${WRONG} not matching ${PINNED}, out of ${TOTAL}." >&2
  echo "Do not measure on these files." >&2
  exit 1
fi
echo "==> ${TOTAL} files verified against ${PINNED}"
check_tafeng || true
