#!/usr/bin/env python3
"""Generate the two verification-only variants of the baselines with the bound as published.

    python3 tools/make_published_bound_variants.py           # write src/verify/*AsPublished.java
    python3 tools/make_published_bound_variants.py --check   # exit 1 unless the files in src/verify
                                                             # equal what this script generates

The baselines in src/algorithms replace two published bounds that are not upper bounds: the TRSU
of HUSP-SP (Zhang et al., TKDD 18(1), Definition 4.11) and the depth-pruning bound of USpan (Yin
et al., KDD 2012, Theorem 2, Eq. (21)). verify.BaselineExhaustiveCheck counts what the published
bounds miss. The variants are generated from the measured classes rather than edited by hand, so
that the measured classes stay byte for byte as they are and each variant differs from its source
only in the class name, the package and the one block replaced below. Each block must occur exactly
once in the source, or the script stops.
"""
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

HUSPSP_OLD = '''    private void trsuScan(SeqPro sp) {
        SeqArray sa = sp.sa;
        int p1 = sp.ex[0];
        int ptr = 0;
        double runMaxS = NONE;            // max acu over instances in earlier itemsets
        double pendAcu = NONE;            // the latest instance before q...
        int pendElem = -1;                // ...and its itemset
        for (int q = p1 + 1; q < sa.len; q++) {
            int it = sa.item[q];
            if (deleted[it]) continue;
            while (ptr < sp.size && sp.ex[ptr] < q) {
                if (pendAcu > runMaxS) runMaxS = pendAcu;
                pendAcu = sp.acu[ptr];
                pendElem = sa.elemStart[sp.ex[ptr]];
                ptr++;
            }
            double bestS = runMaxS, bestI = NONE;
            if (pendElem == sa.elemStart[q]) bestI = pendAcu;
            else if (pendAcu > bestS) bestS = pendAcu;
            double tail = sa.remUtil[q - 1];  // u(S/(q-1)): utility from q onwards
            if (bestI != NONE || bestS != NONE) {
                if (seqMaxI[it] == NONE && seqMaxS[it] == NONE) seqTouched.add(it);
                if (bestI != NONE && bestI + tail > seqMaxI[it]) seqMaxI[it] = bestI + tail;
                if (bestS != NONE && bestS + tail > seqMaxS[it]) seqMaxS[it] = bestS + tail;
            }
        }
'''

HUSPSP_NEW = '''    private void trsuScan(SeqPro sp) {
        // TRSU as published (Definition 4.11). With t the prefix, p_1 its first extension position
        // and q the first extension position of t+i in S: when PEU(t,S) is reached at p_1,
        // TRSU(t+i,S) = PEU(t,S) - [u(S/p_i) - u(S/(q-1))], else RSU(t+i,S) = PEU(t,S). p_i is read
        // as in the authors' code (get_TRSU): the last extension position of t before q. PEU(t,S)
        // and the remaining utilities are those after the irrelevant items of this node are removed.
        SeqArray sa = sp.sa;
        double peu = NONE, atP1 = NONE;
        for (int j = 0; j < sp.size; j++) {
            double v = sp.acu[j] + sa.remUtil[sp.ex[j]];
            if (j == 0) atP1 = v;
            if (v > peu) peu = v;
        }
        boolean reachedAtP1 = atP1 >= peu;
        int p1 = sp.ex[0], e1 = sa.elemStart[p1];
        int ptr = 0, last = -1;
        for (int q = p1 + 1; q < sa.len; q++) {
            int it = sa.item[q];
            if (deleted[it]) continue;
            while (ptr < sp.size && sp.ex[ptr] < q) last = sp.ex[ptr++];
            boolean iExt = last >= 0 && sa.elemStart[last] == sa.elemStart[q];
            boolean sExt = sa.elemStart[q] > e1;
            if (!iExt && !sExt) continue;
            double gap = reachedAtP1 ? Math.max(0, sa.remUtil[last] - sa.remUtil[q - 1]) : 0;
            double v = peu - gap;
            boolean firstI = iExt && seqMaxI[it] == NONE, firstS = sExt && seqMaxS[it] == NONE;
            if ((firstI || firstS) && seqMaxI[it] == NONE && seqMaxS[it] == NONE) seqTouched.add(it);
            if (firstI) seqMaxI[it] = v;     // only the first extension position q of t+i counts
            if (firstS) seqMaxS[it] = v;
        }
'''

USPAN_OLD = '''            for (Instance inst : pSeq.instances) {
                // Boundary check before getRestUtility (remainingUtilities.get(itemsetIdx).get(itemIdx))
                if (!inBounds(pSeq.seq, inst.itemsetIdx, inst.itemIdx)) continue;
                // O(1) getRestUtility from common.Sequence
                double ub = inst.utility + pSeq.seq.getRestUtility(inst.itemsetIdx, inst.itemIdx);
                if (ub > seqMaxUB) seqMaxUB = ub;
            }
'''

USPAN_NEW = '''            for (Instance inst : pSeq.instances) {
                // Eq. (21) as published: u_rest(i,s) + u(s') at the pivot i, the first position where a
                // match of t ends (the instances are in increasing itemset order), with u(s') read as the
                // largest utility of a match ending there, the larger of the two readings.
                if (!inBounds(pSeq.seq, inst.itemsetIdx, inst.itemIdx)) continue;
                seqMaxUB = inst.utility + pSeq.seq.getRestUtility(inst.itemsetIdx, inst.itemIdx);
                break;
            }
'''

VARIANTS = [
    ("src/algorithms/HUSPSPAlgorithm.java", "src/verify/HUSPSPAsPublished.java",
     "HUSPSPAlgorithm", "HUSPSPAsPublished", HUSPSP_OLD, HUSPSP_NEW),
    ("src/algorithms/USpanAlgorithm.java", "src/verify/USpanAsPublished.java",
     "USpanAlgorithm", "USpanAsPublished", USPAN_OLD, USPAN_NEW),
]

HEADER = ("// GENERATED by tools/make_published_bound_variants.py from {src}; do not edit.\n"
          "// Verification only: the bound of the paper as published, which is not an upper bound.\n")


def generate(src, old_name, new_name, old, new):
    text = open(os.path.join(ROOT, src), encoding="utf-8").read()
    n = text.count(old)
    if n != 1:
        raise SystemExit(f"{src}: the block to replace occurs {n} times, expected 1")
    if text.count("package algorithms;") != 1:
        raise SystemExit(f"{src}: expected one 'package algorithms;'")
    out = text.replace(old, new).replace("package algorithms;", "package verify;", 1)
    out = out.replace(f"class {old_name}", f"class {new_name}").replace(f"public {old_name}(", f"public {new_name}(")
    return HEADER.format(src=src) + out


def main():
    check = "--check" in sys.argv[1:]
    bad = 0
    for src, dst, a, b, old, new in VARIANTS:
        text = generate(src, a, b, old, new)
        path = os.path.join(ROOT, dst)
        if check:
            same = os.path.exists(path) and open(path, encoding="utf-8").read() == text
            print(f"{'equal  ' if same else 'DIFFERS'} {dst}")
            bad += not same
        else:
            with open(path, "w", encoding="utf-8", newline="\n") as f:
                f.write(text)
            print(f"written {dst}")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
