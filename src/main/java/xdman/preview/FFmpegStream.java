package xdman.preview;

import xdman.Config;
import xdman.mediaconversion.FFmpeg;
import xdman.util.Logger;
import xdman.util.XDMUtils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;

public class FFmpegStream extends InputStream implements Runnable {
	String input1, input2;
	Process proc;
	InputStream in;
	long read;
	Thread t;

	public FFmpegStream(String input1, String input2) throws IOException {
		this.input1 = input1;
		this.input2 = input2;
		init();
		t = new Thread(this);
		t.start();
	}

	@Override
	public void close() throws IOException {
		try {
			in.close();
		} catch (Exception e) {
		}
		try {
			Logger.log("closing");
			proc.destroyForcibly();
			t.interrupt();
		} catch (Exception e) {
		}
	}

	private void init() throws IOException {
		ArrayList<String> args = new ArrayList<>();
		File ffFile = new File(Config.getInstance().getDataFolder(),
				FFmpeg.getFFMpeg());
		if (!ffFile.exists()) {
			ffFile = new File(XDMUtils.getJarFile().getParentFile(),
					FFmpeg.getFFMpeg());
			if (!ffFile.exists()) {
				return;
			}
		}
		args.add(ffFile.getAbsolutePath());
		args.add("-err_detect");
		args.add("ignore_err");
		args.add("-i");
		args.add(input1);
		if (input2 != null) {
			args.add("-i");
			args.add(input2);
		}
		args.add("-f");
		args.add("webm");
		args.add("-vcodec");
		args.add("vp8");
		args.add("-cpu-used");
		args.add("5");
		args.add("-deadline");
		args.add("realtime");
		args.add("-q:v");
		args.add("1");
		args.add("-acodec");
		args.add("libvorbis");
		args.add("pipe:1");
		args.add("-blocksize");
		args.add("8192");
		args.add("-nostdin");

		ProcessBuilder pb = new ProcessBuilder(args);
		//pb.redirectError(new File(System.getProperty("user.home"), "error.txt"));
		proc = pb.start();
		in = proc.getInputStream();
	}

	@Override
	public void run() {
		while (true) {
			long last = read;
			try {
				Thread.sleep(2000);
			} catch (Exception e) {
				Logger.log("interrupted returning", e);
				return;
			}
			if (read - last < 1) {
				try {
					in.close();
				} catch (Exception e) {
					Logger.log(e);
				}
				Logger.log("closing hanged ffmpeg");
				proc.destroyForcibly();
				break;
			}
		}
	}

	@Override
	public int read() throws IOException {
		int x = in.read();
		if (x != -1) {
			read++;
		}
		return x;
	}

	@Override
	public int read(byte[] b, int off, int len) throws IOException {
		int x = in.read(b, off, len);
		if (x != -1) {
			read += x;
			// Logger.log(new String(b, 0, x));
		} else {
			Logger.log("stream ended after " + read + " bytes");
			// Logger.log(proc.exitValue());
		}
		return x;
	}

}