<#
    SC2 at K=100, one repeat, one JVM per algorithm.

    Usage, from the repository root:

        .\campaign-sc2.ps1                       # all seven databases
        .\campaign-sc2.ps1 FIFA                  # one, to split the work across machines
        .\campaign-sc2.ps1 KOSARAK FIFA          # or several

    Why one JVM per arm: the arms shared a process until now. At five batches that was
    invisible. At a hundred, LEVIATHAN showed the price - IncUSP-Miner+ exhausted the heap,
    and Pre-HUSPM started next with total=160MB and died in 31 s having been given no
    chance. Isolated, the same Pre-HUSPM run finishes in 35 s. Run order was deciding the
    result, which makes it an artefact rather than a measurement.

    BMS1 is not in the list. SC2 grows past D_init and BMS1 cannot be mined there; the
    runner would skip it anyway and say so.

    Results land in results\campaign\<database>\<algorithm>\, one CSV and one console log
    per arm, so an interrupted campaign keeps everything it had already measured.
#>
param(
    [string[]] $Datasets = @('SIGN','LEVIATHAN','TAFENG','BIBLE','C8T1S5I8N5K','KOSARAK','FIFA'),
    # Eight hours per arm. An arm that wants more has told us something worth knowing, and
    # the CSV is written batch by batch, so what it measured before the cap survives.
    [int] $CapSeconds = 28800
)

$ErrorActionPreference = 'Stop'
$algos = @('Adaptive-Full','Pre-HUSPM','Pre-HUSPM-TSU','Remine-from-scratch','IncUSP-Miner+')

$java = Join-Path $env:JAVA_HOME 'bin\java.exe'
if (-not (Test-Path $java)) { $java = (Get-Command java).Source }
$ver = & $java -version 2>&1 | Select-Object -First 1
Write-Host "java: $java"
Write-Host "      $ver"
if ($ver -notmatch '"21\.') { throw "This study is pinned to JDK 21. Found: $ver" }

if (-not (Test-Path 'datasets\SIGN_seq.txt')) {
    throw "Databases are missing. Fetch them first:  .\fetch-datasets.ps1"
}

Write-Host "compiling..."
if (Test-Path build) { Remove-Item -Recurse -Force build }
New-Item -ItemType Directory build | Out-Null
$srcs = Get-ChildItem -Recurse -Filter *.java src | ForEach-Object { $_.FullName }
& (Join-Path (Split-Path $java) 'javac.exe') -encoding UTF-8 -d build $srcs
if ($LASTEXITCODE -ne 0) { throw "compile failed" }

Write-Host "=== SC2 K=100, repeats=1, isolated arms - $(Get-Date -f 'yyyy-MM-dd HH:mm:ss') ==="
foreach ($d in $Datasets) {
    foreach ($a in $algos) {
        $dir = "results\campaign\$d\$a"
        New-Item -ItemType Directory -Force $dir | Out-Null
        $start = Get-Date
        Write-Host "#### $d / $a bat dau $(Get-Date -f HH:mm:ss)"
        $p = Start-Process -FilePath $java -PassThru -NoNewWindow `
             -RedirectStandardOutput "$dir\run.log" -RedirectStandardError "$dir\run.err" `
             -ArgumentList @(
                '-Xmx24g','-Xss64m','-cp','build',
                "-Ddataset.only=$d", "-Dalgo.only=$a", '-Drepeats=1',
                "-Dresults.dir=$dir/", 'AutomatedExperimentRunner','SC2')
        if (-not $p.WaitForExit($CapSeconds * 1000)) {
            Write-Host "#### $d / $a CHAM TRAN ${CapSeconds}s - dung lai"
            $p.Kill(); $p.WaitForExit()
        }
        $secs = [int]((Get-Date) - $start).TotalSeconds
        Write-Host "#### $d / $a xong $(Get-Date -f HH:mm:ss)  rc=$($p.ExitCode)  $secs s"
    }
    Write-Host "======== $d XONG $(Get-Date -f 'yyyy-MM-dd HH:mm:ss') ========"
}
Write-Host "======== CHIEN DICH SC2 XONG $(Get-Date -f 'yyyy-MM-dd HH:mm:ss') ========"
