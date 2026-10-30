# Is any source file derived from SPMF or from the authors' code?

Kind: invariant (text-similarity counts; machine-independent). Output in `2026-10-10/`, each
run with its `run_meta.json`.

`tools/provenance.py` reports, for each target, the reference file that contains the largest
share of it under three measures: normalised source lines, 8-grams of code tokens, and 8-grams
of token shapes (identifiers and literals replaced, so renaming does not hide a copy).
Command: `python3 -I tools/provenance.py <empty-dir> <reference-root> src/**/*.java`.


Targets: every tracked `src/**/*.java` (34 files). References: `spmf.zip` from
https://www.philippe-fournier-viger.com/spmf/ (sha256 in the first line of `src-vs-spmf/console.txt`),
and the code the HUSP-SP authors publish, github.com/DSI-Lab1/HUSPM (commit in the first line of
`src-vs-husp-sp-authors/console.txt`; it holds HUSP-SP only). SPMF holds USpan among the
baselines, and none of HUSP-ULL, HUSP-SP, IncUSP-Miner+ or Pre-HUSPM.

| file | SPMF: lines / code 8-grams / shape 8-grams | HUSP-SP authors: lines / code / shape |
|---|---|---|
| USpanAlgorithm.java | 0.022 / 0.027 / 0.353 | 0.004 / 0.011 / 0.243 |
| HUSPULLAlgorithm.java | 0.011 / 0.009 / 0.261 | 0.000 / 0.001 / 0.127 |
| HUSPSPAlgorithm.java | 0.008 / 0.009 / 0.205 | 0.000 / 0.002 / 0.118 |
| IncUSPMinerPlusAlgorithm.java | 0.010 / 0.011 / 0.263 | 0.000 / 0.002 / 0.159 |
| PreHUSPMAlgorithm.java | 0.008 / 0.008 / 0.206 | 0.000 / 0.002 / 0.126 |
| PreHUSPMAdaptiveAlgorithm.java | 0.004 / 0.006 / 0.167 | 0.000 / 0.002 / 0.101 |

Over the other 28 files the largest share is 0.333 of lines (`QItem`, 2 of 6 lines) and 0.250 of code
8-grams. The highest shape scores belong to the smallest files: `QItem` (a class with two
fields and a constructor) has 29 shape 8-grams, all found in an unrelated SPMF class; a class
that small has no other shape to take. Control (`control-renamed-uspan/`): SPMF's
`AlgoUSpan.java` with comments removed and every identifier renamed
(`tools/rename_identifiers.py`) scores 0.992 on shape against its source and 0.360 against the
next file, so a renamed copy would show.

## 2026-10-30: every source file at commit 6dbc44a9

Output in `2026-10-30/`, the same three commands on the 42 tracked `src/**/*.java` files (8 more than
on 2026-10-10, two of the method's files changed). The references are the same: `spmf.zip` with the
same SHA-256 and the HUSP-SP authors' code at the same commit.

| file | SPMF: lines / code 8-grams / shape 8-grams | HUSP-SP authors: lines / code / shape |
|---|---|---|
| USpanAlgorithm.java | 0.022 / 0.027 / 0.353 | 0.004 / 0.011 / 0.243 |
| HUSPULLAlgorithm.java | 0.011 / 0.009 / 0.261 | 0.000 / 0.001 / 0.127 |
| HUSPSPAlgorithm.java | 0.008 / 0.009 / 0.205 | 0.000 / 0.002 / 0.118 |
| IncUSPMinerPlusAlgorithm.java | 0.010 / 0.011 / 0.263 | 0.000 / 0.002 / 0.159 |
| PreHUSPMAlgorithm.java | 0.008 / 0.008 / 0.205 | 0.000 / 0.002 / 0.125 |
| PreHUSPMAdaptiveAlgorithm.java | 0.003 / 0.006 / 0.150 | 0.000 / 0.001 / 0.084 |

Over the other 36 files the largest share is 0.333 of lines (`QItem`, 2 of 6 lines) and 0.250 of code
8-grams (`QItemset`, 3 of 12). The highest shape scores are again the smallest files, each against an
unrelated SPMF class: `QItem` 1.000 (`clhminer/Element.java`), `QItemset` 0.750
(`GSPAN2GraphMLConverter.java`), `ResultCollector` 0.560 (a GUI window), `SpmfHuspConvert` 0.432 (a GUI
viewer). Their shares of lines and code 8-grams are at most 0.083 and 0.074 for the last two, and 0.333
(2 of 6 lines) and 0.250 for `QItem` and `QItemset`, classes of a few fields and a constructor. The
control renamed copy of `AlgoUSpan.java` again scores 0.992 on shape against its source and 0.360
against the next file.
