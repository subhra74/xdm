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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Objects;

import org.tinylog.Logger;

@SuppressWarnings("ResultOfMethodCallIgnored")
public class LinuxUtils {
	static String[] shutdownCmds = {
			"dbus-send --system --print-reply --dest=org.freedesktop.login1 /org/freedesktop/login1 \"org.freedesktop.login1.Manager.PowerOff\" boolean:true",
			"dbus-send --system --print-reply --dest=\"org.freedesktop.ConsoleKit\" /org/freedesktop/ConsoleKit/Manager org.freedesktop.ConsoleKit.Manager.Stop",
			"systemctl poweroff" };

	public static void initShutdown() {
		for (int i = 0; i < shutdownCmds.length; i++) {
			String cmd = shutdownCmds[0];
			try {
				Process proc = Runtime.getRuntime().exec(cmd);
				int ret = proc.waitFor();
				if (ret == 0)
					break;
			} catch (Exception e) {
				Logger.error(e);
			}
		}
	}

	public static void open(final File f) throws FileNotFoundException {
		if (!f.exists()) {
			throw new FileNotFoundException();
		}
		try {
			ProcessBuilder pb = new ProcessBuilder();
			pb.command("xdg-open", f.getAbsolutePath());
			pb.start();
		} catch (Exception e) {
			Logger.error(e);
		}
	}

	public static void keepAwakePing() {
		try {
			Runtime.getRuntime().exec(
					"dbus-send --print-reply --type=method_call --dest=org.freedesktop.ScreenSaver /ScreenSaver org.freedesktop.ScreenSaver.SimulateUserActivity");
		} catch (Exception e) {
			Logger.error(e);
		}
	}

	public static void addToStartup() {
		File dir = new File(System.getProperty("user.home"), ".config/autostart");
		dir.mkdirs();
		File f = new File(dir, "xdman.desktop");
		FileOutputStream fs = null;
		try {
			fs = new FileOutputStream(f);
			fs.write(getDesktopFileString().getBytes());
		} catch (Exception e) {
			Logger.error(e);
		} finally {
			IOUtils.closeFlow(fs);
		}
		f.setExecutable(true);
	}

	public static boolean isAlreadyAutoStart() {
		File f = new File(System.getProperty("user.home"), ".config/autostart/xdman.desktop");
		if (!f.exists())
			return false;
		FileInputStream in = null;
		byte[] buf = new byte[(int) f.length()];
		try {
			in = new FileInputStream(f);
			if (in.read(buf) != f.length()) {
				return false;
			}
		} catch (Exception e) {
			Logger.error(e);
		} finally {
			IOUtils.closeFlow(in);
		}
		String str = new String(buf);
		String s1 = getProperPath(System.getProperty("java.home"));
		String s2 = Objects.requireNonNull(XDMUtils.getJarFile()).getAbsolutePath();
		return str.contains(s1) && str.contains(s2);
	}

	public static void removeFromStartup() {
		File f = new File(System.getProperty("user.home"), ".config/autostart/xdman.desktop");
		f.delete();
	}

	private static String getDesktopFileString() {
		String str = "[Desktop Entry]\r\n" + "Encoding=UTF-8\r\n" + "Version=1.0\r\n" + "Type=Application\r\n"
				+ "Terminal=false\r\n" + "Exec=\"%sbin/java\" -Xmx1024m -jar \"%s\" -m\r\n"
				+ "Name=Xtreme Download Manager\r\n" + "Comment=Xtreme Download Manager\r\n" + "Categories=Network;\r\n"
				+ "Icon=/opt/xdman/icon.png";
		String s1 = getProperPath(System.getProperty("java.home"));
		String s2 = Objects.requireNonNull(XDMUtils.getJarFile()).getAbsolutePath();
		return String.format(str, s1, s2);
	}

	private static String getProperPath(String path) {
		if (path.endsWith("/"))
			return path;
		return path + "/";
	}

	public static void browseURL(final String url) {
		try {
			ProcessBuilder pb = new ProcessBuilder();
			pb.command("xdg-open", url);
			pb.start();
		} catch (Exception e) {
			Logger.error(e);
		}
	}

	public static String getXDGDownloadDir() {
		BufferedReader br = null;
		try {
			String userHome = System.getProperty("user.home");
			br = Files.newBufferedReader(Paths.get(userHome + "/.config/user-dirs.dirs"));
			while (true) {
				String line = br.readLine();
				if (line == null) {
					break;
				}
				if (line.startsWith("XDG_DOWNLOAD_DIR")) {
					String[] lines = line.split("=");
					if (lines.length > 0)  {
						String path = lines[1].replace("\"", "").replace("$HOME", userHome);
						File downloadDir = new File(path);
						if (downloadDir.exists()) {
							return downloadDir.getAbsolutePath();
						}
					}
				}
			}
		} catch (Exception e) {
			Logger.error(e);
		} finally {
			IOUtils.closeFlow(br);
		}
		return null;
	}

}
