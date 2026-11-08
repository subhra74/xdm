#!/usr/bin/env python3
"""Muxes every corpus variant with TransmuxingMuxer and compares a decode of the output with
ffmpeg's decode of the source segments, frame by frame (md5 of each decoded frame).

usage: check.py [variant ...]      (run through check.sh, which sets the environment)
env:   XDM_CORPUS_DIR  corpus made by gen.sh
       XDM_CHECK_OUT   where the muxed .mp4 files and results.json go
       XDM_CHECK_CP    classpath with xdm-core and the Mux harness
       XDM_JAVA        java executable (JDK 25)
"""
import array, collections, glob, json, os, subprocess, sys

S = os.path.dirname(os.path.abspath(__file__))
C = os.environ['XDM_CORPUS_DIR']
OUT = os.environ['XDM_CHECK_OUT']
CP = os.environ['XDM_CHECK_CP']
JAVA = os.environ.get('XDM_JAVA', 'java')
# The noisy picture runs at ~115 Mbit/s: ffmpeg needs a large probe window to find the audio.
PROBE = ['-probesize', '500M', '-analyzeduration', '30M']
# After a discontinuity the audio decoder carries state from the old stream into the new one, so a
# continuous decode is not bit-identical to decoding each half on its own: the first frames after the
# boundary differ, and later ones by rounding. Discontinuity sets are compared as PCM instead: same
# length, and within PCM_TOLERANCE (of 32767) outside a window right after each boundary.
PCM_TOLERANCE = 64
BOUNDARY_WINDOW = 4096

REFERENCE = {'mod_asplit_mono': 'aac_mono_44k', 'mod_pidshift': 'short_seg', 'mod_vsplit_nopts_hevc': 'hevc8',
             # ffmpeg reads a trun without data_offset from the base offset, against ISO 14496-12
             # 8.8.8.3, so it can't decode this variant itself: compare with the source it was made from.
             'f_mod_multitrun': 'f_base', 'f_mod_nobase': 'f_base', 'f_mod_chunked': 'f_base',
             'f_mod_notfdt': 'f_base',
             # Same encode as f_base, written with negative composition offsets and an edit list that
             # by ISO/IEC 14496-12 presents video at the same time (66.7 ms) as f_base; ffmpeg's MP4
             # demuxer reads its own negative-offset output two frames late, so f_base is the reference.
             'f_negcts': 'f_base'}
# Audio MP4 can't carry (ffmpeg -c copy fails on these too): left out, logged; the video must still match.
AUDIO_LEFT_OUT = {'x_h264_pcmbd', 'x_h264_s302m', 'x_h264_truehd'}
# MPEG-1/2 Layer II in MP4 is described as MPEG-1 audio (object type 0x6B), which ffmpeg decodes with its
# floating-point MP3 decoder instead of the fixed-point MP2 one used for the TS source: frames differ by
# rounding (ffmpeg's own -c copy output differs identically), so these are compared as PCM.
DECODER_DIFFERS = {'x_mpeg1_mp2', 'x_mpeg2_mp2'}
DECODER_TOLERANCE = 256
# Variants whose sample entries legitimately differ from ffmpeg's -c copy (see README).
PARITY_SKIP = set()
DISCONTINUOUS = {'disc_pids', 'disc_ts', 'f_disc_res', 'f_disc_same', 'f_disc_renum'}


def segs(d):
    return sorted(glob.glob(os.path.join(d, '*.ts')))


def playlist_files(d, name):
    """The files the downloader would hand the muxer for the media playlist in [d]: the EXT-X-MAP
    init segment(s) and media segments in playlist order, byte ranges cut out to files of their own."""
    files, byterange, next_start = [], None, {}
    cut_dir = os.path.join(OUT, 'ranges', name, os.path.relpath(d, C).replace(os.sep, '_'))
    os.makedirs(cut_dir, exist_ok=True)

    def add(uri, rng):
        path = os.path.join(d, uri)
        if rng is None:
            files.append(path); return
        length, _, start = rng.partition('@')
        start = int(start) if start else next_start.get(uri, 0)
        next_start[uri] = start + int(length)
        piece = os.path.join(cut_dir, f'{len(files):04d}.part')
        with open(path, 'rb') as f:
            f.seek(start); open(piece, 'wb').write(f.read(int(length)))
        files.append(piece)

    for line in open(os.path.join(d, 'index.m3u8')):
        line = line.strip()
        if line.startswith('#EXT-X-MAP:'):
            attrs = dict(a.split('=', 1) for a in line[len('#EXT-X-MAP:'):].split(','))
            add(attrs['URI'].strip('"'), attrs.get('BYTERANGE', '').strip('"') or None)
        elif line.startswith('#EXT-X-BYTERANGE:'):
            byterange = line[len('#EXT-X-BYTERANGE:'):]
        elif line and not line.startswith('#'):
            add(line, byterange); byterange = None
    return files


def spec(name):
    """(video segments, audio segments or None, reference groups [(kinds, inputs)], discontinuous)."""
    if name.startswith('f_'):
        def parts(n):
            base = os.path.join(C, n)
            return [os.path.join(base, p) for p in ('p1', 'p2', 'v', 'a') if os.path.isdir(os.path.join(base, p))] or [base]
        if name == 'f_separate':
            v, a = parts(name)[0], parts(name)[1]
            return playlist_files(v, name), playlist_files(a, name), \
                [('v', [f'{v}/index.m3u8']), ('a', [f'{a}/index.m3u8'])], False
        files = [f for p in parts(name) for f in playlist_files(p, name)]
        ref = REFERENCE.get(name, name)
        return files, None, [('av', [f'{p}/index.m3u8']) for p in parts(ref)], name in DISCONTINUOUS
    if name == 'separate':
        v, a = segs(f'{C}/separate/v'), segs(f'{C}/separate/a')
        return v, a, [('v', v), ('a', a)], False
    if name.startswith('cfg_'):  # audio config changes: p1, p2, p3 back to back
        parts = sorted(glob.glob(f'{C}/{name}/p*'))
        return [f for p in parts for f in segs(p)], None, [('av', segs(p)) for p in parts], True
    if name in ('disc_pids', 'disc_ts'):  # MPEG-TS discontinuity sets
        p1, p2 = segs(f'{C}/{name}/p1'), segs(f'{C}/{name}/p2')
        return p1 + p2, None, [('av', p1), ('av', p2)], True
    src = REFERENCE.get(name) or ('base' if name.startswith('mod_') else None)
    s = segs(f'{C}/{name}')
    return s, None, [('av', segs(f'{C}/{src}') if src else s)], name == 'mod_pidshift'


def hashes(inputs, kind):
    out = []
    for inp in inputs:
        hls = ['-allowed_extensions', 'ALL'] if inp.endswith('.m3u8') else []
        # autoscale off: a mid-stream resolution change is hashed at its own size.
        r = subprocess.run(['ffmpeg', '-v', 'quiet', *PROBE, *hls, '-i', inp, '-an' if kind == 'v' else '-vn',
                            '-fps_mode', 'passthrough', '-noautoscale', '-f', 'framemd5', '-'],
                           capture_output=True, text=True)
        out += [l.rsplit(',', 1)[1].strip() for l in r.stdout.splitlines() if l and not l.startswith('#')]
    return out


def pcm(inp):
    hls = ['-allowed_extensions', 'ALL'] if inp.endswith('.m3u8') else []
    r = subprocess.run(['ffmpeg', '-v', 'quiet', *PROBE, *hls, '-i', inp, '-vn', '-ac', '1', '-f', 's16le', '-'],
                       capture_output=True)
    return array.array('h', r.stdout)


def compare_pcm(ref_inputs, out):
    """Largest sample difference outside the boundary windows; None if the lengths differ."""
    ref, boundaries = array.array('h'), []
    for inp in ref_inputs:
        boundaries.append(len(ref)); ref.extend(pcm(inp))
    got = pcm(out)
    if len(got) != len(ref):
        return None
    skip = set()
    for b in boundaries[1:]:
        skip.update(range(b, min(b + BOUNDARY_WINDOW, len(ref))))
    return max((abs(ref[i] - got[i]) for i in range(len(ref)) if i not in skip), default=0)


def cli_frames(inp, kind):
    """Decoded frames of the first audio ('a') or video ('v') stream as the ffmpeg CLI sees them, from
    ashowinfo/showinfo: (pts_time, rate, channels) for audio (before any resampling), (pts_time,) for
    video. The CLI shifts every stream of a file by the same start offset, so A/V offsets compare."""
    hls = ['-allowed_extensions', 'ALL'] if inp.endswith('.m3u8') else []
    filt = ['-vn', '-af', 'ashowinfo'] if kind == 'a' else ['-an', '-vf', 'showinfo']
    r = subprocess.run(['ffmpeg', '-hide_banner', '-v', 'info', *PROBE, *hls, '-i', inp, *filt, '-f', 'null', '-'],
                       capture_output=True, text=True)
    tag = 'ashowinfo' if kind == 'a' else 'Parsed_showinfo'
    out = []
    for line in r.stderr.splitlines():
        if tag not in line or 'pts_time:' not in line:
            continue
        f = dict(kv.split(':', 1) for kv in line.split('] ', 1)[1].split() if ':' in kv and not kv.startswith('['))
        t = float(f['pts_time'])
        out.append((t, int(f['rate']), int(f['channels'])) if kind == 'a' else (t,))
    return out


def pcm_window(inp, start, duration, rate):
    """Mono PCM of [inp] from [start] for [duration] s at [rate]; frames already at [rate] pass unchanged."""
    hls = ['-allowed_extensions', 'ALL'] if inp.endswith('.m3u8') else []
    r = subprocess.run(['ffmpeg', '-v', 'quiet', *PROBE, *hls, '-ss', f'{start:.6f}', '-i', inp, '-t', f'{duration:.6f}',
                        '-vn', '-ar', str(rate), '-ac', '1', '-f', 's16le', '-'], capture_output=True)
    return array.array('h', r.stdout)


def best_alignment(window, ref, max_shift):
    """(largest sample difference, shift) of [window] placed inside [ref] at its best shift."""
    best = (1 << 30, 0)
    for off in range(0, min(max_shift, len(ref) - len(window)) + 1):
        d = 0
        for i in range(0, len(window), 61):
            d = max(d, abs(window[i] - ref[i + off]))
            if d >= best[0]:
                break
        if d < best[0]:
            best = (d, off)
            if d <= 2:
                break
    return best


def packet_times(inp, kind):
    """Packet PTS (seconds) of the first audio ('a') or video ('v') stream, in stream order (video
    sorted into presentation order): container timestamps, no decoder involved."""
    hls = ['-allowed_extensions', 'ALL'] if inp.endswith('.m3u8') else []
    r = subprocess.run(['ffprobe', '-v', 'quiet', *PROBE, *hls, '-select_streams', f'{kind}:0', '-show_entries',
                        'packet=pts_time', '-of', 'csv=p=0', inp], capture_output=True, text=True)
    t = [float(x.split(',')[0]) for x in r.stdout.split() if x.split(',')[0] not in ('', 'N/A')]
    return sorted(t) if kind == 'v' else t


def timing_issues(refgroups, out):
    """Packet timing for variants without discontinuities: each stream's packet timestamps, measured
    from the earliest packet of its file (so A/V offsets count), must match the source's within 2 ms."""
    issues = []
    for kind in ('v', 'a'):
        inputs = [f[0] if f[0].endswith('.m3u8') else 'concat:' + '|'.join(f) for k, f in refgroups if kind in k]
        if len(inputs) != 1:
            continue
        ref, got = packet_times(inputs[0], kind), packet_times(out, kind)
        if not ref or not got:
            continue
        ref0 = min(ref[0], *(packet_times(inputs[0], 'a' if kind == 'v' else 'v')[:1] or [ref[0]]))
        got0 = min(got[0], *(packet_times(out, 'a' if kind == 'v' else 'v')[:1] or [got[0]]))
        if len(ref) != len(got):
            issues.append(f'{kind}: {len(got)} packets, source has {len(ref)}'); continue
        worst = max(abs((g - got0) - (r - ref0)) for r, g in zip(ref, got))
        if worst > 0.002:
            issues.append(f'{kind}: packet times off by up to {worst * 1000:.1f} ms')
    return issues


def box_facts(path):
    """Per-handler facts of an MP4 that ffmpeg's muxer and ours should agree on."""
    import struct
    sys.path.insert(0, S)
    import fmp4mod as m
    moov = [b for b in m.parse(open(path, 'rb').read()) if b.type == b'moov'][0]
    facts = {}
    for trak in moov.find(b'trak'):
        mdia = trak.find(b'mdia')[0]
        hdlr = mdia.find(b'hdlr')[0].payload[8:12].decode()
        stbl = mdia.find(b'minf')[0].find(b'stbl')[0]
        entry = stbl.find(b'stsd')[0].payload[8:]
        kids, p = {}, 8 + (78 if hdlr == 'vide' else 28)
        while p + 8 <= len(entry):
            size = struct.unpack('>I', entry[p:p + 4])[0]
            if size < 8:
                break
            kids[entry[p + 4:p + 8].decode('latin1')] = entry[p + 8:p + size]
            p += size
        f = {}
        stss = stbl.find(b'stss')
        f['sync samples'] = struct.unpack('>I', stss[0].payload[4:8])[0] if stss else 'all'
        if hdlr == 'vide':
            tkhd = trak.find(b'tkhd')[0].payload
            f['tkhd width'] = struct.unpack('>I', tkhd[-8:-4])[0] >> 16
            f['pasp'] = struct.unpack('>II', kids['pasp'][:8]) if 'pasp' in kids else None
            f['colr'] = kids['colr'][:10].hex() if 'colr' in kids else None
            if 'avcC' in kids:
                f['avcC'] = kids['avcC'].hex()
            if 'hvcC' in kids:
                h = kids['hvcC']
                f['hvcC chroma/depth/layers'] = (h[16] & 3, (h[17] & 7) + 8, (h[18] & 7) + 8, h[21] >> 2)
        else:
            if 'esds' in kids:
                e = kids['esds']
                f['esds dsi'] = e[e.find(b'\x05\x80') + 5:][:e[e.find(b'\x05\x80') + 4]].hex() if b'\x05\x80' in e else None
        facts.setdefault(hdlr, f)
    return facts


def mdat_switches(path):
    """How often the chunk order in the mdat switches between tracks (interleaving)."""
    import struct
    sys.path.insert(0, S)
    import fmp4mod as m
    moov = [b for b in m.parse(open(path, 'rb').read()) if b.type == b'moov'][0]
    ev = []
    for k, trak in enumerate(moov.find(b'trak')):
        stbl = trak.find(b'mdia')[0].find(b'minf')[0].find(b'stbl')[0]
        co = stbl.find(b'stco') or stbl.find(b'co64')
        pl = co[0].payload
        n = struct.unpack('>I', pl[4:8])[0]
        w = 4 if co[0].type == b'stco' else 8
        ev += [(int.from_bytes(pl[8 + w * i:8 + w * (i + 1)], 'big'), k) for i in range(n)]
    seq = [k for _, k in sorted(ev)]
    return sum(1 for a, b in zip(seq, seq[1:]) if a != b)


def parity_issues(refgroups, out, name):
    """ffmpeg -c copy of the same input against ours, box by box (see box_facts)."""
    ff = out + '.ffmpeg.mp4'
    inputs = []
    for k, f in refgroups:
        inputs += ['-allowed_extensions', 'ALL', '-i', f[0]] if f[0].endswith('.m3u8') else ['-i', 'concat:' + '|'.join(f)]
    maps = sum((['-map', f'{i}:v?', '-map', f'{i}:a?'] for i in range(len(refgroups))), [])
    r = subprocess.run(['ffmpeg', '-v', 'quiet', '-y', *PROBE, *inputs, *maps, '-c', 'copy', ff], capture_output=True)
    if r.returncode != 0 or not os.path.exists(ff):
        return []  # ffmpeg can't write this input as MP4: nothing to compare against
    try:
        theirs, ours = box_facts(ff), box_facts(out)
        issues = []
        for hdlr in sorted(set(theirs) | set(ours)):
            for key in sorted(set(theirs.get(hdlr, {})) | set(ours.get(hdlr, {}))):
                a, b = theirs.get(hdlr, {}).get(key), ours.get(hdlr, {}).get(key)
                if a != b:
                    issues.append(f'{hdlr} {key}: ffmpeg {a}, ours {b}')
        if len(refgroups) > 1 and mdat_switches(out) < 3:
            issues.append(f'separate streams not interleaved ({mdat_switches(out)} switches; ffmpeg {mdat_switches(ff)})')
        return issues
    finally:
        os.remove(ff)


def compare_config_runs(ref_inputs, out):
    """Discontinuity and config-change sets (parts back to back), checked per part:
    - packets: ours has exactly each part's audio and video packets, in order;
    - A/V: each part's first audio packet sits at the same offset from its first video frame as in
      the source (container timestamps);
    - config: decoded audio changes rate/channels where the parts do (a decoder may label one frame
      at a boundary with the old config, so +-1 frame);
    - content: a PCM window inside each part matches the part decoded on its own, at its own rate.
    """
    issues = []
    ga, gv = packet_times(out, 'a'), packet_times(out, 'v')
    parts = [(packet_times(inp, 'a'), packet_times(inp, 'v')) for inp in ref_inputs]
    if len(ga) != sum(len(p[0]) for p in parts) or len(gv) != sum(len(p[1]) for p in parts):
        issues.append(f'packets: audio {len(ga)} vs {sum(len(p[0]) for p in parts)}, '
                      f'video {len(gv)} vs {sum(len(p[1]) for p in parts)}')
        return issues
    apos = vpos = 0
    for k, (pa, pv) in enumerate(parts):
        if pa and pv:
            off_ref = pa[0] - pv[0]
            off_got = ga[apos] - gv[vpos]
            if abs(off_ref - off_got) > 0.002:
                issues.append(f'part {k + 1}: audio starts {off_got - off_ref:+.3f}s off its video')
        apos += len(pa); vpos += len(pv)

    got = cli_frames(out, 'a')
    runs = []  # [start time, rate, channels, frames]
    for t, r, c in got:
        if runs and runs[-1][1] == r and runs[-1][2] == c:
            runs[-1][3] += 1
        else:
            runs.append([t, r, c, 1])
    refs = [cli_frames(inp, 'a') for inp in ref_inputs]
    expected = []  # (config, [part indices]); neighbouring parts with one config form one run
    for k, ra in enumerate(refs):
        if not ra:
            return issues + [f'part {k + 1}: reference decoded no audio']
        cfg = (ra[0][1], ra[0][2])
        if expected and expected[-1][0] == cfg:
            expected[-1][1].append(k)
        else:
            expected.append((cfg, [k]))
    if [tuple(r[1:3]) for r in runs] != [e[0] for e in expected]:
        return issues + [f'config runs {[tuple(r[1:3]) for r in runs]} != parts {[e[0] for e in expected]}']
    for run, (cfg, ks) in zip(runs, expected):
        frames = sum(len(refs[k]) for k in ks)
        if abs(run[3] - frames) > 1 * len(ks) + 1:
            issues.append(f'parts {[k + 1 for k in ks]}: {run[3]} decoded audio frames, source {frames}')
        first = ks[0]
        ra = refs[first]
        part_len = ra[-1][0] - ra[0][0]
        if part_len > 1.0:
            window = pcm_window(out, run[0] + 0.2, part_len - 0.6, cfg[0])
            ref_pcm = pcm_window(ref_inputs[first], ra[0][0], part_len + 0.2, cfg[0])
            diff, _ = best_alignment(window, ref_pcm, int(0.4 * cfg[0]))
            if diff > PCM_TOLERANCE:
                issues.append(f'part {first + 1}: PCM differs by up to {diff}/32767')
    return issues


def compare(ref, got):
    matched = sum((collections.Counter(ref) & collections.Counter(got)).values())
    return {'ref': len(ref), 'out': len(got), 'matched': matched, 'in_order': ref == got}


def ref_inputs_flat(refgroups):
    return [f[0] if f[0].endswith('.m3u8') else 'concat:' + '|'.join(f) for k, f in refgroups if 'a' in k]


def verdict(row, disc):
    if row['mux'] != 'OK' or row.get('decode_errors') or row.get('timing_issues') or row.get('parity_issues'):
        return 'FAIL'
    if row['variant'] in DECODER_DIFFERS:
        v, diff = row.get('v'), row.get('pcm_max_diff')
        return 'PASS~' if v and v['in_order'] and diff is not None and diff <= DECODER_TOLERANCE else 'FAIL'
    if row['variant'] in AUDIO_LEFT_OUT:
        v, a = row.get('v'), row.get('a')
        return 'PASS-' if v and v['in_order'] and v['ref'] and a and a['out'] == 0 else 'FAIL'
    if row.get('config_issues'):
        return 'FAIL'
    if 'config_issues' in row and row['name_is_cfg']:
        v = row.get('v')
        return 'PASS' if v and v['in_order'] else 'FAIL'
    v, a = row.get('v'), row.get('a')
    if not v or not v['ref'] or any(x is not None and x['ref'] == 0 and x['out'] > 0 for x in (a,)):
        return 'FAIL'  # nothing compared: the reference decode failed
    if v and not v['in_order']:
        return 'FAIL'
    if a and not a['in_order']:
        diff = row.get('pcm_max_diff')
        if disc and diff is not None and diff <= PCM_TOLERANCE:
            return 'PASS*'
        return 'FAIL'
    return 'PASS'


def fmt(x):
    return '-' if not x or not x['ref'] else f"{x['matched']}/{x['ref']} (out {x['out']})"


def main():
    names = sys.argv[1:] or sorted(n for n in os.listdir(C) if os.path.isdir(os.path.join(C, n)))
    os.makedirs(OUT, exist_ok=True)
    rows = []
    failed = 0
    for name in names:
        v, a, refgroups, disc = spec(name)
        out = f'{OUT}/{name}.mp4'
        open(f'{OUT}/{name}.v.txt', 'w').write('\n'.join(v))
        cmd = [JAVA, '-cp', CP, 'Mux', out, str(disc).lower(), f'{OUT}/{name}.v.txt']
        if a:
            open(f'{OUT}/{name}.a.txt', 'w').write('\n'.join(a))
            cmd.append(f'{OUT}/{name}.a.txt')
        r = subprocess.run(cmd, capture_output=True, text=True)
        row = {'variant': name, 'mux': 'OK' if r.returncode == 0 and os.path.exists(out) else 'FAIL',
               'name_is_cfg': name.startswith('cfg_')}
        if row['mux'] == 'OK':
            for kind in ('v', 'a'):
                # TS segments are decoded concatenated; fMP4 through ffmpeg's HLS demuxer (the playlist).
                ref_inputs = [f[0] if f[0].endswith('.m3u8') else 'concat:' + '|'.join(f) for k, f in refgroups if kind in k]
                if ref_inputs:
                    row[kind] = compare(hashes(ref_inputs, kind), hashes([out], kind))
                    if kind == 'a' and (name.startswith('cfg_') or name in DISCONTINUOUS):
                        row['config_issues'] = compare_config_runs(ref_inputs_flat(refgroups), out)
                    if kind == 'a' and name in DECODER_DIFFERS:
                        row['pcm_max_diff'] = compare_pcm(ref_inputs, out)
                    if kind == 'a' and disc and not row[kind]['in_order']:
                        row['pcm_max_diff'] = compare_pcm(ref_inputs, out)
            if not disc and name not in AUDIO_LEFT_OUT and name != 'separate' and name != 'f_separate':
                row['timing_issues'] = timing_issues(refgroups, out)
            if not disc and name not in PARITY_SKIP:
                row['parity_issues'] = parity_issues(refgroups, out, name)
            e = subprocess.run(['ffmpeg', '-v', 'error', *PROBE, '-i', out, '-f', 'null', '-'], capture_output=True, text=True)
            row['decode_errors'] = len(e.stderr.strip().splitlines())
            src_files = (v or []) + (a or [])
            row['size_ratio'] = round(os.path.getsize(out) / sum(os.path.getsize(f) for f in src_files), 3)
        row['verdict'] = verdict(row, disc)
        failed += row['verdict'] == 'FAIL'
        rows.append(row)
        print(f"{row['verdict']:6} {name:22} video {fmt(row.get('v')):22} audio {fmt(row.get('a')):22} "
              f"errors {row.get('decode_errors', '-'):<4} size {row.get('size_ratio', '-')}"
              + (f" pcm-diff {row['pcm_max_diff']}" if 'pcm_max_diff' in row else '')
              + ''.join(f"\n         {x}" for x in row.get('config_issues') or [])
              + ''.join(f"\n         {x}" for x in row.get('timing_issues') or [])
              + ''.join(f"\n         parity: {x}" for x in row.get('parity_issues') or []), flush=True)
    json.dump(rows, open(f'{OUT}/results.json', 'w'), indent=1)
    print(f'{len(rows) - failed}/{len(rows)} passed ({OUT}/results.json)')
    sys.exit(1 if failed else 0)


if __name__ == '__main__':
    main()
