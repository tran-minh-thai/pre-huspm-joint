"""Inject one fault into a copy of src/ (or take the implementation before commit 899dd59),
compile it and run verify.IncUspVerifier on random streams.

    python3 inject.py REPO WORKDIR NAME     NAME: old | no_ndb_rescan | case2_ldb_only | tsu_without_ru | no_rebuild
    SEED=11 python3 inject.py ...
"""
import sys,shutil,subprocess,os
repo,out,name=sys.argv[1],sys.argv[2],sys.argv[3]
F=sys.argv[2]
d=os.path.join(F,name); shutil.rmtree(d,ignore_errors=True); shutil.copytree(os.path.join(repo,'src'),os.path.join(d,'src'))
p=os.path.join(d,'src/algorithms/IncUSPMinerPlusAlgorithm.java')
s=open(p).read()
if name=='old':
    s=subprocess.run(['git','-C',repo,'show','076c0b8:src/algorithms/IncUSPMinerPlusAlgorithm.java'],capture_output=True,text=True).stdout
    a='    public Map<Pattern, Double> largeMap = new HashMap<>();\n'
    b=a+'    public long ndbScans = 0, case2Builds = 0, rebuilds = 0;\n    public Map<Pattern, Double> husps() { return largeMap; }\n'
else:
    a,b={
     'no_ndb_rescan':('if (t.lmTsu == 0 || peuLdb + t.lmTsu < currentMinUtil) {','if (true) {'),
     'case2_ldb_only':('                if (cn != null) whole.addAll(cn.entries);\n','\n'),
     'tsu_without_ru':('tsu = parent.util[0] + ip1 + ru[p1];','tsu = parent.util[0] + ip1;'),
     'no_rebuild':('if (!t.expanded || t.stale || peuOld < currentMinUtil) {','if (!t.expanded) {'),
    }[name]
assert s.count(a)==1,(name,'injection site not found exactly once')
s=s.replace(a,b); open(p,'w').write(s)
src=[os.path.join(r,f) for r,_,fs in os.walk(os.path.join(d,'src')) for f in fs if f.endswith('.java')]
src.append(os.path.join(repo,'src/verify/IncUspVerifier.java')) if not os.path.exists(os.path.join(d,'src/verify/IncUspVerifier.java')) else None
c=subprocess.run(['javac','-nowarn','-d',os.path.join(d,'bin')]+src,capture_output=True,text=True)
assert c.returncode==0,c.stderr[-2000:]
r=subprocess.run(['java','-cp',os.path.join(d,'bin'),'verify.IncUspVerifier','random','300',os.environ.get('SEED','7')],capture_output=True,text=True)
print(name,'exit',r.returncode,'|',r.stdout.strip().splitlines()[0])
