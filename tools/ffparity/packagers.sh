#!/bin/bash
# Packages the large H.264 streams (made by large.sh) with the packagers in the xdm-packagers image
# (nginx-vod-module, Bento4, GPAC, GStreamer), one harness case per packager and stream.
#   docker build -t xdm-packagers tools/ffparity/docker      (once)
#   ./packagers.sh            XDM_PARITY_CASES  as for cases.sh / large.sh
# The MP4 a site would store is made back from large.sh's segments by ffmpeg stream copy.
set -e
S=$(cd "$(dirname "$0")" && pwd)
OUT=${XDM_PARITY_CASES:-${TMPDIR:-/tmp}/xdm-parity-cases}
M=$OUT/_large
SRC=$M/src
mkdir -p "$SRC"
names=()
for name in ${PACKAGE_NAMES:-tube_1080p hw_h264}; do
  # A stream large.sh hasn't finished (no playlist yet) is skipped.
  [ -f "$M/$name/index.m3u8" ] || { echo "skipping $name: not made yet (large.sh)" >&2; continue; }
  if [ ! -f "$SRC/$name.mp4" ]; then
    ls "$M/$name"/s*.ts | sort > "$SRC/$name.list"
    ffmpeg -hide_banner -loglevel error -y -i "concatf:$SRC/$name.list" -map 0 -c copy -movflags +faststart "$SRC/$name.mp4"
  fi
  names+=("$name")
done
[ ${#names[@]} -gt 0 ] || { echo "nothing to package: run large.sh first" >&2; exit 2; }
docker run --rm -v "$SRC:/media:ro" -v "$OUT:/out" -v "$S/docker:/tools:ro" xdm-packagers \
  python3 -I /tools/package.py "${names[@]}"
