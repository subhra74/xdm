package xdm.app.util;

import xdm.core.util.XDMUtils;

import java.io.File;

public class BrowserLauncher {
	public static boolean launchFirefox(String args) {
		int os = PlatformUtils.detectOS();
		if (os == PlatformUtils.WINDOWS) {
			File[] ffPaths = { new File(System.getenv("ProgramW6432"), "Mozilla Firefox\\firefox.exe"),
					new File(System.getenv("PROGRAMFILES"), "Mozilla Firefox\\firefox.exe"),
					new File(System.getenv("PROGRAMFILES(X86)"), "Mozilla Firefox\\firefox.exe") };
			for (int i = 0; i < ffPaths.length; i++) {
				System.out.println(ffPaths[i]);
				if (ffPaths[i].exists()) {
					return PlatformUtils.exec("\"" + ffPaths[i] + "\" " + args);
				}
			}
		}
		if (os == PlatformUtils.MAC) {
			File[] ffPaths = { new File("/Applications/Firefox.app") };
			for (int i = 0; i < ffPaths.length; i++) {
				if (ffPaths[i].exists()) {
					return MacUtils.launchApp(ffPaths[i].getAbsolutePath(), args);
				}
			}
		}
		if (os == PlatformUtils.LINUX) {
			File[] ffPaths = { new File("/usr/bin/firefox") };
			for (int i = 0; i < ffPaths.length; i++) {
				if (ffPaths[i].exists()) {
					return PlatformUtils.exec(ffPaths[i] + " " + args);
				}
			}
		}
		return false;
	}

	public static boolean launchChrome(String args) {
		int os = PlatformUtils.detectOS();
		if (os == PlatformUtils.WINDOWS) {
			File[] ffPaths = { new File(System.getenv("PROGRAMFILES"), "Google\\Chrome\\Application\\chrome.exe"),
					new File(System.getenv("PROGRAMFILES(X86)"), "Google\\Chrome\\Application\\chrome.exe"),
					new File(System.getenv("LOCALAPPDATA"), "Google\\Chrome\\Application\\chrome.exe") };
			for (int i = 0; i < ffPaths.length; i++) {
				if (ffPaths[i].exists()) {
					return PlatformUtils.exec("\"" + ffPaths[i] + "\" " + args);
				}
			}
		}
		if (os == PlatformUtils.MAC) {
			File[] ffPaths = { new File("/Applications/Google Chrome.app") };
			for (int i = 0; i < ffPaths.length; i++) {
				if (ffPaths[i].exists()) {
					return MacUtils.launchApp(ffPaths[i].getAbsolutePath(), args);
				}
			}
		}
		if (os == PlatformUtils.LINUX) {
			File[] ffPaths = { new File("/usr/bin/google-chrome") };
			for (int i = 0; i < ffPaths.length; i++) {
				if (ffPaths[i].exists()) {
					return PlatformUtils.exec(ffPaths[i] + " " + args);
				}
			}
		}
		return false;
	}
}
