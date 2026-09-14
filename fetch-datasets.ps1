<#
  Fetch the quantitative sequence databases into datasets\.

  The databases are not kept in this repository. They are shared across several
  studies, so they live in one place and each study pins the exact release it
  measured on:

      https://github.com/tran-minh-thai/huspm-datasets

  Every file is checked against the SHA-256 manifest attached to that release, so
  a mismatch stops the script rather than producing numbers on the wrong bytes.

      .\fetch-datasets.ps1                                  pinned release
      .\fetch-datasets.ps1 -Release v1.1.1-seed42-lognormal  a different one
      .\fetch-datasets.ps1 -Force                            re-download
      .\fetch-datasets.ps1 -VerifyOnly                       check what is there

  TAFENG is not part of the release and is not downloaded. Ta-Feng is redistributed
  under terms this project does not rely on, so the database is built locally from the
  raw file and pinned by checksum instead; see tafeng\ and the README. This script
  reports whether it is present and correct, and -VerifyOnly fails if it is not.
#>

[CmdletBinding()]
param(
    # The release this study measures on. Changing it changes the data, and
    # therefore every result: re-run everything after changing it.
    [string]$Release = 'v1.2.1-measured-quantity',
    [string]$Dir     = 'datasets',
    [switch]$Force,
    [switch]$VerifyOnly
)

$ErrorActionPreference = 'Stop'
Set-Location -Path $PSScriptRoot

$repo    = 'tran-minh-thai/huspm-datasets'
$baseUrl = "https://github.com/$repo/releases/download/$Release"
$archive = "huspm-datasets-$Release.tar.gz"

New-Item -ItemType Directory -Force -Path $Dir | Out-Null
$manifestPath = Join-Path $Dir 'MANIFEST.sha256'

function Get-Asset([string]$name, [string]$dest) {
    Write-Host "    $name"
    Invoke-WebRequest -Uri "$baseUrl/$name" -OutFile $dest -UseBasicParsing
}

# The manifest belongs to the release, not to the default branch: the copy on main
# always describes the newest release and would silently disagree with a pinned one.
if (-not $VerifyOnly) {
    Write-Host "==> release $Release"
    Get-Asset 'MANIFEST.sha256' $manifestPath
}
if (-not (Test-Path $manifestPath)) {
    throw "No manifest in $Dir. Run without -VerifyOnly first."
}

$expected = @{}
foreach ($line in Get-Content $manifestPath) {
    if ($line -match '^\s*([0-9a-fA-F]{64})\s+(\S.*)$') {
        $expected[$Matches[2].Trim()] = $Matches[1].ToLower()
    }
}
if ($expected.Count -eq 0) { throw "Manifest is empty or malformed: $manifestPath" }

function Test-Datasets {
    $missing = 0; $wrong = 0
    foreach ($name in $expected.Keys) {
        $path = Join-Path $Dir $name
        if (-not (Test-Path $path)) { $missing++; continue }
        if ((Get-FileHash $path -Algorithm SHA256).Hash.ToLower() -ne $expected[$name]) { $wrong++ }
    }
    return @{ missing = $missing; wrong = $wrong; total = $expected.Count }
}

# TAFENG is built locally rather than downloaded, so its checksums live in this
# repository rather than in the release manifest: the study that consumes the data is
# what pins its version. Returns $true when both files are present and correct.
function Test-Tafeng {
    $sums = Join-Path $PSScriptRoot 'tafeng/TAFENG.sha256'
    if (-not (Test-Path $sums)) { return $true }        # no recipe here, nothing claimed
    $bad = @()
    foreach ($line in Get-Content $sums) {
        if ($line -notmatch '^\s*([0-9a-fA-F]{64})\s+(\S.*)$') { continue }
        $name = $Matches[2].Trim(); $want = $Matches[1].ToLower()
        $path = Join-Path $Dir $name
        if (-not (Test-Path $path)) { $bad += "$name missing"; continue }
        if ((Get-FileHash $path -Algorithm SHA256).Hash.ToLower() -ne $want) {
            $bad += "$name does not match"
        }
    }
    if ($bad.Count -eq 0) { Write-Host '==> TAFENG present and verified'; return $true }
    Write-Host "==> TAFENG not ready: $($bad -join ', ')"
    Write-Host '    Ta-Feng is not redistributed here. Get ta_feng_all_months_merged.csv'
    Write-Host '    (Kaggle: chiranjivdas09/ta-feng-grocery-dataset), then build it:'
    Write-Host "        python tafeng/build_tafeng.py --zip <archive.zip> --out-dir $Dir --name TAFENG"
    Write-Host '    The build is deterministic; tafeng/TAFENG.sha256 pins the result.'
    return $false
}

$state = Test-Datasets
if (-not $Force -and -not $VerifyOnly -and $state.missing -eq 0 -and $state.wrong -eq 0) {
    Write-Host "==> $($state.total) files already present and verified; nothing to do"
    if (-not (Test-Tafeng)) { exit 1 }
    exit 0
}
if ($VerifyOnly) {
    Write-Host ("==> {0} files: {1} verified, {2} missing, {3} corrupt" -f
                $state.total, ($state.total - $state.missing - $state.wrong), $state.missing, $state.wrong)
    $tafengOk = Test-Tafeng
    if ($state.missing -gt 0 -or $state.wrong -gt 0 -or -not $tafengOk) { exit 1 }
    exit 0
}

Write-Host "==> downloading"
$tmp = Join-Path ([System.IO.Path]::GetTempPath()) "husp-datasets-$PID"
New-Item -ItemType Directory -Force -Path $tmp | Out-Null
try {
    $tarPath = Join-Path $tmp $archive
    Get-Asset $archive $tarPath

    Write-Host "==> extracting"
    # tar ships with Windows 10 1803 and later.
    & tar -xzf $tarPath -C $tmp
    if ($LASTEXITCODE -ne 0) { throw "tar failed on $tarPath" }

    # Archives built on macOS carry ._NAME resource forks beside the real files.
    Get-ChildItem $tmp -Recurse -File |
        Where-Object { $expected.ContainsKey($_.Name) -and -not $_.Name.StartsWith('._') } |
        ForEach-Object { Copy-Item $_.FullName (Join-Path $Dir $_.Name) -Force }
} finally {
    Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Host "==> verifying"
$state = Test-Datasets
if ($state.missing -gt 0 -or $state.wrong -gt 0) {
    throw ("Verification failed: {0} missing, {1} corrupt out of {2}. " -f
           $state.missing, $state.wrong, $state.total) +
          "Do not measure on these files."
}
Write-Host ("==> {0} files verified against the manifest of {1}" -f $state.total, $Release)
Test-Tafeng | Out-Null
Write-Host "==> record this release tag alongside any results measured on it"
