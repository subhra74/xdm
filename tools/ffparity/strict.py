"""Strict decoding: what a player that shows every frame would show.

ffmpeg's default decode skips frames it can't decode (frames before the first keyframe, pictures whose
references are missing) and reconfigures itself from parameter sets inside the stream. Hardware
decoders and most players do neither: they are set up from the MP4's sample entry (avcC/hvcC) and
output every frame, broken or not, which is what shows as green or grey flashes.

  truth(case_list)          frame checksums of a normal ffmpeg decode of the source segments: the
                            frames a correct file can show
  decode(path, hw=False)    every frame of the file's first video track (-flags2 showall, error
                            detection on), or through VideoToolbox (Apple's hardware decoder)

A frame a strict decode outputs whose checksum isn't one of the source's is garbage.
"""
import collections, subprocess

ERR_DETECT = ['-err_detect', '+crccheck+bitstream+buffer']


def pixel_format(path):
    """The pixel format all decodes are converted to before checksums, so software and hardware
    output (nv12/p010 from VideoToolbox) compare: 8-bit or 10-bit planar 4:2:0."""
    r = subprocess.run(['ffprobe', '-v', 'quiet', '-select_streams', 'v:0', '-show_entries', 'stream=pix_fmt',
                        '-of', 'csv=p=0', path], capture_output=True, text=True)
    return 'yuv420p10le' if '10' in r.stdout else 'yuv420p'


def _run(args):
    r = subprocess.run(args, capture_output=True, text=True)
    sums = [l.rsplit(',', 1)[1].strip() for l in r.stdout.splitlines() if l and not l.startswith('#')]
    return sums, [l for l in r.stderr.splitlines() if l.strip()]


def truth(source, pix):
    """[source]: an ffmpeg input (concatf:<list>). Frame checksums of a normal decode."""
    sums, _ = _run(['ffmpeg', '-hide_banner', '-v', 'error', '-i', source, '-map', '0:v:0', '-vf', f'format={pix}',
                    '-fps_mode', 'passthrough', '-noautoscale', '-f', 'framecrc', '-'])
    return sums


Decoded = collections.namedtuple('Decoded', 'frames errors corrupt fallback')

# ffmpeg's log bookkeeping, not decoder findings.
NOT_ERRORS = ('Last message repeated', '[null @', 'deprecated', 'Failed setup', 'corrupt decoded frame')


def decode(path, pix, hw=False):
    """Every frame of [path]'s first video track. [hw]: through VideoToolbox; fallback is True when
    ffmpeg had to use its software decoder instead (the hardware one refused the stream)."""
    pre = ['-hwaccel', 'videotoolbox'] if hw else ['-flags2', 'showall', *ERR_DETECT]
    sums, err = _run(['ffmpeg', '-hide_banner', '-v', 'warning', *pre, '-i', path, '-map', '0:v:0',
                      '-vf', f'format={pix}', '-fps_mode', 'passthrough', '-noautoscale', '-f', 'framecrc', '-'])
    fallback = hw and any('Failed setup' in l or 'hwaccel initialisation returned error' in l for l in err)
    errors = [l for l in err if not any(x in l for x in NOT_ERRORS)]
    corrupt = sum('corrupt decoded frame' in l for l in err)
    return Decoded(sums, errors, corrupt, fallback)


def garbage(decoded, good):
    """Frames shown that a correct decode never produces, and good frames never shown."""
    have = collections.Counter(good)
    shown = collections.Counter(decoded.frames)
    bad = sum((shown - have).values())
    lost = sum((have - shown).values())
    first_bad = next((i for i, s in enumerate(decoded.frames) if s not in have), None)
    return bad, lost, first_bad
