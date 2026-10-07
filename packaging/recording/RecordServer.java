/*
 * Test server for recording the AppCDS class lists (APPCDS.md, PACKAGING.md "Recording the class list").
 *
 * The recording run of XDM (build-bundle.sh --record-server / build-bundle.ps1 -RecordServer) drives
 * a scripted session against this server: plain, chunked and Basic-auth HTTP downloads, HLS (with a
 * separate audio rendition), AES-128 HLS, DASH, a progressive MP4, two batches, and downloads through
 * an authenticating HTTP proxy. Real HTTPS downloads go to the internet (see session.properties).
 *
 *   java packaging/recording/RecordServer.java [--port 8780] [--assets build/record-assets]
 *
 * Needs JDK 17+ and, the first time only, ffmpeg on PATH to generate the media into the assets folder.
 * The proxy listens on port + 1. Every machine that records can share one server: run it anywhere the
 * recording machines can reach, and pass http://<host>:<port> to the build.
 */

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RecordServer {
    static final String USER = "xdm";
    static final String PASSWORD = "xdm-record";
    static final String HTTPS_URL = "https://ffmpeg.org/releases/ffmpeg-7.1.1.tar.xz";
    static final String HTTPS_PROXY_URL =
            "https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-stdlib/2.0.0/kotlin-stdlib-2.0.0.jar";

    static Path assets;
    static int port = 8780;

    public static void main(String[] args) throws Exception {
        assets = Paths.get("build", "record-assets");
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--assets" -> assets = Paths.get(args[++i]);
                default -> {
                    System.err.println("Usage: java RecordServer.java [--port 8780] [--assets dir]");
                    System.exit(2);
                }
            }
        }
        assets = assets.toAbsolutePath();
        prepareFiles();
        prepareMedia();

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 64);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", RecordServer::handle);
        server.start();
        Thread proxy = new Thread(() -> runProxy(port + 1), "proxy");
        proxy.start();

        System.out.println("Assets:  " + assets);
        System.out.println("Server:  http://<this host>:" + port + "/  (session: /session.properties)");
        System.out.println("Proxy:   <this host>:" + (port + 1) + "  (Basic, " + USER + " / " + PASSWORD + ")");
        for (String host : hostAddresses()) {
            System.out.println("Record with: --record-server http://" + host + ":" + port
                    + "   (Windows: -RecordServer http://" + host + ":" + port + ")");
        }
    }

    // ---- assets ----------------------------------------------------------------------------

    static void prepareFiles() throws IOException {
        randomFile("files/data-32m.bin", 32 << 20, 1);
        randomFile("auth/secret-8m.bin", 8 << 20, 2);
        randomFile("chunked/stream-6m.bin", 6 << 20, 3);
        randomFile("proxy/via-proxy-2m.bin", 2 << 20, 4);
        for (int i = 1; i <= 6; i++) {
            randomFile("batch/file-" + i + ".bin", (512 << 10) * i, 10 + i);
        }
    }

    static void randomFile(String rel, int size, long seed) throws IOException {
        Path p = assets.resolve(rel);
        if (Files.isRegularFile(p) && Files.size(p) == size) return;
        Files.createDirectories(p.getParent());
        byte[] data = new byte[size];
        new Random(seed).nextBytes(data);
        Files.write(p, data);
    }

    /** The media is made once by ffmpeg; delete the media folder to make it again. */
    static void prepareMedia() throws Exception {
        Path media = assets.resolve("media");
        if (Files.isRegularFile(media.resolve(".done"))) return;
        System.out.println("Generating HLS/DASH test media with ffmpeg in " + media + " ...");
        deleteTree(media);
        Files.createDirectories(media);
        String clip = media.resolve("clip.mp4").toString();
        // 12 s, 640x360, keyframe every 2 s so 4 s segments cut cleanly; AAC stereo.
        ffmpeg("-f", "lavfi", "-i", "testsrc2=size=640x360:rate=25", "-f", "lavfi", "-i",
                "sine=frequency=440:sample_rate=44100", "-t", "12", "-c:v", "libx264", "-preset", "veryfast",
                "-pix_fmt", "yuv420p", "-g", "50", "-keyint_min", "50", "-sc_threshold", "0", "-c:a", "aac",
                "-ac", "2", "-b:a", "96k", "-movflags", "+faststart", clip);

        // HLS: master playlist with a video-only variant and a separate audio rendition (two TS streams).
        Path hls = media.resolve("hls");
        Files.createDirectories(hls.resolve("video"));
        Files.createDirectories(hls.resolve("audio"));
        ffmpeg("-i", clip, "-map", "0:v", "-c", "copy", "-bsf:v", "h264_mp4toannexb", "-f", "hls",
                "-hls_time", "4", "-hls_playlist_type", "vod",
                "-hls_segment_filename", hls.resolve("video/seg%03d.ts").toString(),
                hls.resolve("video/index.m3u8").toString());
        ffmpeg("-i", clip, "-map", "0:a", "-c", "copy", "-f", "hls", "-hls_time", "4", "-hls_playlist_type", "vod",
                "-hls_segment_filename", hls.resolve("audio/seg%03d.ts").toString(),
                hls.resolve("audio/index.m3u8").toString());
        Files.writeString(hls.resolve("master.m3u8"), String.join("\n",
                "#EXTM3U",
                "#EXT-X-VERSION:3",
                "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"aud\",NAME=\"English\",LANGUAGE=\"en\",DEFAULT=YES,AUTOSELECT=YES,URI=\"audio/index.m3u8\"",
                "#EXT-X-STREAM-INF:BANDWIDTH=900000,RESOLUTION=640x360,CODECS=\"avc1.64001e,mp4a.40.2\",AUDIO=\"aud\"",
                "video/index.m3u8", ""));

        // HLS with AES-128: one muxed media playlist (no master), key served next to it.
        Path aes = media.resolve("hls-aes");
        Files.createDirectories(aes);
        byte[] key = new byte[16];
        new Random(42).nextBytes(key);
        Files.write(aes.resolve("key.bin"), key);
        Path keyInfo = media.resolve("keyinfo.txt");
        Files.writeString(keyInfo, "key.bin\n" + aes.resolve("key.bin") + "\n0123456789abcdef0123456789abcdef\n");
        ffmpeg("-i", clip, "-c", "copy", "-bsf:v", "h264_mp4toannexb", "-f", "hls", "-hls_time", "4",
                "-hls_playlist_type", "vod", "-hls_key_info_file", keyInfo.toString(),
                "-hls_segment_filename", aes.resolve("seg%03d.ts").toString(), aes.resolve("index.m3u8").toString());
        Files.delete(keyInfo);

        // DASH: separate video and audio adaptation sets, SegmentTemplate with $Number$.
        Path dash = media.resolve("dash");
        Files.createDirectories(dash);
        ffmpeg("-i", clip, "-map", "0:v", "-map", "0:a", "-c", "copy", "-f", "dash", "-seg_duration", "4",
                "-use_template", "1", "-use_timeline", "0", "-adaptation_sets", "id=0,streams=v id=1,streams=a",
                "-init_seg_name", "init-$RepresentationID$.m4s",
                "-media_seg_name", "chunk-$RepresentationID$-$Number%05d$.m4s", dash.resolve("manifest.mpd").toString());

        Files.writeString(media.resolve(".done"), "ok\n");
    }

    static void ffmpeg(String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("ffmpeg", "-hide_banner", "-loglevel", "error", "-y"));
        cmd.addAll(List.of(args));
        Process p;
        try {
            p = new ProcessBuilder(cmd).inheritIO().start();
        } catch (IOException e) {
            throw new IllegalStateException("ffmpeg is needed once to generate the test media: " + e.getMessage());
        }
        if (p.waitFor() != 0) throw new IllegalStateException("ffmpeg failed: " + cmd);
    }

    static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (var walk = Files.walk(p)) {
            for (Path f : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(f);
        }
    }

    // ---- HTTP ------------------------------------------------------------------------------

    static final Pattern RANGE = Pattern.compile("bytes=(\\d*)-(\\d*)");
    static final DateTimeFormatter HTTP_DATE = DateTimeFormatter.RFC_1123_DATE_TIME;

    static void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        int status = 500;
        try (ex) {
            if (path.equals("/session.properties")) {
                status = text(ex, 200, session());
            } else if (path.equals("/")) {
                status = text(ex, 200, "XDM class-list recording server. See /session.properties\n");
            } else {
                Path file = assets.resolve(path.substring(1)).normalize();
                if (!file.startsWith(assets) || !Files.isRegularFile(file) || path.contains("/.")) {
                    status = text(ex, 404, "not found\n");
                } else if (path.startsWith("/auth/") && !authorized(ex.getRequestHeaders().getFirst("Authorization"))) {
                    ex.getResponseHeaders().add("WWW-Authenticate", "Basic realm=\"XDM recording\"");
                    status = text(ex, 401, "authentication required\n");
                } else {
                    status = serve(ex, file, path.startsWith("/chunked/"));
                }
            }
        } catch (IOException e) {
            // The client went away mid-body (a segment XDM cancelled or re-split): nothing to do.
        } finally {
            String range = ex.getRequestHeaders().getFirst("Range");
            System.out.println("[http]  " + ex.getRequestMethod() + " " + path + " -> " + status
                    + (range != null ? " (" + range + ")" : ""));
        }
    }

    static String session() {
        return String.join("\n",
                "# Read by XDM's recording session (xdm.app.recording.RecordingSession)",
                "user=" + USER,
                "password=" + PASSWORD,
                "proxy.port=" + (port + 1),
                "https.url=" + HTTPS_URL,
                "https.proxy.url=" + HTTPS_PROXY_URL,
                "http.file=/files/data-32m.bin",
                "http.proxy.file=/proxy/via-proxy-2m.bin",
                "auth.file=/auth/secret-8m.bin",
                "chunked.file=/chunked/stream-6m.bin",
                "hls=/media/hls/master.m3u8",
                "hls.aes=/media/hls-aes/index.m3u8",
                "hls.media=/media/hls/video/index.m3u8",
                "dash=/media/dash/manifest.mpd",
                "video=/media/clip.mp4",
                "video.size=" + sizeOf("media/clip.mp4"),
                "batch=/batch/file-1.bin,/batch/file-2.bin,/batch/file-3.bin",
                "batch.each=/batch/file-4.bin,/batch/file-5.bin,/batch/file-6.bin",
                "");
    }

    static long sizeOf(String rel) {
        try {
            return Files.size(assets.resolve(rel));
        } catch (IOException e) {
            return -1;
        }
    }

    static boolean authorized(String header) {
        String expected = "Basic " + Base64.getEncoder()
                .encodeToString((USER + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8));
        return expected.equals(header);
    }

    static int text(HttpExchange ex, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
        return status;
    }

    /** Static file with a single byte range, Last-Modified and ETag; [chunked] hides the length. */
    static int serve(HttpExchange ex, Path file, boolean chunked) throws IOException {
        String name = file.getFileName().toString();
        String accept = ex.getRequestHeaders().getFirst("Accept-Encoding");
        if ((name.endsWith(".m3u8") || name.endsWith(".mpd")) && accept != null && accept.contains("gzip")
                && ex.getRequestHeaders().getFirst("Range") == null) {
            // Playlists and manifests compressed, as CDNs serve them.
            ex.getResponseHeaders().set("Content-Type", contentType(name));
            ex.getResponseHeaders().set("Content-Encoding", "gzip");
            ex.sendResponseHeaders(200, 0);
            try (var gz = new java.util.zip.GZIPOutputStream(ex.getResponseBody())) {
                Files.copy(file, gz);
            }
            return 200;
        }
        long size = Files.size(file);
        long modified = Files.getLastModifiedTime(file).toMillis();
        var h = ex.getResponseHeaders();
        h.set("Content-Type", contentType(file.getFileName().toString()));
        h.set("Last-Modified", HTTP_DATE.format(ZonedDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(modified), ZoneOffset.UTC)));
        h.set("ETag", "\"" + Long.toHexString(size) + "-" + Long.toHexString(modified) + "\"");

        long start = 0, end = size - 1;
        int status = 200;
        String range = chunked ? null : ex.getRequestHeaders().getFirst("Range");
        if (!chunked) h.set("Accept-Ranges", "bytes");
        if (range != null) {
            Matcher m = RANGE.matcher(range.trim());
            if (m.matches() && !(m.group(1).isEmpty() && m.group(2).isEmpty())) {
                if (m.group(1).isEmpty()) {
                    start = Math.max(0, size - Long.parseLong(m.group(2)));
                } else {
                    start = Long.parseLong(m.group(1));
                    if (!m.group(2).isEmpty()) end = Math.min(end, Long.parseLong(m.group(2)));
                }
                if (start >= size || start > end) {
                    h.set("Content-Range", "bytes */" + size);
                    ex.sendResponseHeaders(416, -1);
                    return 416;
                }
                status = 206;
                h.set("Content-Range", "bytes " + start + "-" + end + "/" + size);
            }
        }
        long length = end - start + 1;
        boolean head = ex.getRequestMethod().equalsIgnoreCase("HEAD");
        ex.sendResponseHeaders(status, head ? -1 : chunked ? 0 : length);
        if (head) return status;
        try (var in = Files.newInputStream(file); OutputStream out = ex.getResponseBody()) {
            in.skipNBytes(start);
            byte[] buf = new byte[64 << 10];
            long left = length;
            while (left > 0) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                if (n < 0) break;
                out.write(buf, 0, n);
                left -= n;
            }
        }
        return status;
    }

    static String contentType(String name) {
        if (name.endsWith(".m3u8")) return "application/vnd.apple.mpegurl";
        if (name.endsWith(".mpd")) return "application/dash+xml";
        if (name.endsWith(".ts")) return "video/mp2t";
        if (name.endsWith(".m4s")) return "video/iso.segment";
        if (name.endsWith(".mp4")) return "video/mp4";
        return "application/octet-stream";
    }

    // ---- proxy -----------------------------------------------------------------------------

    /** HTTP proxy with Basic auth: CONNECT tunnels, and absolute-form requests, one per connection. */
    static void runProxy(int proxyPort) {
        try (ServerSocket ss = new ServerSocket(proxyPort, 64)) {
            while (true) {
                Socket client = ss.accept();
                Thread.ofVirtual().start(() -> proxyConnection(client));
            }
        } catch (IOException e) {
            System.err.println("[proxy] stopped: " + e);
        }
    }

    static void proxyConnection(Socket client) {
        try (client) {
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();
            List<String> head = readHead(in);
            if (head.isEmpty()) return;
            String[] line = head.get(0).split(" ");
            if (line.length < 3) return;
            String auth = header(head, "Proxy-Authorization");
            if (!authorized(auth)) {
                System.out.println("[proxy] " + line[0] + " " + line[1] + " -> 407");
                out.write(("HTTP/1.1 407 Proxy Authentication Required\r\n"
                        + "Proxy-Authenticate: Basic realm=\"XDM recording proxy\"\r\n"
                        + "Content-Length: 0\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
                return;
            }
            if (line[0].equalsIgnoreCase("CONNECT")) {
                String[] hp = line[1].split(":");
                try (Socket upstream = new Socket(hp[0], hp.length > 1 ? Integer.parseInt(hp[1]) : 443)) {
                    out.write("HTTP/1.1 200 Connection established\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
                    out.flush();
                    System.out.println("[proxy] CONNECT " + line[1] + " -> 200");
                    pipe(client, upstream);
                }
                return;
            }
            URI uri = URI.create(line[1]);
            int p = uri.getPort() > 0 ? uri.getPort() : 80;
            try (Socket upstream = new Socket(uri.getHost(), p)) {
                StringBuilder req = new StringBuilder();
                String target = (uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath())
                        + (uri.getRawQuery() != null ? "?" + uri.getRawQuery() : "");
                req.append(line[0]).append(' ').append(target).append(" HTTP/1.1\r\n");
                for (String hl : head.subList(1, head.size())) {
                    String name = hl.substring(0, Math.max(0, hl.indexOf(':'))).trim().toLowerCase(Locale.ROOT);
                    if (name.startsWith("proxy-") || name.equals("connection")) continue;
                    req.append(hl).append("\r\n");
                }
                req.append("Connection: close\r\n\r\n");
                upstream.getOutputStream().write(req.toString().getBytes(StandardCharsets.ISO_8859_1));
                upstream.getOutputStream().flush();
                System.out.println("[proxy] " + line[0] + " " + line[1] + " -> forwarded");
                upstream.getInputStream().transferTo(out);
            }
        } catch (IOException e) {
            // Either side closed early: normal for a download segment that was cut short.
        }
    }

    static void pipe(Socket a, Socket b) throws IOException {
        Thread t = Thread.ofVirtual().start(() -> {
            try {
                b.getInputStream().transferTo(a.getOutputStream());
            } catch (IOException ignored) {
            } finally {
                try { a.shutdownOutput(); } catch (IOException ignored) { }
            }
        });
        try {
            a.getInputStream().transferTo(b.getOutputStream());
            b.shutdownOutput();
        } catch (IOException ignored) {
        }
        try {
            t.join();
        } catch (InterruptedException ignored) {
        }
    }

    static List<String> readHead(InputStream in) throws IOException {
        List<String> lines = new ArrayList<>();
        ByteArrayOutputStream cur = new ByteArrayOutputStream();
        int c, prev = -1;
        while ((c = in.read()) != -1) {
            if (c == '\n' && prev == '\r') {
                String s = cur.toString(StandardCharsets.ISO_8859_1);
                s = s.substring(0, s.length() - 1);
                if (s.isEmpty()) return lines;
                lines.add(s);
                cur.reset();
            } else {
                cur.write(c);
            }
            prev = c;
        }
        return lines;
    }

    static String header(List<String> head, String name) {
        for (String hl : head) {
            int i = hl.indexOf(':');
            if (i > 0 && hl.substring(0, i).trim().equalsIgnoreCase(name)) return hl.substring(i + 1).trim();
        }
        return null;
    }

    static List<String> hostAddresses() {
        List<String> out = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address) out.add(a.getHostAddress());
                }
            }
        } catch (SocketException ignored) {
        }
        return out;
    }
}
