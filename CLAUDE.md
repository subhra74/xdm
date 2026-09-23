# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

XDM (Xtreme Download Manager) is a desktop download manager. It is a Maven multi-module
Kotlin/JVM project with a Swing UI (FlatLaf look-and-feel), driven by a companion browser
extension that captures download/media requests and forwards them to the running app over
a local HTTP server.

## Build & Run

This is a plain Maven project (no wrapper script; uses a system `mvn`). The reactor root
is `pom.xml`, with modules `xdm-core`, `xdm-app`, and `hls-muxer`.

```bash
mvn -q clean package              # build all modules; produces xdm-app/target/xdm-app.jar (fat jar)
mvn -q -pl xdm-app -am package    # build xdm-app and its dependencies only
mvn -q test -DskipTests=false                       # run all tests
mvn -q -pl xdm-core test -DskipTests=false          # test a single module
mvn -q -pl xdm-core test -DskipTests=false -Dtest=SomeTest          # run a single test class (Surefire)
mvn -q -pl xdm-core test -DskipTests=false -Dtest=SomeTest#method   # run a single test method
```

Run the app (main class is `xdm.app.AppMain`):

```bash
java -jar xdm-app/target/xdm-app.jar          # or run AppMain from the IDE
java -jar xdm-app/target/xdm-app.jar --no-gc  # disable the periodic System.gc() thread
```

Notes:
- Kotlin compiles via `kotlin-maven-plugin`; the default `maven-compiler-plugin`
  `default-compile`/`default-testCompile` executions are deliberately disabled and
  re-bound so Kotlin and Java sources interop. Source roots are `src/main/java` even
  though they hold Kotlin (`xdm-core`/`xdm-app`); `hls-muxer` uses `src/main/kotlin`.
- JVM target differs by module: `xdm-app` targets JDK 11, `xdm-core` and `hls-muxer`
  target JDK 8.
- `kotlinx-serialization` is enabled as a Kotlin compiler plugin for JSON models.

## Runtime layout

At startup the app creates `~/.xdm-app/` (and `~/.xdm-app/tmp/`) as its config/state
directory. Config, the downloads DB, per-task `task-<id>.info` files, and `<id>.state`
resume files all live there. (Note: `Constants.kt` also defines a legacy `.xdman` dir name
that is not what `AppMain` actually uses.)

## Architecture

### Modules
- **xdm-core** — download engine, with no UI/Swing dependencies. Owns the HTTP client
  (OkHttp-based `HttpClientImpl`), the downloader tasks, manifest parsers, and the muxer.
- **xdm-app** — Swing desktop application + browser-integration HTTP server. Depends on
  xdm-core.
- **hls-muxer** — an older standalone pure-Kotlin HLS/MPEG-TS muxer experiment (depends on
  `org.mp4parser`). NOT wired in and listed in `.gitignore`; superseded by the in-tree
  transmuxer under `xdm-core/.../media/muxer/transmux/` (see Muxing below).

### Global wiring (`AppContext`)
`AppContext` is a singleton service locator holding `lateinit` references to every major
service: `db` (downloads DB), `app` (UI facade), `downloader` (`DownloadManager`), `config`,
`platform`, `queue`, `videoTracker`, `taskInfoDB`, `scheduler`. `AppMain.main()` constructs
all of these and calls `AppContext.init(...)`, which loads config, starts the browser
integration server, then runs the UI. Most code reaches services through `AppContext.*`.

### Download flow
1. **Browser → app.** The browser extension (`browser-extension/`) POSTs JSON
   (`ExtensionMessage`) to a local HTTP server on `127.0.0.1:8597` (`xdm.integration`).
   `BrowserIntegration.handleRequest` dispatches `/download`, `/media`, `/vid`, and every
   request also gets a `/sync` config+detected-video-list response (`ConfigDto`). Request
   headers are filtered through a `blockedHeaders` set before use.
2. **Media detection.** `VideoHelper` classifies captured media URLs as plain HTTP video,
   HLS (`.m3u8`), or DASH (`.mpd`) and registers them with `CapturedVideoTracker`, which
   feeds the extension's detected-video list.
3. **Task creation → `DownloadManager`** (`xdm.app`). This is the orchestrator. It maintains
   `activeSessions` (id → `DownloaderTask`), a pending `queue`, enforces
   `maxParallelDownloads`, and persists records. It builds the concrete task per
   `DownloadType`: `HttpDownloaderTask`, `HlsDownloaderTask`, or `DashDownloaderTask`
   (all in xdm-core), injecting an `HttpClientImpl` (with proxy config) and a `TransmuxingMuxer`
   for streaming types.
4. **Engine callbacks via `DownloadHost`.** Every downloader task talks back through the
   `DownloadHost` interface (extends `FileProvider`). `DownloadManager` implements it as an
   anonymous object: `onDownloadProgress/Success/Failed/Paused/Init` update the DB record
   and push UI updates; `getTempDir`/`commitOutputFile` decide temp vs. final paths and do
   the atomic rename on completion. This interface is the core↔app boundary — the engine
   never imports app/UI types.

### Persistence
- **`AppDB`** (`DownloadsDB.kt`) holds the in-memory list of `DbRecord` (UI rows: status,
  progress, sizes) and persists active/paused/finished record sets.
- **`TaskInfoDB`** (xdm-core) serializes immutable task parameters (`*DownloadTaskInfo`) to
  `task-<id>.info` using a hand-written `DataInput`/`DataOutput` binary format — field order
  in `get*Task`/`save*Task` must stay in lockstep.
- **`AtomicIO`** provides transacted read/write (with `.bak2` backups) used for both task
  info and `<id>.state` resume files. Prefer it for any new on-disk state.
- **Deleting downloads.** `DownloadManager.deleteDownload` stops an active download and purges it
  once it reports paused/failed (`toDelete`); anything else is purged at once. The purge
  (`purgeFiles`/`deleteMetadata`) removes temp data, `task-<id>.info`, `<id>.state*`, HLS
  `<id>.keys` and the schedule entry. The Clear button uses `clearInactive()`, which keeps running,
  assembling and queued downloads. Any new per-download file must be added to `deleteMetadata`.

### Task info model
`HttpDownloadTaskInfo` and the `StreamingDownloadTaskInfo` subtypes (`HlsDownloadTaskInfo`,
`DashDownloadTaskInfo`) in `xdm-core/.../downloaders/Models.kt` are the canonical descriptors
passed from app to engine. `DownloadType` (`Http`, `Hls`, `Dash`, `Hds`, `Hss`, `Torrent`)
gates the `when` branches throughout `DownloadManager` and `TaskInfoDB` — several branches
(`Hds`, `Hss`, `Torrent`, and DASH in places) are `TODO()`/unimplemented stubs.

### Manifest parsing
HLS and DASH manifest parsing live under
`xdm-core/.../downloaders/web/streaming/manifest/{hls,dash}` (e.g. `HlsParser`, `MpdParser`
plus DASH template/period/representation parsers). The corresponding segment downloaders are
under `.../streaming/downloader/{hls,dash}`.

### Muxing
After a streaming download finishes, its segments are merged into a single MP4 by a muxer
implementing the `Muxer` interface (`media/muxer/Muxer.kt`). The default is `TransmuxingMuxer`
(`media/muxer/impl/`), a **pure-Kotlin transmuxer** — it copies the compressed elementary
streams into MP4 with no decoding, and **does not call ffmpeg**. `FFmpegMuxer` (which shells out
to an external `ffmpeg`) is kept in the tree but is no longer wired into the download path.

The engine lives under `media/muxer/transmux/`:
- `ts/` — MPEG-TS demux (`TsDemuxer`: 188/192/204-byte packets, PAT/PMT, `PesAssembler` for
  PES reassembly + PTS/DTS with 33-bit rollover unwrap).
- `es/` — per-codec elementary-stream readers for the TS path (`H264Reader`, `H265Reader`,
  `AdtsReader` for AAC, `Ac3Reader` for AC-3/E-AC-3, `Mp3Reader`). Each parses just enough to
  split samples, extract the decoder config (SPS/PPS→avcC, etc.) and flag keyframes — never decode.
- `iso/Mp4Demuxer.kt` — ISO-BMFF demux for the non-TS path: fragmented MP4/CMAF (`moof`/`trun`)
  **and** progressive single-file MP4 (`stbl` tables). Copies sample bytes and the `stsd` sample
  entry verbatim, so any MP4 codec (incl. HEVC/AV1/Opus) passes through.
- `mp4/Mp4Writer.kt` — writes a progressive MP4 (`ftyp` + streaming 64-bit `mdat` + `moov`), with
  hand-written boxes (`BoxBuf`/`CodecBoxes`). Each sample is its own chunk so audio/video
  interleave is order-independent; A/V sync via edit lists, B-frames via `ctts`.
- `sample/` (`Track`/`Sample`/`Codec`) and `io/` (byte/bit readers, NAL utilities) are shared.

`TransmuxingMuxer` sniffs each segment list's container (TS vs MP4) and routes to the right
demuxer, feeding all tracks into one `Mp4Writer`. `TestTransmuxer` exercises it end-to-end by
generating fixtures with `ffmpeg` if present (skips via JUnit `Assume` otherwise).

### UI
Swing UI is under `xdm-app/.../ui`: `screens/` (windows/dialogs, `AppWindow` is the main
window) and `components/` (the downloads list `MainListView`/`MainListViewModel`, toolbar,
filters, progress widgets). `AppInstance` (implements `IAppInstance`) is the facade the rest
of the app calls to show/update windows; it marshals everything onto the Swing EDT
(`SwingUtilities.invokeLater`). FlatLaf (`FlatMacDarkLaf`) and a system tray are set up in
`AppMain`/`AppInstance`. UI strings are localized via `I8N` from `resources/lang`.
Icons are glyphs from the bundled Remix Icon font (`resources/fonts/remixicon.ttf`), drawn by
`FontIcon` via `createIcon(RemixIcon.X, size, color)` (`utils/RemixIcon.kt`); to add one, add its
codepoint from the same release's `remixicon.css` to the `RemixIcon` enum. The logo and macOS tray
icon are PNGs under `resources/images/` (`logoImage`/`logoIcon`/`trayMacImage` in `UiHelper.kt`).

## Conventions
- Reach shared services through `AppContext`, not by passing them around manually.
- Keep the core↔app boundary clean: xdm-core must not depend on Swing/app types — communicate
  via `DownloadHost`/`FileProvider` callbacks and the task-info models.
- When changing any `*DownloadTaskInfo` field that is persisted, update the matching
  read/write pair in `TaskInfoDB` (and any `.state` serialization) together, preserving order.
- All UI mutation must happen on the EDT; follow the existing `runOnUIThread`/`invokeLater`
  pattern in `AppInstance`. Every public `IAppInstance` method may be called from engine or
  server threads, so it must marshal to the EDT itself. Pass download ids to the EDT and resolve
  the row index there (`db.indexById`), never an index computed on the calling thread.
- `AppDB` accessors are synchronized on the `AppDB` instance. `DownloadManager`'s lock order is
  `queue` → `appDB`; never take the `queue` lock while holding `appDB`.
