#!/usr/bin/env python3
"""Re-packetizes 188-byte MPEG-TS segments to produce encoder layouts ffmpeg won't emit.

usage: tsmod.py <mode> <in.ts> <out.ts> [segment_index]
modes:
  vsplit_nopts  video PES split every 30000 bytes; continuation PES carry no PTS/DTS
  vsplit_pts    same, continuations repeat the PTS/DTS
  vsplit_small  video PES split every 4099 bytes (cuts start codes too), no PTS on continuations
  asplit        audio ES re-chunked into 700-byte PES, so ADTS/AC-3 frames straddle PES boundaries
  pkt204        16 zero bytes appended to every packet (TS+FEC layout)
  pidshift      segments with index >= 2 move PMT/video/audio to new PIDs
"""
import struct, sys

def crc32_mpeg(data):
    crc = 0xFFFFFFFF
    for b in data:
        crc ^= b << 24
        for _ in range(8):
            crc = ((crc << 1) ^ 0x04C11DB7) & 0xFFFFFFFF if crc & 0x80000000 else (crc << 1) & 0xFFFFFFFF
    return crc

def read_ts(path):
    d = open(path, 'rb').read()
    assert len(d) % 188 == 0 and d[0] == 0x47, 'expects plain 188-byte TS'
    return [d[i:i + 188] for i in range(0, len(d), 188)]

def payload(p):
    afc = (p[3] >> 4) & 3
    o = 4
    if afc in (2, 3):
        o += 1 + p[4]
    return p[o:188] if afc in (1, 3) and o < 188 else b''

def parse(path):
    """Returns (pmt_pid, streams[(type,pid,esinfo)], events[('pes', pid, bytes)] in start order)."""
    pkts = read_ts(path)
    pmt_pid = None; streams = None
    bufs = {}; events = []; open_ev = {}
    for p in pkts:
        pid = ((p[1] & 0x1F) << 8) | p[2]
        pusi = bool(p[1] & 0x40)
        pl = payload(p)
        if pid == 0 and pusi and pmt_pid is None:
            s = pl[1 + pl[0]:]
            pmt_pid = ((s[10] & 0x1F) << 8) | s[11]
        elif pid == pmt_pid and pusi and streams is None:
            s = pl[1 + pl[0]:]
            slen = ((s[1] & 0x0F) << 8) | s[2]
            end = 3 + slen - 4
            pil = ((s[10] & 0x0F) << 8) | s[11]
            i = 12 + pil; streams = []
            while i + 5 <= end:
                st = s[i]; epid = ((s[i + 1] & 0x1F) << 8) | s[i + 2]
                eil = ((s[i + 3] & 0x0F) << 8) | s[i + 4]
                streams.append((st, epid, bytes(s[i + 5:i + 5 + eil])))
                i += 5 + eil
        elif streams and pid in [x[1] for x in streams]:
            if pusi:
                ev = ['pes', pid, bytearray()]
                events.append(ev); open_ev[pid] = ev
            if pid in open_ev:
                open_ev[pid][2] += pl
    return pmt_pid, streams, [(e[1], bytes(e[2])) for e in events]

def pes_fields(pes):
    flags = (pes[7] >> 6) & 3
    hl = pes[8]
    def ts(o):
        return (((pes[o] >> 1) & 7) << 30) | (pes[o + 1] << 22) | ((pes[o + 2] >> 1) << 15) | (pes[o + 3] << 7) | (pes[o + 4] >> 1)
    pts = ts(9) if flags & 2 else None
    dts = ts(14) if flags == 3 else None
    return pes[3], pts, dts, pes[9 + hl:]

def enc_ts(prefix, v):
    return bytes([(prefix << 4) | (((v >> 30) & 7) << 1) | 1, (v >> 22) & 0xFF, (((v >> 15) & 0x7F) << 1) | 1,
                  (v >> 7) & 0xFF, ((v & 0x7F) << 1) | 1])

def make_pes(sid, pts, dts, data, video):
    opt = b''
    flags = 0
    if pts is not None and dts is not None and dts != pts:
        flags = 0xC0; opt = enc_ts(3, pts) + enc_ts(1, dts)
    elif pts is not None:
        flags = 0x80; opt = enc_ts(2, pts)
    body = bytes([0x80, flags, len(opt)]) + opt + data
    ln = 0 if (video or len(body) > 0xFFFF) else len(body)
    return b'\x00\x00\x01' + bytes([sid]) + struct.pack('>H', ln) + body

class Writer:
    def __init__(self):
        self.out = bytearray(); self.cc = {}
    def packets(self, pid, data, psi=False):
        first = True; pos = 0; res = []
        if psi:
            data = b'\x00' + data
        while pos < len(data) or first:
            cc = self.cc.get(pid, 0); self.cc[pid] = (cc + 1) & 0xF
            chunk = data[pos:pos + 184]
            hdr = bytes([0x47, (0x40 if first else 0) | (pid >> 8), pid & 0xFF])
            if len(chunk) < 184:
                if psi:
                    pkt = hdr + bytes([0x10 | cc]) + chunk + b'\xff' * (184 - len(chunk))
                else:
                    stuff = 184 - len(chunk)
                    af = bytes([stuff - 1]) + (b'\x00' + b'\xff' * (stuff - 2) if stuff >= 2 else b'')
                    pkt = hdr + bytes([0x30 | cc]) + af + chunk
            else:
                pkt = hdr + bytes([0x10 | cc]) + chunk
            assert len(pkt) == 188
            res.append(pkt); pos += 184; first = False
        return res
    def psi(self, pid, table_id, ext, body):
        sec = bytes([table_id]) + struct.pack('>H', 0xB000 | (len(body) + 9)) + struct.pack('>H', ext) + b'\xc1\x00\x00' + body
        sec += struct.pack('>I', crc32_mpeg(sec))
        return self.packets(pid, sec, psi=True)

def pat_pmt(w, pmt_pid, streams):
    pkts = w.psi(0, 0x00, 1, struct.pack('>HH', 1, 0xE000 | pmt_pid))
    vpid = next((p for t, p, _ in streams if t in (0x1B, 0x24)), streams[0][1])
    body = struct.pack('>HH', 0xE000 | vpid, 0xF000)
    for t, p, info in streams:
        body += bytes([t]) + struct.pack('>HH', 0xE000 | p, 0xF000 | len(info)) + info
    pkts += w.psi(pmt_pid, 0x02, 1, body)
    return pkts

def main():
    mode, src, dst = sys.argv[1:4]
    seg = int(sys.argv[4]) if len(sys.argv) > 4 else 0
    if mode in ('pkt204',):
        d = open(src, 'rb').read()
        open(dst, 'wb').write(b''.join(d[i:i + 188] + b'\x00' * 16 for i in range(0, len(d), 188)))
        return
    pmt_pid, streams, events = parse(src)
    vtypes = (0x1B, 0x24)
    vpids = {p for t, p, _ in streams if t in vtypes}
    pidmap = {p: p for _, p, _ in streams}
    if mode == 'pidshift' and seg >= 2:
        pmt_pid = 0x1500
        pidmap = {p: 0x300 + i for i, (_, p, _) in enumerate(streams)}
    out_streams = [(t, pidmap[p], info) for t, p, info in streams]
    w = Writer()
    pkts = pat_pmt(w, pmt_pid, out_streams)

    # asplit state: audio bytes queued with the PTS of each original PES start offset.
    abuf = bytearray(); amarks = []; asid = 0xC0
    def flush_audio(final):
        nonlocal abuf, amarks
        CH = 700
        while len(abuf) >= CH or (final and abuf):
            n = min(CH, len(abuf))
            # PTS of the first frame that starts inside this chunk: we only know original PES starts.
            pts = next((p for off, p in amarks if off < n), None)
            pkts.extend(w.packets(apid_out, make_pes(asid, pts, None, bytes(abuf[:n]), False)))
            abuf = abuf[n:]
            amarks = [(off - n, p) for off, p in amarks if off >= n]

    apid_out = None
    for pid, pes in events:
        opid = pidmap[pid]
        if len(pes) < 9 or pes[:3] != b'\x00\x00\x01':
            continue
        sid, pts, dts, data = pes_fields(pes)
        if pid in vpids and mode.startswith('vsplit'):
            step = 4099 if mode == 'vsplit_small' else 30000
            for k in range(0, len(data), step):
                keep = k == 0 or mode == 'vsplit_pts'
                pkts.extend(w.packets(opid, make_pes(sid, pts if keep else None, dts if keep else None, data[k:k + step], True)))
        elif pid not in vpids and mode == 'asplit':
            apid_out = opid; asid = sid
            # Mark every ADTS frame start with its own PTS (PES PTS + k * 1024 samples).
            i = 0; k = 0
            while i + 7 <= len(data) and data[i] == 0xFF and (data[i + 1] & 0xF0) == 0xF0:
                sr = [96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000][(data[i + 2] >> 2) & 0xF]
                flen = ((data[i + 3] & 3) << 11) | (data[i + 4] << 3) | (data[i + 5] >> 5)
                amarks.append((len(abuf) + i, None if pts is None else pts + k * 1024 * 90000 // sr))
                i += flen; k += 1
            abuf += data
            flush_audio(False)
        else:
            pkts.extend(w.packets(opid, make_pes(sid, pts, dts, data, pid in vpids)))
    if mode == 'asplit':
        flush_audio(True)
    open(dst, 'wb').write(b''.join(pkts))

if __name__ == '__main__':
    main()
