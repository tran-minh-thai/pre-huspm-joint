<#
  Record the measurement machine: processor, memory, operating system, JDK and the JVM
  settings the campaign uses, into results/machine/<COMPUTERNAME>.json, then commit and
  push it.

      .\record-machine.ps1            record, commit, push
      .\record-machine.ps1 -NoGit     record only

  Run it on the machine that measures, before the campaign. The platform description used
  with the figures and tables is generated from this file (analysis/make_figures.py,
  export_platform), not typed. A field that cannot be read is written as "unavailable"
  rather than guessed, and the script says so.
#>
param([switch]$NoGit)

$ErrorActionPreference = 'Continue'
Set-Location -Path $PSScriptRoot
$unavailable = New-Object System.Collections.Generic.List[string]

function Read-Field($name, [scriptblock]$get) {
    try {
        $v = & $get
        if ($null -eq $v -or "$v".Trim() -eq '') { throw 'empty' }
        return $v
    } catch {
        $unavailable.Add($name)
        return 'unavailable'
    }
}

$hostName = if ($env:COMPUTERNAME) { $env:COMPUTERNAME } else { [System.Net.Dns]::GetHostName() }
$cpu = Read-Field 'cpu' { Get-CimInstance Win32_Processor -ErrorAction Stop | Select-Object -First 1 }
$os  = Read-Field 'os'  { Get-CimInstance Win32_OperatingSystem -ErrorAction Stop }
$cs  = Read-Field 'computer' { Get-CimInstance Win32_ComputerSystem -ErrorAction Stop }
$dimms = Read-Field 'memory modules' { @(Get-CimInstance Win32_PhysicalMemory -ErrorAction Stop) }

$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { $null }
if (-not ($java -and (Test-Path $java))) { $java = (Get-Command java -ErrorAction SilentlyContinue).Source }
$javaVersion = Read-Field 'java' { (& $java -version 2>&1 | ForEach-Object { "$_" }) -join ' | ' }
$javaProps = Read-Field 'java properties' {
    $p = & $java -XshowSettings:properties -version 2>&1 | ForEach-Object { "$_" }
    $want = 'java.runtime.version', 'java.vm.name', 'java.vendor', 'java.vm.version'
    $o = [ordered]@{}
    foreach ($line in $p) {
        foreach ($k in $want) { if ($line -match "^\s*$([regex]::Escape($k)) = (.*)$") { $o[$k] = $Matches[1].Trim() } }
    }
    if ($o.Count -eq 0) { throw 'none' }
    $o
}

$record = [ordered]@{
    machine          = $hostName
    recorded         = (Get-Date -Format 'yyyy-MM-ddTHH:mm:sszzz')
    cpu              = if ($cpu -is [string]) { $cpu } else { "$($cpu.Name)".Trim() }
    cpu_cores        = if ($cpu -is [string]) { $cpu } else { $cpu.NumberOfCores }
    cpu_threads      = if ($cpu -is [string]) { $cpu } else { $cpu.NumberOfLogicalProcessors }
    cpu_max_mhz      = if ($cpu -is [string]) { $cpu } else { $cpu.MaxClockSpeed }
    memory_gb        = if ($cs -is [string]) { $cs } else { [math]::Round($cs.TotalPhysicalMemory / 1GB, 1) }
    memory_modules   = if ($dimms -is [string]) { $dimms } else { @($dimms | ForEach-Object {
                           [ordered]@{ capacity_gb = [math]::Round($_.Capacity / 1GB, 1); speed_mts = $_.Speed;
                                       configured_mts = $_.ConfiguredClockSpeed } }) }
    os               = if ($os -is [string]) { $os } else { "$($os.Caption)".Trim() }
    os_version       = if ($os -is [string]) { $os } else { $os.Version }
    os_build         = if ($os -is [string]) { $os } else { $os.BuildNumber }
    os_display       = Read-Field 'os display version' {
                           (Get-ItemProperty 'HKLM:\SOFTWARE\Microsoft\Windows NT\CurrentVersion' -ErrorAction Stop).DisplayVersion }
    power_plan       = Read-Field 'power plan' { (powercfg /getactivescheme | Out-String).Trim() }
    java             = $javaVersion
    java_properties  = $javaProps
    jvm_flags        = '-Xmx48g -Xss64m'
    jvm_flags_source = 'campaign.ps1: -HeapGb 48 and -Xss64m on every arm, one JVM per arm'
    git_commit       = Read-Field 'git commit' { (git rev-parse HEAD 2>$null | Out-String).Trim() }
    unavailable      = $unavailable
}

$dir = 'results/machine'
New-Item -ItemType Directory -Force $dir | Out-Null
$path = Join-Path $dir "$hostName.json"
# UTF-8 without a byte-order mark, LF line ends: the file is read on other systems
$json = ($record | ConvertTo-Json -Depth 5) -replace "`r`n", "`n"
[System.IO.File]::WriteAllText((Join-Path (Get-Location) $path), $json + "`n", (New-Object System.Text.UTF8Encoding($false)))
Write-Host "wrote $path"
Get-Content $path | Write-Host
if ($unavailable.Count -gt 0) {
    Write-Host ("could not read: " + ($unavailable -join ', ') + " - written as 'unavailable'")
}

if (-not $NoGit) {
    git add $path
    git commit -q -m "Record the measurement machine $hostName" 2>&1 | Out-Null
    git push -q 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { Write-Host "(push failed; the record is committed locally)" }
}
