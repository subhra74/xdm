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
  validates it by JDK build, runtime image size and VM flags, never by CPU (APPCDS.md sect. 10).

  The app ships inside the runtime image as the module xdm.app (packaging\module\module-info.java,
  made from the fat jar by packaging\ModularJar.java), not as a jar in app\: the JVM checks a classpath
  jar against the archive by exact mtime, which an MSI install moves by the build and install machines'
  time-zone difference. Classes in lib\modules are checked by size only.

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
.PARAMETER AnyJdk
  Build with a JDK other than the Liberica build pinned in packaging\jdk.properties. The AppCDS class list
  then doesn't match the runtime: local experiments only.
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
  [switch]$AnyJdk
)

$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent $PSScriptRoot
Set-Location $ProjectRoot

# JDK modules the app actually needs - see the comment block in build-bundle.sh
# for how this list was derived and what was deliberately left out.
$Modules = 'java.desktop,java.logging,jdk.crypto.ec,jdk.unsupported'
if ($WithLocales) { $Modules += ',jdk.localedata' }

# JVM tuning flags baked into the launcher. See the comment block in
# build-bundle.sh for the measurements behind this set: G1 tuned to return
# memory had the lowest settled and download-time memory without a heap file
# (APPCDS.md sect. 12). -XX:AllocateHeapAt is no longer used (JDK-8390353).
#
# Two collectors on Windows, each with its own AppCDS archive and launcher config; the MSI installs the one
# matching the Windows build (PACKAGING.md sect. 5.8). ZGC needs Windows 10 1803 (build 17134) or later and
# keeps its heap in pagefile-backed shared memory, which Task Manager's private column leaves out (about
# 10-15 MB lower there than G1, APPCDS.md sect. 13). G1 is the fallback for older builds.
$G1Options = @(
  # collector: G1 sized down hard and returning memory quickly (see build-bundle.sh)
  '-XX:+UseG1GC'
  '-XX:G1HeapRegionSize=1m'
  '-XX:ParallelGCThreads=1'
  '-XX:ConcGCThreads=1'
  '-XX:G1ConcRefinementThreads=0'
  # a compacting full GC whenever 15 s pass without a GC; it is what shrinks the heap
  '-XX:G1PeriodicGCInterval=15000'
  '-XX:-G1PeriodicGCInvokesConcurrent'
  '-XX:MinHeapFreeRatio=1'
  '-XX:MaxHeapFreeRatio=5'
  '-XX:-ShrinkHeapInSteps'
  # grow slowly: accept more GC time instead of a bigger heap
  '-XX:GCTimeRatio=4'
  '-XX:MarkStackSize=65536'
  # experimental sizing knobs; must follow the unlock flag
  '-XX:+UnlockExperimentalVMOptions'
  '-XX:G1NewSizePercent=1'
  '-XX:G1MaxNewSizePercent=10'
  '-XX:G1ReservePercent=1'
  '-XX:G1HeapWastePercent=1'
  '-XX:G1RemSetFreeMemoryKeepExcessRatio=0'
  '-XX:G1ExpandByPercentOfAvailable=2'
)
$ZgcOptions = @(
  # collector: generational ZGC with one worker each, a cycle at least every 5 s and free memory returned
  # after 1 s; no proactive cycles (XDM's own System.gc() calls do the settling)
  '-XX:+UseZGC'
  '-XX:ZCollectionInterval=5'
  '-XX:ZUncommitDelay=1'
  '-XX:-ZProactive'
  '-XX:ConcGCThreads=1'
  '-XX:ParallelGCThreads=1'
)
$JavaOptions = @(
  # >= 5m: the AppCDS archive's ~3.4 MB of pre-built heap objects must fit the initial heap, or the
  # JVM skips them (and the archived module graph) without a word. 4m was just too small.
  '-Xms6m'
  '-XX:-AlwaysPreTouch'
  # XDM's own System.gc() calls (AppMain's 15 s thread, after downloads) must stay full GCs
  '-XX:-DisableExplicitGC'
  # class metadata
  '-XX:+ClassUnloading'
  '-XX:MinMetaspaceFreeRatio=1'
  '-XX:MaxMetaspaceFreeRatio=2'
  '-XX:CompressedClassSpaceSize=64m'
  # JIT: the compiler flags are added per architecture below (see build-bundle.sh)
  '-XX:ReservedCodeCacheSize=32m'
  # drop FlatLaf/Swing soft-referenced image caches on each GC
  '-XX:SoftRefLRUPolicyMSPerMB=0'
  # no hsperfdata mmap file
  '-XX:-UsePerfData'
  # bound the per-thread direct-buffer cache NIO keeps for heap-buffer writes
  '-Djdk.nio.maxCachedBufferSize=262144'
  # VM messages ("2 compiler directives added") on stderr: stdout is the headless CLI's output
  '-XX:+DisplayVMOutputToStderr'
  # FlatLaf loads a native library; JDK 22+ warns unless native access is enabled. The app is the
  # module xdm.app; the dump must see the same value, or the JVM drops the archived module graph.
  '--enable-native-access=xdm.app'
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
$AppModule   = 'xdm.app'
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
# The Liberica build pinned in packaging\jdk.properties: the class lists are recorded on it (APPCDS.md).
function Read-Pin([string]$Key) {
  $line = Get-Content (Join-Path $PSScriptRoot 'jdk.properties') | Where-Object { $_ -match "^$([regex]::Escape($Key))=" }
  if (-not $line) { throw "packaging\jdk.properties has no $Key" }
  ($line -split '=', 2)[1].Trim()
}
$PinnedRuntime = Read-Pin 'liberica.runtime'
function Test-PinnedJdk($Release) {
  $Release['IMPLEMENTOR'] -match 'BellSoft' -and $Release['JAVA_RUNTIME_VERSION'] -eq $PinnedRuntime
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
  # The pinned build first; any JDK 25 only with -AnyJdk.
  foreach ($pinnedOnly in @($true, $false)) {
    if (-not $pinnedOnly -and -not $AnyJdk) { break }
    foreach ($dir in $candidates) {
      $r = Read-JdkRelease $dir
      if (-not $r -or [int](($r['JAVA_VERSION'] -split '\.')[0]) -lt 25) { continue }
      if ($Arch -and (Get-JdkArch $r) -ne $Arch) { continue }
      if ($pinnedOnly -and -not (Test-PinnedJdk $r)) { continue }
      $JavaHome = $dir
      break
    }
    if ($JavaHome) { break }
  }
  if (-not $JavaHome) {
    throw "No $(if ($Arch) { "$Arch " })Liberica $PinnedRuntime (packaging\jdk.properties) found: run packaging\setup-windows.ps1 -InstallJdk, pass -JavaHome, or -AnyJdk for a local experiment"
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
# The stamp the class list was recorded with (on the Mac's Liberica build), compared before the dump.
$JdkStamp = "$($release['IMPLEMENTOR']) $($release['JAVA_RUNTIME_VERSION'])"
if (-not (Test-PinnedJdk $release)) {
  if (-not $AnyJdk) {
    throw "$JavaHome is $JdkStamp, not the pinned Liberica $PinnedRuntime (packaging\jdk.properties): run packaging\setup-windows.ps1 -InstallJdk, or pass -AnyJdk for a local experiment"
  }
  Write-Warning "-AnyJdk: building with $JdkStamp instead of the pinned Liberica $PinnedRuntime; the AppCDS class list won't match"
}

# The machine's own architecture, whatever this PowerShell process runs as.
$hostArch = (Get-ItemProperty 'HKLM:\SYSTEM\CurrentControlSet\Control\Session Manager\Environment').PROCESSOR_ARCHITECTURE
if ($Arch -eq 'arm64' -and $hostArch -ne 'ARM64') { throw 'An arm64 build must run on an ARM64 machine' }
$emulated = ($Arch -eq 'x64' -and $hostArch -eq 'ARM64')
Write-Host ">> target windows-$Arch, JDK $($release['JAVA_VERSION']) ($($release['IMPLEMENTOR']))$(if ($emulated) { ', x64 under emulation' })"

# JIT: x64 ships Conscrypt's native library (ConscryptTls), so TLS and digests run in BoringSSL and no
# JDK crypto is left for C2: C1 only. arm64 has none (Conscrypt has no Windows arm64 build), so C1
# compiles everything and C2 only the JDK's crypto methods in jit-directives.json (PACKAGING.md sect. 3.2).
$C2Crypto = ($Arch -eq 'arm64')
if ($C2Crypto) {
  $JavaOptions += '-XX:CICompilerCount=2'
  Write-Host ">> JIT: C1, C2 for the JDK's crypto (no Conscrypt on windows-arm64)"
} else {
  $JavaOptions += @('-XX:TieredStopAtLevel=1', '-XX:CICompilerCount=1')
  Write-Host ">> JIT: C1 only (Conscrypt does TLS and digests)"
}

$RuntimeDir = Join-Path $BuildDir "windows-$Arch\runtime"
$InputDir   = Join-Path $BuildDir "windows-$Arch\input"
$ModuleDir  = Join-Path $BuildDir "windows-$Arch\module"
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
# one. Only the copy under build\...\module that becomes the app module is trimmed.
# xdm-app\target\xdm-app.jar keeps every native, so the fat jar stays runnable
# on any platform when it is shared or launched on its own with `java -jar`.
#
# FlatLaf and Conscrypt resolve their library through an <os>-<arch> classifier
# in the entry name, so keeping just the matching entries is enough. Conscrypt has
# no windows-arm64 library, so an arm64 bundle keeps none (the JDK's TLS then); the
# x64 one moves out of the module into app\ (below).
# The arch is the target's, not this process's: an x64 build on an ARM64 machine
# needs the x64 DLLs.
# --------------------------------------------------------------------------
function Remove-ForeignNatives {
  param([string]$Jar)

  $flArch = if ($Arch -eq 'arm64') { 'arm64' } else { 'x86_64' }
  $flKeep = "com/formdev/flatlaf/natives/*-windows-$flArch.*"
  $csKeep = "META-INF/native/conscrypt_openjdk_jni-windows-$flArch.dll"

  $before = (Get-Item $Jar).Length
  $zip = [System.IO.Compression.ZipFile]::Open($Jar, 'Update')
  try {
    $doomed = @($zip.Entries | Where-Object {
      $_.FullName -match '\.(dylib|jnilib|so|dll)$' -and
      -not ($_.FullName -like $flKeep) -and $_.FullName -ne $csKeep
    })
    foreach ($entry in $doomed) { $entry.Delete() }
  } finally {
    $zip.Dispose()
  }
  $saved = [math]::Round(($before - (Get-Item $Jar).Length) / 1KB)
  Write-Host ">> stripped $($doomed.Count) foreign natives (windows-$flArch): $saved KB"
}

Remove-Item -Recurse -Force $RuntimeDir, $InputDir, $ModuleDir, $ImageDir -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $InputDir, $ModuleDir, $DestDir | Out-Null

# The fat jar, trimmed to this target's natives. It stays out of $InputDir: jpackage would put anything
# there on the class path.
$AppJar = Join-Path $ModuleDir $MainJar
Copy-Item $JarPath $AppJar
Remove-ForeignNatives $AppJar
# Conscrypt's DLL as a file in app\, next to FlatLaf's, not in the app module. Conscrypt extracts a library it
# finds among its resources into %TEMP% (a 1.9 MB copy on every start) and calls System.loadLibrary only when
# there is none. That searches java.library.path, which has no app\ by default (the launcher adds its app dir
# to PATH only by re-running itself, off with win.norestart below), so the launcher sets it to $APPDIR (below).
# Nothing else loads a library by name: FlatLaf by full path, the JDK's own from sun.boot.library.path, the
# Win32 calls through the OS search. x64 runs C1 only and relies on Conscrypt for TLS; without the DLL it
# would run the JDK's TLS on C1.
$ConscryptDll = 'conscrypt_openjdk_jni-windows-x86_64.dll'
$ConscryptFile = Join-Path $InputDir $ConscryptDll
$zip = [System.IO.Compression.ZipFile]::Open($AppJar, 'Update')
try {
  $entry = $zip.GetEntry("META-INF/native/$ConscryptDll")
  if ($entry) {
    [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $ConscryptFile, $true)
    $entry.Delete()
  }
} finally {
  $zip.Dispose()
}
if (-not $C2Crypto -and -not (Test-Path $ConscryptFile)) { throw "No Conscrypt DLL in $AppJar, but windows-$Arch runs C1 only" }
if (Test-Path $ConscryptFile) { Write-Host ">> Conscrypt DLL -> app\$ConscryptDll" }

# The app module (packaging\module\module-info.java). The recording session (xdm.app.recording) is build
# tooling, run on the Mac only, and stays out of it: nothing else loads it, the app only calls it when
# -Dxdm.record.session is set.
$ModuleJar = Join-Path $ModuleDir "$AppModule.jar"
& $Java (Join-Path $PSScriptRoot 'ModularJar.java') $AppJar $ModuleJar `
    (Join-Path $PSScriptRoot 'module\module-info.java') --exclude 'xdm/app/recording/'
if ($LASTEXITCODE -ne 0) { throw 'ModularJar failed' }

Write-Host ">> jlink runtime: $Modules,$AppModule"
# --vm server: Liberica's Windows x64 JDK also ships the client VM (bin\client\jvm.dll, 8.7 MB). x64 runs
# C1 only too, but on the server VM the class list and archive were made with (PACKAGING.md sect. 3.3).
& $Jlink --module-path $ModuleJar --add-modules "$Modules,$AppModule" --vm server --strip-debug `
         --no-header-files --no-man-pages --compress=zip-9 --output $RuntimeDir
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

# FlatLaf's DLL as a file in app\, loaded from there through -Dflatlaf.nativeLibraryPath (added with
# the JIT directive below). Otherwise FlatLaf copies it out of the jar into %TEMP%\flatlaf.temp on
# every start and loads it from there - a fresh DLL in %TEMP% is the kind of thing antivirus flags.
# The name is the one FlatLaf asks for, System.mapLibraryName("flatlaf-windows-<arch>"). The module
# keeps its copy, which FlatLaf falls back to (logging it) if this file goes missing.
$flArch = if ($Arch -eq 'arm64') { 'arm64' } else { 'x86_64' }
$FlatLafDll = "flatlaf-windows-$flArch.dll"
$zip = [System.IO.Compression.ZipFile]::OpenRead($AppJar)
try {
  $entry = $zip.GetEntry("com/formdev/flatlaf/natives/$FlatLafDll")
  if (-not $entry) { throw "$MainJar has no FlatLaf native for windows-$flArch" }
  [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, (Join-Path $InputDir $FlatLafDll), $true)
} finally {
  $zip.Dispose()
}
$JitDirectives = 'jit-directives.json'
if ($C2Crypto) { Copy-Item (Join-Path $PSScriptRoot $JitDirectives) $InputDir }
# No app\.package on Windows: without it the launcher never reads a per-user .cfg, so the installed one
# (G1 or ZGC, PACKAGING.md sect. 5.8) is the only one.

# AppCDS archive - see the matching block in build-bundle.sh and APPCDS.md. The app's classes come
# from the runtime image (module xdm.app), which the JVM checks by size only, so the archive survives
# the MSI install moving file times.
# The Liberica list, recorded on the Mac's Liberica build (same JDK build as here, so its @cp and lambda
# lines hold). Windows-only classes (WToolkit peers, utils\win) load from lib\modules: measured ~0.3 MB more
# than a Windows-recorded list (docs/class-list-recording.md). The lists are recorded on the Mac only.
$CdsListName = 'liberica.classlist'
$CdsList     = Join-Path $PSScriptRoot "cds\$CdsListName"
# One archive per collector: an archive only maps under the compressed-oops setting it was dumped with, which
# G1 uses and ZGC can't (PACKAGING.md sect. 5.8).
$CdsArchive    = 'xdm.jsa'
$ZgcCdsArchive = 'xdm-zgc.jsa'
$BundledJava = Join-Path $RuntimeDir 'bin\java.exe'
$MainModule  = "$AppModule/$MainClass"

if ((Test-Path $CdsList) -and (Get-Item $CdsList).Length -gt 0) {
  $listJdk = (Get-Content $CdsList -TotalCount 20 | Where-Object { $_ -like '# xdm-jdk: *' } | Select-Object -First 1)
  if (-not $listJdk) {
    Write-Warning "$CdsListName has no '# xdm-jdk:' stamp (recorded before the JDK pin): re-record it on $JdkStamp"
  } elseif ($listJdk.Substring(11) -ne $JdkStamp) {
    $msg = "$CdsListName was recorded on $($listJdk.Substring(11)), but this build uses $JdkStamp"
    if (-not $AnyJdk) { throw "$msg`: re-record it on the Mac (docs/class-list-recording.md), or revert packaging\jdk.properties" }
    Write-Warning $msg
  }
  # ArchiveRelocationMode=0 maps the archive at its preferred address, so its pages stay shared instead
  # of turning private on relocation.
  $CdsOptions = @('-XX:+UnlockDiagnosticVMOptions', '-XX:ArchiveRelocationMode=0')
  # The archive must still map once the MSI has moved every file time: lib\modules' mtime is moved before
  # the check, which starts the VM as the launcher will, with -Xshare:on (fails instead of running without
  # the archive). The archived module graph is lost silently when a module property (-m,
  # --enable-native-access) differs from the dump; check it too. JDK 25 can't archive heap objects under
  # ZGC ("ZGC is not supported"), so the ZGC archive holds class metadata only and never has the module
  # graph. The log goes to a file: -XX:+DisplayVMOutputToStderr is in the flags.
  (Get-Item (Join-Path $RuntimeDir 'lib\modules')).LastWriteTimeUtc = [datetime]::UtcNow
  foreach ($cds in @(@{ Gc = 'G1'; Options = $G1Options; Archive = $CdsArchive; FullGraph = $true },
                     @{ Gc = 'ZGC'; Options = $ZgcOptions; Archive = $ZgcCdsArchive; FullGraph = $false })) {
    $archivePath = Join-Path $InputDir $cds.Archive
    Write-Host ">> dumping the $($cds.Gc) AppCDS archive ($($cds.Archive)) from $CdsListName"
    $dump = & $BundledJava -Xshare:dump @($cds.Options) @JavaOptions "-XX:SharedClassListFile=$CdsList" `
        "-XX:SharedArchiveFile=$archivePath" -m $MainModule
    if ($LASTEXITCODE -ne 0) { $dump | Write-Host; throw "$($cds.Gc) AppCDS dump failed" }
    # The list was recorded on mac-arm64, so it names classes Windows doesn't have: those lines are
    # skipped (about 400). Staleness is checked where it was recorded (build-bundle.sh on the Mac).
    $stale = @($dump | Select-String 'out of sync|Preload Warning').Count
    if ($stale -gt 0) {
      Write-Host ">> $stale lines of $CdsListName skipped (recorded on mac-arm64: classes this build doesn't have)"
    }
    $checkLog = Join-Path $BuildDir "windows-$Arch\cds-check-$($cds.Gc.ToLower()).log"
    & $BundledJava @($cds.Options) @JavaOptions "-XX:SharedArchiveFile=$archivePath" @CdsOptions `
        -Xshare:on "-Xlog:cds=info:file=$checkLog" --dry-run -m $MainModule
    if ($LASTEXITCODE -ne 0) { throw "The $($cds.Gc) AppCDS archive doesn't map (see $checkLog)" }
    if (-not $cds.FullGraph) {
      Write-Host ">> $($cds.Gc) AppCDS archive maps, class metadata only (lib\modules mtime moved)"
    } elseif (Select-String -Path $checkLog -Pattern 'full module graph: enabled' -Quiet) {
      Write-Host ">> $($cds.Gc) AppCDS archive maps with the full module graph (lib\modules mtime moved)"
    } else {
      Select-String -Path $checkLog -Pattern '\[(error|warning)\]' | ForEach-Object { Write-Host $_.Line }
      throw "The $($cds.Gc) AppCDS archive maps without its module graph (see $checkLog)"
    }
  }
  # $APPDIR is expanded by the jpackage launcher; single quotes keep PowerShell off it. The launcher gets
  # the G1 archive; the ZGC config swaps it for the ZGC one after jpackage (below).
  $JavaOptions += @(('-XX:SharedArchiveFile=$APPDIR\' + $CdsArchive)) + $CdsOptions
} else {
  Write-Host ">> no packaging\cds\$CdsListName - building without an AppCDS archive (recorded on the Mac)"
}

# JIT directive and FlatLaf's DLL path: added only now, because $APPDIR exists only in the launcher
# and the AppCDS dump above runs the bundled java directly (the recording run passes both with their
# build paths). x64 is C1 only and has no directive.
if ($C2Crypto) {
  $JavaOptions += @('-XX:+UnlockDiagnosticVMOptions', ('-XX:CompilerDirectivesFile=$APPDIR\' + $JitDirectives))
}
if (Test-Path $ConscryptFile) {
  # where Conscrypt's System.loadLibrary finds its DLL (see above)
  $JavaOptions += '-Djava.library.path=$APPDIR'
}
$JavaOptions += @(
  '-Dflatlaf.nativeLibraryPath=$APPDIR'
  # the app dir for PlatformUtils.baseDirectory: with the app in the runtime image there is no jar path
  '-Dxdm.app.dir=$APPDIR'
)

$jpArgs = @(
  '--type', 'app-image'
  '--name', $Launcher
  '--app-version', $AppVersion
  '--vendor', $Vendor
  '--description', $AppName
  # windows\resources\xdm-app.properties replaces jpackage's WinLauncher.template: ProductName would
  # otherwise be the launcher name (xdm-app); SignPath restricts it to the product name on every file.
  '--resource-dir', (Join-Path $PSScriptRoot 'windows\resources')
  '--input', $InputDir
  '--module', "$AppModule/$MainClass"
  '--runtime-image', $RuntimeDir
  '--dest', $DestDir
)
# The launcher's own config is the G1 one, so the app image runs anywhere; the ZGC config is made from it below.
foreach ($o in $G1Options + $JavaOptions) { $jpArgs += @('--java-options', $o) }

$Icon = Join-Path $PSScriptRoot 'icons\xdm.ico'
if (Test-Path $Icon) { $jpArgs += @('--icon', $Icon) }

Write-Host ">> jpackage app-image (windows-$Arch, version $AppVersion)"
& $Jpackage @jpArgs
if ($LASTEXITCODE -ne 0) { throw 'jpackage failed' }

# One version resource in the launcher. jpackage writes its own (xdm-app.properties) as language neutral
# (0000) next to the one in Liberica's launcher template (0409, ProductName "Liberica Platform <ver>"), which
# it leaves in. Windows shows the neutral one, but SignPath read the 0409 one and refused the product name
# (signpath.md). Every non-neutral RT_VERSION goes; jpackage leaves the exe read-only.
$LauncherExe = Join-Path $ImageDir "$Launcher.exe"
Add-Type @'
using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;
public static class XdmVersionResource {
  const uint LoadAsDatafile = 0x22; // LOAD_LIBRARY_AS_DATAFILE | LOAD_LIBRARY_AS_IMAGE_RESOURCE
  static readonly IntPtr RtVersion = (IntPtr)16, VersionId = (IntPtr)1;
  delegate bool EnumLangProc(IntPtr module, IntPtr type, IntPtr name, ushort lang, IntPtr param);
  [DllImport("kernel32", SetLastError = true, CharSet = CharSet.Unicode)]
  static extern IntPtr LoadLibraryEx(string file, IntPtr reserved, uint flags);
  [DllImport("kernel32")] static extern bool FreeLibrary(IntPtr module);
  [DllImport("kernel32")]
  static extern bool EnumResourceLanguages(IntPtr module, IntPtr type, IntPtr name, EnumLangProc proc, IntPtr param);
  [DllImport("kernel32", SetLastError = true, CharSet = CharSet.Unicode)]
  static extern IntPtr BeginUpdateResource(string file, bool deleteExisting);
  [DllImport("kernel32", SetLastError = true)]
  static extern bool UpdateResource(IntPtr update, IntPtr type, IntPtr name, ushort lang, IntPtr data, uint size);
  [DllImport("kernel32", SetLastError = true)] static extern bool EndUpdateResource(IntPtr update, bool discard);

  public static List<ushort> Languages(string file) {
    var langs = new List<ushort>();
    IntPtr module = LoadLibraryEx(file, IntPtr.Zero, LoadAsDatafile);
    if (module == IntPtr.Zero) throw new InvalidOperationException("LoadLibraryEx: " + Marshal.GetLastWin32Error());
    try { EnumResourceLanguages(module, RtVersion, VersionId, (m, t, n, lang, p) => { langs.Add(lang); return true; }, IntPtr.Zero); }
    finally { FreeLibrary(module); }
    return langs;
  }

  public static void Remove(string file, IEnumerable<ushort> langs) {
    IntPtr update = BeginUpdateResource(file, false);
    if (update == IntPtr.Zero) throw new InvalidOperationException("BeginUpdateResource: " + Marshal.GetLastWin32Error());
    foreach (ushort lang in langs) {
      if (!UpdateResource(update, RtVersion, VersionId, lang, IntPtr.Zero, 0)) {
        int error = Marshal.GetLastWin32Error();
        EndUpdateResource(update, true);
        throw new InvalidOperationException("UpdateResource " + lang.ToString("x4") + ": " + error);
      }
    }
    if (!EndUpdateResource(update, false)) throw new InvalidOperationException("EndUpdateResource: " + Marshal.GetLastWin32Error());
  }
}
'@
$langs = [XdmVersionResource]::Languages($LauncherExe)
if ($langs -notcontains 0) { throw "$LauncherExe has no language-neutral version resource from jpackage" }
$extra = @($langs | Where-Object { $_ -ne 0 })
if ($extra) {
  Write-Host ">> launcher: dropping version resources $(($extra | ForEach-Object { $_.ToString('x4') }) -join ', ')"
  Set-ItemProperty $LauncherExe IsReadOnly $false
  [XdmVersionResource]::Remove($LauncherExe, [uint16[]]$extra)
  Set-ItemProperty $LauncherExe IsReadOnly $true
  if (@([XdmVersionResource]::Languages($LauncherExe) | Where-Object { $_ -ne 0 })) {
    throw "$LauncherExe still has a non-neutral version resource"
  }
}
$vi = (Get-Item $LauncherExe).VersionInfo
Write-Host ">> launcher: $($vi.ProductName) $($vi.ProductVersion), $($vi.CompanyName)"

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

# The ZGC launcher config, app\xdm-app.cfg.zgc: the G1 one with the collector lines swapped and the ZGC
# archive. The MSI installs one of the two as app\xdm-app.cfg (PACKAGING.md sect. 5.8). Not a *.cfg name, so
# the launcher never takes it for a config of its own.
$ZgcCfg = "$Cfg.zgc"
$g1Lines = @($G1Options | ForEach-Object { "java-options=$_" })
$zgcLines = [System.Collections.Generic.List[string]]::new()
$swapped = $false
foreach ($line in (Get-Content $Cfg)) {
  if ($g1Lines -contains $line) {
    if (-not $swapped) { $ZgcOptions | ForEach-Object { $zgcLines.Add("java-options=$_") }; $swapped = $true }
  } elseif ($line -eq ('java-options=-XX:SharedArchiveFile=$APPDIR\' + $CdsArchive)) {
    $zgcLines.Add('java-options=-XX:SharedArchiveFile=$APPDIR\' + $ZgcCdsArchive)
  } else {
    $zgcLines.Add($line)
  }
}
$left = @(Get-Content $Cfg | Where-Object { $g1Lines -contains $_ }).Count
if (-not $swapped -or $left -ne $G1Options.Count) {
  throw "$Cfg doesn't hold the G1 options as one java-options line each ($left of $($G1Options.Count) found)"
}
[IO.File]::WriteAllLines($ZgcCfg, $zgcLines)

$size = [math]::Round((Get-ChildItem -Recurse $RuntimeDir | Measure-Object Length -Sum).Sum / 1MB, 1)
Write-Host ">> runtime size: $size MB"
Write-Host ">> launcher config ($Cfg):"
Get-Content $Cfg | ForEach-Object { Write-Host "   $_" }
Write-Host ">> ZGC launcher config ($ZgcCfg), collector and archive lines:"
Get-Content $ZgcCfg | Where-Object { $_ -cmatch 'ZGC|-XX:[+-]?Z|GCThreads|SharedArchiveFile' } | ForEach-Object { Write-Host "   $_" }

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
