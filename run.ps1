<#
  Build, run the selected scenarios, and plot the results. Windows version of run.sh.

      .\run.ps1                        every scenario
      .\run.ps1 SC1 SC2                selected scenarios
      .\run.ps1 -Heap 48g SC1          larger heap
      .\run.ps1 -TimeoutMin 120 SC2    longer per-call limit
      .\run.ps1 TEST SC1               reduced grid, finishes in minutes

  Output goes to results\run-<timestamp>-<commit>\ with a manifest describing the code
  version and the machine, and figures to figures\run-<timestamp>-<commit>\.
#>

[CmdletBinding()]
param(
    [string]$Heap       = '24g',
    [string]$Stack      = '64m',
    [int]   $TimeoutMin = 60,
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$Scenarios
)

$ErrorActionPreference = 'Stop'
Set-Location -Path $PSScriptRoot

if (-not $Scenarios -or $Scenarios.Count -eq 0) { $Scenarios = @('ALL') }

function Get-GitValue([string[]]$gitArgs, [string]$fallback) {
    try { $v = & git @gitArgs 2>$null; if ($LASTEXITCODE -eq 0 -and $v) { return "$v".Trim() } } catch { }
    return $fallback
}

$commitShort = Get-GitValue @('rev-parse','--short','HEAD') 'nogit'
$commitFull  = Get-GitValue @('rev-parse','HEAD')           'nogit'
git diff --quiet 2>$null
$clean = ($LASTEXITCODE -eq 0)
$dirty = if ($clean) { '' } else { '-dirty' }

$stamp  = (Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssZ')
$runId  = "run-$stamp-$commitShort$dirty"
$runDir = "results\$runId"
$figDir = "figures\$runId"
New-Item -ItemType Directory -Force -Path $runDir, $figDir, 'build' | Out-Null

Write-Host "==> compiling"
Remove-Item 'build\*' -Recurse -Force -ErrorAction SilentlyContinue
$sources = Get-ChildItem -Path 'src' -Recurse -Filter '*.java' | ForEach-Object { $_.FullName }
$argFile = Join-Path $env:TEMP "husp-sources-$PID.txt"
$sources | Set-Content -Path $argFile -Encoding UTF8
& javac -encoding UTF-8 -d build "@$argFile"
if ($LASTEXITCODE -ne 0) { throw "javac failed" }
Remove-Item $argFile -Force

Write-Host "==> writing manifest"
$cpu = (Get-CimInstance Win32_Processor | Select-Object -First 1)
$os  = Get-CimInstance Win32_OperatingSystem
$cs  = Get-CimInstance Win32_ComputerSystem
$javaVersion = (& java -version 2>&1 | Select-Object -First 1) -replace '"', ''

$hashes = @{}
Get-ChildItem 'datasets' -Filter '*.txt' -File | ForEach-Object {
    $hashes[$_.Name] = (Get-FileHash $_.FullName -Algorithm SHA256).Hash.ToLower()
}

[ordered]@{
    run_id              = $runId
    commit              = $commitFull
    working_tree_clean  = $clean
    started_utc         = $stamp
    scenarios           = ($Scenarios -join ' ')
    jvm_flags           = "-Xmx$Heap -Xss$Stack -Dtimeout.min=$TimeoutMin"
    timeout_minutes     = $TimeoutMin
    java_version        = $javaVersion
    os                  = "$($os.Caption) $($os.Version)"
    cpu                 = $cpu.Name
    cpu_cores           = $cpu.NumberOfCores
    cpu_threads         = $cpu.NumberOfLogicalProcessors
    memory_bytes        = $cs.TotalPhysicalMemory
    power_plan          = (powercfg /getactivescheme 2>$null | Out-String).Trim()
    dataset_sha256      = $hashes
} | ConvertTo-Json -Depth 4 | Set-Content -Path "$runDir\manifest.json" -Encoding UTF8

Write-Host "==> running: $($Scenarios -join ' ')"
$javaArgs = @(
    "-Xmx$Heap", "-Xss$Stack",
    "-Dtimeout.min=$TimeoutMin",
    "-Dresults.dir=$($runDir -replace '\\','/')/",
    '-cp', 'build', 'AutomatedExperimentRunner'
) + $Scenarios
& java @javaArgs 2>&1 | Tee-Object -FilePath "$runDir\console.log"

Write-Host "==> building figures"
$env:HUSP_DATA_DIR = (Resolve-Path $runDir).Path
$env:HUSP_OUT_DIR  = (Resolve-Path $figDir).Path
& python analysis\make_figures.py
Remove-Item Env:\HUSP_DATA_DIR, Env:\HUSP_OUT_DIR -ErrorAction SilentlyContinue

Write-Host ""
Write-Host "Results:  $runDir"
Write-Host "Figures:  $figDir"
Write-Host "Commit these together to keep the numbers tied to commit $commitShort."
