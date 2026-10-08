#!/usr/bin/env bash
#
# Fetch the quantitative sequence databases into datasets/.
#
# The databases are not kept in this repository. They are shared across several
# studies, so they live in one place and each study pins the exact release it
# measured on:
#
#     https://github.com/tran-minh-thai/huspm-datasets
#
# Every file is checked against the SHA-256 manifest attached to that release, so
# a mismatch stops the script rather than producing numbers on the wrong bytes.
#
#     ./fetch-datasets.sh                                 pinned release
#     RELEASE=v1.1.1-seed42-lognormal ./fetch-datasets.sh  a different one
#     FORCE=1 ./fetch-datasets.sh                          re-download
#     VERIFY_ONLY=1 ./fetch-datasets.sh                    check what is there
#
# TAFENG is not part of the release and is not downloaded. Ta-Feng is redistributed
# under terms this project does not rely on, so the database is built locally from the
# raw file and pinned by checksum instead; see tafeng/ and the README. This script
# reports whether it is present and correct, and VERIFY_ONLY=1 fails if it is not.

set -euo pipefail
cd "$(dirname "$0")"

# The release this study measures on. Changing it changes the data, and therefore
# every result: re-run everything after changing it.
RELEASE="${RELEASE:-v1.2.1-measured-quantity}"
DIR="${DIR:-datasets}"
FORCE="${FORCE:-0}"
VERIFY_ONLY="${VERIFY_ONLY:-0}"

REPO="tran-minh-thai/huspm-datasets"
BASE="https://github.com/${REPO}/releases/download/${RELEASE}"
ARCHIVE="huspm-datasets-${RELEASE}.tar.gz"

mkdir -p "$DIR"
MANIFEST="${DIR}/MANIFEST.sha256"

# TAFENG is built locally rather than downloaded, so its checksums live in this
# repository rather than in the release manifest: the study that consumes the data is
# what pins its version. Returns 0 when both files are present and correct.
check_tafeng() {
  local sums='tafeng/TAFENG.sha256' bad=0 name want have
  [ -f "$sums" ] || return 0                 # no recipe here, nothing claimed
  while read -r want name; do
    [ -n "${name:-}" ] || continue
    if [ ! -f "${DIR}/${name}" ]; then echo "    ${name} missing"; bad=1; continue; fi
    have="$(sha_of "${DIR}/${name}")"
    [ "$have" = "$want" ] || { echo "    ${name} does not match"; bad=1; }
  done < "$sums"
  if [ "$bad" -eq 0 ]; then echo '==> TAFENG present and verified'; return 0; fi
  echo '==> TAFENG not ready'
  echo '    Ta-Feng is not redistributed here. Get ta_feng_all_months_merged.csv'
  echo '    (Kaggle: chiranjivdas09/ta-feng-grocery-dataset), then build it:'
  echo "        python tafeng/build_tafeng.py --zip <archive.zip> --out-dir ${DIR} --name TAFENG"
  echo '    The build is deterministic; tafeng/TAFENG.sha256 pins the result.'
  return 1
}

sha_of() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | awk '{print $1}'
  else shasum -a 256 "$1" | awk '{print $1}'; fi
}

# The manifest belongs to the release, not to the default branch: the copy on main
# always describes the newest release and would silently disagree with a pinned one.
if [ "$VERIFY_ONLY" != "1" ]; then
  echo "==> release ${RELEASE}"
  echo "    MANIFEST.sha256"
  curl -fsSL -o "$MANIFEST" "${BASE}/MANIFEST.sha256"
fi
[ -f "$MANIFEST" ] || { echo "No manifest in ${DIR}. Run without VERIFY_ONLY first." >&2; exit 1; }

verify() {
  local missing=0 wrong=0 total=0
  while read -r want name; do
    [ -n "${name:-}" ] || continue
    total=$((total + 1))
    if [ ! -f "${DIR}/${name}" ]; then missing=$((missing + 1)); continue; fi
    [ "$(sha_of "${DIR}/${name}")" = "$want" ] || wrong=$((wrong + 1))
  done < "$MANIFEST"
  echo "$missing $wrong $total"
}

read -r MISSING WRONG TOTAL <<< "$(verify)"
[ "$TOTAL" -gt 0 ] || { echo "Manifest is empty or malformed: $MANIFEST" >&2; exit 1; }

if [ "$VERIFY_ONLY" = "1" ]; then
  echo "==> ${TOTAL} files: $((TOTAL - MISSING - WRONG)) verified, ${MISSING} missing, ${WRONG} corrupt"
  TAFENG_OK=0; check_tafeng || TAFENG_OK=1
  [ "$MISSING" -eq 0 ] && [ "$WRONG" -eq 0 ] && [ "$TAFENG_OK" -eq 0 ]
  exit $?
fi

if [ "$FORCE" != "1" ] && [ "$MISSING" -eq 0 ] && [ "$WRONG" -eq 0 ]; then
  echo "==> ${TOTAL} files already present and verified; nothing to do"
  check_tafeng
  exit $?
fi

echo "==> downloading"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
echo "    ${ARCHIVE}"
curl -fsSL -o "${TMP}/${ARCHIVE}" "${BASE}/${ARCHIVE}"

echo "==> extracting"
tar -xzf "${TMP}/${ARCHIVE}" -C "$TMP"
# Archives built on macOS carry ._NAME resource forks beside the real files.
while read -r _ name; do
  [ -n "${name:-}" ] || continue
  found="$(find "$TMP" -type f -name "$name" ! -name '._*' -print -quit)"
  [ -n "$found" ] && cp -f "$found" "${DIR}/${name}"
done < "$MANIFEST"

echo "==> verifying"
read -r MISSING WRONG TOTAL <<< "$(verify)"
if [ "$MISSING" -ne 0 ] || [ "$WRONG" -ne 0 ]; then
  echo "Verification failed: ${MISSING} missing, ${WRONG} corrupt out of ${TOTAL}." >&2
  echo "Do not measure on these files." >&2
  exit 1
fi
echo "==> ${TOTAL} files verified against the manifest of ${RELEASE}"
check_tafeng || true
echo "==> record this release tag alongside any results measured on it"
