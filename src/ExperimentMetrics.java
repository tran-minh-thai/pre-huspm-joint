import java.util.StringJoiner;
import java.time.LocalDateTime;

/**
 * One CSV row per measured call. Column meanings, formulas and units are in
 * docs/result-columns.md.
 *
 * Notation: U(D) = TSU (total utility of the database); Su = relative high-utility threshold;
 *           Sl_min = r_l * Su (pre-large floor, r_l per database); uut = Su * U(D) (absolute
 *           high-utility threshold); B = an incremental batch.
 *
 * Measurement rules:
 *   - Each algorithm runs in its own scope after a full GC, so arms do not contaminate each other.
 *   - runtime_ms is wall-clock time of mining only (data loading excluded);
 *     -1 = failed, -2 = over the time limit, -3 = completed in live memory mode (timing invalid).
 *   - peak_heap_mb is the sampled peak heap minus the baseline, not the final delta.
 *   - Every column is measured or counted directly. Derived quantities (delta_D = uut_val -
 *     lut_dyn, speedup, throughput, skip ratio) are not stored; compute them from the raw columns.
 */
public class ExperimentMetrics {
    // 1. Identifier
    public String runId;
    public String timestamp = LocalDateTime.now().toString();
    public String algorithm;
    public String dataset;
    public int iteration;

    // 2. Performance Metrics
    public long runtimeMs;
    public long peakHeapMb;
    public long candidateCount;   // distinct extensions generated before pruning; each algorithm's own search
    public long exploredNodes;    // patterns actually materialised after pruning; measures pruning effectiveness

    // 3. Pruning counters
    //
    //   USpan       : prunedSwu (SWU width pruning), prunedDepth (depth bound)
    //   HUSP-ULL    : prunedSwu (1-items by SWU), prunedLar (look-ahead strategy, LAS),
    //                 prunedIip (items removed by IPS), prunedPeu (lists with PEU below threshold)
    //   HUSP-SP     : prunedSwu (root items by SWU), prunedIip (IIP), prunedLar (early pruning),
    //                 prunedPeu (Algorithm 3 PEU test)
    //   IncUSP-Miner+: prunedTsu (low-TSU extensions)
    //   Pre-HUSPM   : prunedPeu (candidates or buffered patterns below the bound)
    //   Adaptive    : prunedValueBound (PEU value bound), prunedIub (IUB branch pruning)
    public long prunedSwu, prunedDepth;          // USpan
    public long prunedLar, prunedIip;            // HUSP-ULL (besides prunedSwu)
    public long prunedTsu;                       // IncUSP-Miner+
    public long prunedPeu;                       // Pre-HUSPM
    public long prunedValueBound, prunedIub;     // Adaptive (PEU value bound, IUB branch pruning)

    // 4. Adaptive pre-large band and rescan state
    public double lutDynValue, cubValue;
    public boolean cubTriggeredRescan;
    public String opType;

    // 5. Number of high-utility patterns found
    public long totalPatternsFound;

    // 6. Pre-large count and absolute thresholds (Pre-HUSPM and Adaptive)
    //   prelargePatternCount = |PL|, the number of pre-large patterns. 0 for algorithms
    //                          without pre-large patterns (USpan, HUSP-ULL, IncUSP-Miner+).
    //   uutValue             = absolute high-utility threshold. Set by Adaptive only.
    //   sftValue             = absolute safety budget = uut - lutDyn for Adaptive; Pre-HUSPM
    //                          writes its own safety bound here.
    public long prelargePatternCount;
    public double uutValue;
    public double sftValue;
    // 7. Cumulative rescan count (Pre-HUSPM and Adaptive); 0 for algorithms without rescans.
    //    The main comparison metric for the incremental phase.
    public int rescanCount;
    // 8. Block index: blocks read and blocks skipped while evaluating patterns on an increment.
    //    Skip ratio = blocksSkipped / (blocksRead + blocksSkipped). Adaptive only; 0 elsewhere.
    public long blocksRead;
    public long blocksSkipped;
    // 9. Batch statistics of the adaptive band, raw (Adaptive only, set on insert/delete; 0 at init):
    //    sigma2 = variance of batch utility, rho = skew (swuMax / batch total).
    public double sigma2;
    public double rho;

    // 10. Run configuration, so every row can be traced to the parameters that produced it.
    //     seed        - seed for batch splitting and operation plans
    //     repeat      - repeat index of the same configuration
    //     blockSize   - block size of the block-level inverted index
    //     kappa       - adaptation coefficient
    //     delta0      - initial amplitude
    //     triggerRule - rescan trigger rule for insertions
    //     iauMax      - max_i A_i of the latest batch (the quantity the trigger compares)
    //     iauTightness- CUB / max_i A_i
    // 11. Memory measurement mode.
    //
    //   memMode = "used" (default): peak_heap_mb is the peak used heap sampled every 50 ms,
    //     totalMemory - freeMemory, including uncollected garbage. It reflects GC scheduling
    //     rather than the algorithm's memory need: it depends on -Xmx (a larger heap lets G1
    //     collect later, so the peak is higher) and on what ran earlier in the same JVM.
    //
    //   memMode = "live": forces a full collection at sampling points and reads used heap
    //     right after. memLiveMb = peak of those readings, memRetainedMb = the last reading.
    //     This is the live set, independent of GC scheduling, -Xmx and arm order.
    //     memLiveMb is a lower bound on the true live-set peak; the error is bounded by the
    //     sampling interval.
    //
    //   Forced GC invalidates timing, so a live-mode run must not be used for runtime.
    //   runtimeMs is written as -3 and the wall time goes to wallMsPerturbed, so it cannot be
    //   plotted as a performance figure by mistake.
    public String memMode = "used";
    public long memLiveMb;        // peak used heap read right after a forced collection
    public long memRetainedMb;    // used heap after the last forced collection
    public int  gcForced;         // number of forced collections (0 = final reading only)
    public long wallMsPerturbed;  // wall time of a live-mode run (perturbed by forced GC)
    /** Bytes allocated by the mining thread, in MB. Deterministic, independent of GC scheduling. */
    public long allocatedMb;

    public long seed;
    public int repeat;
    public int blockSize;
    public double kappa;
    public double delta0;
    public String triggerRule = "";
    public double iauMax;
    public double iauTightness;

    // Adaptation to work rather than to utility. repetition is the database's repetition
    // (occurrences per distinct item), the structural quantity that separates the cheap
    // databases from the expensive ones in this collection. sl_min_eff is the pre-large floor
    // ratio actually used, after any raise above the configured ratio, and rescan_retries
    // counts the raises; 0 means the mechanism did not intervene.
    public double repetition;
    public double slMinEff;
    public int rescanRetries;

    public String toCsvRow() {
        StringJoiner sj = new StringJoiner(",");
        sj.add(runId).add(timestamp).add(algorithm).add(dataset).add(String.valueOf(iteration));
        sj.add(String.valueOf(runtimeMs)).add(String.valueOf(peakHeapMb)).add(String.valueOf(candidateCount));
        sj.add(String.valueOf(exploredNodes)); // patterns materialised after pruning
        sj.add(String.valueOf(totalPatternsFound));
        // Pruning counters, grouped by algorithm:
        //   USpan       extras: prunedSwu + prunedDepth
        //   HUSP-ULL, HUSP-SP extras: prunedLar + prunedIip (+ prunedPeu)
        //   IncUSP+     : prunedTsu
        //   Pre-HUSPM   : prunedPeu
        //   Adaptive    : prunedValueBound + prunedIub
        sj.add(String.valueOf(prunedSwu)).add(String.valueOf(prunedDepth))
                .add(String.valueOf(prunedLar)).add(String.valueOf(prunedIip))
                .add(String.valueOf(prunedTsu))
                .add(String.valueOf(prunedPeu))
                .add(String.valueOf(prunedValueBound))
                .add(String.valueOf(prunedIub));
        sj.add(String.valueOf(lutDynValue)).add(String.valueOf(cubValue))
                .add(String.valueOf(cubTriggeredRescan)).add(opType);
        // Pre-large count and absolute thresholds. Later column groups are appended at the
        // end so the positions of earlier columns stay fixed.
        sj.add(String.valueOf(prelargePatternCount))
                .add(String.valueOf(uutValue))
                .add(String.valueOf(sftValue))
                .add(String.valueOf(rescanCount))
                .add(String.valueOf(blocksRead))      // block index
                .add(String.valueOf(blocksSkipped))   // block index
                .add(String.valueOf(sigma2))          // adaptive band, raw batch statistic
                .add(String.valueOf(rho));            // adaptive band, raw batch statistic
        // Memory mode and run configuration.
        sj.add(memMode).add(String.valueOf(memLiveMb)).add(String.valueOf(memRetainedMb))
                .add(String.valueOf(gcForced)).add(String.valueOf(wallMsPerturbed))
                .add(String.valueOf(allocatedMb));
        sj.add(String.valueOf(seed)).add(String.valueOf(repeat))
                .add(String.valueOf(blockSize))
                .add(String.valueOf(kappa)).add(String.valueOf(delta0))
                .add(triggerRule)
                .add(String.valueOf(iauMax)).add(String.valueOf(iauTightness))
                .add(String.valueOf(repetition)).add(String.valueOf(slMinEff))
                .add(String.valueOf(rescanRetries));
        return sj.toString();
    }

    public static String getHeader() {
        return "run_id,timestamp,algorithm,dataset,iteration," +
                "runtime_ms,peak_heap_mb,candidate_count,explored_nodes,total_patterns_found," +
                "pruned_swu,pruned_depth,pruned_lar,pruned_iip," +
                "pruned_tsu,pruned_peu," +
                "pruned_value,pruned_iub," +
                "lut_dyn,cub_val,cub_triggered,op_type," +
                "prelarge_count,uut_val,sft_val,rescan_count,blocks_read,blocks_skipped," +
                "sigma2,rho," +
                "mem_mode,mem_live_mb,mem_retained_mb,gc_forced,wall_ms_perturbed,allocated_mb," +
                "seed,repeat,block_size,kappa,delta0,trigger_rule,iau_max,iau_tightness," +
                "repetition,sl_min_eff,rescan_retries";
    }
}