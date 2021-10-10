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

package xdman.downloaders;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;

import org.tinylog.Logger;

import xdman.Config;
import xdman.DownloadListener;
import xdman.XDMApp;
import xdman.XDMConstants;
import xdman.downloaders.http.HttpChannel;
import xdman.downloaders.metadata.HttpMetadata;
import xdman.mediaconversion.FFmpeg;
import xdman.util.HttpDateParser;
import xdman.util.StringUtils;

@SuppressWarnings("ResultOfMethodCallIgnored")
public abstract class Downloader implements SegmentListener {

	protected volatile boolean stopFlag;
	protected boolean isJavaClientRequired;
	protected long length;
	protected String folder;
	protected String id;
	protected boolean finished;
	protected int MAX_COUNT = 8;
	protected DownloadListener listener;
	protected long downloaded;
	protected long lastDownloaded;
	protected long prevTime;
	protected int progress;
	protected long lastUpdated;
	protected long lastSaved;
	protected boolean assembling;
	protected float downloadSpeed;
	protected String eta;
	protected SegmentDetails segDet;
	protected int errorCode;
	protected int outputFormat;
	protected boolean converting;
	protected int convertPrg;
	protected String lastModified;
	protected FFmpeg ffmpeg;

	protected ArrayList<Segment> chunks;

	public abstract void start();

	public abstract void stop();

	public abstract void resume();

	public abstract int getType();

	public long getSize() {
		return length;
	}

	public int getProgress() {
		return progress;
	}

	public long getDownloaded() {
		return downloaded;
	}

	public abstract boolean isFileNameChanged();

	public abstract String getNewFile();

	public abstract HttpMetadata getMetadata();

	public String getId() {
		return this.id;
	}

	public int getErrorCode() {
		return errorCode;
	}

	public boolean isAssembling() {
		return assembling;
	}

	public boolean isConverting() {
		return converting;
	}

	public float getDownloadSpeed() {
		return downloadSpeed;
	}

	public String getEta() {
		return eta;
	}

	public SegmentDetails getSegmentDetails() {
		return segDet;
	}

	public void setOutputMediaFormat(int format) {
		this.outputFormat = format;
	}

	protected synchronized int retryFailedChunks(int rem) throws IOException {
		if (stopFlag)
			return 0;
		int count = 0;
		int totalInactive = findTotalInactiveChunk();
		Logger.info("Total inactive chunks: " + totalInactive);

		if (totalInactive > rem) {
			totalInactive = rem;
		}
		if (totalInactive > 0) {
			for (; totalInactive > 0; totalInactive--) {
				Segment c = findInactiveChunk();
				if (c != null) {
					c.download(this);
					count++;
				} else {
					Logger.info("$$$ debug rem:" + rem);
				}
			}
		}
		return count;
	}

	protected Segment findInactiveChunk() {
		if (stopFlag)
			return null;
		for (Segment c : chunks) {
			if (c.isFinished() || c.isActive())
				continue;
			return c;
		}
		return null;
	}

	protected int findTotalInactiveChunk() {
		int count = 0;
		for (Segment c : chunks) {
			if (c.isFinished() || c.isActive())
				continue;
			count++;
		}
		return count;
	}

	public int getActiveChunkCount() {
		int count = 0;
		for (Segment chunk : chunks) {
			if (chunk.isActive()) {
				count++;
			}
		}
		return count;
	}

	public void registerListener(DownloadListener listener) {
		this.listener = listener;
	}

	public void unregisterListener() {
		this.listener = null;
	}

	protected boolean allFinished() {
		if (chunks.size() > 0) {
			for (Segment chunk : chunks) {
				if (!chunk.isFinished()) {
					return false;
				}
			}
			return true;
		} else {
			return false;
		}
	}

	protected Segment getById(String id) {
		for (Segment chunk : chunks) {
			if (chunk.getId().equals(id)) {
				return chunk;
			}
		}
		return null;
	}

	public void cleanup() {
		File dir = new File(folder);
		File[] files = dir.listFiles();
		if (files != null) {
			for (File file : files) {
				Logger.info("Delete: " + file + " [" + file.length() + "] " + file.delete());
			}
		}

		new File(this.folder).delete();
	}

	public synchronized void synchronize() {

	}

	@Override
	public synchronized void chunkFailed(String id, String reason) {
		if (stopFlag)
			return;
		int err = 0;
		for (Segment chunk : chunks) {
			if (chunk.isActive()) {
				return;
			}
			if (chunk.getErrorCode() != 0) {
				err = chunk.getErrorCode();
			}
		}

		if (err == XDMConstants.ERR_INVALID_RESP) {
			if (downloaded > 0) {
				if (length > 0) {
					if (chunks.size() > 1) {
						this.errorCode = XDMConstants.ERR_SESSION_FAILED;
					} else {
						this.errorCode = XDMConstants.ERR_NO_RESUME;
					}
				} else {
					this.errorCode = XDMConstants.ERR_NO_RESUME;
				}
			} else {
				this.errorCode = XDMConstants.ERR_INVALID_RESP;
			}
		} else {
			Logger.info("Setting final error code: " + err);
			this.errorCode = err;
		}

		this.listener.downloadFailed(this.id);
		Logger.error("failed");
	}

	protected String getOutputFileName(boolean updated) {
		return listener.getOutputFile(id, updated);
	}

	protected String getOutputFolder() {
		return listener.getOutputFolder(id);
	}

	@Override
	public synchronized boolean promptCredential(String msg, boolean proxy) {
		return XDMApp.getInstance().promptCredential(id, msg, proxy);
	}

	protected File getBackupFile(String folder) {
		File f = new File(folder);
		File[] files = f.listFiles();
		if (files == null || files.length < 1)
			return null;
		for (File file : files) {
			if (file.getName().endsWith(".bak")) {
				return file;
			}
		}
		return null;
	}

	public void setLastModifiedDate(File outFile) {
		if (Config.getInstance().isFetchTs()) {
			try {
				Logger.info("setting date");
				Date lastModified = HttpDateParser.parseHttpDate(this.lastModified);
				if (lastModified != null) {
					Logger.info("setting date file " + lastModified);
					boolean val = outFile.setLastModified(lastModified.getTime());
					Logger.info("rename: " + val + new Date(outFile.lastModified()));
				}
			} catch (Exception e) {
				Logger.error(e);
			}
		}
	}

	public void getLastModifiedDate(Segment c) {
		if (StringUtils.isNullOrEmpty(lastModified)) {
			try {
				this.lastModified = ((HttpChannel) c.getChannel()).getHeader("last-modified");
			} catch (Exception e) {
				Logger.error(e);
			}
		}
	}

	protected void clearChannel(Segment s) {
		if (s != null) {
			s.clearChannel();
		}
	}

}
