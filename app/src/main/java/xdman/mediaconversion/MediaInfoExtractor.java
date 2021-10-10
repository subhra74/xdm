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

package xdman.mediaconversion;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.ImageIcon;

import org.tinylog.Logger;

import xdman.Config;
import xdman.util.XDMUtils;

@SuppressWarnings("ResultOfMethodCallIgnored")
public class MediaInfoExtractor {

	Pattern pattern1, pattern2;
	boolean stop;
	Process proc;

	public void stop() {
		stop = true;
		if (proc != null) {
			try {
				proc.destroy();
			} catch (Exception e) {
				Logger.error(e);
			}
		}
	}

	public MediaInfoExtractor() {
		String str1 = "Duration:\\s+(\\d+:\\d+:\\d+)    " + "Stream .*, ([0-9]+x[0-9]+)";
		Logger.info(str1);
		pattern1 = Pattern.compile("Duration:\\s+([0-9]+:[0-9]+:[0-9]+)");
		pattern2 = Pattern.compile("Stream .*, ([0-9]+x[0-9]+)");

	}

	public MediaFormatInfo getInfo(String file) {
		File f = new File(file);
		File tmpOutput = new File(Config.getInstance().getTemporaryFolder(), UUID.randomUUID().toString());
		File tmpImgFile = new File(Config.getInstance().getTemporaryFolder(), UUID.randomUUID() + ".jpg");
		if (!f.exists())
			return null;
		File ffFile = new File(Config.getInstance().getDataFolder(),
				System.getProperty("os.name").toLowerCase().contains("windows") ? "ffmpeg.exe" : "ffmpeg");
		if (!ffFile.exists()) {
			ffFile = new File(Objects.requireNonNull(XDMUtils.getJarFile()).getParentFile(),
					System.getProperty("os.name").toLowerCase().contains("windows") ? "ffmpeg.exe" : "ffmpeg");
			if (!ffFile.exists()) {
				return null;
			}
		}
		if (stop)
			return null;
		try {
			List<String> args = new ArrayList<>();
			args.add(ffFile.getAbsolutePath());
			args.add("-i");
			args.add(f.getAbsolutePath());
			args.add("-vf");
			args.add("scale=64:-1");
			args.add("-vframes");
			args.add("1");
			args.add("-f");
			args.add("image2");
			args.add(tmpImgFile.getAbsolutePath());
			args.add("-y");

			StringBuilder str2 = new StringBuilder();
			for (String s : args) {
				str2.append(" ").append(s);
			}

			Logger.info(str2.toString());

			ProcessBuilder pb = new ProcessBuilder(args);
			pb.redirectError(tmpOutput);
			proc = pb.start();

			int ret = proc.waitFor();
			Logger.info("ret: " + ret);
			if (stop) {
				return null;
			}
			MediaFormatInfo info = new MediaFormatInfo();
			info.thumbnail = new ImageIcon(tmpImgFile.getAbsolutePath());
			byte[] array = Files.readAllBytes(tmpOutput.toPath());
			String str = new String(array, StandardCharsets.UTF_8);
			Logger.info(str);
			Matcher matcher1 = pattern1.matcher(str);
			Matcher matcher2 = pattern2.matcher(str);
			if (matcher1.find()) {
				info.duration = matcher1.group(1);
				Logger.info("Match: " + info.duration);
			} else {
				Logger.info("no match");
			}
			if (matcher2.find()) {
				info.resolution = matcher2.group(1);
				Logger.info("Match: " + info.resolution);
			}
			if (stop) {
				return null;
			}
			return info;
		} catch (Exception e) {
			Logger.error(e);
		} finally {
			tmpOutput.delete();
			tmpImgFile.delete();
		}
		return null;
	}

}
