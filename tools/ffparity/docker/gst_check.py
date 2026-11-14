"""Plays an MP4 the way Linux desktop players do (GStreamer: qtdemux + decodebin, as GNOME Videos,
Showtime and Totem), as fast as possible, and prints one JSON object: whether it plays to the end,
decoded video frames, held frames (gaps over 1.5 frames: stutter), duplicate/backward times, errors.

  python3 -I /tools/gst_check.py file.mp4        (inside the xdm-packagers image)
"""
import json, re, subprocess, sys

path = sys.argv[1]
r = subprocess.run(['gst-launch-1.0', '-v', 'filesrc', f'location={path}', '!', 'qtdemux', 'name=d',
                    'd.video_0', '!', 'queue', '!', 'decodebin', '!', 'identity', 'name=frames', 'silent=false', '!',
                    'fakesink', 'sync=false'],
                   capture_output=True, text=True)
times = []
for line in r.stdout.splitlines():
    # identity: "/GstPipeline:pipeline0/GstIdentity:frames: last-message = chain   ******* (frames:sink) (... bytes, dts: ..., pts: 0:00:01.040000000, ...)"
    if 'frames' in line and 'chain' in line:
        m = re.search(r'pts: (\d+):(\d+):(\d+)\.(\d+)', line)
        if m:
            h, mi, s, ns = m.groups()
            times.append(int(h) * 3600 + int(mi) * 60 + int(s) + int(ns) / 10 ** len(ns))
errors = [l.strip() for l in (r.stdout + r.stderr).splitlines() if 'ERROR' in l or 'WARNING' in l]
out = {'file': path, 'plays_to_end': r.returncode == 0, 'frames': len(times), 'errors': errors[:5]}
if len(times) > 1:
    steps = [b - a for a, b in zip(times, times[1:])]
    pos = sorted(s for s in steps if s > 0)
    typical = pos[len(pos) // 2] if pos else 0
    out.update(firstPts=times[0], lastPts=times[-1], duplicateOrBackward=sum(s <= 0 for s in steps),
               heldFrames=sum(s > typical * 1.5 for s in steps), longestGapMs=round(max(steps) * 1000, 1))
print(json.dumps(out, sort_keys=True))
