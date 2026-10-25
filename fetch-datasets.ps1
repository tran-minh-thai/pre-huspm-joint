<#
  Assemble the quantitative sequence databases into datasets\.

  The sequences come from the SPMF dataset collection and are not kept or redistributed
  here. The generated utilities are release v2.0.0-utilities of

      https://github.com/tran-minh-thai/huspm-datasets

  This script downloads that release, downloads the SPMF files it names, and runs the
  release's tools\assemble.py, which checks each SPMF file and each assembled file against
  the checksums of the release. The assembled files are then checked once more against
  datasets.sha256 in this repository, the checksums of the files every result was measured on.

      .\fetch-datasets.ps1               assemble what is missing
      .\fetch-datasets.ps1 -Force        assemble again
      .\fetch-datasets.ps1 -VerifyOnly   check what is there

  Needs tar (Windows 10 1803 and later) and Python 3. TAFENG is not part of the release; it
  is built locally from the Ta-Feng file (see tafeng\ and the README). This script reports
  whether it is present and correct, and -VerifyOnly fails if it is not.
#>

[CmdletBinding()]
param(
    [string]$Dir     = 'datasets',
    [string]$Python  = 'python',
    [string]$BaseUrl = 'https://github.com/tran-minh-thai/huspm-datasets/releases/download/v2.0.0-utilities',
    [switch]$Force,
    [switch]$VerifyOnly
)

$ErrorActionPreference = 'Stop'
Set-Location -Path $PSScriptRoot

$release       = 'v2.0.0-utilities'
# The release archive, pinned: a release asset can be replaced, this checksum cannot.
$archiveSha256 = '5d3ea0463ec1570b2b0ff530fbbd40ca204744443758ac620b6a3a777b31cad5'
$archive       = "huspm-datasets-$release.tar.gz"
$pinned        = Join-Path $PSScriptRoot 'datasets.sha256'

New-Item -ItemType Directory -Force -Path $Dir | Out-Null

function Read-Sums([string]$path) {
    $sums = @{}
    foreach ($line in Get-Content -LiteralPath $path) {
        if ($line -match '^\s*([0-9a-fA-F]{64})\s+(\S.*)$') { $sums[$Matches[2].Trim()] = $Matches[1].ToLower() }
    }
    return $sums
}

function Test-Files($sums) {
    $missing = 0; $wrong = 0
    foreach ($name in $sums.Keys) {
        $path = Join-Path $Dir $name
        if (-not (Test-Path -LiteralPath $path)) { $missing++; continue }
        if ((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLower() -ne $sums[$name]) { $wrong++ }
    }
    return @{ missing = $missing; wrong = $wrong; total = $sums.Count }
}

# TAFENG is built locally rather than assembled, so its checksums live in tafeng\.
function Test-Tafeng {
    $s = Test-Files (Read-Sums (Join-Path $PSScriptRoot 'tafeng/TAFENG.sha256'))
    if ($s.missing -eq 0 -and $s.wrong -eq 0) { Write-Host '==> TAFENG present and verified'; return $true }
    Write-Host "==> TAFENG not ready: $($s.missing) missing, $($s.wrong) not matching tafeng\TAFENG.sha256"
    Write-Host '    Ta-Feng is not redistributed here. Get ta_feng_all_months_merged.csv'
    Write-Host '    (Kaggle: chiranjivdas09/ta-feng-grocery-dataset), then build it:'
    Write-Host "        python tafeng/build_tafeng.py --zip <archive.zip> --out-dir $Dir --name TAFENG"
    Write-Host '    The build is deterministic; tafeng/TAFENG.sha256 pins the result.'
    return $false
}

$expected = Read-Sums $pinned
if ($expected.Count -eq 0) { throw "No checksum listed in $pinned" }
$state = Test-Files $expected

if ($VerifyOnly) {
    Write-Host ("==> {0} files: {1} verified, {2} missing, {3} not matching datasets.sha256" -f
                $state.total, ($state.total - $state.missing - $state.wrong), $state.missing, $state.wrong)
    $tafengOk = Test-Tafeng
    if ($state.missing -gt 0 -or $state.wrong -gt 0 -or -not $tafengOk) { exit 1 }
    exit 0
}
if (-not $Force -and $state.missing -eq 0 -and $state.wrong -eq 0) {
    Write-Host "==> $($state.total) files already present and verified; nothing to do"
    if (-not (Test-Tafeng)) { exit 1 }
    exit 0
}

$tmp = Join-Path ([System.IO.Path]::GetTempPath()) "husp-datasets-$PID"
New-Item -ItemType Directory -Force -Path $tmp | Out-Null
try {
    Write-Host "==> release $release"
    $tarPath = Join-Path $tmp $archive
    Invoke-WebRequest -Uri "$BaseUrl/$archive" -OutFile $tarPath -UseBasicParsing
    $have = (Get-FileHash -LiteralPath $tarPath -Algorithm SHA256).Hash.ToLower()
    if ($have -ne $archiveSha256) { throw "$archive has SHA-256 $have, expected $archiveSha256. Not using it." }
    & tar -xzf $tarPath -C $tmp
    if ($LASTEXITCODE -ne 0) { throw "tar failed on $tarPath" }
    $rel = Join-Path $tmp "huspm-datasets-$release"

    Write-Host "==> assembling from the SPMF files (kept in $Dir\spmf\)"
    & $Python (Join-Path $rel 'tools/assemble.py') --release $rel --spmf (Join-Path $Dir 'spmf') --out $Dir --download
    if ($LASTEXITCODE -ne 0) { throw "assemble.py stopped with exit code $LASTEXITCODE. Do not measure on these files." }
} finally {
    Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Host "==> verifying against datasets.sha256"
$state = Test-Files $expected
if ($state.missing -gt 0 -or $state.wrong -gt 0) {
    throw ("Verification failed: {0} missing, {1} not matching datasets.sha256, out of {2}. " -f
           $state.missing, $state.wrong, $state.total) + "Do not measure on these files."
}
Write-Host ("==> {0} files verified against datasets.sha256" -f $state.total)
Test-Tafeng | Out-Null
