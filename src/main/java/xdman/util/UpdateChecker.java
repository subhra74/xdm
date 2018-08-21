package xdman.util;

import xdman.Config;
import xdman.XDMApp;
import xdman.network.http.JavaHttpClient;

import java.io.File;
import java.io.FilenameFilter;
import java.io.InputStream;
import java.net.UnknownHostException;

public class UpdateChecker {
	private static final String APP_UPDATE_URL = "http://xdman.sourceforge.net/update/update_check.php",
			COMPONENTS_UPDATE_URL = "http://xdman.sourceforge.net/components/update_check.php";
	public static final int APP_UPDATE_AVAILABLE = 10,
			COMP_UPDATE_AVAILABLE = 20,
			COMP_NOT_INSTALLED = 30,
			NO_UPDATE_AVAILABLE = 40;

	public static int getUpdateStat() {

		Integer isComponentUpdateAvailable = isComponentUpdateAvailable();
		Logger.log("isComponentUpdateAvailable: ", isComponentUpdateAvailable);
		if (isComponentUpdateAvailable == COMP_UPDATE_AVAILABLE
				|| isComponentUpdateAvailable == COMP_NOT_INSTALLED) {
			return isComponentUpdateAvailable;
		} else {
			Logger.log("checking for app update");
			int isAppUpdateAvailable = isAppUpdateAvailable();
			Logger.log("isAppUpdateAvailable: ", isAppUpdateAvailable);
			return isAppUpdateAvailable;
		}
	}

	private static int isAppUpdateAvailable() {
		String appVersion = XDMApp.APP_VERSION;
		Logger.log("Current App version:", appVersion);
		int isUpdateAvailable = isUpdateAvailable(APP_UPDATE_URL,
				appVersion,
				APP_UPDATE_AVAILABLE);
		return isUpdateAvailable;
	}

	// return 1 is no update required
	// return 0, -1 if update required
	private static Integer isComponentUpdateAvailable() {
		String componentVersion = getComponentVersion();
		Logger.log("Current component version:", componentVersion);
		if (componentVersion == null)
			return COMP_NOT_INSTALLED;
		int isUpdateAvailable = isUpdateAvailable(COMPONENTS_UPDATE_URL,
				componentVersion,
				COMP_UPDATE_AVAILABLE);
		return isUpdateAvailable;
	}

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

	private static int isUpdateAvailable(String updateURL,
	                                     String version,
	                                     int updateAvailable) {
		JavaHttpClient client = null;
		try {
			String url = String.format("%s?ver=%s",
					updateURL,
					version);
			Logger.log("isUpdateAvailable", url, version);
			client = new JavaHttpClient(url);
			client.setFollowRedirect(true);
			client.connect();
			int resp = client.getStatusCode();
			Logger.log("manifest download response:", resp);
			if (resp == 200) {
				InputStream in = client.getInputStream();
				StringBuffer sb = new StringBuffer();
				int x;
				while ((x = in.read()) != -1) {
					sb.append((char) x);
				}
				Boolean isNewerVersion = isNewerVersion(sb.toString(),
						XDMApp.APP_VERSION);
				int isUpdateAvailable = isNewerVersion
						? updateAvailable
						: NO_UPDATE_AVAILABLE;
				return isUpdateAvailable;
			}
		} catch (UnknownHostException e) {
			Logger.log(e);
			return NO_UPDATE_AVAILABLE;
		} catch (Exception e) {
			Logger.log(e);
		} finally {
			try {
				client.dispose();
			} catch (Exception e) {
				Logger.log(e);
			}
		}
		return NO_UPDATE_AVAILABLE;
	}

	private static Boolean isNewerVersion(String v1, String v2) {
		Logger.log("isNewerVersion", v1, v2);
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
			return true;
		}
	}
}
