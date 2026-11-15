#!/usr/bin/env python3
"""Fetches a public HLS VOD stream into a harness case folder (as cases.sh / package.py make them):
the lowest-bandwidth variant and its default audio rendition, up to N segments each, with the
EXT-X-MAP init first and byte ranges cut into files (as the downloader fetches them). Encrypted
streams are skipped. Writes video.txt, audio.txt (separate audio), note.txt and the playlists.

  python3 -I fetch_real.py <cases dir> <name> <master or media playlist URL> [max segments, default 20]

Only public test streams (see README: "Real streams"); nothing is sent but the requests themselves.
"""
import os, subprocess, sys, urllib.parse

def get(url, rng=None):
    """The body at [url] (bytes [rng] = (start, end) inclusive), through curl: it uses the system's trusted
    certificates, which this Python may not have."""
    # Retries: some public test servers answer 502/503 or drop connections now and then.
    cmd = ['curl', '-sSfL', '-m', '120', '--retry', '6', '--retry-all-errors', '--retry-delay', '3', '-A', 'xdm-parity-harness', url]
    if rng:
        cmd[1:1] = ['-r', f'{rng[0]}-{rng[1]}']
    return subprocess.run(cmd, capture_output=True, check=True).stdout


def attr(line, key):
    if f'{key}="' in line:
        return line.split(f'{key}="', 1)[1].split('"', 1)[0]
    if f'{key}=' in line:
        return line.split(f'{key}=', 1)[1].split(',', 1)[0]
    return None


def media(url, case, tag, limit):
    """Downloads up to [limit] segments of the media playlist at [url]; returns local paths, init first."""
    text = get(url).decode('utf-8', 'replace')
    open(os.path.join(case, f'{tag}.m3u8'), 'w').write(text)
    if '#EXT-X-KEY' in text and 'METHOD=NONE' not in text:
        raise SystemExit(f'{url}: encrypted, skipped')
    out, pending, offsets, n = [], None, {}, 0

    def fetch(uri, rng, name):
        full = urllib.parse.urljoin(url, uri)
        dest = os.path.join(case, name)
        if rng is None:
            data = get(full)
        else:
            length, _, start = rng.partition('@')
            start = int(start) if start else offsets.get(full, 0)
            offsets[full] = start + int(length)
            data = get(full, (start, start + int(length) - 1))
        open(dest, 'wb').write(data)
        return dest

    for line in text.splitlines():
        line = line.strip()
        if line.startswith('#EXT-X-MAP:'):
            ext = os.path.splitext(urllib.parse.urlparse(attr(line, 'URI')).path)[1] or '.mp4'
            out.append(fetch(attr(line, 'URI'), attr(line, 'BYTERANGE'), f'{tag}_init{ext}'))
        elif line.startswith('#EXT-X-DISCONTINUITY') and not line.startswith('#EXT-X-DISCONTINUITY-SEQUENCE'):
            out.append('#EXT-X-DISCONTINUITY')
        elif line.startswith('#EXT-X-BYTERANGE:'):
            pending = line.split(':', 1)[1]
        elif line and not line.startswith('#'):
            if n >= limit:
                break
            ext = os.path.splitext(urllib.parse.urlparse(line).path)[1] or '.ts'
            out.append(fetch(line, pending, f'{tag}_{n:04d}{ext}'))
            pending, n = None, n + 1
    return out


def main():
    cases, name, url = sys.argv[1:4]
    limit = int(sys.argv[4]) if len(sys.argv) > 4 else 20
    case = os.path.join(cases, f'real_{name}')
    os.makedirs(case, exist_ok=True)
    text = get(url).decode('utf-8', 'replace')
    open(os.path.join(case, 'index.m3u8'), 'w').write(text)
    lines = text.splitlines()
    audio_url, video_url, note = None, url, ''
    if '#EXT-X-STREAM-INF' in text:
        variants = []
        for i, l in enumerate(lines):
            if l.startswith('#EXT-X-STREAM-INF') and i + 1 < len(lines):
                bw = int(attr(l, 'BANDWIDTH') or 0)
                variants.append((bw, l, urllib.parse.urljoin(url, lines[i + 1].strip())))
        # The lowest variant with video (RESOLUTION, or a video codec), else the lowest: audio-only variants
        # come first by bandwidth in many masters. FETCH_AUDIO_ONLY=1 takes the lowest audio-only one instead.
        has_video = lambda l: attr(l, 'RESOLUTION') or any(c in (attr(l, 'CODECS') or '') for c in ('avc', 'hvc', 'hev', 'av01', 'vp09'))
        want_audio_only = os.environ.get('FETCH_AUDIO_ONLY') == '1'
        pool = [v for v in variants if bool(has_video(v[1])) != want_audio_only] or variants
        bw, inf, video_url = min(pool)
        group = attr(inf, 'AUDIO')
        renditions = [l for l in lines if l.startswith('#EXT-X-MEDIA:') and 'TYPE=AUDIO' in l and attr(l, 'GROUP-ID') == group and attr(l, 'URI')]
        pick = next((l for l in renditions if attr(l, 'DEFAULT') == 'YES'), renditions[0] if renditions else None)
        if pick:
            audio_url = urllib.parse.urljoin(url, attr(pick, 'URI'))
        note = f'{bw} bps, CODECS={attr(inf, "CODECS")}' + (f', audio {attr(pick, "NAME")}' if pick else '')
    video = media(video_url, case, 'v', limit)
    rel = lambda p: p if p.startswith('#') else os.path.relpath(p, case)
    open(os.path.join(case, 'video.txt'), 'w').write(''.join(rel(p) + '\n' for p in video))
    if audio_url:
        audio = media(audio_url, case, 'a', limit)
        open(os.path.join(case, 'audio.txt'), 'w').write(''.join(rel(p) + '\n' for p in audio))
    open(os.path.join(case, 'note.txt'), 'w').write(f'{url} ({note}), first {limit} segments\n')
    print(f'real_{name}: {sum(1 for p in video if not p.startswith("#"))} video' + (f' + {len(audio)} audio' if audio_url else '') + f' files  {note}')


if __name__ == '__main__':
    main()
