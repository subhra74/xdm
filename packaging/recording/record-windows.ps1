<#
.SYNOPSIS
  Records the AppCDS class lists on Windows x64: packaging\cds\liberica.classlist and packaging\cds\jbr.classlist.
.DESCRIPTION
  The Windows counterpart of record-all.sh (docs/class-list-recording.md section 0). For each vendor, one
  after the other:

    1. finds the pinned x64 JDK (packaging\jdk.properties) in the JDK cache, or downloads it there and checks
       its checksum (BellSoft's SHA-1, JetBrains' SHA-512). Always x64, also on an ARM64 machine (it runs
       under emulation there): the lists are recorded on the x64 builds.
    2. links a runtime the way build-bundle.ps1 does, but with the recording session (xdm.app.recording)
       left in the app module;
    3. runs it with the launcher's flags, -XX:DumpLoadedClassList and the scripted session against the
       record server;
    4. drops the session's own classes and writes the list with its '# xdm-jdk:' stamp.

  The JDK cache is the folder in the XDM_CLASSLIST_CACHE environment variable. When it isn't set, it is set (for this
  user, persistently) to %USERPROFILE%\jdks, where setup-windows.ps1 and build-bundle.ps1 also look for
  the Liberica zip.

  Without -Server, RecordServer runs for the time of the recording on port 8780 (proxy 8781), with its
  assets in build\record-assets. Making its media the first time needs ffmpeg on PATH; copy the Mac's
  build\record-assets there to skip that.

  Run it in the desktop session: XDM opens windows. Quit any running XDM first (port 8597).
.PARAMETER Vendor
  The lists to record: liberica, jbr or both (default).
.PARAMETER Server
  An already running RecordServer, e.g. http://172.16.45.1:8780. Default: start one here.
.PARAMETER Jar
  The app jar. Default: xdm-app\target\xdm-app.jar, or xdm-app.jar in a kit. Built first with Maven when
  pom.xml and mvn are there, unless -NoBuild.
.PARAMETER NoBuild
  Use the jar as it is.
#>
[CmdletBinding()]
param(
  [ValidateSet('liberica','jbr')] [string[]]$Vendor = @('liberica', 'jbr'),
  [string]$Server,
  [string]$Jar,
  [switch]$NoBuild
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$Packaging = Split-Path -Parent $PSScriptRoot
$ProjectRoot = Split-Path -Parent $Packaging
Set-Location $ProjectRoot

function Read-Pin([string]$Key) {
  $line = Get-Content (Join-Path $Packaging 'jdk.properties') | Where-Object { $_ -match "^$([regex]::Escape($Key))=" }
  if (-not $line) { throw "packaging\jdk.properties has no $Key" }
  ($line -split '=', 2)[1].Trim()
}
function Read-JdkRelease([string]$Dir) {
  $file = Join-Path $Dir 'release'
  if (-not (Test-Path $file)) { return $null }
  $r = @{}
  Get-Content $file | ForEach-Object { if ($_ -match '^(\w+)="?(.*?)"?$') { $r[$Matches[1]] = $Matches[2] } }
  $r
}
function Test-PortListening([int]$Port) {
  [bool](Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue)
}

# ---- the JDK cache ------------------------------------------------------------------------------------------
$JdkCache = $env:XDM_CLASSLIST_CACHE
if (-not $JdkCache) {
  $JdkCache = Join-Path $env:USERPROFILE 'jdks'
  [Environment]::SetEnvironmentVariable('XDM_CLASSLIST_CACHE', $JdkCache, 'User')
  $env:XDM_CLASSLIST_CACHE = $JdkCache
  Write-Host ">> XDM_CLASSLIST_CACHE was not set: set it to $JdkCache (user environment)"
}
New-Item -ItemType Directory -Force -Path $JdkCache | Out-Null

$Pins = @{
  liberica = @{
    Name    = "Liberica $(Read-Pin 'liberica.runtime')"
    Dir     = "liberica-$((Read-Pin 'liberica.version') -replace '\+', '_')-x64"
    Url     = Read-Pin 'liberica.windows-x64.url'
    Hash    = 'SHA1'
    Matches = { param($r) $r['IMPLEMENTOR'] -match 'BellSoft' -and $r['JAVA_RUNTIME_VERSION'] -eq (Read-Pin 'liberica.runtime') }
  }
  jbr = @{
    Name    = "JBR $(Read-Pin 'jbr.version')-$(Read-Pin 'jbr.build')"
    Dir     = "jbr-$(Read-Pin 'jbr.version')-$(Read-Pin 'jbr.build')-x64"
    Url     = "https://cache-redirector.jetbrains.com/intellij-jbr/jbrsdk-$(Read-Pin 'jbr.version')-windows-x64-$(Read-Pin 'jbr.build').zip"
    Hash    = 'SHA512'
    Matches = { param($r) $r['IMPLEMENTOR'] -match 'JetBrains' -and $r['JAVA_VERSION'] -eq (Read-Pin 'jbr.version') -and
                          $r['JAVA_RUNTIME_VERSION'] -like "*-$(Read-Pin 'jbr.build')" }
  }
}

# The JDK home under $Dir: the folder holding bin\java.exe and release (the zip's top folder).
function Find-JdkHome([string]$Dir) {
  if (-not (Test-Path $Dir)) { return $null }
  $java = Get-ChildItem $Dir -Recurse -Depth 3 -Filter java.exe -File -ErrorAction SilentlyContinue |
          Where-Object { $_.Directory.Name -eq 'bin' } | Select-Object -First 1
  if ($java) { Split-Path -Parent $java.DirectoryName } else { $null }
}

function Get-PinnedJdk([string]$V) {
  $pin = $Pins[$V]
  $dest = Join-Path $JdkCache $pin.Dir
  $jdkHome = Find-JdkHome $dest
  if ($jdkHome) {
    Write-Host ">> $($pin.Name) x64 already in $dest"
  } else {
    $name = Split-Path $pin.Url -Leaf
    $tmp = Join-Path $JdkCache ".download-$V"
    Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force -Path (Join-Path $tmp 'unpacked') | Out-Null
    $zip = Join-Path $tmp $name
    Write-Host ">> downloading $($pin.Url)"
    & curl.exe -fsSL --retry 3 -o $zip $pin.Url
    if ($LASTEXITCODE -ne 0) { throw "download failed: $($pin.Url)" }
    $expected = if ($V -eq 'liberica') { Read-Pin 'liberica.windows-x64.sha1' }
                else { ((& curl.exe -fsSL --retry 3 "$($pin.Url).checksum") -split '\s+')[0] }
    if (-not $expected) { throw "no checksum for $name" }
    $actual = (Get-FileHash $zip -Algorithm $pin.Hash).Hash
    if ($actual -ne $expected.ToUpperInvariant()) { throw "$($pin.Hash) mismatch for ${name}: $actual, expected $expected" }
    # tar.exe (bsdtar) reads zips and is much faster than Expand-Archive.
    & tar.exe -xf $zip -C (Join-Path $tmp 'unpacked')
    if ($LASTEXITCODE -ne 0) { throw "could not unpack $name" }
    Remove-Item -Recurse -Force $dest -ErrorAction SilentlyContinue
    Move-Item (Join-Path $tmp 'unpacked') $dest
    Remove-Item -Recurse -Force $tmp
    Write-Host ">> unpacked to $dest"
    $jdkHome = Find-JdkHome $dest
    if (-not $jdkHome) { throw "no bin\java.exe in $dest" }
  }
  $r = Read-JdkRelease $jdkHome
  if (-not $r -or -not (& $pin.Matches $r)) {
    throw "$jdkHome is $($r['IMPLEMENTOR']) $($r['JAVA_RUNTIME_VERSION']), not the pinned $($pin.Name): delete it, it is fetched again"
  }
  if ($r['OS_ARCH'] -notin 'x86_64', 'amd64') { throw "$jdkHome is $($r['OS_ARCH']), not x64" }
  [pscustomobject]@{ Home = $jdkHome; Stamp = "$($r['IMPLEMENTOR']) $($r['JAVA_RUNTIME_VERSION'])" }
}

# ---- checks -------------------------------------------------------------------------------------------------
if (Test-PortListening 8597) { throw 'port 8597 is in use: quit the running XDM first' }

$IsKit = -not (Test-Path (Join-Path $ProjectRoot 'pom.xml'))
if (-not $Jar) {
  $Jar = if ($IsKit) { Join-Path $ProjectRoot 'xdm-app.jar' } else { Join-Path $ProjectRoot 'xdm-app\target\xdm-app.jar' }
  if (-not $IsKit -and -not $NoBuild -and (Get-Command mvn -ErrorAction SilentlyContinue)) {
    Write-Host '>> mvn -q -pl xdm-app -am package'
    & mvn -q -pl xdm-app -am package
    if ($LASTEXITCODE -ne 0) { throw 'Maven build failed' }
  } elseif (-not $NoBuild) {
    Write-Host ">> no Maven build here: using $Jar as it is"
  }
}
if (-not (Test-Path $Jar)) { throw "Missing $Jar" }
$Jar = (Resolve-Path $Jar).Path

# Fetch every JDK before anything runs, so a download failure doesn't come after a long recording.
$Jdks = @{}
foreach ($v in $Vendor) { $Jdks[$v] = Get-PinnedJdk $v }

# ---- the launcher's flags (build-bundle.ps1): G1, common options, x64 is C1 only ----------------------------
$JavaOptions = @(
  '-XX:+UseG1GC', '-XX:G1HeapRegionSize=1m', '-XX:ParallelGCThreads=1', '-XX:ConcGCThreads=1',
  '-XX:G1ConcRefinementThreads=0', '-XX:G1PeriodicGCInterval=15000', '-XX:-G1PeriodicGCInvokesConcurrent',
  '-XX:MinHeapFreeRatio=1', '-XX:MaxHeapFreeRatio=5', '-XX:-ShrinkHeapInSteps', '-XX:GCTimeRatio=4',
  '-XX:MarkStackSize=65536', '-XX:+UnlockExperimentalVMOptions', '-XX:G1NewSizePercent=1',
  '-XX:G1MaxNewSizePercent=10', '-XX:G1ReservePercent=1', '-XX:G1HeapWastePercent=1',
  '-XX:G1RemSetFreeMemoryKeepExcessRatio=0', '-XX:G1ExpandByPercentOfAvailable=2',
  '-Xms6m', '-XX:-AlwaysPreTouch', '-XX:-DisableExplicitGC', '-XX:+ClassUnloading',
  '-XX:MinMetaspaceFreeRatio=1', '-XX:MaxMetaspaceFreeRatio=2', '-XX:CompressedClassSpaceSize=64m',
  '-XX:ReservedCodeCacheSize=32m', '-XX:SoftRefLRUPolicyMSPerMB=0', '-XX:-UsePerfData',
  '-Djdk.nio.maxCachedBufferSize=262144', '-XX:+DisplayVMOutputToStderr', '--enable-native-access=xdm.app',
  '-XX:+UseCompactObjectHeaders', '-XX:TieredStopAtLevel=1', '-XX:CICompilerCount=1'
)
$Modules = 'java.desktop,java.logging,jdk.crypto.ec,jdk.unsupported'
$AppModule = 'xdm.app'
$FlatLafDll = 'flatlaf-windows-x86_64.dll'
$ConscryptDll = 'conscrypt_openjdk_jni-windows-x86_64.dll'
$WorkRoot = Join-Path $ProjectRoot 'build\record-windows'
Add-Type -AssemblyName System.IO.Compression.FileSystem

function Invoke-Recording([string]$V, $Jdk, [string]$ServerUrl) {
  $java = Join-Path $Jdk.Home 'bin\java.exe'
  $work = Join-Path $WorkRoot $V
  $appDir = Join-Path $work 'app'
  $runtime = Join-Path $work 'runtime'
  Remove-Item -Recurse -Force $work -ErrorAction SilentlyContinue
  New-Item -ItemType Directory -Force -Path $appDir | Out-Null

  # The app's DLLs as files in app\, as the bundle has them; Conscrypt's leaves the module, or it would
  # extract its own copy to %TEMP% instead of System.loadLibrary (build-bundle.ps1).
  $appJar = Join-Path $work 'xdm-app.jar'
  Copy-Item $Jar $appJar
  $zip = [System.IO.Compression.ZipFile]::Open($appJar, 'Update')
  try {
    $fl = $zip.GetEntry("com/formdev/flatlaf/natives/$FlatLafDll")
    if (-not $fl) { throw "$Jar has no $FlatLafDll" }
    [System.IO.Compression.ZipFileExtensions]::ExtractToFile($fl, (Join-Path $appDir $FlatLafDll), $true)
    $cs = $zip.GetEntry("META-INF/native/$ConscryptDll")
    if (-not $cs) { throw "$Jar has no $ConscryptDll" }
    [System.IO.Compression.ZipFileExtensions]::ExtractToFile($cs, (Join-Path $appDir $ConscryptDll), $true)
    $cs.Delete()
  } finally {
    $zip.Dispose()
  }

  # The app module with the session still in it (the shipped one excludes xdm/app/recording/).
  $moduleJar = Join-Path $work "$AppModule.jar"
  & $java (Join-Path $Packaging 'ModularJar.java') $appJar $moduleJar (Join-Path $Packaging 'module\module-info.java')
  if ($LASTEXITCODE -ne 0) { throw 'ModularJar failed' }
  Write-Host ">> jlink runtime: $Modules,$AppModule"
  & (Join-Path $Jdk.Home 'bin\jlink.exe') --module-path $moduleJar --add-modules "$Modules,$AppModule" --vm server `
      --strip-debug --no-header-files --no-man-pages --output $runtime
  if ($LASTEXITCODE -ne 0) { throw 'jlink failed' }

  # A fresh profile: the session starts from first run and leaves the user's downloads alone.
  $recordHome = Join-Path $work 'record-home'
  New-Item -ItemType Directory -Force -Path $recordHome | Out-Null
  $recorded = Join-Path $work 'recorded.classlist'
  Write-Host ">> recording $V.classlist with the scripted session against $ServerUrl"
  & (Join-Path $runtime 'bin\java.exe') @JavaOptions "-Dflatlaf.nativeLibraryPath=$appDir" `
      "-Djava.library.path=$appDir" "-Dxdm.app.dir=$appDir" "-Duser.home=$recordHome" `
      "-Dxdm.data.dir=$recordHome\xdm-app" "-Dxdm.record.session=$ServerUrl" `
      "-XX:DumpLoadedClassList=$recorded" -m "$AppModule/xdm.app.AppMain"
  $status = $LASTEXITCODE
  # The session downloads ~700 MB: keep only the profile (logs).
  Get-ChildItem $recordHome | Where-Object Name -ne 'xdm-app' | Remove-Item -Recurse -Force
  Remove-Item -Recurse -Force (Join-Path $recordHome 'xdm-app\tmp') -ErrorAction SilentlyContinue
  if ($status -ne 0) {
    throw "The recording session reported $status failed step(s) (RECORD: lines above); $V.classlist was not updated. Logs: $recordHome\xdm-app\logs"
  }

  $list = Join-Path $Packaging "cds\$V.classlist"
  New-Item -ItemType Directory -Force -Path (Split-Path $list) | Out-Null
  $lines = @("# xdm-jdk: $($Jdk.Stamp)") + @(Get-Content $recorded | Where-Object { $_ -notmatch 'xdm/app/recording/' })
  # LF and no BOM, like the lists recorded on the Mac.
  [System.IO.File]::WriteAllText($list, (($lines -join "`n") + "`n"), (New-Object System.Text.UTF8Encoding $false))
  Remove-Item $recorded
  $classes = @($lines | Where-Object { $_ -notmatch '^[#@]' }).Count
  Write-Host ">> wrote packaging\cds\$V.classlist: $classes classes, stamp: $($lines[0])"
  Remove-Item -Recurse -Force $runtime, $moduleJar, $appJar
}

# ---- the record server, for this run only --------------------------------------------------------------------
$serverProc = $null
try {
  if (-not $Server) {
    foreach ($p in 8780, 8781) {
      if (Test-PortListening $p) { throw "port $p is in use: stop the RecordServer already running, or pass -Server" }
    }
    $Server = 'http://127.0.0.1:8780'
    $log = Join-Path $ProjectRoot 'build\record-server.log'
    New-Item -ItemType Directory -Force -Path (Split-Path $log) | Out-Null
    # Any of the fetched JDKs runs it (JDK 17+).
    $serverJava = Join-Path ($Jdks[$Vendor[0]].Home) 'bin\java.exe'
    Write-Host ">> starting RecordServer on $Server (log: $log)"
    $serverProc = Start-Process $serverJava -PassThru -NoNewWindow -RedirectStandardOutput $log `
        -RedirectStandardError "$log.err" -ArgumentList @(
          (Join-Path $PSScriptRoot 'RecordServer.java'), '--port', '8780',
          '--assets', (Join-Path $ProjectRoot 'build\record-assets'))
  }
  $Server = $Server.TrimEnd('/')
  # The first start makes the HLS/DASH media with ffmpeg, which takes a while; an x64 JDK under emulation
  # also takes about 30 s to compile the server.
  $deadline = (Get-Date).AddMinutes(10)
  while ($true) {
    # -s without -S: no stderr, which PowerShell 5.1 would turn into an error under 'Stop'
    & curl.exe -fs -m 2 -o NUL "$Server/session.properties"
    if ($LASTEXITCODE -eq 0) { break }
    if ($serverProc -and $serverProc.HasExited) {
      Get-Content "$log.err" -Tail 20 -ErrorAction SilentlyContinue | Write-Host
      throw "RecordServer exited ($log)"
    }
    if ((Get-Date) -gt $deadline) { throw "no record server at $Server" }
    Start-Sleep 1
  }

  foreach ($v in $Vendor) {
    Write-Host ''
    Write-Host "==== $v ===="
    Invoke-Recording $v $Jdks[$v] $Server
  }
} finally {
  if ($serverProc -and -not $serverProc.HasExited) { Stop-Process -Id $serverProc.Id -Force }
}
Write-Host ''
Write-Host '>> done: copy packaging\cds\*.classlist to the checkout, review and commit'
