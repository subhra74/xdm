#!/usr/bin/env python3
"""Checks the HLS matrix cases (docker/matrix.py) with our muxer, both ways the user can pick:
"Save as MP4 where possible" on (remux, or the fallback ladder when a stream can't be carried) and off
(TS kept as .ts). Per case and mode: OK/FAIL, the container written and how (REMUXED / TS_FALLBACK /
AS_IS), video and audio frames out against the source lists, and ffmpeg decode errors.

  python3 -I matrix_check.py <cases dir> <out dir> [case-name regex ...]
Env: XDM_CHECK_CP (classpath with Mux and xdm-core), XDM_JAVA.
"""
import json, os, re, subprocess, sys

JAVA = os.environ.get('XDM_JAVA', 'java')
CP = os.environ['XDM_CHECK_CP']


def run(cmd, env=None):
    return subprocess.run(cmd, capture_output=True, text=True, env=env)


def read_list(case, name):
    p = os.path.join(case, name)
    if not os.path.exists(p):
        return []
    return [os.path.join(case, l.strip()) for l in open(p) if l.strip() and not l.startswith('#')]


def boxes(path):
    with open(path, 'rb') as f:
        head = f.read(64)
    return head[4:8]


def counts(path):
    """Packets per codec type of one readable file."""
    r = run(['ffprobe', '-v', 'error', '-count_packets', '-show_entries', 'stream=index,codec_type,codec_name,nb_read_packets', '-of', 'json', path])
    try:
        streams = json.loads(r.stdout).get('streams', [])
    except json.JSONDecodeError:
        return {}, {}
    n, names = {}, {}
    for s in streams:
        t = s.get('codec_type')
        if t in ('video', 'audio') and s.get('nb_read_packets') not in (None, 'N/A'):
            n[t] = n.get(t, 0) + int(s['nb_read_packets'])
            names.setdefault(t, s.get('codec_name'))
    return n, names


def list_counts(files, scratch):
    """Packets per codec type of a segment list: an fMP4 list (init then fragments) joined first, else per file."""
    if not files:
        return {}, {}
    # fMP4: an init (moov) then fragments (moof); standalone MP4 parts each have a moov, often at the end.
    if len(files) > 1 and b'moov' in open(files[0], 'rb').read(1 << 16) and b'moof' in open(files[1], 'rb').read(1 << 16):
        with open(scratch, 'wb') as out:
            for f in files:
                out.write(open(f, 'rb').read())
        return counts(scratch)
    total, names = {}, {}
    for f in files:
        n, nm = counts(f)
        for k, v in n.items():
            total[k] = total.get(k, 0) + v
        for k, v in nm.items():
            names.setdefault(k, v)
    return total, names


def decode_errors(path):
    r = run(['ffmpeg', '-v', 'error', '-i', path, '-map', '0', '-f', 'null', '-'])
    return [l for l in r.stderr.splitlines() if l.strip()]


def check(case, out_dir):
    name = os.path.basename(case)
    video, audio = read_list(case, 'video.txt'), read_list(case, 'audio.txt')
    scratch = os.path.join(out_dir, name + '.src')
    src, src_names = list_counts(video, scratch)
    if audio:
        a, an = list_counts(audio, scratch)
        for k, v in a.items():
            src[k] = src.get(k, 0) + v
        for k, v in an.items():
            src_names.setdefault(k, v)
    if os.path.exists(scratch):
        os.remove(scratch)
    rows = []
    for keep in (False, True):
        lv, la = os.path.join(out_dir, name + '.v.txt'), os.path.join(out_dir, name + '.a.txt')
        open(lv, 'w').write('\n'.join(video) + '\n')
        args = [JAVA, '-cp', CP, 'Mux', os.path.join(out_dir, f'{name}.{"keep" if keep else "mp4"}.out'), 'false', lv]
        if audio:
            open(la, 'w').write('\n'.join(audio) + '\n')
            args.append(la)
        env = dict(os.environ, XDM_KEEP_ORIGINAL='1' if keep else '0')
        r = run(args, env)
        result = next((l for l in r.stdout.splitlines() if l.startswith('RESULT ')), 'RESULT ? ?').split(' ', 3)
        ok = r.returncode == 0
        out = args[4]
        row = {'case': name, 'mode': 'keep' if keep else 'mp4', 'ok': ok, 'container': result[1], 'kind': result[2],
               'error': result[3] if len(result) > 3 else '', 'src': src, 'src_codecs': src_names}
        if ok and os.path.exists(out):
            row['out'], row['out_codecs'] = counts(out)
            row['decode_errors'] = len(decode_errors(out))
            os.remove(out)
        rows.append(row)
    return rows


def main():
    cases_dir, out_dir = sys.argv[1], sys.argv[2]
    only = sys.argv[3:]
    os.makedirs(out_dir, exist_ok=True)
    rows = []
    for name in sorted(os.listdir(cases_dir)):
        case = os.path.join(cases_dir, name)
        if not os.path.isdir(case) or not os.path.exists(os.path.join(case, 'video.txt')):
            continue
        if only and not any(re.search(o, name) for o in only):
            continue
        for row in check(case, out_dir):
            rows.append(row)
            s, o = row['src'], row.get('out', {})
            frames = ' '.join(f"{t[0]} {o.get(t, 0)}/{s.get(t, 0)}" for t in ('video', 'audio') if t in s or t in o)
            codecs = '+'.join(v for v in (row['src_codecs'].get('video'), row['src_codecs'].get('audio')) if v)
            lost = any(o.get(t, 0) < s.get(t, 0) for t in s) if row['ok'] else False
            verdict = ('FAIL' if not row['ok'] else 'LOST' if lost else 'ERRS' if row.get('decode_errors') else 'ok')
            print(f"{verdict:4} {name:34} {row['mode']:4} {codecs:14} -> {row['container']:4} {row['kind']:11} {frames}"
                  + (f" decode-errors {row['decode_errors']}" if row.get('decode_errors') else '')
                  + (f"  [{row['error'][:110]}]" if not row['ok'] else ''), flush=True)
    json.dump(rows, open(os.path.join(out_dir, 'matrix-results.json'), 'w'), indent=1)


if __name__ == '__main__':
    main()
