/*
 * Copyright (c)  Subhra Das Gupta
 *
 * This file is part of Xtreme Download Manager.
 *
 * Xtreme Download Manager is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * Xtreme Download Manager is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with Xtream Download Manager; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA
 * 
 */

package xdman.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.tinylog.Logger;

import xdman.Config;
import xdman.DownloadListener;
import xdman.DownloadWindowListener;
import xdman.XDMConstants;
import xdman.downloaders.http.HttpDownloader;
import xdman.downloaders.metadata.HttpMetadata;
import xdman.ui.components.DownloadWindow;
import xdman.ui.components.FFmpegExtractorWnd;

@SuppressWarnings({ "ResultOfMethodCallIgnored", "unused" })
public class FFmpegDownloader implements DownloadListener, DownloadWindowListener, FFExtractCallback {

	private FFmpegExtractorWnd wnd2;
	private HttpDownloader d;
	private DownloadWindow wnd;
	private String url = "http://xdman.sourceforge.net/components/";
	private final String tmpFile;
	private boolean stop;

	private static final String XP_COMPONENT = "xp.zip", WIN7_COMPONENT = "win.zip", MAC_COMPONENT = "mac.zip",
			LINUX32_COMPONENT = "linux32.zip", LINUX64_COMPONENT = "linux64.zip";

	public FFmpegDownloader() {
		if (XDMUtils.detectOS() == XDMUtils.WINDOWS) {
			if (XDMUtils.below7()) {
				this.url += XP_COMPONENT;
			} else {
				this.url += WIN7_COMPONENT;
			}
		} else if (XDMUtils.detectOS() == XDMUtils.MAC) {
			this.url += MAC_COMPONENT;
		} else if (XDMUtils.detectOS() == XDMUtils.LINUX) {
			if (XDMUtils.getOsArch() == 32) {
				this.url += LINUX32_COMPONENT;
			} else {
				this.url += LINUX64_COMPONENT;
			}
		}
		this.tmpFile = UUID.randomUUID().toString();
	}

	public void start() {
		HttpMetadata metadata = new HttpMetadata();
		metadata.setUrl(this.url);
		Logger.info(this.url);
		this.d = new HttpDownloader(metadata.getId(), Config.getInstance().getTemporaryFolder(), metadata);
		this.d.registerListener(this);
		this.d.start();
		this.wnd = new DownloadWindow(metadata.getId(), this);
		this.wnd.setVisible(true);
	}

	@Override
	public void downloadFinished(String id) {
		extractFFmpeg();
		this.wnd.close(XDMConstants.FINISHED, 0);
	}

	@Override
	public void downloadFailed(String id) {
		this.wnd.close(XDMConstants.FAILED, this.d.getErrorCode());
		deleteTmpFiles(id);
	}

	@Override
	public void downloadStopped(String id) {
		this.wnd.close(XDMConstants.PAUSED, 0);
		deleteTmpFiles(id);
	}

	@Override
	public void downloadConfirmed(String id) {
	}

	@Override
	public void downloadUpdated(String id) {
		this.wnd.update(d, "Components");
	}

	@Override
	public String getOutputFolder(String id) {
		return Config.getInstance().getTemporaryFolder();
	}

	@Override
	public String getOutputFile(String id, boolean update) {
		return this.tmpFile;
	}

	@Override
	public void pauseDownload(String id) {
		if (this.d != null) {
			this.d.stop();
			this.d.unregisterListener();
		}
	}

	@Override
	public void hidePrgWnd(String id) {
	}

	private void deleteTmpFiles(String id) {
		Logger.info("Deleting metadata for " + id);
		File mf = new File(Config.getInstance().getMetadataFolder(), id);
		boolean deleted = mf.delete();
		Logger.info("Deleted manifest " + id + " " + deleted);
		File df = new File(Config.getInstance().getTemporaryFolder(), id);
		File[] files = df.listFiles();
		if (files != null && files.length > 0) {
			for (File f : files) {
				deleted = f.delete();
				Logger.info("Deleted tmp file " + id + " " + deleted);
			}
		}
		deleted = df.delete();
		Logger.info("Deleted tmp folder " + id + " " + deleted);
	}

	private void extractFFmpeg() {
		ZipInputStream zipIn = null;
		OutputStream out = null;
		this.wnd2 = new FFmpegExtractorWnd(this);
		this.wnd2.setVisible(true);
		try {
			String versionFile = null;
			File input = new File(Config.getInstance().getTemporaryFolder(), this.tmpFile);
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
				IOUtils.closeFlow(out);
				out = null;
				outFile.setExecutable(true);
			}

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
				Logger.error(e);
			}
			input.delete();
			this.wnd2.dispose();
		} catch (Exception e) {
			Logger.error(e);
		} finally {
			IOUtils.closeFlow(zipIn);
			IOUtils.closeFlow(out);
		}
	}

	public void stop() {
		if (this.wnd2 != null) {
			this.wnd2.dispose();
		}
	}

}
