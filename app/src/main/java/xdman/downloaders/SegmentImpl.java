package xdman.downloaders;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.UUID;

import xdman.Config;
import xdman.util.Logger;

public class SegmentImpl implements Segment {

	private volatile long length, startOffset, downloaded;
	private RandomAccessFile outStream;
	private String id;
	volatile private SegmentListener cl;
	private volatile AbstractChannel channel;

	private long bytesRead1, bytesRead2, time1, time2;
	private float transferRate;
	private Config config;
	private volatile boolean stop;
	private int errorCode;
	private Object tag;
	private String folder;

	public SegmentImpl(SegmentListener cl, String folder) throws IOException {
		id = UUID.randomUUID().toString();
		this.cl = cl;
		this.folder = folder;
		this.time1 = System.currentTimeMillis();
		this.time2 = time1;
		this.config = Config.getInstance();
		outStream = new RandomAccessFile(new File(folder, id), "rw");// new
		// FileOutputStream(new
		// File(folder,
		// id));
		Logger.log("File opened " + id);
	}

	public SegmentImpl(String folder, String id, long off, long len, long dwn) throws IOException {
		this.id = id;
		this.id = id;
		this.startOffset = off;
		this.folder = folder;
		this.length = len;
		this.downloaded = dwn;
		this.time1 = System.currentTimeMillis();
		this.time2 = time1;
		this.bytesRead1 = dwn;
		this.bytesRead2 = dwn;
		try {
			outStream = new RandomAccessFile(new File(folder, id), "rw");
			outStream.seek(dwn);
			Logger.log("File opened " + id);
		} catch (IOException e) {
			Logger.log(e);
			if (outStream != null) {
				outStream.close();
			}
			throw new IOException(e);
		}
		this.config = Config.getInstance();
	}

	@Override
	public long getLength() {
		return length;
	}

	@Override
	public long getStartOffset() {
		return startOffset;
	}

	@Override
	public long getDownloaded() {
		return downloaded;
	}

	@Override
	public RandomAccessFile getOutStream() {
		return outStream;
	}

	@Override
	public boolean transferComplete() throws IOException {
		if (stop)
			return true;
		if (length < 0) {
			length = downloaded;
		}
		if (cl.chunkComplete(id)) {
			try {
				outStream.close();
			} catch (IOException e) {
				Logger.log(e);
			}
			channel = null;
			if (cl != null) {
				if (cl.shouldCleanup()) {
					cl.cleanup();
				}
			}
			return true;
		} else {
			return false;
		}
	}

	public void clearChannel() {
		this.channel = null;
	}

	@Override
	public void transferInitiated() throws IOException {
		if (stop)
			return;
		cl.chunkInitiated(id);
		time2 = System.currentTimeMillis();
	}

	@Override
	public void transferFailed(String reason) {
		if (stop)
			return;
		if (outStream != null) {
			try {
				outStream.close();
				outStream = null;
			} catch (IOException e) {
				Logger.log(e);
			}
		}
		this.errorCode = channel.getErrorCode();
		Logger.log(id + " notifying failure " + this.channel);
		this.channel = null;
		cl.chunkFailed(id, reason);
		cl = null;
	}

	@Override
	public boolean isFinished() {
		return (getLength() - getDownloaded()) == 0;
	}

	@Override
	public boolean isActive() {
		return !(this.channel == null);
	}

	@Override
	public String getId() {
		return id;
	}

	@Override
	public void download(SegmentListener cl) {
		this.cl = cl;
		channel = cl.createChannel(this);
		channel.open();
	}

	@Override
	public void setLength(long length) {
		this.length = length;
	}

	@Override
	public void setDownloaded(long downloaded) {
		this.downloaded = downloaded;
	}

	@Override
	public void setStartOffset(long offset) {
		this.startOffset = offset;
	}

	@Override
	public void stop() {
		stop = true;
		dispose();
	}

	@Override
	public SegmentListener getChunkListener() {
		return cl;
	}

	@Override
	public void dispose() {
		cl = null;
		if (channel != null) {
			channel.stop();
		}
		if (outStream != null) {
			try {
				outStream.close();
			} catch (IOException e) {
				Logger.log(e);
			}
		}
	}

	@Override
	public String toString() {
		return id;
	}

	@Override
	public void transferring() {
		if (stop)
			return;
		cl.chunkUpdated(id);
		calculateTransferRate();
		throttle();
		// applyThrottling();
	}

	@Override
	public AbstractChannel getChannel() {
		return channel;
	}

	@Override
	public void setId(String id) {
		this.id = id;
	}

	private void calculateTransferRate() {
		long now = System.currentTimeMillis();
		long timeDiff = now - time1;
		long bytesDiff = this.downloaded - bytesRead1;
		if (timeDiff > 1000) {
			transferRate = ((float) bytesDiff / timeDiff) * 1000;
			bytesRead1 = this.downloaded;
			time1 = now;
		}
	}

	private void throttle() {
		try {
			if (config.getSpeedLimit() < 1)
				return;
			if (cl.getActiveChunkCount() < 1)
				return;
			long maxBpms = (config.getSpeedLimit() * 1024) / (cl.getActiveChunkCount() * 1000);
			long now = System.currentTimeMillis();
			long timeSpentInReal = now - time2;
			if (timeSpentInReal > 0) {
				time2 = now;
				long bytesDownloaded = downloaded - bytesRead2;
				bytesRead2 = downloaded;
				long timeShouldRequired = bytesDownloaded / maxBpms;
				if (timeShouldRequired > timeSpentInReal) {
					long timeNeedToSleep = timeShouldRequired - timeSpentInReal;
					Thread.sleep(timeNeedToSleep);
				}
			}
		} catch (Exception e) {
			Logger.log(e);
		}
	}

	// private void applyThrottling() {
	// try {
	// long maxBps = config.getSpeedLimit() * 1024;
	// // System.out.println("Maxbps: "+maxBps);
	// if (maxBps < 1) {
	// return;
	// }
	// long now = System.currentTimeMillis();
	// long timeSpentInReal = now - time2;
	// if (timeSpentInReal > 0) {
	// // System.out.println("inside1");
	// long currentBpms = calculateBytesPerMilliSecond(bytesRead2, downloaded,
	// time2, now);
	// long expectedBpms = maxBps / (1000 * config.getMaxSegments());
	// if (expectedBpms > 0 && expectedBpms < currentBpms) {
	// // calculate the time that would have required if
	// // downloading
	// // was
	// // in expected speed
	// long timeShouldHaveSpent = currentBpms / expectedBpms;
	// time2 = now;
	// bytesRead2 = downloaded;
	// if (timeShouldHaveSpent > 0 && timeShouldHaveSpent > timeSpentInReal) {
	// // System.out.println("inside2: "+(timeShouldHaveSpent -
	// // timeSpentInReal));
	// Thread.sleep(timeShouldHaveSpent - timeSpentInReal);
	// }
	// }
	// }
	// } catch (Exception e) {
	// Logger.log(e);
	// }
	//
	// }

	// private long calculateBytesPerMilliSecond(long bytes1, long bytes2, long
	// oldtime, long newtime) {
	// long timeDiff = newtime - oldtime;
	// if (timeDiff > 0) {
	// long bytesPerMilliSec = (bytes2 - bytes1) / timeDiff;
	// return bytesPerMilliSec;
	// }
	// return -1L;
	// }

	@Override
	public final float getTransferRate() {
		try {
			if (isFinished())
				return 0;
		} catch (Exception e) {
		}
		return transferRate;
	}

	@Override
	public int getErrorCode() {
		return this.errorCode;
	}

	@Override
	public Object getTag() {
		return tag;
	}

	public void setTag(Object tag) {
		this.tag = tag;
	}

	public String getErrorMsg() {
		return null;
	}

	@Override
	public void resetStream() throws IOException {
		outStream.seek(0);
		outStream.setLength(0);
	}

	public void reopenStream() throws IOException {
		if (outStream != null) {
			return;
		}
		try {
			outStream = new RandomAccessFile(new File(folder, id), "rw");
			outStream.seek(downloaded);
			Logger.log("File opened " + id);
		} catch (IOException e) {
			Logger.log(e);
			if (outStream != null) {
				outStream.close();
			}
			throw new IOException(e);
		}
	}

	@Override
	public boolean promptCredential(String msg, boolean proxy) {
		return cl.promptCredential(msg, proxy);
	}

}
