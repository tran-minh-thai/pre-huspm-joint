# Long sequences repeated nearly unchanged

Counts on the data files, written by `analysis/repeated_sequences.py`; no timing.
`run_meta.json` holds the command and the commit, `console.txt` the output,
`BMS1_repeated.csv` one row per group of sequences of at least 100 items that share their item
sequence (quantities ignored).

BMS1 on the whole database explodes at UUT 2.0% and finishes at 2.5% (`results/thresholds/bms1-whole`).
The data show why. One 149-item click sequence occurs 11 times, and 7 copies of a 154-item
sequence contain it as a subsequence, all after `D_init` (the first 39,601 sequences). These 18
sequences hold 1.78% of TU(D). A pattern drawn from the 149 items is supported by all 18, so its
utility can approach that share. The mining cutoff is r_l * UUT: 1.6% of TU(D) at UUT 2.0%, below
1.78%, and 2.0% at UUT 2.5%, above it.

Checked against crafted files: a repeated group, a longer sequence containing it, a long
sequence that does not, and a database without long sequences are each reported correctly.
