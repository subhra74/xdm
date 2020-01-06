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
import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;

import xdman.Config;
import xdman.XDMConstants;
import xdman.downloaders.http.HttpChannel;
import xdman.downloaders.metadata.DashMetadata;
import xdman.mediaconversion.FFmpeg;
import xdman.mediaconversion.MediaConversionListener;
import xdman.mediaconversion.MediaFormats;
import xdman.util.FormatUtilities;
import xdman.util.Logger;
import xdman.util.StringUtils;
import xdman.util.XDMUtils;

public abstract class SegmentDownloader extends Downloader implements SegmentListener, MediaConversionListener {
	private boolean init = false;
	private int MIN_CHUNK_SIZE = 256 * 1024;
	private boolean assembleFinished;
	private long totalAssembled;

	protected SegmentDownloader(String id, String folder) {
		this.id = id;
		this.folder = new File(folder, id).getAbsolutePath();
		this.length = -1;
		this.MAX_COUNT = Config.getInstance().getMaxSegments();
		this.MIN_CHUNK_SIZE = Config.getInstance().getMinSegmentSize();
		this.lastDownloaded = downloaded;
		this.prevTime = System.currentTimeMillis();
		this.eta = "---";
	}

	public void start() {
		Logger.log("creating folder " + folder);
		new File(folder).mkdirs();
		chunks = new ArrayList<Segment>();
		try {
			Segment c1 = new SegmentImpl(this, folder);
			// handle case of single dash stream
			if (getMetadata() instanceof DashMetadata) {
				c1.setTag("T1");
			}
			c1.setLength(-1);
			c1.setStartOffset(0);
			c1.setDownloaded(0);
			chunks.add(c1);
			c1.download(this);
		} catch (IOException e) {
			this.errorCode = XDMConstants.RESUME_FAILED;
			this.listener.downloadFailed(id);
		}

	}

	@Override
	public void resume() {
		try {
			stopFlag = false;
			Logger.log("Resuming");
			if (!restoreState()) {
				Logger.log("Starting from beginning");
				start();
				return;
			}
			this.lastDownloaded = downloaded;
			this.prevTime = System.currentTimeMillis();
			Logger.log("Restore success");
			init = true;
			Segment c1 = findInactiveChunk();
			if (c1 != null) {
				try {
					c1.download(this);
				} catch (Exception e) {
					Logger.log(e);
					if (!stopFlag) {
						Logger.log(e);
						this.errorCode = XDMConstants.RESUME_FAILED;
						listener.downloadFailed(this.id);
						return;
					}
				}
			} else if (allFinished()) {
				assembleAsync();
			} else {
				Logger.log("Internal error: no inactive/incomplete chunk found while resuming!");
			}
		} catch (Exception e) {
			Logger.log(e);
			this.errorCode = XDMConstants.RESUME_FAILED;
			listener.downloadFailed(this.id);
			return;
		}
	}

	private synchronized void createChunk() throws IOException {
		if (stopFlag)
			return;
		int activeCount = getActiveChunkCount();
		Logger.log("active count:" + activeCount);
		if (activeCount == MAX_COUNT) {
			return;
		}

		int rem = MAX_COUNT - activeCount;
		// Logger.log("rem:" + rem);

		rem -= retryFailedChunks(rem);

		if (rem > 0) {
			Segment c1 = findMaxChunk();
			Segment c = splitChunk(c1);
			if (c != null) {
				Logger.log("creating chunk " + c);
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
		for (int i = 0; i < chunks.size(); i++) {
			Segment c = chunks.get(i);
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

	// merge c2 into c1
	private void mergeChunk(Segment c1, Segment c2) {
		c1.setLength(c1.getLength() + c2.getLength());
	}

	private Segment splitChunk(Segment c) throws IOException {
		if (c == null || stopFlag)
			return null;
		long rem = c.getLength() - c.getDownloaded();
		long offset = c.getStartOffset() + c.getLength() - rem / 2;
		long len = rem / 2;
		Logger.log("Changing length from: " + c.getLength() + " to " + (c.getLength() - rem / 2));
		c.setLength(c.getLength() - rem / 2);
		Segment c2 = new SegmentImpl(this, folder);
		// handle case of single dash stream
		if (getMetadata() instanceof DashMetadata) {
			c2.setTag("T1");
		}
		c2.setLength(len);
		c2.setStartOffset(offset);
		return c2;
	}

	private Segment findNextNeedyChunk(Segment chunk) {
		if (stopFlag)
			return null;
		long offset = chunk.getStartOffset() + chunk.getLength();
		for (int i = 0; i < chunks.size(); i++) {
			Segment c = chunks.get(i);
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
		if (allFinished() || length < 0) {
			// finish
			finished = true;
			updateStatus();
			try {
				assemble();
				if (!assembleFinished) {
					throw new IOException("Assemble failed");
				}
				Logger.log("********Download finished*********");
				updateStatus();
				listener.downloadFinished(this.id);
			} catch (Exception e) {
				if (!stopFlag) {
					Logger.log(e);
					this.errorCode = XDMConstants.ERR_ASM_FAILED;
					listener.downloadFailed(this.id);
				}
			}

			listener = null;
			return true;
		}
		Segment chunk = getById(id);
		Logger.log("Complete: " + chunk + " " + chunk.getDownloaded() + " " + chunk.getLength());
		Segment nextNeedyChunk = findNextNeedyChunk(chunk);
		if (nextNeedyChunk != null) {
			Logger.log("****************Needy chunk found!!!");
			Logger.log("Stopping: " + nextNeedyChunk);
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
	public synchronized void chunkInitiated(String id) throws IOException {
		if (stopFlag)
			return;
		if (!init) {
			Segment c = getById(id);
			this.length = c.getLength();
			init = true;
			Logger.log("size: " + this.length);
			if (c.getChannel() instanceof HttpChannel) {
				super.getLastModifiedDate(c);
			}
			saveState();
			chunkConfirmed(c);
			listener.downloadConfirmed(this.id);
		}
		if (length > 0) {
			createChunk();
		}
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
					} catch (Exception e) {
						Logger.log(e);
					}
				}
			}
		}
	}

	private void assemble() throws IOException {
		InputStream in = null;
		OutputStream out = null;
		totalAssembled = 0L;
		assembling = true;
		assembleFinished = false;
		String outFileFinal = getOutputFileName(true);
		String outFileName = (outputFormat == 0 ? UUID.randomUUID().toString() + "_" + outFileFinal
				: UUID.randomUUID().toString());
		String outputFolder = (outputFormat == 0 ? getOutputFolder() : folder);
		XDMUtils.mkdirs(getOutputFolder());
		File outFile = new File(outputFolder, outFileName);
		File ffOutFile = null;
		try {
			if (stopFlag)
				return;
			byte buf[] = new byte[1024 * 1024];
			Logger.log("assembling... ");
			Collections.sort(chunks, new SegmentComparator());
			// chunks.sort(new SegmentComparator());
			out = new FileOutputStream(outFile);
			for (int i = 0; i < chunks.size(); i++) {
				Logger.log("chunk " + i + " " + stopFlag);
				Segment c = chunks.get(i);
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
				in.close();
			}
			out.close();
			setLastModifiedDate(outFile);
			updateStatus();
			if (outputFormat != 0) {
				XDMUtils.mkdirs(getOutputFolder());
				this.converting = true;
				ffOutFile = new File(getOutputFolder(), UUID.randomUUID().toString() + "_" + getOutputFileName(true));

				this.ffmpeg = new FFmpeg(Arrays.asList(new String[] { outFile.getAbsolutePath() }),
						ffOutFile.getAbsolutePath(), this, MediaFormats.getSupportedFormats()[outputFormat],
						outputFormat == 0);
				int ret = ffmpeg.convert();
				Logger.log("FFmpeg exit code: " + ret);

				if (ret != 0) {
					throw new IOException("FFmpeg failed");
				} else {
					long length = ffOutFile.length();
					if (length > 0) {
						this.length = length;
					}
				}
			}
			// delete the original file if exists and rename the temp file to original
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

			assembleFinished = true;
		} catch (Exception e) {
			Logger.log(e);
			throw new IOException(e);
		} finally {
			if (in != null) {
				try {
					in.close();
				} catch (Exception e2) {
				}
			}
			if (out != null) {
				try {
					out.close();
				} catch (Exception e2) {
				}
			}

			if (!assembleFinished) {
				outFile.delete();
				if (ffOutFile != null) {
					ffOutFile.delete();
				}
			}
		}
	}

	// private void closeStream(InputStream in, OutputStream out) {
	// if (in != null) {
	// try {
	// in.close();
	// } catch (Exception e2) {
	// }
	// if (out != null) {
	// try {
	// out.close();
	// } catch (Exception e2) {
	// }
	// }
	// }
	// }

	// example
	// public HttpChannel createChannel(Segment segment){
	// return new HttpChannel(httpMetadata,segment);
	// }
	@Override
	public abstract AbstractChannel createChannel(Segment segment);

	public void stop() {
		stopFlag = true;
		saveState();
		for (int i = 0; i < chunks.size(); i++) {
			chunks.get(i).stop();
		}

		if (this.ffmpeg != null) {
			this.ffmpeg.stop();
		}
		listener.downloadStopped(id);
		listener = null;
	}

	private void saveState() {
		if (length < 0)
			return;
		StringBuffer sb = new StringBuffer();
		sb.append(this.length + "\n");
		sb.append(downloaded + "\n");
		sb.append(chunks.size() + "\n");
		for (int i = 0; i < chunks.size(); i++) {
			Segment seg = chunks.get(i);
			sb.append(seg.getId() + "\n");
			sb.append(seg.getLength() + "\n");
			sb.append(seg.getStartOffset() + "\n");
			sb.append(seg.getDownloaded() + "\n");
		}
		if (!StringUtils.isNullOrEmptyOrBlank(lastModified)) {
			sb.append(this.lastModified + "\n");
		}
		try {
			File tmp = new File(folder, System.currentTimeMillis() + ".tmp");
			File out = new File(folder, "state.txt");
			FileOutputStream fs = new FileOutputStream(tmp);
			fs.write(sb.toString().getBytes());
			fs.close();
			out.delete();
			tmp.renameTo(out);
		} catch (Exception e) {
			Logger.log(e);
		}
	}

	private boolean restoreState() {
		BufferedReader br = null;
		chunks = new ArrayList<Segment>();
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
			int chunkCount = Integer.parseInt(br.readLine());
			for (int i = 0; i < chunkCount; i++) {
				String cid = XDMUtils.readLineSafe(br);
				long len = Long.parseLong(XDMUtils.readLineSafe(br));
				long off = Long.parseLong(XDMUtils.readLineSafe(br));
				long dwn = Long.parseLong(XDMUtils.readLineSafe(br));
				Segment seg = new SegmentImpl(folder, cid, off, len, dwn);
				// handle case of single dash stream
				if (getMetadata() instanceof DashMetadata) {
					seg.setTag("T1");
				}

				Logger.log("id: " + seg.getId() + "\nlength: " + seg.getLength() + "\noffset: " + seg.getStartOffset()
						+ "\ndownload: " + seg.getDownloaded());
				chunks.add(seg);
			}
			this.lastModified = br.readLine();
			return true;
		} catch (Exception e) {
			Logger.log("Failed to load saved state");
			Logger.log(e);
		} finally {
			if (br != null) {
				try {
					br.close();
				} catch (IOException e) {
				}
			}
		}
		return false;
	}

	protected abstract void chunkConfirmed(Segment c);

	public boolean shouldCleanup() {
		return assembleFinished;
	}

	private void updateStatus() {
		try {
			long now = System.currentTimeMillis();
			if (converting) {
				progress = this.convertPrg;
			} else if (this.assembling) {
				long len = length > 0 ? length : downloaded;
				progress = (int) ((totalAssembled * 100) / len);
			} else {
				long downloaded2 = 0;
				if (segDet == null) {
					segDet = new SegmentDetails();
				}
				if (segDet.getCapacity() < chunks.size()) {
					segDet.extend(chunks.size() - segDet.getCapacity());
				}
				segDet.setChunkCount(chunks.size());
				downloadSpeed = 0;
				for (int i = 0; i < chunks.size(); i++) {
					Segment s = chunks.get(i);
					downloaded2 += s.getDownloaded();
					SegmentInfo info = segDet.getChunkUpdates().get(i);
					info.setDownloaded(s.getDownloaded());
					info.setStart(s.getStartOffset());
					info.setLength(s.getLength());
					downloadSpeed += s.getTransferRate();
				}
				this.downloaded = downloaded2;
				if (length > 0) {
					progress = (int) ((downloaded * 100) / length);
					long diff = downloaded - lastDownloaded;
					long timeSpend = now - prevTime;
					if (timeSpend > 0) {
						float rate = ((float) diff / timeSpend) * 1000;
						// if (rate > downloadSpeed) {
						// downloadSpeed = rate;
						// }
						//downloadSpeed = rate;
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
			Logger.log(e);
		}
	}

	private void assembleAsync() {
		new Thread(new Runnable() {

			@Override
			public void run() {
				finished = true;
				try {
					assemble();
					if (!assembleFinished) {
						throw new IOException("Assemble not finished successfully");
					}
					Logger.log("********Download finished*********");
					updateStatus();
					cleanup();
					listener.downloadFinished(id);
				} catch (Exception e) {
					if (!stopFlag) {
						Logger.log(e);
						errorCode = XDMConstants.ERR_ASM_FAILED;
						listener.downloadFailed(id);
					}
				}
			}
		}).start();
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
