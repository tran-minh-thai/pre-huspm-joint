# Ranges of the unit profits and quantities

Counts on the data files, written by `analysis/utility_ranges.py`; no timing. `run_meta.json`
holds the command and the commit, `console.txt` the output, `utility_ranges.csv` one row per
database.

The model assumes a unit profit p(i) > 0 for every item and a quantity q > 0 for every
occurrence. The tool reads the files the way `common.DatasetLoader` does and counts every
value that breaks the assumption, together with two cases the loader would not report: an
occurrence of an item that has no unit profit (the loader gives it a profit of 0) and a token
it cannot parse (the loader drops it).

On all nine databases every count is zero. Unit profits lie in [1, 725] on the seven
databases with generated utilities, in [4, 8990] on TAFENG and in [0.0023, 499.99] on
DUNNHUMBY; quantities lie in [1, 10] on the seven, in [1, 1200] on TAFENG and in [1, 30080]
on DUNNHUMBY, all integers.

Checked against crafted files: a profit of 0, a quantity of 0, a fractional quantity, an item
without a profit and an unparsable token are each counted, and a clean file passes.
