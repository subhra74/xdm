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

package xdman;

import java.time.LocalDate;

import org.tinylog.Logger;
import org.tinylog.configuration.Configuration;

public class Main {
	static {
		System.setProperty("http.KeepAlive.remainingData", "0");
		System.setProperty("http.KeepAlive.queuedConnections", "0");
		System.setProperty("sun.net.http.errorstream.enableBuffering", "false");
		System.setProperty("awt.useSystemAAFontSettings", "lcd");
		System.setProperty("swing.aatext", "true");
		System.setProperty("sun.java2d.d3d", "false");
		System.setProperty("sun.java2d.opengl", "false");
		System.setProperty("sun.java2d.xrender", "false");
	}

	private static void loadLoggerConfiguration() {
		String logFile = System.getProperty("user.home") + "/.xdman/logs/log-" + LocalDate.now() + ".log";
		Configuration.set("writer", "file");
		Configuration.set("writer.level ", "debug");
		Configuration.set("writer.file", logFile);
		Configuration.set("writer.charset", "UTF-8");
		Configuration.set("writer.append", "true");
	}

	public static void main(String[] args) {
		loadLoggerConfiguration();
		Logger.info("loading...");
		Logger.info(System.getProperty("java.version") + " " + System.getProperty("os.version"));
		XDMApp.start(args);
	}

}
