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

import java.awt.Desktop;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.io.BufferedReader;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import org.tinylog.Logger;

import xdman.Config;
import xdman.Main;
import xdman.XDMConstants;
import xdman.downloaders.metadata.HttpMetadata;

@SuppressWarnings({ "unused", "ResultOfMethodCallIgnored", "BooleanMethodIsAlwaysInverted" })
public class XDMUtils {

	public static final int WINDOWS = 10, MAC = 20, LINUX = 30;
	private static final char[] invalid_chars = { '/', '\\', '"', '?', '*', '<', '>', ':', '|' };
	static String[] doc = { ".doc", ".docx", ".txt", ".pdf", ".rtf", ".xml", ".c", ".cpp", ".cc", ".cxx", ".java",
			".cs", ".vb", ".html", ".htm", ".chm", ".xls", ".xlsx", ".ppt", ".pptx", ".js", ".css", ".h", ".hpp",
			".hxx" };
	static String[] cmp = { ".7z", ".zip", ".rar", ".gz", ".tgz", ".tbz2", ".bz2", ".lzh", ".sit", ".z", ".xz", ".iso",
			".tar", ".lz", ".lzma" };
	static String[] music = { ".mp3", ".wma", ".ogg", ".aiff", ".au", ".mid", ".midi", ".mp2", ".mpa", ".wav", ".aac",
			".oga", ".ogx", ".ogm", ".spx", ".opus" };
	static String[] vid = { ".mpg", ".mpeg", ".avi", ".flv", ".asf", ".mov", ".mpe", ".wmv", ".mkv", ".mp4", ".3gp",
			".divx", ".vob", ".webm", ".ts" };
	static String[] prog = { ".exe", ".msi", ".bin", ".sh", ".deb", ".cab", ".cpio", ".dll", ".jar", "rpm", ".run",
			".py", ".AppImage", ".out" };
	private static int screenType = -1;

	public static String decodeFileName(String encoded) {
		String str;
		try {
			str = URLDecoder.decode(encoded.replace("+", "%2B"), StandardCharsets.UTF_8);
		} catch (Exception e) {
			StringBuilder builder = new StringBuilder();
			char[] ch = encoded.toCharArray();
			for (int i = 0; i < ch.length; i++) {
				if (ch[i] == '%') {
					if (i + 2 < ch.length) {
						int c = Integer.parseInt(ch[i + 1] + "" + ch[i + 2], 16);
						builder.append((char) c);
						i += 2;
						continue;
					}
				}
				builder.append(ch[i]);
			}
			str = builder.toString();
			Logger.error(e);
		}
		StringBuilder builder = new StringBuilder();
		for (char c : str.toCharArray()) {
			if (c == '/' || c == '\\' || c == '"' || c == '?' || c == '*' || c == '<' || c == '>' || c == ':')
				continue;
			builder.append(c);
		}
		return builder.toString();
	}

	public static String getFileName(String uri) {
		try {
			if (uri == null)
				return "FILE";
			if (uri.equals("/") || uri.length() < 1) {
				return "FILE";
			}
			int x = uri.lastIndexOf("/");
			String path = uri;
			if (x > -1) {
				path = uri.substring(x);
			}
			int qindex = path.indexOf("?");
			if (qindex > -1) {
				path = path.substring(0, qindex);
			}
			path = decodeFileName(path);
			if (path.length() < 1)
				return "FILE";
			if (path.equals("/"))
				return "FILE";
			return createSafeFileName(path);
		} catch (Exception e) {
			Logger.error(e);
			return "FILE";
		}
	}

	public static String createSafeFileName(String str) {
		String safe_name = str;
		for (char invalid_char : invalid_chars) {
			if (safe_name.indexOf(invalid_char) != -1) {
				safe_name = safe_name.replace(invalid_char, '_');
			}
		}
		return safe_name;
	}

	public static boolean validateURL(String url) {
		try {
			url = url.toLowerCase();
			if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("ftp://")) {
				new URL(url);
				return true;
			}
			return false;
		} catch (Exception e) {
			Logger.error(e);
			return false;
		}
	}

	public static int findCategory(String filename) {
		String file = filename.toLowerCase();
		for (String s : doc) {
			if (file.endsWith(s)) {
				return XDMConstants.DOCUMENTS;
			}
		}
		for (String s : cmp) {
			if (file.endsWith(s)) {
				return XDMConstants.COMPRESSED;
			}
		}
		for (String s : music) {
			if (file.endsWith(s)) {
				return XDMConstants.MUSIC;
			}
		}
		for (String s : prog) {
			if (file.endsWith(s)) {
				return XDMConstants.PROGRAMS;
			}
		}
		for (String s : vid) {
			if (file.endsWith(s)) {
				return XDMConstants.VIDEO;
			}
		}
		return XDMConstants.OTHER;
	}

	public static String appendArray2Str(String[] arr) {
		boolean first = true;
		StringBuilder buf = new StringBuilder();
		for (String s : arr) {
			if (!first) {
				buf.append(",");
			}
			buf.append(s);
			first = false;
		}
		return buf.toString();
	}

	public static String[] appendStr2Array(String str) {
		String[] arr = str.split(",");
		ArrayList<String> arrList = new ArrayList<>();
		for (String s : arr) {
			String txt = s.trim();
			if (txt.length() > 0) {
				arrList.add(txt);
			}
		}
		arr = new String[arrList.size()];
		return arrList.toArray(arr);
	}

	public static String getExtension(String file) {
		int index = file.lastIndexOf(".");
		if (index > 0) {
			return file.substring(index);
		} else {
			return null;
		}
	}

	public static String getFileNameWithoutExtension(String fileName) {
		int index = fileName.lastIndexOf(".");
		if (index > 0) {
			fileName = fileName.substring(0, index);
		}
		return fileName;
	}

	public static void copyStream(InputStream inputStream, OutputStream outputStream, long size) throws Exception {
		byte[] b = new byte[8192];
		long rem = size;
		while (true) {
			int bs = (int) (size > 0 ? (rem > b.length ? b.length : rem) : b.length);
			int x = inputStream.read(b, 0, bs);
			if (x == -1) {
				if (size > 0) {
					throw new EOFException("Unexpected EOF");
				} else {
					break;
				}
			}
			outputStream.write(b, 0, x);
			rem -= x;
			if (size > 0) {
				if (rem <= 0)
					break;
			}
		}
	}

	public static int detectOS() {
		String os = System.getProperty("os.name").toLowerCase(Locale.ENGLISH);
		if (os.contains("mac") || os.contains("darwin") || os.contains("os x")) {
			return MAC;
		} else if (os.contains("linux")) {
			return LINUX;
		} else if (os.contains("windows")) {
			return WINDOWS;
		} else {
			return -1;
		}
	}

	public static int getOsArch() {
		if (System.getProperty("os.arch").contains("64")) {
			return 64;
		} else {
			return 32;
		}
	}

	public static void openFile(String file, String folder) throws Exception {
		int os = detectOS();
		File f = new File(folder, file);
		switch (os) {
		case WINDOWS:
			WinUtils.open(f);
			break;
		case LINUX:
			LinuxUtils.open(f);
			break;
		case MAC:
			MacUtils.open(f);
			break;
		default:
			Desktop.getDesktop().open(f);
		}
	}

	public static void openFolder(String file, String folder) throws Exception {
		int os = detectOS();
		switch (os) {
		case WINDOWS:
			WinUtils.openFolder(folder, file);
			break;
		case LINUX:
			File f = new File(folder);
			LinuxUtils.open(f);
			break;
		case MAC:
			MacUtils.openFolder(folder, file);
			break;
		default:
			File ff = new File(folder);
			Desktop.getDesktop().open(ff);
		}
	}

	public static void copyURL(String url) {
		try {
			Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(url), null);
		} catch (Exception e) {
			Logger.error(e);
		}
	}

	public static boolean exec(String args) {
		try {
			Logger.info("Launching: " + args);
			Runtime.getRuntime().exec(args);
		} catch (IOException e) {
			Logger.error(e);
			return false;
		}
		return true;
	}

	public static long getFreeSpace(String folder) {
		if (folder == null)
			return new File(Config.getInstance().getTemporaryFolder()).getFreeSpace();
		else
			return new File(folder).getFreeSpace();
	}

	public static void keepAwakePing() {
		try {
			int os = detectOS();
			if (os == LINUX) {
				LinuxUtils.keepAwakePing();
			} else if (os == WINDOWS) {
				WinUtils.keepAwakePing();
			} else if (os == MAC) {
				MacUtils.keepAwakePing();
			}
		} catch (Throwable e) {
			Logger.error(e);
		}
	}

	public static boolean isAlreadyAutoStart() {
		try {
			int os = detectOS();
			if (os == LINUX) {
				return LinuxUtils.isAlreadyAutoStart();
			} else if (os == WINDOWS) {
				return WinUtils.isAlreadyAutoStart();
			} else if (os == MAC) {
				return MacUtils.isAlreadyAutoStart();
			}
			return false;
		} catch (Throwable e) {
			Logger.error(e);
		}
		return false;
	}

	public static void addToStartup() {
		try {
			int os = detectOS();
			if (os == LINUX) {
				LinuxUtils.addToStartup();
			} else if (os == WINDOWS) {
				WinUtils.addToStartup();
			} else if (os == MAC) {
				MacUtils.addToStartup();
			}
		} catch (Throwable e) {
			Logger.error(e);
		}
	}

	public static void removeFromStartup() {
		try {
			int os = detectOS();
			if (os == LINUX) {
				LinuxUtils.removeFromStartup();
			} else if (os == WINDOWS) {
				WinUtils.removeFromStartup();
			} else if (os == MAC) {
				MacUtils.removeFromStartup();
			}
		} catch (Throwable e) {
			Logger.error(e);
		}
	}

	public static File getJarFile() {
		try {
			return new File(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI().getPath());
		} catch (URISyntaxException e) {
			Logger.error(e);
		}
		return null;
	}

	public static boolean checkComponentsInstalled() {
		File ffFile = new File(Config.getInstance().getDataFolder(),
				XDMUtils.detectOS() == XDMUtils.WINDOWS ? "ffmpeg.exe" : "ffmpeg");
		File ytFile = new File(Config.getInstance().getDataFolder(),
				XDMUtils.detectOS() == XDMUtils.WINDOWS ? "youtube-dl.exe" : "youtube-dl");
		if ((ffFile.exists() && ytFile.exists())) {
			return true;
		} else {
			ffFile = new File(Objects.requireNonNull(XDMUtils.getJarFile()).getParentFile(),
					XDMUtils.detectOS() == XDMUtils.WINDOWS ? "ffmpeg.exe" : "ffmpeg");
			ytFile = new File(XDMUtils.getJarFile().getParentFile(),
					XDMUtils.detectOS() == XDMUtils.WINDOWS ? "youtube-dl.exe" : "youtube-dl");
			return (ffFile.exists() && ytFile.exists());
		}
	}

	public static String getClipBoardText() {
		try {
			return (String) Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor);
		} catch (Exception e) {
			Logger.error(e);
		}
		return "";
	}

	public static void browseURL(String url) {
		int os = detectOS();
		if (os == WINDOWS) {
			WinUtils.browseURL(url);
		} else if (os == LINUX) {
			LinuxUtils.browseURL(url);
		} else if (os == MAC) {
			MacUtils.browseURL(url);
		}
	}

	public static boolean below7() {
		try {
			int version = Integer.parseInt(System.getProperty("os.version").split("\\.")[0]);
			return (version < 6);
		} catch (Exception e) {
			Logger.error(e);
		}
		return false;
	}

	public static String getDownloadsFolder() {
		if (detectOS() == XDMUtils.LINUX) {
			String path = LinuxUtils.getXDGDownloadDir();
			if (path != null) {
				return path;
			}
		}
		return new File(System.getProperty("user.home"), "Downloads").getAbsolutePath();
	}

	public static boolean isFFmpegInstalled() {
		File f1 = new File(Config.getInstance().getDataFolder(),
				"ffmpeg" + (XDMUtils.detectOS() == XDMUtils.WINDOWS ? ".exe" : ""));
		if (f1.exists()) {
			return true;
		}
		return new File(Objects.requireNonNull(XDMUtils.getJarFile()).getParentFile(),
				"ffmpeg" + (XDMUtils.detectOS() == XDMUtils.WINDOWS ? ".exe" : "")).exists();

	}

	public static boolean isMacPopupTrigger(MouseEvent e) {
		if (XDMUtils.detectOS() == XDMUtils.MAC) {
			return (e.getModifiersEx() & InputEvent.BUTTON1_DOWN_MASK) != 0
					&& (e.getModifiersEx() & InputEvent.CTRL_DOWN_MASK) != 0;
		}
		return false;
	}

	public static void mkdirs(String folder) {
		File outFolder = new File(folder);
		if (!outFolder.exists()) {
			outFolder.mkdirs();
		}
	}

	public static void forceScreenType(int type) {
		screenType = type;
	}

	public static float getScaleFactor() {
		if (screenType == XDMConstants.XHDPI) {
			return 2.0f;
		} else if (screenType == XDMConstants.HDPI) {
			return 1.5f;
		} else {
			return 1.0f;
		}
	}

	public static List<HttpMetadata> toMetadata(List<String> urls) {
		List<HttpMetadata> list = new ArrayList<>();
		for (String url : urls) {
			HttpMetadata md = new HttpMetadata();
			md.setUrl(url);
			list.add(md);
		}
		return list;
	}

	public static int getScaledInt(int value) {
		return value;
	}

	public static String readLineSafe(BufferedReader r) throws IOException {
		String ln = r.readLine();
		if (ln == null) {
			throw new IOException("Unexpected EOF");
		}
		return ln;
	}

}
