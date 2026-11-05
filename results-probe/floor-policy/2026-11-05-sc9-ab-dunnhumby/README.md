# Probe: is the method slower than the fixed lower threshold on the same mines? (SC9, DUNNHUMBY)

Kind: probe on the development machine (Apple M5, 32 GB). Its times decide whether a replicate run on
the measurement machine is worth making; they are not measurements.

Why: on the measurement machine, SC9/DUNNHUMBY, the two arms make almost the same rescans (666 and 663,
6 at the threshold) and explore 1.0023 times the nodes, yet the method took 1.089 times as long. On
mines with the same operation and the same explored nodes its mine time was 1.054 to 1.104 times the
fixed arm's (median per seed). One run per seed, the method's runs always first.

Hypothesis, written before the runs: if the method carries an overhead of its own, then on identical
mines its median mine time over the fixed arm's is at least 1.05 in every pairing of a method run with a
fixed run, whatever their order. If the ratios lie on both sides of 1, or follow the order, the
difference measured on the measurement machine is not attributable to the method.

Design: seed 1337 only (-Dseed.index=1), one arm per JVM, six runs in the order
fixed, method, method, fixed, fixed, method, so that a drift in time cancels. Cuts against the
campaign: heap ceiling 16 GB instead of 48 GB (the arms peak near 61 MB), one seed of three, the
development machine.

Run: `sh results-probe/floor-policy/2026-11-05-sc9-ab-dunnhumby/run_ab.sh` from the repository root.

## Result (analysis/console.txt)

Every run reproduces the counters of the campaign's run of the same arm and seed (rescans, rescans at
the threshold, explored nodes, patterns, per operation): 6/6. The runs therefore make the same search
as on the measurement machine.

Time of the operations: fixed lower threshold 234.684, 192.591, 202.895 s (runs 1, 4, 5); method
221.270, 223.584, 216.480 s (runs 2, 3, 6). The fixed arm alone spans 1.219 times from its fastest to its
slowest run; the method's runs span 1.033.

Identical mines (220 per pairing), median ratio method over fixed, over the nine pairings: 0.9172 to
1.1723; below 1 in the three pairings with run 1, above 1.03 in the six others.

Against the hypothesis: rejected as stated (not at least 1.05 in every pairing). The probe does not
exclude an overhead of a few percent either: the method is slower than the fixed arm's runs 4 and 5 in
every pairing. One run per arm cannot resolve a difference of this size, since two JVMs of the same arm
differ by up to 1.219 times here.
