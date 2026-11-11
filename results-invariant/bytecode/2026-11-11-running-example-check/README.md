# The running-example check changed; the measured classes did not

`src/verify/RunningExampleCheck.java` now states Example 2 of the manuscript under the method's rule
as measured (the initial mine's cycle counted as an absorption): 49 values, 0 mismatching; with
`-Dadapt.countInitialCycle=false` (the earlier rule) 11 of the 49 mismatch, so the check tells the
two rules apart.

Every source file was compiled without debug information before and after the change
(`javac -g:none -encoding UTF-8 -d <dir> $(find src -name "*.java")`, openjdk version "25.0.4.1" 2026-08-18 LTS),
and every class file hashed (`classes_before.sha256`, `classes_after.sha256`, 100 classes each).
Classes whose bytes differ: 1, `./verify/RunningExampleCheck.class`.
That one class differs also shows the comparison is not blind. The classes that produce the
measurements are byte for byte those of the commit the results name (335fd3554, the last change
to `src/` before this one).
