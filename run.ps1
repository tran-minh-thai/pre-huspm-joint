<#
  Build and run the selected scenarios. Windows version of run.sh.

      .\run.ps1                        every scenario
      .\run.ps1 SC1 SC2                selected scenarios
      .\run.ps1 -Heap 48g SC1          larger heap
      .\run.ps1 -TimeoutMin 60 SC2     shorter per-call limit
      .\run.ps1 TEST SC1               reduced grid, finishes in minutes

  Output goes to results\run-<timestamp>-<commit>\ with a manifest describing the code
  version and the machine. Figures are built from the campaign trees by
  analysis\make_figures.py, not from a single run.
#>

[CmdletBinding()]
param(
    [string]$Heap       = '24g',
    [string]$Stack      = '64m',
    [int]   $TimeoutMin = 120,
    # Refuse to start if another java process is live. Only bypass for non-measurement runs.
    [switch]$AllowConcurrent,
    # Set to the measurement machine's computer name to refuse running anywhere else.
    [string]$ExpectMachine = '',
    # Compile and stop. Needed after every pull before invoking a class directly with
    # java -cp build, since run.ps1 is what compiles.
    [switch]$BuildOnly,
    # 'used' (default) or 'live'. See docs/experiments.md.
    # 'live' forces a full collection at every sample point and reads the used heap right
    # after, which is the live set. It runs one JVM per arm, writes into <run>\mem\, and
    # its runtimes are void (runtime_ms = -3).
    [ValidateSet('used','live')][string]$MemMode = 'used',
    [int]$MemIntervalMs = 1000,
    # Arms swept in live mode, one JVM each.
    [string[]]$MemArms = @('USpan','HUSP-ULL','IncUSP-Miner+','Pre-HUSPM','Pre-HUSPM-TSU','Adaptive-Full'),
    # Position = 0 here makes every other parameter non-positional; without it PowerShell
    # binds bare arguments to -Heap, -Stack, -TimeoutMin in order, so 'run.ps1 SC1 SC2'
    # would bind SC1 to -Heap.
    [Parameter(Position = 0, ValueFromRemainingArguments = $true)]
    [string[]]$Scenarios
)

$ErrorActionPreference = 'Stop'

# Windows PowerShell 5.1 turns a native program's stderr into ErrorRecords, and with
# $ErrorActionPreference = 'Stop' a single harmless warning line aborts the script.
# java -version, git, and the runner itself all write to stderr as a matter of course,
# so every native call goes through here instead of being redirected inline.
function Invoke-Native {
    param([Parameter(Mandatory)][scriptblock]$Block)
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { & $Block } finally { $ErrorActionPreference = $prev }
}
Set-Location -Path $PSScriptRoot

if (-not $Scenarios -or $Scenarios.Count -eq 0) { $Scenarios = @('ALL') }

function Get-GitValue([string[]]$gitArgs, [string]$fallback) {
    try { $v = & git @gitArgs 2>$null; if ($LASTEXITCODE -eq 0 -and $v) { return "$v".Trim() } } catch { }
    return $fallback
}

# ---------------------------------------------------------------------------
#  Preflight. A run on the wrong machine and a heap larger than physical memory both
#  fail silently and void every number, so refuse before spending hours.
# ---------------------------------------------------------------------------
$cs0  = Get-CimInstance Win32_ComputerSystem
$cpu0 = Get-CimInstance Win32_Processor | Select-Object -First 1
$ramGb = [math]::Round($cs0.TotalPhysicalMemory / 1GB, 1)

# Heap request, e.g. '24g' or '8192m', converted to GB.
$heapGb = switch -Regex ($Heap) {
    '^(\d+(?:\.\d+)?)g$' { [double]$Matches[1]; break }
    '^(\d+)m$'             { [double]$Matches[1] / 1024; break }
    default { throw "Cannot parse -Heap '$Heap'; use a form like 24g or 8192m." }
}

Write-Host "==> machine: $($cs0.Name) | $($cpu0.Name) | $ramGb GB RAM"
Write-Host "==> heap   : $Heap ($heapGb GB), timeout $TimeoutMin min"

if ($heapGb -gt ($ramGb - 4)) {
    throw ("Heap $Heap does not fit in $ramGb GB of RAM. A heap larger than physical " +
           "memory does not fail fast: the JVM starts, the run thrashes the page file, " +
           "timings inflate by orders of magnitude, and it dies with a native allocation " +
           "failure. Every number from such a run is void. Lower -Heap or use the " +
           "measurement machine.")
}

$otherJava = @(Get-Process java -ErrorAction SilentlyContinue)
if ($otherJava.Count -gt 0 -and -not $AllowConcurrent -and -not $BuildOnly) {
    throw ("$($otherJava.Count) java process(es) already running (PID " +
           ($otherJava.Id -join ', ') + "). Concurrent runs compete for cores, memory " +
           "bandwidth, and page cache, which corrupts runtime and peak heap. Stop them " +
           "first, or pass -AllowConcurrent if this really is not a measurement run.")
}

if ($ExpectMachine -and $cs0.Name -ne $ExpectMachine) {
    throw "Expected to run on '$ExpectMachine' but this is '$($cs0.Name)'."
}

$commitShort = Get-GitValue @('rev-parse','--short','HEAD') 'nogit'
$commitFull  = Get-GitValue @('rev-parse','HEAD')           'nogit'
# Native stderr under $ErrorActionPreference='Stop' becomes a terminating error in
# Windows PowerShell 5.1, so a harmless git warning would abort the whole run. Capture it.
$clean = $true
try {
    $null = Invoke-Native { git diff --quiet 2>&1 }
    $clean = ($LASTEXITCODE -eq 0)
} catch { $clean = $false }
$dirty = if ($clean) { '' } else { '-dirty' }

$stamp  = (Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssZ')
$runId  = "run-$stamp-$commitShort$dirty"
$runDir = "results\$runId"
New-Item -ItemType Directory -Force -Path $runDir, 'build' | Out-Null

Write-Host "==> compiling"
Remove-Item 'build\*' -Recurse -Force -ErrorAction SilentlyContinue
$sources = Get-ChildItem -Path 'src' -Recurse -Filter '*.java' | ForEach-Object { $_.FullName }
$argFile = Join-Path $env:TEMP "husp-sources-$PID.txt"
# Two things javac argfiles are strict about, and Set-Content -Encoding UTF8 gets both
# wrong on Windows PowerShell 5.1: it prepends a BOM, which javac reads as part of the
# first flag, and unquoted paths break on directories containing spaces. Write the file
# with .NET using a BOM-less encoder, quote every path, and use forward slashes so the
# backslash is never taken as an escape.
$quoted = $sources | ForEach-Object { '"' + ($_.Replace([char]92, [char]47)) + '"' }
[System.IO.File]::WriteAllLines($argFile, $quoted, (New-Object System.Text.UTF8Encoding($false)))
Invoke-Native { javac -encoding UTF-8 -d build "@$argFile" }
if ($LASTEXITCODE -ne 0) { throw "javac failed" }
Remove-Item $argFile -Force

if ($BuildOnly) {
    Write-Host "==> build only; nothing measured"
    exit 0
}

Write-Host "==> writing manifest"
$cpu = (Get-CimInstance Win32_Processor | Select-Object -First 1)
$os  = Get-CimInstance Win32_OperatingSystem
$cs  = Get-CimInstance Win32_ComputerSystem
$javaVersion = ((Invoke-Native { java -version 2>&1 } | Select-Object -First 1) -replace '"', '').ToString().Trim()

$hashes = @{}
Get-ChildItem 'datasets' -Filter '*.txt' -File | ForEach-Object {
    $hashes[$_.Name] = (Get-FileHash $_.FullName -Algorithm SHA256).Hash.ToLower()
}

[ordered]@{
    run_id              = $runId
    commit              = $commitFull
    working_tree_clean  = $clean
    machine_name        = $cs0.Name
    ram_gb              = $ramGb
    started_utc         = $stamp
    scenarios           = ($Scenarios -join ' ')
    jvm_flags           = "-Xmx$Heap -Xss$Stack -Dtimeout.min=$TimeoutMin"
    timeout_minutes     = $TimeoutMin
    mem_mode            = $MemMode
    mem_interval_ms     = $MemIntervalMs
    java_version        = $javaVersion
    os                  = "$($os.Caption) $($os.Version)"
    cpu                 = $cpu.Name
    cpu_cores           = $cpu.NumberOfCores
    cpu_threads         = $cpu.NumberOfLogicalProcessors
    memory_bytes        = $cs.TotalPhysicalMemory
    power_plan          = (Invoke-Native { powercfg /getactivescheme 2>&1 } | Out-String).Trim()
    dataset_sha256      = $hashes
} | ConvertTo-Json -Depth 4 | Set-Content -Path "$runDir\manifest.json" -Encoding UTF8

if ($MemMode -eq 'live') {
    # One JVM per arm. In a shared JVM the heap is already grown by whatever ran before,
    # so the collector behaves differently and the reading depends on arm order.
    $memDir = Join-Path $runDir 'mem'
    New-Item -ItemType Directory -Force -Path $memDir | Out-Null
    Write-Host "==> live-heap campaign: $($MemArms.Count) arms, one JVM each -> $memDir"
    Write-Host "==> runtimes from these runs are void (runtime_ms = -3)"
    foreach ($arm in $MemArms) {
        Write-Host "--> arm: $arm"
        $safeArm = ($arm -replace '[^\w.-]', '_')
        $javaArgs = @(
            "-Xmx$Heap", "-Xss$Stack",
            "-Dtimeout.min=$TimeoutMin",
            "-Dmem.mode=live",
            "-Dmem.interval.ms=$MemIntervalMs",
            "-Dalgo.only=$arm",
            "-Dresults.dir=$($memDir -replace '\\','/')/",
            '-cp', 'build', 'AutomatedExperimentRunner'
        ) + $Scenarios
        Invoke-Native { java @javaArgs 2>&1 } | Tee-Object -FilePath "$memDir\console-$safeArm.log"
    }
} else {
    Write-Host "==> running: $($Scenarios -join ' ')"
    $javaArgs = @(
        "-Xmx$Heap", "-Xss$Stack",
        "-Dtimeout.min=$TimeoutMin",
        "-Dresults.dir=$($runDir -replace '\\','/')/",
        '-cp', 'build', 'AutomatedExperimentRunner'
    ) + $Scenarios
    Invoke-Native { java @javaArgs 2>&1 } | Tee-Object -FilePath "$runDir\console.log"
}


Write-Host ""
Write-Host "Results:  $runDir"
Write-Host "Summary:  python analysis\aggregate.py $runDir"
Write-Host "Commit these together to keep the numbers tied to commit $commitShort."
