package xdman.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import xdman.Config;
import xdman.DownloadListener;
import xdman.DownloadWindowListener;
import xdman.XDMConstants;
import xdman.downloaders.http.HttpDownloader;
import xdman.downloaders.metadata.HttpMetadata;
import xdman.ui.components.DownloadWindow;
import xdman.ui.components.FFmpegExtractorWnd;

public class FFmpegDownloader implements DownloadListener, DownloadWindowListener, FFExtractCallback {
	HttpDownloader d;
	DownloadWindow wnd;
	String url = "http://xdman.sourceforge.net/components/";
	String tmpFile;
	boolean stop;

	private static final String XP_COMPONENT = "xp.zip", WIN7_COMPONENT = "win.zip", MAC_COMPONENT = "mac.zip",
			LINUX32_COMPONENT = "linux32.zip", LINUX64_COMPONENT = "linux64.zip";

	public FFmpegDownloader() {
		if (XDMUtils.detectOS() == XDMUtils.WINDOWS) {
			if (XDMUtils.below7()) {
				url += XP_COMPONENT;
			} else {
				url += WIN7_COMPONENT;
			}
		} else if (XDMUtils.detectOS() == XDMUtils.MAC) {
			url += MAC_COMPONENT;
		} else if (XDMUtils.detectOS() == XDMUtils.LINUX) {
			if (XDMUtils.getOsArch() == 32) {
				url += LINUX32_COMPONENT;
			} else {
				url += LINUX64_COMPONENT;
			}
		}
		tmpFile = UUID.randomUUID().toString();
	}

	public void start() {
		HttpMetadata metadata = new HttpMetadata();
		metadata.setUrl(url);
		System.out.println(url);
		d = new HttpDownloader(metadata.getId(), Config.getInstance().getTemporaryFolder(), metadata);
		d.registerListener(this);
		d.start();
		wnd = new DownloadWindow(metadata.getId(), this);
		wnd.setVisible(true);
	}

	@Override
	public void downloadFinished(String id) {
		extractFFmpeg();
		wnd.close(XDMConstants.FINISHED, 0);
	}

	@Override
	public void downloadFailed(String id) {
		wnd.close(XDMConstants.FAILED, d.getErrorCode());
		deleteTmpFiles(id);
	}

	@Override
	public void downloadStopped(String id) {
		wnd.close(XDMConstants.PAUSED, 0);
		deleteTmpFiles(id);
	}

	@Override
	public void downloadConfirmed(String id) {
		// TODO Auto-generated method stub

	}

	@Override
	public void downloadUpdated(String id) {
		wnd.update(d, "Components");
	}

	@Override
	public String getOutputFolder(String id) {
		return Config.getInstance().getTemporaryFolder();
	}

	@Override
	public String getOutputFile(String id, boolean update) {
		return tmpFile;
	}

	@Override
	public void pauseDownload(String id) {
		if (d != null) {
			d.stop();
			d.unregisterListener();
		}
	}

	@Override
	public void hidePrgWnd(String id) {
		// TODO Auto-generated method stub

	}

	private void deleteTmpFiles(String id) {
		Logger.log("Deleting metadata for " + id);
		File mf = new File(Config.getInstance().getMetadataFolder(), id);
		boolean deleted = mf.delete();
		Logger.log("Deleted manifest " + id + " " + deleted);
		File df = new File(Config.getInstance().getTemporaryFolder(), id);
		File[] files = df.listFiles();
		if (files != null && files.length > 0) {
			for (File f : files) {
				deleted = f.delete();
				Logger.log("Deleted tmp file " + id + " " + deleted);
			}
		}
		deleted = df.delete();
		Logger.log("Deleted tmp folder " + id + " " + deleted);
	}

	FFmpegExtractorWnd wnd2;

	private void extractFFmpeg() {
		ZipInputStream zipIn = null;
		OutputStream out = null;
		wnd2 = new FFmpegExtractorWnd(this);
		wnd2.setVisible(true);
		try {
			String versionFile = null;
			File input = new File(Config.getInstance().getTemporaryFolder(), tmpFile);
			zipIn = new ZipInputStream(new FileInputStream(input));

			while (true) {
				ZipEntry ent = zipIn.getNextEntry();
				if (ent == null)
					break;
				String name = ent.getName();
				if (name.endsWith(".version")) {
					versionFile = name;
				}
				File outFile = new File(Config.getInstance().getDataFolder(), name);
				out = new FileOutputStream(outFile);
				byte[] buf = new byte[8192];
				while (true) {
					int x = zipIn.read(buf);
					if (x == -1)
						break;
					out.write(buf, 0, x);
				}
				out.close();
				out = null;
				outFile.setExecutable(true);
			}

			// remove old x.version files if exists
			try {
				if (Config.getInstance().getDataFolder() != null) {
					File[] files = new File(Config.getInstance().getDataFolder()).listFiles();
					if (files != null) {
						for (File f : files) {
							if (f.getName().endsWith(".version") && (!f.getName().equals(versionFile))) {
								f.delete();
							}
						}
					}
				}
			} catch (Exception e) {
				Logger.log(e);
			}

			input.delete();
			wnd2.dispose();
		} catch (Exception e) {
			Logger.log(e);
		} finally {
			try {
				zipIn.close();
				if (out != null)
					out.close();
			} catch (Exception e) {
				Logger.log(e);
			}
		}
	}

	public void stop() {
		if (wnd2 != null)
			wnd2.dispose();
	}

}
