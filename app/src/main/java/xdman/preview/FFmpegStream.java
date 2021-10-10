/*
 * Copyright (c)  Subhra Das Gupta
 *
 * This file is part of Xtreme Download Manager.
 *
 * Xtreme Download Manager is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * Xtreme Download Manager is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with Xtream Download Manager; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA
 * 
 */

package xdman.preview;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Objects;

import org.tinylog.Logger;

import xdman.Config;
import xdman.util.IOUtils;
import xdman.util.XDMUtils;

public class FFmpegStream extends InputStream implements Runnable {

	private final String input1;
	private final String input2;
	private Process proc;
	private InputStream in;
	private long read;
	private final Thread executorThread;

	public FFmpegStream(String input1, String input2) throws IOException {
		this.input1 = input1;
		this.input2 = input2;
		init();
		executorThread = new Thread(this);
		executorThread.start();
	}

	@Override
	public void close() throws IOException {
		IOUtils.closeFlow(in);
		try {
			Logger.info("closing");
			proc.destroyForcibly();
			executorThread.interrupt();
		} catch (Exception e) {
			Logger.error(e);
		}
	}

	private void init() throws IOException {
		ArrayList<String> args = new ArrayList<>();
		File ffFile = new File(Config.getInstance().getDataFolder(),
				System.getProperty("os.name").toLowerCase().contains("windows") ? "ffmpeg.exe" : "ffmpeg");
		if (!ffFile.exists()) {
			ffFile = new File(Objects.requireNonNull(XDMUtils.getJarFile()).getParentFile(),
					System.getProperty("os.name").toLowerCase().contains("windows") ? "ffmpeg.exe" : "ffmpeg");
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
				Logger.error(e);
				Logger.warn("interrupted returning");
				return;
			}
			if (read - last < 1) {
				IOUtils.closeFlow(in);
				Logger.info("closing hanged ffmpeg");
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
		} else {
			Logger.info("stream ended after " + read + " bytes");
		}
		return x;
	}

}