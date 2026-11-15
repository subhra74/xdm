#!/bin/bash
# Large parity cases shaped like the video-on-demand streams users download from tube sites (see
# private-docs/docs/transmuxer-ffmpeg-parity.md, "Where the streams come from"). Same case layout as
# cases.sh. About 1 GB of segments with the defaults.
#   ./large.sh                XDM_PARITY_CASES  where to put them (default: $TMPDIR/xdm-parity-cases)
#   LARGE_SECONDS=600         length of the 1080p streams;  LONG_SECONDS=3600  the long low-resolution one
set -e
OUT=${XDM_PARITY_CASES:-${TMPDIR:-/tmp}/xdm-parity-cases}
M=$OUT/_large
SEC=${LARGE_SECONDS:-600}
LONG=${LONG_SECONDS:-3600}
mkdir -p "$M"
F() { ffmpeg -hide_banner -loglevel error -y "$@"; }

# Picture with hard scene cuts (x264's scenecut then places keyframes off the fixed interval, so GOPs
# and segment lengths vary as in real uploads), and a tone that changes pitch.
video() { # <size> <seconds>
  echo "testsrc2=size=$1:rate=30:duration=$2[a];life=size=$1:rate=30:mold=10:ratio=0.1:life_color=#00ff00:death_color=#c83232[b];[a][b]overlay=enable='lt(mod(t\,20)\,7)':shortest=1"
}
audio() { echo "aevalsrc=0.4*sin(2*PI*(300+100*floor(mod(t\,20)/5))*t)|0.4*sin(2*PI*(450+50*floor(mod(t\,15)/5))*t):s=44100:d=$1"; }

# hls <dir> <input mp4> <seconds per segment> [extra hls flags]: TS segments of an existing file, copied.
hls() {
  local dir=$1 src=$2 len=$3; shift 3
  [ -f "$M/$dir/index.m3u8" ] && return
  mkdir -p "$M/$dir"
  F -i "$src" -c copy -f hls -hls_time "$len" -hls_list_size 0 "$@" -hls_segment_filename "$M/$dir/s%04d.ts" "$M/$dir/index.m3u8"
}
list() { ls "$M/$1"/s*.ts | sort; }
case_() { # <name> <note> (video list on stdin)
  rm -rf "$OUT/$1"; mkdir -p "$OUT/$1"
  cat > "$OUT/$1/video.txt"
  echo "$2" > "$OUT/$1/note.txt"
  echo "  $1 ($(wc -l < "$OUT/$1/video.txt" | tr -d ' ') segments)"
}

# 1. The site's transcoding farm: x264 veryfast, CRF with a bitrate cap, default GOP (keyint 250,
#    scenecut, 3 B-frames), 44.1 kHz AAC. Encoded once to MP4, as the sites store it.
if [ ! -f "$M/tube_1080p/index.m3u8" ] || [ ! -f "$M/vod_cut/index.m3u8" ]; then
  if [ ! -f "$M/tube_1080p.mp4" ]; then
    echo "encoding tube_1080p.mp4 ($SEC s, x264)"
    F -f lavfi -i "$(video 1920x1080 "$SEC")" -f lavfi -i "$(audio "$SEC")" \
      -c:v libx264 -preset veryfast -crf 23 -maxrate 3M -bufsize 6M -pix_fmt yuv420p \
      -c:a aac -b:a 128k -t "$SEC" -movflags +faststart "$M/tube_1080p.mp4"
  fi
fi
# Packaged by ffmpeg's hls muxer: cuts only at keyframes, so segments start on one (6 s target).
hls tube_1080p "$M/tube_1080p.mp4" 6
# Packaged nginx-vod-module style: cut every 10 s by time, not at keyframes (its default
# vod_align_segments_to_key_frames off), so segments after the first start mid-GOP.
hls vod_cut "$M/tube_1080p.mp4" 10 -hls_flags split_by_time
rm -f "$M/tube_1080p.mp4"

# 2. A hardware encoder (VideoToolbox, as phones and GPU encoders): uploads some sites pass through.
if [ ! -f "$M/hw_h264/index.m3u8" ]; then
  echo "encoding hw_h264 ($SEC s, h264_videotoolbox)"
  F -f lavfi -i "$(video 1920x1080 "$SEC")" -f lavfi -i "$(audio "$SEC")" \
    -c:v h264_videotoolbox -profile:v high -b:v 3M -g 120 -pix_fmt yuv420p \
    -c:a aac_at -b:a 128k -t "$SEC" "$M/hw_h264.mp4"
  hls hw_h264 "$M/hw_h264.mp4" 6
  rm -f "$M/hw_h264.mp4"
fi
if [ ! -f "$M/hw_hevc10/index.m3u8" ]; then
  echo "encoding hw_hevc10 ($SEC s, hevc_videotoolbox Main10)"
  F -f lavfi -i "$(video 1920x1080 "$SEC")" -f lavfi -i "$(audio "$SEC")" \
    -c:v hevc_videotoolbox -profile:v main10 -pix_fmt p010le -b:v 2M -g 120 \
    -c:a aac_at -b:a 128k -t "$SEC" "$M/hw_hevc10.mp4"
  hls hw_hevc10 "$M/hw_hevc10.mp4" 6
  rm -f "$M/hw_hevc10.mp4"
fi

# 3. A long stream: sample tables past the in-memory limit, many segments.
if [ ! -f "$M/long_240p/index.m3u8" ]; then
  echo "encoding long_240p ($LONG s, x264)"
  F -f lavfi -i "$(video 426x240 "$LONG")" -f lavfi -i "$(audio "$LONG")" \
    -c:v libx264 -preset veryfast -crf 26 -maxrate 300k -bufsize 600k -pix_fmt yuv420p \
    -c:a aac -b:a 64k -t "$LONG" "$M/long_240p.mp4"
  hls long_240p "$M/long_240p.mp4" 10
  rm -f "$M/long_240p.mp4"
fi

echo "large cases in $OUT:"
list tube_1080p | case_ large_tube_1080p "x264 farm, $SEC s 1080p, scenecut GOPs, segments on keyframes"
list vod_cut | case_ large_vod_midgop_cuts "nginx-vod style: 10 s segments cut by time, mid-GOP"
list vod_cut | tail -n +8 | case_ large_vod_from_segment_7 "nginx-vod style, the download starts at segment 7 (mid-GOP)"
list hw_h264 | case_ large_hw_h264 "VideoToolbox H.264 High, 1080p, B-frames as the encoder chooses"
list hw_hevc10 | case_ large_hw_hevc10 "VideoToolbox HEVC Main10, 1080p"
list long_240p | case_ large_long_240p "x264, $LONG s at 240p, many segments and samples"
du -sh "$M"
