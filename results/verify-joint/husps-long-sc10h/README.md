# Refuted design: SC10H with heavy batches of ten times the utility

This record ran at 2ecbd83be, where every tenth batch of SC10H inserted the next
sequences with their utilities multiplied by ten. At the low floor the scaled
sequences made the pattern set explode: arms ran out of memory (for example
Pre-HUSPM-Joint-w5 on C8T1S5I8N5K at operation 29), so the reference arm left
571 rows without a reference (15920 comparisons, 0 mismatching).

The design was replaced in 5a2d99ce1: a heavy batch inserts ten times as many
sequences of the file, with their utilities unchanged. The checks of that design
are `husps-long-sc10h-sequences-<database>` (6012 comparisons each on
C8T1S5I8N5K, LEVIATHAN and TAFENG, 0 mismatching, 0 without a reference).
Nothing in the paper reads this record.
