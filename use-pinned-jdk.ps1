<#
  Point this PowerShell session at the JDK that environment.lock.json pins.

  campaign.ps1 refuses any JVM other than the pinned one (runtime version and vendor). An
  installed JDK can be replaced by an update; this script keeps the pinned build in a
  directory of its own, which no installer or updater touches, and sets JAVA_HOME to it for
  the current process. campaign.ps1 takes java.exe and javac.exe from JAVA_HOME.

      .\use-pinned-jdk.ps1              use the pinned JDK, downloading it if it is missing
      .\use-pinned-jdk.ps1 -VerifyOnly  check that it is there, without downloading

  The JDK goes to <parent of this repository>\jdk\<zip name>\. The zip is the windows_zip of
  the lock file, checked against the SHA-256 recorded there before it is unpacked. The
  unpacked JDK is then asked for its java.runtime.version and java.vendor, which must match
  the lock file. Prints the JAVA_HOME it set.

  Needs tar (Windows 10 1803 and later).
#>

[CmdletBinding()]
param(
    [string]$Root     = (Join-Path (Split-Path -Parent $PSScriptRoot) 'jdk'),
    [string]$LockPath = (Join-Path $PSScriptRoot 'environment.lock.json'),
    [string]$JavaExe  = 'java.exe',
    [switch]$VerifyOnly
)

$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $LockPath)) { throw "No lock file at ${LockPath}." }
$lock    = Get-Content -Raw -LiteralPath $LockPath | ConvertFrom-Json
$want    = "$($lock.jdk.runtime_version)".Trim()
$vendor  = "$($lock.jdk.vendor)".Trim()
$zipName = "$($lock.jdk.windows_zip.name)".Trim()
$zipUrl  = "$($lock.jdk.windows_zip.url)".Trim()
$zipSha  = "$($lock.jdk.windows_zip.sha256)".Trim().ToLower()
if (-not ($want -and $vendor -and $zipName -and $zipUrl -and $zipSha)) {
    throw "${LockPath}: jdk.runtime_version, jdk.vendor and jdk.windows_zip (name, url, sha256) must all be set."
}
$base = [System.IO.Path]::GetFileNameWithoutExtension($zipName)
$dest = Join-Path $Root $base

# The JDK home is the one directory under $dest that holds bin\<java>.
function Find-JdkHome([string]$dir) {
    if (-not (Test-Path -LiteralPath $dir)) { return $null }
    $homes = @(Get-ChildItem -LiteralPath $dir -Directory |
               Where-Object { Test-Path -LiteralPath (Join-Path (Join-Path $_.FullName 'bin') $JavaExe) })
    if ($homes.Count -eq 1) { return $homes[0].FullName }
    if ($homes.Count -gt 1) { throw "$dir holds $($homes.Count) JDK directories; expected one. Remove $dir and run again." }
    return $null
}

$jdkHome = Find-JdkHome $dest
if (-not $jdkHome) {
    if ($VerifyOnly) { throw "The pinned JDK is not at $dest. Run .\use-pinned-jdk.ps1 without -VerifyOnly to fetch it." }
    if (-not (Test-Path -LiteralPath $Root)) { New-Item -ItemType Directory -Path $Root | Out-Null }
    $zipPath = Join-Path $Root $zipName
    $have = if (Test-Path -LiteralPath $zipPath) { (Get-FileHash -LiteralPath $zipPath -Algorithm SHA256).Hash.ToLower() } else { '' }
    if ($have -ne $zipSha) {
        # Windows PowerShell 5.1 does not offer TLS 1.2 by default, and its progress bar slows
        # the download many times over.
        [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
        $oldProgress = $ProgressPreference; $ProgressPreference = 'SilentlyContinue'
        try { Invoke-WebRequest -Uri $zipUrl -OutFile $zipPath -UseBasicParsing }
        finally { $ProgressPreference = $oldProgress }
        $have = (Get-FileHash -LiteralPath $zipPath -Algorithm SHA256).Hash.ToLower()
        if ($have -ne $zipSha) {
            throw "$zipPath has SHA-256 $have; ${LockPath} pins $zipSha. The file is kept for inspection; delete it to download again."
        }
    }
    # Unpack beside the destination and rename at the end, so an interrupted unpack never
    # leaves a directory that looks complete.
    $partial = "$dest.partial"
    if (Test-Path -LiteralPath $partial) { Remove-Item -LiteralPath $partial -Recurse -Force }
    New-Item -ItemType Directory -Path $partial | Out-Null
    & tar -xf $zipPath -C $partial
    if ($LASTEXITCODE -ne 0) { throw "tar failed on $zipPath (exit $LASTEXITCODE)." }
    if (Test-Path -LiteralPath $dest) { Remove-Item -LiteralPath $dest -Recurse -Force }
    Rename-Item -LiteralPath $partial -NewName (Split-Path -Leaf $dest)
    $jdkHome = Find-JdkHome $dest
    if (-not $jdkHome) { throw "$zipPath unpacked to $dest, but no directory there holds bin\$JavaExe." }
}

# Ask the JDK itself, as campaign.ps1 does: from the properties, not from 'java -version'.
# java prints them on stderr. Through '& java 2>&1', Windows PowerShell 5.1 turns each stderr
# line into an error record, which stops this script at the first one under 'Stop'; reading
# the process's streams directly behaves the same in every PowerShell.
$java = Join-Path (Join-Path $jdkHome 'bin') $JavaExe
$psi  = New-Object System.Diagnostics.ProcessStartInfo $java, '-XshowSettings:properties -version'
$psi.UseShellExecute = $false
$psi.RedirectStandardOutput = $true
$psi.RedirectStandardError  = $true
$proc   = [System.Diagnostics.Process]::Start($psi)
$stdout = $proc.StandardOutput.ReadToEndAsync()
$stderr = $proc.StandardError.ReadToEnd()
$proc.WaitForExit()
if ($proc.ExitCode -ne 0) { throw "$java -XshowSettings:properties -version exited with $($proc.ExitCode): $stderr" }
$props = ($stderr + "`n" + $stdout.Result) -split "`r?`n"
$runtime = (($props | Where-Object { $_ -match '^\s*java\.runtime\.version = ' } | Select-Object -First 1) -replace '^\s*java\.runtime\.version = ', '').Trim()
$vend    = (($props | Where-Object { $_ -match '^\s*java\.vendor = ' } | Select-Object -First 1) -replace '^\s*java\.vendor = ', '').Trim()
if ($runtime -ne $want) { throw "$java reports java.runtime.version '$runtime'; ${LockPath} pins '$want'." }
if ($vend -ne $vendor)  { throw "$java reports java.vendor '$vend'; ${LockPath} pins '$vendor'." }

$env:JAVA_HOME = $jdkHome
Write-Output $jdkHome
