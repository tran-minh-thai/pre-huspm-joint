<#
    Fetch SPMF's high-utility sequence files (SIGN, FIFA, BIBLE, Kosarak10k, BMS), used only by the
    FIDELITY scenario, into datasets\spmf-husp, check them against tools\spmf-husp.sha256, and
    convert them to this repository's format in datasets\spmf-husp\converted.

        .\fetch-spmf-husp.ps1

    Source: https://www.philippe-fournier-viger.com/spmf/index.php?link=datasets.php, section
    "Datasets for high-utility sequential rule mining or high-utility sequential pattern mining".
    The files are not redistributed here. A file whose SHA-256 differs stops the script.
#>
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
$base = 'https://www.philippe-fournier-viger.com/spmf/publicdatasets/husp'
$dir  = 'datasets\spmf-husp'
New-Item -ItemType Directory -Force -Path $dir | Out-Null
foreach ($line in Get-Content 'tools\spmf-husp.sha256') {
    if (-not $line.Trim()) { continue }
    $want, $name = $line -split '\s+', 2
    $path = Join-Path $dir $name
    if (-not (Test-Path $path)) {
        Write-Host "==> $name"
        Invoke-WebRequest -Uri "$base/$name" -OutFile $path -UseBasicParsing
    }
    $have = (Get-FileHash -Algorithm SHA256 -LiteralPath $path).Hash.ToLower()
    if ($have -ne $want) { throw "$name has SHA-256 $have, expected $want" }
}
$java = (Get-Command java -ErrorAction Stop).Source
$javac = Join-Path (Split-Path $java) 'javac.exe'
New-Item -ItemType Directory -Force -Path 'build' | Out-Null
& $javac -encoding UTF-8 -nowarn -d build (Get-ChildItem -Recurse src -Filter *.java | ForEach-Object FullName)
if ($LASTEXITCODE) { throw 'javac failed' }
& $java -cp build experiments.SpmfHuspConvert $dir "$dir\converted"
if ($LASTEXITCODE) { throw 'conversion failed' }
Write-Host "==> SPMF files checked and converted into $dir\converted"
