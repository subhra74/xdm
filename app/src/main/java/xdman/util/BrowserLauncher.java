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

import org.tinylog.Logger;

public class BrowserLauncher {

	public static boolean launchFirefox(String args) {
		int os = XDMUtils.detectOS();
		if (os == XDMUtils.WINDOWS) {
			File[] ffPaths = { new File(System.getenv("ProgramW6432"), "Mozilla Firefox\\firefox.exe"),
					new File(System.getenv("PROGRAMFILES"), "Mozilla Firefox\\firefox.exe"),
					new File(System.getenv("PROGRAMFILES(X86)"), "Mozilla Firefox\\firefox.exe") };
			for (File ffPath : ffPaths) {
				Logger.info(ffPath);
				if (ffPath.exists()) {
					return XDMUtils.exec("\"" + ffPath + "\" " + args);
				}
			}
		}
		if (os == XDMUtils.MAC) {
			File[] ffPaths = { new File("/Applications/Firefox.app") };
			for (File ffPath : ffPaths) {
				if (ffPath.exists()) {
					return MacUtils.launchApp(ffPath.getAbsolutePath(), args);
				}
			}
		}
		if (os == XDMUtils.LINUX) {
			File[] ffPaths = { new File("/usr/bin/firefox") };
			for (File ffPath : ffPaths) {
				if (ffPath.exists()) {
					return XDMUtils.exec(ffPath + " " + args);
				}
			}
		}
		return false;
	}

	public static boolean launchChrome(String args) {
		int os = XDMUtils.detectOS();
		if (os == XDMUtils.WINDOWS) {
			File[] ffPaths = { new File(System.getenv("PROGRAMFILES"), "Google\\Chrome\\Application\\chrome.exe"),
					new File(System.getenv("PROGRAMFILES(X86)"), "Google\\Chrome\\Application\\chrome.exe"),
					new File(System.getenv("LOCALAPPDATA"), "Google\\Chrome\\Application\\chrome.exe") };
			for (File ffPath : ffPaths) {
				if (ffPath.exists()) {
					return XDMUtils.exec("\"" + ffPath + "\" " + args);
				}
			}
		}
		if (os == XDMUtils.MAC) {
			File[] ffPaths = { new File("/Applications/Google Chrome.app") };
			for (File ffPath : ffPaths) {
				if (ffPath.exists()) {
					return MacUtils.launchApp(ffPath.getAbsolutePath(), args);
				}
			}
		}
		if (os == XDMUtils.LINUX) {
			File[] ffPaths = { new File("/usr/bin/google-chrome") };
			for (File ffPath : ffPaths) {
				if (ffPath.exists()) {
					return XDMUtils.exec(ffPath + " " + args);
				}
			}
		}
		return false;
	}

}
