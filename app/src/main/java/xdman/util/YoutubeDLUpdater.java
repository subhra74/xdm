package xdman.util;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import xdman.Config;

/**
 * Keeps the bundled video parser (yt-dlp) current.
 *
 * yt-dlp needs frequent updates because it tracks changes on the sites it
 * supports, so a copy shipped with XDM stops working after a few months. It can
 * update itself, but only by rewriting its own executable, and the copy that
 * ships with XDM lives in the installation directory - writing there needs
 * elevated rights and, on macOS, invalidates the code signature of the
 * application bundle.
 *
 * So the shipped binary is copied into the XDM data folder and updated there.
 * {@link ExternalTools} searches the data folder before the installation
 * directory, so the updated copy is picked up automatically and the shipped one
 * stays untouched as a fallback.
 */
public class YoutubeDLUpdater {
	private static final long UPDATE_INTERVAL = 24 * 60 * 60 * 1000L;
	private static final long UPDATE_TIMEOUT_SECONDS = 120;

	/**
	 * Updates at most once a day, and only if the user has not turned it off.
	 * Safe to call from a background thread; failures are logged and ignored so
	 * that an offline machine never blocks anything.
	 */
	public static synchronized void updateIfDue() {
		Config config = Config.getInstance();
		if (!config.isYtdlAutoUpdate()) {
			return;
		}
		long now = System.currentTimeMillis();
		if (now - config.getYtdlLastUpdated() < UPDATE_INTERVAL) {
			return;
		}
		config.setYtdlLastUpdated(now);
		try {
			update();
		} catch (Exception e) {
			Logger.log(e);
		}
	}

	/**
	 * @return true if the updater ran and reported success.
	 */
	public static boolean update() {
		File installed = ExternalTools.getYoutubeDL();
		if (installed == null) {
			Logger.log("yt-dlp not found, skipping update");
			return false;
		}
		File updatable = getUpdatableCopy(installed);
		if (updatable == null) {
			return false;
		}
		try {
			List<String> args = new ArrayList<String>();
			args.add(updatable.getAbsolutePath());
			args.add("-U");
			ProcessBuilder pb = new ProcessBuilder(args);
			pb.redirectErrorStream(true);
			Process proc = pb.start();
			if (!proc.waitFor(UPDATE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
				proc.destroyForcibly();
				Logger.log("yt-dlp update timed out");
				return false;
			}
			int exit = proc.exitValue();
			Logger.log("yt-dlp update finished with exit code " + exit);
			return exit == 0;
		} catch (Exception e) {
			Logger.log(e);
			return false;
		}
	}

	/**
	 * Returns the copy in the data folder, creating or refreshing it from the
	 * shipped binary when needed.
	 */
	private static File getUpdatableCopy(File installed) {
		File dataFolder = new File(Config.getInstance().getDataFolder());
		File target = new File(dataFolder, installed.getName());
		if (installed.equals(target)) {
			return target;
		}
		/*
		 * A newer shipped binary means XDM itself was updated, so the older
		 * copy in the data folder must not keep shadowing it.
		 */
		if (target.exists() && target.lastModified() >= installed.lastModified()) {
			return target;
		}
		try {
			dataFolder.mkdirs();
			Files.copy(installed.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
			target.setExecutable(true, false);
			Logger.log("Copied " + installed + " to " + target + " for updating");
			return target;
		} catch (IOException e) {
			Logger.log(e);
			return null;
		}
	}
}
