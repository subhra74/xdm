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

package xdman.util;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Objects;

import org.tinylog.Logger;

import xdman.win32.NativeMethods;

public class WinUtils {

	public static void open(File f) throws FileNotFoundException {
		if (!f.exists()) {
			throw new FileNotFoundException();
		}
		try {
			ProcessBuilder builder = new ProcessBuilder();
			ArrayList<String> lst = new ArrayList<>();
			lst.add("rundll32");
			lst.add("url.dll,FileProtocolHandler");
			lst.add(f.getAbsolutePath());
			builder.command(lst);
			builder.start();
		} catch (IOException e) {
			Logger.error(e);
		}
	}

	public static void openFolder(String folder, String file) {
		if (file == null) {
			openFolder2(folder);
			return;
		}
		try {
			File f = new File(folder, file);
			if (!f.exists()) {
				throw new FileNotFoundException();
			}
			ProcessBuilder builder = new ProcessBuilder();
			ArrayList<String> lst = new ArrayList<>();
			lst.add("explorer");
			lst.add("/select,");
			lst.add(f.getAbsolutePath());
			builder.command(lst);
			builder.start();
		} catch (IOException e) {
			Logger.error(e);
		}
	}

	private static void openFolder2(String folder) {
		try {
			ProcessBuilder builder = new ProcessBuilder();
			ArrayList<String> lst = new ArrayList<>();
			lst.add("explorer");
			lst.add(folder);
			builder.command(lst);
			builder.start();
		} catch (Exception e) {
			Logger.error(e);
		}
	}

	public static void keepAwakePing() {
		NativeMethods.getInstance().keepAwakePing();
	}

	public static void addToStartup() {
		String launchCmd = "\"" + System.getProperty("java.home") + "\\bin\\javaw.exe\" -Xmx1024m -jar \""
				+ Objects.requireNonNull(XDMUtils.getJarFile()).getAbsolutePath() + "\" -m";
		Logger.info("Launch CMD: " + launchCmd);
		NativeMethods.getInstance().addToStartup("XDM", launchCmd);
	}

	public static boolean isAlreadyAutoStart() {
		String launchCmd = "\"" + System.getProperty("java.home") + "\\bin\\javaw.exe\" -Xmx1024m -jar \""
				+ Objects.requireNonNull(XDMUtils.getJarFile()).getAbsolutePath() + "\" -m";
		Logger.info("Launch CMD: " + launchCmd);
		return NativeMethods.getInstance().presentInStartup("XDM", launchCmd);
	}

	public static void removeFromStartup() {
		NativeMethods.getInstance().removeFromStartup("XDM");
	}

	public static void browseURL(String url) {
		try {
			ProcessBuilder builder = new ProcessBuilder();
			ArrayList<String> lst = new ArrayList<>();
			lst.add("rundll32");
			lst.add("url.dll,FileProtocolHandler");
			lst.add(url);
			builder.command(lst);
			builder.start();
		} catch (IOException e) {
			Logger.error(e);
		}
	}

	public static void initShutdown() {
		try {
			ProcessBuilder builder = new ProcessBuilder();
			ArrayList<String> lst = new ArrayList<>();
			lst.add("shutdown");
			lst.add("-t");
			lst.add("30");
			lst.add("-s");
			builder.command(lst);
			builder.start();
		} catch (Exception e) {
			Logger.error(e);
		}
	}

}
