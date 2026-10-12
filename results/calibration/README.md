# Calibration of the campaign

Kind: measurement on the measurement machine (`PC-THAI`, JDK 25.0.4.1+1-LTS Eclipse Adoptium,
`-Xmx48g` per child JVM), written by `.\campaign.ps1 -Calibrate` from commit `884290f`.
The rules were fixed before the run in `experiments.CampaignCalibration`.

| file | content |
|---|---|
| `agreement.log` | `verify.BaselineAgreement ALL`: every miner against USpan on `D_init` at the default threshold of all eight databases |
| `calibration.csv` | every calibration run: workload, variant, status, seconds and counters |
| `decision.txt` | the two settings and the numbers they rest on |
| `run.log` | the output of every child JVM, each opening with its platform line |
| `run.info` | host, commit, start and end; the JVM of each run is in its platform line in `run.log` |

Outcome:

- Agreement: 40 comparisons (five miners on eight databases), none mismatching, none out of heap.
- Item removal: `once`. Its total is within 2 percent of `fixpoint`, the tie rule.
- KOSARAK's floor ratio: 0.80. Both of its rule-2 mines finished at that floor.

The defaults in `PreHUSPMAdaptiveAlgo.IIP_DEFAULT` and `common.Datasets` follow this file.
