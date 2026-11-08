#!/usr/bin/env python3
"""Rewrites fragmented-MP4 HLS segments into layouts other packagers produce but ffmpeg doesn't.

usage: fmp4mod.py <mode> <in> <out> [init.mp4]
modes (media segments; init.mp4 gives trex defaults):
  multitrun   each trun split in two; the second has no data_offset (its data follows the first's)
  nobase      default-base-is-moof cleared: a traf's data then starts where the previous traf's ended
  renumber    track_IDs 1 and 2 swapped (init segment or media segment)
  chunked     each moof+mdat split into 4 (low-latency CMAF chunks), each with its own tfdt
  notfdt      tfdt removed: each fragment's decode time continues from the previous one
"""
import struct, sys

CONTAINERS = {b'moov', b'trak', b'mdia', b'minf', b'stbl', b'mvex', b'moof', b'traf', b'edts', b'dinf', b'mfra'}


class Box:
    def __init__(self, t, payload=b'', children=None):
        self.type, self.payload, self.children = t, payload, children

    def find(self, t):
        return [c for c in (self.children or []) if c.type == t]

    def to_bytes(self):
        body = b''.join(c.to_bytes() for c in self.children) if self.children is not None else self.payload
        return struct.pack('>I4s', len(body) + 8, self.type) + body


def parse(d, s=0, e=None):
    e = len(d) if e is None else e
    out = []
    while s + 8 <= e:
        size, t = struct.unpack('>I4s', d[s:s + 8]); h = 8
        if size == 1:
            size = struct.unpack('>Q', d[s + 8:s + 16])[0]; h = 16
        elif size == 0:
            size = e - s
        if t in CONTAINERS:
            out.append(Box(t, children=parse(d, s + h, s + size)))
        else:
            out.append(Box(t, d[s + h:s + size]))
        s += size
    return out


def u32(b, o): return struct.unpack('>I', b[o:o + 4])[0]


class Tfhd:
    def __init__(self, p):
        self.flags = int.from_bytes(p[1:4], 'big'); self.track = u32(p, 4)
        o = 8; self.fields = {}
        for bit, name, n in ((1, 'base', 8), (2, 'sdi', 4), (8, 'dur', 4), (0x10, 'size', 4), (0x20, 'flags', 4)):
            if self.flags & bit:
                self.fields[name] = p[o:o + n]; o += n

    def to_payload(self):
        out = bytes([0]) + self.flags.to_bytes(3, 'big') + struct.pack('>I', self.track)
        for bit, name in ((1, 'base'), (2, 'sdi'), (8, 'dur'), (0x10, 'size'), (0x20, 'flags')):
            if self.flags & bit:
                out += self.fields[name]
        return out


class Trun:
    def __init__(self, p):
        self.version = p[0]; self.flags = int.from_bytes(p[1:4], 'big')
        n = u32(p, 4); o = 8
        self.data_offset = None; self.first_flags = None
        if self.flags & 1:
            self.data_offset = struct.unpack('>i', p[o:o + 4])[0]; o += 4
        if self.flags & 4:
            self.first_flags = p[o:o + 4]; o += 4
        per = sum(4 for bit in (0x100, 0x200, 0x400, 0x800) if self.flags & bit)
        self.samples = [p[o + i * per:o + (i + 1) * per] for i in range(n)]

    def size_of(self, i, default_size):
        if not self.flags & 0x200:
            return default_size
        o = 4 if self.flags & 0x100 else 0
        return u32(self.samples[i], o)

    def to_payload(self):
        out = bytes([self.version]) + self.flags.to_bytes(3, 'big') + struct.pack('>I', len(self.samples))
        if self.flags & 1:
            out += struct.pack('>i', self.data_offset)
        if self.flags & 4:
            out += self.first_flags
        return out + b''.join(self.samples)


def trex_sizes(init_path):
    sizes = {}
    if init_path:
        for b in parse(open(init_path, 'rb').read()):
            if b.type == b'moov':
                for mvex in b.find(b'mvex'):
                    for trex in mvex.find(b'trex'):
                        sizes[u32(trex.payload, 4)] = u32(trex.payload, 16)
    return sizes


def rewrite_moofs(boxes, edit):
    """Calls edit(moof) for each moof; then shifts data offsets by how much the moof grew."""
    for moof in [b for b in boxes if b.type == b'moof']:
        before = len(moof.to_bytes())
        edit(moof)
        delta = len(moof.to_bytes()) - before
        if delta:
            for traf in moof.find(b'traf'):
                tf = Tfhd(traf.find(b'tfhd')[0].payload)
                if tf.flags & 1:
                    continue  # absolute base_data_offset: not used by these files
                for i, trun_box in enumerate(traf.find(b'trun')):
                    tr = Trun(trun_box.payload)
                    if tr.data_offset is not None and (tf.flags & 0x020000 or (traf is moof.find(b'traf')[0] and i == 0)):
                        tr.data_offset += delta
                        trun_box.payload = tr.to_payload()


def multitrun(boxes, defaults):
    def edit(moof):
        for traf in moof.find(b'traf'):
            new_children = []
            for c in traf.children:
                if c.type != b'trun':
                    new_children.append(c); continue
                tr = Trun(c.payload)
                if len(tr.samples) < 2:
                    new_children.append(c); continue
                half = len(tr.samples) // 2
                a = Trun(c.payload); a.samples = tr.samples[:half]
                b = Trun(c.payload); b.samples = tr.samples[half:]
                b.flags &= ~0x5  # no data_offset, no first_sample_flags: data follows trun a
                new_children += [Box(b'trun', a.to_payload()), Box(b'trun', b.to_payload())]
            traf.children = new_children
    rewrite_moofs(boxes, edit)


def nobase(boxes, defaults):
    """Clears default-base-is-moof. The first traf's base stays the moof start; each later traf's base
    becomes the end of the previous traf's data, so its trun data_offsets are rebased onto that."""
    for moof in [b for b in boxes if b.type == b'moof']:
        prev_end = 0  # end of the previous traf's data, relative to the moof start
        for k, traf in enumerate(moof.find(b'traf')):
            tf_box = traf.find(b'tfhd')[0]
            tf = Tfhd(tf_box.payload)
            default_size = u32(tf.fields['size'], 0) if 'size' in tf.fields else defaults.get(tf.track, 0)
            tf.flags &= ~0x020000
            tf_box.payload = tf.to_payload()
            pos = None
            for trun_box in traf.find(b'trun'):
                tr = Trun(trun_box.payload)
                if tr.data_offset is not None:
                    pos = tr.data_offset  # relative to the moof start
                    if k > 0:
                        tr.data_offset = pos - prev_end
                        trun_box.payload = tr.to_payload()
                pos += sum(tr.size_of(i, default_size) for i in range(len(tr.samples)))
            prev_end = pos


def notfdt(boxes, defaults):
    def edit(moof):
        for traf in moof.find(b'traf'):
            traf.children = [c for c in traf.children if c.type != b'tfdt']
    rewrite_moofs(boxes, edit)


def renumber(boxes, defaults):
    swap = {1: 2, 2: 1}

    def fix(b, offset):
        tid = u32(b.payload, offset)
        if tid in swap:
            b.payload = b.payload[:offset] + struct.pack('>I', swap[tid]) + b.payload[offset + 4:]

    def walk(bs):
        for b in bs:
            if b.type == b'tkhd':
                fix(b, 20 if b.payload[0] == 1 else 12)
            elif b.type in (b'trex', b'tfhd'):
                fix(b, 4)
            if b.children is not None:
                walk(b.children)
    walk(boxes)


def trex_defaults(init_path):
    """track_ID -> (default duration, default size) from the init segment's trex boxes."""
    out = {}
    for b in parse(open(init_path, 'rb').read()):
        if b.type == b'moov':
            for mvex in b.find(b'mvex'):
                for trex in mvex.find(b'trex'):
                    out[u32(trex.payload, 4)] = (u32(trex.payload, 12), u32(trex.payload, 16))
    return out


def chunked(data, init_path, parts=4):
    """Splits every moof+mdat pair of a media segment into [parts] pairs (default-base-is-moof)."""
    defaults = trex_defaults(init_path)
    raw = []  # (type, bytes, start offset)
    s = 0
    while s + 8 <= len(data):
        size = u32(data, s)
        raw.append((data[s + 4:s + 8], data[s:s + size], s)); s += size
    out = b''
    for k, (t, b, start) in enumerate(raw):
        if t == b'sidx' or t == b'mdat':
            continue
        if t != b'moof':
            out += b; continue
        moof = parse(b)[0]
        trafs = []
        for traf in moof.find(b'traf'):
            tf = Tfhd(traf.find(b'tfhd')[0].payload)
            ddur, dsize = defaults.get(tf.track, (0, 0))
            ddur = u32(tf.fields['dur'], 0) if 'dur' in tf.fields else ddur
            dsize = u32(tf.fields['size'], 0) if 'size' in tf.fields else dsize
            tfdt = traf.find(b'tfdt')[0].payload
            base_time = struct.unpack('>Q', tfdt[4:12])[0] if tfdt[0] == 1 else u32(tfdt, 4)
            samples = []  # (sample entry bytes, duration, data)
            for trun_box in traf.find(b'trun'):
                tr = Trun(trun_box.payload)
                pos = start + tr.data_offset
                for i, entry in enumerate(tr.samples):
                    size = tr.size_of(i, dsize)
                    dur = u32(entry, 0) if tr.flags & 0x100 else ddur
                    samples.append((entry, dur, data[pos:pos + size])); pos += size
            trafs.append((tf, traf, tr, base_time, samples))
        n = max(len(x[4]) for x in trafs)
        bounds = [n * c // parts for c in range(parts + 1)]
        for c in range(parts):
            new_trafs, payloads = [], []
            for tf, traf, tr, base_time, samples in trafs:
                lo = len(samples) * c // parts; hi = len(samples) * (c + 1) // parts
                part = samples[lo:hi]
                if not part:
                    continue
                t0 = base_time + sum(x[1] for x in samples[:lo])
                ntr = Trun(traf.find(b'trun')[0].payload)
                ntr.samples = [x[0] for x in part]
                if c > 0:
                    ntr.flags &= ~0x4  # first_sample_flags described the original first sample
                ntr.data_offset = 0  # patched below
                new_trafs.append((Box(b'traf', children=[Box(b'tfhd', tf.to_payload()),
                                                         Box(b'tfdt', bytes([1, 0, 0, 0]) + struct.pack('>Q', t0)),
                                                         Box(b'trun', ntr.to_payload())]), ntr))
                payloads.append(b''.join(x[2] for x in part))
            mfhd = moof.find(b'mfhd')[0]
            frag = Box(b'moof', children=[mfhd] + [x[0] for x in new_trafs])
            moof_size = len(frag.to_bytes())
            off = moof_size + 8
            for (box, ntr), payload in zip(new_trafs, payloads):
                ntr.data_offset = off
                box.children[2].payload = ntr.to_payload()
                off += len(payload)
            body = b''.join(payloads)
            out += frag.to_bytes() + struct.pack('>I4s', len(body) + 8, b'mdat') + body
    return out


def main():
    mode, src, dst = sys.argv[1:4]
    if mode == 'chunked':
        open(dst, 'wb').write(chunked(open(src, 'rb').read(), sys.argv[4]))
        return
    defaults = trex_sizes(sys.argv[4] if len(sys.argv) > 4 else None)
    # sidx sizes would no longer match the rewritten moofs; players and our demuxer don't need it.
    boxes = [b for b in parse(open(src, 'rb').read()) if b.type != b'sidx']
    {'multitrun': multitrun, 'nobase': nobase, 'renumber': renumber, 'notfdt': notfdt}[mode](boxes, defaults)
    open(dst, 'wb').write(b''.join(b.to_bytes() for b in boxes))


if __name__ == '__main__':
    main()
