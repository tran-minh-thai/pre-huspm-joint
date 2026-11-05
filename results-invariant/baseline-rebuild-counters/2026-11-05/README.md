# Counters of the baselines whose timed code changed

Kind: invariant check. Only the counters (patterns, explored nodes, candidates, rescans,
pre-large patterns) are the result here; they do not depend on the machine. The runtime and
memory columns of `run/` were taken on the development machine and are not measurements.

`run_all.sh` reruns USpan, IncUSP-Miner+ and Pre-HUSPM (SC1 on SIGN, LEVIATHAN, TAFENG,
C8T1S5I8N5K, BIBLE; SC2 Pre-HUSPM-SWUmax, Pre-HUSPM-TSU, IncUSP-Miner+ on SIGN, LEVIATHAN,
TAFENG; SC4 and SC7 Pre-HUSPM on SIGN) with the code that drops the cached per-sequence data
before their timed calls (commit b65e0eaf4); `run/run_meta.json` records the command.
`compare.py run results/campaign` compares them with the campaign: `compare.txt`, 26 files,
974 rows, 0 differing values. With a fault injected into the sequence utility (0.5 times the
sum), the same comparison fails on 3 of 3 files with 818 differing values.
