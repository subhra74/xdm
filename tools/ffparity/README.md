# ffmpeg parity harness

Muxes each case with `TransmuxingMuxer` and with ffmpeg stream copy, then compares the two MP4s
**decision by decision**, not by decoding them with ffmpeg. ffmpeg's decoder hides exactly the
problems users see on other players: it skips frames it can't decode and reconfigures itself from
in-band parameter sets. So "decodes clean in ffmpeg" can't catch green frames.

Two references; both gate the run, the first is the main one (`XDM_PARITY_REFS`, default `concat,concatf`):

| Reference | Command | What it stands for |
|---|---|---|
| `concat` (main) | `ffmpeg -f concat -safe 0 -i <list> [-f concat ... -i <audio>] -vcodec copy -acodec copy -f mp4` | What users run by hand. Each segment opened on its own; parsers restart per file; each segment starts where the previous one ended by its probed duration. Its timing drifts per segment (allowed in `allow.json`, with the reason). |
| `concatf` | `ffmpeg -i concatf:<list> [-i concatf:<audio>] -map 0 [-map 1] -c copy -movflags +faststart -f mp4` | What the app runs with "Use ffmpeg for muxing" (`FFmpegMuxer.command`). Segments read as one byte stream: one demuxer and parser across segment ends. |

## Run

```bash
tools/ffparity/cases.sh                          # synthetic cases (needs ffmpeg with libx264, libx265)
tools/ffparity/parity.sh                         # every case against both references
tools/ffparity/parity.sh midgop_start res_change_ts
XDM_PARITY_REFS=concatf tools/ffparity/parity.sh # one reference
tools/ffparity/parity.sh --corpus base disc_ts   # transmux-corpus variants (tools/transmux-corpus/gen.sh)
tools/ffparity/parity.sh /path/to/capture        # a folder of real segments (see "Cases")
```

`XDM_JAVA` must be a JDK 25 `java`. Outputs, both MP4s per case and `results.json` go to
`XDM_PARITY_OUT` (default `$TMPDIR/xdm-parity-out`). The exit code is non-zero on any FAIL or ERROR.

## Cases

A case is a folder with:
- `video.txt`: segment files in playlist order, one per line (absolute, or relative to the folder);
- `audio.txt` (optional): a separate audio rendition;
- `disc` (optional, empty): the playlist has discontinuities (passed to the muxer as the manifest flag);
- `note.txt` (optional): what it exercises.

Real captures go in the same shape: copy the downloaded segments of a failing download out of its temp
folder, write `video.txt` (and `audio.txt`) in playlist order, add `disc` if the playlist had
`#EXT-X-DISCONTINUITY`.

## Checks

| Check id | What differs |
|---|---|
| `tracks` | number of video/audio tracks |
| `<track>.entry.*`, `<track>.config.*`, `<track>.tkhd.*` | sample entry type, size, channels/rate; decoder config (avcC/hvcC broken into profile, level, bit depth, chroma, each SPS/PPS/VPS; esds object type and DSI; dac3/dec3/dOps; pasp/colr); display size and matrix |
| `<track>.samples.extra/missing/changed.<leading/middle/trailing>` | samples only one side has (matched by a hash of their bytes, access unit delimiters and filler data left out: ffmpeg keeps them, we drop them) |
| `<track>.samples.keyframe.extra/missing` | sync-sample flags of samples both have |
| `<track>.samples.entry` | which sample entry a sample uses |
| `<track>.placement[.disc]` | from some point on a track is shown earlier or later (a section placed differently) |
| `<track>.samples.time[.disc]` | a few samples out of place inside a section |
| `<track>.samples.ffmpeg-overlap` | samples placed differently where ffmpeg's own file shows them on top of other frames and ours shows none on top of each other (left out of sections and sync) |
| `av.offset[.inputs]`, `av.sync` | A/V offset at the start, and in later sections (`SYNC_TOLERANCE`, 25 ms) |
| `decode.showall` | more decoder errors than ffmpeg's file with `-flags2 showall` (every frame output, as hardware decoders do) |
| `layout.moov` | `moov` before or after `mdat` |

Times are compared on the movie timeline (edit lists applied), as runs of equal shift in our decode
order. One shift for the whole file is just a different time origin.

## HLS format matrix (`matrix.sh`)

What each packager in the image can make, checked with our muxer both ways the user can pick
("Save as MP4 where possible" on and off). `docker/matrix.py` makes one case per (tool, format, layout):
TS together / separate, TS video + packed audio (Bento4, ID3 timestamp), TS video + bare ADTS (GPAC raw,
no ID3), fMP4 together / separate (Bento4 `mp4dash --hls`, also single-file byte ranges, cut into files
as the downloader fetches them), standalone MP4 parts (GStreamer `splitmuxsink`, ffmpeg segment muxer).
A combination a tool can't make is skipped and listed in `matrix-skipped.txt`. The sources are a 60 s
cut of `large.sh`'s 1080p stream and 20 s clips with codecs the remux can't carry (AV1+Opus, VP9+FLAC,
TrueHD, PCM, VC-2, JPEG 2000, SMPTE 302M). `matrix_check.py` prints per case and mode: OK/FAIL, the
container written and how (`REMUXED`, `TS_FALLBACK`, `AS_IS`), video/audio frames out against the
source lists, decode errors. `Mux` prints that as `RESULT <container> <kind>`; `XDM_KEEP_ORIGINAL=1`
turns "Save as MP4 where possible" off.

```
./matrix.sh                    # everything
./matrix.sh '_x_' '^b4_'       # only cases matching these regexes
```

Expected: every frame of the source lists in the output; codecs MP4 copies (AV1, VP9, FLAC, Opus,
TrueHD, PCM, J2K, VC-2 in fMP4/MP4 parts) remuxed; codecs our TS demuxer can't read (AV1, VP9, FLAC,
TrueHD, S302M, VC-2, J2K in TS) → `TS_FALLBACK` with MP4 on, `.ts` with it off; TS video + an audio
list that is neither TS nor packed audio (GPAC raw Ogg Opus / FLAC / PCM next to TS) → FAIL, which the
downloader turns into the segments kept as files.

## Real streams (`fetch_real.py`)

Public VOD test streams fetched into case folders (`real_<name>`: the lowest video variant and its default audio
rendition, first N segments, byte ranges cut into files, encrypted streams skipped), then checked like any other
case with `parity.sh` and `matrix_check.py`. Downloads go through `curl` (system certificates). Used so far:
Apple bipbop advanced (TS, fMP4) and 16x9, Mux test streams, Shaka angel-one, Unified Streaming tears-of-steel,
THEOplayer elephants-dream. `FETCH_AUDIO_ONLY=1` takes the lowest audio-only variant (packed audio).

```
python3 -I tools/ffparity/fetch_real.py "$XDM_PARITY_CASES" apple_bipbop16x9 \
  https://devstreaming-cdn.apple.com/videos/streaming/examples/bipbop_16x9/bipbop_16x9_variant.m3u8 20
```

## Verdicts and the allow list

Any difference fails unless `allow.json` covers it:
- `"status": "deliberate"`: we differ on purpose; the `reason` says why. Shown as `ok`.
- `"status": "open"`: a decision not taken yet. Makes the case WARN.

Entries match `check` (glob), optionally `cases` (globs) and `refs`. Each names its row in the
decision table, [private-docs/docs/transmuxer-ffmpeg-parity.md](../../private-docs/docs/transmuxer-ffmpeg-parity.md).
A new deliberate difference goes into both.

Verdicts: PASS, WARN (only open items), FAIL, NOREF (ffmpeg couldn't write MP4: nothing to compare
against), ERROR (the harness failed on the case).
