#!/usr/bin/env bash
# Fault injection of verify.StreamPlansCheck: each case changes one line of common/StreamPlans.java
# in a copy of src/ at the commit given, compiles it, runs the check, and keeps its last line.
# The check must exit 1 on every case.   bash inject.sh <repository> <work directory>
set -u
REPO=$1; W=$2
case_line() {   # name, line, old text, new text
  local name=$1 line=$2 old=$3 new=$4 d="$W/$1"
  rm -rf "${d:?}"; mkdir -p "$d/build"; cp -R "$REPO/src" "$d/src"
  python3 - "$d/src/common/StreamPlans.java" "$line" "$old" "$new" <<'PY'
import sys
p, line, old, new = sys.argv[1], int(sys.argv[2]), sys.argv[3], sys.argv[4]
lines = open(p).read().split("\n")
assert lines[line - 1].count(old) == 1, f"line {line} does not hold the text to replace exactly once"
lines[line - 1] = lines[line - 1].replace(old, new)
open(p, "w").write("\n".join(lines))
PY
  echo "== $name: line $line"; diff "$REPO/src/common/StreamPlans.java" "$d/src/common/StreamPlans.java"
  javac -nowarn -d "$d/build" $(find "$d/src" -name '*.java') 2>&1 | grep -v '^Note'
  (cd "$REPO" && java -Xmx8g -cp "$d/build" verify.StreamPlansCheck > "$d/out.txt" 2>&1); echo "exit $?"
  tail -1 "$d/out.txt"
}
case_line heavy-x9      102 'scaled(s, 10.0,' 'scaled(s, 9.0,'
case_line no-alternation 58 'kinds.add(MixedPlan.Kind.DEL); }' 'kinds.add(MixedPlan.Kind.INS); }'
case_line old-id        120 'modify(s, nextId++,' 'modify(s, s.id,'
case_line factor-one    189 'el.get(j).utility * (0.5 + 1.5 * rnd.nextDouble())));' 'el.get(j).utility * 1.0)); rnd.nextDouble();'
