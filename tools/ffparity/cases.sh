#!/bin/bash
# Makes the synthetic parity cases with ffmpeg (libx264, libx265, aac). Each case is a folder with
# video.txt (segment files in playlist order, "#EXT-X-DISCONTINUITY" lines where the playlist would
# have them), optional audio.txt (a separate audio rendition),
# optional `disc` (the playlist has discontinuities) and note.txt (what it exercises).
#   ./cases.sh            XDM_PARITY_CASES  where to put them (default: $TMPDIR/xdm-parity-cases)
set -e
OUT=${XDM_PARITY_CASES:-${TMPDIR:-/tmp}/xdm-parity-cases}
M=$OUT/_media
mkdir -p "$M"
F() { ffmpeg -hide_banner -loglevel error -y "$@"; }
X265="-x265-params log-level=error"

# hls <dir> <segment seconds> <ffmpeg input and codec args...>: an HLS rendition of TS segments in $M/<dir>.
hls() {
  local dir=$1 len=$2; shift 2
  [ -f "$M/$dir/index.m3u8" ] && return
  mkdir -p "$M/$dir"
  F "$@" -f hls -hls_time "$len" -hls_list_size 0 -hls_segment_filename "$M/$dir/s%02d.ts" "$M/$dir/index.m3u8"
}
seg() { echo "$M/$1/s$(printf %02d "$2").ts"; }
# case <name> <note> <video segments...>; AUDIO=... and DISC=1 set the extras.
case_() {
  local name=$1 note=$2; shift 2
  rm -rf "$OUT/$name"; mkdir -p "$OUT/$name"
  printf '%s\n' "$@" > "$OUT/$name/video.txt"
  [ -n "$AUDIO" ] && printf '%s\n' $AUDIO > "$OUT/$name/audio.txt"
  [ -n "$DISC" ] && touch "$OUT/$name/disc"
  echo "$note" > "$OUT/$name/note.txt"
  echo "  $name"
}

SINE="-f lavfi -i sine=f=440:sample_rate=48000"
hls h264_720 2 -f lavfi -i testsrc2=size=1280x720:rate=25:duration=6 $SINE:duration=6 \
  -c:v libx264 -profile:v high -g 50 -bf 2 -c:a aac
hls h264_360_base 2 -f lavfi -i testsrc=size=640x360:rate=25:duration=4 -f lavfi -i sine=f=880:sample_rate=48000:duration=4 \
  -pix_fmt yuv420p -c:v libx264 -profile:v baseline -g 50 -c:a aac
hls h264_gop100 1 -f lavfi -i testsrc2=size=640x360:rate=25:duration=8 $SINE:duration=8 \
  -c:v libx264 -g 100 -bf 2 -c:a aac -hls_flags split_by_time
hls hevc10_720 2 -f lavfi -i testsrc2=size=1280x720:rate=25:duration=4 $SINE:duration=4 \
  -pix_fmt yuv420p10le -c:v libx265 $X265:keyint=50 -c:a aac
hls hevc8_360 2 -f lavfi -i testsrc=size=640x360:rate=25:duration=2 -f lavfi -i sine=f=880:sample_rate=48000:duration=2 \
  -pix_fmt yuv420p -c:v libx265 $X265:keyint=50 -c:a aac
hls hevc_open_a 2 -f lavfi -i testsrc2=size=640x360:rate=25:duration=6 $SINE:duration=6 \
  -pix_fmt yuv420p -c:v libx265 $X265:keyint=50:open-gop=1:bframes=4 -c:a aac
hls hevc_open_b 2 -f lavfi -i mandelbrot=size=640x360:rate=25,trim=duration=6 $SINE:duration=6 \
  -pix_fmt yuv420p -c:v libx265 $X265:keyint=50:open-gop=1:bframes=4 -c:a aac
hls h264_open_a 2 -f lavfi -i testsrc2=size=640x360:rate=25:duration=6 $SINE:duration=6 \
  -c:v libx264 -x264-params keyint=50:open-gop=1:bframes=3 -c:a aac
hls h264_open_b 2 -f lavfi -i mandelbrot=size=640x360:rate=25,trim=duration=6 $SINE:duration=6 \
  -pix_fmt yuv420p -c:v libx264 -x264-params keyint=50:open-gop=1:bframes=3 -c:a aac
hls h264_refresh 2 -f lavfi -i testsrc2=size=640x360:rate=25:duration=6 $SINE:duration=6 \
  -c:v libx264 -x264-params keyint=50:intra-refresh=1 -c:a aac
hls h264_video_only 2 -f lavfi -i testsrc2=size=640x360:rate=25:duration=6 -an -c:v libx264 -g 50 -bf 2
hls aac_only 2 $SINE:duration=6 -vn -c:a aac
hls eac3_51_only 2 -f lavfi -i "aevalsrc=0.3*sin(2*PI*440*t)|0.3*sin(2*PI*550*t)|0.3*sin(2*PI*660*t)|0.1*sin(2*PI*60*t)|0.3*sin(2*PI*770*t)|0.3*sin(2*PI*880*t):s=48000:d=6:c=5.1" \
  -vn -c:a eac3 -b:a 384k
hls h264_eac3 2 -f lavfi -i testsrc2=size=640x360:rate=25:duration=6 -f lavfi -i "aevalsrc=0.3*sin(2*PI*440*t)|0.3*sin(2*PI*550*t)|0.3*sin(2*PI*660*t)|0.1*sin(2*PI*60*t)|0.3*sin(2*PI*770*t)|0.3*sin(2*PI*880*t):s=48000:d=6:c=5.1" \
  -c:v libx264 -g 50 -bf 2 -c:a eac3 -b:a 384k
# Packed audio (raw ADTS / E-AC-3 behind an ID3 PRIV timestamp), from the audio-only TS segments.
packed() { # <dir> <ext> <ts dir>
  [ -f "$M/$1/p0000.$2" ] || python3 -I "$(dirname "$0")/packed.py" "$M/$1" "$2" "$M/$3"/s*.ts > /dev/null
}
packed aac_packed aac aac_only
packed eac3_packed ec3 eac3_51_only
hls h264_pid200 2 -f lavfi -i testsrc2=size=1280x720:rate=25:duration=4 -f lavfi -i sine=f=660:sample_rate=48000:duration=4 \
  -c:v libx264 -profile:v high -g 50 -bf 2 -c:a aac -mpegts_start_pid 0x200 -mpegts_pmt_start_pid 0x1100

echo "cases in $OUT:"
case_ base_h264_aac "720p H.264 High with B-frames + AAC, IDR-aligned segments" \
  $(seg h264_720 0) $(seg h264_720 1) $(seg h264_720 2)
case_ midgop_start "starts mid-GOP: frames before the first keyframe (D1)" \
  $(for i in 1 2 3 4 5 6 7; do seg h264_gop100 $i; done)
DISC=1 case_ res_change_ts "720p High, then a 360p Baseline insert, then 720p again (D5)" \
  $(seg h264_720 0) $(seg h264_720 1) $(seg h264_720 2) "#EXT-X-DISCONTINUITY" $(seg h264_360_base 0) $(seg h264_360_base 1) "#EXT-X-DISCONTINUITY" $(seg h264_720 0)
DISC=1 case_ hevc_depth_change "HEVC Main10 720p, an 8-bit 360p insert, Main10 again (D5)" \
  $(seg hevc10_720 0) $(seg hevc10_720 1) "#EXT-X-DISCONTINUITY" $(seg hevc8_360 0) "#EXT-X-DISCONTINUITY" $(seg hevc10_720 0)
DISC=1 case_ midgop_after_disc "720p, then a section that starts mid-GOP (D1 only applies at the start in ffmpeg)" \
  $(seg h264_720 0) $(seg h264_720 1) "#EXT-X-DISCONTINUITY" $(seg h264_gop100 3) $(seg h264_gop100 4)
DISC=1 case_ hevc_opengop_splice "HEVC open GOP (CRA + RASL) spliced: leading pictures reference the other clip" \
  $(seg hevc_open_a 0) "#EXT-X-DISCONTINUITY" $(seg hevc_open_b 1) "#EXT-X-DISCONTINUITY" $(seg hevc_open_a 2)
DISC=1 case_ h264_opengop_splice "H.264 open GOP (recovery point SEI on I) spliced" \
  $(seg h264_open_a 0) "#EXT-X-DISCONTINUITY" $(seg h264_open_b 1) "#EXT-X-DISCONTINUITY" $(seg h264_open_a 2)
case_ h264_intra_refresh "H.264 periodic intra refresh: one IDR, recovery point SEIs after (D2)" \
  $(seg h264_refresh 0) $(seg h264_refresh 1) $(seg h264_refresh 2)
AUDIO="$(seg aac_only 0) $(seg aac_only 1) $(seg aac_only 2)" case_ separate_av "video and audio renditions in separate TS lists" \
  $(seg h264_video_only 0) $(seg h264_video_only 1) $(seg h264_video_only 2)
AUDIO="$(ls "$M"/aac_packed/p*.aac | sort | tr '\n' ' ')" case_ packed_aac "video TS + packed audio: ADTS AAC segments behind an ID3 PRIV timestamp" \
  $(seg h264_video_only 0) $(seg h264_video_only 1) $(seg h264_video_only 2)
AUDIO="$(ls "$M"/eac3_packed/p*.ec3 | sort | tr '\n' ' ')" case_ packed_eac3 "video TS + packed audio: E-AC-3 5.1 segments behind an ID3 PRIV timestamp" \
  $(seg h264_video_only 0) $(seg h264_video_only 1) $(seg h264_video_only 2)
AUDIO="$(seg eac3_51_only 0) $(seg eac3_51_only 1) $(seg eac3_51_only 2)" case_ eac3_separate_ts "video TS + an E-AC-3 5.1 audio-only TS rendition" \
  $(seg h264_video_only 0) $(seg h264_video_only 1) $(seg h264_video_only 2)
case_ eac3_muxed_ts "H.264 + E-AC-3 5.1 in one TS" \
  $(seg h264_eac3 0) $(seg h264_eac3 1) $(seg h264_eac3 2)
DISC=1 case_ pid_change "a discontinuity to another encoder: other PIDs, other PMT PID" \
  $(seg h264_720 0) $(seg h264_720 1) "#EXT-X-DISCONTINUITY" $(seg h264_pid200 0) $(seg h264_pid200 1)
