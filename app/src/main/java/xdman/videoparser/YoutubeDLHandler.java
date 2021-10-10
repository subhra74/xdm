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

package xdman.videoparser;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.tinylog.Logger;

import xdman.Config;
import xdman.network.ProxyResolver;
import xdman.network.http.WebProxy;
import xdman.util.IOUtils;
import xdman.util.StringUtils;
import xdman.util.XDMUtils;

@SuppressWarnings({ "ResultOfMethodCallIgnored", "unused" })
public class YoutubeDLHandler {
	private Process proc;
	private int exitCode;
	private final String url;
	private final String ydlLocation;
	private boolean stop;
	private final String user;
	private final String pass;

	private final ArrayList<YdlResponse.YdlVideo> videos;

	public YoutubeDLHandler(String url, String user, String pass) {
		this.url = url;
		this.videos = new ArrayList<>();
		File ydlFile = new File(Config.getInstance().getDataFolder(),
				System.getProperty("os.name").toLowerCase().contains("windows") ? "youtube-dl.exe" : "youtube-dl");
		if (!ydlFile.exists()) {
			ydlFile = new File(Objects.requireNonNull(XDMUtils.getJarFile()).getParentFile(),
					System.getProperty("os.name").toLowerCase().contains("windows") ? "youtube-dl.exe" : "youtube-dl");
		}
		this.ydlLocation = ydlFile.getAbsolutePath();
		this.user = user;
		this.pass = pass;
	}

	public void start() {
		File tmpError = new File(Config.getInstance().getTemporaryFolder(), UUID.randomUUID().toString());
		File tmpOutput = new File(Config.getInstance().getTemporaryFolder(), UUID.randomUUID().toString());
		InputStream in = null;
		try {
			List<String> args = new ArrayList<>();
			args.add(this.ydlLocation);
			args.add("--no-warnings");
			args.add("-q");
			args.add("-i");
			args.add("-J");
			if (!(StringUtils.isNullOrEmptyOrBlank(this.user) || StringUtils.isNullOrEmptyOrBlank(this.pass))) {
				args.add("-u");
				args.add(this.user);
				args.add("-p");
				args.add(this.pass);
			}

			WebProxy webproxy = ProxyResolver.resolve(url);
			if (webproxy != null) {
				StringBuilder sb = new StringBuilder();
				String user = Config.getInstance().getProxyUser();
				String pass = Config.getInstance().getProxyPass();
				if (!(StringUtils.isNullOrEmptyOrBlank(user) || StringUtils.isNullOrEmptyOrBlank(pass))) {
					sb.append(user).append(":").append(pass);
				}
				String proxy = "http://" + webproxy.getHost();
				int port = webproxy.getPort();
				if (port > 0 && port != 80) {
					sb.append(":").append(port);
				}
				if (sb.length() > 0) {
					sb.append("@");
				}
				sb.append(proxy);
				args.add("--proxy");
				args.add(sb.toString());
			}

			args.add(url);

			ProcessBuilder pb = new ProcessBuilder(args);
			for (String arg : args) {
				Logger.info(arg);
			}

			Logger.info("Writing JSON to: " + tmpOutput);

			pb.redirectError(tmpError);
			pb.redirectOutput(tmpOutput);
			proc = pb.start();
			this.exitCode = this.proc.waitFor();
			if (!this.stop) {
				in = new FileInputStream(tmpOutput);
				this.videos.addAll(YdlResponse.parse(in));
				Logger.info("video found: " + this.videos.size());
			}
		} catch (Exception e) {
			Logger.error(e);
		} finally {
			IOUtils.closeFlow(in);
			tmpError.delete();
			tmpOutput.delete();
		}
	}

	public int getExitCode() {
		return this.exitCode;
	}

	public void setExitCode(int exitCode) {
		this.exitCode = exitCode;
	}

	public ArrayList<YdlResponse.YdlVideo> getVideos() {
		return this.videos;
	}

	public void stop() {
		try {
			this.proc.destroy();
		} catch (Exception e) {
			Logger.error(e);
		}
	}
}
