package xdman.util;

import java.io.File;
import java.io.FilenameFilter;

import xdman.Config;
import xdman.XDMApp;
import xdman.network.http.JavaHttpClient;

public class UpdateChecker {
	private static final String SF_APP_UPDAT_URL = "http://xdman.sourceforge.net/update/update_check.php",
			GH_APP_UPDAT_URL = "https://subhra74.github.com/xdm/update/update_check.php",
			COMPONENTS_UPDATE_URL = "http://xdman.sourceforge.net/components/update_check.php";

	public static final int APP_UPDATE_AVAILABLE = 10, COMP_UPDATE_AVAILABLE = 20, COMP_NOT_INSTALLED = 30,
			NO_UPDATE_AVAILABLE = 40;

	public static int getUpdateStat() {
		if (isAppUpdateAvailable())
			return APP_UPDATE_AVAILABLE;
		return NO_UPDATE_AVAILABLE;
	}

	private static boolean isAppUpdateAvailable() {
		return isUpdateAvailable(XDMApp.APP_VERSION);
	}

	// return 1 is no update required
	// return 0, -1 if update required
	// private static int isComponentUpdateAvailable() {
	// String componentVersion = getComponentVersion();
	// System.out.println("current component version: " + componentVersion);
	// if (componentVersion == null)
	// return -1;
	// return isUpdateAvailable(false, componentVersion) ? 0 : 1;
	// }

	public static String getComponentVersion() {
		File f = new File(Config.getInstance().getDataFolder());
		String[] files = f.list(new FilenameFilter() {
			@Override
			public boolean accept(File dir, String name) {
				return name.endsWith(".version");
			}
		});
		if (files.length < 1) {
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
		if (files.length < 1) {
			Logger.log("Component not installed");
			return null;
		}
		return files[0].split("\\.")[0];
	}

	private static boolean isUpdateAvailable(String version) {
		JavaHttpClient client = null;
		try {
			client = new JavaHttpClient( SF_APP_UPDAT_URL + "?ver=" + version);
			client.setFollowRedirect(true);
			client.connect();
			int resp = client.getStatusCode();
			Logger.log("manifest download response: " + resp);
			if (resp == 200) {
				return true;
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

	private static boolean checkAppUpdate(String version) {
		String[] urls = { GH_APP_UPDAT_URL, SF_APP_UPDAT_URL };
		while (true) {

		}
	}
}
