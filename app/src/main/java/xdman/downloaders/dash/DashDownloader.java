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

package xdman.downloaders.dash;

import org.tinylog.Logger;
import xdman.Config;
import xdman.XDMConstants;
import xdman.downloaders.*;
import xdman.downloaders.http.HttpChannel;
import xdman.downloaders.metadata.DashMetadata;
import xdman.downloaders.metadata.HttpMetadata;
import xdman.mediaconversion.FFmpeg;
import xdman.mediaconversion.MediaConversionListener;
import xdman.mediaconversion.MediaFormats;
import xdman.util.FormatUtilities;
import xdman.util.IOUtils;
import xdman.util.StringUtils;
import xdman.util.XDMUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

@SuppressWarnings("ResultOfMethodCallIgnored")
public class DashDownloader extends Downloader implements SegmentListener, MediaConversionListener {

	private final DashMetadata metadata;
	private final long MIN_CHUNK_SIZE;
	private long len1, len2;
	private boolean assembleFinished;
	private long totalAssembled;
	private boolean assembling;

	public DashDownloader(String id, String folder, DashMetadata dm) {
		this.id = id;
		this.folder = new File(folder, id).getAbsolutePath();
		this.length = -1;
		this.MAX_COUNT = Config.getInstance().getMaxSegments();
		this.MIN_CHUNK_SIZE = Config.getInstance().getMinSegmentSize();
		this.metadata = dm;
		this.eta = "---";
	}

	public void start() {
		Logger.info("creating folder " + this.folder);
		new File(this.folder).mkdirs();
		this.lastDownloaded = this.downloaded;
		this.prevTime = System.currentTimeMillis();
		this.chunks = new ArrayList<>();
		try {
			Segment c1 = new SegmentImpl(this, this.folder);
			c1.setTag("T1");
			c1.setLength(-1);
			c1.setStartOffset(0);
			c1.setDownloaded(0);
			this.chunks.add(c1);

			Segment c2 = new SegmentImpl(this, this.folder);
			c2.setTag("T2");
			c2.setLength(-1);
			c2.setStartOffset(0);
			c2.setDownloaded(0);
			this.chunks.add(c2);
			c1.download(this);
		} catch (IOException e) {
			this.errorCode = XDMConstants.RESUME_FAILED;
			this.listener.downloadFailed(this.id);
			Logger.error(e);
		}
	}

	public AbstractChannel createChannel(Segment segment) {
		long len = "T1".equals(segment.getTag()) ? this.metadata.getLen1() : this.metadata.getLen2();
		String url = "T1".equals(segment.getTag()) ? this.metadata.getUrl() : this.metadata.getUrl2();
		return new HttpChannel(segment, url,
				"T1".equals(segment.getTag()) ? this.metadata.getHeaders() : this.metadata.getHeaders2(), len,
				this.isJavaClientRequired);
	}

	@Override
	public synchronized void chunkInitiated(String id) throws IOException {
		if (this.stopFlag)
			return;
		Segment c = getById(id);
		if (c == null) {
			Logger.warn(id + " is no longer valid chunk");
			return;
		}
		if (this.isFirstChunk(c)) {
			super.getLastModifiedDate(c);
			if (c.getTag().equals("T1")) {
				this.len1 = c.getLength();
			} else if (c.getTag().equals("T2")) {
				this.len2 = c.getLength();
			}
			this.saveState();
		}

		if (this.length < 1 && this.len1 > 0 && this.len2 > 0) {
			this.length = this.len1 + this.len2;
			Logger.info("length set - this.len1: " + this.len1 + " this.len2: " + this.len2);
			this.listener.downloadConfirmed(this.id);
		} else {
			Logger.info("this.len1: " + this.len1 + " this.len2: " + this.len2);
		}

		if ("T1".equals(c.getTag()) && this.len1 > 0) {
			this.createChunk();
		}
		if ("T2".equals(c.getTag()) && this.len2 > 0) {
			this.createChunk();
		}
	}

	private synchronized boolean onComplete(String id) throws IOException {
		if (this.allFinished()) {
			this.finished = true;
			this.updateStatus();
			try {
				this.assemble();
				if (!this.assembleFinished) {
					throw new IOException("Assemble failed");
				}
				Logger.info("********Download finished*********");
				this.updateStatus();
				this.listener.downloadFinished(this.id);
			} catch (Exception e) {
				if (!this.stopFlag) {
					Logger.error(e);
					this.errorCode = XDMConstants.ERR_ASM_FAILED;
					this.listener.downloadFailed(this.id);
				}
			}

			this.listener = null;
			return true;
		}
		Segment chunk = this.getById(id);
		Logger.info("Complete: " + chunk + " " + chunk.getDownloaded() + " " + chunk.getLength());
		Segment nextNeedyChunk = this.findNextNeedyChunk(chunk);
		if (nextNeedyChunk != null) {
			Logger.info("****************Needy chunk found!!!");
			Logger.info("Stopping: " + nextNeedyChunk);
			nextNeedyChunk.stop();
			this.chunks.remove(nextNeedyChunk);
			nextNeedyChunk.dispose();
			this.mergeChunk(chunk, nextNeedyChunk);
			this.createChunk();
			return false;
		}
		this.clearChannel(chunk);
		this.createChunk();
		return true;
	}

	@Override
	public synchronized boolean chunkComplete(String id) throws IOException {
		if (this.finished) {
			return true;
		}
		if (this.stopFlag) {
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
				this.saveState();
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
					} catch (IOException e) {
						Logger.error(e);
					}
				}
			}
		}
	}

	@Override
	public boolean shouldCleanup() {
		return this.assembleFinished;
	}

	private void assemble() throws Exception {
		File tf1 = new File(this.folder, "T1");
		File tf2 = new File(this.folder, "T2");
		File outFile = null;
		XDMUtils.mkdirs(this.getOutputFolder());
		try {
			this.assembleFinished = false;
			ArrayList<Segment> list1 = new ArrayList<>();
			ArrayList<Segment> list2 = new ArrayList<>();
			for (Segment sc : this.chunks) {
				if (sc.getTag().equals("T1")) {
					list1.add(sc);
				} else {
					list2.add(sc);
				}
			}

			this.assemblePart(tf1, list1);
			if (this.stopFlag) {
				return;
			}
			this.assemblePart(tf2, list2);
			if (this.stopFlag) {
				return;
			}

			List<String> inputFiles = new ArrayList<>();
			inputFiles.add(tf1.getAbsolutePath());
			inputFiles.add(tf2.getAbsolutePath());

			this.converting = true;
			outFile = new File(getOutputFolder(), UUID.randomUUID() + "_" + getOutputFileName(true));

			this.ffmpeg = new FFmpeg(inputFiles, outFile.getAbsolutePath(), this,
					MediaFormats.getSupportedFormats()[this.outputFormat], this.outputFormat == 0);
			int ret = this.ffmpeg.convert();
			Logger.info("FFmpeg exit code: " + ret);

			if (ret != 0) {
				throw new IOException("FFmpeg failed");
			} else {
				long length = outFile.length();
				if (length > 0) {
					this.length = length;
				}
				this.setLastModifiedDate(outFile);
			}

			File realFile = new File(getOutputFolder(), this.getOutputFileName(true));
			if (realFile.exists()) {
				realFile.delete();
			}
			outFile.renameTo(realFile);

			this.assembleFinished = true;
		} finally {
			if (!this.assembleFinished) {
				tf1.delete();
				tf2.delete();
				if (outFile != null) {
					outFile.delete();
				}
			}
		}
	}

	private void updateStatus() {
		try {
			long now = System.currentTimeMillis();
			if (this.converting) {
				this.progress = this.convertPrg;
			} else if (this.assembling) {
				long len = this.length > 0 ? this.length : this.downloaded;
				this.progress = (int) ((this.totalAssembled * 100) / len);
			} else {
				long downloaded2 = 0;
				if (this.length > 0) {
					if (this.segDet == null) {
						this.segDet = new SegmentDetails();
					}
					if (this.segDet.getCapacity() < this.chunks.size()) {
						this.segDet.extend(this.chunks.size() - this.segDet.getCapacity());
					}
					this.segDet.setChunkCount(this.chunks.size());
				}
				this.downloadSpeed = 0;
				for (int i = 0; i < this.chunks.size(); i++) {
					Segment s = this.chunks.get(i);
					downloaded2 += s.getDownloaded();
					if (this.length > 0) {
						long off = 0;
						if (s.getTag().equals("T2")) {
							off = this.len1;
						}
						SegmentInfo info = this.segDet.getChunkUpdates().get(i);
						info.setDownloaded(s.getDownloaded());
						info.setStart(s.getStartOffset() + off);
						info.setLength(s.getLength());
					}
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

			this.listener.downloadUpdated(this.id);
		} catch (Exception e) {
			Logger.error(e);
		}
	}

	private void assemblePart(File file, ArrayList<Segment> list) throws IOException {
		InputStream in = null;
		OutputStream out = null;
		this.totalAssembled = 0L;
		this.assembling = true;
		Logger.info("Combining " + file + " " + list.size());
		try {
			if (!this.stopFlag) {
				byte[] buf = new byte[8192 * 8];
				Logger.info("assembling... " + this.stopFlag);
				list.sort(new SegmentComparator());
				out = new FileOutputStream(file);
				for(int i = 0; i < list.size(); ++i) {
					Logger.info("chunk " + i + " " + this.stopFlag);
					Segment c = list.get(i);
					in = new FileInputStream(new File(this.folder, c.getId()));
					long rem = c.getLength();
					while(true) {
						int x = (int)(rem > 0L ? (Math.min(rem, buf.length)) : (long)buf.length);
						int r = in.read(buf, 0, x);
						if (this.stopFlag) {
							return;
						}
						if (r == -1) {
							if (this.length > 0L) {
								IOUtils.closeFlow(in);
								IOUtils.closeFlow(out);
								throw new IllegalArgumentException("Assemble EOF");
							}
							break;
						}
						out.write(buf, 0, r);
						if (this.stopFlag) {
							return;
						}
						if (this.length > 0L) {
							rem -= r;
							if (rem == 0L) {
								break;
							}
						}
						this.totalAssembled += r;
						long now = System.currentTimeMillis();
						if (now - this.lastUpdated > 1000L) {
							this.updateStatus();
							this.lastUpdated = now;
						}
					}
					IOUtils.closeFlow(in);
				}
				IOUtils.closeFlow(out);
			}
		} catch (Exception ex) {
			Logger.error(ex);
			throw new IOException(ex);
		} finally {
			IOUtils.closeFlow(in);
			IOUtils.closeFlow(out);
		}
	}

	private boolean isFirstChunk(Segment s) {
		int c = 0;
		for (Segment ss : this.chunks) {
			if (ss.getTag().equals(s.getTag())) {
				++c;
			}
		}
		return c == 1;
	}

	@Override
	public void stop() {
		this.stopFlag = true;
		this.saveState();
		for (Segment chunk : this.chunks) {
			chunk.stop();
		}
		if (this.ffmpeg != null) {
			this.ffmpeg.stop();
		}
		this.listener.downloadStopped(this.id);
		this.listener = null;
	}

	@Override
	public void resume() {
		try {
			this.stopFlag = false;
			Logger.info("Resuming");
			if (!restoreState()) {
				Logger.info("Starting from beginning");
				this.start();
				return;
			}
			Logger.info("Restore success");
			this.lastDownloaded = this.downloaded;
			this.prevTime = System.currentTimeMillis();

			if (this.allFinished()) {
				this.assembleAsync();
				return;
			}

			Segment c1 = null;
			for (Segment c : this.chunks) {
				if (!c.isFinished() && !c.isActive() && c.getTag().equals("T1")) {
					c1 = c;
					break;
				}
			}

			Segment c2 = null;
			for (Segment c : this.chunks) {
				if (!c.isFinished() && !c.isActive() && c.getTag().equals("T2")) {
					c2 = c;
					break;
				}
			}

			if (c1 != null) {
				try {
					c1.download(this);
				} catch (IOException e) {
					Logger.error(e);
				}
			}

			if (c2 != null) {
				try {
					if (c1 == null) {
						c2.download(this);
					}
				} catch (IOException e) {
					Logger.error(e);
				}
			}

			if (c1 == null && c2 == null) {
				Logger.warn("Internal error: no inactive/incomplete chunk found while resuming!");
			}
		} catch (Exception e) {
			Logger.error(e);
			this.errorCode = XDMConstants.RESUME_FAILED;
			this.listener.downloadFailed(this.id);
		}
	}

	@Override
	public int getType() {
		return XDMConstants.DASH;
	}

	@Override
	public boolean isFileNameChanged() {
		return false;
	}

	@Override
	public String getNewFile() {
		return null;
	}

	@Override
	public HttpMetadata getMetadata() {
		return this.metadata;
	}

	private void saveState() {
		if (this.chunks.size() >= 1) {
			StringBuilder sb = new StringBuilder();
			sb.append(this.length).append("\n");
			sb.append(this.downloaded).append("\n");
			sb.append(this.len1).append("\n");
			sb.append(this.len2).append("\n");
			sb.append(this.chunks.size()).append("\n");
			for (Segment seg : this.chunks) {
				sb.append(seg.getId()).append("\n");
				sb.append(seg.getLength()).append("\n");
				sb.append(seg.getStartOffset()).append("\n");
				sb.append(seg.getDownloaded()).append("\n");
				sb.append(seg.getTag()).append("\n");
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
	}

	private boolean restoreState() {
		BufferedReader br = null;
		this.chunks = new ArrayList<>();
		File file = new File(this.folder, "state.txt");
		if (!file.exists()) {
			file = this.getBackupFile(this.folder);
			if (file == null) {
				return false;
			}
		}
		try {
			br = new BufferedReader(new FileReader(file));
			this.length = Long.parseLong(br.readLine());
			this.downloaded = Long.parseLong(br.readLine());
			this.len1 = Long.parseLong(br.readLine());
			this.len2 = Long.parseLong(br.readLine());
			int chunkCount = Integer.parseInt(br.readLine());
			for (int i = 0; i < chunkCount; i++) {
				String cid = XDMUtils.readLineSafe(br);
				long len = Long.parseLong(br.readLine());
				long off = Long.parseLong(br.readLine());
				long dwn = Long.parseLong(br.readLine());
				String tag = XDMUtils.readLineSafe(br);// br.readLine();
				Segment seg = new SegmentImpl(this.folder, cid, off, len, dwn);
				seg.setTag(tag);
				Logger.info("id: " + seg.getId() + "\nlength: " + seg.getLength() + "\noffset: " + seg.getStartOffset()
						+ "\ndownload: " + seg.getDownloaded());
				this.chunks.add(seg);
			}
			this.lastModified = XDMUtils.readLineSafe(br);
			return true;
		} catch (Exception e) {
			Logger.error(e, "Failed to load saved state");
		} finally {
			IOUtils.closeFlow(br);
		}
		return false;
	}

	private void assembleAsync() {
		(new Thread(() -> {
			this.finished = true;
			try {
				this.assemble();
				if (!this.assembleFinished) {
					throw new IOException("Assemble not finished successfully");
				}
				Logger.info("********Download finished*********");
				this.updateStatus();
				this.cleanup();
				this.listener.downloadFinished(this.id);
			} catch (Exception var2) {
				if (!this.stopFlag) {
					Logger.error(var2);
					this.errorCode = 132;
					this.listener.downloadFailed(this.id);
				}
			}
		})).start();
	}

	private synchronized void createChunk() throws IOException {
		if (!this.stopFlag) {
			int activeCount = this.getActiveChunkCount();
			Logger.info("active count:" + activeCount);
			if (activeCount == this.MAX_COUNT) {
				Logger.info("Maximum chunk created");
			} else {
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
		}
	}

	private Segment findMaxChunk() {
		if (this.stopFlag) {
			return null;
		} else {
			long size = -1L;
			String id = null;
			for (Segment segment : this.chunks) {
				if (segment.isActive()) {
					long rem = segment.getLength() - segment.getDownloaded();
					if (rem > size) {
						id = segment.getId();
						size = rem;
					}
				}
			}
			if (size < this.MIN_CHUNK_SIZE) {
				return null;
			} else {
				return this.getById(id);
			}
		}
	}

	private void mergeChunk(Segment c1, Segment c2) {
		c1.setLength(c1.getLength() + c2.getLength());
	}

	private Segment splitChunk(Segment c) throws IOException {
		if (c != null && !this.stopFlag) {
			long rem = c.getLength() - c.getDownloaded();
			long offset = c.getStartOffset() + c.getLength() - rem / 2L;
			long len = rem / 2L;
			long var10000 = c.getLength();
			Logger.info("Changing length from: " + var10000 + " to " + (c.getLength() - rem / 2L));
			c.setLength(c.getLength() - rem / 2L);
			Segment c2 = new SegmentImpl(this, this.folder);
			c2.setTag(c.getTag());
			c2.setLength(len);
			c2.setStartOffset(offset);
			return c2;
		} else {
			return null;
		}
	}

	private Segment findNextNeedyChunk(Segment chunk) {
		if (this.stopFlag) {
			return null;
		} else {
			long offset = chunk.getStartOffset() + chunk.getLength();
			Iterator<Segment> iterator = this.chunks.iterator();
			Segment segment;
			do {
				if (!iterator.hasNext()) {
					return null;
				}
				segment = iterator.next();
			} while(segment.getDownloaded() != 0L || segment.isFinished() || segment.getStartOffset() != offset || !chunk.getTag().equals(segment.getTag()));
			return segment;
		}
	}

	@Override
	public void progress(int progress) {
		this.convertPrg = progress;
		long now = System.currentTimeMillis();
		if (now - this.lastUpdated > 1000L) {
			this.updateStatus();
			this.lastUpdated = now;
		}
	}

}
