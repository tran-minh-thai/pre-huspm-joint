#!/usr/bin/env python3
"""Brute-force check of the joint rule's discard floors and of the two tightness constructions, on
small random streams, every quantity recomputed from the definitions (independent of src/).

    python3 analysis/joint_rule_tightness.py [--streams N] [--regimes light,heavy]

A sequence is a tuple of elements, an element a tuple of (item, utility) pairs with distinct
items; a pattern is a tuple of frozensets; u(X, s) is the largest summed utility over the
occurrences of X in s. Utilities are Fractions, so ties are exact. A rescan tracks every pattern
of the database with u >= L_ref = lambda * TU_ref; lambda is drawn at random from {LUT_min, UUT}
at every rescan, as the threshold selection may choose either.

1. Exactness of the floors. After every operation the reported set (tracked patterns with
   u >= UUT * TU) must equal the HUSPs of the database, under the floors L_ref (plain),
   L_ref + min_{i in X} A_i (min) and L_ref + max_{i in X} A_i (max). Two faulty floors must be
   caught in at least one regime: L_ref + max_i A_i over all items, and the max floor plus one
   unit of utility.
2. Tightness of the trigger. At every state with Phi > beta and U- <= (1 - lambda) TU_ref, another
   reference database and stream are built that give the same information (TU_ref; for each
   insertion its elements, items and utility; for each deletion its utility) and in which <(i*)>,
   i* = argmax A_i, is below L_ref at the reference and a HUSP now. At states with Phi <= beta
   the same construction must never succeed.
3. Tightness of the max floor. At every state without rescan, for a sample of tracked patterns X
   with u(X) >= L_ref + max_{i in X} A_i, a continuation of insertions (single fresh items, then
   copies of X) keeps Phi <= beta after every insertion and ends with X a HUSP. Patterns below the
   max floor must never be made HUSPs this way. States with no insertion since the rescan and
   Phi = beta are excluded: every operation there rescans.

Self-test: three faults in the checker itself must be caught (the trigger construction without
the redistribution of utility, the floor construction without filler, and the min floor taken as
the boundary). Exit status 0 when every claim holds on a non-empty set of tested cases and every
fault is caught, 1 otherwise.
"""
import argparse
import itertools
import random
import sys
from fractions import Fraction as F

FRESH = 1000  # items >= FRESH never occur in the random streams

REGIMES = {
    # small databases, heavy batches: most operations rescan
    "heavy": dict(alphabet=4, db=(3, 6), batch=(1, 2), uut=(1, 2, 3)),
    # larger databases, single-sequence batches over more items: most operations are absorbed
    "light": dict(alphabet=6, db=(14, 20), batch=(1, 1), uut=(2, 3, 4)),
}
STEPS = 12


def occurrences(X, s):
    out = []

    def rec(j, start, acc):
        if j == len(X):
            out.append(tuple(acc))
            return
        for k in range(start, len(s)):
            if X[j] <= {i for i, _ in s[k]}:
                rec(j + 1, k + 1, acc + [k])
    rec(0, 0, [])
    return out


def u_seq(X, s):
    best = None
    for occ in occurrences(X, s):
        v = sum(q for j, k in enumerate(occ) for i, q in s[k] if i in X[j])
        best = v if best is None or v > best else best
    return best


def u_db(X, db):
    return sum((u_seq(X, s) or 0) for s in db)


def useq(s):
    return sum(q for e in s for _, q in e)


def subpatterns(s):
    pats = set()
    for r in range(1, len(s) + 1):
        for ks in itertools.combinations(range(len(s)), r):
            choices = []
            for k in ks:
                items = [i for i, _ in s[k]]
                choices.append([frozenset(c) for m in range(1, len(items) + 1)
                                for c in itertools.combinations(items, m)])
            for combo in itertools.product(*choices):
                pats.add(tuple(combo))
    return pats


def items_of(X):
    return set().union(*X)


def rand_seq(rng, alphabet, max_el=3, max_it=2, max_u=5):
    s = []
    for _ in range(rng.randint(1, max_el)):
        its = rng.sample(alphabet, rng.randint(1, min(max_it, len(alphabet))))
        s.append(tuple((i, F(rng.randint(1, max_u))) for i in sorted(its)))
    return tuple(s)


def husps(db, threshold):
    universe = set()
    for s in db:
        universe |= subpatterns(s)
    return {X for X in universe if u_db(X, db) >= threshold}


class Run:
    def __init__(self, uut, lut_min, floor):
        self.uut, self.lut_min, self.floor = uut, lut_min, floor

    def rescan(self, db, lam):
        self.lam = lam
        self.tu_ref = sum(useq(s) for s in db)
        self.l_ref = lam * self.tu_ref
        self.A, self.up, self.um = {}, F(0), F(0)
        universe = set()
        for s in db:
            universe |= subpatterns(s)
        self.tracked = {X: v for X in universe for v in [u_db(X, db)] if v >= self.l_ref}

    def floor_of(self, X):
        a = [self.A.get(i, F(0)) for i in items_of(X)]
        return {"plain": self.l_ref, "min": self.l_ref + min(a), "max": self.l_ref + max(a),
                "maxplus": self.l_ref + max(a) + 1,
                "globalmax": self.l_ref + max(self.A.values(), default=F(0))}[self.floor]

    def phi(self):
        return max(self.A.values(), default=F(0)) - self.uut * self.up + self.uut * self.um

    def beta(self):
        return (self.uut - self.lam) * self.tu_ref

    def insert(self, db, s):
        db.append(s)
        self.up += useq(s)
        for i in {i for e in s for i, _ in e}:
            self.A[i] = self.A.get(i, F(0)) + useq(s)


def stream(seed, regime):
    cfg = REGIMES[regime]
    rng = random.Random(seed)
    alphabet = list(range(1, cfg["alphabet"] + 1))
    uut = F(rng.choice(cfg["uut"]), 10)
    lut_min = uut * F(rng.choice([1, 2, 3]), 4)
    db = [rand_seq(rng, alphabet) for _ in range(rng.randint(*cfg["db"]))]
    return rng, alphabet, uut, lut_min, db, cfg


def check_floor(seed, floor, regime):
    """Mismatching states of one stream under one floor."""
    rng, alphabet, uut, lut_min, db, cfg = stream(seed, regime)
    run = Run(uut, lut_min, floor)
    run.rescan(db, lut_min)
    st = dict(states=0, mismatches=0, discards=0, beyond_min=0)
    for _ in range(STEPS):
        if db and rng.random() < 0.35:
            for _ in range(min(len(db) - 1, rng.randint(1, 2))):
                run.um += useq(db.pop(rng.randrange(len(db))))
        else:
            for _ in range(rng.randint(*cfg["batch"])):
                run.insert(db, rand_seq(rng, alphabet))
        tu = sum(useq(s) for s in db)
        if run.phi() > run.beta():
            run.rescan(db, rng.choice([lut_min, uut]))
        else:
            new = {}
            for X in run.tracked:
                v = u_db(X, db)
                if v >= run.floor_of(X):
                    new[X] = v
                else:
                    st["discards"] += 1
                    st["beyond_min"] += v >= run.l_ref + min(run.A.get(i, F(0)) for i in items_of(X))
            run.tracked = new
        st["states"] += 1
        st["mismatches"] += {X for X, v in run.tracked.items() if v >= uut * tu} != husps(db, uut * tu)
    return st


def info_insert(s):
    return (tuple(tuple(i for i, _ in e) for e in s), useq(s))


def construct_trigger(run, inserted, deleted_u, current_tu, fault=None):
    """True/False: the construction gives an untracked HUSP; None: outside U- <= (1 - lambda) TU_ref."""
    istar = max(run.A, key=lambda i: (run.A[i], -i))
    margin = run.l_ref + run.A[istar] - run.uut * current_tu
    if sum(deleted_u) > run.tu_ref - run.l_ref:
        return None
    delta = min(margin / 4, run.l_ref / 2) if margin > 0 else run.l_ref / 2
    occs = sum(len(e) for s in inserted for e in s) + 1
    eps = margin / (4 * occs) if margin > 0 else F(1, 10 ** 6)
    ref = [(((istar, run.l_ref - delta),),)]
    fillers = [(((FRESH, du),),) for du in deleted_u]
    rest = run.tu_ref - (run.l_ref - delta) - sum(deleted_u)
    kept = [(((FRESH, rest),),)] if rest > 0 else []
    assert sum(useq(s) for s in ref + fillers + kept) == run.tu_ref
    new_ins = []
    for s in inserted:
        if fault != "no-redistribution" and any(i == istar for e in s for i, _ in e):
            n_other, first, t = sum(len(e) for e in s) - 1, True, []
            for e in s:
                el = []
                for i, _ in e:
                    el.append((i, useq(s) - eps * n_other) if (i == istar and first) else (i, eps))
                    first = first and i != istar
                t.append(tuple(el))
            new_ins.append(tuple(t))
        else:
            new_ins.append(s)
    assert [info_insert(a) for a in new_ins] == [info_insert(b) for b in inserted]
    X = (frozenset([istar]),)
    if not u_db(X, ref + fillers + kept) < run.l_ref:
        return False
    db = ref + kept + new_ins  # each deletion removed one of the fillers
    assert sum(useq(s) for s in db) == current_tu
    return u_db(X, db) >= run.uut * current_tu


def construct_floor(run, db, X, fault=None):
    """True/False: the continuation makes X a HUSP with Phi <= beta throughout; None: excluded state."""
    uut = run.uut
    A = dict(run.A)
    a_x = max(A.get(i, F(0)) for i in items_of(X))
    M = max(A.values(), default=F(0))
    if M > 0:
        piece = M  # a fresh item's counter never exceeds the current maximum
    else:
        slack = run.beta() - run.phi()
        if slack <= 0:
            return None
        piece = slack / (2 * (1 - uut))
    tu = sum(useq(s) for s in db)
    g = max(F(0), max(M, piece) - a_x, (uut * tu - run.l_ref - a_x) / (1 - uut)) + 1
    f = (run.l_ref + a_x + g) / uut - tu - g
    assert f >= 0
    seqs, k, left = [], 0, (F(0) if fault == "no-filler" else f)
    while left > 0:
        p = min(piece, left)
        seqs.append((((FRESH + 1 + k, p),),))
        k, left = k + 1, left - p
    n_x, left = sum(len(e) for e in X), g
    while left > 0:
        p = min(piece, left)
        seqs.append(tuple(tuple((i, p / n_x) for i in sorted(e)) for e in X))
        left -= p
    db2, up, worst = list(db), run.up, None
    for s in seqs:  # one insertion per sequence, counters recomputed from the definition
        db2.append(s)
        up += useq(s)
        for i in {i for e in s for i, _ in e}:
            A[i] = A.get(i, F(0)) + useq(s)
        slack = max(A.values()) - uut * up + uut * run.um - run.beta()
        worst = slack if worst is None or slack > worst else worst
    for s in seqs[k:]:
        assert u_seq(X, s) == useq(s)
    return u_db(X, db2) >= uut * sum(useq(s) for s in db2) and worst <= 0


def check_tightness(n, regime, fault_t=None, fault_f=None, boundary="max"):
    c = dict(t=0, t_ok=0, t_neg=0, t_neg_ok=0, t_out=0, f=0, f_ok=0, f_neg=0, f_neg_ok=0, f_out=0)
    for seed in range(n):
        rng, alphabet, uut, lut_min, db, cfg = stream(seed, regime)
        run = Run(uut, lut_min, "max")
        run.rescan(db, lut_min)
        inserted, deleted_u = [], []
        for _ in range(STEPS):
            if db and rng.random() < 0.35:
                for _ in range(min(len(db) - 1, rng.randint(1, 2))):
                    s = db.pop(rng.randrange(len(db)))
                    run.um += useq(s)
                    deleted_u.append(useq(s))
            else:
                for _ in range(rng.randint(*cfg["batch"])):
                    s = rand_seq(rng, alphabet)
                    inserted.append(s)
                    run.insert(db, s)
            tu = sum(useq(s) for s in db)
            if run.A:
                ok = construct_trigger(run, inserted, deleted_u, tu, fault_t)
                if ok is None:
                    c["t_out"] += 1
                elif run.phi() > run.beta():
                    c["t"] += 1
                    c["t_ok"] += ok
                else:
                    c["t_neg"] += 1
                    c["t_neg_ok"] += ok
            if run.phi() > run.beta():
                run.rescan(db, rng.choice([lut_min, uut]))
                inserted, deleted_u = [], []
                continue
            new = {}
            for X in run.tracked:
                v = u_db(X, db)
                a = [run.A.get(i, F(0)) for i in items_of(X)]
                bound = run.l_ref + (max(a) if boundary == "max" else min(a))
                if len(X) <= 2 and rng.random() < 0.25:  # a sample: the continuation is slow
                    ok = construct_floor(run, db, X, fault_f)
                    if ok is None:
                        c["f_out"] += 1
                    elif v >= bound:
                        c["f"] += 1
                        c["f_ok"] += ok
                    else:
                        c["f_neg"] += 1
                        c["f_neg_ok"] += ok
                if v >= run.l_ref + max(a):
                    new[X] = v
            run.tracked = new
    return c


def tightness_holds(c):
    return (c["t"] > 0 and c["t_ok"] == c["t"] and c["t_neg"] > 0 and c["t_neg_ok"] == 0
            and c["f"] > 0 and c["f_ok"] == c["f"] and c["f_neg"] > 0 and c["f_neg_ok"] == 0)


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--streams", type=int, default=1500)
    ap.add_argument("--regimes", default="light,heavy")
    ap.add_argument("--fault-streams", type=int, default=60)
    a = ap.parse_args()
    ok = True
    fault_mismatches = {"globalmax": 0, "maxplus": 0}
    for regime in a.regimes.split(","):
        print(f"== regime {regime}: {a.streams} random streams of {STEPS} operations")
        for floor, must_fail in [("plain", False), ("min", False), ("max", False),
                                 ("globalmax", True), ("maxplus", True)]:
            tot = dict(states=0, mismatches=0, discards=0, beyond_min=0)
            for seed in range(a.streams):
                for k, v in check_floor(seed, floor, regime).items():
                    tot[k] += v
            if must_fail:
                fault_mismatches[floor] += tot["mismatches"]
                verdict = "caught here" if tot["mismatches"] else "not caught in this regime"
            else:
                good = tot["mismatches"] == 0 and tot["states"] > 0
                ok &= good
                verdict = "ok" if good else "FAIL"
            what = "fault, must be caught in some regime" if must_fail else "must be exact"
            print(f"floor {floor:9s} ({what}): states {tot['states']}, discards {tot['discards']} "
                  f"(at or above the min floor: {tot['beyond_min']}), mismatching states {tot['mismatches']}"
                  f" -> {verdict}")
        c = check_tightness(a.streams, regime)
        good = tightness_holds(c)
        ok &= good
        print(f"trigger: states with Phi > beta {c['t']}, construction gives an untracked HUSP {c['t_ok']}; "
              f"states with Phi <= beta {c['t_neg']}, succeeded {c['t_neg_ok']}; "
              f"outside U- <= (1 - lambda) TU_ref {c['t_out']}")
        print(f"max floor: patterns at or above it {c['f']}, made HUSPs {c['f_ok']}; below it {c['f_neg']}, "
              f"made HUSPs {c['f_neg_ok']}; excluded states (no insertion, Phi = beta) {c['f_out']}"
              f" -> {'ok' if good else 'FAIL'}")
    for floor, n in fault_mismatches.items():
        ok &= n > 0
        print(f"faulty floor {floor}: mismatching states over all regimes {n} -> {'caught' if n else 'NOT CAUGHT'}")
    print(f"== self-test: faults in this checker, {a.fault_streams} light streams each")
    for name, kw in [("trigger construction without the redistribution", dict(fault_t="no-redistribution")),
                     ("floor construction without filler", dict(fault_f="no-filler")),
                     ("min floor taken as the boundary", dict(boundary="min"))]:
        caught = not tightness_holds(check_tightness(a.fault_streams, "light", **kw))
        ok &= caught
        print(f"fault: {name} -> {'caught' if caught else 'NOT CAUGHT'}")
    print("all claims hold and every fault is caught" if ok else "A CLAIM FAILS OR A FAULT IS NOT CAUGHT")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
