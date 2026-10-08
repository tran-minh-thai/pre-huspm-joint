"""Measure how much of each TKCM source file is contained in some SPMF source file.

For every TKCM file T and every SPMF file S, three containment scores |A(T) & A(S)| / |A(T)|:
  lines : normalised non-trivial source lines (whitespace collapsed, comments kept as lines)
  raw   : token 8-grams of the code with comments removed, identifiers kept
  shape : token 8-grams with every identifier and literal replaced by a placeholder
          (survives renaming; Java keywords and operators kept)
Reports the best S per score, plus the denominator |A(T)|.
Usage: provenance.py <tkcm_src_dir> <spmf_root> [extra_files...]
"""
import re, sys
from pathlib import Path

K = 8
KW = set("""abstract assert boolean break byte case catch char class const continue default do double
else enum extends final finally float for goto if implements import instanceof int interface long native
new package private protected public return short static strictfp super switch synchronized this throw
throws transient try void volatile while var null true false""".split())
TOK = re.compile(r'"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|[A-Za-z_$][\w$]*|\d[\w.]*|==|!=|<=|>=|&&|\|\||\+\+|--|[+\-*/%]=|<<|>>|\S')
COMMENT = re.compile(r'/\*.*?\*/|//[^\n]*', re.S)
TRIVIAL = re.compile(r'^[{}();,\s]*$|^(else|try|finally|return;|break;|continue;)\s*[{}]?$')


def lines(text):
    out = set()
    for ln in text.splitlines():
        n = " ".join(ln.split())
        if len(n) >= 12 and not TRIVIAL.match(n) and not n.startswith("import ") and not n.startswith("package "):
            out.add(n)
    return out


LEX = re.compile(r'/\*.*?\*/|//[^\n]*|"(?:\\.|[^"\\\n])*"|\'(?:\\.|[^\'\\\n])*\'|[A-Za-z_$][\w$]*|\d[\w.]*|==|!=|<=|>=|&&|\|\||\+\+|--|[+\-*/%]=|<<|>>|\S', re.S)


def tokens(text):
    """Single pass, so '//' or '/*' inside a string literal is never read as a comment."""
    code = "\n".join(l for l in text.splitlines() if not l.strip().startswith(("import ", "package ")))
    return [t for t in LEX.findall(code) if not t.startswith(("//", "/*"))]


def grams(text, shape):
    toks = tokens(text)
    if shape:
        toks = [t if (t in KW or not re.match(r'[A-Za-z_$"\'\d]', t)) else "#" for t in toks]
    return {tuple(toks[i:i + K]) for i in range(len(toks) - K + 1)}


def features(path):
    text = path.read_text(encoding="utf-8", errors="replace")
    return {"lines": lines(text), "raw": grams(text, False), "shape": grams(text, True)}


def main():
    tk_dir, spmf_root = Path(sys.argv[1]), Path(sys.argv[2])
    targets = sorted(tk_dir.glob("*.java")) + [Path(p) for p in sys.argv[3:]]
    corpus = [(p, features(p)) for p in sorted(spmf_root.rglob("*.java"))]
    print(f"SPMF files scanned: {len(corpus)}")
    root = spmf_root.as_posix()
    for t in targets:
        ft = features(t)
        print(f"\n== {t.name}")
        for key in ("lines", "raw", "shape"):
            den = len(ft[key])
            best = sorted(((len(ft[key] & fs[key]) / den if den else 0.0, len(ft[key] & fs[key]), p)
                           for p, fs in corpus), key=lambda x: -x[0])[:2]
            desc = "; ".join(f"{s:.3f} ({n}/{den}) {p.as_posix()[len(root) + 1:]}" for s, n, p in best)
            print(f"  {key:5s} {desc}")


if __name__ == "__main__":
    main()
