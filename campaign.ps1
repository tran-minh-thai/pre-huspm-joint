<#
    One command, any machine.

        .\campaign.ps1              # do whatever is left, whichever machine this is
        .\campaign.ps1 -List        # say what is left without running anything
        .\campaign.ps1 -Scenario SC1
        .\campaign.ps1 -Datasets FIFA
        .\campaign.ps1 -SessionHours 8   # one sitting; run it again tomorrow to continue

    Sessions. The campaign is tens of hours, so it is meant to be run in sittings rather
    than in one go. -SessionHours stops the loop taking new arms once the budget is spent
    and prints what is left; running the same command again continues, because an arm is
    the unit of progress and every finished arm is already committed. Nothing is lost by
    stopping and nothing is repeated by starting again.

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
    # SC7's arms are the ablations, not the algorithms, so it gets its own list below unless
    # one is given explicitly.
    [string[]] $Algorithms,
    [int]      $CapSeconds = 28800,
    # Heap for every arm, one value for the whole campaign. -Xmx is a ceiling, not a
    # reservation: an arm that needs 800 MB takes 800 MB whatever this says, so there is
    # nothing to gain by tuning it per database and something to lose - the number decides
    # results, and a table whose rows were measured under different ceilings compares the
    # ceilings as much as the algorithms.
    #
    # 48 on a 64 GB machine, leaving the operating system the rest. It was 24, chosen before
    # anyone looked at the hardware, and six arms died of it. Raising it to 48 did not rescue
    # them but moved every one: IncUSP-Miner+ reached batch 75 instead of 32 on LEVIATHAN, 41
    # instead of 15 on BIBLE, 12 instead of 7 on KOSARAK. That is why the figure has to be
    # fixed for the whole campaign and reported with the results.
    [int]      $HeapGb = 48,
    # Stop taking new arms once the session has run this long. 0 means run until the
    # scenario is finished, which is the old behaviour.
    #
    # The session stops BETWEEN arms, never inside one. An arm killed at a wall-clock
    # boundary is not a measurement: how far it got would depend on when the session
    # happened to start, so the row would be neither a runtime nor an honest failure.
    # A session therefore overruns by however long its last arm takes, bounded by
    # CapSeconds. Budget 8 hours to land inside 10 with CapSeconds at its default 8;
    # the arms known to run past 4 hours are FIFA/Remine-from-scratch and the two
    # Pre-HUSPM-TSU arms already marked DEFERRED.
    [double]   $SessionHours = 0,
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
if (-not $Algorithms) {
    $Algorithms = if ($Scenario -eq 'SC7') {
        @('proposed_full','proposed_no_block_index','proposed_no_IUB',
          'proposed_C5_on','proposed_C5_off','Pre-HUSPM')
    } else {
        @('Adaptive-Full','Pre-HUSPM','Pre-HUSPM-TSU','Remine-from-scratch','IncUSP-Miner+')
    }
}

if (-not $Datasets) {
    $Datasets = if ($Scenario -eq 'SC1') {
        @('SIGN','LEVIATHAN','TAFENG','BIBLE','BMS1','C8T1S5I8N5K','KOSARAK','FIFA')
    } else {
        @('SIGN','LEVIATHAN','TAFENG','BIBLE','C8T1S5I8N5K','KOSARAK','FIFA')
    }
}

# Check the names before anything uses them. Passed through -File, PowerShell hands an
# array parameter the whole comma-joined string, so -Algorithms A,B arrives as one name
# "A,B". Unchecked, that name matched no algorithm: the runner produced an empty CSV in
# seconds and the script wrote DONE for it, leaving one junk arm per database and looking
# like it had worked. Use -Command "& { .\campaign.ps1 -Algorithms 'A','B' }" instead.
$knownAlgos = @('Adaptive-Full','Pre-HUSPM','Pre-HUSPM-TSU','Remine-from-scratch','IncUSP-Miner+',
                'proposed_full','proposed_no_block_index','proposed_no_IUB',
                'proposed_C5_on','proposed_C5_off')
$knownSets  = @('SIGN','LEVIATHAN','TAFENG','BIBLE','BMS1','C8T1S5I8N5K','KOSARAK','FIFA')
foreach ($a in $Algorithms) {
    if ($knownAlgos -notcontains $a) {
        throw ("Unknown algorithm '$a'. Known: " + ($knownAlgos -join ', ') +
               ". A comma-separated list passed through -File arrives as one string; " +
               "invoke with -Command instead, quoting each name separately.")
    }
}
foreach ($d in $Datasets) {
    if ($knownSets -notcontains $d) { throw "Unknown database '$d'. Known: $($knownSets -join ', ')." }
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
        # An arm set aside on purpose. It is not failure and not success: the measurement is
        # real but too expensive to finish right now, and the file says what was learned and
        # what to try. Delete the file to put the arm back in the queue.
        if (Test-Path "$dir/DEFERRED") { continue }
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
# One JVM version for the whole campaign, checked here rather than trusted. Runtimes from
# two versions do not belong in one table, and the arms are spread over several machines, so
# nothing else would catch a machine that upgraded its JDK between arms. The source uses no
# API removed since 21, so the pin is about comparability, not compilation. Changing this
# number invalidates every arm already measured: clear results/campaign so they are queued
# again instead of being mixed with the new ones.
if ($ver -notmatch '"25\.') { throw "This study is pinned to JDK 25. Found: $ver" }

if ($List) {
    Sync-Repo
    $todo = @(); $defer = @(); $done = 0; $held = 0
    foreach ($d in $Datasets) { foreach ($a in $Algorithms) {
        $dir = "$root/$d/$a"
        if (Test-Path "$dir/DONE") { $done++ }
        elseif (Test-Path "$dir/DEFERRED") { $defer += "$d/$a" }
        elseif (Test-Path "$dir/CLAIM") { $held++; $todo += "$d/$a (claimed: $(Get-Content "$dir/CLAIM" -Raw))".Trim() }
        else { $todo += "$d/$a" }
    } }
    Write-Host "$Scenario : $done done, $held claimed, $($todo.Count) left, $($defer.Count) deferred"
    $todo | ForEach-Object { Write-Host "  $_" }
    if ($defer.Count) {
        Write-Host "deferred (delete the DEFERRED file to requeue):"
        $defer | ForEach-Object { Write-Host "  $_" }
    }
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
$sessionStart = Get-Date
$armsThisSession = 0
if ($SessionHours -gt 0) {
    Write-Host ("session budget $SessionHours h - no new arm starts after " +
                "$($sessionStart.AddHours($SessionHours).ToString('HH:mm:ss'))")
}

function Stop-Session($reason) {
    $h = ((Get-Date) - $sessionStart).TotalHours
    Write-Host ("======== session over: $reason ========")
    Write-Host ("  {0} arm(s) in {1:N2} h on $host_" -f $armsThisSession, $h)
    $left = 0; $done = 0
    foreach ($d in $Datasets) { foreach ($a in $Algorithms) {
        if (Test-Path "$root/$d/$a/DONE") { $done++ }
        elseif (-not (Test-Path "$root/$d/$a/DEFERRED")) { $left++ }
    } }
    Write-Host "  $Scenario now: $done done, $left left. Same command again to continue."
}

while ($true) {
    if ($SessionHours -gt 0 -and ((Get-Date) - $sessionStart).TotalHours -ge $SessionHours) {
        Stop-Session "budget of $SessionHours h spent"
        break
    }
    $arm = Get-NextArm
    if (-not $arm) {
        if ($SessionHours -gt 0) { Stop-Session "nothing left in $Scenario" }
        else { Write-Host "======== nothing left in $Scenario ========" }
        break
    }
    $d = $arm.d; $a = $arm.a; $dir = $arm.dir
    New-Item -ItemType Directory -Force $dir | Out-Null
    # Clear anything a previous attempt left. ResultCollector APPENDS to its CSV, so an arm
    # that is retried writes its new batches after the old ones: FIFA/Pre-HUSPM-TSU ended up
    # with 143 rows and 48 duplicated iteration numbers from two runs, which no analysis
    # could read correctly and nothing flagged. The run log is overwritten and so hid it.
    Get-ChildItem $dir -File -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -ne 'DONE' } | Remove-Item -Force
    "$host_ $(Get-Date -f 'yyyy-MM-dd HH:mm:ss')" | Set-Content "$dir/CLAIM"
    [void](Publish "Claim $Scenario/$d/$a on $host_")

    $start = Get-Date
    Write-Host "#### $d / $a start $(Get-Date -f HH:mm:ss)"
    $p = Start-Process -FilePath $java -PassThru -NoNewWindow `
         -RedirectStandardOutput "$dir/run.log" -RedirectStandardError "$dir/run.err" `
         -ArgumentList @("-Xmx${HeapGb}g",'-Xss64m','-cp','build',
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
    $armsThisSession++
    Write-Host "#### $d / $a done $(Get-Date -f HH:mm:ss)  ${secs}s  rc=$($p.ExitCode)  rows=$rows$(if($capped){'  CAPPED'})"
    if (-not (Publish "$Scenario/$d/$a on $host_ ($secs s, $rows rows)")) {
        Write-Host "  (could not push results; they are committed locally)"
    }
}
