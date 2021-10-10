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

package xdman.ui.res;

import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import org.tinylog.Logger;

import xdman.Config;

public class StringResource {
	private static Properties strings;

	public synchronized static String get(String id) {
		if (strings == null) {
			strings = new Properties();
			try {
				String lang = Config.getInstance().getLanguage();
				Logger.info(lang);
				if (!loadLang(lang, strings)) {
					Logger.warn("Unable to load language: " + lang);
					strings.clear();
					loadLang("en", strings);
				}
			} catch (Exception e) {
				Logger.error(e);
			}
		}
		return strings.getProperty(id);
	}

	private static boolean loadLang(String code, Properties prop) {
		Logger.info("Loading language " + code);
		try {
			InputStream inStream = StringResource.class.getResourceAsStream("/lang/en.txt");
			if (inStream == null) {
				inStream = new FileInputStream("lang/" + code + ".txt");
			}
			InputStreamReader r = new InputStreamReader(inStream, StandardCharsets.UTF_8);
			prop.load(r);
		} catch (Exception e) {
			Logger.error(e);
			return false;
		}
		if ("en".equals(code)) {
			return true;
		}
		try {
			InputStream inStream = StringResource.class.getResourceAsStream("/lang/" + code + ".txt");
			if (inStream == null) {
				inStream = new FileInputStream("lang/" + code + ".txt");
			}
			InputStreamReader r = new InputStreamReader(inStream, StandardCharsets.UTF_8);
			prop.load(r);
			return true;
		} catch (Exception e) {
			Logger.error(e);
			return false;
		}
	}

}
