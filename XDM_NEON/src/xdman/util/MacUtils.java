package xdman.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.util.ArrayList;

public class MacUtils {
	public static void open(final File f) throws FileNotFoundException {
		if (!f.exists()) {
			throw new FileNotFoundException();
		}
		try {
			ProcessBuilder pb = new ProcessBuilder();
			pb.command("open", f.getAbsolutePath());
			if (pb.start().waitFor() != 0) {
				throw new FileNotFoundException();
			}
		} catch (Exception e) {
			Logger.log(e);
		}
	}

	public static void openFolder(String folder, String file) throws FileNotFoundException {
		if (file == null) {
			openFolder2(folder);
			return;
		}
		File f = new File(folder, file);
		if (!f.exists()) {
			throw new FileNotFoundException();
		}
		try {
			ProcessBuilder pb = new ProcessBuilder();
			Logger.log("Opening folder: " + f.getAbsolutePath());
			pb.command("open", "-R", f.getAbsolutePath());
			if (pb.start().waitFor() != 0) {
				throw new FileNotFoundException();
			}
		} catch (Exception e) {
			Logger.log(e);
		}
	}

	private static void openFolder2(String folder) {
		try {
			ProcessBuilder builder = new ProcessBuilder();
			ArrayList<String> lst = new ArrayList<String>();
			lst.add("open");
			lst.add(folder);
			builder.command(lst);
			builder.start();
		} catch (Exception e) {
			Logger.log(e);
		}
	}

	public static boolean launchApp(String app, String args) {
		try {
			ProcessBuilder pb = new ProcessBuilder();
			pb.command("open", "-n", "-a", app, "--args", args);
			if (pb.start().waitFor() != 0) {
				throw new FileNotFoundException();
			}
			// Runtime.getRuntime().exec(new String[] { "open \"" + app + "\" " + args });
			return true;
		} catch (Exception e) {
			Logger.log(e);
			return false;
		}
	}

	public static void keepAwakePing() {
		try {
			Runtime.getRuntime().exec("caffeinate -i -t 3");
		} catch (Exception e) {
			Logger.log(e);
		}
	}

	public static void addToStartup() {
		File dir = new File(System.getProperty("user.home"), "Library/LaunchAgents");
		dir.mkdirs();
		File f = new File(dir, "org.sdg.xdman.plist");
		FileOutputStream fs = null;
		try {
			fs = new FileOutputStream(f);
			fs.write(getStartupPlist().getBytes());
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
		File f = new File(System.getProperty("user.home"), "Library/LaunchAgents/org.sdg.xdman.plist");
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
		String str = new String(buf);
		String s1 = getProperPath(System.getProperty("java.home"));
		String s2 = XDMUtils.getJarFile().getAbsolutePath();
		return str.contains(s1) && str.contains(s2);
	}

	public static void removeFromStartup() {
		File f = new File(System.getProperty("user.home"), "Library/LaunchAgents/org.sdg.xdman.plist");
		f.delete();
	}

	public static String getStartupPlist() {
		String str = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\r\n"
				+ "<!DOCTYPE plist PUBLIC \"-//Apple Computer//DTD PLIST 1.0//EN\"\r\n"
				+ "\"http://www.apple.com/DTDs/PropertyList-1.0.dtd\" >\r\n" + "<plist version=\"1.0\">\r\n"
				+ "	<dict>\r\n" + "		<key>Label</key>\r\n" + "		<string>org.sdg.xdman</string>\r\n"
				+ "		<key>ProgramArguments</key>\r\n" + "		<array>\r\n"
				+ "			<string>%sbin/java</string>\r\n" + "			<string>-Xmx1024m</string>\r\n"
				+ "			<string>-Xdock:name=XDM</string>\r\n" + "			<string>-jar</string>\r\n"
				+ "			<!-- MODIFY THIS TO POINT TO YOUR EXECUTABLE JAR FILE -->\r\n"
				+ "			<string>%s</string>\r\n" + "			<string>-m</string>\r\n" + "		</array>\r\n"
				+ "		<key>OnDemand</key>\r\n" + "		<true />\r\n" + "		<key>RunAtLoad</key>\r\n"
				+ "		<true />\r\n" + "		<key>KeepAlive</key>\r\n" + "		<false />\r\n" + "	</dict>\r\n"
				+ "</plist>";
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
			pb.command("open", url);
			pb.start();// .waitFor();
		} catch (Exception e) {
			Logger.log(e);
		}
	}

	public static void initShutdown() {
		try {
			ProcessBuilder builder = new ProcessBuilder();
			ArrayList<String> lst = new ArrayList<String>();
			lst.add("osascript");
			lst.add("-e");
			lst.add("tell app \"System Events\" to shut down");
			builder.command(lst);
			builder.start();
		} catch (Exception e) {
			Logger.log(e);
		}
	}
}
