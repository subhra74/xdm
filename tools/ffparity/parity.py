#!/usr/bin/env python3
"""ffmpeg parity: muxes each case with TransmuxingMuxer and with ffmpeg stream copy, then compares the
MP4s decision by decision: tracks, sample entries and decoder configs, which samples are there,
keyframe flags, presentation times, layout, and how a decoder that shows every frame
(`-flags2 showall`, as hardware decoders do) fares.

Two ffmpeg references (XDM_PARITY_REFS, default both, the first is the main one):
  concat   (main) what users run by hand: the concat demuxer, `-f concat -safe 0 -i <list> -vcodec copy
           -acodec copy`. Each segment is opened as its own file and starts where the previous one
           ended (by its duration): parsers restart per file, timestamp jumps never reach the muxer.
  concatf  the command the app runs when "Use ffmpeg for muxing" is on (`FFmpegMuxer.command`):
           `-i concatf:<list> [-i concatf:<audio list>] -map 0 [-map 1] -c copy -movflags +faststart`.
           The segments are read as one byte stream: one demuxer, one parser across segment ends.

Every difference is a failure unless allow.json lists it: "deliberate" (we differ on purpose, the
reason says why) or "open" (a decision not taken yet: reported as WARN).

usage: parity.py [--corpus] [--strict] [--clean] [case ...]   (run through parity.sh, which sets the environment)
  --strict   also decode strictly against the main reference (strict.py): every frame output with error
             detection, and through VideoToolbox (hardware, set up from avcC/hvcC as players are); frames
             that match no frame of a normal decode of the source are garbage (green/grey flashes)
  --native   also play both files through the system's own player stack (macOS: AVFoundation, as
             QuickTime/Safari/iOS; native/avf_check.swift): playable, frames decoded, held frames (stutter),
             decode errors ("Cannot Decode" stops playback there)
  --clean    delete the muxed MP4s after each case (large cases)
Allow-list entries may name the references they hold for ("refs": ["concat"]); default all.
  case       a folder with video.txt [audio.txt] [disc], or with --corpus a transmux-corpus variant
env:   XDM_PARITY_CASES  folder of case folders (default $TMPDIR/xdm-parity-cases, made by cases.sh)
       XDM_PARITY_OUT    outputs, reports and results.json (default $TMPDIR/xdm-parity-out)
       XDM_CHECK_CP      classpath with xdm-core and the Mux harness
       XDM_JAVA          java from JDK 25
       XDM_CORPUS_DIR    the transmux corpus, for --corpus
       XDM_PARITY_REFS   comma-separated references (concatf,concat)
"""
import difflib, fnmatch, json, os, subprocess, sys

S = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, S)
import mp4read  # noqa: E402
import strict  # noqa: E402

TMP = os.environ.get('TMPDIR', '/tmp')
CASES = os.environ.get('XDM_PARITY_CASES', os.path.join(TMP, 'xdm-parity-cases'))
OUT = os.environ.get('XDM_PARITY_OUT', os.path.join(TMP, 'xdm-parity-out'))
CP = os.environ.get('XDM_CHECK_CP', '')
JAVA = os.environ.get('XDM_JAVA', 'java')
FFMPEG = os.environ.get('XDM_FFMPEG', 'ffmpeg')
# Presentation times may differ by this much (movie-timescale rounding of the edit lists).
TIME_TOLERANCE = 0.0015
# The first reference is the main one (the concat demuxer: what users run by hand); the others are
# second opinions. All of them gate the run.
REFS = [r for r in os.environ.get('XDM_PARITY_REFS', 'concat,concatf').split(',') if r]


# ---- cases ----

DISCONTINUITY = '#EXT-X-DISCONTINUITY'


class Case:
    """[video]/[audio]: segment files. [video_lines]/[audio_lines]: the same with `#EXT-X-DISCONTINUITY`
    markers where the playlist has them (handed to our muxer, which gets them from the playlist in
    the app; ffmpeg never sees them, as with the app's ffmpeg command)."""

    def __init__(self, name, video, audio, disc, note='', video_lines=None, audio_lines=None):
        self.name, self.video, self.audio, self.disc, self.note = name, video, audio, disc, note
        self.video_lines = video_lines or video
        self.audio_lines = audio_lines or audio


def load_case(name):
    d = name if os.path.isdir(name) else os.path.join(CASES, name)

    def lines(f):
        p = os.path.join(d, f)
        if not os.path.exists(p):
            return None
        return [x if x.startswith('#') or os.path.isabs(x) else os.path.join(d, x)
                for x in (l.strip() for l in open(p)) if x]

    def files(ls):
        return None if ls is None else [x for x in ls if not x.startswith('#')]

    note = open(os.path.join(d, 'note.txt')).read().strip() if os.path.exists(os.path.join(d, 'note.txt')) else ''
    v, a = lines('video.txt'), lines('audio.txt')
    disc = os.path.exists(os.path.join(d, 'disc')) or any(DISCONTINUITY in x for x in (v or []) + (a or []))
    return Case(os.path.basename(os.path.normpath(d)), files(v), files(a), disc, note, v, a)


def corpus_case(name):
    os.environ.setdefault('XDM_CORPUS_DIR', os.path.join(TMP, 'xdm-transmux-corpus'))
    os.environ.setdefault('XDM_CHECK_OUT', os.path.join(OUT, 'corpus'))
    os.environ.setdefault('XDM_CHECK_CP', CP)
    sys.path.insert(0, os.path.join(S, '..', 'transmux-corpus'))
    import check  # noqa: E402
    v, a, _, disc = check.spec(name)
    return Case('corpus/' + name, v, a, disc)


# ---- the two muxes ----

def write_list(files, path):
    open(path, 'w').write(''.join(f + '\n' for f in files))
    return path


def mux_ours(case, base):
    out = base + '.xdm.mp4'
    cmd = [JAVA, '-cp', CP, 'Mux', out, str(case.disc).lower(), write_list(case.video_lines, base + '.v.txt')]
    if case.audio:
        cmd.append(write_list(case.audio_lines, base + '.a.txt'))
    r = subprocess.run(cmd, capture_output=True, text=True)
    log = (r.stdout + r.stderr).splitlines()
    open(base + '.xdm.log', 'w').write(r.stdout + r.stderr)   # for the safety-net lines ("Remux check: ...")
    return (out if r.returncode == 0 and os.path.exists(out) else None), log


def write_ffconcat(files, path):
    """A concat demuxer script: one `file` line per segment, quotes escaped as the demuxer expects."""
    lines = ['ffconcat version 1.0'] + ["file '" + f.replace("'", "'\\''") + "'" for f in files]
    open(path, 'w').write('\n'.join(lines) + '\n')
    return path


def mux_ffmpeg(case, base, ref):
    """[ref] 'concatf': FFmpegMuxer.command with the lists FFmpegMuxer writes (video first, then audio).
    'concat': the concat demuxer with -vcodec copy -acodec copy and ffmpeg's own stream selection."""
    out = f'{base}.{ref}.mp4'
    cmd = [FFMPEG, '-hide_banner', '-nostdin', '-y']
    tracks = [case.video] + ([case.audio] if case.audio else [])
    if ref == 'concatf':
        for k, files in enumerate(tracks):
            cmd += ['-i', 'concatf:' + write_list(files, f'{base}.ffv{k}.txt')]
        for i in range(len(tracks)):
            cmd += ['-map', str(i)]
        cmd += ['-c', 'copy', '-movflags', '+faststart', '-f', 'mp4', out]
    elif ref == 'concat':
        for k, files in enumerate(tracks):
            cmd += ['-f', 'concat', '-safe', '0', '-i', write_ffconcat(files, f'{base}.ffc{k}.txt')]
        cmd += ['-vcodec', 'copy', '-acodec', 'copy', '-f', 'mp4', out]
    else:
        raise ValueError(f'unknown reference {ref}')
    r = subprocess.run(cmd, capture_output=True, text=True)
    warnings = [l for l in r.stderr.splitlines() if l.startswith('[') or 'rror' in l]
    return (out if r.returncode == 0 and os.path.exists(out) else None), warnings


def showall_errors(path):
    """Decoder error lines when every frame is output (-flags2 showall), as hardware decoders do."""
    r = subprocess.run([FFMPEG, '-hide_banner', '-v', 'error', '-flags2', 'showall', '-i', path,
                        '-map', '0:v?', '-f', 'null', '-'], capture_output=True, text=True)
    return len([l for l in r.stderr.splitlines() if l.strip()])


# ---- comparison ----

class Issues:
    def __init__(self):
        self.items = []   # (check id, message)
        self.dropped_at_section_start = 0   # video samples ffmpeg keeps that ours drops before a section's keyframe

    def add(self, check, msg):
        self.items.append((check, msg))


def compare_tracks(ref, ours, issues):
    pairs = []
    for kind, label in (('vide', 'video'), ('soun', 'audio')):
        a = [t for t in ref.tracks if t.handler == kind]
        b = [t for t in ours.tracks if t.handler == kind]
        if len(a) != len(b):
            issues.add('tracks', f'{label} tracks: ffmpeg {len(a)}, ours {len(b)}')
        pairs += [(f'{label}{"" if i == 0 else i + 1}', x, y) for i, (x, y) in enumerate(zip(a, b))]
    others = sorted({t.handler for t in ref.tracks + ours.tracks} - {'vide', 'soun'})
    for h in others:
        n1 = sum(t.handler == h for t in ref.tracks)
        n2 = sum(t.handler == h for t in ours.tracks)
        if n1 != n2:
            issues.add('tracks', f'{h} tracks: ffmpeg {n1}, ours {n2}')
    return pairs


def compare_entries(label, rt, ot, issues):
    if len(rt.entries) != len(ot.entries):
        issues.add(f'{label}.entry.count', f'{label}: {len(rt.entries)} sample entries in ffmpeg\'s, ours {len(ot.entries)}')
    if not rt.entries or not ot.entries:
        return
    re, oe = rt.entries[0], ot.entries[0]
    if re.fourcc != oe.fourcc:
        issues.add(f'{label}.entry.fourcc', f'{label}: sample entry ffmpeg {re.fourcc}, ours {oe.fourcc}')
    for k in sorted(set(re.fields) | set(oe.fields)):
        if re.fields.get(k) != oe.fields.get(k):
            issues.add(f'{label}.entry.{k}', f'{label} {k}: ffmpeg {re.fields.get(k)}, ours {oe.fields.get(k)}')
    rc, oc = mp4read.describe_config(re), mp4read.describe_config(oe)
    for k in sorted(set(rc) | set(oc)):
        if rc.get(k) != oc.get(k):
            issues.add(f'{label}.config.{k.split()[0]}', f'{label} {k}: ffmpeg {rc.get(k)}, ours {oc.get(k)}')
    if rt.video:
        if rt.tkhd_size != ot.tkhd_size:
            issues.add(f'{label}.tkhd.size', f'{label} display size (tkhd): ffmpeg {rt.tkhd_size}, ours {ot.tkhd_size}')
        if rt.matrix != ot.matrix:
            issues.add(f'{label}.tkhd.matrix', f'{label} matrix: ffmpeg {rt.matrix}, ours {ot.matrix}')


def span(track, samples, movie_ts):
    if not samples:
        return ''
    t = sorted(track.shown_at(s, movie_ts) for s in samples)
    keys = sum(s.key for s in samples)
    return f'{len(samples)} samples ({keys} keyframes), shown {t[0]:.3f}-{t[-1]:.3f} s'


def compare_samples(label, ref, ours, rt, ot, issues, case):
    rh = [mp4read.normalized_hash(rt, ref.sample_bytes(s)) for s in rt.samples]
    oh = [mp4read.normalized_hash(ot, ours.sample_bytes(s)) for s in ot.samples]
    sm = difflib.SequenceMatcher(None, rh, oh, autojunk=False)
    matched = []
    for op, i1, i2, j1, j2 in sm.get_opcodes():
        if op == 'equal':
            matched += list(zip(rt.samples[i1:i2], ot.samples[j1:j2]))
            continue
        where = 'leading' if i1 == 0 and j1 == 0 else 'trailing' if i2 == len(rh) and j2 == len(oh) else 'middle'
        if op == 'insert':
            issues.add(f'{label}.samples.extra.{where}',
                       f'{label}: ours has {where} samples ffmpeg drops: {span(ot, ot.samples[j1:j2], ours.timescale)}')
        elif op == 'delete':
            missing = rt.samples[i1:i2]
            # Non-keyframes right before a keyframe of ours: a section's frames before its first keyframe.
            section_start = where == 'middle' and not any(x.key for x in missing) and j1 < len(ot.samples) and ot.samples[j1].key
            kind = 'section-start' if section_start else where
            if section_start:
                issues.dropped_at_section_start += len(missing)
            issues.add(f'{label}.samples.missing.{kind}',
                       f'{label}: ours lacks {where} samples ffmpeg keeps: {span(rt, missing, ref.timescale)}'
                       + (' (before the keyframe that opens a section)' if section_start else ''))
        else:
            issues.add(f'{label}.samples.changed.{where}',
                       f'{label}: different bytes, ffmpeg {span(rt, rt.samples[i1:i2], ref.timescale)}; '
                       f'ours {span(ot, ot.samples[j1:j2], ours.timescale)}')
    # Keyframe flags of the samples both have.
    extra_keys = [(r, o) for r, o in matched if o.key and not r.key]
    lost_keys = [(r, o) for r, o in matched if r.key and not o.key]
    if extra_keys:
        r, o = extra_keys[0]
        issues.add(f'{label}.samples.keyframe.extra', f'{label}: {len(extra_keys)} samples are keyframes only in ours '
                   f'(first: ours #{o.index + 1} at {ot.shown_at(o, ours.timescale):.3f} s)')
    if lost_keys:
        r, o = lost_keys[0]
        issues.add(f'{label}.samples.keyframe.missing', f'{label}: {len(lost_keys)} keyframes in ffmpeg\'s are not in ours '
                   f'(first: ours #{o.index + 1} at {ot.shown_at(o, ours.timescale):.3f} s)')
    if len(rt.entries) > 1 or len(ot.entries) > 1:
        moved = [(r, o) for r, o in matched if r.entry != o.entry]
        if moved:
            issues.add(f'{label}.samples.entry', f'{label}: {len(moved)} samples use another sample entry than in ffmpeg\'s')
    levels = shift_levels(rt, ot, matched, ref.timescale, ours.timescale)
    return {'ffmpeg': len(rt.samples), 'ours': len(ot.samples), 'matched': len(matched)}, levels


class Level:
    """A run of matched samples, consecutive in our decode order, that ours shows [shift] seconds later
    than ffmpeg's; t0/t1 span their times in ffmpeg's file."""

    def __init__(self, shift):
        self.t0, self.t1, self.shift, self.n = float('inf'), float('-inf'), shift, 0
        self.ref_overlapping = 0   # samples shown at (nearly) the same time as another in ffmpeg's file
        self.ours_overlapping = 0  # the same in ours

    @property
    def ffmpeg_overlap(self):
        """Most of the run is frames ffmpeg's file shows on top of others while ours shows none on top of
        each other: ffmpeg's placement (non-monotonic times clamped, a jump noticed late), not ours."""
        return self.ref_overlapping * 2 >= self.n and self.ours_overlapping == 0

    @property
    def big(self):
        return self.n >= 5


def overlapping(track, movie_ts):
    """Indexes of samples shown within a quarter frame of another sample of the same track."""
    shown = sorted((track.shown_at(s, movie_ts), s.index) for s in track.samples)
    if len(shown) < 2:
        return set()
    steps = sorted(b[0] - a[0] for a, b in zip(shown, shown[1:]) if b[0] > a[0])
    near = (steps[len(steps) // 2] if steps else 0) / 4
    out = set()
    for a, b in zip(shown, shown[1:]):
        if b[0] - a[0] < near:
            out.update((a[1], b[1]))
    return out


def shift_levels(rt, ot, matched, ref_ts, our_ts):
    """How much later ours presents each matched sample than ffmpeg's, as runs of equal shift in our
    decode order (sections are contiguous there even where ffmpeg's presentation times overlap)."""
    tol = max(TIME_TOLERANCE, 2.0 / max(1, rt.timescale), 2.0 / max(1, ot.timescale))
    ref_over, our_over = overlapping(rt, ref_ts), overlapping(ot, our_ts)
    levels = []
    for r, o in sorted(matched, key=lambda p: p[1].index):
        t = rt.shown_at(r, ref_ts)
        d = ot.shown_at(o, our_ts) - t
        if not levels or abs(d - levels[-1].shift) > tol:
            levels.append(Level(d))
        lv = levels[-1]
        lv.t0, lv.t1, lv.n = min(lv.t0, t), max(lv.t1, t), lv.n + 1
        lv.ref_overlapping += r.index in ref_over
        lv.ours_overlapping += o.index in our_over
    return levels


def level_at(levels, t):
    for lv in levels:
        if lv.t0 - 0.05 <= t <= lv.t1 + 0.05:
            return lv
    return None


PRIV_OWNER = b'com.apple.streaming.transportStreamTimestamp\x00'


def first_pts(files):
    """Earliest packet PTS (seconds) of the first segment of a list, as stored in the source. A packed
    audio segment (raw ADTS/AC-3/E-AC-3/MP3 after an ID3 tag) has its start in the tag's PRIV frame,
    which ffprobe doesn't read (it reports 0)."""
    with open(files[0], 'rb') as f:
        head = f.read(512)
    if head.startswith(b'ID3') and PRIV_OWNER in head:
        at = head.index(PRIV_OWNER) + len(PRIV_OWNER)
        return (int.from_bytes(head[at:at + 8], 'big') & ((1 << 33) - 1)) / 90000.0
    r = subprocess.run(['ffprobe', '-v', 'quiet', '-show_entries', 'packet=pts_time', '-of', 'csv=p=0', files[0]],
                       capture_output=True, text=True)
    t = [float(x.strip(',')) for x in r.stdout.split() if x.strip(',') not in ('', 'N/A')]
    return min(t) if t else None


# A/V offsets within this of ffmpeg's count as the same sync (one AAC frame is 21-23 ms).
SYNC_TOLERANCE = 0.025


def compare_timing(case, track_levels, issues, ours_av0=None):
    """Presentation times, after removing a common time origin. Runs of 5 or more samples with one
    shift are sections; shorter runs between two sections of different shift are the frames around
    the change (ffmpeg squeezes overlapping frames onto one time there) and count with it.
    - `<track>.placement[.disc]`: from some point on a track is shown later or earlier than in ffmpeg's
      (`.disc` in a case with discontinuities: where the sections are put);
    - `<track>.samples.time[.disc]`: a few samples out of place inside a section;
    - `av.offset[.inputs]`: the A/V offset at the start differs (`.inputs`: separate renditions, where
      ours keeps the offset their timestamps give and ffmpeg starts each input at 0);
    - `av.sync`: the A/V offset in a later section differs from ffmpeg's by more than SYNC_TOLERANCE."""
    tol = 2 * TIME_TOLERANCE
    disc = '.disc' if case.disc else ''
    for label, levels in track_levels:
        for lv in levels:
            if lv.ffmpeg_overlap and lv is not levels[0]:
                issues.add(f'{label}.samples.ffmpeg-overlap',
                           f'{label}: {lv.n} samples from ffmpeg {lv.t0:.3f} s are shown on top of other frames in '
                           f'ffmpeg\'s file ({lv.ref_overlapping} overlap there, none in ours); ours shows them '
                           f'{lv.shift * 1000:+.1f} ms later')
    track_levels = [(label, [lv for k, lv in enumerate(levels) if k == 0 or not lv.ffmpeg_overlap])
                    for label, levels in track_levels]
    big_of = {label: [lv for lv in levels if lv.big] or levels[:1] for label, levels in track_levels}
    video = next((big_of[label] for label, lv in track_levels if label.startswith('video') and lv), None)
    audio = next((big_of[label] for label, lv in track_levels if label.startswith('audio') and lv), None)
    origin = (video or audio or [Level(0)])[0].shift
    for label, levels in track_levels:
        big = big_of[label]
        steps = [b for a, b in zip(big, big[1:]) if abs(b.shift - a.shift) > tol]
        if steps:
            where = ', '.join(f'from {lv.t0:.3f} s {(lv.shift - origin) * 1000:+.1f} ms' for lv in steps[:4])
            issues.add(f'{label}.placement{disc}',
                       f'{label}: placed differently than in ffmpeg\'s: {where}' + (' ...' if len(steps) > 4 else ''))
        # Short runs: around a step they belong to it; inside a section they are frames out of place.
        prev_big = None
        pending = []
        for lv in levels + [None]:
            if lv is not None and not lv.big:
                pending.append(lv); continue
            if pending:
                around_step = prev_big is not None and lv is not None and abs(lv.shift - prev_big.shift) > tol
                if not around_step and lv is not None:
                    n = sum(x.n for x in pending)
                    worst = max(pending, key=lambda x: abs(x.shift - (prev_big or lv).shift))
                    issues.add(f'{label}.samples.time{disc}',
                               f'{label}: {n} samples from ffmpeg {pending[0].t0:.3f} s shown up to '
                               f'{(worst.shift - (prev_big or lv).shift) * 1000:+.1f} ms off their section')
                pending = []
            prev_big = lv if lv is not None else prev_big
    if video and audio:
        start = video[0].shift - audio[0].shift
        if abs(start) > TIME_TOLERANCE:
            if case.audio:
                v0, a0 = first_pts(case.video), first_pts(case.audio)
                src = None if v0 is None or a0 is None else v0 - a0
                ours_matches = src is not None and ours_av0 is not None and abs(ours_av0 - src) <= tol
                issues.add('av.offset.inputs' if ours_matches else 'av.offset',
                           f'video starts {start * 1000:+.1f} ms relative to audio compared with ffmpeg\'s; the renditions\' '
                           f'timestamps put video {"?" if src is None else f"{src * 1000:+.1f}"} ms after audio, '
                           f'ours {"?" if ours_av0 is None else f"{ours_av0 * 1000:+.1f}"} ms')
            else:
                issues.add('av.offset', f'video starts {start * 1000:+.1f} ms relative to audio compared with ffmpeg\'s')
        worst = None
        for lv in video:
            a = level_at(audio, (lv.t0 + lv.t1) / 2)
            if a is None:
                continue
            d = (lv.shift - a.shift) - start
            if worst is None or abs(d) > abs(worst[0]):
                worst = (d, lv.t0)
        if worst and abs(worst[0]) > SYNC_TOLERANCE:
            issues.add('av.sync', f'from ffmpeg {worst[1]:.3f} s the A/V offset differs from ffmpeg\'s by {worst[0] * 1000:+.1f} ms')
        return None if worst is None else round(worst[0] * 1000, 1)
    return None


def compare(case, ref_path, our_path):
    issues = Issues()
    ref, ours = mp4read.Movie(ref_path), mp4read.Movie(our_path)
    if ref.moov_first != ours.moov_first:
        issues.add('layout.moov', f'moov {"before" if ref.moov_first else "after"} mdat in ffmpeg\'s, '
                   f'{"before" if ours.moov_first else "after"} in ours')
    counts = {}
    track_levels = []
    for label, rt, ot in compare_tracks(ref, ours, issues):
        compare_entries(label, rt, ot, issues)
        counts[label], levels = compare_samples(label, ref, ours, rt, ot, issues, case)
        track_levels.append((label, levels))
    first = {}
    for t in ours.tracks:
        if t.samples and t.handler not in first:
            first[t.handler] = min(t.shown_at(x, ours.timescale) for x in t.samples[:64])
    ours_av0 = first['vide'] - first['soun'] if 'vide' in first and 'soun' in first else None
    sync = compare_timing(case, track_levels, issues, ours_av0)
    if sync is not None:
        counts['A/V offset vs ffmpeg ms'] = {'ffmpeg': 0, 'ours': sync}
    if any(t.video for t in ref.tracks):
        e_ref, e_ours = showall_errors(ref_path), showall_errors(our_path)
        counts['showall errors'] = {'ffmpeg': e_ref, 'ours': e_ours}
        if e_ours > e_ref:
            issues.add('decode.showall', f'decoder errors when every frame is shown: ffmpeg\'s {e_ref}, ours {e_ours}')
    return issues, counts


# ---- allow list ----

def load_allow():
    p = os.path.join(S, 'allow.json')
    return json.load(open(p)) if os.path.exists(p) else []


def classify(case, ref, check, allow):
    """The allow-list entry covering an issue of [check] in [case] against reference [ref], or None."""
    for a in allow:
        if fnmatch.fnmatch(check, a['check']) and any(fnmatch.fnmatch(case, c) for c in a.get('cases', ['*'])) \
                and ref in a.get('refs', REFS):
            return a
    return None


# ---- strict decoding ----

_truth = {}


def strict_compare(case, base, ref_path, our_path, issues, counts):
    """Strict software and hardware decodes of ours and the reference against the source's frames.
    `strict.garbage` / `hw.garbage`: ours shows more frames that are no frame of the source than ffmpeg's
    file (`.shared`: some, but no more than ffmpeg's); `strict.lost` / `hw.lost`: more of the source's
    frames never shown; `hw.fallback`: VideoToolbox refused our file but not ffmpeg's; `strict.corrupt` /
    `strict.errors`: more frames flagged corrupt, or more decoder errors, than ffmpeg's file (software
    only: VideoToolbox's messages vary from run to run)."""
    pix = strict.pixel_format(our_path)
    if case.name not in _truth:
        _truth[case.name] = strict.truth('concatf:' + write_list(case.video, base + '.src.txt'), pix)
    good = _truth[case.name]
    counts['source frames'] = {'ffmpeg': len(good), 'ours': len(good)}
    for kind, hw in (('strict', False), ('hw', True)):
        o, r = strict.decode(our_path, pix, hw), strict.decode(ref_path, pix, hw)
        (ob, ol, of), (rb, rl, _) = strict.garbage(o, good), strict.garbage(r, good)
        counts[f'{kind} garbage'] = {'ffmpeg': rb, 'ours': ob}
        counts[f'{kind} lost'] = {'ffmpeg': rl, 'ours': ol}
        if ob > rb:
            issues.add(f'{kind}.garbage', f'{kind} decode shows {ob} frames that are no frame of the source '
                       f'(ffmpeg\'s file {rb}); first at frame {of}')
        elif ob:
            issues.add(f'{kind}.garbage.shared', f'{kind} decode shows {ob} frames that are no frame of the source; '
                       f'ffmpeg\'s file {rb}')
        if ol > rl:
            # Frames a normal decode of the source produces before a section's keyframe (decoded against
            # the other content's pictures) are the ones ours drops on purpose (D1b).
            sub = '.section-start' if ol - rl <= issues.dropped_at_section_start else ''
            issues.add(f'{kind}.lost{sub}', f'{kind} decode never shows {ol} of the source\'s frames (ffmpeg\'s file {rl})'
                       + (f'; ours dropped {issues.dropped_at_section_start} before a section\'s keyframe' if sub else ''))
        if hw and o.fallback and not r.fallback:
            issues.add('hw.fallback', 'VideoToolbox refused our file and ffmpeg decoded it in software; '
                       'it took ffmpeg\'s file')
        if hw:
            continue  # VideoToolbox decodes asynchronously: its error messages vary from run to run
        counts[f'{kind} corrupt'] = {'ffmpeg': r.corrupt, 'ours': o.corrupt}
        if o.corrupt > r.corrupt:
            issues.add(f'{kind}.corrupt', f'{kind} decode flags {o.corrupt} frames corrupt (ffmpeg\'s file {r.corrupt})')
        if len(o.errors) > len(r.errors):
            extra = sorted(set(o.errors) - set(r.errors))
            issues.add(f'{kind}.errors', f'{kind} decoder errors: ours {len(o.errors)}, ffmpeg\'s {len(r.errors)}; '
                       f'first only in ours: {(extra or o.errors)[0][:120]}')


# ---- system player (AVFoundation) ----

AVF = os.environ.get('XDM_AVF_CHECK', os.path.join(OUT, 'avf_check'))


def avf(path):
    r = subprocess.run([AVF, path], capture_output=True, text=True)
    try:
        return json.loads(r.stdout)
    except ValueError:
        return {'playable': False, 'error': (r.stderr or 'no output')[-200:]}


def native_compare(ref_path, our_path, issues, counts):
    """`native.unplayable`: AVFoundation won't play ours (`.shared`: nor ffmpeg's); `native.stops`: decoding
    stops with an error part-way (QuickTime stops showing video there); `native.frames`: fewer frames than
    ffmpeg's file; `native.held`: more frames held over 1.5 frame durations (stutter)."""
    o, r = avf(our_path), avf(ref_path)
    ov, rv = o.get('video') or {}, r.get('video') or {}
    counts['native frames'] = {'ffmpeg': rv.get('frames', 0), 'ours': ov.get('frames', 0)}
    counts['native held'] = {'ffmpeg': rv.get('heldFrames', 0), 'ours': ov.get('heldFrames', 0)}
    if not o.get('playable') or (o.get('video') is not None and not ov.get('frames')):
        issues.add('native.unplayable' + ('.shared' if not r.get('playable') or not rv.get('frames') else ''),
                   f'AVFoundation: ours playable={o.get("playable")} ({ov.get("codec")}, {ov.get("error") or o.get("error")}); '
                   f'ffmpeg\'s playable={r.get("playable")}')
        return
    if ov.get('error'):
        issues.add('native.stops' + ('.shared' if rv.get('error') else ''),
                   f'AVFoundation stops decoding after {ov.get("frames")} frames: {ov["error"]} '
                   f'(ffmpeg\'s file: {rv.get("frames")} frames, {rv.get("error") or "no error"})')
    if ov.get('frames', 0) < rv.get('frames', 0) - 1:
        issues.add('native.frames', f'AVFoundation decodes {ov.get("frames")} frames of ours, {rv.get("frames")} of ffmpeg\'s')
    if rv.get('error') or rv.get('frames', 0) < ov.get('frames', 0) - 1:
        return  # ffmpeg's file stops early or plays fewer frames: its held frames don't compare
    if ov.get('heldFrames', 0) > rv.get('heldFrames', 0):
        issues.add('native.held', f'AVFoundation holds {ov["heldFrames"]} frames longer than 1.5 frames in ours '
                   f'(longest {ov.get("longestGapMs", 0):.0f} ms), {rv.get("heldFrames")} in ffmpeg\'s')


# ---- main ----

STRICT = False
NATIVE = False
CLEAN = False


def run(case, ref_name, allow, ours, log, base):
    row = {'case': case.name, 'ref': ref_name, 'disc': case.disc}
    ref, ffwarn = mux_ffmpeg(case, base, ref_name)
    row['ffmpeg_warnings'] = ffwarn[:20]
    if ref is None:
        row['verdict'] = 'NOREF'
        row['issues'] = [{'check': 'ffmpeg', 'message': 'ffmpeg could not write MP4: ' + ' | '.join(ffwarn[-3:])}]
        return row
    if ours is None:
        row['verdict'] = 'FAIL'
        row['issues'] = [{'check': 'mux', 'message': 'TransmuxingMuxer failed: ' + ' | '.join(log[-5:])}]
        return row
    issues, counts = compare(case, ref, ours)
    if STRICT and ref_name == REFS[0]:
        strict_compare(case, base, ref, ours, issues, counts)
    if NATIVE and ref_name == REFS[0]:
        native_compare(ref, ours, issues, counts)
    row['counts'] = counts
    row['issues'] = []
    verdict = 'PASS'
    for check, msg in issues.items:
        a = classify(case.name, ref_name, check, allow)
        status = a['status'] if a else 'fail'
        row['issues'].append({'check': check, 'message': msg, 'status': status,
                              **({'decision': a.get('decision'), 'reason': a.get('reason')} if a else {})})
        if status == 'fail':
            verdict = 'FAIL'
        elif status == 'open' and verdict == 'PASS':
            verdict = 'WARN'
    row['verdict'] = verdict
    return row


def print_row(row):
    counts = row.get('counts', {})
    summary = '  '.join(f'{k} {v["ours"]}/{v["ffmpeg"]}' for k, v in counts.items())
    main = ' main' if REFS and row['ref'] == REFS[0] else ''
    print(f'{row["verdict"]:6} {row["case"] + " [" + row["ref"] + main + "]":41} {summary}  (ours/ffmpeg)', flush=True)
    for i in row.get('issues', []):
        tag = {'fail': 'DIFF', 'open': 'open', 'deliberate': 'ok  '}.get(i.get('status', 'fail'), '')
        extra = f' [{i["decision"]}]' if i.get('decision') else ''
        print(f'         {tag} {i["check"]}{extra}: {i["message"]}')


def main():
    global STRICT, CLEAN, NATIVE
    args = sys.argv[1:]
    corpus = '--corpus' in args
    STRICT = '--strict' in args
    CLEAN = '--clean' in args
    NATIVE = '--native' in args
    names = [a for a in args if not a.startswith('--')]
    if corpus:
        cdir = os.environ.get('XDM_CORPUS_DIR', os.path.join(TMP, 'xdm-transmux-corpus'))
        names = names or sorted(n for n in os.listdir(cdir) if os.path.isdir(os.path.join(cdir, n)))
        cases = [corpus_case(n) for n in names]
    else:
        names = names or sorted(n for n in os.listdir(CASES) if os.path.isdir(os.path.join(CASES, n)) and not n.startswith('_'))
        cases = [load_case(n) for n in names]
    allow = load_allow()
    rows = []
    os.makedirs(OUT, exist_ok=True)
    for case in cases:
        base = os.path.join(OUT, case.name.replace('/', '_'))
        ours, log = mux_ours(case, base)
        for ref_name in REFS:
            try:
                row = run(case, ref_name, allow, ours, log, base)
            except Exception as e:   # a reader problem is a result too, not the end of the run
                row = {'case': case.name, 'ref': ref_name, 'verdict': 'ERROR',
                       'issues': [{'check': 'harness', 'message': repr(e)}]}
            rows.append(row)
            print_row(row)
        if CLEAN:
            for f in [base + '.xdm.mp4'] + [f'{base}.{r}.mp4' for r in REFS]:
                if os.path.exists(f):
                    os.remove(f)
    os.makedirs(OUT, exist_ok=True)
    json.dump(rows, open(os.path.join(OUT, 'results.json'), 'w'), indent=1)
    by = {v: sum(r['verdict'] == v for r in rows) for v in ('PASS', 'WARN', 'FAIL', 'NOREF', 'ERROR')}
    print(' '.join(f'{k} {v}' for k, v in by.items() if v) + f'  ({os.path.join(OUT, "results.json")})')
    sys.exit(1 if by['FAIL'] or by['ERROR'] else 0)


if __name__ == '__main__':
    main()
