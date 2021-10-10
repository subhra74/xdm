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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.tinylog.Logger;

import xdman.Config;
import xdman.XDMConstants;
import xdman.downloaders.http.HttpChannel;
import xdman.downloaders.metadata.DashMetadata;
import xdman.mediaconversion.FFmpeg;
import xdman.mediaconversion.MediaConversionListener;
import xdman.mediaconversion.MediaFormats;
import xdman.util.FormatUtilities;
import xdman.util.IOUtils;
import xdman.util.StringUtils;
import xdman.util.XDMUtils;

@SuppressWarnings("ResultOfMethodCallIgnored")
public abstract class SegmentDownloader extends Downloader implements SegmentListener, MediaConversionListener {

	private final int MIN_CHUNK_SIZE;
	private boolean init = false;
	private boolean assembleFinished;
	private long totalAssembled;

	protected SegmentDownloader(String id, String folder) {
		this.id = id;
		this.folder = new File(folder, id).getAbsolutePath();
		this.length = -1;
		this.MAX_COUNT = Config.getInstance().getMaxSegments();
		this.MIN_CHUNK_SIZE = Config.getInstance().getMinSegmentSize();
		this.lastDownloaded = this.downloaded;
		this.prevTime = System.currentTimeMillis();
		this.eta = "---";
	}

	public void start() {
		Logger.info("creating folder " + this.folder);
		new File(this.folder).mkdirs();
		this.chunks = new ArrayList<>();
		try {
			Segment c1 = new SegmentImpl(this, this.folder);
			if (getMetadata() instanceof DashMetadata) {
				c1.setTag("T1");
			}
			c1.setLength(-1);
			c1.setStartOffset(0);
			c1.setDownloaded(0);
			this.chunks.add(c1);
			c1.download(this);
		} catch (IOException e) {
			this.errorCode = XDMConstants.RESUME_FAILED;
			this.listener.downloadFailed(this.id);
		}

	}

	@Override
	public void resume() {
		try {
			this.stopFlag = false;
			Logger.info("Resuming");
			if (!restoreState()) {
				Logger.info("Starting from beginning");
				start();
				return;
			}
			this.lastDownloaded = this.downloaded;
			this.prevTime = System.currentTimeMillis();
			Logger.info("Restore success");
			this.init = true;
			Segment c1 = findInactiveChunk();
			if (c1 != null) {
				try {
					c1.download(this);
				} catch (Exception e) {
					Logger.error(e);
					if (!this.stopFlag) {
						this.errorCode = XDMConstants.RESUME_FAILED;
						this.listener.downloadFailed(this.id);
					}
				}
			} else if (allFinished()) {
				this.assembleAsync();
			} else {
				Logger.warn("Internal error: no inactive/incomplete chunk found while resuming!");
			}
		} catch (Exception e) {
			Logger.error(e);
			this.errorCode = XDMConstants.RESUME_FAILED;
			this.listener.downloadFailed(this.id);
		}
	}

	private synchronized void createChunk() throws IOException {
		if (this.stopFlag)
			return;
		int activeCount = getActiveChunkCount();
		Logger.info("active count:" + activeCount);
		if (activeCount == this.MAX_COUNT) {
			return;
		}

		int rem = this.MAX_COUNT - activeCount;

		rem -= this.retryFailedChunks(rem);

		if (rem > 0) {
			Segment c1 = this.findMaxChunk();
			Segment c = this.splitChunk(c1);
			if (c != null) {
				Logger.info("creating chunk " + c);
				this.chunks.add(c);
				c.download(this);
			}
		}
	}

	private Segment findMaxChunk() {
		if (this.stopFlag)
			return null;
		long size = -1;
		String id = null;
		for (Segment c : this.chunks) {
			if (c.isActive()) {
				long rem = c.getLength() - c.getDownloaded();
				if (rem > size) {
					id = c.getId();
					size = rem;
				}
			}
		}
		if (size < this.MIN_CHUNK_SIZE)
			return null;
		return getById(id);
	}

	private void mergeChunk(Segment c1, Segment c2) {
		c1.setLength(c1.getLength() + c2.getLength());
	}

	private Segment splitChunk(Segment c) throws IOException {
		if (c == null || this.stopFlag)
			return null;
		long rem = c.getLength() - c.getDownloaded();
		long offset = c.getStartOffset() + c.getLength() - rem / 2;
		long len = rem / 2;
		Logger.info("Changing length from: " + c.getLength() + " to " + (c.getLength() - rem / 2));
		c.setLength(c.getLength() - rem / 2);
		Segment c2 = new SegmentImpl(this, this.folder);
		if (this.getMetadata() instanceof DashMetadata) {
			c2.setTag("T1");
		}
		c2.setLength(len);
		c2.setStartOffset(offset);
		return c2;
	}

	private Segment findNextNeedyChunk(Segment chunk) {
		if (this.stopFlag)
			return null;
		long offset = chunk.getStartOffset() + chunk.getLength();
		for (Segment c : this.chunks) {
			if (c.getDownloaded() == 0) {
				if (!c.isFinished()) {
					if (c.getStartOffset() == offset) {
						return c;
					}
				}
			}
		}
		return null;
	}

	private synchronized boolean onComplete(String id) throws IOException {
		if (allFinished() || this.length < 0) {
			this.finished = true;
			this.updateStatus();
			try {
				this.assemble();
				if (!this.assembleFinished) {
					throw new IOException("Assemble failed");
				}
				Logger.info("********Download finished*********");
				updateStatus();
				this.listener.downloadFinished(this.id);
			} catch (Exception e) {
				Logger.error(e);
				if (!this.stopFlag) {
					this.errorCode = XDMConstants.ERR_ASM_FAILED;
					this.listener.downloadFailed(this.id);
				}
			}
			this.listener = null;
			return true;
		}
		Segment chunk = getById(id);
		Logger.info("Complete: " + chunk + " " + chunk.getDownloaded() + " " + chunk.getLength());
		Segment nextNeedyChunk = findNextNeedyChunk(chunk);
		if (nextNeedyChunk != null) {
			Logger.info("****************Needy chunk found!!!");
			Logger.info("Stopping: " + nextNeedyChunk);
			nextNeedyChunk.stop();
			this.chunks.remove(nextNeedyChunk);
			nextNeedyChunk.dispose();
			mergeChunk(chunk, nextNeedyChunk);
			this.createChunk();
			return false;
		}
		this.clearChannel(chunk);
		this.createChunk();
		return true;
	}

	@Override
	public synchronized void chunkInitiated(String id) throws IOException {
		if (this.stopFlag)
			return;
		if (!this.init) {
			Segment c = getById(id);
			this.length = c.getLength();
			this.init = true;
			Logger.info("size: " + this.length);
			if (c.getChannel() instanceof HttpChannel) {
				super.getLastModifiedDate(c);
			}
			this.saveState();
			this.chunkConfirmed(c);
			this.listener.downloadConfirmed(this.id);
		}
		if (this.length > 0) {
			this.createChunk();
		}
	}

	@Override
	public synchronized boolean chunkComplete(String id) throws IOException {
		if (this.finished) {
			return true;
		}

		if (stopFlag) {
			return true;
		}

		this.saveState();

		return this.onComplete(id);
	}

	@Override
	public void chunkUpdated(String id) {
		if (this.stopFlag)
			return;
		long now = System.currentTimeMillis();
		if (now - this.lastSaved > 5000) {
			synchronized (this) {
				saveState();
			}
			this.lastSaved = now;
		}
		if (now - this.lastUpdated > 1000) {
			this.updateStatus();
			this.lastUpdated = now;
			synchronized (this) {
				int activeCount = this.getActiveChunkCount();
				if (activeCount < this.MAX_COUNT) {
					int rem = this.MAX_COUNT - activeCount;
					try {
						this.retryFailedChunks(rem);
					} catch (Exception e) {
						Logger.error(e);
					}
				}
			}
		}
	}

	private void assemble() throws IOException {
		InputStream in = null;
		OutputStream out = null;
		this.totalAssembled = 0L;
		this.assembling = true;
		this.assembleFinished = false;
		String outFileFinal = this.getOutputFileName(true);
		String outFileName = (this.outputFormat == 0 ? UUID.randomUUID() + "_" + outFileFinal
				: UUID.randomUUID().toString());
		String outputFolder = (this.outputFormat == 0 ? getOutputFolder() : this.folder);
		XDMUtils.mkdirs(getOutputFolder());
		File outFile = new File(outputFolder, outFileName);
		File ffOutFile = null;
		try {
			if (this.stopFlag)
				return;
			byte[] buf = new byte[1024 * 1024];
			Logger.info("assembling... ");
			this.chunks.sort(new SegmentComparator());
			out = new FileOutputStream(outFile);
			for (int i = 0; i < chunks.size(); i++) {
				Logger.info("chunk " + i + " " + this.stopFlag);
				Segment c = this.chunks.get(i);
				in = new FileInputStream(new File(folder, c.getId()));
				long rem = c.getLength();
				while (true) {
					int x = (int) (rem > 0 ? (rem > buf.length ? buf.length : rem) : buf.length);
					int r = in.read(buf, 0, x);
					if (this.stopFlag) {
						return;
					}

					if (r == -1) {
						if (this.length > 0) {
							throw new IllegalArgumentException("Assemble EOF");
						} else {
							break;
						}
					}

					out.write(buf, 0, r);
					if (this.stopFlag) {
						return;
					}
					if (this.length > 0) {
						rem -= r;
						if (rem == 0)
							break;
					}
					this.totalAssembled += r;
					long now = System.currentTimeMillis();
					if (now - this.lastUpdated > 1000) {
						this.updateStatus();
						this.lastUpdated = now;
					}
				}
				IOUtils.closeFlow(in);
			}
			IOUtils.closeFlow(out);
			this.setLastModifiedDate(outFile);
			this.updateStatus();
			if (this.outputFormat != 0) {
				XDMUtils.mkdirs(getOutputFolder());
				this.converting = true;
				ffOutFile = new File(getOutputFolder(), UUID.randomUUID() + "_" + getOutputFileName(true));

				this.ffmpeg = new FFmpeg(List.of(outFile.getAbsolutePath()), ffOutFile.getAbsolutePath(), this,
						MediaFormats.getSupportedFormats()[outputFormat], outputFormat == 0);
				int ret = this.ffmpeg.convert();
				Logger.info("FFmpeg exit code: " + ret);

				if (ret != 0) {
					throw new IOException("FFmpeg failed");
				} else {
					long length = ffOutFile.length();
					if (length > 0) {
						this.length = length;
					}
				}
			}
			File realFile = new File(getOutputFolder(), getOutputFileName(true));
			if (realFile.exists()) {
				realFile.delete();
			}

			if (ffOutFile != null) {
				outFile.delete();
				outFile = ffOutFile;
			}
			outFile.renameTo(realFile);
			setLastModifiedDate(outFile);

			this.assembleFinished = true;
		} catch (Exception e) {
			Logger.error(e);
			throw new IOException(e);
		} finally {
			IOUtils.closeFlow(in);
			IOUtils.closeFlow(out);

			if (!this.assembleFinished) {
				outFile.delete();
				if (ffOutFile != null) {
					ffOutFile.delete();
				}
			}
		}
	}

	@Override
	public abstract AbstractChannel createChannel(Segment segment);

	public void stop() {
		this.stopFlag = true;
		this.saveState();
		for (Segment chunk : this.chunks) {
			chunk.stop();
		}

		if (this.ffmpeg != null) {
			this.ffmpeg.stop();
		}
		this.listener.downloadStopped(id);
		this.listener = null;
	}

	private void saveState() {
		if (this.length < 0)
			return;
		StringBuilder sb = new StringBuilder();
		sb.append(this.length).append("\n");
		sb.append(this.downloaded).append("\n");
		sb.append(this.chunks.size()).append("\n");
		for (Segment seg : this.chunks) {
			sb.append(seg.getId()).append("\n");
			sb.append(seg.getLength()).append("\n");
			sb.append(seg.getStartOffset()).append("\n");
			sb.append(seg.getDownloaded()).append("\n");
		}
		if (!StringUtils.isNullOrEmptyOrBlank(this.lastModified)) {
			sb.append(this.lastModified).append("\n");
		}
		try {
			File tmp = new File(this.folder, System.currentTimeMillis() + ".tmp");
			File out = new File(this.folder, "state.txt");
			FileOutputStream fs = new FileOutputStream(tmp);
			fs.write(sb.toString().getBytes());
			IOUtils.closeFlow(fs);
			out.delete();
			tmp.renameTo(out);
		} catch (Exception e) {
			Logger.error(e);
		}
	}

	private boolean restoreState() {
		BufferedReader br = null;
		this.chunks = new ArrayList<>();
		File file = new File(this.folder, "state.txt");
		if (!file.exists()) {
			file = getBackupFile(this.folder);
			if (file == null) {
				return false;
			}
		}
		try {
			br = new BufferedReader(new FileReader(file));
			this.length = Long.parseLong(br.readLine());
			this.downloaded = Long.parseLong(br.readLine());
			int chunkCount = Integer.parseInt(br.readLine());
			for (int i = 0; i < chunkCount; i++) {
				String cid = XDMUtils.readLineSafe(br);
				long len = Long.parseLong(XDMUtils.readLineSafe(br));
				long off = Long.parseLong(XDMUtils.readLineSafe(br));
				long dwn = Long.parseLong(XDMUtils.readLineSafe(br));
				Segment seg = new SegmentImpl(this.folder, cid, off, len, dwn);
				if (getMetadata() instanceof DashMetadata) {
					seg.setTag("T1");
				}

				Logger.info("id: " + seg.getId() + "\nlength: " + seg.getLength() + "\noffset: " + seg.getStartOffset()
						+ "\ndownload: " + seg.getDownloaded());
				this.chunks.add(seg);
			}
			this.lastModified = br.readLine();
			return true;
		} catch (Exception e) {
			Logger.error(e, "Failed to load saved state");
		} finally {
			IOUtils.closeFlow(br);
		}
		return false;
	}

	protected abstract void chunkConfirmed(Segment c);

	public boolean shouldCleanup() {
		return this.assembleFinished;
	}

	private void updateStatus() {
		try {
			long now = System.currentTimeMillis();
			if (this.converting) {
				this.progress = this.convertPrg;
			} else if (this.assembling) {
				long len = this.length > 0 ? this.length : this.downloaded;
				this.progress = (int) ((totalAssembled * 100) / len);
			} else {
				long downloaded2 = 0;
				if (this.segDet == null) {
					this.segDet = new SegmentDetails();
				}
				if (this.segDet.getCapacity() < this.chunks.size()) {
					this.segDet.extend(this.chunks.size() - this.segDet.getCapacity());
				}
				this.segDet.setChunkCount(this.chunks.size());
				this.downloadSpeed = 0;
				for (int i = 0; i < this.chunks.size(); i++) {
					Segment s = this.chunks.get(i);
					downloaded2 += s.getDownloaded();
					SegmentInfo info = this.segDet.getChunkUpdates().get(i);
					info.setDownloaded(s.getDownloaded());
					info.setStart(s.getStartOffset());
					info.setLength(s.getLength());
					this.downloadSpeed += s.getTransferRate();
				}
				this.downloaded = downloaded2;
				if (this.length > 0) {
					this.progress = (int) ((this.downloaded * 100) / this.length);
					long diff = this.downloaded - this.lastDownloaded;
					long timeSpend = now - this.prevTime;
					if (timeSpend > 0) {
						float rate = ((float) diff / timeSpend) * 1000;
						this.eta = FormatUtilities.getETA(this.length - this.downloaded, rate);
						if (this.eta == null) {
							this.eta = "---";
						}
						this.lastDownloaded = this.downloaded;
						this.prevTime = now;
					}
				}
			}
			this.listener.downloadUpdated(id);
		} catch (Exception e) {
			Logger.error(e);
		}
	}

	private void assembleAsync() {
		new Thread(() -> {
			this.finished = true;
			try {
				this.assemble();
				if (!this.assembleFinished) {
					throw new IOException("Assemble not finished successfully");
				}
				Logger.info("********Download finished*********");
				this.updateStatus();
				this.cleanup();
				this.listener.downloadFinished(id);
			} catch (Exception e) {
				Logger.error(e);
				if (!this.stopFlag) {
					this.errorCode = XDMConstants.ERR_ASM_FAILED;
					this.listener.downloadFailed(this.id);
				}
			}
		}).start();
	}

	@Override
	public void progress(int progress) {
		this.convertPrg = progress;
		long now = System.currentTimeMillis();
		if (now - this.lastUpdated > 1000) {
			this.updateStatus();
			this.lastUpdated = now;
		}
	}

}
