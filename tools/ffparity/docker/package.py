"""Runs inside the xdm-packagers image: packages each /media/<name>.mp4 into HLS with TS segments with
every packager, one harness case folder per (packager, file) under /out:

  pk_nginxvod_<name>          nginx-vod-module defaults: 10 s segments cut by time (mid-GOP)
  pk_nginxvod_aligned_<name>  nginx-vod-module with vod_align_segments_to_key_frames on
  pk_bento4_<name>            Bento4 mp42hls, 6 s
  pk_gpac_<name>              GPAC dasher, TS segments, 6 s (audio and video may be separate lists)
  pk_gstreamer_<name>         GStreamer hlssink2 (mpegtsmux), 6 s

Each folder gets the segments, video.txt (and audio.txt) in playlist order, and note.txt. Usage:
  python3 -I /tools/package.py [name ...]
"""
import glob, os, shutil, subprocess, sys, time, urllib.request

MEDIA, OUT = '/media', '/out'


def sh(cmd, **kw):
    r = subprocess.run(cmd, capture_output=True, text=True, **kw)
    if r.returncode != 0:
        raise RuntimeError(f'{cmd[0]} failed: {r.stderr[-800:]}')
    return r.stdout


def playlist_items(path_or_text):
    text = open(path_or_text).read() if os.path.exists(path_or_text) else path_or_text
    return [l.strip() for l in text.splitlines() if l.strip() and not l.startswith('#')]


def media_lists(index):
    """(video segments, audio segments or None) of a master or media playlist, in playlist order."""
    base = os.path.dirname(index)
    text = open(index).read()
    if '#EXT-X-STREAM-INF' not in text:
        return [os.path.join(base, u) for u in playlist_items(text)], None
    audio = None
    for line in text.splitlines():
        if line.startswith('#EXT-X-MEDIA:') and 'TYPE=AUDIO' in line and 'URI="' in line:
            uri = line.split('URI="', 1)[1].split('"', 1)[0]
            audio = os.path.join(base, uri)
    variant = os.path.join(base, playlist_items(text)[0])
    vbase = os.path.dirname(variant)
    video = [os.path.join(vbase, u) for u in playlist_items(variant)]
    alist = None
    if audio:
        abase = os.path.dirname(audio)
        alist = [os.path.join(abase, u) for u in playlist_items(audio)]
    return video, alist


def write_case(case, video, audio, note):
    with open(os.path.join(case, 'video.txt'), 'w') as f:
        f.write(''.join(os.path.relpath(p, case) + '\n' for p in video))
    if audio:
        with open(os.path.join(case, 'audio.txt'), 'w') as f:
            f.write(''.join(os.path.relpath(p, case) + '\n' for p in audio))
    open(os.path.join(case, 'note.txt'), 'w').write(note + '\n')
    print(f'  {os.path.basename(case)}: {len(video)} segments' + (f', audio {len(audio)}' if audio else ''), flush=True)


def fresh(name):
    d = os.path.join(OUT, name)
    shutil.rmtree(d, ignore_errors=True)
    os.makedirs(d)
    return d


def nginxvod(name, location, case_name, note):
    case = fresh(case_name)
    url = f'http://127.0.0.1:8080/{location}/{name}.mp4/'
    index = urllib.request.urlopen(url + 'index.m3u8', timeout=60).read().decode()
    files = []
    for k, uri in enumerate(playlist_items(index)):
        dest = os.path.join(case, f's{k:04d}.ts')
        with urllib.request.urlopen(url + uri, timeout=120) as r, open(dest, 'wb') as f:
            shutil.copyfileobj(r, f)
        files.append(dest)
    open(os.path.join(case, 'index.m3u8'), 'w').write(index)
    write_case(case, files, None, note)


def main():
    names = sys.argv[1:] or sorted(os.path.splitext(os.path.basename(p))[0] for p in glob.glob(f'{MEDIA}/*.mp4'))
    # nginx daemonizes: started with its output on our pipes, it would keep them open and the call
    # would never return.
    subprocess.run(['/opt/nginx/sbin/nginx'], stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                   stderr=subprocess.DEVNULL, check=True)
    time.sleep(1)
    for name in names:
        src = f'{MEDIA}/{name}.mp4'
        print(name, flush=True)
        nginxvod(name, 'vod', f'pk_nginxvod_{name}', 'nginx-vod-module defaults: 10 s segments cut by time, mid-GOP')
        nginxvod(name, 'vod-aligned', f'pk_nginxvod_aligned_{name}', 'nginx-vod-module, segments aligned to keyframes')

        case = fresh(f'pk_bento4_{name}')
        sh(['mp42hls', '--segment-duration', '6', '--index-filename', f'{case}/index.m3u8',
            '--segment-filename-template', f'{case}/s%04llu.ts', '--segment-url-template', 's%04llu.ts', src])
        write_case(case, *media_lists(f'{case}/index.m3u8'), 'Bento4 mp42hls, 6 s')

        case = fresh(f'pk_gpac_{name}')
        sh(['gpac', '-i', src, '-o', f'{case}/index.m3u8:segdur=6:muxtype=ts:template=$RepresentationID$_s$Number%04d$'])
        write_case(case, *media_lists(f'{case}/index.m3u8'), 'GPAC dasher, TS segments, 6 s')

        case = fresh(f'pk_gstreamer_{name}')
        sh(['gst-launch-1.0', '-q', 'filesrc', f'location={src}', '!', 'qtdemux', 'name=d',
            'd.video_0', '!', 'queue', '!', 'h264parse', '!', 'hls.video',
            'd.audio_0', '!', 'queue', '!', 'aacparse', '!', 'hls.audio',
            'hlssink2', 'name=hls', f'location={case}/s%05d.ts', f'playlist-location={case}/index.m3u8',
            'target-duration=6', 'max-files=0', 'playlist-length=0'])
        write_case(case, *media_lists(f'{case}/index.m3u8'), 'GStreamer hlssink2 (mpegtsmux), 6 s')
    subprocess.run(['/opt/nginx/sbin/nginx', '-s', 'quit'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


if __name__ == '__main__':
    main()
