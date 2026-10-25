package verify;

import algorithms.PreHUSPMAdaptiveAlgorithm.PreHUSPMAdaptiveAlgo;
import common.DatasetLoader;
import common.Datasets;
import common.Density;
import common.QItem;
import common.QItemset;
import common.Sequence;

import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Where the time of remining goes, against rescanning after every increment.
 *
 *   java -Xmx16g -Xss64m -cp build verify.RemineCostProbe --out DIR --db SIGN,C8T1S5I8N5K [--datasets datasets/]
 *
 * On the SC2 stream of each database (D_init, then the rest in BATCH_COUNT even increments) and
 * at the fixed lower threshold, in one JVM and three passes:
 *
 *   1. rescan  one instance of the proposal with the adaptive rescan off, so every increment
 *              rescans (the SC7 arm Pre-HUSPM-Joint-rescan-every-batch); runIncrementalMining timed;
 *   2. remine  a new instance per increment mining the whole current database with
 *              runInitialMining (the SC2 arm Remine-from-scratch); timed;
 *   4. runRemine  a new instance per increment calling runRemine, the call of the remining arms
 *              since the remining cost was measured, which leaves out what depends on the sequences alone;
 *   3. steps   the same as pass 2, but the calls runInitialMining makes are made one by one and
 *              timed each: the new instance, the block index over the whole database, the per-sequence
 *              remaining utilities and flat arrays, the scratch arrays, the pool warm-up, the density
 *              measure, and the mine; then the same mine is repeated on that instance, now warm,
 *              which is the mining cost without the setup of a new instance.
 *
 * Every increment of passes 2 and 3 is compared with pass 1: the mining cutoff (lutDynValue) and
 * the large and pre-large patterns with their utilities. Pass 3 is also compared with pass 2,
 * which checks that the step-by-step calls do what runInitialMining does.
 *
 * Timings from this probe are measured on whatever machine runs it; they explain where the time
 * goes and are not the measurements the paper reports.
 */
public final class RemineCostProbe {

    private static Field field(String name) throws ReflectiveOperationException {
        Field f = PreHUSPMAdaptiveAlgo.class.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    private static Method method(Class<?> c, String name, Class<?>... args) throws ReflectiveOperationException {
        Method m = c.getDeclaredMethod(name, args);
        m.setAccessible(true);
        return m;
    }

    /** The even split of the runner (splitByWeights with equal weights). */
    static List<List<Sequence>> evenBatches(List<Sequence> all, int k) {
        int n = all.size();
        int[] sizes = new int[k];
        int m = n - k;
        double[] frac = new double[k];
        int allocated = 0;
        for (int i = 0; i < k; i++) {
            double raw = (1.0 / k) * m;
            int f = (int) Math.floor(raw);
            sizes[i] = f; frac[i] = raw - f; allocated += f;
        }
        Integer[] order = new Integer[k];
        for (int i = 0; i < k; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> frac[a] != frac[b] ? Double.compare(frac[b], frac[a]) : Integer.compare(a, b));
        for (int r = 0; r < m - allocated; r++) sizes[order[r]]++;
        for (int i = 0; i < k; i++) sizes[i]++;
        List<List<Sequence>> out = new ArrayList<>();
        int idx = 0;
        for (int i = 0; i < k; i++) {
            int end = Math.min(n, idx + sizes[i]);
            out.add(new ArrayList<>(all.subList(idx, end)));
            idx = end;
        }
        return out;
    }

    static double sum(List<Sequence> db) {
        double t = 0;
        for (Sequence s : db) t += s.sequenceUtility;
        return t;
    }

    static PreHUSPMAdaptiveAlgo fresh(double su, double sl) {
        return new PreHUSPMAdaptiveAlgo(su, 0.5, 1e-4, sl, PreHUSPMAdaptiveAlgo.AblationMode.FULL, false);
    }

    /** The patterns an instance holds, in a canonical form for comparison. */
    static String patterns(PreHUSPMAdaptiveAlgo a) {
        return new TreeMap<>(a.exportLargeAsStrings()) + "|" + new TreeMap<>(a.exportPrelargeAsStrings());
    }

    static double ms(long t0) { return (System.nanoTime() - t0) / 1e6; }

    public static void main(String[] args) throws Exception {
        String out = null, dbs = "SIGN,C8T1S5I8N5K", dir = "datasets/";
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--out": out = args[++i]; break;
                case "--db": dbs = args[++i]; break;
                case "--datasets": dir = args[++i]; break;
                default: throw new IllegalArgumentException("unknown argument " + args[i]);
            }
        }
        if (out == null) throw new IllegalArgumentException("--out DIR is required");
        Files.createDirectories(Path.of(out));
        int k = Datasets.BATCH_COUNT;

        Field fMaxItem = field("maxItemId"), fMaxFlat = field("maxFlatLen"), fPrelarge = field("prelargeMap");
        Field fPool = field("trackerPool"), fListPool = field("trackerListPool");
        Field fWarmT = field("POOL_WARMUP_TRACKERS"), fWarmL = field("POOL_WARMUP_TRACKER_LISTS");
        Method mDbClear = method(PreHUSPMAdaptiveAlgo.class, "dbClear");
        Method mDbPut = method(PreHUSPMAdaptiveAlgo.class, "dbPut", int.class, Sequence.class);
        Method mAlloc = method(PreHUSPMAdaptiveAlgo.class, "allocateScratch", int.class);
        Method mEnsure = method(PreHUSPMAdaptiveAlgo.class, "ensureScratchCapacity", int.class);
        Method mMine = method(PreHUSPMAdaptiveAlgo.class, "mineWithBudget", List.class, double.class, double.class);
        Field fIndex = field("globalBlockIndex");
        Class<?> indexClass = fIndex.getType();
        Constructor<?> newIndex = indexClass.getDeclaredConstructor();
        newIndex.setAccessible(true);
        Field fIndexBlock = indexClass.getDeclaredField("blockSize");
        fIndexBlock.setAccessible(true);
        Method mBuild = method(indexClass, "buildFromBatch", List.class);
        Object pool0 = fPool.get(fresh(0.1, 0.8));
        Method mWarmT = method(pool0.getClass(), "warmup", int.class);
        Object lpool0 = fListPool.get(fresh(0.1, 0.8));
        Method mWarmL = method(lpool0.getClass(), "warmup", int.class);
        int warmT = fWarmT.getInt(null), warmL = fWarmL.getInt(null);

        int failures = 0, compared = 0;
        try (PrintWriter csv = new PrintWriter(Files.newBufferedWriter(Path.of(out, "remine_cost.csv"), StandardCharsets.UTF_8));
             PrintWriter remine4 = new PrintWriter(Files.newBufferedWriter(Path.of(out, "run_remine.csv"), StandardCharsets.UTF_8))) {
            remine4.println("dataset,increment,run_remine_ms,equals_rescan");
            csv.println("dataset,increment,sequences,rescan_ms,remine_ms,step_new_instance_ms,step_block_index_ms,"
                    + "step_sequences_ms,step_scratch_ms,step_pools_ms,step_density_ms,step_mine_ms,mine_again_warm_ms,"
                    + "cutoff_rescan,cutoff_remine,large,prelarge,remine_equals_rescan,steps_equal_rescan");
            for (String ds : dbs.split(",")) {
                List<Sequence> full = DatasetLoader.loadDataset(dir + ds + "_seq.txt", dir + ds + "_eui.txt");
                double su = Datasets.defaultUut(ds), sl = Datasets.slMinRatio(ds);
                int nInit = Datasets.dInitCount(ds, full.size());
                List<Sequence> dInit = new ArrayList<>(full.subList(0, nInit));
                List<List<Sequence>> batches = evenBatches(new ArrayList<>(full.subList(nInit, full.size())), k);
                System.out.printf("%s: %d sequences, D_init %d, %d increments, UUT %.6f, r_l %.2f%n",
                        ds, full.size(), nInit, batches.size(), su, sl);

                // Pass 1: rescan after every increment, one instance.
                int n = batches.size();
                double[] pMs = new double[n], pCut = new double[n];
                String[] pPat = new String[n];
                int[] large = new int[n], pre = new int[n];
                PreHUSPMAdaptiveAlgo p = fresh(su, sl);
                p.useAdaptiveRescan = false;
                p.runInitialMining(dInit, sum(dInit));
                List<Sequence> soFar = new ArrayList<>(dInit);
                double total = sum(dInit);
                int rescans = 0;
                for (int b = 0; b < n; b++) {
                    List<Sequence> delta = batches.get(b);
                    total += sum(delta);
                    List<Sequence> snap = new ArrayList<>(soFar);
                    snap.addAll(delta);
                    int before = p.rescanCount;
                    long t0 = System.nanoTime();
                    p.runIncrementalMining(delta, snap, total);
                    pMs[b] = ms(t0);
                    rescans += p.rescanCount - before;
                    pCut[b] = p.lutDynValue;
                    pPat[b] = patterns(p);
                    large[b] = p.patternCount; pre[b] = p.prelargeCount;
                    soFar.addAll(delta);
                }
                System.out.printf("  pass 1 (rescan after every increment): %.0f ms over %d increments, %d rescans%n",
                        Arrays.stream(pMs).sum(), n, rescans);
                p = null;
                System.gc();

                // Pass 2: remine with a new instance per increment, as the runner does.
                double[] rMs = new double[n], rCut = new double[n];
                boolean[] rEq = new boolean[n];
                soFar = new ArrayList<>(dInit);
                total = sum(dInit);
                for (int b = 0; b < n; b++) {
                    List<Sequence> delta = batches.get(b);
                    total += sum(delta);
                    soFar.addAll(delta);
                    final List<Sequence> snap = new ArrayList<>(soFar);
                    PreHUSPMAdaptiveAlgo r = fresh(su, sl);
                    long t0 = System.nanoTime();
                    r.runInitialMining(snap, total);
                    rMs[b] = ms(t0);
                    rCut[b] = r.lutDynValue;
                    rEq[b] = rCut[b] == pCut[b] && patterns(r).equals(pPat[b]);
                }
                System.out.printf("  pass 2 (remine, new instance per increment): %.0f ms%n", Arrays.stream(rMs).sum());
                System.gc();

                // Pass 3: the calls of runInitialMining one by one, then the same mine again, warm.
                soFar = new ArrayList<>(dInit);
                total = sum(dInit);
                double[] sum = new double[9];
                for (int b = 0; b < n; b++) {
                    List<Sequence> delta = batches.get(b);
                    total += sum(delta);
                    soFar.addAll(delta);
                    List<Sequence> snap = new ArrayList<>(soFar);
                    double[] st = new double[8];

                    long t0 = System.nanoTime();
                    PreHUSPMAdaptiveAlgo r = fresh(su, sl);
                    st[0] = ms(t0);

                    t0 = System.nanoTime();
                    mDbClear.invoke(r);
                    Object index = newIndex.newInstance();
                    fIndexBlock.setInt(index, r.blockSize);
                    fIndex.set(r, index);
                    mBuild.invoke(index, snap);
                    st[1] = ms(t0);

                    t0 = System.nanoTime();
                    int maxFlat = fMaxFlat.getInt(r), maxItem = fMaxItem.getInt(r);
                    for (Sequence s : snap) {
                        s.calculateRemainingUtilities();
                        s.buildFlatRepresentation();
                        if (s.flatLen > maxFlat) maxFlat = s.flatLen;
                        mDbPut.invoke(r, s.id, s);
                        for (QItemset is : s.itemsets) for (QItem it : is.items) if (it.id > maxItem) maxItem = it.id;
                    }
                    fMaxFlat.setInt(r, maxFlat);
                    fMaxItem.setInt(r, maxItem);
                    st[2] = ms(t0);

                    t0 = System.nanoTime();
                    mAlloc.invoke(r, maxItem + 1);
                    mEnsure.invoke(r, maxFlat);
                    st[3] = ms(t0);

                    t0 = System.nanoTime();
                    mWarmT.invoke(fPool.get(r), warmT);
                    mWarmL.invoke(fListPool.get(r), warmL);
                    st[4] = ms(t0);

                    t0 = System.nanoTime();
                    r.repetition = Density.repetition(snap);
                    st[5] = ms(t0);

                    double uut = total * su;
                    r.uutValue = uut;
                    r.lutDynValue = total * r.Sl_min;
                    r.sftValue = uut - r.lutDynValue;
                    t0 = System.nanoTime();
                    mMine.invoke(r, snap, uut, total);
                    st[6] = ms(t0);
                    r.tsuAtLastRescan = total;
                    String stepPat = patterns(r);
                    boolean stepsEq = r.lutDynValue == pCut[b] && stepPat.equals(pPat[b]);

                    r.largeMap.clear(); ((Map<?, ?>) fPrelarge.get(r)).clear(); r.candidateCount = 0;
                    t0 = System.nanoTime();
                    mMine.invoke(r, snap, uut, total);
                    st[7] = ms(t0);
                    stepsEq &= patterns(r).equals(pPat[b]);

                    for (int i = 0; i < 8; i++) sum[i] += st[i];
                    compared++;
                    if (!rEq[b] || !stepsEq) failures++;
                    csv.printf("%s,%d,%d,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.6f,%.6f,%d,%d,%b,%b%n",
                            ds, b + 1, snap.size(), pMs[b], rMs[b], st[0], st[1], st[2], st[3], st[4], st[5], st[6], st[7],
                            pCut[b], rCut[b], large[b], pre[b], rEq[b], stepsEq);
                }
                System.out.printf("  pass 3 steps, summed over the increments (ms): new instance %.0f, block index %.0f, "
                        + "sequences %.0f, scratch %.0f, pools %.0f, density %.0f, mine %.0f; same mine again, warm %.0f%n",
                        sum[0], sum[1], sum[2], sum[3], sum[4], sum[5], sum[6], sum[7]);
                System.gc();

                // Pass 4: runRemine, the remining arms' call, with a new instance per increment.
                soFar = new ArrayList<>(dInit);
                total = sum(dInit);
                double rrMs = 0;
                int rrEqual = 0;
                for (int b = 0; b < n; b++) {
                    List<Sequence> delta = batches.get(b);
                    total += sum(delta);
                    soFar.addAll(delta);
                    final List<Sequence> snap = new ArrayList<>(soFar);
                    PreHUSPMAdaptiveAlgo r = fresh(su, sl);
                    long t0 = System.nanoTime();
                    r.runRemine(snap, total);
                    double t = ms(t0);
                    rrMs += t;
                    boolean eq = r.lutDynValue == pCut[b] && patterns(r).equals(pPat[b]);
                    rrEqual += eq ? 1 : 0;
                    compared++;
                    if (!eq) failures++;
                    remine4.printf("%s,%d,%.3f,%b%n", ds, b + 1, t, eq);
                }
                System.out.printf("  pass 4 (runRemine, new instance per increment): %.0f ms, %d of %d increments equal to pass 1%n",
                        rrMs, rrEqual, n);
                System.gc();
            }
        }
        System.out.printf("increments compared with pass 1: %d, differing in cutoff or patterns: %d%n", compared, failures);
        if (failures > 0) System.exit(1);
    }
}
