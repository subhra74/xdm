package xdman.util;

import java.io.File;

public class BrowserLauncher {
	public static boolean launchFirefox(String args) {
		int os = XDMUtils.detectOS();
		if (os == XDMUtils.WINDOWS) {
			String programFiles = System.getenv("PROGRAMFILES");
			String programFilesX86 = System.getenv("PROGRAMFILES(X86)");
			String firefox = "Mozilla Firefox\\firefox.exe";
			File[] ffPaths = {new File(programFiles, firefox),
					new File(programFilesX86, firefox)};
			for (int i = 0; i < ffPaths.length; i++) {
				Logger.log(ffPaths[i]);
				if (ffPaths[i].exists()) {
					return XDMUtils.exec(String.format("\"%s\" %s", ffPaths[i], args));
				}
			}
		}
		if (os == XDMUtils.MAC) {
			File[] ffPaths = { new File("/Applications/Firefox.app") };
			for (int i = 0; i < ffPaths.length; i++) {
				if (ffPaths[i].exists()) {
					return MacUtils.launchApp(ffPaths[i].getAbsolutePath(), args);
				}
			}
		}
		if(os==XDMUtils.LINUX) {
			File[] ffPaths = { new File("/usr/bin/firefox") };
			for (int i = 0; i < ffPaths.length; i++) {
				if (ffPaths[i].exists()) {
					return XDMUtils.exec(String.format("%s %s", ffPaths[i], args));
				}
			}
		}
		return false;
	}

	public static boolean launchChrome(String args) {
		int os = XDMUtils.detectOS();
		if (os == XDMUtils.WINDOWS) {
			String programFiles = System.getenv("PROGRAMFILES");
			String programFilesX86 = System.getenv("PROGRAMFILES(X86)");
			String localAppData = System.getenv("LOCALAPPDATA");
			String chrome = "Google\\Chrome\\Application\\chrome.exe";
			File[] ffPaths = {new File(programFiles, chrome),
					new File(programFilesX86, chrome),
					new File(localAppData, chrome)};
			for (int i = 0; i < ffPaths.length; i++) {
				if (ffPaths[i].exists()) {
					return XDMUtils.exec(String.format("\"%s\" %s", ffPaths[i], args));
				}
			}
		}
		if (os == XDMUtils.MAC) {
			File[] ffPaths = { new File("/Applications/Google Chrome.app") };
			for (int i = 0; i < ffPaths.length; i++) {
				if (ffPaths[i].exists()) {
					return MacUtils.launchApp(ffPaths[i].getAbsolutePath(), args);
				}
			}
		}
		if (os == XDMUtils.LINUX) {
			File[] ffPaths = { new File("/usr/bin/google-chrome") };
			for (int i = 0; i < ffPaths.length; i++) {
				if (ffPaths[i].exists()) {
					return XDMUtils.exec(String.format("%s %s", ffPaths[i], args));
				}
			}
		}
		return false;
	}
}
