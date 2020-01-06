package xdman.util;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;

import xdman.win32.NativeMethods;

public class WinUtils {
	public static void open(File f) throws FileNotFoundException {
		if (!f.exists()) {
			throw new FileNotFoundException();
		}
		try {
			ProcessBuilder builder = new ProcessBuilder();
			ArrayList<String> lst = new ArrayList<String>();
			lst.add("rundll32");
			lst.add("url.dll,FileProtocolHandler");
			lst.add(f.getAbsolutePath());
			builder.command(lst);
			builder.start();
		} catch (IOException e) {
			Logger.log(e);
		}
	}

	public static void openFolder(String folder, String file) throws FileNotFoundException {
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
			ArrayList<String> lst = new ArrayList<String>();
			lst.add("explorer");
			lst.add("/select,");
			lst.add(f.getAbsolutePath());
			builder.command(lst);
			builder.start();
		} catch (IOException e) {
			Logger.log(e);
		}
	}

	private static void openFolder2(String folder) {
		try {
			ProcessBuilder builder = new ProcessBuilder();
			ArrayList<String> lst = new ArrayList<String>();
			lst.add("explorer");
			lst.add(folder);
			builder.command(lst);
			builder.start();
		} catch (Exception e) {
			Logger.log(e);
		}
	}

	public static void keepAwakePing() {
		NativeMethods.getInstance().keepAwakePing();
	}

	public static void addToStartup() {
		String launchCmd = "\"" + System.getProperty("java.home") + "\\bin\\javaw.exe\" -Xmx1024m -jar \""
				+ XDMUtils.getJarFile().getAbsolutePath() + "\" -m";
		Logger.log("Launch CMD: " + launchCmd);
		NativeMethods.getInstance().addToStartup("XDM", launchCmd);
	}

	public static boolean isAlreadyAutoStart() {
		String launchCmd = "\"" + System.getProperty("java.home") + "\\bin\\javaw.exe\" -Xmx1024m -jar \""
				+ XDMUtils.getJarFile().getAbsolutePath() + "\" -m";
		Logger.log("Launch CMD: " + launchCmd);
		return NativeMethods.getInstance().presentInStartup("XDM", launchCmd);
	}

	public static void removeFromStartup() {
		NativeMethods.getInstance().removeFromStartup("XDM");
	}

	public static void browseURL(String url) {
		try {
			ProcessBuilder builder = new ProcessBuilder();
			ArrayList<String> lst = new ArrayList<String>();
			lst.add("rundll32");
			lst.add("url.dll,FileProtocolHandler");
			lst.add(url);
			builder.command(lst);
			builder.start();
		} catch (IOException e) {
			Logger.log(e);
		}
	}

	public static void initShutdown() {
		try {
			ProcessBuilder builder = new ProcessBuilder();
			ArrayList<String> lst = new ArrayList<String>();
			lst.add("shutdown");
			lst.add("-t");
			lst.add("30");
			lst.add("-s");
			builder.command(lst);
			builder.start();
		} catch (Exception e) {
			Logger.log(e);
		}
	}

}
