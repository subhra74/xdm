package xdman.util;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.WinReg;

import xdman.Config;
import xdman.XDMApp;
import xdman.ui.components.MessageBox;

public class NativeMessagingHostInstaller {
	private static final String CHROME_EXTENSION_IDS = String.join(",",
			"\"chrome-extension://danmljfachfhpbfikjgedlfifabhofcj/\"",
			"\"chrome-extension://dkckaoghoiffdbomfbbodbbgmhjblecj/\"");

	private static final String FIREFOX_EXTENSION_IDS = String.join(",", "\"browser-mon@xdman.sourceforge.net\"");

	private static final String CHROME_LINUX_LOCATION = ".config/google-chrome/NativeMessagingHosts",
			FIREFOX_LINUX_LOCATION = ".mozilla/native-messaging-hosts",
			CHROME_MAC_LOCATION = "Library/Application Support/Google/Chrome/NativeMessagingHosts",
			FIREFOX_MAC_LOCATION = "Library/Application Support/Mozilla/NativeMessagingHosts",
			CHROMIUM_LINUX_LOCATION = ".config/chromium/NativeMessagingHosts",
			CHROMIUM_MAC_LOCATION = "Library/Application Support/Chromium/NativeMessagingHosts";

	public static final synchronized void installNativeMessagingHostForChrome() {
		installNativeMessagingHostForChrome(XDMUtils.detectOS(), false);
	}

	public static final void installNativeMessagingHostForChromium() {
		installNativeMessagingHostForChrome(XDMUtils.detectOS(), true);
	}

	public static final void installNativeMessagingHostForFireFox() {
		installNativeMessagingHostForFireFox(XDMUtils.detectOS());
	}

	private static final void installNativeMessagingHostForChrome(int os, boolean chromium) {
		if (os == XDMUtils.WINDOWS) {
			if (!Advapi32Util.registryKeyExists(WinReg.HKEY_CURRENT_USER,
					"Software\\Google\\Chrome\\NativeMessagingHosts\\xdm_chrome.native_host")) {
				if (!Advapi32Util.registryCreateKey(WinReg.HKEY_CURRENT_USER,
						"Software\\Google\\Chrome\\NativeMessagingHosts", "xdm_chrome.native_host")) {
					MessageBox.show(XDMApp.getInstance().getMainWindow(),
							"Error: Unable to register native messaging host");
					return;
				}
			}
			File manifestFile = new File(Config.getInstance().getDataFolder(), "xdm_chrome.native_host.json");
			File nativeHostFile = new File(XDMUtils.getJarFile().getParentFile(), "native_host.exe");
			createNativeManifest(manifestFile, nativeHostFile, BrowserType.Chrome);
			try {
				Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER,
						"Software\\Google\\Chrome\\NativeMessagingHosts\\xdm_chrome.native_host", null,
						manifestFile.getAbsolutePath());
			} catch (Exception e) {
				MessageBox.show(XDMApp.getInstance().getMainWindow(),
						"Error: Unable to register native messaging host");
				return;
			}
		} else {
			File manifestFolder = new File(System.getProperty("user.home"),
					os == XDMUtils.MAC ? (chromium ? CHROMIUM_MAC_LOCATION : CHROME_MAC_LOCATION)
							: (chromium ? CHROMIUM_LINUX_LOCATION : CHROME_LINUX_LOCATION));
			if (!manifestFolder.exists()) {
				manifestFolder.mkdirs();
			}
			File manifestFile = new File(manifestFolder, "xdm_chrome.native_host.json");
			File nativeHostFile = new File(XDMUtils.getJarFile().getParentFile(), "native_host");
			createNativeManifest(manifestFile, nativeHostFile, BrowserType.Chrome);
		}

	}

	public static final void installNativeMessagingHostForFireFox(int os) {
		if (os == XDMUtils.WINDOWS) {
			if (!Advapi32Util.registryKeyExists(WinReg.HKEY_CURRENT_USER,
					"Software\\Mozilla\\NativeMessagingHosts\\xdmff.native_host")) {
				if (!Advapi32Util.registryCreateKey(WinReg.HKEY_CURRENT_USER, "Software\\Mozilla\\NativeMessagingHosts",
						"xdmff.native_host")) {
					MessageBox.show(XDMApp.getInstance().getMainWindow(),
							"Error: Unable to register native messaging host");
					return;
				}
			}

			File manifestFile = new File(Config.getInstance().getDataFolder(), "xdmff.native_host.json");
			File nativeHostFile = new File(XDMUtils.getJarFile().getParentFile(), "native_host.exe");
			createNativeManifest(manifestFile, nativeHostFile, BrowserType.Firefox);
			try {
				Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER,
						"Software\\Mozilla\\NativeMessagingHosts\\xdmff.native_host", null,
						manifestFile.getAbsolutePath());
			} catch (Exception e) {
				MessageBox.show(XDMApp.getInstance().getMainWindow(),
						"Error: Unable to register native messaging host");
				return;
			}
		} else {
			File manifestFolder = new File(System.getProperty("user.home"),
					os == XDMUtils.MAC ? FIREFOX_MAC_LOCATION : FIREFOX_LINUX_LOCATION);
			if (!manifestFolder.exists()) {
				manifestFolder.mkdirs();
			}
			File manifestFile = new File(manifestFolder, "xdmff.native_host.json");
			File nativeHostFile = new File(XDMUtils.getJarFile().getParentFile(), "native_host");
			createNativeManifest(manifestFile, nativeHostFile, BrowserType.Firefox);
		}
	}

	private static final void createNativeManifest(File manifestFile, File nativeHostFile, BrowserType browserType) {
		try (OutputStream out = new FileOutputStream(manifestFile)) {
			String name, manifestKey, extension;
			if (browserType == BrowserType.Chrome || browserType == BrowserType.Chromium) {
				manifestKey = "\"allowed_origins\"";
				extension = CHROME_EXTENSION_IDS;
				name = "\"xdm_chrome.native_host\"";
			} else {
				manifestKey = "\"allowed_extensions\"";
				extension = FIREFOX_EXTENSION_IDS;
				name = "\"xdmff.native_host\"";
			}

			String json = String.format(
					"{\n" + "  \"name\": %s,\n"
							+ "  \"description\": \"Native messaging host for Xtreme Download Manager\",\n"
							+ "  \"path\": \"%s\",\n" + "  \"type\": \"stdio\",\n" + "  %s: [ %s ]\n" + "}",
					name, nativeHostFile.getAbsolutePath().replace("\\", "\\\\"), manifestKey, extension);

			out.write(json.getBytes("utf-8"));
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	public enum BrowserType {
		Chrome, Chromium, Firefox
	}
}
