# Assembly of the SPMF databases from release v2.0.0-utilities

`analysis/check_database_assembly.py`, run through `analysis/record_run.py`. In a temporary
directory it downloads release `v2.0.0-utilities` of huspm-datasets (generated quantities and
profits only) and checks it against the archive checksum pinned in `fetch-datasets.sh`, downloads
the seven SPMF sequence files named in the release's `SOURCES.tsv`, assembles the databases with
the release's `tools/assemble.py`, and compares every assembled file with `datasets.sha256` and
with the `MANIFEST.sha256` of release `v1.2.1-measured-quantity`, which the measurements read.

| file | content |
|---|---|
| `<date>/assembly.csv` | one row per assembled file: its SHA-256, the two references, and whether it equals each |
| `<date>/spmf_files.csv` | one row per SPMF file: address, SHA-256 of the downloaded file, SHA-256 in the release |
| `<date>/console.txt`, `<date>/run_meta.json` | output and run record |

`analysis/scenario_tables.py` turns the latest record into the `dataset_assembly_*` keys.
