# Live set of SC1 before and after the memory-run change

Kind: probe on the development machine. `mem/` runs `run_mem.sh` with commit 335fd3554 (flat
arrays dropped before every static mine in a memory run), recorded in `mem/run_meta.json`;
`mem-old/` runs the same script right after, on the same machine, with the classes of commit
750b18097 (see `mem-old/README.md`). HUSP-SP, whose code path did not change, differs by at
most 1 MB; Pre-HUSPM on TAFENG rises by 10 to 20 MB at the three later thresholds; patterns are
equal in 32 of 32 cells. Compared with PC-THAI's campaign values the control itself differs by
10 to 20%, so only the same-machine comparison says anything.
