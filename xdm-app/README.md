# xdm-app — XDM desktop application

The Swing desktop application of XDM (Xtreme Download Manager) and its browser-integration
HTTP server. Depends on [`xdm-core`](../xdm-core/README.md), which holds the download engine.

Main class: `xdm.app.AppMain`.

## What lives here

- **UI** (`ui/screens`, `ui/components`) — FlatLaf-themed Swing windows, dialogs and the
  downloads list. `AppInstance` is the facade the rest of the app calls to show/update
  windows; it marshals everything onto the Swing EDT.
- **`DownloadManager`** — the orchestrator. Creates the concrete engine task per download
  type, enforces `maxParallelDownloads`, persists records, and implements the engine's
  `DownloadHost` callbacks (progress, completion, temp vs. final paths).
- **Browser integration** (`xdm.integration`) — a local HTTP server on `127.0.0.1:8597`
  that the companion extension in `browser-extension/` POSTs captured download/media
  requests to.
- **`AppContext`** — the service locator holding the DB, config, downloader, queue,
  scheduler and video tracker. Most code reaches services through it.

At startup the app creates `~/.xdm-app/` (and `~/.xdm-app/tmp/`) for config, the downloads
DB, per-task `task-<id>.info` files and `<id>.state` resume files.

## Build & Run

Plain Maven (system `mvn`, no wrapper), run from the **repository root**:

```bash
mvn -q clean package              # build all modules → xdm-app/target/xdm-app.jar (fat jar)
mvn -q -pl xdm-app -am package    # build xdm-app and its dependencies only
mvn -q -pl xdm-app test -DskipTests=false
```

Run:

```bash
java -jar xdm-app/target/xdm-app.jar          # or run AppMain from the IDE
java -jar xdm-app/target/xdm-app.jar --no-gc  # disable the periodic System.gc() thread
```

This module targets JDK 25 (`xdm-core` targets JDK 8).

## Run with AppCDS and the JIT directive

This runs the fat jar the way the packaged build does (`packaging/build-bundle.sh`): Serial GC
with a heap that shrinks back, a static AppCDS archive mapped at a fixed address, and the
compiler directive `packaging/jit-directives.json` (C1 for all code, C2 only for the JDK's
crypto hot methods, see [PACKAGING.md §3.2](../PACKAGING.md)). Background on each flag is in
[APPCDS.md](../APPCDS.md). Needs JDK 25; run from the **repository root**.

### 1. Flags (shared by every step below)

```bash
XDM_FLAGS=(
  -XX:+UseSerialGC -XX:MinHeapFreeRatio=5 -XX:MaxHeapFreeRatio=10 -Xms4m -XX:-AlwaysPreTouch
  -XX:-DisableExplicitGC -XX:+ClassUnloading -XX:MinMetaspaceFreeRatio=1 -XX:MaxMetaspaceFreeRatio=2
  -XX:CompressedClassSpaceSize=64m -XX:SoftRefLRUPolicyMSPerMB=0 -XX:-UsePerfData
  -Djdk.nio.maxCachedBufferSize=262144 --enable-native-access=ALL-UNNAMED -XX:+UseCompactObjectHeaders
  # JIT: tiered on, one C1 + one C2 thread; the directive keeps C2 to the crypto methods
  -XX:CICompilerCount=2 -XX:ReservedCodeCacheSize=32m
  -XX:+UnlockDiagnosticVMOptions -XX:CompilerDirectivesFile=packaging/jit-directives.json
)
```

- `-XX:+UnlockDiagnosticVMOptions` must come before `CompilerDirectivesFile` and `ArchiveRelocationMode`.
- Never add `-XX:TieredStopAtLevel=1`: the JDK's TLS then runs on C1 alone at 4-10x the CPU per MB.
- The archive is only usable with the same GC, `UseCompactObjectHeaders` and compressed class
  space settings it was dumped with, so keep these flags identical in steps 3-5.

### 2. Stage and pin the jar

The JVM maps the archive only while the jar's size and mtime match dump time. `mvn package`
rewrites the jar, so work on a copy pinned to a fixed time (the same one the build uses):

```bash
mkdir -p build/cds
cp xdm-app/target/xdm-app.jar build/cds/xdm-app.jar
TZ=UTC touch -t 202001010000.00 build/cds/xdm-app.jar     # 2020-01-01T00:00:00Z = 1577836800
```

Redo steps 2-4 after every rebuild of the jar.

### 3. Record the class list

Start XDM with the flags, **run a real download to completion**, then quit XDM from the tray. An
idle-only recording misses the download path.

```bash
java "${XDM_FLAGS[@]}" -XX:DumpLoadedClassList=build/cds/classes.lst -jar build/cds/xdm-app.jar
```

This uses your real `~/.xdm-app` profile: the download lands in your download folder and appears
in the list.

### 4. Dump the archive

```bash
java -Xshare:dump "${XDM_FLAGS[@]}" \
  -XX:SharedClassListFile=build/cds/classes.lst \
  -XX:SharedArchiveFile=build/cds/xdm.jsa \
  -cp build/cds/xdm-app.jar
```

Classes in the list that are gone from the jar are skipped with a warning.

### 5. Run

```bash
java "${XDM_FLAGS[@]}" \
  -XX:SharedArchiveFile=build/cds/xdm.jsa \
  -XX:ArchiveRelocationMode=0 \
  -Dxdm.cds.mtime=1577836800 \
  -jar build/cds/xdm-app.jar
```

- `-XX:ArchiveRelocationMode=0` maps the archive at its preferred address, so its pages stay
  shared instead of turning private on relocation.
- `-Dxdm.cds.mtime` lets `CdsJarPin` put the jar's mtime back if something changed it; the
  archive is then used again from the next start.

To check that the archive maps, add `-Xshare:on -Xlog:cds` once: XDM refuses to start if the
archive can't be used, and the log shows `Mapped static region`. The directive logs
`2 compiler directives added` at startup.

On Windows, run the same commands from Git Bash, or put the same flags in a `cmd` script with
`^` line continuations.

## Packaging a native installer (jpackage)

The app ships as a self-contained native bundle built with `jlink` + `jpackage` (JDK 14+,
tested on JDK 25). First build the fat jar with `mvn -q clean package`.

### 1. Determine the required JDK modules

The minimal module set was derived with `jdeps` against the fat jar and verified at runtime:

```bash
jdeps --multi-release 25 --ignore-missing-deps \
      --print-module-deps xdm-app/target/xdm-app.jar
```

The app needs only these modules (everything else in the JDK is dropped):

| Module           | Why it's needed                                              |
|------------------|-------------------------------------------------------------|
| `java.base`      | Core runtime, networking, I/O (OkHttp uses raw sockets).    |
| `java.desktop`   | Swing/AWT UI, FlatLaf, system tray.                         |
| `java.prefs`     | Java Preferences (FlatLaf / config).                        |
| `java.xml`       | DASH `.mpd` manifest parsing (`DocumentBuilderFactory`).    |
| `java.logging`   | Soft dependency of OkHttp/okio.                             |
| `jdk.crypto.ec`  | Elliptic-curve TLS — required for HTTPS handshakes.\*       |
| `jdk.charsets`   | Non-Latin charsets for international URLs/filenames.        |

\* `jdk.crypto.ec` is **not** reported by `jdeps` (it's loaded as a security provider via
service binding), but without it HTTPS downloads fail with handshake errors. Keep it.

### 2. Build a trimmed runtime image with jlink

```bash
jlink \
  --add-modules java.base,java.desktop,java.prefs,java.xml,java.logging,jdk.crypto.ec,jdk.charsets \
  --strip-debug --no-header-files --no-man-pages --compress=zip-6 \
  --output xdm-app/target/runtime
```

This produces a ~53 MB self-contained runtime.

### 3. Package with jpackage

`jpackage` is **not** a cross-compiler — you must run both `jlink` (step 2) and `jpackage`
**on the target OS**. The runtime image and native launchers it produces are
platform-specific. The portable core of the command below is the same everywhere; only the
`--type` and the platform-specific shortcut/icon flags differ.

Stage the fat jar in a clean input directory (so jpackage doesn't bundle the rest of
`target/`) and define the shared GC options once:

```bash
rm -rf xdm-app/target/jpackage-in && mkdir -p xdm-app/target/jpackage-in
cp xdm-app/target/xdm-app.jar xdm-app/target/jpackage-in/

# Tuned GC/startup options baked into the app launcher (see Notes below).
JAVA_OPTS="-XX:+UseZGC -XX:MinMetaspaceFreeRatio=1 -XX:MaxMetaspaceFreeRatio=2 -XX:ZCollectionInterval=30 -XX:ZUncommitDelay=10 -XX:+ClassUnloading -XX:+ClassUnloadingWithConcurrentMark -XX:-AlwaysPreTouch -XX:-ZProactive -XX:-DisableExplicitGC -XX:TieredStopAtLevel=1 -XX:CICompilerCount=1 -Xms4m"
```

**macOS** (`.dmg`, the default on macOS — works out of the box):

```bash
jpackage \
  --name XDM \
  --app-version 0.0.1 \
  --vendor "XDM" \
  --input xdm-app/target/jpackage-in \
  --main-jar xdm-app.jar \
  --main-class xdm.app.AppMain \
  --runtime-image xdm-app/target/runtime \
  --dest xdm-app/target/dist \
  --icon packaging/xdm.icns \
  --java-options "$JAVA_OPTS"
```

**Windows** (`.exe` via [WiX Toolset](https://wixtoolset.org/) v3.x on `PATH`; use `--type msi` for MSI):

```bash
jpackage \
  --type exe \
  --name XDM \
  --app-version 0.0.1 \
  --vendor "XDM" \
  --input xdm-app/target/jpackage-in \
  --main-jar xdm-app.jar \
  --main-class xdm.app.AppMain \
  --runtime-image xdm-app/target/runtime \
  --dest xdm-app/target/dist \
  --icon packaging/xdm.ico \
  --win-menu --win-shortcut --win-dir-chooser \
  --java-options "$JAVA_OPTS"
```

**Linux — Debian/Ubuntu (`.deb`) or Fedora/RHEL (`.rpm`):** swap `--type deb` for
`--type rpm`. `.deb` needs `fakeroot` + `dpkg`; `.rpm` needs `rpm-build`.

```bash
jpackage \
  --type deb \
  --name xdm \
  --app-version 0.0.1 \
  --vendor "XDM" \
  --input xdm-app/target/jpackage-in \
  --main-jar xdm-app.jar \
  --main-class xdm.app.AppMain \
  --runtime-image xdm-app/target/runtime \
  --dest xdm-app/target/dist \
  --icon packaging/xdm.png \
  --linux-shortcut --linux-menu-group "Network" \
  --linux-package-name xdm \
  --java-options "$JAVA_OPTS"
```

**Linux — Arch Linux:** jpackage has no native Arch (`.pkg.tar.zst`) packager, so build a
self-contained **app-image** and wrap it in a `PKGBUILD`. First produce the app-image:

```bash
jpackage \
  --type app-image \
  --name xdm \
  --app-version 0.0.1 \
  --input xdm-app/target/jpackage-in \
  --main-jar xdm-app.jar \
  --main-class xdm.app.AppMain \
  --runtime-image xdm-app/target/runtime \
  --dest xdm-app/target/dist \
  --java-options "$JAVA_OPTS"
# → xdm-app/target/dist/xdm/  (contains bin/xdm launcher + lib/ + runtime)
```

Then a minimal `PKGBUILD` that installs it under `/opt` and exposes a launcher + desktop entry:

```bash
# PKGBUILD
pkgname=xdm
pkgver=0.0.1
pkgrel=1
pkgdesc="Xtreme Download Manager"
arch=('x86_64')
license=('custom')
options=(!strip)            # don't strip the bundled JVM
package() {
  # assumes the jpackage app-image dir 'xdm/' sits next to this PKGBUILD
  install -dm755 "$pkgdir/opt"
  cp -r "$srcdir/../xdm" "$pkgdir/opt/xdm"
  install -dm755 "$pkgdir/usr/bin"
  ln -s /opt/xdm/bin/xdm "$pkgdir/usr/bin/xdm"
  install -Dm644 "$srcdir/../packaging/xdm.png" "$pkgdir/usr/share/pixmaps/xdm.png"
  install -Dm644 /dev/stdin "$pkgdir/usr/share/applications/xdm.desktop" <<'EOF'
[Desktop Entry]
Type=Application
Name=XDM
Exec=/opt/xdm/bin/xdm
Icon=xdm
Categories=Network;
EOF
}
```

Build the package with `makepkg -f` (run from the directory holding the `PKGBUILD` and the
`xdm/` app-image), then install with `sudo pacman -U xdm-0.0.1-1-x86_64.pkg.tar.zst`. For a
shareable recipe, publish this as an AUR package that pulls the release jar in `prepare()`
instead of bundling the prebuilt app-image.

Notes:

- `--app-version` must be numeric (no `-SNAPSHOT`).
- `--icon` format is per-platform: `.icns` (macOS), `.ico` (Windows), `.png` (Linux). The
  `packaging/` paths above are placeholders — point them at your actual icon files or drop
  the flag to use the default icon.
- The `$JAVA_OPTS` string is the tuned GC/startup configuration baked into the app launcher:
  ZGC with aggressive metaspace reclamation, concurrent class unloading, minimal JIT
  tiering, and a tiny initial heap for fast startup and low idle footprint. Explicit GC is
  kept enabled (`-XX:-DisableExplicitGC`) so the app's periodic `System.gc()` works; pass
  `--no-gc` at runtime to suppress it.
