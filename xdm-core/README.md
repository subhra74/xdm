# xdm-core — XDM download engine

The download engine behind XDM (Xtreme Download Manager): HTTP client, downloader tasks,
streaming manifest parsers and the transmuxer. **No UI or Swing dependencies** — the Swing
application lives in [`xdm-app`](../xdm-app/README.md).

Targets JDK 8 (`xdm-app` targets JDK 11).

## Build & test

Plain Maven (system `mvn`, no wrapper), run from the **repository root**:

```bash
mvn -q -pl xdm-core -am package                                     # build this module
mvn -q -pl xdm-core test -DskipTests=false                          # run its tests
mvn -q -pl xdm-core test -DskipTests=false -Dtest=SomeTest          # a single test class
mvn -q -pl xdm-core test -DskipTests=false -Dtest=SomeTest#method   # a single test method
```

Kotlin compiles via `kotlin-maven-plugin`; the source root is `src/main/java` even though it
holds Kotlin. `kotlinx-serialization` is enabled as a compiler plugin for the JSON models.

## The core↔app boundary

The engine never imports app or UI types. Everything flows through two seams:

- **`DownloadHost`** (extends `FileProvider`) — the callback interface every downloader task
  talks back through: `onDownloadProgress/Success/Failed/Paused/Init`, plus `getTempDir` and
  `commitOutputFile` so the host decides temp vs. final paths and does the atomic rename on
  completion. `DownloadManager` in `xdm-app` implements it.
- **Task info models** (`downloaders/Models.kt`) — `HttpDownloadTaskInfo` and the
  `StreamingDownloadTaskInfo` subtypes (`HlsDownloadTaskInfo`, `DashDownloadTaskInfo`) are
  the immutable descriptors passed from app to engine. `DownloadType` (`Http`, `Hls`, `Dash`,
  `Hds`, `Hss`, `Torrent`) gates the `when` branches; several are still `TODO()` stubs.

## Downloaders

`HttpDownloaderTask`, `HlsDownloaderTask` and `DashDownloaderTask`, each injected with an
OkHttp-backed `HttpClientImpl` (proxy-aware) and, for the streaming types, a `Muxer`.

Manifest parsing lives under `downloaders/web/streaming/manifest/{hls,dash}` (`HlsParser`,
`MpdParser` plus the DASH template/period/representation parsers); the matching segment
downloaders are under `.../streaming/downloader/{hls,dash}`.

## Muxing

When a streaming download finishes, its segments are merged into a single MP4 by a `Muxer`
(`media/muxer/Muxer.kt`). The default is **`TransmuxingMuxer`** — a pure-Kotlin transmuxer
that copies the compressed elementary streams into MP4 with no decoding and **does not call
ffmpeg**. `FFmpegMuxer` is kept in the tree but is no longer wired into the download path.

The engine lives under `media/muxer/transmux/`:

- `ts/` — MPEG-TS demux (`TsDemuxer`: 188/192/204-byte packets, PAT/PMT, `PesAssembler` for
  PES reassembly + PTS/DTS with 33-bit rollover unwrap).
- `es/` — per-codec elementary-stream readers for the TS path (`H264Reader`, `H265Reader`,
  `AdtsReader` for AAC, `Ac3Reader` for AC-3/E-AC-3, `Mp3Reader`). Each parses just enough to
  split samples, extract the decoder config (SPS/PPS→avcC, etc.) and flag keyframes — never
  decode.
- `iso/Mp4Demuxer.kt` — ISO-BMFF demux for the non-TS path: fragmented MP4/CMAF (`moof`/
  `trun`) **and** progressive single-file MP4 (`stbl` tables). Copies sample bytes and the
  `stsd` sample entry verbatim, so any MP4 codec (incl. HEVC/AV1/Opus) passes through.
- `mp4/Mp4Writer.kt` — writes a progressive MP4 (`ftyp` + streaming 64-bit `mdat` + `moov`)
  with hand-written boxes. Each sample is its own chunk so A/V interleave is order-
  independent; A/V sync via edit lists, B-frames via `ctts`.
- `sample/` (`Track`/`Sample`/`Codec`) and `io/` (byte/bit readers, NAL utilities) are shared.

`TransmuxingMuxer` sniffs each segment list's container (TS vs MP4) and routes to the right
demuxer, feeding all tracks into one `Mp4Writer`. `TestTransmuxer` exercises it end-to-end,
generating fixtures with `ffmpeg` if present (skipping via JUnit `Assume` otherwise).

## Persistence

- **`TaskInfoDB`** serializes the task-info models to `task-<id>.info` with a hand-written
  `DataInput`/`DataOutput` binary format — field order in `get*Task`/`save*Task` must stay in
  lockstep. Changing a persisted `*DownloadTaskInfo` field means updating the matching
  read/write pair together.
- **`AtomicIO`** provides transacted read/write (with `.bak2` backups), used for task info and
  the `<id>.state` resume files. Prefer it for any new on-disk state.
