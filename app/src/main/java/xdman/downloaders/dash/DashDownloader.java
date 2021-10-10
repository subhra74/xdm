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
import java.util.List;
import java.util.UUID;

@SuppressWarnings("ResultOfMethodCallIgnored")
public class DashDownloader extends Downloader implements SegmentListener, MediaConversionListener {

	private final DashMetadata metadata;
	private final long MIN_CHUNK_SIZE;
	private long len1, len2;
	private boolean assembleFinished;

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
		Logger.info("creating folder " + folder);
		new File(folder).mkdirs();
		this.lastDownloaded = downloaded;
		this.prevTime = System.currentTimeMillis();
		chunks = new ArrayList<>();
		try {
			Segment c1 = new SegmentImpl(this, folder);
			c1.setTag("T1");
			c1.setLength(-1);
			c1.setStartOffset(0);
			c1.setDownloaded(0);
			chunks.add(c1);

			Segment c2 = new SegmentImpl(this, folder);
			c2.setTag("T2");
			c2.setLength(-1);
			c2.setStartOffset(0);
			c2.setDownloaded(0);
			chunks.add(c2);

			c1.download(this);
		} catch (IOException e) {
			this.errorCode = XDMConstants.RESUME_FAILED;
			this.listener.downloadFailed(id);
			Logger.error(e);
		}
	}

	public AbstractChannel createChannel(Segment segment) {
		long len = "T1".equals(segment.getTag()) ? metadata.getLen1() : metadata.getLen2();
		String url = "T1".equals(segment.getTag()) ? metadata.getUrl() : metadata.getUrl2();
		return new HttpChannel(segment, url,
				"T1".equals(segment.getTag()) ? metadata.getHeaders() : metadata.getHeaders2(), len,
				isJavaClientRequired);
	}

	@Override
	public synchronized void chunkInitiated(String id) throws IOException {
		if (stopFlag)
			return;
		Segment c = getById(id);
		if (c == null) {
			Logger.warn(id + " is no longer valid chunk");
			return;
		}
		if (isFirstChunk(c)) {
			super.getLastModifiedDate(c);
			if (c.getTag().equals("T1")) {
				this.len1 = c.getLength();
			} else if (c.getTag().equals("T2")) {
				this.len2 = c.getLength();
			}
			saveState();
		}

		if (this.length < 1 && this.len1 > 0 && this.len2 > 0) {
			this.length = len1 + len2;
			Logger.info("length set - this.len1: " + this.len1 + " this.len2: " + this.len2);
			listener.downloadConfirmed(this.id);
		} else {
			Logger.info("this.len1: " + this.len1 + " this.len2: " + this.len2);
		}

		if ("T1".equals(c.getTag()) && this.len1 > 0) {
			createChunk();
		}
		if ("T2".equals(c.getTag()) && this.len2 > 0) {
			createChunk();
		}
	}

	private synchronized boolean onComplete(String id) throws IOException {
		if (allFinished()) {
			finished = true;
			updateStatus();
			try {
				assemble();
				if (!assembleFinished) {
					throw new IOException("Assemble failed");
				}
				Logger.info("********Download finished*********");
				updateStatus();
				listener.downloadFinished(this.id);
			} catch (Exception e) {
				if (!stopFlag) {
					Logger.error(e);
					this.errorCode = XDMConstants.ERR_ASM_FAILED;
					listener.downloadFailed(this.id);
				}
			}

			listener = null;
			return true;
		}
		Segment chunk = getById(id);
		Logger.info("Complete: " + chunk + " " + chunk.getDownloaded() + " " + chunk.getLength());
		Segment nextNeedyChunk = findNextNeedyChunk(chunk);
		if (nextNeedyChunk != null) {
			Logger.info("****************Needy chunk found!!!");
			Logger.info("Stopping: " + nextNeedyChunk);
			nextNeedyChunk.stop();
			chunks.remove(nextNeedyChunk);
			nextNeedyChunk.dispose();
			mergeChunk(chunk, nextNeedyChunk);
			createChunk();
			return false;
		}
		clearChannel(chunk);
		createChunk();
		return true;
	}

	@Override
	public synchronized boolean chunkComplete(String id) throws IOException {
		if (finished) {
			return true;
		}

		if (stopFlag) {
			return true;
		}

		saveState();

		return onComplete(id);
	}

	@Override
	public void chunkUpdated(String id) {
		if (stopFlag)
			return;
		long now = System.currentTimeMillis();
		if (now - lastSaved > 5000) {
			synchronized (this) {
				saveState();
			}
			lastSaved = now;
		}
		if (now - lastUpdated > 1000) {
			updateStatus();
			lastUpdated = now;
			synchronized (this) {
				int activeCount = getActiveChunkCount();
				if (activeCount < MAX_COUNT) {
					int rem = MAX_COUNT - activeCount;
					try {
						retryFailedChunks(rem);
					} catch (IOException e) {
						Logger.error(e);
					}
				}
			}
		}
	}

	@Override
	public boolean shouldCleanup() {
		return assembleFinished;
	}

	private void assemble() throws Exception {
		File tf1 = new File(folder, "T1");
		File tf2 = new File(folder, "T2");
		File outFile = null;
		XDMUtils.mkdirs(getOutputFolder());
		try {
			assembleFinished = false;
			ArrayList<Segment> list1 = new ArrayList<>();
			ArrayList<Segment> list2 = new ArrayList<>();
			for (Segment sc : chunks) {
				if (sc.getTag().equals("T1")) {
					list1.add(sc);
				} else {
					list2.add(sc);
				}
			}

			assemblePart(tf1, list1);
			if (stopFlag) {
				return;
			}
			assemblePart(tf2, list2);
			if (stopFlag) {
				return;
			}

			List<String> inputFiles = new ArrayList<>();
			inputFiles.add(tf1.getAbsolutePath());
			inputFiles.add(tf2.getAbsolutePath());

			this.converting = true;
			outFile = new File(getOutputFolder(), UUID.randomUUID() + "_" + getOutputFileName(true));

			this.ffmpeg = new FFmpeg(inputFiles, outFile.getAbsolutePath(), this,
					MediaFormats.getSupportedFormats()[outputFormat], outputFormat == 0);
			int ret = ffmpeg.convert();
			Logger.info("FFmpeg exit code: " + ret);

			if (ret != 0) {
				throw new IOException("FFmpeg failed");
			} else {
				long length = outFile.length();
				if (length > 0) {
					this.length = length;
				}
				setLastModifiedDate(outFile);
			}

			File realFile = new File(getOutputFolder(), getOutputFileName(true));
			if (realFile.exists()) {
				realFile.delete();
			}
			outFile.renameTo(realFile);

			assembleFinished = true;
		} finally {
			if (!assembleFinished) {
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
			if (converting) {
				progress = this.convertPrg;
			} else if (assembling) {
				long len = length > 0 ? length : downloaded;
				progress = (int) ((totalAssembled * 100) / len);
			} else {
				long downloaded2 = 0;
				if (length > 0) {
					if (segDet == null) {
						segDet = new SegmentDetails();
					}
					if (segDet.getCapacity() < chunks.size()) {
						segDet.extend(chunks.size() - segDet.getCapacity());
					}
					segDet.setChunkCount(chunks.size());
				}
				downloadSpeed = 0;
				for (int i = 0; i < chunks.size(); i++) {
					Segment s = chunks.get(i);
					downloaded2 += s.getDownloaded();
					if (length > 0) {
						long off = 0;
						if (s.getTag().equals("T2")) {
							off = len1;
						}
						SegmentInfo info = segDet.getChunkUpdates().get(i);
						info.setDownloaded(s.getDownloaded());
						info.setStart(s.getStartOffset() + off);
						info.setLength(s.getLength());
					}
					downloadSpeed += s.getTransferRate();
				}
				this.downloaded = downloaded2;
				if (length > 0) {
					progress = (int) ((downloaded * 100) / length);
					long diff = downloaded - lastDownloaded;
					long timeSpend = now - prevTime;
					if (timeSpend > 0) {
						float rate = ((float) diff / timeSpend) * 1000;
						this.eta = FormatUtilities.getETA(length - downloaded, rate);
						if (this.eta == null) {
							this.eta = "---";
						}
						lastDownloaded = downloaded;
						prevTime = now;
					}
				}
			}

			listener.downloadUpdated(id);
		} catch (Exception e) {
			Logger.error(e);
		}

	}

	long totalAssembled;
	boolean assembling;

	private void assemblePart(File file, ArrayList<Segment> list) throws IOException {
		InputStream in = null;
		OutputStream out = null;
		totalAssembled = 0L;
		assembling = true;
		Logger.info("Combining " + file + " " + list.size());
		try {
			if (stopFlag)
				return;
			byte[] buf = new byte[8192 * 8];
			Logger.info("assembling... " + stopFlag);
			list.sort(new SegmentComparator());
			out = new FileOutputStream(file);
			for (int i = 0; i < list.size(); i++) {
				Logger.info("chunk " + i + " " + stopFlag);
				Segment c = list.get(i);
				in = new FileInputStream(new File(folder, c.getId()));
				long rem = c.getLength();
				while (true) {
					int x = (int) (rem > 0 ? (rem > buf.length ? buf.length : rem) : buf.length);
					int r = in.read(buf, 0, x);
					if (stopFlag) {
						return;
					}

					if (r == -1) {
						if (length > 0) {
							IOUtils.closeFlow(in);
							IOUtils.closeFlow(out);
							throw new IllegalArgumentException("Assemble EOF");
						} else {
							break;
						}
					}

					out.write(buf, 0, r);
					if (stopFlag) {
						return;
					}
					if (length > 0) {
						rem -= r;
						if (rem == 0)
							break;
					}
					totalAssembled += r;
					long now = System.currentTimeMillis();
					if (now - lastUpdated > 1000) {
						updateStatus();
						lastUpdated = now;
					}
				}
				IOUtils.closeFlow(in);
			}
			IOUtils.closeFlow(out);
		} catch (Exception e) {
			Logger.error(e);
			throw new IOException(e);
		} finally {
			IOUtils.closeFlow(in);
			IOUtils.closeFlow(out);
		}
	}

	private boolean isFirstChunk(Segment s) {
		int c = 0;
		for (Segment ss : chunks) {
			if (ss.getTag().equals(s.getTag())) {
				c++;
			}
		}
		return c == 1;
	}

	@Override
	public void stop() {
		stopFlag = true;
		saveState();
		for (Segment chunk : chunks) {
			chunk.stop();
		}
		if (this.ffmpeg != null) {
			this.ffmpeg.stop();
		}
		listener.downloadStopped(id);
		listener = null;
	}

	@Override
	public void resume() {
		try {
			stopFlag = false;
			Logger.info("Resuming");
			if (!restoreState()) {
				Logger.info("Starting from beginning");
				start();
				return;
			}
			Logger.info("Restore success");
			this.lastDownloaded = downloaded;
			this.prevTime = System.currentTimeMillis();

			if (allFinished()) {
				assembleAsync();
				return;
			}

			Segment c1 = null;
			for (Segment c : chunks) {
				if (c.isFinished() || c.isActive())
					continue;
				if (c.getTag().equals("T1")) {
					c1 = c;
					break;
				}
			}

			Segment c2 = null;
			for (Segment c : chunks) {
				if (c.isFinished() || c.isActive())
					continue;
				if (c.getTag().equals("T2")) {
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
			listener.downloadFailed(this.id);
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
		return metadata;
	}

	private void saveState() {
		if (chunks.size() < 1)
			return;
		StringBuilder sb = new StringBuilder();
		sb.append(this.length).append("\n");
		sb.append(downloaded).append("\n");
		sb.append(this.len1).append("\n");
		sb.append(this.len2).append("\n");
		sb.append(chunks.size()).append("\n");
		for (Segment seg : chunks) {
			sb.append(seg.getId()).append("\n");
			sb.append(seg.getLength()).append("\n");
			sb.append(seg.getStartOffset()).append("\n");
			sb.append(seg.getDownloaded()).append("\n");
			sb.append(seg.getTag()).append("\n");
		}
		if (!StringUtils.isNullOrEmptyOrBlank(lastModified)) {
			sb.append(this.lastModified).append("\n");
		}
		try {
			File tmp = new File(folder, System.currentTimeMillis() + ".tmp");
			File out = new File(folder, "state.txt");
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
		chunks = new ArrayList<>();
		File file = new File(folder, "state.txt");
		if (!file.exists()) {
			file = getBackupFile(folder);
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
				Segment seg = new SegmentImpl(folder, cid, off, len, dwn);
				seg.setTag(tag);
				Logger.info("id: " + seg.getId() + "\nlength: " + seg.getLength() + "\noffset: " + seg.getStartOffset()
						+ "\ndownload: " + seg.getDownloaded());
				chunks.add(seg);
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
		new Thread(() -> {
			finished = true;
			try {
				assemble();
				if (!assembleFinished) {
					throw new IOException("Assemble not finished successfully");
				}
				Logger.info("********Download finished*********");
				updateStatus();
				cleanup();
				listener.downloadFinished(id);
			} catch (Exception e) {
				if (!stopFlag) {
					Logger.error(e);
					errorCode = XDMConstants.ERR_ASM_FAILED;
					listener.downloadFailed(id);
				}
			}
		}).start();
	}

	private synchronized void createChunk() throws IOException {
		if (stopFlag)
			return;
		int activeCount = getActiveChunkCount();
		Logger.info("active count:" + activeCount);
		if (activeCount == MAX_COUNT) {
			Logger.info("Maximum chunk created");
			return;
		}

		int rem = MAX_COUNT - activeCount;

		rem -= retryFailedChunks(rem);

		if (rem > 0) {
			Segment c1 = findMaxChunk();
			Segment c = splitChunk(c1);
			if (c != null) {
				Logger.info("creating chunk " + c);
				chunks.add(c);
				c.download(this);
			}
		}
	}

	private Segment findMaxChunk() {
		if (stopFlag)
			return null;
		long size = -1;
		String id = null;
		for (Segment c : chunks) {
			if (c.isActive()) {
				long rem = c.getLength() - c.getDownloaded();
				if (rem > size) {
					id = c.getId();
					size = rem;
				}
			}
		}
		if (size < MIN_CHUNK_SIZE)
			return null;
		return getById(id);
	}

	private void mergeChunk(Segment c1, Segment c2) {
		c1.setLength(c1.getLength() + c2.getLength());
	}

	private Segment splitChunk(Segment c) throws IOException {
		if (c == null || stopFlag)
			return null;
		long rem = c.getLength() - c.getDownloaded();
		long offset = c.getStartOffset() + c.getLength() - rem / 2;
		long len = rem / 2;
		Logger.info("Changing length from: " + c.getLength() + " to " + (c.getLength() - rem / 2));
		c.setLength(c.getLength() - rem / 2);
		Segment c2 = new SegmentImpl(this, folder);
		c2.setTag(c.getTag());
		c2.setLength(len);
		c2.setStartOffset(offset);
		return c2;
	}

	private Segment findNextNeedyChunk(Segment chunk) {
		if (stopFlag)
			return null;
		long offset = chunk.getStartOffset() + chunk.getLength();
		for (Segment c : chunks) {
			if (c.getDownloaded() == 0) {
				if (!c.isFinished()) {
					if (c.getStartOffset() == offset && chunk.getTag().equals(c.getTag())) {
						return c;
					}
				}
			}
		}
		return null;
	}

	@Override
	public void progress(int progress) {
		this.convertPrg = progress;
		long now = System.currentTimeMillis();
		if (now - lastUpdated > 1000) {
			updateStatus();
			lastUpdated = now;
		}
	}

}
