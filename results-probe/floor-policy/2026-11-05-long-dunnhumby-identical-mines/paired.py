"""Operations where the method and the fixed lower threshold both mined with the same explored nodes
(the same mine, same floor, same data): the ratio of their mine times, per scenario and seed."""
import os
import pandas as pd, sys
root, db = sys.argv[1], sys.argv[2]
M, F = "Pre-HUSPM-Joint", "Pre-HUSPM-Joint-fixed-threshold"
for sc in ["SC8", "SC8P", "SC9", "SC10", "SC10H"]:
    paths = {a: f"{root}/results/campaign/{sc}/{db}/{a}/{sc}_long.csv" for a in (M, F)}
    missing = [a for a, q in paths.items() if not os.path.exists(q)]
    if missing:
        print(f"{sc:5} {db}: not measured for {', '.join(missing)}")
        continue
    r = {a: pd.read_csv(q, low_memory=False) for a, q in paths.items()}
    for seed in sorted(r[M].seed.unique()):
        m = r[M][(r[M].seed == seed) & (r[M].iteration > 0) & r[M].mine_ms.notna()].set_index("iteration")
        f = r[F][(r[F].seed == seed) & (r[F].iteration > 0) & r[F].mine_ms.notna()].set_index("iteration")
        j = m.join(f, lsuffix="_m", rsuffix="_f", how="inner")
        same = j[j.explored_nodes_m == j.explored_nodes_f]
        q = (same.mine_ms_m / same.mine_ms_f)
        ts = lambda d: f"{d.timestamp.min()} .. {d.timestamp.max()}"
        print(f"{sc:5} seed {seed:5}: both mined {len(j):4}, same nodes {len(same):4}; mine-time ratio median {q.median():.4f} "
              f"[q10 {q.quantile(.1):.4f}, q90 {q.quantile(.9):.4f}]; method {ts(r[M][r[M].seed==seed])}; fixed {ts(r[F][r[F].seed==seed])}")
