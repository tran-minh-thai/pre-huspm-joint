# Is any source file derived from SPMF?

Kind: invariant (text-similarity counts; machine-independent).

Reference: https://www.philippe-fournier-viger.com/spmf/spmf.zip, 34,107,808 bytes,
Last-Modified Wed, 30 Sep 2026 03:22:52 GMT,
sha256 48599087a24bd43fc1f32dac05cd2390f3895da437e092a7c0b7e6c7426a9c10, 3,051 Java files.
Targets: every tracked `src/**/*.java` at commit 325a058.

`tools/provenance.py` reports, for each target, the SPMF file that contains the largest share
of it under three measures: normalised source lines, 8-grams of code tokens, and 8-grams of
token shapes (identifiers and literals replaced, so renaming does not hide a copy).

| file | lines | code 8-grams | shape 8-grams |
|---|---|---|---|
| USpanAlgorithm.java | 0.024 | 0.030 | 0.353 |
| HUSPULLAlgorithm.java | 0.017 | 0.032 | 0.425 |
| HUSPSPAlgorithm.java | 0.010 | 0.033 | 0.261 |
| IncUSPMinerPlusAlgorithm.java | 0.020 | 0.015 | 0.354 |
| PreHUSPMAlgorithm.java | 0.008 | 0.013 | 0.229 |
| PreHUSPMAdaptiveAlgorithm.java | 0.004 | 0.007 | 0.177 |

Control: SPMF's own AlgoUSpan.java with every identifier renamed scores 1.000 on shape
against AlgoUSpan.java, and 0.363 against the next file (control_renamed_uspan.txt). No
target comes near a copy; the best SPMF matches of USpan and HUSP-SP are unrelated files,
not SPMF's implementations of those algorithms. Full output: scan_src_vs_spmf.txt.

Scope: this compares against SPMF only.

Command: `python3 -I tools/provenance.py <empty-dir> <spmf-root> src/**/*.java`
