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
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.tinylog.Logger;

import xdman.ui.res.StringResource;

public class FormatLoader {

	public static List<FormatGroup> load() {
		List<FormatGroup> list = new ArrayList<>();
		try {
			InputStream inStream = StringResource.class.getResourceAsStream("/formats/format_db.txt");
			if (inStream == null) {
				inStream = new FileInputStream("formats/format_db.txt");
			}
			InputStreamReader r = new InputStreamReader(inStream, StandardCharsets.UTF_8);

			BufferedReader br = new BufferedReader(r);

			while (true) {
				String ln = br.readLine();
				if (ln == null || ln.length() < 1) {
					break;
				}
				FormatGroup fg = new FormatGroup();
				String[] arr = ln.split("\\|");
				fg.name = arr[0].trim();
				fg.desc = arr[1].trim();
				Logger.info("group: " + fg.name);
				list.add(fg);
			}
			while (true) {
				Format format = Format.read(br);
				if (format == null) {
					break;
				}
				print(format);
				for (FormatGroup fg : list) {
					if (fg.name.equals(format.group)) {
						Logger.info(fg.desc + " " + format.desc);
						fg.formats.add(format);
					}
				}
			}
		} catch (Exception e) {
			Logger.error(e);
		}
		return list;
	}

	static void print(Format format) {
		Logger.info("\t" + format.getDesc() + " '" + format.group + "'");
		List<String> list = format.getVideoCodecs();
		if (list.size() > 0) {
			Logger.info("\t\tVideo Codec:");
			for (String s : list) {
				if (s.length() > 1) {
					if (s.equals(format.getDefautVideoCodec())) {
						Logger.info("*");
					}

					Logger.info(s + " ");
				}
			}
			Logger.info("\n");
		}

		list = format.getResolutions();
		if (list.size() > 0) {
			Logger.info("\t\tResolution:");
			for (String s : list) {
				if (s.length() > 1) {
					if (s.equals(format.getDefaultResolution())) {
						Logger.info("*");
					}

					Logger.info(s + " ");
				}
			}
			Logger.info("\n");
		}

		list = format.getAudioChannel();
		if (list.size() > 0) {
			Logger.info("\t\tChannel:");
			for (String s : list) {
				if (s.equals(format.getDefaultAudioChannel())) {
					Logger.info("*");
				}

				Logger.info(s + " ");
			}
			Logger.info("\n");
		}

	}

}
