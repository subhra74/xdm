package xdm.core.media.muxer.impl;

import java.io.*;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import xdm.core.Config;
import xdm.core.media.muxer.Muxer;
import xdm.core.util.Logger;
import xdm.core.util.PlatformUtils;
import xdm.core.util.StringUtils;

public class FFmpegMuxer implements Muxer {
  private static final Pattern rxDuration =
      Pattern.compile("Duration:\\s+(\\d\\d):(\\d\\d):(\\d\\d)\\.\\d\\d,\\s");
  private static final Pattern rxTime =
      Pattern.compile("frame=.*?time=(\\d\\d):(\\d\\d):(\\d\\d)\\.\\d\\d.*?bitrate=");
  private long duration;
  private long time;
  private long lastTick;
  private final AtomicReference<Process> ffmpegProcess = new AtomicReference<>();

  @Override
  public boolean mux(
      List<String> segments, String outputFile, IntConsumer progressCallback, String tempDir) {
    Logger.info("Merging segments");
    File contatFile = new File(tempDir, UUID.randomUUID() + ".txt");
    try {
      try (FileWriter writer = new FileWriter(contatFile)) {
        for (String s : segments) {
          writer.write("file '" + s + "'\n");
        }
      }
      String pathToFFmpeg = findFFmpegBinary();
      spawnFFmpeg(
          progressCallback,
          pathToFFmpeg,
          "-f",
          "concat",
          "-safe",
          "0",
          "-i",
          contatFile.getAbsolutePath(),
          "-auto_convert",
          "1",
          "-acodec",
          "copy",
          "-vcodec",
          "copy",
          outputFile,
          "-y");
      Logger.info("Merging success, file: " + outputFile);
      return true;
    } catch (Exception ex) {
      Logger.error("XDM", "FFmpeg merging failed", ex);
    } finally {
//      contatFile.delete();
    }
    return false;
  }

  @Override
  public boolean mux(
      String file1, String file2, String outputFile, IntConsumer progressCallback, String tempDir) {
    try {
      String pathToFFmpeg = findFFmpegBinary();
      spawnFFmpeg(
          progressCallback,
          pathToFFmpeg,
          "-i",
          file1,
          "-i",
          file2,
          "-acodec",
          "copy",
          "-vcodec",
          "copy",
          "-map",
          "0?",
          "-map",
          "1?",
          outputFile,
          "-y");
      Logger.info("FFmpeg merge success!");
      return true;
    } catch (Exception ex) {
      Logger.error("FFmpeg merging failed", ex);
    }
    return false;
  }

  @Override
  public boolean mux(
      List<String> audioSegments,
      List<String> videoSegments,
      String outputFile,
      IntConsumer progressCallback,
      String tempDir) {
    final AtomicInteger totalProgress = new AtomicInteger(0);
    IntConsumer callback =
        prg -> {
          int p = totalProgress.addAndGet(prg);
          progressCallback.accept(Math.min(p / 3, 100));
        };
    File audioPart = new File(tempDir, UUID.randomUUID() + ".mp4");
    File videoPart = new File(tempDir, UUID.randomUUID() + ".mp4");
    try {
      if (!this.mux(audioSegments, audioPart.getAbsolutePath(), callback, tempDir)) {
        return false;
      }
      if (!this.mux(videoSegments, videoPart.getAbsolutePath(), callback, tempDir)) {
        return false;
      }
      return this.mux(
          videoPart.getAbsolutePath(), audioPart.getAbsolutePath(), outputFile, callback, tempDir);
    } finally {
//      audioPart.delete();
//      videoPart.delete();
    }
  }

  @Override
  public void stop() {
    Process proc = this.ffmpegProcess.get();
    if (proc != null && proc.isAlive()) proc.destroy();
  }

  private static List<String> getExecNames() {
    return PlatformUtils.isWindows()
        ? Arrays.asList("ffmpeg-x86.exe", "ffmpeg.exe")
        : Collections.singletonList("ffmpeg");
  }

  private static String findFFmpegBinary() throws FileNotFoundException {
    final List<String> exeNames = getExecNames();
    for (String exe : exeNames) {
      File file = new File(Config.getInstance().getDataFolder(), exe);
      if (file.exists()) {
        return file.getAbsolutePath();
      }
      File baseDirectory = PlatformUtils.getBaseDirectory();
      if (baseDirectory != null) {
        file = new File(baseDirectory, exe);
        if (file.exists()) {
          return file.getAbsolutePath();
        }
      }
      String ffMpegHome = System.getenv("FFMPEG_HOME");
      if (ffMpegHome != null) {
        file = new File(ffMpegHome, exe);
        if (file.exists()) {
          return file.getAbsolutePath();
        }
      }
      String systemPath = PlatformUtils.getExecutableFromSystemPath(exe);
      if (systemPath != null) {
        return systemPath;
      }
    }
    throw new FileNotFoundException("FFmpeg executable not found!");
  }

  private void spawnFFmpeg(IntConsumer progressCallback, String... args) throws IOException {
    Logger.info(Arrays.asList(args));
    ProcessBuilder pb = new ProcessBuilder(args);
    pb.redirectErrorStream(true);
    Process proc = pb.start();
    this.ffmpegProcess.set(proc);
    try (BufferedReader br =
        new BufferedReader(new InputStreamReader(proc.getInputStream()), 1024)) {
      while (true) {
        String ln = br.readLine();
        if (ln == null) {
          break;
        }
        String text = ln.trim();
        Logger.info(text);
        processOutput(text, progressCallback);
      }
      int exitCode = proc.waitFor();
      if (exitCode != 0) {
        throw new IOException("FFmpeg non zero exit code: " + exitCode);
      }
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new IOException(ex);
    } finally {
      this.ffmpegProcess.set(null);
    }
  }

  private void processOutput(String text, IntConsumer progressCallback) {
    if (StringUtils.isNullOrEmpty(text)) {
      return;
    }
    try {
      if (duration == 0L) {
        Matcher md = rxDuration.matcher(text);
        duration = parseDuration(md);
      }
      Matcher mt = rxTime.matcher(text);
      long t = parseDuration(mt);
      if (t > 0) {
        time += t;
        long tick = System.currentTimeMillis();
        if (duration > 0 && tick - lastTick > 1000) {
          lastTick = tick;
          progressCallback.accept((int) ((time * 100) / duration));
        }
      }
    } catch (Exception e) {
      Logger.info("Unable to parse ffmpeg output: {}", text);
    }
  }

  private long parseDuration(Matcher matcher) {
    if (matcher.matches()) {
      int count = matcher.groupCount();
      if (count == 4) {
        long h = Long.parseLong(matcher.group(1).trim(), 10) * 3600;
        long m = Long.parseLong(matcher.group(2).trim(), 10) * 60;
        long s = Long.parseLong(matcher.group(3).trim(), 10);
        return h + m + s;
      }
    }
    return 0;
  }
}
