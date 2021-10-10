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
import java.io.RandomAccessFile;
import java.util.UUID;

import org.tinylog.Logger;

import xdman.Config;
import xdman.util.IOUtils;

@SuppressWarnings("unused")
public class SegmentImpl implements Segment {

	private volatile long length, startOffset, downloaded;
	private RandomAccessFile outStream;
	private String id;
	volatile private SegmentListener cl;
	private volatile AbstractChannel channel;

	private long bytesRead1, bytesRead2, time1, time2;
	private float transferRate;
	private final Config config;
	private volatile boolean stop;
	private int errorCode;
	private Object tag;
	private final String folder;

	public SegmentImpl(SegmentListener cl, String folder) throws IOException {
		id = UUID.randomUUID().toString();
		this.cl = cl;
		this.folder = folder;
		this.time1 = System.currentTimeMillis();
		this.time2 = time1;
		this.config = Config.getInstance();
		outStream = new RandomAccessFile(new File(folder, id), "rw");// new
		Logger.info("File opened " + id);
	}

	public SegmentImpl(String folder, String id, long off, long len, long dwn) throws IOException {
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
			Logger.error("File opened " + id);
		} catch (IOException e) {
			Logger.error(e);
			IOUtils.closeFlow(this.outStream);
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
			IOUtils.closeFlow(this.outStream);
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
			IOUtils.closeFlow(this.outStream);
			this.outStream = null;
		}
		this.errorCode = channel.getErrorCode();
		Logger.warn(id + " notifying failure " + this.channel);
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
			IOUtils.closeFlow(this.outStream);
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
			long maxBpms = (config.getSpeedLimit() * 1024L) / (cl.getActiveChunkCount() * 1000L);
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
			Logger.error(e);
		}
	}

	@Override
	public final float getTransferRate() {
		try {
			if (isFinished())
				return 0;
		} catch (Exception e) {
			Logger.error(e);
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
			Logger.info("File opened " + id);
		} catch (IOException e) {
			Logger.error(e);
			IOUtils.closeFlow(this.outStream);
			throw new IOException(e);
		}
	}

	@Override
	public boolean promptCredential(String msg, boolean proxy) {
		return cl.promptCredential(msg, proxy);
	}

}
