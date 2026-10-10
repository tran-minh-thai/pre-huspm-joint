"""Control for provenance.py: strip comments from a Java file and rename every identifier.

    python3 -I rename_identifiers.py IN.java OUT.java

A copy renamed this way must still score near 1.0 on the shape measure against its source,
or the measure cannot detect a renamed copy.
"""
import re
import sys

KEEP = set("""abstract assert boolean break byte case catch char class const continue default do double
else enum extends final finally float for goto if implements import instanceof int interface long native
new package private protected public return short static strictfp super switch synchronized this throw
throws transient try void volatile while var null true false""".split())

text = open(sys.argv[1], encoding="utf-8").read()
text = re.sub(r"/\*.*?\*/|//[^\n]*", "", text, flags=re.S)
names = {}


def rename(m):
    w = m.group(0)
    if w in KEEP:
        return w
    return names.setdefault(w, "v%d" % len(names))


open(sys.argv[2], "w", encoding="utf-8").write(re.sub(r"\b[A-Za-z_]\w*\b", rename, text))
print("identifiers renamed:", len(names))
