package xdman.util;

import java.io.File;
import java.io.FilenameFilter;
import java.io.InputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import xdman.Config;
import xdman.XDMApp;
import xdman.network.http.JavaHttpClient;

import org.tinylog.Logger;

public class UpdateChecker {

	public static final int APP_UPDATE_AVAILABLE = 10,
			COMP_UPDATE_AVAILABLE = 20, COMP_NOT_INSTALLED = 30,
			NO_UPDATE_AVAILABLE = 40;

	private static final Pattern PATTERN_TAG = Pattern
			.compile("\\\"tag_name\\\"\\s*:\\s*\\\"(\\d+\\.\\d+\\.\\d+)\\\"");

	public static int getUpdateStat() {
		System.out.println("checking for app update");
		if (isAppUpdateAvailable())
			return APP_UPDATE_AVAILABLE;
		return NO_UPDATE_AVAILABLE;
//		int stat = isComponentUpdateAvailable();
//		System.out.println("Stat: " + stat);
//		if (stat == 0) {
//			return COMP_UPDATE_AVAILABLE;
//		} else if (stat == -1) {
//			return COMP_NOT_INSTALLED;
//		} else {
//			System.out.println("checking for app update");
//			if (isAppUpdateAvailable())
//				return APP_UPDATE_AVAILABLE;
//			return NO_UPDATE_AVAILABLE;
//		}
	}

	private static boolean isAppUpdateAvailable() {
		return isUpdateAvailable(XDMApp.APP_VERSION);
	}

	// return 1 is no update required
	// return 0, -1 if update required
	private static int isComponentUpdateAvailable() {
		String componentVersion = getComponentVersion();
		System.out.println("current component version: " + componentVersion);
		if (componentVersion == null)
			return -1;
		return isUpdateAvailable(componentVersion) ? 0 : 1;
	}

	public static String getComponentVersion() {
		File f = new File(Config.getInstance().getDataFolder());
		String[] files = f.list(new FilenameFilter() {
			@Override
			public boolean accept(File dir, String name) {
				return name.endsWith(".version");
			}
		});
		if (files == null || files.length < 1) {
			Logger.warn("Component not installed");
			Logger.warn("Checking fallback components");
			return getFallbackComponentVersion();
		}
		return files[0].split("\\.")[0];
	}

	public static String getFallbackComponentVersion() {
		File f = XDMUtils.getJarFile().getParentFile();
		String[] files = f.list(new FilenameFilter() {
			@Override
			public boolean accept(File dir, String name) {
				return name.endsWith(".version");
			}
		});
		if (files == null || files.length < 1) {
			Logger.warn("Component not installed");
			return null;
		}

		return files[0].split("\\.")[0];
	}

	private static boolean isUpdateAvailable(String version) {
		JavaHttpClient client = null;
		try {
			client = new JavaHttpClient(
					XDMApp.APP_UPDATE_URL + "?ver=" + version);
			client.setFollowRedirect(true);
			client.connect();
			int resp = client.getStatusCode();
			Logger.info("manifest download response: " + resp);
			if (resp == 200) {
				InputStream in = client.getInputStream();
				StringBuilder sb = new StringBuilder();
				while (true) {
					int x = in.read();
					if (x == -1)
						break;
					sb.append((char) x);
				}
				return isNewerVersion(sb, XDMApp.APP_VERSION);
			}
		} catch (Exception e) {
			Logger.error(e);
		} finally {
			try {
				client.dispose();
			} catch (Exception e) {
				Logger.error(e);
			}
		}
		return false;
	}

	private static boolean isNewerVersion(StringBuilder text, String v2) {
		try {
			// System.out.println(text);
			Matcher matcher = PATTERN_TAG.matcher(text);
			if (matcher.find()) {
				String v1 = matcher.group(1);
				System.out.println(v1 + " " + v2);
				if (v1.indexOf(".") > 0 && v2.indexOf(".") > 0) {
					String[] arr1 = v1.split("\\.");
					String[] arr2 = v2.split("\\.");
					for (int i = 0; i < Math.min(arr1.length,
							arr2.length); i++) {
						int ia = Integer.parseInt(arr1[i]);
						int ib = Integer.parseInt(arr2[i]);
						if (ia > ib) {
							return true;
						}
					}
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
		return false;
	}
}
