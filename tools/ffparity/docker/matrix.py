"""Runs inside the xdm-packagers image: the HLS format matrix, one case folder per (tool, format, layout)
under /out, from the sources in /media (made by ../matrix.sh):

  m.mp4               H.264 + AAC, 60 s cut of a 1080p stream: every format each tool can make
  x_<codecs>.<ext>    short clips with codecs the remux can't carry (or only copies): what each tool accepts

Formats: ts (audio+video in one TS list), tssep (video and audio TS renditions), tspacked (TS video +
HLS packed audio, ID3 timestamp), tsraw (TS video + bare ADTS, no ID3), fmp4 / fmp4sep (init + fragments),
mp4 / mp4sep (standalone MP4 parts, each with its own moov). A combination a tool can't make is
reported and skipped. Each folder gets video.txt (audio.txt for separate renditions) with the
EXT-X-MAP init first, note.txt and the playlists. Usage: python3 -I /tools/matrix.py [tool ...]

Bento4's fMP4 (mp4fragment + mp4dash --hls) isn't in the image: /b4 is a build of the same tag
(bin/ and utils/), built and mounted by ../matrix.sh.
"""
import glob, os, shutil, subprocess, sys, time, urllib.request

MEDIA, OUT, B4 = '/media', '/out', '/b4'
made, skipped = [], []


def sh(cmd, **kw):
    r = subprocess.run(cmd, capture_output=True, text=True, **kw)
    if r.returncode != 0:
        raise RuntimeError(f'{os.path.basename(cmd[0])} failed: {(r.stderr or r.stdout)[-400:].strip()}')
    return r.stdout


def items(path):
    """(init or None, segment paths) of a media playlist. Byte ranges (EXT-X-BYTERANGE, and on EXT-X-MAP)
    are cut into files of their own, as the downloader fetches each range as a segment."""
    base = os.path.dirname(path)
    init, segs = None, []
    pending, offset = None, {}

    def cut(uri, rng, n):
        src = os.path.join(base, uri)
        if rng is None:
            return src
        length, _, start = rng.partition('@')
        start = int(start) if start else offset.get(uri, 0)
        offset[uri] = start + int(length)
        # Named after the playlist too: several playlists may cut ranges out of one file.
        pl = os.path.splitext(os.path.basename(path))[0]
        dest = os.path.join(base, f'{pl}_{os.path.splitext(os.path.basename(uri))[0]}_r{n:04d}{os.path.splitext(uri)[1]}')
        with open(src, 'rb') as f:
            f.seek(start)
            open(dest, 'wb').write(f.read(int(length)))
        return dest

    for line in open(path).read().splitlines():
        line = line.strip()
        if line.startswith('#EXT-X-MAP:'):
            uri = line.split('URI="', 1)[1].split('"', 1)[0]
            rng = line.split('BYTERANGE="', 1)[1].split('"', 1)[0] if 'BYTERANGE="' in line else None
            init = cut(uri, rng, 9999)
        elif line.startswith('#EXT-X-BYTERANGE:'):
            pending = line.split(':', 1)[1]
        elif line and not line.startswith('#'):
            segs.append(cut(line, pending, len(segs)))
            pending = None
    return init, segs


def lists(index):
    """(video list, audio list or None) of a master or media playlist, each with its init first."""
    base = os.path.dirname(index)
    text = open(index).read()
    if '#EXT-X-STREAM-INF' not in text:
        init, segs = items(index)
        return ([init] if init else []) + segs, None
    audio = None
    for line in text.splitlines():
        if line.startswith('#EXT-X-MEDIA:') and 'TYPE=AUDIO' in line and 'URI="' in line:
            audio = os.path.join(base, line.split('URI="', 1)[1].split('"', 1)[0])
    lines = text.splitlines()
    variant = next(os.path.join(base, lines[i + 1].strip()) for i, l in enumerate(lines) if l.startswith('#EXT-X-STREAM-INF'))
    vi, vs = items(variant)
    video = ([vi] if vi else []) + vs
    if not audio:
        return video, None
    ai, a = items(audio)
    return video, ([ai] if ai else []) + a


def case(name, note, build):
    """Makes /out/<name> with build(dir) -> (video list, audio list or None); a failure is skipped."""
    d = os.path.join(OUT, name)
    shutil.rmtree(d, ignore_errors=True)
    os.makedirs(d)
    try:
        video, audio = build(d)
        if not video:
            raise RuntimeError('no segments')
    except Exception as e:
        shutil.rmtree(d, ignore_errors=True)
        skipped.append((name, str(e).splitlines()[-1][:160] if str(e) else type(e).__name__))
        print(f'  skip {name}: {skipped[-1][1]}', flush=True)
        return
    with open(os.path.join(d, 'video.txt'), 'w') as f:
        f.write(''.join(os.path.relpath(p, d) + '\n' for p in video))
    if audio:
        with open(os.path.join(d, 'audio.txt'), 'w') as f:
            f.write(''.join(os.path.relpath(p, d) + '\n' for p in audio))
    open(os.path.join(d, 'note.txt'), 'w').write(note + '\n')
    made.append(name)
    print(f'  {name}: {len(video)} video' + (f' + {len(audio)} audio' if audio else '') + ' files', flush=True)


def write_master(d, video_pl, audio_pl):
    """A master playlist naming a video playlist and an audio rendition (for tools that write one list per run)."""
    p = os.path.join(d, 'index.m3u8')
    open(p, 'w').write('#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="a",NAME="a",DEFAULT=YES,URI="%s"\n'
                       '#EXT-X-STREAM-INF:BANDWIDTH=1000000,AUDIO="a"\n%s\n' % (audio_pl, video_pl))
    return p


def parts_playlist(d, pattern, name):
    """A media playlist over standalone files matching [pattern] (tools that only split, not write HLS)."""
    files = sorted(glob.glob(os.path.join(d, pattern)))
    p = os.path.join(d, name)
    open(p, 'w').write('#EXTM3U\n#EXT-X-TARGETDURATION:8\n' + ''.join(f'#EXTINF:6,\n{os.path.basename(f)}\n' for f in files) + '#EXT-X-ENDLIST\n')
    return p


def tracks(src):
    out = sh(['ffprobe', '-v', 'error', '-show_entries', 'stream=index,codec_type', '-of', 'csv=p=0', src])
    return {kind: int(i) for i, kind in (l.split(',')[:2] for l in out.splitlines() if l)}


# ---- tools ----

def bento4(tag, src):
    seg = '6'
    tr = tracks(src)
    v_id, a_id = tr['video'] + 1, tr['audio'] + 1    # track IDs follow the stream order in these files

    def hls(d, sub, *opts, ext='ts'):
        os.makedirs(f'{d}/{sub}', exist_ok=True)
        sh(['mp42hls', '--segment-duration', seg, '--index-filename', f'{d}/{sub}/i.m3u8',
            '--segment-filename-template', f'{d}/{sub}/s%llu.{ext}', '--segment-url-template', f's%llu.{ext}', *opts, src])
        return f'{sub}/i.m3u8'

    case(f'b4_ts_{tag}', 'Bento4 mp42hls, audio+video TS', lambda d: lists(f'{d}/' + hls(d, 'av')))
    case(f'b4_tssep_{tag}', 'Bento4 mp42hls, video TS + audio TS renditions',
         lambda d: lists(write_master(d, hls(d, 'v', '--audio-track-id', '0'), hls(d, 'a', '--video-track-id', '0'))))
    case(f'b4_tspacked_{tag}', 'Bento4 mp42hls, video TS + packed audio (ID3 timestamp)',
         lambda d: lists(write_master(d, hls(d, 'v', '--audio-track-id', '0'),
                                      hls(d, 'a', '--video-track-id', '0', '--audio-format', 'packed', ext=packed_ext(src)))))

    def fmp4(d):
        if not os.path.exists(f'{B4}/bin/mp4fragment'):
            raise RuntimeError('no Bento4 mp4fragment build at /b4')
        sh([f'{B4}/bin/mp4fragment', '--fragment-duration', '6000', src, f'{d}/frag.mp4'])
        sh(['python3', f'{B4}/utils/mp4-dash.py', '--exec-dir', f'{B4}/bin', '--hls', '--no-split', '--force',
            '-o', f'{d}/dash', f'{d}/frag.mp4'])
        os.remove(f'{d}/frag.mp4')
        return lists(f'{d}/dash/master.m3u8')

    def fmp4_split(d):
        if not os.path.exists(f'{B4}/bin/mp4fragment'):
            raise RuntimeError('no Bento4 mp4fragment build at /b4')
        sh([f'{B4}/bin/mp4fragment', '--fragment-duration', '6000', src, f'{d}/frag.mp4'])
        sh(['python3', f'{B4}/utils/mp4-dash.py', '--exec-dir', f'{B4}/bin', '--hls', '--force', '-o', f'{d}/dash', f'{d}/frag.mp4'])
        os.remove(f'{d}/frag.mp4')
        return lists(f'{d}/dash/master.m3u8')

    case(f'b4_fmp4sep_{tag}', 'Bento4 mp4fragment + mp4dash --hls (one file per rendition, byte ranges)', fmp4)
    case(f'b4_fmp4sepfiles_{tag}', 'Bento4 mp4fragment + mp4dash --hls, segment files', fmp4_split)


def packed_ext(src):
    codec = sh(['ffprobe', '-v', 'error', '-select_streams', 'a:0', '-show_entries', 'stream=codec_name', '-of', 'csv=p=0', src]).strip()
    return {'aac': 'aac', 'ac3': 'ac3', 'eac3': 'ec3', 'mp3': 'mp3'}.get(codec, 'aud')


def gpac(tag, src):
    def run(d, opts, muxed=False):
        sh(['gpac', '-i', src + (':#Representation=1' if muxed else ''), '-o', f'{d}/index.m3u8:segdur=6:' + opts])
        return lists(f'{d}/index.m3u8')

    case(f'gp_ts_{tag}', 'GPAC dasher, audio+video in one TS representation', lambda d: run(d, 'muxtype=ts', muxed=True))
    case(f'gp_tssep_{tag}', 'GPAC dasher, separate TS renditions', lambda d: run(d, 'muxtype=ts'))
    case(f'gp_fmp4_{tag}', 'GPAC dasher, audio+video in one fMP4 representation', lambda d: run(d, 'muxtype=mp4', muxed=True))
    case(f'gp_fmp4sep_{tag}', 'GPAC dasher, separate fMP4 renditions', lambda d: run(d, 'muxtype=mp4'))

    def ts_raw(d):
        os.makedirs(f'{d}/v'); os.makedirs(f'{d}/a')
        q = ['ffmpeg', '-hide_banner', '-loglevel', 'error', '-y', '-i', src, '-c', 'copy']
        sh(q + ['-map', '0:v:0', f'{d}/v.mp4']); sh(q + ['-map', '0:a:0', f'{d}/a.mp4'])
        sh(['gpac', '-i', f'{d}/v.mp4', '-o', f'{d}/v/i.m3u8:segdur=6:muxtype=ts'])
        sh(['gpac', '-i', f'{d}/a.mp4', '-o', f'{d}/a/i.m3u8:segdur=6:muxtype=raw'])
        os.remove(f'{d}/v.mp4'); os.remove(f'{d}/a.mp4')
        v, _ = lists(f'{d}/v/i.m3u8')
        a, _ = lists(f'{d}/a/i.m3u8')
        return v, a
    case(f'gp_tsraw_{tag}', 'GPAC: video TS + bare ADTS audio list (raw segments, no ID3 timestamp)', ts_raw)


def nginxvod(tag, src):
    name = os.path.splitext(os.path.basename(src))[0]
    ext = os.path.splitext(src)[1]

    def fetch(d, loc):
        url = f'http://127.0.0.1:8080/{loc}/{name}{ext}/'
        master = urllib.request.urlopen(url + 'master.m3u8', timeout=60).read().decode()
        open(f'{d}/index.m3u8', 'w').write(master)
        uris = [l.split('URI="', 1)[1].split('"', 1)[0] for l in master.splitlines() if l.startswith('#EXT-X-MEDIA:') and 'URI="' in l]
        uris += [l for l in master.splitlines() if l and not l.startswith('#')]
        for u in uris:
            pl = urllib.request.urlopen(url + u, timeout=60).read().decode()
            open(f'{d}/{u}', 'w').write(pl)
            init, segs = items(f'{d}/{u}')
            for p in ([init] if init else []) + segs:
                with urllib.request.urlopen(url + os.path.basename(p), timeout=120) as r, open(p, 'wb') as f:
                    shutil.copyfileobj(r, f)
        return lists(f'{d}/index.m3u8')

    case(f'ngx_ts_{tag}', 'nginx-vod-module, audio+video TS (keyframe-aligned)', lambda d: fetch(d, 'ts'))
    case(f'ngx_tssep_{tag}', 'nginx-vod-module, vod_hls_force_unmuxed_segments: separate TS renditions', lambda d: fetch(d, 'ts-unmuxed'))
    case(f'ngx_fmp4_{tag}', 'nginx-vod-module, vod_hls_container_format fmp4: audio+video fMP4', lambda d: fetch(d, 'fmp4'))
    case(f'ngx_fmp4sep_{tag}', 'nginx-vod-module, fmp4 + force_unmuxed: separate fMP4 renditions', lambda d: fetch(d, 'fmp4-unmuxed'))


def gstreamer(tag, src):
    demux = ['filesrc', f'location={src}', '!', 'parsebin', 'name=p']

    def hls(d, sub, video=True, audio=True):
        os.makedirs(f'{d}/{sub}', exist_ok=True)
        cmd = ['gst-launch-1.0', '-q', *demux, 'hlssink2', 'name=h', f'location={d}/{sub}/s%05d.ts',
               f'playlist-location={d}/{sub}/i.m3u8', 'target-duration=6', 'max-files=0', 'playlist-length=0']
        if video: cmd += ['p.', '!', 'video/x-h264;video/x-h265;video/x-dirac;image/x-jpc;video/mpeg', '!', 'queue', '!', 'h.video']
        if audio: cmd += ['p.', '!', 'audio/mpeg;audio/x-ac3;audio/x-opus;audio/x-dts;audio/x-eac3', '!', 'queue', '!', 'h.audio']
        sh(cmd, timeout=90)
        return f'{sub}/i.m3u8'

    def parts(d, sub, video=True, audio=True):
        os.makedirs(f'{d}/{sub}', exist_ok=True)
        cmd = ['gst-launch-1.0', '-q', *demux, 'splitmuxsink', 'name=m', 'muxer=mp4mux',
               f'location={d}/{sub}/s%05d.mp4', 'max-size-time=6000000000']
        if video: cmd += ['p.', '!', 'video/x-h264;video/x-h265', '!', 'queue', '!', 'm.video']
        if audio: cmd += ['p.', '!', 'audio/mpeg', '!', 'queue', '!', 'm.audio_0']
        sh(cmd, timeout=90)
        return os.path.relpath(parts_playlist(f'{d}/{sub}', 's*.mp4', 'i.m3u8'), d)

    case(f'gs_ts_{tag}', 'GStreamer hlssink2 (mpegtsmux), audio+video TS', lambda d: lists(f'{d}/' + hls(d, 'av')))
    case(f'gs_tssep_{tag}', 'GStreamer hlssink2, video TS + audio TS renditions',
         lambda d: lists(write_master(d, hls(d, 'v', audio=False), hls(d, 'a', video=False))))
    case(f'gs_mp4_{tag}', 'GStreamer splitmuxsink (mp4mux): standalone MP4 parts, audio+video', lambda d: lists(f'{d}/' + parts(d, 'av')))
    case(f'gs_mp4sep_{tag}', 'GStreamer splitmuxsink: standalone MP4 parts, separate video and audio',
         lambda d: lists(write_master(d, parts(d, 'v', audio=False), parts(d, 'a', video=False))))


def ffmpeg(tag, src):
    q = ['ffmpeg', '-hide_banner', '-loglevel', 'error', '-y', '-i', src]

    def hls(d, sub, maps, seg_type='mpegts'):
        os.makedirs(f'{d}/{sub}', exist_ok=True)
        ext = 'ts' if seg_type == 'mpegts' else 'm4s'
        sh(q + maps + ['-c', 'copy', '-strict', '-2', '-f', 'hls', '-hls_time', '6', '-hls_list_size', '0', '-hls_segment_type', seg_type,
                       '-hls_fmp4_init_filename', 'init.mp4', '-hls_segment_filename', f'{d}/{sub}/s%d.{ext}', f'{d}/{sub}/i.m3u8'])
        return f'{sub}/i.m3u8'

    def parts(d, sub, maps):
        os.makedirs(f'{d}/{sub}', exist_ok=True)
        sh(q + maps + ['-c', 'copy', '-strict', '-2', '-f', 'segment', '-segment_time', '6', '-segment_format', 'mp4', f'{d}/{sub}/s%03d.mp4'])
        return os.path.relpath(parts_playlist(f'{d}/{sub}', 's*.mp4', 'i.m3u8'), d)

    av, v, a = ['-map', '0:v:0', '-map', '0:a:0'], ['-map', '0:v:0'], ['-map', '0:a:0']
    case(f'ff_ts_{tag}', 'ffmpeg hls, audio+video TS', lambda d: lists(f'{d}/' + hls(d, 'av', av)))
    case(f'ff_tssep_{tag}', 'ffmpeg hls, video TS + audio TS renditions', lambda d: lists(write_master(d, hls(d, 'v', v), hls(d, 'a', a))))
    case(f'ff_fmp4_{tag}', 'ffmpeg hls, audio+video fMP4', lambda d: lists(f'{d}/' + hls(d, 'av', av, 'fmp4')))
    case(f'ff_fmp4sep_{tag}', 'ffmpeg hls, separate fMP4 renditions', lambda d: lists(write_master(d, hls(d, 'v', v, 'fmp4'), hls(d, 'a', a, 'fmp4'))))
    case(f'ff_mp4_{tag}', 'ffmpeg segment muxer: standalone MP4 parts, audio+video', lambda d: lists(f'{d}/' + parts(d, 'av', av)))
    case(f'ff_mp4sep_{tag}', 'ffmpeg segment muxer: standalone MP4 parts, separate video and audio',
         lambda d: lists(write_master(d, parts(d, 'v', v), parts(d, 'a', a))))


TOOLS = {'bento4': bento4, 'gpac': gpac, 'nginx': nginxvod, 'gstreamer': gstreamer, 'ffmpeg': ffmpeg}


def main():
    wanted = sys.argv[1:] or list(TOOLS)
    if 'nginx' in wanted:
        subprocess.run(['/opt/nginx/sbin/nginx', '-c', '/tools/nginx-matrix.conf'], stdin=subprocess.DEVNULL,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True)
        time.sleep(1)
    sources = sorted(glob.glob(f'{MEDIA}/*'))
    # MATRIX_SOURCES=a,b limits the run to those source names (without extension).
    pick = [n for n in os.environ.get('MATRIX_SOURCES', '').split(',') if n]
    if pick:
        sources = [s for s in sources if os.path.splitext(os.path.basename(s))[0] in pick or os.path.basename(s) in pick]
    for src in sources:
        tag = os.path.splitext(os.path.basename(src))[0]
        print(tag, flush=True)
        for t in wanted:
            TOOLS[t](tag, src)
    if 'nginx' in wanted:
        subprocess.run(['/opt/nginx/sbin/nginx', '-c', '/tools/nginx-matrix.conf', '-s', 'quit'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    print(f'\nmade {len(made)}, skipped {len(skipped)}')
    with open(os.path.join(OUT, 'matrix-skipped.txt'), 'w') as f:
        f.write(''.join(f'{n}\t{why}\n' for n, why in skipped))


if __name__ == '__main__':
    main()
