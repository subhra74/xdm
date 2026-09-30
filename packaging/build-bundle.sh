#!/usr/bin/env bash
#
# Builds a self-contained XDM bundle (trimmed JRE + app) with jlink/jpackage.
#
# Works on macOS, Linux and Windows (Git Bash / MSYS2). See --help.
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
# memory for the rest of the app. C1 alone costs ~10x the CPU per MB of TLS.
JAVA_OPTIONS=(
  # --- collector: minimum native overhead, heap returned to the OS quickly ---
  -XX:+UseSerialGC
  -XX:MinHeapFreeRatio=5
  -XX:MaxHeapFreeRatio=10
  -Xms4m
  -XX:-AlwaysPreTouch
  # AppMain runs a System.gc() every 15s; that is what triggers the shrink.
  -XX:-DisableExplicitGC
  # --- class metadata ---
  -XX:+ClassUnloading
  -XX:MinMetaspaceFreeRatio=1
  -XX:MaxMetaspaceFreeRatio=2
  -XX:MetaspaceReclaimPolicy=aggressive
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

# The app is "Xtreme Download Manager" - that is what --name gives the bundle, the installer and
# the Start-menu shortcut. Everything XDM registers with the OS (the login entry, the xdm-app://
# handler) instead points at a second launcher called "xdm-app", added with --add-launcher: a
# stable, space-free binary name that does not change when the display name does. The extra
# launcher gets no shortcut of its own (see the properties file below), so the Start menu shows
# one entry, named properly.
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
EXTRA_ARGS=()

usage() {
  cat <<EOF
Usage: packaging/build-bundle.sh [options] [-- extra jpackage args]

  -t, --type TYPE     Package type. Defaults to app-image.
                      macOS:   app-image | dmg | pkg
                      Linux:   app-image | deb | rpm
                      Windows: app-image | msi | exe
  -s, --skip-build    Skip "mvn package"; reuse xdm-app/target/$MAIN_JAR
      --with-locales  Add jdk.localedata (bigger bundle, full locale formatting)
      --record-classes  Before packaging, run XDM on the bundled runtime to record
                      packaging/cds/<os>.classlist for the AppCDS archive (see APPCDS.md)
  -o, --out DIR       Output directory (default: build/dist)
  -h, --help          This message

Output: build/dist/<installer>, or build/dist/$APP_NAME(.app) for app-image.
        The app image also contains a second launcher, $LAUNCHER_NAME, which is what the
        login entry and the $URL_SCHEME:// handler are registered against.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    -t|--type) PKG_TYPE="$2"; shift 2 ;;
    -s|--skip-build) SKIP_MAVEN=1; shift ;;
    --with-locales) WITH_LOCALES=1; shift ;;
    --record-classes) RECORD_CLASSES=1; shift ;;
    -o|--out) DEST_DIR="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    --) shift; EXTRA_ARGS=("$@"); break ;;
    *) echo "Unknown option: $1" >&2; usage; exit 1 ;;
  esac
done

case "$(uname -s)" in
  Darwin) OS=mac ;;
  Linux)  OS=linux ;;
  MINGW*|MSYS*|CYGWIN*) OS=windows ;;
  *) echo "Unsupported platform: $(uname -s)" >&2; exit 1 ;;
esac
PKG_TYPE="${PKG_TYPE:-app-image}"

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
    windows) fl_os=windows ;;
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
JIT_DIRECTIVES="jit-directives.json"
cp "$PROJECT_ROOT/packaging/$JIT_DIRECTIVES" "$INPUT_DIR/"

# ---- AppCDS archive (APPCDS.md) ---------------------------------------------
# A static archive of JDK + app classes, mapped read-only at startup instead of
# parsed into metaspace: ~19 MB less at idle. The JVM uses it only while the
# jar's size and mtime match the dump, so the jar is pinned to a fixed time and
# the app re-pins it on start if an installer or copy moved it (CdsJarPin).
#
# The class list is recorded by hand (--record-classes) and checked in, one per
# OS; without one, the bundle is built without an archive.
CDS_EPOCH=1577836800                                 # 2020-01-01T00:00:00Z
CDS_LIST="$PROJECT_ROOT/packaging/cds/$OS.classlist"
CDS_ARCHIVE="xdm.jsa"
CDS_PROPERTY="xdm.cds.mtime"                         # read by CdsJarPin
BUNDLED_JAVA="$RUNTIME_DIR/bin/java"
TZ=UTC touch -t 202001010000.00 "$INPUT_DIR/$MAIN_JAR"

if [[ $RECORD_CLASSES -eq 1 ]]; then
  mkdir -p "$(dirname "$CDS_LIST")"
  echo ">> recording $(basename "$CDS_LIST"): quit any running XDM first, then use this one"
  echo ">>   as users do - run a real download to completion - and quit it from the tray"
  "$BUNDLED_JAVA" "${JAVA_OPTIONS[@]}" \
    -XX:+UnlockDiagnosticVMOptions "-XX:CompilerDirectivesFile=$INPUT_DIR/$JIT_DIRECTIVES" \
    "-XX:DumpLoadedClassList=$CDS_LIST" \
    -cp "$INPUT_DIR/$MAIN_JAR" "$MAIN_CLASS"
fi

if [[ -s "$CDS_LIST" ]]; then
  echo ">> dumping the AppCDS archive from $(basename "$CDS_LIST")"
  # Classes named in the list but gone from the jar are skipped with a warning;
  # the output is quiet otherwise.
  "$BUNDLED_JAVA" -Xshare:dump "${JAVA_OPTIONS[@]}" \
    "-XX:SharedClassListFile=$CDS_LIST" \
    "-XX:SharedArchiveFile=$INPUT_DIR/$CDS_ARCHIVE" \
    -cp "$INPUT_DIR/$MAIN_JAR" >/dev/null
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
  echo ">> no packaging/cds/$OS.classlist - building without an AppCDS archive (see --record-classes)"
fi

# ---- JIT directive -------------------------------------------------------
# Added only now: $APPDIR exists only in the launcher, and the AppCDS dump above
# runs the bundled java directly. JitOverride's "Full JIT" setting drops the
# directive, CICompilerCount and ReservedCodeCacheSize lines again.
JAVA_OPTIONS+=(
  -XX:+UnlockDiagnosticVMOptions
  '-XX:CompilerDirectivesFile=$APPDIR/'"$JIT_DIRECTIVES"
)

# ---- jpackage -------------------------------------------------------------
# An added launcher inherits the main class, jar and java-options; all this file does is keep it
# out of the menus, so "xdm-app" never appears as a second entry beside the real one.
LAUNCHER_PROPS="$BUILD_DIR/$LAUNCHER_NAME.properties"
cat > "$LAUNCHER_PROPS" <<'PROPS'
win-shortcut=false
win-menu=false
linux-shortcut=false
PROPS

ARGS=(
  --type "$PKG_TYPE"
  --name "$APP_NAME"
  --add-launcher "$LAUNCHER_NAME=$LAUNCHER_PROPS"
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
    [[ -f "$ICON_DIR/xdm.icns" ]] && ARGS+=(--icon "$ICON_DIR/xdm.icns")
    # CFBundleName: what the menu bar and Finder show, independent of the launcher name.
    # CFBundleName drives the macOS menu bar, where Apple wants <= 15 characters, so the short
    # form goes there and the full name goes in CFBundleDisplayName / the bundle name below.
    ARGS+=(--mac-package-identifier com.xtremedownloadmanager.xdm
           --mac-package-name "XDM")
    ;;
  linux)
    [[ -f "$ICON_DIR/xdm.png" ]] && ARGS+=(--icon "$ICON_DIR/xdm.png")
    ARGS+=(--linux-shortcut
           --linux-menu-group "Network"
           --linux-app-category "net"
           --linux-package-name "xdm")
    ;;
  windows)
    [[ -f "$ICON_DIR/xdm.ico" ]] && ARGS+=(--icon "$ICON_DIR/xdm.ico")
    if [[ "$PKG_TYPE" != "app-image" ]]; then
      ARGS+=(--win-menu --win-menu-group "$DISPLAY_NAME" --win-shortcut
             --win-dir-chooser --win-per-user-install
             --win-upgrade-uuid 6f9619ff-8b86-d011-b42d-00c04fc964ff)
    fi
    ;;
esac

echo ">> jpackage --type $PKG_TYPE ($OS, version $APP_VERSION)"
jpackage "${ARGS[@]}" ${EXTRA_ARGS+"${EXTRA_ARGS[@]}"}

# jpackage copies the jar with a fresh mtime, which would cost a fresh image its
# archive until CdsJarPin fixes it on the first start; pin the image's copy now.
if [[ -s "$CDS_LIST" && "$PKG_TYPE" == "app-image" ]]; then
  while IFS= read -r jar; do
    TZ=UTC touch -t 202001010000.00 "$jar"
  done < <(find "$DEST_DIR/$APP_NAME"* -path "*/app/$MAIN_JAR" 2>/dev/null)
fi

# ---- macOS: display name + the xdm-app:// scheme ---------------------------
# A bundle can only claim a URL scheme in its Info.plist, and jpackage has no option for it, so
# the plist is patched afterwards. This only reaches the plist for an app-image build; a dmg/pkg
# build makes its .app inside jpackage's own temp dir (see the note printed below).
if [[ "$OS" == "mac" && "$PKG_TYPE" == "app-image" ]]; then
  BUNDLE="$DEST_DIR/$APP_NAME.app"
  if [[ -d "$BUNDLE" ]]; then
    PLIST="$BUNDLE/Contents/Info.plist"
    echo ">> declaring the $URL_SCHEME:// scheme in Info.plist"
    plutil -replace CFBundleDisplayName -string "$APP_NAME" "$PLIST" >/dev/null 2>&1 ||
      plutil -insert CFBundleDisplayName -string "$APP_NAME" "$PLIST"
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
  fi
elif [[ "$OS" == "mac" ]]; then
  echo ">> note: $PKG_TYPE bundles cannot be patched for the $URL_SCHEME:// scheme;"
  echo ">>       build --type app-image first, then package that image."
fi

echo
echo "Runtime size: $(du -sh "$RUNTIME_DIR" | cut -f1)"
echo "Output:"
ls -1 "$DEST_DIR"
