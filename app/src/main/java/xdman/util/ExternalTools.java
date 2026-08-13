package xdman.util;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import xdman.Config;

/**
 * Locates the external helper programs XDM shells out to.
 *
 * Every call site used to look in exactly two places - the XDM data folder and
 * the folder holding xdman.jar. That fails when XDM is installed as a self
 * contained application bundle, because the jar then lives inside the package
 * where the user can not drop a binary next to it, and it ignores a copy that
 * is already installed system wide.
 */
public class ExternalTools {
	/*
	 * A GUI process launched from the desktop shell inherits a minimal PATH, so
	 * the usual package manager locations are checked explicitly as well.
	 */
	private static final String[] COMMON_UNIX_FOLDERS = { "/opt/homebrew/bin", "/usr/local/bin", "/usr/bin", "/bin",
			"/opt/local/bin", "/snap/bin" };

	public static File getFFmpeg() {
		return find(exeName("ffmpeg"));
	}

	/**
	 * yt-dlp is the maintained fork of youtube-dl and takes the same command
	 * line arguments, so it is preferred when both are present.
	 */
	public static File getYoutubeDL() {
		File ytdlp = find(exeName("yt-dlp"));
		return ytdlp == null ? find(exeName("youtube-dl")) : ytdlp;
	}

	public static boolean isFFmpegInstalled() {
		return getFFmpeg() != null;
	}

	public static boolean isYoutubeDLInstalled() {
		return getYoutubeDL() != null;
	}

	private static String exeName(String name) {
		return XDMUtils.detectOS() == XDMUtils.WINDOWS ? name + ".exe" : name;
	}

	private static File find(String exe) {
		for (File folder : getSearchFolders()) {
			File f = new File(folder, exe);
			if (f.isFile() && f.canExecute()) {
				return f;
			}
		}
		return null;
	}

	private static List<File> getSearchFolders() {
		List<File> folders = new ArrayList<File>();
		String dataFolder = Config.getInstance().getDataFolder();
		if (!StringUtils.isNullOrEmpty(dataFolder)) {
			folders.add(new File(dataFolder));
		}
		File jarFile = XDMUtils.getJarFile();
		if (jarFile != null && jarFile.getParentFile() != null) {
			folders.add(jarFile.getParentFile());
		}
		String path = System.getenv("PATH");
		if (path != null) {
			for (String entry : path.split(File.pathSeparator)) {
				if (entry.length() > 0) {
					folders.add(new File(entry));
				}
			}
		}
		if (XDMUtils.detectOS() != XDMUtils.WINDOWS) {
			for (String folder : COMMON_UNIX_FOLDERS) {
				folders.add(new File(folder));
			}
		}
		return folders;
	}
}
