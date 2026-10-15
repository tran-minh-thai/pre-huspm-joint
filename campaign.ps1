<#
    One command, any machine.

        .\campaign.ps1              # do whatever is left, whichever machine this is
        .\campaign.ps1 -List        # say what is left without running anything
        .\campaign.ps1 -Scenario SC1
        .\campaign.ps1 -Datasets FIFA
        .\campaign.ps1 -SessionHours 8   # one sitting; run it again tomorrow to continue
        .\campaign.ps1 -Scenario SC1 -MemMode live   # the memory campaign

    Memory. -MemMode live measures the live set instead of timing: the runner forces a full
    collection at every sample and reads the heap right after, so its runtimes are void and
    written as -3. It is a separate campaign with its own tree, results/campaign-mem, its own
    DONE and CLAIM files and the same arms, so a timing arm and a memory arm never share a
    directory or a row, and either can be resumed without the other.

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

    One JVM per arm, so one arm's heap exhaustion cannot affect the next arm.
#>
param(
    [string]   $Scenario   = 'SC2',
    [string[]] $Datasets,
    # SC7's arms are the ablations, not the algorithms, so it gets its own list below unless
    # one is given explicitly.
    [string[]] $Algorithms,
    [int]      $CapSeconds = 28800,
    # Heap ceiling for every arm, one value for the whole campaign. -Xmx is a ceiling, not a
    # reservation, so tuning it per database gains nothing; but the ceiling decides how far a
    # memory-bound arm gets, so rows measured under different ceilings are not comparable.
    # Keep it fixed and report it with the results. 48 GB on a 64 GB machine leaves the
    # operating system the rest.
    [int]      $HeapGb = 48,
    # Stop taking new arms once the session has run this long. 0 means run until the
    # scenario is finished.
    #
    # The session stops BETWEEN arms, never inside one: an arm killed at a wall-clock
    # boundary is neither a runtime nor a failure. A session therefore overruns by however
    # long its last arm takes, bounded by CapSeconds.
    [double]   $SessionHours = 0,
    # 'used' is the timing campaign. 'live' is the memory campaign; see the header.
    [ValidateSet('used','live')][string] $MemMode = 'used',
    # Shortest wait between forced collections in live mode. The runner stretches it to four
    # times the last collection, so a large live set is sampled less often than a small one.
    [int]      $MemIntervalMs = 20,
    # Generation of the memory campaign. Live-mode readings taken by a different sampling
    # procedure are a different measurement and must not share a tree with the old ones. ''
    # is the first generation (results/campaign-mem); 'sampled' is the one in which a short
    # static mine is run again until the sampler has read it enough times (live_repeats),
    # written to results/campaign-mem-sampled and results/campaign-mem-sampled-replicates.
    [ValidateSet('','sampled')][string] $MemGeneration = '',
    # Replicates. Repeat n > 0 measures the same arms again, each in a fresh JVM as every arm
    # is, and SeedIndex k > 0 measures them with SEEDS[k] instead of SEEDS[0]. A replicate
    # goes to a tree of its own, results/campaign-replicates/s<k>r<n>, with its own DONE and
    # CLAIM files, so the main tree is the replicate s0r0 and is never touched by this. Rows
    # carry the repeat in their repeat column and the seed in their seed column.
    [int]      $Repeat = 0,
    [int]      $SeedIndex = 0,
    # The exact JDK every arm runs on: runtime version and vendor, not just the major
    # version, since two builds of the same major version are different runtimes. Read from
    # environment.lock.json, the one place they are set; these parameters override it only
    # for testing on another machine.
    [string]   $JdkRuntime = '',
    [string]   $JdkVendor  = '',
    [switch]   $List,
    [switch]   $NoGit,
    # Measure the two settings experiments.CampaignCalibration decides (item removal in the
    # proposal's miner, KOSARAK's floor ratio) under its pre-set rules, after checking that
    # every miner returns the same patterns as USpan. Writes results/calibration and pushes it.
    [switch]   $Calibrate,
    # Run verify.DirectPathVerifier in adversarial mode on one database, with this machine's
    # heap. Writes results/verify-joint/adversarial-<database> (console.txt, run_meta.json in
    # the format of analysis/record_run.py) and pushes it.
    [string]   $VerifyAdversarial = ''
)
# 'Continue', not 'Stop'. Native tools write progress to stderr - java -version does, git
# does - and under 'Stop' PowerShell turns every such line into a terminating error. The
# checks that matter are explicit below: the JDK version, the compile, each exit code.
$ErrorActionPreference = 'Continue'
$host_ = $env:COMPUTERNAME
if (-not $host_) { $host_ = 'unknown' }

# Each scenario has its own arms and databases. BMS1 is absent from every scenario that grows
# past D_init: it cannot be mined on the whole database at any threshold that returns
# patterns, and the runner skips it.
#
# SUBSET_3 below repeats AutomatedExperimentRunner.SUBSET_3. If the two drift apart, an arm
# that should not exist produces no rows; it is recorded as EMPTY and reported by -List
# rather than marked DONE.
$all7    = @('SIGN','LEVIATHAN','TAFENG','BIBLE','C8T1S5I8N5K','KOSARAK','FIFA')
$all8    = @('SIGN','LEVIATHAN','TAFENG','BIBLE','BMS1','C8T1S5I8N5K','KOSARAK','FIFA')
$subset3 = @('SIGN','LEVIATHAN','C8T1S5I8N5K')
$static  = @('USpan','HUSP-ULL','HUSP-SP','IncUSP-Miner+','Pre-HUSPM','Adaptive-Full')
$incr    = @('Adaptive-Full','Pre-HUSPM-SWUmax','Pre-HUSPM-TSU','Remine-from-scratch','IncUSP-Miner+',
             'Remine-HUSP-ULL','Remine-HUSP-SP','Joint-miner+SWUmax','Joint-miner+TSU','Joint-rule+SWU-tracking',
             'Adaptive-FloorSelect')
# The mixed streams (SC6, SC6D, SC6B): AutomatedExperimentRunner.MIXED_ARMS.
$mixed   = @('Adaptive-Full','Remine-from-scratch','Remine-HUSP-ULL','Remine-HUSP-SP','Adaptive-FloorSelect')
$scenarios = @('SC1','SC2','SC3','SC4','SC5','SC6','SC6D','SC6B','SC7')
if ($scenarios -notcontains $Scenario) { throw "Unknown scenario '$Scenario'. Known: $($scenarios -join ', ')." }

# Arms that exist on some databases only. SC7's incremental ablations (the block index and
# the adaptive rescan) run in the runner on C5_ABLATION_SET, which is SIGN and C8T1S5I8N5K
# once BMS1 is filtered out by scope. This list repeats that set; if the two drift apart the
# stray arm is recorded as EMPTY and named by -List.
$sc7Incremental = @('proposed_C5_on','proposed_C5_off','proposed_no_block_index',
                    'proposed_bsz_1','proposed_bsz_5','proposed_bsz_10','proposed_bsz_50','proposed_bsz_200')
$sc7IncSets     = @('SIGN','C8T1S5I8N5K')
function Test-Arm($d, $a) {
    if ($Scenario -eq 'SC7' -and $sc7Incremental -contains $a -and $sc7IncSets -notcontains $d) {
        return $false
    }
    return $true
}

if (-not $Algorithms) {
    $Algorithms = switch ($Scenario) {
        'SC1'   { $static }
        'SC3'   { $static }
        'SC4'   { @('Pre-HUSPM','Adaptive-Full') }
        'SC6'   { $mixed }
        'SC6D'  { $mixed }
        'SC6B'  { $mixed }
        'SC7'   { @('proposed_full','proposed_no_block_index','proposed_no_IUB','proposed_no_IPS',
                    'proposed_bsz_1','proposed_bsz_5','proposed_bsz_10','proposed_bsz_50','proposed_bsz_200',
                    'proposed_C5_on','proposed_C5_off','Pre-HUSPM') }
        default { $incr }                          # SC2, SC5
    }
}

if (-not $Datasets) {
    $Datasets = switch ($Scenario) {
        'SC1'   { $all8 }                          # the one scenario BMS1 can take part in
        'SC3'   { $subset3 }
        'SC4'   { $subset3 }
        'SC5'   { $subset3 }
        'SC6'   { $subset3 }
        'SC6D'  { $subset3 }
        'SC6B'  { $subset3 }
        default { $all7 }                          # SC2, SC7
    }
}

# Check the names before anything uses them. Passed through -File, PowerShell hands an
# array parameter the whole comma-joined string, so -Algorithms A,B arrives as one name
# "A,B", which matches no algorithm. Use -Command "& { .\campaign.ps1 -Algorithms 'A','B' }".
$knownAlgos = @('Adaptive-Full','Pre-HUSPM','Pre-HUSPM-SWUmax','Pre-HUSPM-TSU','Remine-from-scratch','IncUSP-Miner+',
                'Remine-HUSP-ULL','Remine-HUSP-SP','Joint-miner+SWUmax','Joint-miner+TSU','Joint-rule+SWU-tracking',
                'Adaptive-FloorSelect',
                'USpan','HUSP-ULL','HUSP-SP',
                'proposed_full','proposed_no_block_index','proposed_no_IUB','proposed_no_IPS',
                'proposed_bsz_1','proposed_bsz_5','proposed_bsz_10','proposed_bsz_50','proposed_bsz_200',
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
            git add $top 2>&1 | Out-Null
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

# The memory campaign keeps its own tree. The runner refuses live mode unless results.dir
# names a directory with "mem" in it, which is what keeps the two kinds of row apart.
$replicate = ($Repeat -gt 0 -or $SeedIndex -gt 0)
if ($Repeat -lt 0 -or $SeedIndex -lt 0 -or $SeedIndex -gt 4) {
    throw "Repeat must be 0 or more and SeedIndex 0 to 4. Found Repeat=$Repeat SeedIndex=$SeedIndex."
}
# Memory replicates have a tree of their own too, results/campaign-mem-replicates/s<k>r<n>,
# so a re-measured live set never lands in the memory campaign's main tree.
$memTree = if ($MemGeneration) { "campaign-mem-$MemGeneration" } else { 'campaign-mem' }
if ($MemGeneration -and $MemMode -ne 'live') { throw "-MemGeneration applies to -MemMode live only." }
$top   = if ($MemMode -eq 'live' -and $replicate) { "results/$memTree-replicates/s${SeedIndex}r${Repeat}" }
         elseif ($MemMode -eq 'live') { "results/$memTree" }
         elseif ($replicate) { "results/campaign-replicates/s${SeedIndex}r${Repeat}" }
         else { 'results/campaign' }
$label = if ($MemMode -eq 'live' -and $replicate) { "mem s${SeedIndex}r${Repeat} $Scenario" }
         elseif ($MemMode -eq 'live') { "mem $Scenario" }
         elseif ($replicate) { "s${SeedIndex}r${Repeat} $Scenario" }
         else { $Scenario }
$root  = "$top/$Scenario"
$memArgs = if ($MemMode -eq 'live') { @('-Dmem.mode=live', "-Dmem.interval.ms=$MemIntervalMs") } else { @() }
$memArgs += @("-Drepeat.index=$Repeat", "-Dseed.index=$SeedIndex")
$stale = (New-TimeSpan -Seconds ($CapSeconds + 3600))

function Get-NextArm {
    Sync-Repo
    foreach ($d in $Datasets) { foreach ($a in $Algorithms) {
        if (-not (Test-Arm $d $a)) { continue }
        $dir = "$root/$d/$a"
        if (Test-Path "$dir/DONE") { continue }
        # An arm set aside on purpose: the measurement is real but too expensive to finish,
        # and the file says why. Delete the file to put the arm back in the queue.
        if (Test-Path "$dir/DEFERRED") { continue }
        # An arm that ran and produced no rows: the scenario has no such arm on this database.
        # Skipped so the loop does not offer it forever; -List names it.
        if (Test-Path "$dir/EMPTY") { continue }
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
# One exact JVM for the whole campaign, checked here rather than trusted. Runtimes from two
# JVMs do not belong in one table, and arms may run on several machines. The pin is about
# comparability, not compilation. Changing the pinned JDK invalidates every arm already
# measured: clear results/campaign so they are queued again instead of mixed with new ones.
$lockPath = Join-Path $PSScriptRoot 'environment.lock.json'
if (-not (Test-Path $lockPath)) { throw "No ${lockPath}: the pinned environment is defined there." }
$lock = Get-Content -Raw $lockPath | ConvertFrom-Json
if (-not $JdkRuntime) { $JdkRuntime = $lock.jdk.runtime_version }
if (-not $JdkVendor)  { $JdkVendor  = $lock.jdk.vendor }
$props = & $java -XshowSettings:properties -version 2>&1 | ForEach-Object { "$_" }
$runtime = ($props | Where-Object { $_ -match '^\s*java\.runtime\.version = ' } | Select-Object -First 1) -replace '^\s*java\.runtime\.version = ', ''
$vendor  = ($props | Where-Object { $_ -match '^\s*java\.vendor = ' } | Select-Object -First 1) -replace '^\s*java\.vendor = ', ''
if ("$runtime".Trim() -ne $JdkRuntime) {
    throw "This study is pinned to JDK runtime $JdkRuntime. $java reports java.runtime.version '$runtime'."
}
if ("$vendor".Trim() -ne $JdkVendor) {
    throw "This study is pinned to JDK vendor '$JdkVendor'. $java reports java.vendor '$vendor'."
}
# From the properties, not from 'java -version': under Windows PowerShell its stderr line comes
# back wrapped in an error record, and that record is what ends up in the logs.
$ver = "$("$runtime".Trim()) ($("$vendor".Trim()))"

if ($List) {
    Sync-Repo
    $todo = @(); $defer = @(); $empty = @(); $done = 0; $held = 0
    foreach ($d in $Datasets) { foreach ($a in $Algorithms) {
        if (-not (Test-Arm $d $a)) { continue }
        $dir = "$root/$d/$a"
        if (Test-Path "$dir/DONE") { $done++ }
        elseif (Test-Path "$dir/DEFERRED") { $defer += "$d/$a" }
        elseif (Test-Path "$dir/EMPTY") { $empty += "$d/$a" }
        elseif (Test-Path "$dir/CLAIM") { $held++; $todo += "$d/$a (claimed: $(Get-Content "$dir/CLAIM" -Raw))".Trim() }
        else { $todo += "$d/$a" }
    } }
    Write-Host "$Scenario : $done done, $held claimed, $($todo.Count) left, $($defer.Count) deferred"
    $todo | ForEach-Object { Write-Host "  $_" }
    if ($defer.Count) {
        Write-Host "deferred (delete the DEFERRED file to requeue):"
        $defer | ForEach-Object { Write-Host "  $_" }
    }
    if ($empty.Count) {
        Write-Host "EMPTY - ran and produced no rows; the arm list is wrong for this scenario:"
        $empty | ForEach-Object { Write-Host "  $_" }
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

if ($Calibrate) {
    if ((git status --porcelain -- src 2>$null | Out-String).Trim()) { throw "src/ has uncommitted changes; calibrate from a commit." }
    $cal = 'results/calibration'
    if (Test-Path $cal) { throw "$cal exists. Delete it to calibrate again." }
    New-Item -ItemType Directory -Force $cal | Out-Null
    $commit = (git log -1 --format=%H -- src 2>$null | Out-String).Trim()
    "host=$host_ commit=$commit java=$ver heap=${HeapGb}g os=`"$([Environment]::OSVersion.VersionString)`" start=$(Get-Date -f 'yyyy-MM-dd HH:mm:ss')" | Set-Content "$cal/run.info"
    Write-Host "=== agreement of every miner with USpan, default thresholds ==="
    $p = Start-Process -FilePath $java -PassThru -Wait -NoNewWindow `
         -RedirectStandardOutput "$cal/agreement.log" -RedirectStandardError "$cal/agreement.err" `
         -ArgumentList @("-Xmx${HeapGb}g", '-Xss64m', '-cp', 'build', 'verify.BaselineAgreement', 'ALL')
    if ($p.ExitCode -ne 0) { Get-Content "$cal/agreement.log" -Tail 30; Get-Content "$cal/agreement.err" -Tail 30; throw "miners disagree or failed; see $cal/agreement.log" }
    Write-Host "=== calibration ==="
    $p = Start-Process -FilePath $java -PassThru -Wait -NoNewWindow `
         -RedirectStandardOutput "$cal/run.log" -RedirectStandardError "$cal/run.err" `
         -ArgumentList @('-Xmx4g', '-cp', 'build', "-Dcalib.childHeap=${HeapGb}g",
                         'experiments.CampaignCalibration', 'all', $cal, 'datasets/')
    $rc = $p.ExitCode
    Get-Content "$cal/run.log" -Tail 20
    "end=$(Get-Date -f 'yyyy-MM-dd HH:mm:ss') rc=$rc" | Add-Content "$cal/run.info"
    if (-not $NoGit) {
        git add $cal 2>&1 | Out-Null
        git commit -q -m "Calibration on $host_ (rc=$rc)" 2>&1 | Out-Null
        git push -q 2>&1 | Out-Null
        if ($LASTEXITCODE -ne 0) { git pull --rebase --autostash -q 2>&1 | Out-Null; git push -q 2>&1 | Out-Null }
        if ($LASTEXITCODE -ne 0) { throw "calibration results committed but not pushed; run git push" }
    }
    if ($rc -ne 0) { throw "calibration failed; see $cal/run.log" }
    return
}

if ($VerifyAdversarial) {
    if ((git status --porcelain -- src 2>$null | Out-String).Trim()) { throw "src/ has uncommitted changes; verify from a commit." }
    $db  = $VerifyAdversarial.ToUpper()
    $out = "results/verify-joint/adversarial-$($db.ToLower())"
    if (Test-Path $out) { throw "$out exists. Delete it to run again." }
    New-Item -ItemType Directory -Force $out | Out-Null
    $vargs = @("-Xmx${HeapGb}g", '-Xss64m', '-cp', 'build', 'verify.DirectPathVerifier', $db,
               '--adversarial', '--ops', '16', '--out', $out)
    $start = Get-Date -Format 's'
    Write-Host "=== adversarial verification on $db, heap ${HeapGb}g ==="
    $p = Start-Process -FilePath $java -PassThru -NoNewWindow `
         -RedirectStandardOutput "$out/console.txt" -RedirectStandardError "$out/run.err" -ArgumentList $vargs
    $null = $p.Handle
    $p.WaitForExit()
    $rc = $p.ExitCode
    $meta = [ordered]@{
        command     = "java " + ($vargs -join ' ')
        build_dir   = 'build'
        git_commit  = (git rev-parse HEAD 2>$null | Out-String).Trim()
        tree        = if ((git status --porcelain 2>$null | Out-String).Trim()) { 'src clean, other files modified' } else { 'clean' }
        java        = $ver
        os          = [Environment]::OSVersion.VersionString
        machine     = $host_
        role        = 'measurement machine: counters only, no timings'
        start       = $start
        end         = Get-Date -Format 's'
        exit_code   = $rc
    }
    [IO.File]::WriteAllText((Join-Path $PWD "$out/run_meta.json"), ($meta | ConvertTo-Json) + "`n")
    if ((Get-Item "$out/run.err").Length -eq 0) { Remove-Item "$out/run.err" }
    Get-Content "$out/console.txt" -Tail 5
    if (-not $NoGit) {
        git add $out 2>&1 | Out-Null
        git commit -q -m "Adversarial verification on $db, $host_ (rc=$rc)" 2>&1 | Out-Null
        git push -q 2>&1 | Out-Null
        if ($LASTEXITCODE -ne 0) { git pull --rebase --autostash -q 2>&1 | Out-Null; git push -q 2>&1 | Out-Null }
        if ($LASTEXITCODE -ne 0) { throw "verification committed but not pushed; run git push" }
    }
    if ($rc -ne 0) { throw "verification failed (exit $rc); see $out/console.txt" }
    return
}

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
        if (-not (Test-Arm $d $a)) { continue }
        if (Test-Path "$root/$d/$a/DONE") { $done++ }
        elseif (-not (Test-Path "$root/$d/$a/DEFERRED") -and
                -not (Test-Path "$root/$d/$a/EMPTY")) { $left++ }
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
    # Clear anything a previous attempt left. ResultCollector appends to its CSV, so a retried
    # arm would otherwise write its batches after the old ones and duplicate iterations.
    Get-ChildItem $dir -File -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -ne 'DONE' } | Remove-Item -Force
    "$host_ $(Get-Date -f 'yyyy-MM-dd HH:mm:ss')" | Set-Content "$dir/CLAIM"
    [void](Publish "Claim $label/$d/$a on $host_")

    $start = Get-Date
    Write-Host "#### $d / $a start $(Get-Date -f HH:mm:ss)"
    $p = Start-Process -FilePath $java -PassThru -NoNewWindow `
         -RedirectStandardOutput "$dir/run.log" -RedirectStandardError "$dir/run.err" `
         -ArgumentList (@("-Xmx${HeapGb}g",'-Xss64m','-cp','build',
             "-Ddataset.only=$d", "-Dalgo.only=$a", '-Drepeats=1',
             "-Dresults.dir=$dir/") + $memArgs + @('AutomatedExperimentRunner', $Scenario))
    # Windows PowerShell reports ExitCode as empty for a process started this way unless its
    # handle is read before the process ends; rc= in DONE depends on it.
    $null = $p.Handle
    $capped = $false
    if (-not $p.WaitForExit($CapSeconds * 1000)) { $capped = $true; $p.Kill(); $p.WaitForExit() }
    $secs = [int]((Get-Date) - $start).TotalSeconds

    $csv  = Get-ChildItem "$dir" -Filter '*.csv' | Select-Object -First 1
    $rows = if ($csv) { (Get-Content $csv.FullName | Measure-Object -Line).Lines - 1 } else { 0 }
    # DONE also records the commit and the operating system; the runner's log has the JVM.
    # The commit is the last one that changed src/: HEAD moves with every result this script
    # commits, so it would name a results commit rather than the code the arm ran.
    $commit = if ($NoGit) { 'unknown' } else { (git log -1 --format=%h -- src 2>$null | Out-String).Trim() }
    $treeState = if ($NoGit) { 'unknown' } elseif ((git status --porcelain -- src 2>$null | Out-String).Trim()) { 'src-modified' } else { 'clean' }
    $osText = [Environment]::OSVersion.VersionString
    $line = "host=$host_ finished=$(Get-Date -f 'yyyy-MM-dd HH:mm:ss') seconds=$secs rc=$($p.ExitCode) rows=$rows capped=$capped commit=$commit src=$treeState os=`"$osText`""
    # No rows and not capped means the runner had nothing to do for this arm: the scenario
    # does not run this algorithm, or not on this database. That is not a measurement, so it
    # is recorded as EMPTY. A capped arm with no rows did not get through D_init inside the
    # cap, which is a result, so it keeps DONE and says capped=True.
    if ($rows -le 0 -and -not $capped) {
        ($line + [Environment]::NewLine +
         "No rows: $Scenario has no arm '$a' on $d. Not a measurement. Delete this file once " +
         "the arm lists in campaign.ps1 are right.") | Set-Content "$dir/EMPTY"
    } else {
        $line | Set-Content "$dir/DONE"
    }
    Remove-Item "$dir/CLAIM" -ErrorAction SilentlyContinue
    $armsThisSession++
    Write-Host "#### $d / $a done $(Get-Date -f HH:mm:ss)  ${secs}s  rc=$($p.ExitCode)  rows=$rows$(if($capped){'  CAPPED'})"
    if (-not (Publish "$label/$d/$a on $host_ ($secs s, $rows rows)")) {
        Write-Host "  (could not push results; they are committed locally)"
    }
}
