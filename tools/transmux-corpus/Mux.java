import xdm.core.media.muxer.impl.TransmuxingMuxer;
import xdm.core.storage.LocalStorage;
import java.nio.file.*;
import java.util.*;

/** Usage: Mux <out.mp4> <discontinuous:true|false> <videoList.txt> [audioList.txt] */
public class Mux {
    public static void main(String[] a) throws Exception {
        String out = a[0];
        boolean disc = Boolean.parseBoolean(a[1]);
        List<String> video = Files.readAllLines(Path.of(a[2]));
        String tmp = Files.createTempDirectory("mux").toString();
        TransmuxingMuxer m = new TransmuxingMuxer(tmp, null);
        kotlin.jvm.functions.Function1<Integer, kotlin.Unit> cb = p -> kotlin.Unit.INSTANCE;
        boolean ok;
        if (a.length > 3) {
            List<String> audio = Files.readAllLines(Path.of(a[3]));
            ok = m.mux(audio, video, LocalStorage.INSTANCE.file(out), cb, tmp, false, false, disc);
        } else {
            ok = m.mux(video, LocalStorage.INSTANCE.file(out), cb, tmp, false, false, disc);
        }
        System.out.println(ok ? "OK" : "FAIL");
        System.exit(ok ? 0 : 1);
    }
}
