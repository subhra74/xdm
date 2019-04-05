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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import xdman.Config;
import xdman.Main;
import xdman.XDMConstants;
import xdman.downloaders.metadata.HttpMetadata;

public class XDMUtils {
	private static int dpiScale;
	// private static Map<Integer, String> categoryFolderMap;
	//
	// static {
	// categoryFolderMap = new HashMap<>();
	// categoryFolderMap.put(XDMConstants.DOCUMENTS, "Documents");
	// categoryFolderMap.put(XDMConstants.MUSIC, "Music");
	// categoryFolderMap.put(XDMConstants.VIDEO, "Videos");
	// categoryFolderMap.put(XDMConstants.PROGRAMS, "Programs");
	// categoryFolderMap.put(XDMConstants.COMPRESSED, "Compressed");
	// }
	//
	// public static String getFolderForCategory(int category) {
	// return categoryFolderMap.get(category);
	// }

	private static final char[] invalid_chars = { '/', '\\', '"', '?', '*', '<', '>', ':', '|' };

	public static String decodeFileName(String str) {
		char ch[] = str.toCharArray();
		StringBuffer buf = new StringBuffer();
		for (int i = 0; i < ch.length; i++) {
			if (ch[i] == '/' || ch[i] == '\\' || ch[i] == '"' || ch[i] == '?' || ch[i] == '*' || ch[i] == '<'
					|| ch[i] == '>' || ch[i] == ':')
				continue;
			if (ch[i] == '%') {
				if (i + 2 < ch.length) {
					int c = Integer.parseInt(ch[i + 1] + "" + ch[i + 2], 16);
					buf.append((char) c);
					i += 2;
					continue;
				}
			}
			buf.append(ch[i]);
		}
		return buf.toString();
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
			Logger.log(e);
			return "FILE";
		}
	}

	public static String createSafeFileName(String str) {
		String safe_name = str;
		for (int i = 0; i < invalid_chars.length; i++) {
			if (safe_name.indexOf(invalid_chars[i]) != -1) {
				safe_name = safe_name.replace(invalid_chars[i], '_');
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
			return false;
		}
	}

	static String doc[] = { ".doc", ".docx", ".txt", ".pdf", ".rtf", ".xml", ".c", ".cpp", ".java", ".cs", ".vb",
			".html", ".htm", ".chm", ".xls", ".xlsx", ".ppt", ".pptx", ".js", ".css" };
	static String cmp[] = { ".7z", ".zip", ".rar", ".gz", ".tgz", ".tbz2", ".bz2", ".lzh", ".sit", ".z" };
	static String music[] = { ".mp3", ".wma", ".ogg", ".aiff", ".au", ".mid", ".midi", ".mp2", ".mpa", ".wav", ".aac",
			".oga", ".ogx", ".ogm", ".spx", ".opus" };
	static String vid[] = { ".mpg", ".mpeg", ".avi", ".flv", ".asf", ".mov", ".mpe", ".wmv", ".mkv", ".mp4", ".3gp",
			".divx", ".vob", ".webm", ".ts" };
	static String prog[] = { ".exe", ".msi", ".bin", ".sh", ".deb", ".cab", ".cpio", ".dll", ".jar", "rpm", ".run",
			".py" };

	public static int findCategory(String filename) {
		String file = filename.toLowerCase();
		for (int i = 0; i < doc.length; i++) {
			if (file.endsWith(doc[i])) {
				return XDMConstants.DOCUMENTS;
			}
		}
		for (int i = 0; i < cmp.length; i++) {
			if (file.endsWith(cmp[i])) {
				return XDMConstants.COMPRESSED;
			}
		}
		for (int i = 0; i < music.length; i++) {
			if (file.endsWith(music[i])) {
				return XDMConstants.MUSIC;
			}
		}
		for (int i = 0; i < prog.length; i++) {
			if (file.endsWith(prog[i])) {
				return XDMConstants.PROGRAMS;
			}
		}
		for (int i = 0; i < vid.length; i++) {
			if (file.endsWith(vid[i])) {
				return XDMConstants.VIDEO;
			}
		}
		return XDMConstants.OTHER;
	}

	public static String appendArray2Str(String[] arr) {
		boolean first = true;
		StringBuffer buf = new StringBuffer();
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
		ArrayList<String> arrList = new ArrayList<String>();
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
			String ext = file.substring(index);
			return ext;
		} else {
			return null;
		}
	}

	public static String getFileNameWithoutExtension(String fileName) {
		int index = fileName.lastIndexOf(".");
		if (index > 0) {
			fileName = fileName.substring(0, index);
			return fileName;
		} else {
			return fileName;
		}
	}

	public static void copyStream(InputStream instream, OutputStream outstream, long size) throws Exception {
		byte[] b = new byte[8192];
		long rem = size;
		while (true) {
			int bs = (int) (size > 0 ? (rem > b.length ? b.length : rem) : b.length);
			int x = instream.read(b, 0, bs);
			if (x == -1) {
				if (size > 0) {
					throw new EOFException("Unexpected EOF");
				} else {
					break;
				}
			}
			outstream.write(b, 0, x);
			rem -= x;
			if (size > 0) {
				if (rem <= 0)
					break;
			}
		}
	}

	public static final int WINDOWS = 10, MAC = 20, LINUX = 30;

	public static final int detectOS() {
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

	public static final int getOsArch() {
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
			Logger.log(e);
		}
	}

	public static boolean exec(String args) {
		try {
			Logger.log("Launching: " + args);
			Runtime.getRuntime().exec(args);
		} catch (IOException e) {
			Logger.log(e);
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
			// Logger.log(e);
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
			Logger.log(e);
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
			Logger.log(e);
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
			Logger.log(e);
		}
	}

	public static File getJarFile() {
		try {
			return new File(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI().getPath());
		} catch (URISyntaxException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
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
			ffFile = new File(XDMUtils.getJarFile().getParentFile(),
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
			Logger.log(e);
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

		}
		return false;
	}

	public static String getDownloadsFolder() {
		if (detectOS() == XDMUtils.LINUX) {
			String path = LinuxUtils.getXDGDownloaDir();
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
		return new File(XDMUtils.getJarFile().getParentFile(),
				"ffmpeg" + (XDMUtils.detectOS() == XDMUtils.WINDOWS ? ".exe" : "")).exists();

	}

	// public static boolean isYdlInstalled() {
	// return (new File(Config.getInstance().getDataFolder(),
	// "youtube-dl" + (XDMUtils.detectOS() == XDMUtils.WINDOWS ? ".exe" :
	// "")).exists());
	// }

	public static boolean isMacPopupTrigger(MouseEvent e) {
		if (XDMUtils.detectOS() == XDMUtils.MAC) {
			return (e.getModifiersEx() & InputEvent.BUTTON1_DOWN_MASK) != 0
					&& (e.getModifiersEx() & InputEvent.CTRL_DOWN_MASK) != 0;
			// return (e.getModifiers() & InputEvent.BUTTON1_MASK) != 0 && (e.getModifiers()
			// & InputEvent.CTRL_MASK) != 0;
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

	public static int detectScreenType() {
		if (screenType < 0) {
			int dpi = Toolkit.getDefaultToolkit().getScreenResolution();
			float dpiScale = dpi / 96.0f;
			Logger.log("Dpi scale: " + dpiScale);
			// Dimension d = Toolkit.getDefaultToolkit().getScreenSize();
			// double height = d.getHeight();
			if (dpiScale >= 2) {
				screenType = XDMConstants.XHDPI;
			} else if (dpiScale >= 1.5) {
				screenType = XDMConstants.HDPI;
			} else {
				screenType = XDMConstants.NORMAL;
			}
		}
		return screenType;
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

	private static int screenType = -1;
	
	public static final int getScaledInt(int value) {
		if (dpiScale == 0.0f) {
			int dpi = Toolkit.getDefaultToolkit().getScreenResolution();
			dpiScale = dpi / 96;
		}
		return  (value * dpiScale);
	}

	/*
	 * public static final int getScaledInt(int size) { detectScreenType(); return
	 * (int) (size * getScaleFactor()); }
	 */

	public static final String readLineSafe(BufferedReader r) throws IOException {
		String ln = r.readLine();
		if (ln == null) {
			throw new IOException("Unexpected EOF");
		}
		return ln;
	}

}