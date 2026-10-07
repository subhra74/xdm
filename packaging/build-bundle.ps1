<#
.SYNOPSIS
  Builds the Windows XDM bundle (trimmed JRE + app) and, optionally, its MSI. x64 or arm64.
.DESCRIPTION
  The Windows build (PACKAGING.md sect. 3, sect. 5). Everything that is architecture-specific - the jlink
  runtime, the jpackage launcher, the FlatLaf native, the AppCDS archive - comes from the JDK in
  -JavaHome, so the target architecture is that JDK's, not the machine's:

    x64    an x64 JDK, on an x64 machine or under emulation on Windows 11 ARM
    arm64  an arm64 JDK, on an ARM64 machine

  Running an x64 JDK under emulation is fine: the archive holds class metadata only, and the JVM
  validates it by JDK build, jar size/mtime and VM flags, never by CPU (APPCDS.md sect. 10).

  Needs JDK 25 (BellSoft Liberica Standard, PACKAGING.md sect. 3.1). The app jar is the same on every
  platform, so it can be built elsewhere: either copy it to xdm-app\target\xdm-app.jar and pass
  -SkipBuild, or run this script from the Windows kit made by packaging/make-windows-kit.sh (no
  pom.xml next to packaging\, so there is no Maven step and the jar is the kit's xdm-app.jar).
  -Type msi also needs the WiX 7 CLI ("dotnet tool install -g wix") with its EULA accepted;
  packaging\setup-windows.ps1 checks for and installs both.
.PARAMETER Type
  app-image (default) or msi. The MSI is built with WiX from the app image (packaging\windows\xdm.wxs).
.PARAMETER Arch
  x64 or arm64. Picks a JDK of that architecture (see -JavaHome); with -JavaHome, it must match.
  Without it, the target is the first JDK found. Both can be built on one ARM64 machine: run once
  with -Arch arm64 and once with -Arch x64; each has its own build\windows-<arch> and dist folder.
.PARAMETER JavaHome
  The JDK to link the runtime from. Defaults to the first JDK 25 (of -Arch, when given) among
  JAVA_HOME, Program Files\BellSoft\LibericaJDK-25*, %USERPROFILE%\jdks\liberica-25* (unpacked by
  setup-windows.ps1) and the java.exe on PATH.
.PARAMETER SkipBuild
  Reuse xdm-app\target\xdm-app.jar instead of running "mvn package". Implied in a kit.
.PARAMETER Jar
  The app jar to package. Defaults to xdm-app\target\xdm-app.jar, or xdm-app.jar in a kit.
.PARAMETER WithLocales
  Include jdk.localedata (~15 MB bigger, full locale-aware formatting).
.PARAMETER Out
  Output directory (default: build\dist\windows-<arch>).
.PARAMETER RecordClasses
  Before packaging, run XDM on the bundled runtime to record
  packaging\cds\windows-<arch>.classlist for the AppCDS archive (see APPCDS.md). With -RecordServer
  the session is scripted and needs no hands; without it, use XDM yourself and quit it from the tray.
.PARAMETER RecordServer
  With -RecordClasses: the URL of packaging\recording\RecordServer.java (e.g. http://192.168.1.20:8780).
  XDM then runs the recording session against it on a fresh profile and exits by itself; the build
  stops if any step of the session failed.
#>
[CmdletBinding()]
param(
  [ValidateSet('app-image','msi')] [string]$Type = 'app-image',
  [ValidateSet('x64','arm64')] [string]$Arch,
  [string]$JavaHome,
  [switch]$SkipBuild,
  [string]$Jar,
  [switch]$WithLocales,
  [string]$Out,
  [switch]$RecordClasses,
  [string]$RecordServer
)

$ErrorActionPreference = 'Stop'
if ($RecordServer) { $RecordClasses = $true }
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
  # >= 5m: the AppCDS archive's ~3.4 MB of pre-built heap objects must fit the initial heap, or the
  # JVM skips them (and the archived module graph) without a word. 4m was just too small.
  '-Xms6m'
  '-XX:-AlwaysPreTouch'
  # AppMain runs a System.gc() every 15s; that is what triggers the shrink
  '-XX:-DisableExplicitGC'
  # class metadata
  '-XX:+ClassUnloading'
  '-XX:MinMetaspaceFreeRatio=1'
  '-XX:MaxMetaspaceFreeRatio=2'
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
  # FlatLaf loads a native library; JDK 22+ warns unless native access is enabled
  '--enable-native-access=ALL-UNNAMED'
  # 64-bit object headers instead of 96-bit (product flag since JDK 25)
  '-XX:+UseCompactObjectHeaders'
)

# One launcher, xdm-app.exe (PACKAGING.md sect. 3.3): the MSI's shortcuts carry the display name, and
# the login entry and the xdm-app:// handler point at this same stable, space-free name.
$AppName     = 'Xtreme Download Manager'
$Launcher    = 'xdm-app'
$Vendor      = 'Subhra Das Gupta'
$MainClass   = 'xdm.app.AppMain'
$MainJar     = 'xdm-app.jar'
$BuildDir    = Join-Path $ProjectRoot 'build'

# ---- JDK and target architecture ------------------------------------------
# The JDK's own record of what it is: OS_ARCH="x86_64", JAVA_VERSION="25.0.1", IMPLEMENTOR="BellSoft".
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

if (-not $JavaHome) {
  # Candidates in order: JAVA_HOME, Liberica 25 installed by its MSI (which doesn't set JAVA_HOME
  # unless asked to), the zips setup-windows.ps1 unpacks (one per architecture), java.exe on PATH.
  # With -Arch, the first JDK 25 of that architecture wins, so one machine can build both MSIs.
  $candidates = @()
  if ($env:JAVA_HOME) { $candidates += $env:JAVA_HOME }
  $candidates += @(Get-ChildItem (Join-Path $env:ProgramFiles 'BellSoft') -Directory -Filter 'LibericaJDK-25*' `
                     -ErrorAction SilentlyContinue | Sort-Object Name -Descending | ForEach-Object FullName)
  $candidates += @(Get-ChildItem (Join-Path $env:USERPROFILE 'jdks') -Directory -Filter 'liberica-25*' `
                     -ErrorAction SilentlyContinue | Sort-Object Name -Descending | ForEach-Object FullName)
  $onPath = Get-Command java.exe -ErrorAction SilentlyContinue
  if ($onPath) { $candidates += (Split-Path -Parent (Split-Path -Parent $onPath.Source)) }
  foreach ($dir in $candidates) {
    $r = Read-JdkRelease $dir
    if (-not $r -or [int](($r['JAVA_VERSION'] -split '\.')[0]) -lt 25) { continue }
    if ($Arch -and (Get-JdkArch $r) -ne $Arch) { continue }
    $JavaHome = $dir
    break
  }
  if (-not $JavaHome) {
    throw "No $(if ($Arch) { "$Arch " })JDK 25 found: run packaging\setup-windows.ps1 -InstallJdk, or pass -JavaHome"
  }
}
$Java     = Join-Path $JavaHome 'bin\java.exe'
$Jlink    = Join-Path $JavaHome 'bin\jlink.exe'
$Jpackage = Join-Path $JavaHome 'bin\jpackage.exe'
foreach ($tool in $Java, $Jlink, $Jpackage) {
  if (-not (Test-Path $tool)) { throw "$tool not found - -JavaHome must be a full JDK" }
}

$release = Read-JdkRelease $JavaHome
if (-not $release) { throw "$JavaHome has no release file - -JavaHome must be a full JDK" }
$jdkArch = Get-JdkArch $release
if (-not $jdkArch) { throw "Unsupported JDK architecture '$($release['OS_ARCH'])' in $JavaHome" }
if ($Arch -and $Arch -ne $jdkArch) {
  throw "-Arch $Arch needs an $Arch JDK, but $JavaHome is $jdkArch"
}
$Arch = $jdkArch
if ([int]($release['JAVA_VERSION'] -split '\.')[0] -lt 25) {
  throw "JDK 25 or later required; $JavaHome is $($release['JAVA_VERSION'])"
}
if ($release['IMPLEMENTOR'] -notmatch 'BellSoft') {
  Write-Host ">> note: $JavaHome is $($release['IMPLEMENTOR']), not Liberica (PACKAGING.md sect. 3.1)"
}

# The machine's own architecture, whatever this PowerShell process runs as.
$hostArch = (Get-ItemProperty 'HKLM:\SYSTEM\CurrentControlSet\Control\Session Manager\Environment').PROCESSOR_ARCHITECTURE
if ($Arch -eq 'arm64' -and $hostArch -ne 'ARM64') { throw 'An arm64 build must run on an ARM64 machine' }
$emulated = ($Arch -eq 'x64' -and $hostArch -eq 'ARM64')
Write-Host ">> target windows-$Arch, JDK $($release['JAVA_VERSION']) ($($release['IMPLEMENTOR']))$(if ($emulated) { ', x64 under emulation' })"

$RuntimeDir = Join-Path $BuildDir "windows-$Arch\runtime"
$InputDir   = Join-Path $BuildDir "windows-$Arch\input"
$DestDir    = if ($Out) { $Out } else { Join-Path $BuildDir "dist\windows-$Arch" }
$ImageDir   = Join-Path $DestDir $Launcher

# ---- app jar ---------------------------------------------------------------
# A kit (packaging/make-windows-kit.sh) has no pom.xml: the jar comes with it, built on another machine.
$IsKit = -not (Test-Path (Join-Path $ProjectRoot 'pom.xml'))
if ($IsKit -or $Jar) { $SkipBuild = $true }
if (-not $SkipBuild) {
  Write-Host '>> mvn clean package'
  & mvn -q clean package -DskipTests
  if ($LASTEXITCODE -ne 0) { throw 'Maven build failed' }
}

$JarPath = if ($Jar) { (Resolve-Path $Jar).Path }
           elseif ($IsKit) { Join-Path $ProjectRoot $MainJar }
           else { Join-Path $ProjectRoot "xdm-app\target\$MainJar" }
if (-not (Test-Path $JarPath)) { throw "Missing $JarPath$(if (-not $IsKit) { ' - run without -SkipBuild' })" }

# The version the jar itself carries (its app-version.json), so a jar built elsewhere can't
# be packaged under this checkout's version.
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($JarPath)
try {
  $entry = $zip.GetEntry('app-version.json')
  if (-not $entry) { throw "$JarPath has no app-version.json" }
  $reader = New-Object System.IO.StreamReader($entry.Open())
  try { $AppVersion = ($reader.ReadToEnd() | ConvertFrom-Json).currentVersion } finally { $reader.Dispose() }
} finally {
  $zip.Dispose()
}
if (-not $AppVersion) { $AppVersion = '1.0.0' }

# --------------------------------------------------------------------------
# Drop the bundled natives for every platform except this build's target.
#
# FlatLaf ships its native library for seven platforms; a bundle runs on exactly
# one. Only the copy under build\...\input that jpackage wraps is trimmed.
# xdm-app\target\xdm-app.jar keeps every native, so the fat jar stays runnable
# on any platform when it is shared or launched on its own with `java -jar`.
#
# FlatLaf resolves its library through an <os>-<arch> classifier in the entry
# name, so keeping just the matching entry is enough. The arch is the target's,
# not this process's: an x64 build on an ARM64 machine needs the x64 DLL.
# --------------------------------------------------------------------------
function Remove-ForeignNatives {
  param([string]$Jar)

  $flArch = if ($Arch -eq 'arm64') { 'arm64' } else { 'x86_64' }
  $flKeep = "com/formdev/flatlaf/natives/*-windows-$flArch.*"

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
  Write-Host ">> stripped $($doomed.Count) foreign natives (windows-$flArch): $saved KB"
}

Write-Host ">> jlink runtime: $Modules"
Remove-Item -Recurse -Force $RuntimeDir, $InputDir, $ImageDir -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $InputDir, $DestDir | Out-Null
# --vm server: Liberica's Windows x64 JDK also ships the client VM (bin\client\jvm.dll, 8.7 MB), which is
# C1-only - no C2, so none of the crypto intrinsics TLS depends on (PACKAGING.md sect. 3.2).
& $Jlink --add-modules $Modules --vm server --strip-debug --no-header-files --no-man-pages `
         --compress=zip-9 --output $RuntimeDir
if ($LASTEXITCODE -ne 0) { throw 'jlink failed' }

# Files the runtime carries but never uses on Windows 10+ (which JDK 25 requires):
# - the app-local Universal CRT: ucrtbase.dll and the ~40 api-ms-win-*.dll forwarders (~2 MB). Since
#   Windows 10 the loader always uses the system UCRT, even when an app ships its own copy. The
#   vcruntime140*.dll / msvcp140.dll redistributables are not part of it and stay.
# - keytool.exe and javaw.exe: XDM starts through the jpackage launcher and never runs them. java.exe
#   stays: the AppCDS steps below and the MSI's heap-file actions run it (PACKAGING.md sect. 5.7).
$unused = @(Get-ChildItem (Join-Path $RuntimeDir 'bin') -File | Where-Object {
  $_.Name -like 'api-ms-win-*.dll' -or $_.Name -in 'ucrtbase.dll', 'keytool.exe', 'javaw.exe'
})
$unusedKb = [math]::Round(($unused | Measure-Object Length -Sum).Sum / 1KB)
$unused | Remove-Item
Write-Host ">> removed $($unused.Count) unused runtime files (UCRT forwarders, keytool, javaw): $unusedKb KB"

Copy-Item $JarPath (Join-Path $InputDir $MainJar)
Remove-ForeignNatives (Join-Path $InputDir $MainJar)

# FlatLaf's DLL as a file in app\, loaded from there through -Dflatlaf.nativeLibraryPath (added with
# the JIT directive below). Otherwise FlatLaf copies it out of the jar into %TEMP%\flatlaf.temp on
# every start and loads it from there - a fresh DLL in %TEMP% is the kind of thing antivirus flags.
# The name is the one FlatLaf asks for, System.mapLibraryName("flatlaf-windows-<arch>"). The jar
# keeps its copy, which FlatLaf falls back to (logging it) if this file goes missing.
$flArch = if ($Arch -eq 'arm64') { 'arm64' } else { 'x86_64' }
$FlatLafDll = "flatlaf-windows-$flArch.dll"
$zip = [System.IO.Compression.ZipFile]::OpenRead((Join-Path $InputDir $MainJar))
try {
  $entry = $zip.GetEntry("com/formdev/flatlaf/natives/$FlatLafDll")
  if (-not $entry) { throw "$MainJar has no FlatLaf native for windows-$flArch" }
  [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, (Join-Path $InputDir $FlatLafDll), $true)
} finally {
  $zip.Dispose()
}
$JitDirectives = 'jit-directives.json'
Copy-Item (Join-Path $PSScriptRoot $JitDirectives) $InputDir
# No app\.package on Windows: without it the launcher never reads a per-user .cfg, so the installed one
# (and the heap line the MSI may add to it, PACKAGING.md sect. 5.7) is the only one. Full JIT stays locked.

# AppCDS archive - see the matching block in build-bundle.sh and APPCDS.md. The
# JVM uses the archive only while the jar's size and mtime match the dump, so the
# jar is pinned to a fixed time and the app re-pins it on start (CdsJarPin).
$CdsEpoch    = 1577836800                                  # 2020-01-01T00:00:00Z
$CdsPinned   = [datetime]::new(2020, 1, 1, 0, 0, 0, [DateTimeKind]::Utc)
# One list per architecture: each is recorded on its own runtime, so its @cp lines match that JDK build.
$CdsListName = "windows-$Arch.classlist"
$CdsList     = Join-Path $PSScriptRoot "cds\$CdsListName"
$CdsArchive  = 'xdm.jsa'
$CdsProperty = 'xdm.cds.mtime'                             # read by CdsJarPin
$BundledJava = Join-Path $RuntimeDir 'bin\java.exe'
$InputJar    = Join-Path $InputDir $MainJar
(Get-Item $InputJar).LastWriteTimeUtc = $CdsPinned

if ($RecordClasses) {
  New-Item -ItemType Directory -Force -Path (Split-Path $CdsList) | Out-Null
  $recorded = Join-Path $BuildDir "windows-$Arch\recorded.classlist"
  $session = @()
  if ($RecordServer) {
    # A fresh profile: the session starts from first run and leaves the user's downloads alone.
    $recordHome = Join-Path $BuildDir "windows-$Arch\record-home"
    Remove-Item $recordHome -Recurse -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force -Path $recordHome | Out-Null
    $session = @("-Duser.home=$recordHome", "-Dxdm.record.session=$RecordServer")
    Write-Host ">> recording $CdsListName with the scripted session against $RecordServer"
    Write-Host '>>   quit any running XDM first (port 8597); XDM exits by itself when the session ends'
  } else {
    Write-Host ">> recording $CdsListName`: quit any running XDM first, then use this one"
    Write-Host '>>   as users do - run a real download to completion - and quit it from the tray'
  }
  & $BundledJava @JavaOptions '-XX:+UnlockDiagnosticVMOptions' `
      "-XX:CompilerDirectivesFile=$(Join-Path $InputDir $JitDirectives)" `
      "-Dflatlaf.nativeLibraryPath=$InputDir" @session `
      "-XX:DumpLoadedClassList=$recorded" -cp $InputJar $MainClass
  if ($RecordServer -and $LASTEXITCODE -ne 0) {
    throw "The recording session reported $LASTEXITCODE failed step(s) (RECORD: lines above); $CdsListName was not updated"
  }
  # The session's own classes (xdm.app.recording) never load in a user's run: keep them out.
  Get-Content $recorded | Where-Object { $_ -notmatch 'xdm/app/recording/' } | Set-Content -Encoding ascii $CdsList
  Remove-Item $recorded
}

# The recording session (xdm.app.recording) is build tooling: it leaves the bundle's jar once it has
# run, before the archive is dumped, so the archive matches the jar that ships. Nothing else loads it:
# the app only calls it when -Dxdm.record.session is set. The kit's xdm-app.jar keeps it for recording.
$zip = [System.IO.Compression.ZipFile]::Open($InputJar, 'Update')
try {
  @($zip.Entries | Where-Object { $_.FullName -like 'xdm/app/recording/*' }) | ForEach-Object { $_.Delete() }
} finally {
  $zip.Dispose()
}
(Get-Item $InputJar).LastWriteTimeUtc = $CdsPinned
Write-Host '>> removed the recording session (xdm/app/recording) from the bundled jar'

if ((Test-Path $CdsList) -and (Get-Item $CdsList).Length -gt 0) {
  Write-Host ">> dumping the AppCDS archive from $CdsListName"
  $dump = & $BundledJava -Xshare:dump @JavaOptions "-XX:SharedClassListFile=$CdsList" `
      "-XX:SharedArchiveFile=$(Join-Path $InputDir $CdsArchive)" -cp $InputJar
  if ($LASTEXITCODE -ne 0) { $dump | Write-Host; throw 'AppCDS dump failed' }
  # Lines recorded against another JDK build or an older jar are skipped (the class is still
  # archived, only less pre-resolved). Say so: it's the cue to record the list again.
  $stale = @($dump | Select-String 'out of sync|Preload Warning').Count
  if ($stale -gt 0) {
    Write-Warning "$stale stale entries in $CdsListName (JDK or app changed since it was recorded) - re-record with -RecordClasses"
  }
  # $APPDIR is expanded by the jpackage launcher; single quotes keep PowerShell off it.
  $JavaOptions += @(
    ('-XX:SharedArchiveFile=$APPDIR\' + $CdsArchive)
    '-XX:+UnlockDiagnosticVMOptions'
    '-XX:ArchiveRelocationMode=0'
    "-D$CdsProperty=$CdsEpoch"
  )
} else {
  Write-Host ">> no packaging\cds\$CdsListName - building without an AppCDS archive (see -RecordClasses)"
}

# JIT directive and FlatLaf's DLL path: added only now, because $APPDIR exists only in the launcher
# and the AppCDS dump above runs the bundled java directly (the recording run passes both with their
# build paths). JitOverride's "Full JIT" setting drops the directive again and keeps the path.
$JavaOptions += @(
  '-XX:+UnlockDiagnosticVMOptions'
  ('-XX:CompilerDirectivesFile=$APPDIR\' + $JitDirectives)
  '-Dflatlaf.nativeLibraryPath=$APPDIR'
)

$jpArgs = @(
  '--type', 'app-image'
  '--name', $Launcher
  '--app-version', $AppVersion
  '--vendor', $Vendor
  '--description', $AppName
  '--input', $InputDir
  '--main-jar', $MainJar
  '--main-class', $MainClass
  '--runtime-image', $RuntimeDir
  '--dest', $DestDir
)
foreach ($o in $JavaOptions) { $jpArgs += @('--java-options', $o) }

$Icon = Join-Path $PSScriptRoot 'icons\xdm.ico'
if (Test-Path $Icon) { $jpArgs += @('--icon', $Icon) }

Write-Host ">> jpackage app-image (windows-$Arch, version $AppVersion)"
& $Jpackage @jpArgs
if ($LASTEXITCODE -ne 0) { throw 'jpackage failed' }

# jpackage copies the jar with a fresh mtime; pin the image's copy so the image - and the MSI
# built from it, whose cabinet keeps file times - uses the archive from its very first start.
$ImageJar = Join-Path $ImageDir "app\$MainJar"
(Get-Item $ImageJar).LastWriteTimeUtc = $CdsPinned

# One process, not two. By default the Windows launcher re-runs itself as a child in a job object, only
# to put the app dir on that child's PATH (WinLauncher.cpp, needRestartLauncher), so every start shows
# two xdm-app.exe in Task Manager. XDM needs no PATH entry: its Win32 calls name system DLLs, FlatLaf
# loads its DLL by full path and the JVM finds runtime\bin through SetDllDirectory. jpackage has no
# option for this property, so it goes into [Application] here. JitOverride's per-user copy keeps it.
$Cfg = Join-Path $ImageDir "app\$Launcher.cfg"
$cfgLines = [System.Collections.Generic.List[string]](Get-Content $Cfg)
if (-not ($cfgLines -match '^win\.norestart=')) {
  $at = $cfgLines.IndexOf('[Application]')
  if ($at -lt 0) { throw "No [Application] section in $Cfg" }
  $cfgLines.Insert($at + 1, 'win.norestart=true')
  [IO.File]::WriteAllLines($Cfg, $cfgLines)
}

# The clean copy the MSI writes xdm-app.cfg from, adding -XX:AllocateHeapAt only if the install-time test
# passes (PACKAGING.md sect. 5.7). Never modified after install, so an upgrade always replaces it. Not a
# *.cfg name, so neither the launcher nor JitOverride treats it as a launcher config.
Copy-Item $Cfg "$Cfg.base" -Force

$size = [math]::Round((Get-ChildItem -Recurse $RuntimeDir | Measure-Object Length -Sum).Sum / 1MB, 1)
Write-Host ">> runtime size: $size MB"
Write-Host ">> launcher config ($Cfg):"
Get-Content $Cfg | ForEach-Object { Write-Host "   $_" }

# ---- MSI (PACKAGING.md sect. 5) -----------------------------------------------------
if ($Type -eq 'msi') {
  if (-not (Get-Command wix -ErrorAction SilentlyContinue)) {
    throw 'wix not found on PATH - install the WiX 7 CLI: dotnet tool install -g wix'
  }
  # "7.0.0+abc1234" -> "7.0.0". Extensions must match the CLI's version exactly.
  $wixVersion = ((& wix --version) -split '\+')[0].Trim()
  if ([int]($wixVersion -split '\.')[0] -lt 7) {
    throw "WiX $wixVersion is too old: Scope=""perMachineOrUser"" needs WiX 7 (dotnet tool update -g wix)"
  }
  $installed = (& wix extension list -g) -join "`n"
  foreach ($ext in 'WixToolset.Util.wixext', 'WixToolset.UI.wixext') {
    if ($installed -notmatch [regex]::Escape("$ext $wixVersion")) {
      Write-Host ">> wix extension add -g $ext/$wixVersion"
      # Not fatal: if the list format differs, the extension may already be there; wix build says if not.
      & wix extension add -g "$ext/$wixVersion"
    }
  }

  # MSI versions are numeric a.b.c (a, b < 256; c < 65536); only those three fields are compared.
  if ($AppVersion -notmatch '^\d+\.\d+\.\d+$') { throw "App version '$AppVersion' is not a.b.c" }

  $Msi = Join-Path $DestDir "xdm-$AppVersion-$Arch.msi"
  Write-Host ">> wix build -> $Msi"
  & wix build (Join-Path $PSScriptRoot 'windows\xdm.wxs') `
      -arch $Arch `
      -ext WixToolset.Util.wixext -ext WixToolset.UI.wixext `
      -bindpath "AppImage=$ImageDir" `
      -d "Version=$AppVersion" `
      -d "IconFile=$Icon" `
      -d "LicenseRtf=$(Join-Path $PSScriptRoot 'windows\gpl-3.0.rtf')" `
      -o $Msi
  if ($LASTEXITCODE -ne 0) {
    throw 'wix build failed - see the errors above (on first WiX 7 use: "wix eula accept wix7")'
  }
}

Write-Host ''
Get-ChildItem $DestDir | Select-Object -ExpandProperty Name
if ($RecordClasses -and $IsKit) {
  Write-Host ">> copy $CdsList back to the repo (packaging\cds\) and commit it"
}
