package xdman.util;

import java.io.File;
import java.io.FilenameFilter;
import java.io.InputStream;

import xdman.Config;
import xdman.XDMApp;
import xdman.network.http.JavaHttpClient;

public class UpdateChecker {
	private static final String APP_UPDAT_URL = "http://xdman.sourceforge.net/update/update_check.php",
			COMPONENTS_UPDATE_URL = "http://xdman.sourceforge.net/components/update_check.php";
	public static final int APP_UPDATE_AVAILABLE = 10, COMP_UPDATE_AVAILABLE = 20, COMP_NOT_INSTALLED = 30,
			NO_UPDATE_AVAILABLE = 40;

	public static int getUpdateStat() {

		int stat = isComponentUpdateAvailable();
		System.out.println("Stat: " + stat);
		if (stat == 0) {
			return COMP_UPDATE_AVAILABLE;
		} else if (stat == -1) {
			return COMP_NOT_INSTALLED;
		} else {
			System.out.println("checking for app update");
			if (isAppUpdateAvailable())
				return APP_UPDATE_AVAILABLE;
			return NO_UPDATE_AVAILABLE;
		}
	}

	private static boolean isAppUpdateAvailable() {
		return isUpdateAvailable(true, XDMApp.APP_VERSION);
	}

	// return 1 is no update required
	// return 0, -1 if update required
	private static int isComponentUpdateAvailable() {
		String componentVersion = getComponentVersion();
		System.out.println("current component version: " + componentVersion);
		if (componentVersion == null)
			return -1;
		return isUpdateAvailable(false, componentVersion) ? 0 : 1;
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
			Logger.log("Component not installed");
			Logger.log("Checking fallback components");
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
			Logger.log("Component not installed");
			return null;
		}

		return files[0].split("\\.")[0];
	}

	private static boolean isUpdateAvailable(boolean app, String version) {
		JavaHttpClient client = null;
		try {
			client = new JavaHttpClient((app ? APP_UPDAT_URL : COMPONENTS_UPDATE_URL) + "?ver=" + version);
			client.setFollowRedirect(true);
			client.connect();
			int resp = client.getStatusCode();
			Logger.log("manifest download response: " + resp);
			if (resp == 200) {
				InputStream in = client.getInputStream();
				StringBuffer sb = new StringBuffer();
				while (true) {
					int x = in.read();
					if (x == -1)
						break;
					sb.append((char) x);
				}
				return isNewerVersion(sb.toString(), XDMApp.APP_VERSION);
			}
		} catch (Exception e) {
			Logger.log(e);
		} finally {
			try {
				client.dispose();
			} catch (Exception e) {
			}
		}
		return false;
	}

	private static boolean isNewerVersion(String v1, String v2) {
		System.out.println(v1 + " " + v2);
		try {
			if (v1.indexOf(".") > 0 && v2.indexOf(".") > 0) {
				String[] arr1 = v1.split("\\.");
				String[] arr2 = v2.split("\\.");
				for (int i = 0; i < Math.min(arr1.length, arr2.length); i++) {
					int ia = Integer.parseInt(arr1[i]);
					int ib = Integer.parseInt(arr2[i]);
					if (ia > ib) {
						return true;
					}
				}
			}
			return false;
		} catch (Exception e) {
			return false;
		}
	}
}
