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

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

import org.tinylog.Logger;

import xdman.util.IOUtils;
import xdman.util.StringUtils;

@SuppressWarnings("unused")
public class MediaFormats {

	private static MediaFormat[] supportedFormats;
	static {
		ArrayList<MediaFormat> list = new ArrayList<>();
		list.add(new MediaFormat());
		BufferedReader br = null;
		try {
			InputStream inStream = MediaFormats.class.getResourceAsStream("/formats/list.txt");
			if (inStream == null) {
				inStream = new FileInputStream("formats/list.txt");
			}
			InputStreamReader r = new InputStreamReader(inStream, StandardCharsets.UTF_8);
			br = new BufferedReader(r, 1024);
			while (true) {
				String ln = br.readLine();
				if (ln == null)
					break;
				if (ln.startsWith("#")) {
					continue;
				}
				String[] arr = ln.split("\\|");
				if (arr.length != 12) {
					continue;
				}
				MediaFormat format = new MediaFormat();
				String fmt = getString(arr[0]);
				String resolution = getString(arr[1]);
				String vcodec = getString(arr[2]);
				String vbr = getString(arr[3]);
				String fr = getString(arr[4]);
				String vextra = getString(arr[5]);
				String acodec = getString(arr[6]);
				String abr = getString(arr[7]);
				String asr = getString(arr[8]);
				String aextra = getString(arr[9]);
				String desc = getString(arr[10]);
				String audioOnly = getString(arr[11]);

				format.setFormat(fmt);
				format.setResolution(resolution);
				format.setVideo_codec(vcodec);
				format.setVideo_bitrate(vbr);
				format.setFramerate(fr);
				format.setVideo_param_extra(vextra);
				format.setAudio_codec(acodec);
				format.setAudio_bitrate(abr);
				format.setSampleRate(asr);
				format.setAudio_extra_param(aextra);
				format.setDescription(desc);
				format.setAudioOnly("1".equals(audioOnly));

				list.add(format);

				supportedFormats = new MediaFormat[list.size()];
				supportedFormats = list.toArray(supportedFormats);

			}
		} catch (RuntimeException | IOException e) {
			Logger.error(e);
		} finally {
			IOUtils.closeFlow(br);
		}
	}

	static String getString(String str) {
		if (!StringUtils.isNullOrEmptyOrBlank(str)) {
			return str;
		}
		return null;
	}

	public MediaFormats() {

	}

	public static MediaFormat[] getSupportedFormats() {
		return supportedFormats;
	}

	public static void setSupportedFormats(MediaFormat[] supportedFmts) {
		supportedFormats = supportedFmts;
	}

}
