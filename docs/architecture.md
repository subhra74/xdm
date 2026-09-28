# Architecture

## Modules
- **xdm-core** — download engine, no UI/Swing dependencies. HTTP client (OkHttp-based `HttpClientImpl`), downloader tasks,
  manifest parsers, muxer.
- **xdm-app** — Swing app + browser-integration HTTP server. Depends on xdm-core.
- **hls-muxer** — older standalone HLS/MPEG-TS muxer experiment (`org.mp4parser`). NOT wired in, listed in `.gitignore`;
  superseded by the in-tree transmuxer.

## Global wiring (`AppContext`)
Singleton service locator with `lateinit` refs: `db`, `app` (UI facade), `downloader` (`DownloadManager`), `config`,
`platform`, `queue`, `videoTracker`, `taskInfoDB`, `scheduler`. `AppMain.main()` builds them and calls
`AppContext.init(...)`, which loads config, starts the browser integration server, then runs the UI.

## Download flow
1. **Browser → app.** The extension (`browser-extension/`) POSTs JSON (`ExtensionMessage`) to `127.0.0.1:8597`
   (`xdm.integration`). `BrowserIntegration.handleRequest` dispatches `/download`, `/media`, `/vid`; every request also
   gets a `/sync` config + detected-video-list response (`ConfigDto`). Headers are filtered via a `blockedHeaders` set.
2. **Media detection.** `VideoHelper` classifies URLs as HTTP video, HLS (`.m3u8`) or DASH (`.mpd`) and registers them
   with `CapturedVideoTracker`.
3. **Task creation → `DownloadManager`** (`xdm.app`). Keeps `activeSessions` (id → `DownloaderTask`), a pending `queue`,
   enforces `maxParallelDownloads`, persists records. Builds `HttpDownloaderTask`, `HlsDownloaderTask` or
   `DashDownloaderTask`, injecting an `HttpClientImpl` (proxy config) and a `TransmuxingMuxer` for streaming types.
4. **Engine callbacks via `DownloadHost`** (extends `FileProvider`). `DownloadManager` implements it anonymously:
   `onDownloadProgress/Success/Failed/Paused/Init` update the DB record and UI; `getTempDir`/`commitOutputFile` pick
   temp vs. final paths and do the atomic rename. This is the core↔app boundary.

## Persistence
- **`AppDB`** (`DownloadsDB.kt`): in-memory `DbRecord` list (UI rows), persists active/paused/finished sets.
- **`TaskInfoDB`** (xdm-core): serializes immutable `*DownloadTaskInfo` to `task-<id>.info` with a hand-written
  `DataInput`/`DataOutput` format — field order in `get*Task`/`save*Task` must stay in lockstep.
- **`AtomicIO`**: transacted read/write (`.bak2` backups) for task info and `<id>.state` resume files. Prefer it for new on-disk state.
- **Deleting downloads.** `DownloadManager.deleteDownload` stops an active download and purges it once it reports
  paused/failed (`toDelete`); anything else is purged at once. The purge (`purgeFiles`/`deleteMetadata`) removes temp data,
  `task-<id>.info`, `<id>.state*`, HLS `<id>.keys` and the schedule entry. Clear uses `clearInactive()` (keeps running,
  assembling and queued). Any new per-download file must be added to `deleteMetadata`.

## Task info model
`HttpDownloadTaskInfo` and `StreamingDownloadTaskInfo` subtypes (`HlsDownloadTaskInfo`, `DashDownloadTaskInfo`) in
`xdm-core/.../downloaders/Models.kt` are the app→engine descriptors. `DownloadType` (`Http`, `Hls`, `Dash`, `Hds`, `Hss`,
`Torrent`) gates `when` branches in `DownloadManager` and `TaskInfoDB`; `Hds`, `Hss`, `Torrent` (and DASH in places) are `TODO()` stubs.

## Manifest parsing
Under `xdm-core/.../downloaders/web/streaming/manifest/{hls,dash}` (`HlsParser`, `MpdParser`, DASH template/period/
representation parsers). Segment downloaders: `.../streaming/downloader/{hls,dash}`.

## Muxing
Streaming downloads are merged into one MP4 by a `Muxer` (`media/muxer/Muxer.kt`). Default is `TransmuxingMuxer`
(`media/muxer/impl/`), a **pure-Kotlin transmuxer** that copies compressed streams into MP4 with no decoding and
**no ffmpeg**. `FFmpegMuxer` stays in the tree but is not wired in. Engine under `media/muxer/transmux/`:
- `ts/` — MPEG-TS demux (`TsDemuxer`, `PesAssembler` with 33-bit PTS/DTS rollover unwrap).
- `es/` — per-codec readers for TS (`H264Reader`, `H265Reader`, `AdtsReader`, `Ac3Reader`, `Mp3Reader`): split samples,
  extract decoder config, flag keyframes; never decode.
- `iso/Mp4Demuxer.kt` — fragmented MP4/CMAF and progressive MP4; copies samples and `stsd` entries verbatim (HEVC/AV1/Opus pass through).
- `mp4/Mp4Writer.kt` — progressive MP4 (`ftyp` + streaming 64-bit `mdat` + `moov`), hand-written boxes
  (`BoxBuf`/`CodecBoxes`). One sample per chunk; A/V sync via edit lists, B-frames via `ctts`.
- `sample/` (`Track`/`Sample`/`Codec`) and `io/` (byte/bit readers, NAL utils) are shared.

`TransmuxingMuxer` sniffs each segment list's container (TS vs MP4) and feeds all tracks into one `Mp4Writer`.
`TestTransmuxer` generates fixtures with `ffmpeg` if present (skips via JUnit `Assume` otherwise).
