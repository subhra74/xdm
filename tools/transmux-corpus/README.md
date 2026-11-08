# Transmux corpus check

End-to-end check of the MPEG-TS → MP4 transmuxer (`xdm-core`, `TransmuxingMuxer`) against
ffmpeg: every variant is muxed by XDM, decoded by ffmpeg, and each decoded video and audio frame's
md5 is compared with ffmpeg's decode of the source segments. Not part of the Maven build; the unit
tests for the same layouts are in `xdm-core/src/test/java/xdm/core/TestTsPesLayouts.kt`.

Needs ffmpeg/ffprobe (libx264, libx265, libmp3lame), python3, Maven and JDK 25.

```bash
tools/transmux-corpus/gen.sh                 # ~3 GB corpus in $TMPDIR/xdm-transmux-corpus
tools/transmux-corpus/check.sh               # all variants
tools/transmux-corpus/check.sh base m2ts192  # some
```

Set `XDM_JAVA` to a JDK 25 `java` if the one on `PATH` is older.

## Variants

From ffmpeg's HLS muxer (720p noise, 12 s, I-frames over 500 KB):

| Variant | What it covers |
|---|---|
| `base`, `nobframes` | H.264 with 3 B-frames / none, AAC, 4 s segments |
| `short_seg`, `long_seg` | 1 s and 10 s segments |
| `m2ts192` | 192-byte M2TS packets; AAC as private data (0x06) with no descriptor |
| `pes_audio_1fr`, `pes_audio_big` | one AAC frame per PES / 16 KB audio PES |
| `intra_refresh`, `open_gop` | no IDR after the first frame / open GOP |
| `hevc8`, `hevc10` | HEVC Main and Main 10 |
| `ac3`, `eac3`, `mp3`, `aac_mono_44k` | other audio codecs, mono 44.1 kHz |
| `video_only`, `separate` | no audio / separate audio and video playlists |
| `disc_ts`, `disc_pids` | two encoders back to back: timestamps restart; also with other PMT/ES PIDs |

Re-packetized by `tsmod.py` (layouts other encoders produce but ffmpeg doesn't):

| Variant | What it covers |
|---|---|
| `mod_vsplit_nopts`, `mod_vsplit_nopts_hevc` | each picture split over 30 KB PES, continuations without PTS |
| `mod_vsplit_pts` | same, continuations repeat the PTS |
| `mod_vsplit_small` | 4 KB PES, cutting NAL units and start codes |
| `mod_asplit`, `mod_asplit_mono` | audio in 700-byte PES: ADTS frames straddle PES boundaries |
| `mod_pkt204` | 204-byte packets (16-byte FEC trailer) |
| `mod_pidshift` | segments 3+ move PMT and ES to new PIDs |

fMP4 (`-hls_segment_type fmp4`: `EXT-X-MAP` init + `.m4s`, one moof with a traf per track).
`check.py` builds the muxer's input from the playlist as the downloader does (init segments and
media segments in order, byte ranges cut into files of their own); the reference is ffmpeg's HLS
demuxer reading the playlist.

| Variant | What it covers |
|---|---|
| `f_base`, `f_nobframes`, `f_short_seg`, `f_long_seg` | as for TS |
| `f_ts1000` | video timescale 1000: 30 fps frames alternate 33/34 ticks |
| `f_negcts` | trun version 1, negative composition offsets |
| `f_hevc8`, `f_hevc10` | `hvc1` |
| `f_ac3`, `f_eac3`, `f_opus`, `f_mp3`, `f_flac` | audio sample entries copied verbatim |
| `f_byterange` | single file, `EXT-X-BYTERANGE` for the init and every segment |
| `f_video_only`, `f_separate` | no audio / separate audio and video playlists |
| `f_disc_same`, `f_disc_res` | second encoder with its own `EXT-X-MAP`, timestamps restarting; also at 960×540 (a second sample entry) |

Re-written by `fmp4mod.py`:

| Variant | What it covers |
|---|---|
| `f_mod_chunked` | 4 moof+mdat chunks per segment (low-latency CMAF) |
| `f_mod_notfdt` | no `tfdt`: each fragment continues the track's previous decode time |
| `f_mod_multitrun` | two truns per traf, the second without `data_offset` (data follows the first run's) |
| `f_mod_nobase` | no `default-base-is-moof`: the second traf's data starts where the first's ended |
| `f_disc_renum` | `f_disc_same` with track_IDs swapped in the second init and its fragments |

ffmpeg itself misreads `f_mod_multitrun` (it reads a trun without `data_offset` from the base
offset instead of after the previous run, ISO/IEC 14496-12 8.8.8.3), so `f_mod_*` variants are
compared with the decode of `f_base`, which holds the same samples.

Audio config changing across discontinuities (`cfg_*`: three 4 s encodes back to back, an ad at
another rate or layout). The muxer adds a sample entry per config; checked per part: exact packet
counts, A/V offset of each part against the source (container timestamps, 2 ms), the decoded
config sequence (ffmpeg's `ashowinfo`, before any resampling) and the PCM at the part's own rate.

| Variant | What it covers |
|---|---|
| `cfg_aac_rate`, `cfg_aac_channels` | AAC 48 → 44.1 → 48 kHz; stereo → mono → 5.1 |
| `cfg_ac3_rate`, `cfg_eac3_rate`, `cfg_mp3_rate` | 48 → 44.1 → 32 kHz (AC-3 at 32 kHz has its own frame sizes) |

Codecs beyond the HLS norm, as ffmpeg writes them in TS (`x_*`; broadcast/IPTV restreams):

| Variant | What it covers |
|---|---|
| `x_mpeg1_mp2`, `x_mpeg2_mp2` | MPEG-1/2 video (`mp4v`, sequence header as DecoderSpecificInfo), MP2 |
| `x_mpeg4p2_aac` | MPEG-4 Part 2 with the VOL repeated at keyframes |
| `x_h264_dts` | DTS on stream type 0x82 without a registration: identified from content |
| `x_h264_latm` | LATM AAC (0x11), written as plain AAC (ffmpeg `-c copy` can't put LATM in MP4) |
| `x_h264_ac3_bd`, `x_h264_eac3_bd`, `x_hevc_aac_m2ts` | Blu-ray (HDMV) types in M2TS: AC-3 0x81, E-AC-3 0x84 |
| `x_h264_mp3` | MP3 |
| `x_h264_pcmbd`, `x_h264_s302m`, `x_h264_truehd` | audio MP4 can't carry: left out, video kept |

Every variant without a discontinuity also has its packet timestamps (from the start of the file,
so A/V offsets count) compared with the source's, within 2 ms.

Stream properties ffmpeg's MP4 muxer carries over (`m_*`):

| Variant | What it covers |
|---|---|
| `m_anamorphic` | SAR 64:45 in the SPS: `pasp` and the display width in `tkhd` |
| `m_high_bt709` | High profile `avcC` extension fields, BT.709 colour description |
| `m_hdr10` | HEVC Main 10 PQ: `hvcC` bit depth, `colr` nclx BT.2020/PQ |
| `m_open_gop` | non-IDR I pictures with recovery-point SEIs as sync samples |
| `m_aac_pce` | 7.0 AAC with channel_config 0: the PCE moves into the AudioSpecificConfig |

Every variant without a discontinuity is also muxed by ffmpeg (`-c copy` to MP4, skipped where
ffmpeg can't) and compared box by box: sync sample count, `tkhd` width, `pasp`, `colr`, `avcC`,
`hvcC` chroma/bit depth/temporal layers, the esds DecoderSpecificInfo; separate audio/video
playlists must also be interleaved in the mdat.

## Reading the result

`PASS` means every decoded frame matches the source, in order, with no decode errors.
`PASS~` (MPEG-1/2 Layer II in MP4): ffmpeg decodes it with its floating-point MP3 decoder rather than
the fixed-point MP2 one it uses for the TS source, so frames differ by rounding (at most 256/32767);
ffmpeg's own `-c copy` output differs from the source the same way.
`PASS-` (Blu-ray LPCM, SMPTE 302M, TrueHD): MP4 can't carry the audio (ffmpeg `-c copy` fails
outright); it is left out with an error in the log and the video must match exactly.
`PASS*` (discontinuity sets only): video matches exactly; audio isn't bit-identical because the
decoder carries state from one stream into the next (a continuous ffmpeg decode of the source
differs the same way), so it is compared as PCM: same length, and at most 64/32767 apart outside
4096 samples after each boundary.
