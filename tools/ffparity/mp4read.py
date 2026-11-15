"""Reads a progressive MP4 into per-track facts and sample lists, without ffprobe: what is compared
must be what the file says, not what a demuxer makes of it.

    m = Movie('out.mp4')
    for t in m.tracks: t.handler, t.entries, t.samples (Sample: offset, size, dts, pts, key)
"""
import hashlib, struct

CONTAINERS = {b'moov', b'trak', b'mdia', b'minf', b'stbl', b'edts', b'dinf'}


def boxes(d, s, e):
    while s + 8 <= e:
        size, typ = struct.unpack('>I4s', d[s:s + 8])
        h = 8
        if size == 1:
            size = struct.unpack('>Q', d[s + 8:s + 16])[0]
            h = 16
        elif size == 0:
            size = e - s
        if size < h or s + size > e:
            return
        yield typ, s + h, s + size
        s += size


def child(d, s, e, typ):
    for t, b, en in boxes(d, s, e):
        if t == typ:
            return b, en
    return None


class Sample:
    __slots__ = ('index', 'offset', 'size', 'dts', 'pts', 'key', 'entry')

    def __init__(self, index, offset, size, dts, pts, key, entry):
        self.index, self.offset, self.size, self.dts, self.pts, self.key, self.entry = \
            index, offset, size, dts, pts, key, entry


class Entry:
    """One stsd sample entry: its fourcc, the fixed fields and its child boxes (config records)."""

    def __init__(self, fourcc, body, video):
        self.fourcc = fourcc
        self.fields = {}
        if video and len(body) >= 78:
            self.fields['width'], self.fields['height'] = struct.unpack('>HH', body[24:28])
            kids_at = 78
        elif len(body) >= 28:
            version = struct.unpack('>H', body[8:10])[0]
            self.fields['channels'] = struct.unpack('>H', body[16:18])[0]
            self.fields['sample_rate'] = struct.unpack('>I', body[24:28])[0] >> 16
            kids_at = 28 + (16 if version == 1 else 36 if version == 2 else 0)
        else:
            kids_at = len(body)
        self.children = {}
        for t, b, en in boxes(body, kids_at, len(body)):
            self.children[t.decode('latin1')] = body[b:en]


class Track:
    def __init__(self):
        self.handler = None
        self.track_id = 0
        self.timescale = 0
        self.tkhd_size = (0, 0)
        self.matrix = None
        self.edits = []          # (segment_duration in movie ticks, media_time, rate)
        self.entries = []
        self.samples = []
        self.length_size = 4     # NAL length field size from avcC/hvcC

    @property
    def video(self):
        return self.handler == 'vide'

    def presentation_start(self, movie_timescale):
        """Seconds on the movie timeline at which media time 0 of this track is shown, and the media
        time the first normal edit starts at: (empty edits, media_time)."""
        empty, media_time = 0, 0
        for seg, mt, _ in self.edits:
            if mt == -1:
                empty += seg
            else:
                media_time = mt
                break
        return empty / movie_timescale, media_time

    def shown_at(self, s, movie_timescale):
        """Seconds on the movie timeline at which sample [s] is presented (edit list applied)."""
        empty, media_time = self.presentation_start(movie_timescale)
        return empty + (s.pts - media_time) / self.timescale


class Movie:
    def __init__(self, path):
        self.path = path
        with open(path, 'rb') as f:
            self.data = d = f.read()
        top = list(boxes(d, 0, len(d)))
        order = [t for t, _, _ in top]
        self.moov_first = b'moov' in order and b'mdat' in order and order.index(b'moov') < order.index(b'mdat')
        moov = next(((b, e) for t, b, e in top if t == b'moov'), None)
        if moov is None:
            raise ValueError(f'{path}: no moov')
        mvhd = child(d, *moov, b'mvhd')
        v = d[mvhd[0]]
        self.timescale = struct.unpack('>I', d[mvhd[0] + (20 if v == 1 else 12):][:4])[0]
        self.tracks = [self._track(b, e) for t, b, e in boxes(d, *moov) if t == b'trak']

    def _track(self, s, e):
        d = self.data
        t = Track()
        tkhd = child(d, s, e, b'tkhd')
        if tkhd:
            b, en = tkhd
            t.track_id = struct.unpack('>I', d[b + (20 if d[b] == 1 else 12):][:4])[0]
            w, h = struct.unpack('>II', d[en - 8:en])
            t.tkhd_size = (w >> 16, h >> 16)
            t.matrix = struct.unpack('>9i', d[en - 44:en - 8])
        edts = child(d, s, e, b'edts')
        if edts:
            elst = child(d, *edts, b'elst')
            if elst:
                b, _ = elst
                v = d[b]
                n = struct.unpack('>I', d[b + 4:b + 8])[0]
                p = b + 8
                for _ in range(n):
                    if v == 1:
                        seg, mt = struct.unpack('>Qq', d[p:p + 16]); p += 16
                    else:
                        seg, mt = struct.unpack('>Ii', d[p:p + 8]); p += 8
                    rate = struct.unpack('>i', d[p:p + 4])[0]; p += 4
                    t.edits.append((seg, mt, rate))
        mdia = child(d, s, e, b'mdia')
        mdhd = child(d, *mdia, b'mdhd')
        b = mdhd[0]
        t.timescale = struct.unpack('>I', d[b + (20 if d[b] == 1 else 12):][:4])[0]
        hdlr = child(d, *mdia, b'hdlr')
        t.handler = d[hdlr[0] + 8:hdlr[0] + 12].decode('latin1')
        stbl = child(d, *child(d, *mdia, b'minf'), b'stbl')
        self._samples(t, stbl)
        return t

    def _samples(self, t, stbl):
        d = self.data

        def full(typ):
            r = child(d, *stbl, typ)
            return (r[0] + 4, r[1]) if r else None   # past version/flags

        stsd = full(b'stsd')
        n = struct.unpack('>I', d[stsd[0]:stsd[0] + 4])[0]
        p = stsd[0] + 4
        for _ in range(n):
            size, typ = struct.unpack('>I4s', d[p:p + 8])
            t.entries.append(Entry(typ.decode('latin1'), d[p + 8:p + size], t.video))
            p += size
        for entry in t.entries:
            cfg = entry.children.get('avcC') or entry.children.get('hvcC')
            if cfg is not None:
                t.length_size = (cfg[4] & 3) + 1 if 'avcC' in entry.children else (cfg[21] & 3) + 1
                break

        def table(typ, fmt, count_at=0):
            r = full(typ)
            if not r:
                return None
            b, _ = r
            cnt = struct.unpack('>I', d[b + count_at:b + count_at + 4])[0]
            size = struct.calcsize(fmt)
            start = b + count_at + 4
            return [struct.unpack(fmt, d[start + i * size:start + (i + 1) * size]) for i in range(cnt)]

        stsz = full(b'stsz')
        fixed, count = struct.unpack('>II', d[stsz[0]:stsz[0] + 8])
        sizes = [fixed] * count if fixed else list(struct.unpack(f'>{count}I', d[stsz[0] + 8:stsz[0] + 8 + 4 * count]))
        stco = table(b'stco', '>I') or table(b'co64', '>Q')
        chunk_offsets = [x[0] for x in stco]
        stsc = table(b'stsc', '>III')
        stts = table(b'stts', '>II')
        ctts_box = child(d, *stbl, b'ctts')
        ctts = None
        if ctts_box:
            signed = d[ctts_box[0]] == 1
            ctts = table(b'ctts', '>Ii' if signed else '>II')
            # Version 0 offsets are read signed too, as players do.
            ctts = [(c, o - (1 << 32) if o >= 1 << 31 else o) for c, o in ctts]
        stss = table(b'stss', '>I')
        keys = None if stss is None else {x[0] for x in stss}

        # Sample -> (chunk offset, description index) via stsc runs.
        offsets, descs = [], []
        runs = stsc + [(len(chunk_offsets) + 1, 0, 0)]
        k = 0
        for r in range(len(stsc)):
            first, per, desc = runs[r]
            nxt = runs[r + 1][0]
            for chunk in range(first, nxt):
                off = chunk_offsets[chunk - 1]
                for _ in range(per):
                    if k >= count:
                        break
                    offsets.append(off)
                    descs.append(desc)
                    off += sizes[k]
                    k += 1
        dts_list = []
        dts = 0
        for c, delta in stts:
            for _ in range(c):
                dts_list.append(dts)
                dts += delta
        cts_list = []
        if ctts:
            for c, o in ctts:
                cts_list += [o] * c
        for i in range(count):
            dt = dts_list[i] if i < len(dts_list) else dts
            pt = dt + (cts_list[i] if i < len(cts_list) else 0)
            key = True if keys is None else (i + 1) in keys
            t.samples.append(Sample(i, offsets[i] if i < len(offsets) else 0, sizes[i], dt, pt, key,
                                    descs[i] if i < len(descs) else 1))

    def sample_bytes(self, s):
        return self.data[s.offset:s.offset + s.size]


# ---- NAL-level views of H.264/H.265 samples ----

def nal_units(data, length_size=4):
    p = 0
    while p + length_size <= len(data):
        n = int.from_bytes(data[p:p + length_size], 'big')
        p += length_size
        yield data[p:p + n]
        p += n


def h264_type(nal):
    return nal[0] & 0x1F if nal else -1


def h265_type(nal):
    return (nal[0] >> 1) & 0x3F if nal else -1


# Access unit delimiters and filler data: ffmpeg keeps them, the transmuxer drops them. Neither changes
# a decoded picture, so samples are compared without them.
H264_DROP = {9, 12}
H265_DROP = {35, 38}


def normalized_hash(track, data):
    fourcc = track.entries[0].fourcc if track.entries else ''
    if fourcc in ('avc1', 'avc3'):
        nals = [n for n in nal_units(data, track.length_size) if h264_type(n) not in H264_DROP]
    elif fourcc in ('hev1', 'hvc1'):
        nals = [n for n in nal_units(data, track.length_size) if h265_type(n) not in H265_DROP]
    else:
        return hashlib.md5(data).hexdigest()
    h = hashlib.md5()
    for n in nals:
        h.update(len(n).to_bytes(4, 'big'))
        h.update(n)
    return h.hexdigest()


def short(b):
    return hashlib.md5(b).hexdigest()[:10]


def describe_config(entry):
    """The decoder config of a sample entry broken into the decisions a muxer makes."""
    out = {}
    if 'avcC' in entry.children:
        r = entry.children['avcC']
        sps, pps = [], []
        i = 6
        for _ in range(r[5] & 0x1F):
            n = struct.unpack('>H', r[i:i + 2])[0]
            sps.append(r[i + 2:i + 2 + n]); i += 2 + n
        npps = r[i]; i += 1
        for _ in range(npps):
            n = struct.unpack('>H', r[i:i + 2])[0]
            pps.append(r[i + 2:i + 2 + n]); i += 2 + n
        out['avcC profile/compat/level'] = (r[1], r[2], r[3])
        out['avcC SPS'] = [short(s) for s in sps]
        out['avcC PPS'] = [short(p) for p in pps]
        out['avcC ext (chroma, depth)'] = r[i:i + 3].hex() if i < len(r) else None
    if 'hvcC' in entry.children:
        r = entry.children['hvcC']
        out['hvcC profile/tier/level'] = (r[1] & 0x1F, (r[1] >> 5) & 1, r[12])
        out['hvcC chroma/luma/chroma depth'] = (r[16] & 3, (r[17] & 7) + 8, (r[18] & 7) + 8)
        out['hvcC layers/nested'] = ((r[21] >> 3) & 7, (r[21] >> 2) & 1)
        i = 23
        arrays = {}
        for _ in range(r[22]):
            typ = r[i] & 0x3F
            cnt = struct.unpack('>H', r[i + 1:i + 3])[0]; i += 3
            items = []
            for _ in range(cnt):
                n = struct.unpack('>H', r[i:i + 2])[0]
                items.append(short(r[i + 2:i + 2 + n])); i += 2 + n
            arrays[{32: 'VPS', 33: 'SPS', 34: 'PPS'}.get(typ, str(typ))] = items
        out['hvcC parameter sets'] = arrays
    for name in ('esds', 'dac3', 'dec3', 'dOps', 'pasp', 'colr', 'btrt'):
        if name in entry.children:
            body = entry.children[name]
            if name == 'esds':
                # Object type and DecoderSpecificInfo; ES_ID, buffer size and bitrates are informative.
                desc = esds_descriptors(body[4:])
                out['esds object type'] = desc.get(0x04, b'\x00')[:1].hex()
                out['esds DSI'] = desc.get(0x05, b'').hex()
            elif name != 'btrt':
                out[name] = body.hex()
    return out


def esds_descriptors(b):
    """{tag: payload} of the descriptors in an esds (ES, DecoderConfig, DecoderSpecificInfo, SL)."""
    out = {}

    def walk(p, end):
        while p + 2 <= end:
            tag = b[p]; p += 1
            size = 0
            for _ in range(4):
                c = b[p]; p += 1
                size = (size << 7) | (c & 0x7F)
                if not c & 0x80:
                    break
            body_end = min(p + size, end)
            out.setdefault(tag, b[p:body_end])
            if tag == 0x03:      # ES_Descriptor: ES_ID(2), flags(1) [+ optional fields], children
                flags = b[p + 2]
                q = p + 3
                if flags & 0x80: q += 2
                if flags & 0x40: q += 1 + b[q]
                if flags & 0x20: q += 2
                walk(q, body_end)
            elif tag == 0x04:    # DecoderConfigDescriptor: 13 fixed bytes, then DecoderSpecificInfo
                walk(p + 13, body_end)
            p = body_end

    walk(0, len(b))
    return out
