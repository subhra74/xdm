package xdman.util;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.InputStreamReader;

public class LinuxUtils {
	static String shutdownCmds[] = {
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
				Logger.log(e);
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
			pb.start();// .waitFor();
		} catch (Exception e) {
			Logger.log(e);
		}
	}

	public static void keepAwakePing() {
		try {
			Runtime.getRuntime().exec(
					"dbus-send --print-reply --type=method_call --dest=org.freedesktop.ScreenSaver /ScreenSaver org.freedesktop.ScreenSaver.SimulateUserActivity");
		} catch (Exception e) {
			Logger.log(e);
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
			Logger.log(e);
		} finally {
			try {
				if (fs != null)
					fs.close();
			} catch (Exception e2) {
			}
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
			Logger.log(e);
		} finally {
			try {
				if (in != null)
					in.close();
			} catch (Exception e2) {
			}
		}
		String str=new String(buf);
		String s1 = getProperPath(System.getProperty("java.home"));
		String s2 = XDMUtils.getJarFile().getAbsolutePath();
		return str.contains(s1)&&str.contains(s2);
	}

	public static void removeFromStartup() {
		File f = new File(System.getProperty("user.home"), ".config/autostart/xdman.desktop");
		f.delete();
	}

	private static String getDesktopFileString() {
		String str = "[Desktop Entry]\r\n" + "Encoding=UTF-8\r\n" + "Version=1.0\r\n" + "Type=Application\r\n"
				+ "Terminal=false\r\n" + "Exec=\"%sbin/java\" -Xmx1024m -jar \"%s\" -m\r\n" + "Name=Xtreme Download Manager\r\n"
				+ "Comment=Xtreme Download Manager\r\n" + "Categories=Network;\r\n" + "Icon=/opt/xdman/icon.png";
		String s1 = getProperPath(System.getProperty("java.home"));
		String s2 = XDMUtils.getJarFile().getAbsolutePath();
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
			pb.start();// .waitFor();
		} catch (Exception e) {
			Logger.log(e);
		}
	}
	
	public static String getXDGDownloaDir() {
		BufferedReader br = null;
		try {
			br = new BufferedReader(new InputStreamReader(new FileInputStream(new File(System.getProperty("user.home"),".config/user-dirs.dirs"))));
			while (true) {
				String line = br.readLine();
				if (line == null) {
					break;
				}
				if (line.startsWith("XDG_DOWNLOAD_DIR")) {
					int index = line.indexOf("=");
					if (index != -1) {
						String path = line.substring(index + 1).trim();
						path = path.replace("$HOME", System.getProperty("user.home"));
						File f = new File(path);
						if (f.exists()) {
							return f.getAbsolutePath();
						}
					}
				}
			}
		} catch (Exception e) {
			Logger.log(e);
		} finally {
			if (br != null) {
				try {
					br.close();
				} catch (Exception e2) {
				}
			}
		}
		return null;
	}

}
