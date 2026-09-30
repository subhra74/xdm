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
.PARAMETER RecordClasses
  Before packaging, run XDM on the bundled runtime to record
  packaging\cds\windows.classlist for the AppCDS archive (see APPCDS.md).
#>
[CmdletBinding()]
param(
  [ValidateSet('app-image','msi','exe')] [string]$Type = 'app-image',
  [switch]$SkipBuild,
  [switch]$WithLocales,
  [string]$Out,
  [switch]$RecordClasses
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
  # JIT: C1 for everything, C2 only for the crypto methods in jit-directives.json
  # (the directive itself is added after the AppCDS step; see build-bundle.sh)
  '-XX:CICompilerCount=2'
  '-XX:ReservedCodeCacheSize=32m'
  # drop FlatLaf/Swing soft-referenced image caches on each GC
  '-XX:SoftRefLRUPolicyMSPerMB=0'
  # no hsperfdata mmap file
  '-XX:-UsePerfData'
  # bound the per-thread direct-buffer cache NIO keeps for heap-buffer writes
  '-Djdk.nio.maxCachedBufferSize=262144'
)

# The app is "Xtreme Download Manager" - the bundle, the installer and the Start-menu shortcut all
# use it. What XDM registers with the OS (login entry, xdm-app:// handler) points at a second
# launcher, "xdm-app.exe", added with --add-launcher: a stable, space-free binary name. That extra
# launcher gets no shortcut of its own, so the Start menu shows exactly one, named properly.
$AppName      = 'Xtreme Download Manager'
$LauncherName = 'xdm-app'
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

# Version-gated flags join $JavaOptions here, before anything runs the bundled
# runtime: the AppCDS dump below must see the exact flags the launcher will use.
# FlatLaf loads a native library; JDK 22+ warns about that unless native access
# is enabled explicitly (and will block it in a future release).
if ([int]$specVersion -ge 22) { $JavaOptions += '--enable-native-access=ALL-UNNAMED' }
# 64-bit object headers instead of 96-bit: product flag since JDK 25, default
# from JDK 27 (JEP 534), experimental before that - so gate on the JDK version.
if ([int]$specVersion -ge 25) { $JavaOptions += '-XX:+UseCompactObjectHeaders' }

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

# --------------------------------------------------------------------------
# Drop the bundled natives for every platform except this build's target.
#
# FlatLaf ships its native library for seven platforms; a bundle runs on exactly
# one. Only the copy under build\input that jpackage wraps is trimmed.
# xdm-app\target\xdm-app.jar keeps every native, so the fat jar stays runnable
# on any platform when it is shared or launched on its own with `java -jar`.
#
# FlatLaf resolves its library through an <os>-<arch> classifier in the entry
# name, so keeping just the matching entry is enough.
# --------------------------------------------------------------------------
function Remove-ForeignNatives {
  param([string]$Jar)

  switch ($env:PROCESSOR_ARCHITECTURE) {
    'ARM64' { $flArch = 'arm64'  }
    'AMD64' { $flArch = 'x86_64' }
    default {
      Write-Host ">> unrecognised PROCESSOR_ARCHITECTURE '$env:PROCESSOR_ARCHITECTURE' - bundling natives for all platforms"
      return
    }
  }

  $flKeep = "com/formdev/flatlaf/natives/*-windows-$flArch.*"

  Add-Type -AssemblyName System.IO.Compression.FileSystem
  $before = (Get-Item $Jar).Length
  $zip = [System.IO.Compression.ZipFile]::Open($Jar, 'Update')
  try {
    $doomed = @($zip.Entries | Where-Object {
      $_.FullName -match '\.(dylib|jnilib|so|dll)$' -and
      -not ($_.FullName -like $flKeep)
    })
    foreach ($entry in $doomed) { $entry.Delete() }
  } finally {
    $zip.Dispose()
  }
  $saved = [math]::Round(($before - (Get-Item $Jar).Length) / 1KB)
  Write-Host ">> stripped $($doomed.Count) foreign natives (windows/$env:PROCESSOR_ARCHITECTURE): $saved KB"
}

Write-Host ">> jlink runtime: $Modules"
Remove-Item -Recurse -Force $RuntimeDir, $InputDir -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $InputDir, $DestDir | Out-Null
& jlink --add-modules $Modules --strip-debug --no-header-files --no-man-pages `
        --compress=$compress --output $RuntimeDir
if ($LASTEXITCODE -ne 0) { throw 'jlink failed' }

Copy-Item $JarPath (Join-Path $InputDir $MainJar)
Remove-ForeignNatives (Join-Path $InputDir $MainJar)
$JitDirectives = 'jit-directives.json'
Copy-Item (Join-Path $PSScriptRoot $JitDirectives) $InputDir

# AppCDS archive - see the matching block in build-bundle.sh and APPCDS.md. The
# JVM uses the archive only while the jar's size and mtime match the dump, so the
# jar is pinned to a fixed time and the app re-pins it on start (CdsJarPin).
$CdsEpoch    = 1577836800                                  # 2020-01-01T00:00:00Z
$CdsPinned   = [datetime]::new(2020, 1, 1, 0, 0, 0, [DateTimeKind]::Utc)
$CdsList     = Join-Path $PSScriptRoot 'cds\windows.classlist'
$CdsArchive  = 'xdm.jsa'
$CdsProperty = 'xdm.cds.mtime'                             # read by CdsJarPin
$BundledJava = Join-Path $RuntimeDir 'bin\java.exe'
$InputJar    = Join-Path $InputDir $MainJar
(Get-Item $InputJar).LastWriteTimeUtc = $CdsPinned

if ($RecordClasses) {
  New-Item -ItemType Directory -Force -Path (Split-Path $CdsList) | Out-Null
  Write-Host ">> recording windows.classlist: quit any running XDM first, then use this one"
  Write-Host ">>   as users do - run a real download to completion - and quit it from the tray"
  & $BundledJava @JavaOptions '-XX:+UnlockDiagnosticVMOptions' `
      "-XX:CompilerDirectivesFile=$(Join-Path $InputDir $JitDirectives)" `
      "-XX:DumpLoadedClassList=$CdsList" -cp $InputJar $MainClass
}

if ((Test-Path $CdsList) -and (Get-Item $CdsList).Length -gt 0) {
  Write-Host '>> dumping the AppCDS archive from windows.classlist'
  & $BundledJava -Xshare:dump @JavaOptions "-XX:SharedClassListFile=$CdsList" `
      "-XX:SharedArchiveFile=$(Join-Path $InputDir $CdsArchive)" -cp $InputJar | Out-Null
  if ($LASTEXITCODE -ne 0) { throw 'AppCDS dump failed' }
  # $APPDIR is expanded by the jpackage launcher; single quotes keep PowerShell off it.
  $JavaOptions += @(
    ('-XX:SharedArchiveFile=$APPDIR\' + $CdsArchive)
    '-XX:+UnlockDiagnosticVMOptions'
    '-XX:ArchiveRelocationMode=0'
    "-D$CdsProperty=$CdsEpoch"
  )
} else {
  Write-Host '>> no packaging\cds\windows.classlist - building without an AppCDS archive (see -RecordClasses)'
}

# JIT directive: added only now, because $APPDIR exists only in the launcher and the AppCDS dump
# above runs the bundled java directly. JitOverride's "Full JIT" setting drops it again.
$JavaOptions += @(
  '-XX:+UnlockDiagnosticVMOptions'
  ('-XX:CompilerDirectivesFile=$APPDIR\' + $JitDirectives)
)

# An added launcher inherits the main class, jar and java-options; this file only keeps it out of
# the menus, so "xdm-app" never shows up as a second Start-menu entry.
$LauncherProps = Join-Path $BuildDir "$LauncherName.properties"
Set-Content -Path $LauncherProps -Value @('win-shortcut=false', 'win-menu=false')

$args = @(
  '--type', $Type
  '--name', $AppName
  '--add-launcher', "$LauncherName=$LauncherProps"
  '--app-version', $AppVersion
  '--vendor', $Vendor
  '--description', $AppName
  '--input', $InputDir
  '--main-jar', $MainJar
  '--main-class', $MainClass
  '--runtime-image', $RuntimeDir
  '--dest', $DestDir
)
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

# jpackage copies the jar with a fresh mtime; pin the image's copy so a fresh
# image uses the archive from its very first start.
if ($Type -eq 'app-image' -and (Test-Path (Join-Path $InputDir $CdsArchive))) {
  $imageJar = Join-Path $DestDir "$AppName\app\$MainJar"
  if (Test-Path $imageJar) { (Get-Item $imageJar).LastWriteTimeUtc = $CdsPinned }
}

$size = [math]::Round((Get-ChildItem -Recurse $RuntimeDir | Measure-Object Length -Sum).Sum / 1MB, 1)
Write-Host ""
Write-Host "Runtime size: $size MB"
Get-ChildItem $DestDir | Select-Object -ExpandProperty Name
