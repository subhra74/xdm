#!/bin/bash
# The HLS format matrix: every segment layout each packager can make (TS together/separate, TS video +
# packed audio, TS video + bare ADTS, fMP4 together/separate incl. byte ranges, standalone MP4 parts),
# from a 60 s cut of large.sh's 1080p stream plus short clips with codecs the remux can't carry
# (AV1+Opus, VP9+FLAC, TrueHD, PCM, VC-2, JPEG 2000, SMPTE 302M), then matrix_check.py on each case
# with "Save as MP4 where possible" on and off.
#   docker build -t xdm-packagers tools/ffparity/docker      (once)
#   ./matrix.sh [check-regex ...]
# Env: XDM_MATRIX_DIR (default $TMPDIR/xdm-matrix), XDM_PARITY_CASES (for large.sh's tube_1080p),
#      BENTO4_SRC (a Bento4 v1.6.0-641 checkout: its mp4fragment/mp4dash are built for the fMP4 cases),
#      XDM_JAVA (JDK 25).
set -e
S=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$S/../.." && pwd)
M=${XDM_MATRIX_DIR:-${TMPDIR:-/tmp}/xdm-matrix}
CASES=${XDM_PARITY_CASES:-${TMPDIR:-/tmp}/xdm-parity-cases}
JAVA=${XDM_JAVA:-java}
mkdir -p "$M/src" "$M/cases" "$M/check" "$M/harness" "$M/b4"

# Sources.
q=(-hide_banner -loglevel error -y)
cd "$M/src"
[ -f m.mp4 ] || ffmpeg "${q[@]}" -ss 120 -i "$CASES/_large/src/tube_1080p.mp4" -t 60 -c copy -movflags +faststart m.mp4
V=(-f lavfi -i testsrc2=size=640x360:rate=25); A=(-f lavfi -i sine=frequency=440:sample_rate=48000); T=(-t 20)
[ -f x_av1_opus.mp4 ] || ffmpeg "${q[@]}" "${V[@]}" "${A[@]}" "${T[@]}" -c:v libsvtav1 -preset 10 -g 50 -c:a libopus -b:a 96k x_av1_opus.mp4
[ -f x_vp9_flac.mp4 ] || ffmpeg "${q[@]}" "${V[@]}" "${A[@]}" "${T[@]}" -c:v libvpx-vp9 -b:v 600k -g 50 -deadline realtime -cpu-used 8 -c:a flac x_vp9_flac.mp4
[ -f x_h264_truehd.mp4 ] || ffmpeg "${q[@]}" "${V[@]}" "${A[@]}" "${T[@]}" -c:v libx264 -g 50 -c:a truehd -strict -2 -ac 2 x_h264_truehd.mp4
[ -f x_vc2_aac.mkv ] || ffmpeg "${q[@]}" "${V[@]}" "${A[@]}" "${T[@]}" -c:v vc2 -b:v 3M -pix_fmt yuv420p -c:a aac x_vc2_aac.mkv
[ -f x_j2k_aac.mp4 ] || ffmpeg "${q[@]}" "${V[@]}" "${A[@]}" "${T[@]}" -c:v jpeg2000 -pix_fmt yuv420p -c:a aac x_j2k_aac.mp4
[ -f x_h264_pcm.mp4 ] || ffmpeg "${q[@]}" "${V[@]}" "${A[@]}" "${T[@]}" -c:v libx264 -g 50 -c:a pcm_s16le x_h264_pcm.mp4

# Bento4's fMP4 tools (not in the image), built in the image from a checkout.
if [ -n "$BENTO4_SRC" ] && [ ! -x "$M/b4/bin/mp4fragment" ]; then
  docker run --rm -v "$BENTO4_SRC:/src:ro" -v "$M/b4:/b4" xdm-packagers bash -c \
    'cmake -S /src -B /tmp/b -DCMAKE_BUILD_TYPE=Release >/dev/null && cmake --build /tmp/b -j$(nproc) --target mp4fragment mp4info mp4dump mp4split mp4encrypt mp4iframeindex >/dev/null && mkdir -p /b4/bin && cp /tmp/b/mp4fragment /tmp/b/mp4info /tmp/b/mp4dump /tmp/b/mp4split /tmp/b/mp4encrypt /tmp/b/mp4iframeindex /b4/bin/ && cp -r /src/Source/Python/utils /b4/'
fi

# Cases: the packagers in the image, then SMPTE 302M (only ffmpeg writes it, only into TS).
docker run --rm -v "$M/src:/media:ro" -v "$M/cases:/out" -v "$S/docker:/tools:ro" -v "$M/b4:/b4:ro" xdm-packagers \
  python3 -I /tools/matrix.py
for layout in ts tssep; do
  d="$M/cases/ff_${layout}_x_h264_s302m"; rm -rf "$d"; mkdir -p "$d"
  if [ $layout = ts ]; then
    ffmpeg "${q[@]}" "${V[@]}" "${A[@]}" -t 20 -c:v libx264 -g 50 -c:a s302m -strict -2 -ac 2 -f hls -hls_time 6 -hls_list_size 0 \
      -hls_segment_filename "$d/s%d.ts" "$d/i.m3u8"
    ls "$d"/s*.ts | sort -V | sed "s|$d/||" > "$d/video.txt"
  else
    ffmpeg "${q[@]}" "${V[@]}" "${A[@]}" -t 20 -map 0:v -map 1:a -c:v libx264 -g 50 -c:a s302m -strict -2 -ac 2 -f hls -hls_time 6 \
      -hls_list_size 0 -var_stream_map "v:0,agroup:aud a:0,agroup:aud" -master_pl_name index.m3u8 -hls_segment_filename "$d/r%v_%d.ts" "$d/r%v.m3u8"
    ls "$d"/r0_*.ts | sort -V | sed "s|$d/||" > "$d/video.txt"; ls "$d"/r1_*.ts | sort -V | sed "s|$d/||" > "$d/audio.txt"
  fi
  echo "ffmpeg hls, H.264 + SMPTE 302M ($layout)" > "$d/note.txt"
done

# Check.
cd "$ROOT"
mvn -q -pl xdm-core -am compile
mvn -q -pl xdm-core dependency:build-classpath -Dmdep.outputFile="$M/deps.txt"
CP="$ROOT/xdm-core/target/classes:$(cat "$M/deps.txt")"
"$(dirname "$JAVA")/javac" -cp "$CP" -d "$M/harness" "$ROOT/tools/transmux-corpus/Mux.java" 2>/dev/null || javac -cp "$CP" -d "$M/harness" "$ROOT/tools/transmux-corpus/Mux.java"
XDM_CHECK_CP="$M/harness:$CP" XDM_JAVA=$JAVA python3 -I "$S/matrix_check.py" "$M/cases" "$M/check" "$@"
