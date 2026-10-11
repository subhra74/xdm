<#
.SYNOPSIS
  Checks (and optionally installs) what build-bundle.ps1 needs on a Windows build machine.
.DESCRIPTION
  Run once per machine, before build-bundle.ps1 (PACKAGING.md section 0). Without switches it only
  reports what is there and what is missing; nothing is installed or changed.

    JDK 25     BellSoft Liberica JDK 25 Standard, the exact build pinned in packaging\jdk.properties
               (PACKAGING.md section 3.1), one per target architecture: the JDK decides the MSI's
               architecture, and the class lists are recorded on that build.
    .NET SDK   needed only to install the WiX CLI (a dotnet tool); -Type msi only.
    WiX 7      the "wix" dotnet tool; -Type msi only. build-bundle.ps1 adds its extensions itself.

  Maven is not needed: the jar comes from the Mac (packaging/make-windows-kit.sh).
.PARAMETER Arch
  The architectures to build for. Default: x64 and arm64 on an ARM64 machine (x64 runs under
  emulation), x64 on an x64 machine.
.PARAMETER InstallJdk
  For each architecture without the pinned Liberica build, download its zip (URL and SHA-1 from
  packaging\jdk.properties), check the SHA-1 and unpack it to %USERPROFILE%\jdks\liberica-<version>-<arch>,
  where build-bundle.ps1 looks. Zips rather than MSIs: on Windows 11 ARM, the x64 and arm64 MSIs both install to
  C:\Program Files\BellSoft\LibericaJDK-25, so only one of them can be installed.
.PARAMETER InstallWix
  Install the .NET SDK (if missing) and the WiX 7 CLI, and offer to accept the WiX EULA.
.PARAMETER AcceptWixEula
  With -InstallWix: accept the WiX 7 OSMF EULA without asking (unattended builds, e.g. CI). Passing it
  is accepting https://wixtoolset.org/osmf/ for this machine.
#>
[CmdletBinding()]
param(
  [ValidateSet('x64','arm64')] [string[]]$Arch,
  [switch]$InstallJdk,
  [switch]$InstallWix,
  [switch]$AcceptWixEula
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'   # Invoke-WebRequest's progress bar slows downloads to a crawl
$missing = @()

$hostArch = (Get-ItemProperty 'HKLM:\SYSTEM\CurrentControlSet\Control\Session Manager\Environment').PROCESSOR_ARCHITECTURE
if (-not $Arch) { $Arch = if ($hostArch -eq 'ARM64') { @('arm64', 'x64') } else { @('x64') } }
if ($Arch -contains 'arm64' -and $hostArch -ne 'ARM64') { throw 'An arm64 build needs an ARM64 machine' }

function Update-SessionPath {
  # winget and dotnet installs change the registry PATH, not this session's.
  $env:Path = [Environment]::GetEnvironmentVariable('Path', 'Machine') + ';' +
              [Environment]::GetEnvironmentVariable('Path', 'User')
  $tools = Join-Path $env:USERPROFILE '.dotnet\tools'
  if ($env:Path -notlike "*$tools*") { $env:Path += ";$tools" }
}

function Read-JdkRelease([string]$Dir) {
  $file = Join-Path $Dir 'release'
  if (-not (Test-Path $file)) { return $null }
  $r = @{}
  Get-Content $file | ForEach-Object { if ($_ -match '^(\w+)="?(.*?)"?$') { $r[$Matches[1]] = $Matches[2] } }
  $r
}

function Get-JdkArch($Release) {
  switch ($Release['OS_ARCH']) {
    { $_ -in 'x86_64','amd64' }  { 'x64' }
    { $_ -in 'aarch64','arm64' } { 'arm64' }
    default { $null }
  }
}

$JdksDir = Join-Path $env:USERPROFILE 'jdks'

# The Liberica build pinned in packaging\jdk.properties (build-bundle.ps1 accepts only that one).
function Read-Pin([string]$Key) {
  $line = Get-Content (Join-Path $PSScriptRoot 'jdk.properties') | Where-Object { $_ -match "^$([regex]::Escape($Key))=" }
  if (-not $line) { throw "packaging\jdk.properties has no $Key" }
  ($line -split '=', 2)[1].Trim()
}
$PinnedVersion = Read-Pin 'liberica.version'
$PinnedRuntime = Read-Pin 'liberica.runtime'

# The same candidates, in the same order, as build-bundle.ps1 searches with -Arch.
function Find-Jdk25([string]$TargetArch) {
  $candidates = @()
  if ($env:JAVA_HOME) { $candidates += $env:JAVA_HOME }
  $candidates += @(Get-ChildItem (Join-Path $env:ProgramFiles 'BellSoft') -Directory -Filter 'LibericaJDK-25*' `
                     -ErrorAction SilentlyContinue | Sort-Object Name -Descending | ForEach-Object FullName)
  $candidates += @(Get-ChildItem $JdksDir -Directory -Filter 'liberica-25*' `
                     -ErrorAction SilentlyContinue | Sort-Object Name -Descending | ForEach-Object FullName)
  $onPath = Get-Command java.exe -ErrorAction SilentlyContinue
  if ($onPath) { $candidates += (Split-Path -Parent (Split-Path -Parent $onPath.Source)) }
  foreach ($dir in $candidates) {
    $r = Read-JdkRelease $dir
    if ($r -and $r['IMPLEMENTOR'] -match 'BellSoft' -and $r['JAVA_RUNTIME_VERSION'] -eq $PinnedRuntime -and
        (Get-JdkArch $r) -eq $TargetArch -and (Test-Path (Join-Path $dir 'bin\jpackage.exe'))) {
      return [pscustomobject]@{ Home = $dir; Release = $r }
    }
  }
  $null
}

function Install-LibericaZip([string]$TargetArch) {
  $url = Read-Pin "liberica.windows-$TargetArch.url"
  $expected = Read-Pin "liberica.windows-$TargetArch.sha1"
  $name = Split-Path $url -Leaf

  New-Item -ItemType Directory -Force -Path $JdksDir | Out-Null
  $zip = Join-Path $env:TEMP $name
  Write-Host "   downloading $url"
  Invoke-WebRequest $url -OutFile $zip -UseBasicParsing
  $sha1 = (Get-FileHash $zip -Algorithm SHA1).Hash
  if ($sha1 -ne $expected) { Remove-Item $zip; throw "SHA-1 mismatch for ${name}: $sha1, expected $expected" }

  # The zip holds one folder (jdk-25.0.x); unpack beside the others, then give it an arch-specific name.
  $dest = Join-Path $JdksDir "liberica-$($PinnedVersion -replace '\+', '_')-$TargetArch"
  $tmp = Join-Path $JdksDir ".unpack-$TargetArch"
  Remove-Item -Recurse -Force $tmp, $dest -ErrorAction SilentlyContinue
  Expand-Archive $zip -DestinationPath $tmp
  Move-Item (Get-ChildItem $tmp -Directory | Select-Object -First 1).FullName $dest
  Remove-Item -Recurse -Force $tmp, $zip
  Write-Host "   unpacked to $dest"
}

Update-SessionPath

# ---- JDK 25, one per architecture --------------------------------------------
foreach ($a in $Arch) {
  Write-Host ">> Liberica $PinnedRuntime for $a$(if ($a -eq 'x64' -and $hostArch -eq 'ARM64') { ' (runs under emulation)' })"
  $jdk = Find-Jdk25 $a
  if (-not $jdk -and $InstallJdk) {
    Install-LibericaZip $a
    $jdk = Find-Jdk25 $a
  }
  if ($jdk) {
    $r = $jdk.Release
    Write-Host "   ok: $($jdk.Home)  ($($r['IMPLEMENTOR']) $($r['JAVA_RUNTIME_VERSION']))"
  } else {
    Write-Host "   missing: rerun with -InstallJdk (unpacks the pinned $a zip into $JdksDir)"
    $missing += "Liberica $PinnedRuntime ($a)"
  }
}

function Test-Winget {
  if (Get-Command winget -ErrorAction SilentlyContinue) { return $true }
  Write-Host '   winget not found: install "App Installer" from the Microsoft Store, or install by hand (URL above)'
  $false
}

# ---- .NET SDK + WiX 7 (MSI only) --------------------------------------------
Write-Host '>> .NET SDK (for the WiX CLI)'
# @(...) around the whole if: an assignment from an if unrolls a one-element array into a plain string.
$sdks = @(if (Get-Command dotnet -ErrorAction SilentlyContinue) { & dotnet --list-sdks })
if (-not $sdks -and $InstallWix) {
  Write-Host '   installing the .NET 10 SDK - https://dotnet.microsoft.com/download'
  if (Test-Winget) {
    & winget install --id Microsoft.DotNet.SDK.10 --exact
    if ($LASTEXITCODE -ne 0) { throw 'winget could not install the .NET SDK' }
    Update-SessionPath
    $sdks = @(& dotnet --list-sdks)
  }
}
if ($sdks) { Write-Host "   ok: $($sdks[-1])" } else { Write-Host '   missing (MSI only): rerun with -InstallWix'; $missing += '.NET SDK' }

Write-Host '>> WiX 7'
$wixMajor = 0
if (Get-Command wix -ErrorAction SilentlyContinue) {
  $wixMajor = [int](((& wix --version) -split '[.+]')[0])
}
if ($wixMajor -lt 7 -and $InstallWix -and $sdks) {
  $verb = if ($wixMajor -gt 0) { 'update' } else { 'install' }
  Write-Host "   dotnet tool $verb -g wix"
  & dotnet tool $verb -g wix
  if ($LASTEXITCODE -ne 0) { throw "dotnet tool $verb -g wix failed" }
  Update-SessionPath
  $wixMajor = [int](((& wix --version) -split '[.+]')[0])
  if ($wixMajor -ge 7) {
    # WiX 7 refuses to build until its Open Source Maintenance Fee EULA is accepted, once per machine.
    $answer = if ($AcceptWixEula) { 'y' } else { Read-Host '   Accept the WiX 7 OSMF EULA (https://wixtoolset.org/osmf/)? [y/N]' }
    if ($answer -match '^[yY]') { & wix eula accept wix7 } else { Write-Host '   later: wix eula accept wix7' }
  }
}
if ($wixMajor -ge 7) { Write-Host "   ok: wix $(& wix --version)" }
elseif ($wixMajor -gt 0) { Write-Host "   too old (v$wixMajor): rerun with -InstallWix"; $missing += 'WiX 7' }
else { Write-Host '   missing (MSI only): rerun with -InstallWix'; $missing += 'WiX 7' }

# ---- running XDM ------------------------------------------------------------
if (Get-Process xdm-app -ErrorAction SilentlyContinue) {
  Write-Host '>> XDM is running: quit it from the tray before recording (it holds port 8597)'
}

Write-Host ''
if ($missing) {
  Write-Host "Missing: $($missing -join ', ')"
  Write-Host 'Without the .NET SDK and WiX, build-bundle.ps1 can still build the app image (no -Type msi).'
} else {
  # Each architecture has its own class list; record the ones the kit doesn't have yet.
  Write-Host 'Ready. Next (from the kit folder), build each architecture:'
  foreach ($a in $Arch) {
    $record = -not (Test-Path (Join-Path $PSScriptRoot "cds\windows-$a.classlist"))
    Write-Host "  powershell -ExecutionPolicy Bypass -File packaging\build-bundle.ps1 -Arch $a -Type msi$(if ($record) { ' -RecordServer http://<mac>:8780' })"
  }
  Write-Host '  (-RecordServer: the class-list recording server, PACKAGING.md "Recording the class list")'
}
