package xdman.downloaders;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;

import xdman.Config;
import xdman.DownloadListener;
import xdman.XDMApp;
import xdman.XDMConstants;
import xdman.downloaders.http.HttpChannel;
import xdman.downloaders.metadata.HttpMetadata;
import xdman.mediaconversion.FFmpeg;
import xdman.util.HttpDateParser;
import xdman.util.Logger;
import xdman.util.StringUtils;

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

	public void setOuputMediaFormat(int format) {
		this.outputFormat = format;
	}

	protected synchronized int retryFailedChunks(int rem) throws IOException {
		if (stopFlag)
			return 0;
		int count = 0;
		int totalInactive = findTotalInactiveChunk();
		Logger.log("Total inactive chunks: " + totalInactive);

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
					Logger.log("$$$ debug rem:" + rem);
				}
			}
		}
		return count;
	}

	protected Segment findInactiveChunk() {
		if (stopFlag)
			return null;
		for (int i = 0; i < chunks.size(); i++) {
			Segment c = chunks.get(i);
			if (c.isFinished() || c.isActive())
				continue;
			return c;
		}
		return null;
	}

	protected int findTotalInactiveChunk() {
		int count = 0;
		for (int i = 0; i < chunks.size(); i++) {
			Segment c = chunks.get(i);
			if (c.isFinished() || c.isActive())
				continue;
			count++;
		}
		return count;
	}

	public int getActiveChunkCount() {
		int count = 0;
		for (int i = 0; i < chunks.size(); i++) {
			if (chunks.get(i).isActive()) {
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
			for (int i = 0; i < chunks.size(); i++) {
				Segment chunk = chunks.get(i);
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
		for (int i = 0; i < chunks.size(); i++) {
			if (chunks.get(i).getId().equals(id)) {
				return chunks.get(i);
			}
		}
		return null;
	}

	public void cleanup() {
		File dir = new File(folder);
		File[] files = dir.listFiles();
		if (files != null) {
			for (int i = 0; i < files.length; i++) {
				Logger.log("Delete: " + files[i] + " [" + files[i].length() + "] " + files[i].delete());
			}
		}

		new File(folder).delete();
	}

	// call this method before calling socket.recv
	// so that thread waits before all length manipulation is
	// done without data corruption
	public synchronized void synchronize() {

	}

	@Override
	public synchronized void chunkFailed(String id, String reason) {
		if (stopFlag)
			return;
		int err = 0;
		for (int i = 0; i < chunks.size(); i++) {
			Segment chunk = chunks.get(i);
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
			Logger.log("Setting final error code: " + err);
			this.errorCode = err;
		}

		this.listener.downloadFailed(this.id);
		Logger.log("failed");
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
		File files[] = f.listFiles();
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
				System.out.println("setting date");
				Date lastModified = HttpDateParser.parseHttpDate(this.lastModified);
				if (lastModified != null) {
					System.out.println("setting date file " + lastModified);
					boolean val = outFile.setLastModified(lastModified.getTime());
					System.out.println("rename: " + val + new Date(outFile.lastModified()));
				}
			} catch (Exception e) {
				Logger.log(e);
			}
		}
	}

	public void getLastModifiedDate(Segment c) {
		if (StringUtils.isNullOrEmpty(lastModified)) {
			try {
				this.lastModified = ((HttpChannel) c.getChannel()).getHeader("last-modified");
			} catch (Exception e) {
				Logger.log(e);
			}
		}
	}

	protected void clearChannel(Segment s) {
		if (s != null) {
			s.clearChannel();
		}
	}

}
