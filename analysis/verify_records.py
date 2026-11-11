#!/usr/bin/env python3
"""Which record of a correctness check is the current one.

    python3 analysis/verify_records.py              # the record every check name resolves to
    python3 analysis/verify_records.py --self-test

The checks under results/verify-joint/ were rerun when the code they exercise changed. A rerun
does not overwrite the earlier record: it is written into a generation, a subdirectory that
holds a `generation.json`

    {"date": "2026-11-11", "commit": "...", "why": "...",
     "replaces": {"<record in the generation>": ["<name it is read under>", "<other names>"...]}}

A record of a generation is read in place of every name listed for it, under the first of them,
once it has finished (its run_meta.json exists); until then the earlier record stays the current
one. The newest generation that holds a finished record of a name wins. A record that is not
listed in `replaces` is kept as evidence and feeds no key.

    record_dir(name)   directory of the current record of a check
    names()            the check names that have a finished current record
    replaced()         {name: (generation, record)} of the names read from a generation
"""
import glob
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(os.path.dirname(HERE), "results", "verify-joint")
MARK = "generation.json"


def finished(d):
    return os.path.exists(os.path.join(d, "run_meta.json"))


def generations(root=ROOT):
    """[(directory, description)] of the generations under root, newest first."""
    out = []
    for f in glob.glob(os.path.join(root, "*", MARK)):
        g = json.load(open(f, encoding="utf-8"))
        for key in ("date", "replaces"):
            if key not in g:
                raise SystemExit(f"{f}: no '{key}'")
        out.append((os.path.dirname(f), g))
    dates = [g["date"] for _, g in out]
    if len(set(dates)) != len(dates):
        raise SystemExit(f"{root}: two generations carry the same date: {sorted(dates)}")
    return sorted(out, key=lambda x: x[1]["date"], reverse=True)


def replaced(root=ROOT):
    """{name: (generation directory, record, first name)} for every name whose current record
    lies in a generation. A record listed in `replaces` that has not finished, or was not run
    yet, replaces nothing; main() lists it as such."""
    out = {}
    for d, g in generations(root):
        for record, listed in g["replaces"].items():
            if not listed:
                raise SystemExit(f"{d}/{MARK}: '{record}' replaces nothing")
            if not finished(os.path.join(d, record)):
                continue        # not run yet, or still running: the earlier record stays current
            for name in listed:
                out.setdefault(name, (d, record, listed[0]))
    return out


def record_dir(name, root=ROOT):
    r = replaced(root).get(name)
    return os.path.join(r[0], r[1]) if r else os.path.join(root, name)


def names(root=ROOT):
    """The names with a finished current record: those at the top of the tree that no generation
    replaced under another name, and the first names of the finished generation records."""
    rep = replaced(root)
    top = {os.path.basename(d) for d in glob.glob(os.path.join(root, "*")) if os.path.isdir(d) and finished(d)}
    dropped = {n for n, (_, _, first) in rep.items() if n != first}
    return sorted((top - dropped) | {first for _, _, first in rep.values()})


def self_test():
    import shutil
    import tempfile
    fails, cases = [], []

    def expect(label, got, want):
        cases.append(label)
        ok = got == want
        print(f"SELF-TEST {label}: {'ok' if ok else f'FAILED, got {got!r}, expected {want!r}'}")
        if not ok:
            fails.append(label)

    tmp = tempfile.mkdtemp()
    try:
        def record(*parts, done=True):
            d = os.path.join(tmp, *parts)
            os.makedirs(d)
            if done:
                open(os.path.join(d, "run_meta.json"), "w").write('{"exit_code": 0}')
            return d

        def gen(name, date, replaces):
            os.makedirs(os.path.join(tmp, name), exist_ok=True)
            json.dump(dict(date=date, replaces=replaces), open(os.path.join(tmp, name, MARK), "w"))

        record("a")
        record("b")
        record("b-part1")
        record("c")
        record("unfinished", done=False)
        record("g1", "a")
        record("g1", "kept-as-evidence")
        gen("g1", "2026-01-01", {"a": ["a"]})
        record("g2", "a", done=False)
        record("g2", "b")
        record("g2", "new")
        gen("g2", "2026-02-01", {"a": ["a"], "b": ["b", "b-part1"], "new": ["new"]})
        expect("a record of an older generation is read while the newer one is unfinished",
               record_dir("a", tmp), os.path.join(tmp, "g1", "a"))
        expect("a finished generation record replaces every name listed for it",
               (record_dir("b", tmp), record_dir("b-part1", tmp)), (os.path.join(tmp, "g2", "b"),) * 2)
        expect("a name no generation lists is read at the top", record_dir("c", tmp), os.path.join(tmp, "c"))
        expect("names: replaced parts dropped, new records added, unfinished and unlisted left out",
               names(tmp), ["a", "b", "c", "new"])
        open(os.path.join(tmp, "g2", "a", "run_meta.json"), "w").write('{"exit_code": 0}')
        expect("the newest finished generation wins", record_dir("a", tmp), os.path.join(tmp, "g2", "a"))
        gen("g3", "2026-03-01", {"never-run": ["a"]})
        expect("a record that was not run yet replaces nothing", record_dir("a", tmp), os.path.join(tmp, "g2", "a"))
        gen("g3", "2026-03-01", {"never-run": []})
        try:
            replaced(tmp)
            expect("a record that replaces no name stops", "no stop", "stop")
        except SystemExit:
            expect("a record that replaces no name stops", "stop", "stop")
        shutil.rmtree(os.path.join(tmp, "g3"))
        gen("g3", "2026-02-01", {})
        try:
            generations(tmp)
            expect("two generations of one date stop", "no stop", "stop")
        except SystemExit:
            expect("two generations of one date stop", "stop", "stop")
    finally:
        shutil.rmtree(tmp)
    print(f"self-test: {len(cases) - len(fails)}/{len(cases)} cases as expected")
    return 1 if fails else 0


def main():
    if "--self-test" in sys.argv[1:]:
        return self_test()
    rep = replaced()
    for d, g in generations():
        waiting = sorted(r for r in g["replaces"] if not finished(os.path.join(d, r)))
        print(f"{os.path.basename(d)} ({g['date']}): {len(g['replaces'])} records listed, "
              f"{len(g['replaces']) - len(waiting)} finished"
              + (f"; not finished or not run yet: {', '.join(waiting)}" if waiting else ""))
    for name in names():
        where = os.path.relpath(record_dir(name), ROOT)
        print(f"  {name}: {where}" + ("" if name not in rep or rep[name][2] == name else f" (read as {rep[name][2]})"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
