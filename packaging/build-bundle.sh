#!/usr/bin/env bash
#
# Builds a self-contained XDM bundle (trimmed JRE + app) with jlink/jpackage.
#
# macOS and Linux. Windows (x64 and arm64) is built by build-bundle.ps1. See --help.
#
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$PROJECT_ROOT"

# --------------------------------------------------------------------------
# JDK modules the app actually needs.
#
# Determined with `jdeps --ignore-missing-deps --print-module-deps` over the
# fat jar plus a runtime check of the pieces static analysis cannot see:
#   java.desktop   Swing/AWT UI + SystemTray + java.awt.Desktop
#                  (pulls java.datatransfer, java.prefs, java.xml transitively;
#                   java.prefs is used by FlatLaf, java.xml by the DASH MPD parser)
#   java.logging   OkHttp's platform logger (java.util.logging) - hard requirement,
#                  the app dies with NoClassDefFoundError on the first request without it
#   jdk.crypto.ec  legacy SunEC provider, insurance for EC/ECDSA TLS on older JDKs
#   jdk.unsupported  sun.misc.Unsafe, used reflectively by parts of the Kotlin/OkIO stack
#
# Deliberately excluded (verified unnecessary): java.naming, java.management,
# java.sql, java.scripting, jdk.httpserver (only the tests use it; the browser
# integration server is a raw ServerSocket), jdk.zipfs, jdk.localedata.
# jdk.localedata alone adds ~15 MB; pass --with-locales if you need non-English
# date/number formatting (UI translations come from the app's own bundles and
# do NOT need it).
# --------------------------------------------------------------------------
MODULES="java.desktop,java.logging,jdk.crypto.ec,jdk.unsupported"

# JVM tuning flags baked into the launcher.
#
# Goal: smallest steady-state footprint in Task Manager / Activity Monitor,
# with no -Xmx (so max heap stays the default 1/4 of RAM and the app can never
# hit an artificial OOM). Measured with JDK 25 Native Memory Tracking on an
# idle app after several of the app's own periodic System.gc() cycles
# (JVM-committed memory, the part these flags control):
#
#   SerialGC      68 MB   (GC bookkeeping 0.1 MB)   <- chosen
#   Shenandoah    82 MB   (GC bookkeeping 5.9 MB)
#   ZGC          113 MB   (heap floor ~53 MB)
#   G1           120 MB   (GC bookkeeping 51 MB)
#   ParallelGC   195 MB   (GC bookkeeping 123 MB)
#
# The app's live set is ~10-15 MB, so the heap is never the problem; what costs
# memory is per-collector native bookkeeping (card tables, remembered sets),
# which G1/Parallel size from the *maximum* heap. Since we deliberately do not
# set -Xmx, that penalty is large - SerialGC is the only collector whose
# overhead is independent of max heap. Its pauses are irrelevant at this live
# set size.
#
# JIT: C1 compiles everything and C2 only the JDK's crypto hot methods, named in
# packaging/jit-directives.json (added below, once the app dir exists). HTTPS
# then runs on the AES/GHASH/ChaCha20 intrinsics at C2 speed without paying C2's
# memory for the rest of the app. Required: without C2, TLS costs 4-10x the CPU
# per MB. There is no C1-only mode.
JAVA_OPTIONS=(
  # --- collector: minimum native overhead, heap returned to the OS quickly ---
  -XX:+UseSerialGC
  -XX:MinHeapFreeRatio=5
  -XX:MaxHeapFreeRatio=10
  # >= 5m: the AppCDS archive's ~3.4 MB of pre-built heap objects must fit the initial heap, or the
  # JVM skips them (and the archived module graph) without a word. 4m was just too small.
  -Xms6m
  -XX:-AlwaysPreTouch
  # AppMain runs a System.gc() every 15s; that is what triggers the shrink.
  -XX:-DisableExplicitGC
  # --- class metadata ---
  -XX:+ClassUnloading
  -XX:MinMetaspaceFreeRatio=1
  -XX:MaxMetaspaceFreeRatio=2
  -XX:CompressedClassSpaceSize=64m
  # --- JIT (the directive itself is added after the AppCDS step) ---
  -XX:CICompilerCount=2
  -XX:ReservedCodeCacheSize=32m
  # --- misc ---
  # drop FlatLaf/Swing soft-referenced image caches on each GC
  -XX:SoftRefLRUPolicyMSPerMB=0
  # no hsperfdata mmap file
  -XX:-UsePerfData
  # bound the per-thread direct-buffer cache NIO keeps for heap-buffer writes
  -Djdk.nio.maxCachedBufferSize=262144
)

# The image, its one launcher and the macOS bundle are all "xdm-app" (xdm-app.app): a stable,
# space-free name, which is also what XDM registers with the OS (the login entry, the xdm-app://
# handler). The name people see is set separately (MAC_DISPLAY_NAME below, the .desktop Name=).
APP_NAME="Xtreme Download Manager"
LAUNCHER_NAME="xdm-app"
VENDOR="Xtreme Download Manager"
URL_SCHEME="xdm-app"
DESCRIPTION="Xtreme Download Manager"
MAIN_CLASS="xdm.app.AppMain"
MAIN_JAR="xdm-app.jar"
COPYRIGHT="Copyright (c) Subhra Das Gupta"

BUILD_DIR="$PROJECT_ROOT/build"
RUNTIME_DIR="$BUILD_DIR/runtime"
INPUT_DIR="$BUILD_DIR/input"
DEST_DIR="$BUILD_DIR/dist"

PKG_TYPE=""
SKIP_MAVEN=0
WITH_LOCALES=0
RECORD_CLASSES=0
RECORD_SERVER=""
EXTRA_ARGS=()

usage() {
  cat <<EOF
Usage: packaging/build-bundle.sh [options] [-- extra jpackage args]

  -t, --type TYPE     Package type. Defaults to app-image.
                      macOS:   app-image | dmg | pkg
                      Linux:   app-image (deb, rpm, Arch and tar.gz: packaging/build-linux.sh)
  -s, --skip-build    Skip "mvn package"; reuse xdm-app/target/$MAIN_JAR
      --with-locales  Add jdk.localedata (bigger bundle, full locale formatting)
      --record-classes  Before packaging, run XDM on the bundled runtime to record
                      packaging/cds/<os>-<arch>.classlist for the AppCDS archive (see APPCDS.md);
                      use XDM yourself, then quit it from the tray
      --record-server URL  Like --record-classes, but XDM runs the scripted session against
                      packaging/recording/RecordServer.java at URL on a fresh profile and
                      exits by itself; the build stops if a step of the session failed
  -o, --out DIR       Output directory (default: build/dist)
  -h, --help          This message

Output: macOS: build/dist/$LAUNCHER_NAME.app (shown as XDM); --type dmg also makes
        build/dist/$LAUNCHER_NAME-<version>-<arch>.dmg from it.
        Linux: build/dist/$LAUNCHER_NAME. The $LAUNCHER_NAME launcher is what the login entry and the
        $URL_SCHEME:// handler are registered against.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    -t|--type) PKG_TYPE="$2"; shift 2 ;;
    -s|--skip-build) SKIP_MAVEN=1; shift ;;
    --with-locales) WITH_LOCALES=1; shift ;;
    --record-classes) RECORD_CLASSES=1; shift ;;
    --record-server) RECORD_CLASSES=1; RECORD_SERVER="$2"; shift 2 ;;
    -o|--out) DEST_DIR="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    --) shift; EXTRA_ARGS=("$@"); break ;;
    *) echo "Unknown option: $1" >&2; usage; exit 1 ;;
  esac
done

case "$(uname -s)" in
  Darwin) OS=mac ;;
  Linux)  OS=linux ;;
  MINGW*|MSYS*|CYGWIN*)
    echo "On Windows, use packaging/build-bundle.ps1 (x64 and arm64, app image and MSI)." >&2; exit 1 ;;
  *) echo "Unsupported platform: $(uname -s)" >&2; exit 1 ;;
esac
PKG_TYPE="${PKG_TYPE:-app-image}"
if [[ "$OS" == "linux" && "$PKG_TYPE" != "app-image" ]]; then
  echo "On Linux this script builds the app image; packaging/build-linux.sh makes the packages from it." >&2
  exit 1
fi
# Linux and macOS have one launcher, xdm-app, which also names the image (xdm-app.app on macOS) and the
# package (PACKAGING.md 3.3, 4.1). The name people see comes from the menu entry's Name= on Linux and
# from CFBundleName / CFBundleDisplayName ($MAC_DISPLAY_NAME) on macOS.
IMAGE_NAME="$LAUNCHER_NAME"
MAC_DISPLAY_NAME="XDM"
# A macOS dmg is made here from the patched app image (hdiutil, below), not by jpackage, whose dmg
# would carry an unpatched Info.plist (no xdm-app:// scheme, broken signature).
JPACKAGE_TYPE="$PKG_TYPE"
[[ "$OS" == "mac" && "$PKG_TYPE" == "dmg" ]] && JPACKAGE_TYPE="app-image"

[[ $WITH_LOCALES -eq 1 ]] && MODULES="$MODULES,jdk.localedata"

# ---- toolchain ------------------------------------------------------------
for tool in jlink jpackage; do
  command -v "$tool" >/dev/null 2>&1 || { echo "$tool not found on PATH (JDK 17+ required)" >&2; exit 1; }
done
JDK_MAJOR="$(java -XshowSettings:properties -version 2>&1 | sed -n 's/.*java\.specification\.version = \([0-9]*\).*/\1/p' | head -1)"
: "${JDK_MAJOR:=17}"
if [[ "$JDK_MAJOR" -ge 21 ]]; then COMPRESS="zip-9"; else COMPRESS="2"; fi

# Version-gated flags join JAVA_OPTIONS here, before anything runs the bundled
# runtime: the AppCDS dump below must see the exact flags the launcher will use.
# FlatLaf loads a native library; JDK 22+ warns about that unless native access
# is enabled explicitly (and will block it in a future release).
if [[ "$JDK_MAJOR" -ge 22 ]]; then
  JAVA_OPTIONS+=(--enable-native-access=ALL-UNNAMED)
fi
# 64-bit object headers instead of 96-bit. A product (non-experimental) flag
# since JDK 25 and the default from JDK 27 (JEP 534); on JDK 24 and older it is
# experimental, so only pass it where it is supported. Measured here: heap
# 14.4 -> 12.9 MB, metaspace 22.0 -> 20.6 MB committed.
if [[ "$JDK_MAJOR" -ge 25 ]]; then
  JAVA_OPTIONS+=(-XX:+UseCompactObjectHeaders)
fi

# ---- app jar --------------------------------------------------------------
if [[ $SKIP_MAVEN -eq 0 ]]; then
  echo ">> mvn clean package"
  mvn -q clean package -DskipTests
fi
JAR_PATH="$PROJECT_ROOT/xdm-app/target/$MAIN_JAR"
[[ -f "$JAR_PATH" ]] || { echo "Missing $JAR_PATH - run without --skip-build" >&2; exit 1; }

APP_VERSION="$(sed -n 's/.*"currentVersion"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' \
  xdm-app/src/main/resources/app-version.json | head -1)"
: "${APP_VERSION:=1.0.0}"

# --------------------------------------------------------------------------
# Drop the bundled natives for every platform except this build's target.
#
# FlatLaf ships its native library for seven platforms; a bundle runs on exactly
# one. Only the copy under build/input that jpackage wraps is trimmed.
# xdm-app/target/xdm-app.jar keeps every native, so the fat jar stays runnable
# on any platform when it is shared or launched on its own with `java -jar`.
#
# FlatLaf resolves its library through an <os>-<arch> classifier in the entry
# name, so keeping just the matching entry is enough.
# --------------------------------------------------------------------------
strip_foreign_natives() {
  local jar="$1"

  if ! command -v zip >/dev/null 2>&1; then
    echo ">> zip not on PATH - bundling natives for all platforms"
    return 0
  fi

  local fl_os fl_arch
  case "$OS" in
    mac)     fl_os=macos   ;;
    linux)   fl_os=linux   ;;
  esac

  local machine
  machine="$(uname -m)"
  case "$machine" in
    arm64|aarch64) fl_arch=arm64  ;;
    x86_64|amd64)  fl_arch=x86_64 ;;
    *)
      echo ">> unrecognised machine '$machine' - bundling natives for all platforms"
      return 0
      ;;
  esac

  local fl_dir="com/formdev/flatlaf/natives/"

  local before after entry
  local drop=()
  before="$(wc -c <"$jar")"
  while IFS= read -r entry; do
    case "$entry" in
      "$fl_dir"*"-${fl_os}-${fl_arch}."*) continue ;;
      *) drop+=("$entry") ;;
    esac
  done < <(jar tf "$jar" | grep -E '\.(dylib|jnilib|so|dll)$' || true)

  if [[ ${#drop[@]} -eq 0 ]]; then
    echo ">> no foreign natives to strip"
    return 0
  fi

  # zip -d rewrites the central directory and copies the surviving entries
  # byte-for-byte, so the assembly's deliberately uncompressed entries stay
  # uncompressed.
  zip -q -d "$jar" "${drop[@]}"
  after="$(wc -c <"$jar")"
  echo ">> stripped ${#drop[@]} foreign natives ($OS/$machine): $(( (before - after) / 1024 )) KB"
}

# ---- trimmed runtime ------------------------------------------------------
echo ">> jlink runtime: $MODULES"
rm -rf "$RUNTIME_DIR" "$INPUT_DIR"
mkdir -p "$BUILD_DIR" "$INPUT_DIR" "$DEST_DIR"
STRIP_NATIVE=()
if jlink --help 2>&1 | grep -q -- '--strip-native-debug-symbols'; then
  STRIP_NATIVE=(--strip-native-debug-symbols exclude-debuginfo-files)
fi
jlink \
  --add-modules "$MODULES" \
  --strip-debug \
  ${STRIP_NATIVE+"${STRIP_NATIVE[@]}"} \
  --no-header-files \
  --no-man-pages \
  --compress="$COMPRESS" \
  --output "$RUNTIME_DIR"

cp "$JAR_PATH" "$INPUT_DIR/$MAIN_JAR"
strip_foreign_natives "$INPUT_DIR/$MAIN_JAR"

# FlatLaf's native library as a file in app/, loaded from there through
# -Dflatlaf.nativeLibraryPath (added with the JIT directive below). Otherwise
# FlatLaf copies it out of the jar into <tmpdir>/flatlaf.temp on every start and
# loads it from there. The name is the one FlatLaf asks for:
# System.mapLibraryName("flatlaf-<os>-<arch>"), the same as the jar entry's. The
# jar keeps its copy, which FlatLaf falls back to (logging it) if this one is
# missing. macOS: FlatLaf's dylib comes signed, and the bundle re-sign covers it.
FLATLAF_NATIVE=""
case "$OS-$(uname -m)" in
  mac-arm64)                FLATLAF_NATIVE="libflatlaf-macos-arm64.dylib" ;;
  mac-x86_64)               FLATLAF_NATIVE="libflatlaf-macos-x86_64.dylib" ;;
  linux-aarch64|linux-arm64) FLATLAF_NATIVE="libflatlaf-linux-arm64.so" ;;
  linux-x86_64)             FLATLAF_NATIVE="libflatlaf-linux-x86_64.so" ;;
esac
if [[ -n "$FLATLAF_NATIVE" ]] &&
   unzip -j -o -q "$INPUT_DIR/$MAIN_JAR" "com/formdev/flatlaf/natives/$FLATLAF_NATIVE" -d "$INPUT_DIR" 2>/dev/null; then
  echo ">> FlatLaf native in app/: $FLATLAF_NATIVE"
else
  FLATLAF_NATIVE=""
  echo ">> no FlatLaf native for $OS/$(uname -m) - FlatLaf extracts it from the jar at run time"
fi
JIT_DIRECTIVES="jit-directives.json"
cp "$PROJECT_ROOT/packaging/$JIT_DIRECTIVES" "$INPUT_DIR/"

# ---- AppCDS archive (APPCDS.md) ---------------------------------------------
# A static archive of JDK + app classes, mapped read-only at startup instead of
# parsed into metaspace: ~19 MB less at idle. The JVM uses it only while the
# jar's size and mtime match the dump, so the jar is pinned to a fixed time and
# the app re-pins it on start if an installer or copy moved it (CdsJarPin).
#
# The class list is recorded (--record-server, or by hand with --record-classes)
# and checked in, one per OS and architecture: each is recorded on its own
# runtime, so its @cp lines match that JDK build. Without one, the bundle is
# built without an archive.
CDS_EPOCH=1577836800                                 # 2020-01-01T00:00:00Z
JDK_ARCH="$(sed -n 's/^OS_ARCH="\(.*\)"/\1/p' "$(dirname "$(command -v jlink)")/../release" 2>/dev/null)"
case "${JDK_ARCH:-$(uname -m)}" in
  aarch64|arm64) CDS_ARCH=arm64 ;;
  x86_64|amd64)  CDS_ARCH=x64 ;;
  *)             CDS_ARCH="${JDK_ARCH:-$(uname -m)}" ;;
esac
CDS_LIST="$PROJECT_ROOT/packaging/cds/$OS-$CDS_ARCH.classlist"
CDS_ARCHIVE="xdm.jsa"
CDS_PROPERTY="xdm.cds.mtime"                         # read by CdsJarPin
BUNDLED_JAVA="$RUNTIME_DIR/bin/java"
TZ=UTC touch -t 202001010000.00 "$INPUT_DIR/$MAIN_JAR"

if [[ $RECORD_CLASSES -eq 1 ]]; then
  mkdir -p "$(dirname "$CDS_LIST")"
  RECORDED="$BUILD_DIR/recorded.classlist"
  SESSION=()
  if [[ -n "$RECORD_SERVER" ]]; then
    # A fresh profile: the session starts from first run and leaves the user's downloads alone.
    RECORD_HOME="$BUILD_DIR/record-home"
    rm -rf "$RECORD_HOME" && mkdir -p "$RECORD_HOME"
    SESSION=("-Duser.home=$RECORD_HOME" "-Dxdm.record.session=$RECORD_SERVER")
    echo ">> recording $(basename "$CDS_LIST") with the scripted session against $RECORD_SERVER"
    echo ">>   quit any running XDM first (port 8597); XDM exits by itself when the session ends"
  else
    echo ">> recording $(basename "$CDS_LIST"): quit any running XDM first, then use this one"
    echo ">>   as users do - run a real download to completion - and quit it from the tray"
  fi
  status=0
  "$BUNDLED_JAVA" "${JAVA_OPTIONS[@]}" \
    -XX:+UnlockDiagnosticVMOptions "-XX:CompilerDirectivesFile=$INPUT_DIR/$JIT_DIRECTIVES" \
    ${FLATLAF_NATIVE:+"-Dflatlaf.nativeLibraryPath=$INPUT_DIR"} \
    ${SESSION[@]+"${SESSION[@]}"} \
    "-XX:DumpLoadedClassList=$RECORDED" \
    -cp "$INPUT_DIR/$MAIN_JAR" "$MAIN_CLASS" || status=$?
  if [[ -n "$RECORD_SERVER" && $status -ne 0 ]]; then
    echo "The recording session reported $status failed step(s) (RECORD: lines above);" \
      "$(basename "$CDS_LIST") was not updated" >&2
    exit 1
  fi
  # The session's own classes (xdm.app.recording) never load in a user's run: keep them out.
  grep -v 'xdm/app/recording/' "$RECORDED" > "$CDS_LIST" || true
  rm -f "$RECORDED"
fi

# The recording session (xdm.app.recording) is build tooling: it leaves the bundle's jar once it has
# run, before the archive is dumped, so the archive matches the jar that ships. Nothing else loads it:
# the app only calls it when -Dxdm.record.session is set. target/xdm-app.jar keeps it for recording.
if command -v zip >/dev/null 2>&1; then
  zip -q -d "$INPUT_DIR/$MAIN_JAR" 'xdm/app/recording/*' >/dev/null 2>&1 || true
  TZ=UTC touch -t 202001010000.00 "$INPUT_DIR/$MAIN_JAR"
  echo ">> removed the recording session (xdm/app/recording) from the bundled jar"
else
  echo ">> zip not on PATH - the bundled jar keeps xdm/app/recording (unused, ~25 KB)"
fi

if [[ -s "$CDS_LIST" ]]; then
  echo ">> dumping the AppCDS archive from $(basename "$CDS_LIST")"
  DUMP_LOG="$BUILD_DIR/cds-dump.log"
  "$BUNDLED_JAVA" -Xshare:dump "${JAVA_OPTIONS[@]}" \
    "-XX:SharedClassListFile=$CDS_LIST" \
    "-XX:SharedArchiveFile=$INPUT_DIR/$CDS_ARCHIVE" \
    -cp "$INPUT_DIR/$MAIN_JAR" >"$DUMP_LOG" || { cat "$DUMP_LOG"; exit 1; }
  # Lines recorded against another JDK build or an older jar are skipped (the class is
  # still archived, only less pre-resolved). Say so: it's the cue to record the list again.
  STALE="$(grep -c -E 'out of sync|Preload Warning' "$DUMP_LOG" || true)"
  if [[ "${STALE:-0}" -gt 0 ]]; then
    echo "!! $STALE stale entries in $(basename "$CDS_LIST") (JDK or app changed since it was" \
      "recorded) - re-record with --record-server / --record-classes" >&2
  fi
  # $APPDIR is expanded by the jpackage launcher to the image's app directory.
  # ArchiveRelocationMode=0 maps the archive at its preferred address, so its
  # pages stay shared instead of turning private on relocation (-9 MB).
  JAVA_OPTIONS+=(
    '-XX:SharedArchiveFile=$APPDIR/'"$CDS_ARCHIVE"
    -XX:+UnlockDiagnosticVMOptions
    -XX:ArchiveRelocationMode=0
    "-D$CDS_PROPERTY=$CDS_EPOCH"
  )
else
  echo ">> no packaging/cds/$(basename "$CDS_LIST") - building without an AppCDS archive (see --record-classes)"
fi

# ---- JIT directive, FlatLaf native path -----------------------------------
# Added only now: $APPDIR exists only in the launcher, and the AppCDS dump above
# runs the bundled java directly (the recording run passes both with their build
# paths). JitOverride's "Full JIT" setting drops the directive, CICompilerCount
# and ReservedCodeCacheSize lines again, and keeps the FlatLaf path.
JAVA_OPTIONS+=(
  -XX:+UnlockDiagnosticVMOptions
  '-XX:CompilerDirectivesFile=$APPDIR/'"$JIT_DIRECTIVES"
)
[[ -n "$FLATLAF_NATIVE" ]] && JAVA_OPTIONS+=('-Dflatlaf.nativeLibraryPath=$APPDIR')

# ---- jpackage -------------------------------------------------------------
ARGS=(
  --type "$JPACKAGE_TYPE"
  --name "$IMAGE_NAME"
  --app-version "$APP_VERSION"
  --vendor "$VENDOR"
  --description "$DESCRIPTION"
  --copyright "$COPYRIGHT"
  --input "$INPUT_DIR"
  --main-jar "$MAIN_JAR"
  --main-class "$MAIN_CLASS"
  --runtime-image "$RUNTIME_DIR"
  --dest "$DEST_DIR"
)
for opt in "${JAVA_OPTIONS[@]}"; do ARGS+=(--java-options "$opt"); done

ICON_DIR="$PROJECT_ROOT/packaging/icons"
case "$OS" in
  mac)
    # The bundle icon is what the Dock, Finder and notifications show; without one jpackage puts in
    # the generic Java icon. Built from the app's own logo PNGs unless packaging/icons/xdm.icns exists.
    MAC_ICON="$ICON_DIR/xdm.icns"
    if [[ ! -f "$MAC_ICON" ]]; then
      LOGO_DIR="$PROJECT_ROOT/xdm-app/src/main/resources/images"
      ICONSET="$BUILD_DIR/xdm.iconset"
      rm -rf "$ICONSET" && mkdir -p "$ICONSET"
      # name:source size; 512@2x (1024 px) is optional and there is no source that big.
      for spec in 16x16:16 16x16@2x:32 32x32:32 32x32@2x:64 128x128:128 128x128@2x:256 \
                  256x256:256 256x256@2x:512 512x512:512; do
        cp "$LOGO_DIR/xdm-logo-${spec#*:}.png" "$ICONSET/icon_${spec%%:*}.png"
      done
      MAC_ICON="$BUILD_DIR/xdm.icns"
      iconutil -c icns -o "$MAC_ICON" "$ICONSET"
    fi
    ARGS+=(--icon "$MAC_ICON")
    # CFBundleName: what the menu bar, Dock and notifications show, independent of the bundle's
    # file name (xdm-app.app). Apple wants <= 15 characters there; CFBundleDisplayName is set to
    # the same below.
    ARGS+=(--mac-package-identifier com.xtremedownloadmanager.xdm
           --mac-package-name "$MAC_DISPLAY_NAME")
    ;;
  linux)
    # The image's icon (lib/xdm-app.png); the packages install their own copies under hicolor.
    ARGS+=(--icon "$PROJECT_ROOT/xdm-app/src/main/resources/images/xdm-logo-256.png")
    ;;
esac

echo ">> jpackage --type $JPACKAGE_TYPE ($OS, version $APP_VERSION)"
jpackage "${ARGS[@]}" ${EXTRA_ARGS+"${EXTRA_ARGS[@]}"}

# jpackage copies the jar with a fresh mtime, which would cost a fresh image its
# archive until CdsJarPin fixes it on the first start; pin the image's copy now.
if [[ -s "$CDS_LIST" && "$JPACKAGE_TYPE" == "app-image" ]]; then
  while IFS= read -r jar; do
    TZ=UTC touch -t 202001010000.00 "$jar"
  done < <(find "$DEST_DIR/$IMAGE_NAME"* -path "*/app/$MAIN_JAR" 2>/dev/null)
fi

# ---- macOS: display name + the xdm-app:// scheme ---------------------------
# A bundle can only claim a URL scheme in its Info.plist, and jpackage has no option for it, so
# the plist is patched afterwards. This only reaches the plist for an app image (also the base of
# --type dmg); a pkg build makes its .app inside jpackage's own temp dir (see the note printed below).
if [[ "$OS" == "mac" && "$JPACKAGE_TYPE" == "app-image" ]]; then
  BUNDLE="$DEST_DIR/$IMAGE_NAME.app"
  if [[ -d "$BUNDLE" ]]; then
    PLIST="$BUNDLE/Contents/Info.plist"
    echo ">> declaring the $URL_SCHEME:// scheme in Info.plist"
    plutil -replace CFBundleDisplayName -string "$MAC_DISPLAY_NAME" "$PLIST" >/dev/null 2>&1 ||
      plutil -insert CFBundleDisplayName -string "$MAC_DISPLAY_NAME" "$PLIST"
    plutil -remove CFBundleURLTypes "$PLIST" >/dev/null 2>&1 || true
    plutil -insert CFBundleURLTypes -xml \
      "<array><dict>\
         <key>CFBundleURLName</key><string>$APP_NAME</string>\
         <key>CFBundleTypeRole</key><string>Viewer</string>\
         <key>CFBundleURLSchemes</key><array><string>$URL_SCHEME</string></array>\
       </dict></array>" "$PLIST"
    # Tell LaunchServices about it now, so the scheme works without a logout.
    /System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister \
      -f "$BUNDLE" >/dev/null 2>&1 || true
    # jpackage signed the bundle before the plist edits above, so its signature no longer matches
    # ("invalid Info.plist"); a downloaded copy would then be "damaged and can't be opened".
    # Re-sign ad-hoc and fail the build if the result does not verify (PACKAGING.md §6).
    echo ">> re-signing the bundle ad-hoc"
    codesign --force --deep -s - "$BUNDLE"
    codesign --verify --deep --strict "$BUNDLE"

    # ---- macOS dmg: the patched bundle plus an /Applications link (PACKAGING.md §6 step 5) ----
    if [[ "$PKG_TYPE" == "dmg" ]]; then
      DMG="$DEST_DIR/$LAUNCHER_NAME-$APP_VERSION-$CDS_ARCH.dmg"
      STAGE="$BUILD_DIR/dmg-root"
      echo ">> hdiutil: $(basename "$DMG")"
      rm -rf "$STAGE" && mkdir -p "$STAGE"
      ditto "$BUNDLE" "$STAGE/$(basename "$BUNDLE")"   # ditto keeps the signature intact
      ln -s /Applications "$STAGE/Applications"
      hdiutil create -volname "$MAC_DISPLAY_NAME" -srcfolder "$STAGE" -fs HFS+ -format UDZO -ov "$DMG" >/dev/null
      hdiutil verify "$DMG" >/dev/null
      rm -rf "$STAGE"
    fi
  fi
elif [[ "$OS" == "mac" ]]; then
  echo ">> note: $PKG_TYPE bundles cannot be patched for the $URL_SCHEME:// scheme;"
  echo ">>       build --type app-image first, then package that image."
fi

echo
echo "Runtime size: $(du -sh "$RUNTIME_DIR" | cut -f1)"
echo "Output:"
ls -1 "$DEST_DIR"
