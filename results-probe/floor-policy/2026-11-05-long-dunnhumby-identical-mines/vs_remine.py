"""Each adaptive arm's mines against remining at the same operation and the same explored nodes:
lower-floor mines against Remine-from-scratch, threshold mines against Remine-own-UUT. Ratio of
mine time (adaptive) over runtime (remining), median per scenario over the three seeds; plus the
run window and the mean peak heap."""
import pandas as pd, sys
root, db = sys.argv[1], sys.argv[2]
arms = ["Pre-HUSPM-Joint", "Pre-HUSPM-Joint-w5", "Pre-HUSPM-Joint-fixed-threshold"]
for sc in ["SC8", "SC8P", "SC9", "SC10", "SC10H"]:
    try:
        R = {a: pd.read_csv(f"{root}/results/campaign/{sc}/{db}/{a}/{sc}_long.csv", low_memory=False)
             for a in arms + ["Remine-from-scratch", "Remine-own-UUT"]}
    except FileNotFoundError as e:
        print(sc, "missing", e.filename); continue
    line = []
    for a in arms:
        d = R[a].sort_values(["seed", "iteration"])
        d = d[(d.iteration > 0) & d.mine_ms.notna()]
        qs = {}
        for ref in ("Remine-from-scratch", "Remine-own-UUT"):
            j = d.merge(R[ref][R[ref].iteration > 0][["seed", "iteration", "explored_nodes", "runtime_ms"]],
                        on=["seed", "iteration"], suffixes=("", "_r"))
            j = j[j.explored_nodes == j.explored_nodes_r]
            qs[ref] = (len(j), (j.mine_ms / j.runtime_ms_r).median() if len(j) else float("nan"))
        line.append(f"{a.replace('Pre-HUSPM-Joint','M'):9} vs remine-low n={qs['Remine-from-scratch'][0]:4} "
                    f"{qs['Remine-from-scratch'][1]:.4f}, vs remine-UUT n={qs['Remine-own-UUT'][0]:4} {qs['Remine-own-UUT'][1]:.4f}, "
                    f"heap {R[a].peak_heap_mb.mean():.0f} MB, {R[a].timestamp.min()[5:16]}")
    print(sc); [print("   ", l) for l in line]
    for ref in ("Remine-from-scratch", "Remine-own-UUT"):
        print(f"    {ref:20} heap {R[ref].peak_heap_mb.mean():.0f} MB, {R[ref].timestamp.min()[5:16]}")
