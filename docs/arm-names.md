# Arm names

Until commit 12db2815 the proposal's arms carried historical names. They were renamed to their roles
in the code, in `campaign.ps1`, in the analysis and in the result trees `results/campaign*`
(directories and the `algorithm` and `run_id` columns), by `analysis/rename_arms.py`, which holds the
table below and checks that every rewritten file maps back to its original bytes.

| scenario | old name | new name | role |
|---|---|---|---|
| SC2, SC5, SC6, SC6D, SC6B | `Adaptive-FloorSelect` | `Pre-HUSPM-Joint` | the method: maintenance with the rescan threshold selection, window 5 |
| SC2, SC5, SC6, SC6D, SC6B | `Adaptive-FloorSelect-w<n>` | `Pre-HUSPM-Joint-w<n>` | the selection with window n (sensitivity study) |
| SC2, SC5, SC6, SC6D, SC6B | `Adaptive-Full` | `Pre-HUSPM-Joint-fixed-threshold` | the method at the fixed lower threshold, an ablation row |
| SC1, SC3, SC4 | `Adaptive-Full` | `Pre-HUSPM-Joint` | the method's static mine |
| SC4 (rows) | `Adaptive[sl=<r>]` | `Pre-HUSPM-Joint[sl=<r>]` | the static mine at lower threshold ratio r |
| SC7 | `proposed_full` | `Pre-HUSPM-Joint` | static mine, every pruning on |
| SC7 | `proposed_no_IUB` | `Pre-HUSPM-Joint-no-IUB` | static mine without the IUB pruning |
| SC7 | `proposed_no_IPS` | `Pre-HUSPM-Joint-no-IPS` | static mine without irrelevant item pruning |
| SC7 | `proposed_C5_on` | `Pre-HUSPM-Joint-rescan-rule` | incremental, fixed lower threshold, joint rescan rule |
| SC7 | `proposed_C5_off` | `Pre-HUSPM-Joint-rescan-every-batch` | incremental, fixed lower threshold, a rescan at every increment |
| SC7 | `proposed_no_block_index` | `Pre-HUSPM-Joint-no-block-index` | incremental, fixed lower threshold, no block index |
| SC7 | `proposed_bsz_<n>` | `Pre-HUSPM-Joint-bsz-<n>` | incremental, fixed lower threshold, blocks of n sequences |

The logs (`run.log`, `run.err`) and the DONE files of the renamed runs, and the records of
`results/verify-joint`, `results/invariant-*` and `results/v1-submitted`, are kept as written; they name
the arms by their old names. The analysis maps those names where it reads them
(`rename_arms.new_name`). The keys of `paper_numbers.csv` that named an arm follow the new names; every
value is unchanged.
