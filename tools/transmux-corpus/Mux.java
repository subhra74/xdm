import xdm.core.media.muxer.SegmentInfo;
import xdm.core.media.muxer.impl.TransmuxingMuxer;
import xdm.core.storage.LocalStorage;
import java.nio.file.*;
import java.util.*;

/**
 * Usage: Mux <out.mp4> <discontinuous:true|false> <videoList.txt> [audioList.txt]
 * XDM_KEEP_ORIGINAL=1 in the environment: "Save as MP4 where possible" off (TS kept as .ts).
 * Prints OK or FAIL, then "RESULT <container written> <how>" (REMUXED, TS_FALLBACK, AS_IS).
 * A list holds one segment per line; a line "#EXT-X-DISCONTINUITY" before a segment starts a new
 * section, passed to the muxer as the app does (Muxer.setSegmentInfo).
 */
public class Mux {
    public static void main(String[] a) throws Exception {
        String out = a[0];
        boolean disc = Boolean.parseBoolean(a[1]);
        Map<String, SegmentInfo> info = new HashMap<>();
        List<String> video = read(a[2], info);
        String tmp = Files.createTempDirectory("mux").toString();
        TransmuxingMuxer m = new TransmuxingMuxer(tmp, null);
        m.setKeepOriginalFormat("1".equals(System.getenv("XDM_KEEP_ORIGINAL")));
        kotlin.jvm.functions.Function1<Integer, kotlin.Unit> cb = p -> kotlin.Unit.INSTANCE;
        boolean ok;
        if (a.length > 3) {
            List<String> audio = read(a[3], info);
            m.setSegmentInfo(info);
            ok = m.mux(audio, video, LocalStorage.INSTANCE.file(out), cb, tmp, false, false, disc);
        } else {
            m.setSegmentInfo(info);
            ok = m.mux(video, LocalStorage.INSTANCE.file(out), cb, tmp, false, false, disc);
        }
        System.out.println(ok ? "OK" : "FAIL");
        System.out.println("RESULT " + m.getOutputContainer() + " " + m.getOutputKind()
            + (ok ? "" : " " + String.valueOf(m.getLastError()).replace('\n', ' ')));
        System.exit(ok ? 0 : 1);
    }

    /** Segment paths of a list; their sections go into [info] when the list marks any discontinuity. */
    static List<String> read(String list, Map<String, SegmentInfo> info) throws Exception {
        List<String> files = new ArrayList<>();
        Map<String, SegmentInfo> sections = new HashMap<>();
        int section = 0;
        boolean marked = false;
        for (String line : Files.readAllLines(Path.of(list))) {
            line = line.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("#EXT-X-DISCONTINUITY")) { section++; marked = true; continue; }
            if (line.startsWith("#")) continue;
            files.add(line);
            sections.put(line, new SegmentInfo(0.0, section, false));
        }
        if (marked) info.putAll(sections);
        return files;
    }
}
