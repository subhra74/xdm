<#
.SYNOPSIS
  Builds a self-contained XDM bundle (trimmed JRE + app) on Windows.
.DESCRIPTION
  PowerShell counterpart of packaging/build-bundle.sh. Requires JDK 17+ (21+
  recommended) and Maven on PATH. For msi/exe output, WiX v3 must be installed.
.PARAMETER Type
  app-image (default), msi or exe.
.PARAMETER SkipBuild
  Reuse xdm-app\target\xdm-app.jar instead of running "mvn package".
.PARAMETER WithLocales
  Include jdk.localedata (~15 MB bigger, full locale-aware formatting).
.PARAMETER Out
  Output directory (default: build\dist).
#>
[CmdletBinding()]
param(
  [ValidateSet('app-image','msi','exe')] [string]$Type = 'app-image',
  [switch]$SkipBuild,
  [switch]$WithLocales,
  [string]$Out
)

$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent $PSScriptRoot
Set-Location $ProjectRoot

# JDK modules the app actually needs - see the comment block in build-bundle.sh
# for how this list was derived and what was deliberately left out.
$Modules = 'java.desktop,java.logging,jdk.crypto.ec,jdk.unsupported'
if ($WithLocales) { $Modules += ',jdk.localedata' }

# JVM tuning flags baked into the launcher. See the comment block in
# build-bundle.sh for the measurements behind this set: SerialGC keeps
# JVM-committed memory at ~68 MB vs 82 (Shenandoah), 113 (ZGC), 120 (G1) and
# 195 (Parallel), because G1/Parallel size their card tables and remembered
# sets from the *maximum* heap and we deliberately do not set -Xmx.
$JavaOptions = @(
  # collector: minimum native overhead, heap returned to the OS quickly
  '-XX:+UseSerialGC'
  '-XX:MinHeapFreeRatio=5'
  '-XX:MaxHeapFreeRatio=10'
  '-Xms4m'
  '-XX:-AlwaysPreTouch'
  # AppMain runs a System.gc() every 15s; that is what triggers the shrink
  '-XX:-DisableExplicitGC'
  # class metadata
  '-XX:+ClassUnloading'
  '-XX:MinMetaspaceFreeRatio=1'
  '-XX:MaxMetaspaceFreeRatio=2'
  '-XX:MetaspaceReclaimPolicy=aggressive'
  '-XX:CompressedClassSpaceSize=64m'
  # JIT
  '-XX:TieredStopAtLevel=1'
  '-XX:CICompilerCount=1'
  # drop FlatLaf/Swing soft-referenced image caches on each GC
  '-XX:SoftRefLRUPolicyMSPerMB=0'
  # no hsperfdata mmap file
  '-XX:-UsePerfData'
  # bound the per-thread direct-buffer cache NIO keeps for heap-buffer writes
  '-Djdk.nio.maxCachedBufferSize=262144'
)

$AppName    = 'XDM'
$Vendor     = 'Xtreme Download Manager'
$MainClass  = 'xdm.app.AppMain'
$MainJar    = 'xdm-app.jar'
$BuildDir   = Join-Path $ProjectRoot 'build'
$RuntimeDir = Join-Path $BuildDir 'runtime'
$InputDir   = Join-Path $BuildDir 'input'
$DestDir    = if ($Out) { $Out } else { Join-Path $BuildDir 'dist' }

foreach ($tool in 'jlink','jpackage') {
  if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) {
    throw "$tool not found on PATH (JDK 17+ required)"
  }
}

$specVersion = (& java -XshowSettings:properties -version 2>&1 |
  Select-String 'java\.specification\.version = (\d+)').Matches.Groups[1].Value
$compress = if ([int]$specVersion -ge 21) { 'zip-9' } else { '2' }

if (-not $SkipBuild) {
  Write-Host '>> mvn clean package'
  & mvn -q clean package -DskipTests
  if ($LASTEXITCODE -ne 0) { throw 'Maven build failed' }
}

$JarPath = Join-Path $ProjectRoot "xdm-app\target\$MainJar"
if (-not (Test-Path $JarPath)) { throw "Missing $JarPath - run without -SkipBuild" }

$AppVersion = (Get-Content (Join-Path $ProjectRoot 'xdm-app\src\main\resources\app-version.json') -Raw |
  ConvertFrom-Json).currentVersion
if (-not $AppVersion) { $AppVersion = '1.0.0' }

Write-Host ">> jlink runtime: $Modules"
Remove-Item -Recurse -Force $RuntimeDir, $InputDir -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $InputDir, $DestDir | Out-Null
& jlink --add-modules $Modules --strip-debug --no-header-files --no-man-pages `
        --compress=$compress --output $RuntimeDir
if ($LASTEXITCODE -ne 0) { throw 'jlink failed' }

Copy-Item $JarPath (Join-Path $InputDir $MainJar)

$args = @(
  '--type', $Type
  '--name', $AppName
  '--app-version', $AppVersion
  '--vendor', $Vendor
  '--description', $Vendor
  '--input', $InputDir
  '--main-jar', $MainJar
  '--main-class', $MainClass
  '--runtime-image', $RuntimeDir
  '--dest', $DestDir
)
# FlatLaf loads a native library; JDK 22+ warns about that unless native access
# is enabled explicitly (and will block it in a future release).
if ([int]$specVersion -ge 22) { $JavaOptions += '--enable-native-access=ALL-UNNAMED' }
foreach ($o in $JavaOptions) { $args += @('--java-options', $o) }

$icon = Join-Path $PSScriptRoot 'icons\xdm.ico'
if (Test-Path $icon) { $args += @('--icon', $icon) }
if ($Type -ne 'app-image') {
  $args += @(
    '--win-menu', '--win-menu-group', $AppName, '--win-shortcut'
    '--win-dir-chooser', '--win-per-user-install'
    '--win-upgrade-uuid', '6f9619ff-8b86-d011-b42d-00c04fc964ff'
  )
}

Write-Host ">> jpackage --type $Type (windows, version $AppVersion)"
& jpackage @args
if ($LASTEXITCODE -ne 0) { throw 'jpackage failed' }

$size = [math]::Round((Get-ChildItem -Recurse $RuntimeDir | Measure-Object Length -Sum).Sum / 1MB, 1)
Write-Host ""
Write-Host "Runtime size: $size MB"
Get-ChildItem $DestDir | Select-Object -ExpandProperty Name
