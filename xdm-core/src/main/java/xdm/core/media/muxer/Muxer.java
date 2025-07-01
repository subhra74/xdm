package xdm.core.media.muxer;

import java.util.List;
import java.util.function.IntConsumer;

public interface Muxer {

  boolean mux(
      List<String> segments, String outputFile, IntConsumer progressCallback, String tempDir);

  boolean mux(
      String file1, String file2, String outputFile, IntConsumer progressCallback, String tempDir);

  boolean mux(
      List<String> audioSegments,
      List<String> videoSegments,
      String outputFile,
      IntConsumer progressCallback,
      String tempDir);

  void stop();
}
