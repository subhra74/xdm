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
- Tiered compilation with this directive is required: XDM uses the JDK's TLS, which needs C2 for
  its crypto methods. Don't limit the JIT to C1 (`TieredStopAtLevel`) or drop the directive.
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

## Packaging a native installer

Use the build scripts, which bundle the JVM flags, the compiler directive and the AppCDS
archive described above; don't hand-roll `jlink`/`jpackage` commands.

```bash
packaging/build-bundle.sh -t app-image     # macOS/Linux; see --help for dmg, deb, rpm, ...
```

On Windows use `packaging\build-bundle.ps1`. Installers, targets and the XDM 8 migration are
covered in [PACKAGING.md](../PACKAGING.md).
