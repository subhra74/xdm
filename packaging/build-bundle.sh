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
# TieredStopAtLevel=1 (C1 only) keeps the code cache at ~5 MB.
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
  # --- JIT ---
  -XX:TieredStopAtLevel=1
  -XX:CICompilerCount=1
  # --- misc ---
  # drop FlatLaf/Swing soft-referenced image caches on each GC
  -XX:SoftRefLRUPolicyMSPerMB=0
  # no hsperfdata mmap file
  -XX:-UsePerfData
  # bound the per-thread direct-buffer cache NIO keeps for heap-buffer writes
  -Djdk.nio.maxCachedBufferSize=262144
)

APP_NAME="XDM"
VENDOR="Xtreme Download Manager"
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
  -o, --out DIR       Output directory (default: build/dist)
  -h, --help          This message

Output: build/dist/<installer>, or build/dist/$APP_NAME(.app) for app-image.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    -t|--type) PKG_TYPE="$2"; shift 2 ;;
    -s|--skip-build) SKIP_MAVEN=1; shift ;;
    --with-locales) WITH_LOCALES=1; shift ;;
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

# ---- jpackage -------------------------------------------------------------
ARGS=(
  --type "$PKG_TYPE"
  --name "$APP_NAME"
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
for opt in "${JAVA_OPTIONS[@]}"; do ARGS+=(--java-options "$opt"); done

ICON_DIR="$PROJECT_ROOT/packaging/icons"
case "$OS" in
  mac)
    [[ -f "$ICON_DIR/xdm.icns" ]] && ARGS+=(--icon "$ICON_DIR/xdm.icns")
    ARGS+=(--mac-package-identifier com.xtremedownloadmanager.xdm
           --mac-package-name "$APP_NAME")
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
      ARGS+=(--win-menu --win-menu-group "$APP_NAME" --win-shortcut
             --win-dir-chooser --win-per-user-install
             --win-upgrade-uuid 6f9619ff-8b86-d011-b42d-00c04fc964ff)
    fi
    ;;
esac

echo ">> jpackage --type $PKG_TYPE ($OS, version $APP_VERSION)"
jpackage "${ARGS[@]}" ${EXTRA_ARGS+"${EXTRA_ARGS[@]}"}

echo
echo "Runtime size: $(du -sh "$RUNTIME_DIR" | cut -f1)"
echo "Output:"
ls -1 "$DEST_DIR"
