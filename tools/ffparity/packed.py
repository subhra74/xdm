#!/usr/bin/env python3
"""Turns audio-only MPEG-TS HLS segments into HLS "packed audio" segments, as Apple's segmenter and CDN
packagers write them (HLS spec 3.4): each segment is the raw elementary stream (ADTS AAC, AC-3, E-AC-3
or MP3 frames, copied, not re-encoded) after an ID3v2.4 tag whose PRIV frame
`com.apple.streaming.transportStreamTimestamp` holds the segment's first 33-bit PTS (90 kHz). ffmpeg's
hls muxer can't write these: it writes TS whatever the segment extension.

  python3 -I packed.py <out folder> <ext: aac|ac3|ec3|mp3> <audio-only TS segment> ...
Prints the packed segment paths, in order.
"""
import os, struct, subprocess, sys

RAW_FORMAT = {'aac': 'adts', 'ac3': 'ac3', 'ec3': 'eac3', 'mp3': 'mp3'}
OWNER = b'com.apple.streaming.transportStreamTimestamp\x00'


def synchsafe(n):
    return bytes([(n >> 21) & 0x7F, (n >> 14) & 0x7F, (n >> 7) & 0x7F, n & 0x7F])


def id3_timestamp(pts90k):
    data = OWNER + struct.pack('>Q', pts90k & ((1 << 33) - 1))
    frame = b'PRIV' + synchsafe(len(data)) + b'\x00\x00' + data
    return b'ID3\x04\x00\x00' + synchsafe(len(frame)) + frame


def first_pts(ts):
    """First audio PES PTS of [ts] in 90 kHz ticks, as stored (no start-time shift)."""
    r = subprocess.run(['ffprobe', '-v', 'error', '-select_streams', 'a:0', '-show_entries', 'packet=pts',
                        '-read_intervals', '%+#1', '-of', 'csv=p=0', ts], capture_output=True, text=True, check=True)
    return int(r.stdout.split()[0].strip(','))


def main():
    out, ext, segments = sys.argv[1], sys.argv[2], sys.argv[3:]
    os.makedirs(out, exist_ok=True)
    for k, ts in enumerate(segments):
        raw = subprocess.run(['ffmpeg', '-v', 'error', '-i', ts, '-map', '0:a:0', '-c', 'copy', '-f', RAW_FORMAT[ext], '-'],
                             capture_output=True, check=True).stdout
        dest = os.path.join(out, f'p{k:04d}.{ext}')
        with open(dest, 'wb') as f:
            f.write(id3_timestamp(first_pts(ts)))
            f.write(raw)
        print(dest)


if __name__ == '__main__':
    main()
