<#
.SYNOPSIS
  Puts the native torrent library for a platform into <Dest>\<platform>\ (default %USERPROFILE%\.cache\xdm-torrent\bin),
  from the copy a Windows build kit carries (bin\<platform>\ next to this script), else from the release pinned in
  binaries.lock (hash checked); with -Local from the libtorrent-builds repo's build-windows.ps1 output.
  Windows counterpart of fetch-binaries.sh. Writes the library's path to the pipeline.
.PARAMETER Platform
  windows-x64 | windows-arm64 (or any platform in binaries.lock); default this machine's.
.PARAMETER Local
  Copy from <BuildOut>\<platform>\bin instead of downloading.
.PARAMETER BuildOut
  build-windows.ps1's output folder (its -Work plus \out); default E:\xdm-torrent\out.
#>
param(
  [string]$Platform,
  [switch]$Local,
  [string]$BuildOut = 'E:\xdm-torrent\out',
  [string]$Dest = (Join-Path $env:USERPROFILE '.cache\xdm-torrent\bin')
)
$ErrorActionPreference = 'Stop'

if (-not $Platform) {
  $Platform = if ($env:PROCESSOR_ARCHITECTURE -eq 'ARM64') { 'windows-arm64' } else { 'windows-x64' }
}
$file = switch -Wildcard ($Platform) { 'windows-*' { 'xdmtorrent.dll' } 'macos-*' { 'libxdmtorrent.dylib' } default { 'libxdmtorrent.so' } }
$dir = Join-Path $Dest $Platform
New-Item -ItemType Directory -Force -Path $dir | Out-Null
$target = Join-Path $dir $file

if ($Local) {
  $src = Join-Path $BuildOut "$Platform\bin\$file"
  if (-not (Test-Path -LiteralPath $src)) { throw "no local build for ${Platform}: $src" }
  Copy-Item -LiteralPath $src -Destination $target -Force
  return $target
}

# A build kit made on the Mac carries the libraries (make-windows-kit.sh): use them as they are.
$kitCopy = Join-Path $PSScriptRoot "bin\$Platform\$file"
if (Test-Path -LiteralPath $kitCopy) {
  Copy-Item -LiteralPath $kitCopy -Destination $target -Force
  return $target
}

$line = Get-Content (Join-Path $PSScriptRoot 'binaries.lock') | Where-Object { $_ -like "$Platform|*" } | Select-Object -First 1
if (-not $line) { throw "binaries.lock has no entry for $Platform (use -Local)" }
$name, $lfile, $url, $hash = $line.Split('|')
if ($lfile -ne $file) { throw "binaries.lock names $lfile for $Platform, expected $file" }
if (-not (Test-Path -LiteralPath $target) -or (Get-FileHash -Algorithm SHA256 -LiteralPath $target).Hash.ToLower() -ne $hash) {
  $part = "$target.part"
  Invoke-WebRequest -UseBasicParsing -Uri $url -OutFile $part
  $got = (Get-FileHash -Algorithm SHA256 -LiteralPath $part).Hash.ToLower()
  if ($got -ne $hash) { Remove-Item -LiteralPath $part; throw "sha256 mismatch for ${Platform}: $got" }
  Move-Item -LiteralPath $part -Destination $target -Force
}
return $target
