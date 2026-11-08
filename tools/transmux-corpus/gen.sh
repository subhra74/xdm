#!/bin/bash
# Generates the HLS (MPEG-TS) test corpus for the transmuxer: ffmpeg variants of encoder/HLS
# settings, then re-packetized copies (tsmod.py) with PES layouts ffmpeg never writes.
# Each variant: <corpus>/<name>/index.m3u8 + seg*.ts (separate renditions: v/ and a/;
# discontinuity sets: p1/ and p2/). About 3 GB: the noisy 720p picture gives 500+ KB I-frames,
# so frames span many PES packets.
#
#   XDM_CORPUS_DIR   where to write it (default: $TMPDIR/xdm-transmux-corpus)
set -e
S=$(cd "$(dirname "$0")" && pwd)
C=${XDM_CORPUS_DIR:-${TMPDIR:-/tmp}/xdm-transmux-corpus}
rm -rf "$C"; mkdir -p "$C"
DUR=12
# Detailed, moving picture: large I-frames (well over 64 KB) so PES splitting matters.
VSRC="-f lavfi -i testsrc2=s=1280x720:r=30:d=$DUR"
VF="-vf noise=alls=25:allf=t+u"
# Chirp: dropped or duplicated audio frames show up in the decoded hash.
ASRC="-f lavfi -i aevalsrc=sin(2*PI*(200+100*t)*t)|sin(2*PI*(300+50*t)*t):s=48000:d=$DUR"
X264="-c:v libx264 -preset ultrafast -crf 16 -g 60 -keyint_min 60 -sc_threshold 0"
Q="-hide_banner -loglevel error -y"

hls() { # name, extra encode args..., then hls args after --
  local name=$1; shift
  mkdir -p "$C/$name"
  ffmpeg $Q $VSRC $ASRC $VF "$@" -f hls -hls_playlist_type vod -hls_segment_filename "$C/$name/seg%03d.ts" "$C/$name/index.m3u8"
  echo "made $name"
}

hls base          $X264 -bf 3 -c:a aac -b:a 128k -hls_time 4
hls nobframes     $X264 -bf 0 -c:a aac -b:a 128k -hls_time 4
hls short_seg     $X264 -bf 3 -c:a aac -b:a 128k -hls_time 1
hls long_seg      $X264 -bf 3 -c:a aac -b:a 128k -hls_time 10
hls m2ts192       $X264 -bf 3 -c:a aac -b:a 128k -hls_time 4 -hls_segment_options mpegts_m2ts_mode=1
hls pes_audio_1fr $X264 -bf 3 -c:a aac -b:a 128k -hls_time 4 -hls_segment_options pes_payload_size=0
hls pes_audio_big $X264 -bf 3 -c:a aac -b:a 128k -hls_time 4 -hls_segment_options pes_payload_size=16000
hls intra_refresh -c:v libx264 -preset ultrafast -crf 16 -bf 0 -x264-params intra-refresh=1:keyint=60 -c:a aac -b:a 128k -hls_time 4
hls open_gop      $X264 -bf 3 -x264-params open-gop=1 -c:a aac -b:a 128k -hls_time 4
hls hevc8         -c:v libx265 -preset ultrafast -crf 20 -g 60 -x265-params log-level=error -pix_fmt yuv420p -c:a aac -hls_time 4
hls hevc10        -c:v libx265 -preset ultrafast -crf 20 -g 60 -x265-params log-level=error -pix_fmt yuv420p10le -c:a aac -hls_time 4
hls ac3           $X264 -bf 3 -c:a ac3 -b:a 192k -hls_time 4
hls eac3          $X264 -bf 3 -c:a eac3 -b:a 192k -hls_time 4
hls mp3           $X264 -bf 3 -c:a libmp3lame -b:a 128k -hls_time 4
hls aac_mono_44k  $X264 -bf 3 -c:a aac -ac 1 -ar 44100 -hls_time 4
hls video_only    $X264 -bf 3 -an -hls_time 4

# Separate audio / video renditions (two playlists).
mkdir -p "$C/separate/v" "$C/separate/a"
ffmpeg $Q $VSRC $ASRC $VF -map 0:v $X264 -bf 3 -f hls -hls_time 4 -hls_playlist_type vod \
  -hls_segment_filename "$C/separate/v/seg%03d.ts" "$C/separate/v/index.m3u8"
ffmpeg $Q $ASRC -map 0:a -c:a aac -b:a 128k -f hls -hls_time 4 -hls_playlist_type vod \
  -hls_segment_filename "$C/separate/a/seg%03d.ts" "$C/separate/a/index.m3u8"
echo "made separate"

# Discontinuity: two encodes, timestamps restart, second half uses different PIDs (ad-break style).
mkdir -p "$C/disc_pids/p1" "$C/disc_pids/p2"
ffmpeg $Q -f lavfi -i testsrc2=s=1280x720:r=30:d=6 -f lavfi -i "sine=f=440:d=6" $VF $X264 -bf 3 -c:a aac \
  -f hls -hls_time 3 -hls_playlist_type vod -hls_segment_filename "$C/disc_pids/p1/s%03d.ts" "$C/disc_pids/p1/i.m3u8"
ffmpeg $Q -f lavfi -i smptebars=s=1280x720:r=30:d=6 -f lavfi -i "sine=f=880:d=6" $VF $X264 -bf 3 -c:a aac \
  -f hls -hls_time 3 -hls_playlist_type vod -hls_segment_options mpegts_start_pid=0x200:mpegts_pmt_start_pid=0x1100 \
  -hls_segment_filename "$C/disc_pids/p2/s%03d.ts" "$C/disc_pids/p2/i.m3u8"
# Same, but same PIDs (pure timestamp reset).
mkdir -p "$C/disc_ts/p1" "$C/disc_ts/p2"
cp "$C/disc_pids/p1/"*.ts "$C/disc_ts/p1/"
ffmpeg $Q -f lavfi -i smptebars=s=1280x720:r=30:d=6 -f lavfi -i "sine=f=880:d=6" $VF $X264 -bf 3 -c:a aac \
  -f hls -hls_time 3 -hls_playlist_type vod -hls_segment_filename "$C/disc_ts/p2/s%03d.ts" "$C/disc_ts/p2/i.m3u8"
echo "made discontinuity sets"

# ---- re-packetized variants (see tsmod.py) ----
mk() { # mode, source variant
  local mode=$1 srcv=$2 dst=$C/mod_${1}${3:-}
  rm -rf "$dst"; mkdir -p "$dst"
  local i=0
  for f in "$C/$srcv"/seg*.ts; do
    python3 -I "$S/tsmod.py" "$mode" "$f" "$dst/$(basename "$f")" $i
    i=$((i+1))
  done
  cp "$C/$srcv/index.m3u8" "$dst/"
  echo "made mod_${mode}${3:-} from $srcv"
}
mk vsplit_nopts base
mk vsplit_pts base
mk vsplit_small base
mk asplit base
mk asplit aac_mono_44k _mono
mk pkt204 base
mk pidshift short_seg
mk vsplit_nopts hevc8 _hevc

# ---- fMP4 (CMAF-style) HLS: EXT-X-MAP init + .m4s segments ----
fmp4() { # name, encode/hls args...
  local name=$1; shift
  mkdir -p "$C/$name"
  ffmpeg $Q $VSRC $ASRC $VF "$@" -f hls -hls_playlist_type vod -hls_segment_type fmp4 \
    -hls_fmp4_init_filename init.mp4 -hls_segment_filename "$C/$name/seg%03d.m4s" "$C/$name/index.m3u8"
  echo "made $name"
}
fmp4 f_base        $X264 -bf 3 -c:a aac -b:a 128k -hls_time 4
fmp4 f_nobframes   $X264 -bf 0 -c:a aac -b:a 128k -hls_time 4
fmp4 f_short_seg   $X264 -bf 3 -c:a aac -b:a 128k -hls_time 1
fmp4 f_long_seg    $X264 -bf 3 -c:a aac -b:a 128k -hls_time 10
fmp4 f_ts1000      $X264 -bf 3 -c:a aac -b:a 128k -hls_time 4 -video_track_timescale 1000
fmp4 f_negcts      $X264 -bf 3 -c:a aac -b:a 128k -hls_time 4 -hls_segment_options movflags=+negative_cts_offsets
fmp4 f_hevc8       -c:v libx265 -preset ultrafast -crf 20 -g 60 -x265-params log-level=error -pix_fmt yuv420p -tag:v hvc1 -c:a aac -hls_time 4
fmp4 f_hevc10      -c:v libx265 -preset ultrafast -crf 20 -g 60 -x265-params log-level=error -pix_fmt yuv420p10le -tag:v hvc1 -c:a aac -hls_time 4
fmp4 f_ac3         $X264 -bf 3 -c:a ac3 -b:a 192k -hls_time 4
fmp4 f_eac3        $X264 -bf 3 -c:a eac3 -b:a 192k -hls_time 4
fmp4 f_opus        $X264 -bf 3 -c:a libopus -b:a 128k -hls_time 4
fmp4 f_mp3         $X264 -bf 3 -c:a libmp3lame -b:a 128k -hls_time 4
fmp4 f_flac        $X264 -bf 3 -c:a flac -strict -2 -hls_time 4
fmp4 f_byterange   $X264 -bf 3 -c:a aac -b:a 128k -hls_time 4 -hls_flags single_file
fmp4 f_video_only  $X264 -bf 3 -an -hls_time 4

mkdir -p "$C/f_separate/v" "$C/f_separate/a"
ffmpeg $Q $VSRC $ASRC $VF -map 0:v $X264 -bf 3 -f hls -hls_time 4 -hls_playlist_type vod -hls_segment_type fmp4 \
  -hls_fmp4_init_filename init.mp4 -hls_segment_filename "$C/f_separate/v/seg%03d.m4s" "$C/f_separate/v/index.m3u8"
ffmpeg $Q $ASRC -map 0:a -c:a aac -b:a 128k -f hls -hls_time 4 -hls_playlist_type vod -hls_segment_type fmp4 \
  -hls_fmp4_init_filename init.mp4 -hls_segment_filename "$C/f_separate/a/seg%03d.m4s" "$C/f_separate/a/index.m3u8"
echo "made f_separate"

# Discontinuity with a new EXT-X-MAP: second encoder at another resolution, timestamps restarting.
fdisc() { # dir, source, size, extra
  mkdir -p "$1"
  ffmpeg $Q -f lavfi -i "$2=s=$3:r=30:d=6" -f lavfi -i "sine=f=440:d=6" $VF $X264 -bf 3 -c:a aac \
    -f hls -hls_time 3 -hls_playlist_type vod -hls_segment_type fmp4 -hls_fmp4_init_filename init.mp4 \
    -hls_segment_filename "$1/s%03d.m4s" "$1/index.m3u8"
}
fdisc "$C/f_disc_res/p1" testsrc2 1280x720
fdisc "$C/f_disc_res/p2" smptebars 960x540
fdisc "$C/f_disc_same/p1" testsrc2 1280x720
fdisc "$C/f_disc_same/p2" smptebars 1280x720
echo "made fMP4 discontinuity sets"

# ---- re-written fMP4 variants (see fmp4mod.py) ----
fmk() { # mode, source variant, destination
  local mode=$1 src=$C/$2 dst=$C/$3
  rm -rf "$dst"; mkdir -p "$dst"
  cp "$src/index.m3u8" "$dst/"
  if [ "$mode" = renumber ]; then python3 -I "$S/fmp4mod.py" renumber "$src/init.mp4" "$dst/init.mp4"
  else cp "$src/init.mp4" "$dst/"; fi
  for f in "$src"/*.m4s; do python3 -I "$S/fmp4mod.py" "$mode" "$f" "$dst/$(basename "$f")" "$src/init.mp4"; done
  echo "made $3"
}
fmk multitrun f_base f_mod_multitrun
fmk nobase f_base f_mod_nobase
fmk chunked f_base f_mod_chunked
fmk notfdt f_base f_mod_notfdt
mkdir -p "$C/f_disc_renum"; rm -rf "$C/f_disc_renum/p1"; cp -R "$C/f_disc_same/p1" "$C/f_disc_renum/p1"
fmk renumber f_disc_same/p2 f_disc_renum/p2

# ---- audio config changing across discontinuities (an ad at another rate/layout) ----
# cfg_<name>/p1, p2, p3: three 4 s encodes back to back, timestamps restarting at each.
cfgpart() { # dir, audio source, audio codec args
  mkdir -p "$1"
  ffmpeg $Q -f lavfi -i testsrc2=s=640x360:r=30:d=4 -f lavfi -i "$2" $X264 -bf 3 $3 \
    -f hls -hls_time 2 -hls_playlist_type vod -hls_segment_filename "$1/s%03d.ts" "$1/index.m3u8"
}
cfgset() { # name, codec args, source1, source2, source3
  cfgpart "$C/$1/p1" "$3" "$2"; cfgpart "$C/$1/p2" "$4" "$2"; cfgpart "$C/$1/p3" "$5" "$2"; echo "made $1"
}
S48="sine=f=440:r=48000:d=4"; S44="sine=f=660:r=44100:d=4"; S32="sine=f=550:r=32000:d=4"
cfgset cfg_aac_rate     "-c:a aac -ac 2" "$S48" "$S44" "$S48"
cfgset cfg_aac_channels "-c:a aac" "$S48" "aevalsrc=sin(2*PI*660*t):s=48000:d=4" "aevalsrc=sin(2*PI*440*t)|sin(2*PI*550*t)|sin(2*PI*660*t)|sin(2*PI*770*t)|sin(2*PI*880*t)|sin(2*PI*990*t):s=48000:d=4:c=5.1"
cfgset cfg_ac3_rate     "-c:a ac3" "$S48" "$S44" "$S32"
cfgset cfg_eac3_rate    "-c:a eac3" "$S48" "$S44" "$S48"
cfgset cfg_mp3_rate     "-c:a libmp3lame" "$S48" "$S44" "$S32"

# ---- codecs beyond the HLS norm (broadcast/IPTV restreams, Blu-ray types), as ffmpeg writes them in TS ----
XV="-f lavfi -i testsrc2=s=640x360:r=25:d=6"; XA="-f lavfi -i aevalsrc=sin(2*PI*(300+80*t)*t)|sin(2*PI*(450+40*t)*t):s=48000:d=6"
H="-c:v libx264 -preset ultrafast -g 25 -bf 2"
xmk() { # name, args (encode+mpegts options)
  local n=$1; shift; mkdir -p "$C/$n"
  if ffmpeg $Q $XV $XA "$@" -f hls -hls_time 2 -hls_playlist_type vod -hls_segment_filename "$C/$n/s%03d.ts" "$C/$n/index.m3u8" 2>$C/$n.err; then
    echo "made $n: $(ffprobe -v error -show_entries stream=codec_name -of csv=p=0 $C/$n/s000.ts | tr '\n' ' ')"
  else echo "SKIP $n: $(head -c 150 $C/$n.err)"; rm -rf $C/$n; fi
}
xmk x_mpeg2_mp2      -c:v mpeg2video -q:v 3 -g 25 -bf 2 -c:a mp2
xmk x_mpeg1_mp2      -c:v mpeg1video -q:v 3 -g 25 -c:a mp2
xmk x_mpeg4p2_aac    -c:v mpeg4 -q:v 3 -g 25 -bf 2 -bsf:v dump_extra=freq=keyframe -c:a aac
xmk x_h264_dts       $H -c:a dca -strict -2
xmk x_h264_truehd    $H -c:a truehd -strict -2
xmk x_h264_s302m     $H -c:a s302m -strict -2 -ar 48000
xmk x_h264_pcmbd     $H -c:a pcm_bluray -hls_segment_options mpegts_m2ts_mode=1
xmk x_h264_ac3_bd    $H -c:a ac3 -hls_segment_options mpegts_m2ts_mode=1
xmk x_h264_eac3_bd   $H -c:a eac3 -hls_segment_options mpegts_m2ts_mode=1
xmk x_hevc_aac_m2ts  -c:v libx265 -preset ultrafast -x265-params log-level=error -c:a aac -hls_segment_options mpegts_m2ts_mode=1
xmk x_h264_mp3       $H -c:a libmp3lame
# LATM AAC (stream type 0x11): the HLS muxer can't set it, so one plain TS file.
mkdir -p "$C/x_h264_latm"
ffmpeg $Q $XV $XA $H -c:a aac -f mpegts -mpegts_flags +latm "$C/x_h264_latm/s000.ts" && echo "made x_h264_latm"

# ---- stream properties ffmpeg's MP4 muxer carries over (m_*): checked box by box against ffmpeg -c copy ----
mmk() { # name, args (inputs + encode)
  local n=$1; shift; mkdir -p "$C/$n"
  ffmpeg $Q "$@" -f hls -hls_time 2 -hls_playlist_type vod -hls_segment_filename "$C/$n/s%03d.ts" "$C/$n/index.m3u8" && echo "made $n"
}
MV="-f lavfi -i testsrc2=s=720x576:r=25:d=6"; MA="-f lavfi -i sine=f=440:r=48000:d=6"
mmk m_anamorphic $MV $MA -c:v libx264 -preset veryfast -g 50 -bf 2 -vf setsar=64/45 -c:a aac
mmk m_high_bt709 $MV $MA -c:v libx264 -profile:v high -preset veryfast -g 50 -bf 2 \
  -color_primaries bt709 -color_trc bt709 -colorspace bt709 -c:a aac
mmk m_hdr10 $MV $MA -c:v libx265 -preset ultrafast -x265-params log-level=error:hdr10=1:colorprim=bt2020:transfer=smpte2084:colormatrix=bt2020nc \
  -pix_fmt yuv420p10le -c:a aac
mmk m_open_gop $MV $MA -c:v libx264 -preset veryfast -bf 2 -x264-params open-gop=1:keyint=25:min-keyint=25:scenecut=0 -c:a aac
mmk m_aac_pce $MV -f lavfi -i "sine=f=440:r=48000:d=6" -c:v libx264 -preset veryfast -g 50 \
  -af "pan=7.0|c0=c0|c1=c0|c2=c0|c3=c0|c4=c0|c5=c0|c6=c0" -c:a aac
