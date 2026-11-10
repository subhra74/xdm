"""Test HTTP server for XDM's link handling, standard library only (see README.md).

  /limit.bin    At most MAX_CONN transfers at once. More get a 403 that looks like a CDN's
                connection limit ("deny by advanced rate limit"): the link itself still works.
  /link         A site's download link: 302 to /signed.bin?sig=N, newly signed on every request.
                Records the Cookie header it gets.
  /signed.bin   Serves only the newest signature, and only for SIG_TTL seconds after it was issued.
                An expired one gets 403, and transfers still running on it are cut.
  /stats        Counters as JSON.

Every file is the same SIZE bytes of seeded random data (SHA-256 printed at start), served at
PER_CONN_BPS per connection, with Range support and a fixed Last-Modified.

Usage: python3 -I server.py [port] [sig_ttl_seconds]
"""
import hashlib
import json
import random
import re
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

SIZE = 24 * 1024 * 1024
PER_CONN_BPS = 1024 * 1024
MAX_CONN = 3
SIG_TTL = float(sys.argv[2]) if len(sys.argv) > 2 else 2.0
DATA = random.Random(42).randbytes(SIZE)
LAST_MODIFIED = "Tue, 10 Nov 2026 10:00:00 GMT"

lock = threading.Lock()
active = 0
stats = {"limit_403": 0, "limit_peak": 0, "expired_403": 0, "link_hits": 0, "link_cookies": []}
sig = {"n": 0, "issued": 0.0}


def sig_valid(n):
    with lock:
        return n == sig["n"] and time.time() - sig["issued"] < SIG_TTL


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        sys.stderr.write("%.3f %s\n" % (time.time(), fmt % args))

    def send_body(self, status, body, content_type="text/plain"):
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def refuse(self, why, key):
        with lock:
            stats[key] += 1
        self.send_response(403)
        self.send_header("Content-Type", "text/html")
        self.send_header("Error-Info", "condition limit ban")
        self.send_header("x-exception-info", why)
        self.send_header("Content-Length", "0")
        self.end_headers()

    def do_GET(self):
        global active
        path, _, query = self.path.partition("?")
        if path == "/stats":
            with lock:
                body = json.dumps(stats).encode()
            self.send_body(200, body, "application/json")
            return
        if path == "/link":
            with lock:
                stats["link_hits"] += 1
                stats["link_cookies"].append(self.headers.get("Cookie"))
                sig["n"] += 1
                sig["issued"] = time.time()
                n = sig["n"]
            self.send_response(302)
            self.send_header("Location", "/signed.bin?sig=%d" % n)
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        if path not in ("/limit.bin", "/signed.bin"):
            self.send_body(404, b"not found")
            return
        signed = path == "/signed.bin"
        n = None
        if signed:
            m = re.search(r"(?:^|&)sig=(\d+)", query)
            n = int(m.group(1)) if m else -1
            if not sig_valid(n):
                self.refuse("signature expired", "expired_403")
                return
        limited = path == "/limit.bin"
        if limited:
            with lock:
                refused = active >= MAX_CONN
                if not refused:
                    active += 1
                    stats["limit_peak"] = max(stats["limit_peak"], active)
            if refused:
                self.refuse("deny by advanced rate limit", "limit_403")
                return
        try:
            self.serve_range(signed, n)
        finally:
            if limited:
                with lock:
                    active -= 1

    def serve_range(self, signed, n):
        start, end = 0, SIZE - 1
        m = re.match(r"bytes=(\d+)-(\d*)", self.headers.get("Range", ""))
        if m:
            start = int(m.group(1))
            if m.group(2):
                end = min(int(m.group(2)), SIZE - 1)
            self.send_response(206)
            self.send_header("Content-Range", "bytes %d-%d/%d" % (start, end, SIZE))
        else:
            self.send_response(200)
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Last-Modified", LAST_MODIFIED)
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Content-Length", str(end - start + 1))
        self.end_headers()
        pos = start
        step = 64 * 1024
        try:
            while pos <= end:
                if signed and not sig_valid(n):
                    # The signature expired mid-transfer: cut the connection.
                    self.close_connection = True
                    self.connection.shutdown(2)
                    return
                k = min(step, end - pos + 1)
                self.wfile.write(DATA[pos:pos + k])
                pos += k
                time.sleep(k / PER_CONN_BPS)
        except OSError:
            pass  # the client went away (pause, cancel, a cut)


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 0
    srv = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    srv.daemon_threads = True
    print("PORT %d SIZE %d SHA256 %s" % (srv.server_address[1], SIZE, hashlib.sha256(DATA).hexdigest()), flush=True)
    srv.serve_forever()
