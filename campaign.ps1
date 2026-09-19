<#
    One command, any machine.

        .\campaign.ps1              # do whatever is left, whichever machine this is
        .\campaign.ps1 -List        # say what is left without running anything
        .\campaign.ps1 -Scenario SC1
        .\campaign.ps1 -Datasets FIFA

    The campaign is a list of (database, algorithm) arms. Each arm records a DONE file when
    it finishes and a CLAIM file while it runs, both committed and pushed, so a machine
    starting up can see what the other machines have already taken. Run the same command on
    every machine; none of them needs to know what the others are doing.

    A machine that dies mid-arm leaves its CLAIM behind. A claim older than CapSeconds plus
    an hour is treated as abandoned and the arm is offered again, so a crash costs one arm
    rather than the campaign.

    Claims are best effort. If pushing one fails twice the arm runs anyway: two machines
    measuring the same arm wastes time, but a machine that refuses to work because it
    cannot write a lock file wastes more.

    One JVM per arm, always. The arms shared a process until K reached 100, when LEVIATHAN
    showed the price: IncUSP-Miner+ exhausted the heap and Pre-HUSPM started next with
    total=160MB and died in 31 s. Isolated, that same run finishes in 35 s.
#>
param(
    [string]   $Scenario   = 'SC2',
    [string[]] $Datasets,
    [string[]] $Algorithms = @('Adaptive-Full','Pre-HUSPM','Pre-HUSPM-TSU','Remine-from-scratch','IncUSP-Miner+'),
    [int]      $CapSeconds = 28800,
    [switch]   $List,
    [switch]   $NoGit
)
# 'Continue', not 'Stop'. Native tools write progress to stderr - java -version does, git
# does - and under 'Stop' PowerShell turns every such line into a terminating error. The
# checks that matter are explicit below: the JDK version, the compile, each exit code.
$ErrorActionPreference = 'Continue'
$host_ = $env:COMPUTERNAME
if (-not $host_) { $host_ = 'unknown' }

# BMS1 is absent from every scenario that grows past D_init: it cannot be mined on the whole
# database at any threshold that returns patterns. The runner skips it and says so; leaving
# it out here keeps the work list honest about what there is to do.
if (-not $Datasets) {
    $Datasets = if ($Scenario -eq 'SC1') {
        @('SIGN','LEVIATHAN','TAFENG','BIBLE','BMS1','C8T1S5I8N5K','KOSARAK','FIFA')
    } else {
        @('SIGN','LEVIATHAN','TAFENG','BIBLE','C8T1S5I8N5K','KOSARAK','FIFA')
    }
}

function Sync-Repo {
    if ($NoGit) { return }
    try { git pull --rebase --autostash -q 2>&1 | Out-Null } catch { Write-Host "  (pull failed, carrying on)" }
}
function Publish($msg) {
    if ($NoGit) { return $true }
    for ($i = 0; $i -lt 2; $i++) {
        try {
            git add results/campaign 2>&1 | Out-Null
            git diff --cached --quiet 2>$null
            if ($LASTEXITCODE -eq 0) { return $true }   # nothing new to say
            git commit -q -m $msg 2>&1 | Out-Null
            git push -q 2>&1 | Out-Null
            if ($LASTEXITCODE -eq 0) { return $true }
            git pull --rebase --autostash -q 2>&1 | Out-Null
        } catch { }
    }
    return $false
}

$root  = "results/campaign/$Scenario"
$stale = (New-TimeSpan -Seconds ($CapSeconds + 3600))

function Get-NextArm {
    Sync-Repo
    foreach ($d in $Datasets) { foreach ($a in $Algorithms) {
        $dir = "$root/$d/$a"
        if (Test-Path "$dir/DONE") { continue }
        $c = "$dir/CLAIM"
        if (Test-Path $c) {
            $age = (Get-Date) - (Get-Item $c).LastWriteTime
            if ($age -lt $stale) { continue }
            Write-Host "  claim on $d/$a is $([int]$age.TotalHours) h old, taking it over"
        }
        return @{ d = $d; a = $a; dir = $dir }
    } }
    return $null
}

# ---- java, data, build -------------------------------------------------------
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { $null }
if (-not ($java -and (Test-Path $java))) { $java = (Get-Command java).Source }
$ver = (& $java -version 2>&1 | Select-Object -First 1 | Out-String).Trim()
if ($ver -notmatch '"21\.') { throw "This study is pinned to JDK 21. Found: $ver" }

if ($List) {
    Sync-Repo
    $todo = @(); $done = 0; $held = 0
    foreach ($d in $Datasets) { foreach ($a in $Algorithms) {
        $dir = "$root/$d/$a"
        if (Test-Path "$dir/DONE") { $done++ }
        elseif (Test-Path "$dir/CLAIM") { $held++; $todo += "$d/$a (claimed: $(Get-Content "$dir/CLAIM" -Raw))".Trim() }
        else { $todo += "$d/$a" }
    } }
    Write-Host "$Scenario : $done done, $held claimed, $($todo.Count) left"
    $todo | ForEach-Object { Write-Host "  $_" }
    return
}

if (-not (Test-Path 'datasets\SIGN_seq.txt')) { throw "Databases are missing. Run .\fetch-datasets.ps1" }
Write-Host "host=$host_  java=$ver"
Write-Host "compiling..."
if (Test-Path build) { Remove-Item -Recurse -Force build }
New-Item -ItemType Directory build | Out-Null
& (Join-Path (Split-Path $java) 'javac.exe') -encoding UTF-8 -d build `
    (Get-ChildItem -Recurse -Filter *.java src | ForEach-Object { $_.FullName })
if ($LASTEXITCODE -ne 0) { throw "compile failed" }

# ---- the loop ---------------------------------------------------------------
Write-Host "=== $Scenario campaign on $host_ - $(Get-Date -f 'yyyy-MM-dd HH:mm:ss') ==="
while ($true) {
    $arm = Get-NextArm
    if (-not $arm) { Write-Host "======== nothing left in $Scenario ========"; break }
    $d = $arm.d; $a = $arm.a; $dir = $arm.dir
    New-Item -ItemType Directory -Force $dir | Out-Null
    "$host_ $(Get-Date -f 'yyyy-MM-dd HH:mm:ss')" | Set-Content "$dir/CLAIM"
    [void](Publish "Claim $Scenario/$d/$a on $host_")

    $start = Get-Date
    Write-Host "#### $d / $a start $(Get-Date -f HH:mm:ss)"
    $p = Start-Process -FilePath $java -PassThru -NoNewWindow `
         -RedirectStandardOutput "$dir/run.log" -RedirectStandardError "$dir/run.err" `
         -ArgumentList @('-Xmx24g','-Xss64m','-cp','build',
             "-Ddataset.only=$d", "-Dalgo.only=$a", '-Drepeats=1',
             "-Dresults.dir=$dir/", 'AutomatedExperimentRunner', $Scenario)
    $capped = $false
    if (-not $p.WaitForExit($CapSeconds * 1000)) { $capped = $true; $p.Kill(); $p.WaitForExit() }
    $secs = [int]((Get-Date) - $start).TotalSeconds

    $csv  = Get-ChildItem "$dir" -Filter '*.csv' | Select-Object -First 1
    $rows = if ($csv) { (Get-Content $csv.FullName | Measure-Object -Line).Lines - 1 } else { 0 }
    "host=$host_ finished=$(Get-Date -f 'yyyy-MM-dd HH:mm:ss') seconds=$secs rc=$($p.ExitCode) rows=$rows capped=$capped" |
        Set-Content "$dir/DONE"
    Remove-Item "$dir/CLAIM" -ErrorAction SilentlyContinue
    Write-Host "#### $d / $a done $(Get-Date -f HH:mm:ss)  ${secs}s  rc=$($p.ExitCode)  rows=$rows$(if($capped){'  CAPPED'})"
    if (-not (Publish "$Scenario/$d/$a on $host_ ($secs s, $rows rows)")) {
        Write-Host "  (could not push results; they are committed locally)"
    }
}
