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

package xdman.downloaders.hds;

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
import xdman.downloaders.AbstractChannel;
import xdman.downloaders.Downloader;
import xdman.downloaders.Segment;
import xdman.downloaders.SegmentDetails;
import xdman.downloaders.SegmentImpl;
import xdman.downloaders.SegmentInfo;
import xdman.downloaders.SegmentListener;
import xdman.downloaders.http.HttpChannel;
import xdman.downloaders.metadata.HdsMetadata;
import xdman.downloaders.metadata.HttpMetadata;
import xdman.downloaders.metadata.manifests.F4MManifest;
import xdman.mediaconversion.FFmpeg;
import xdman.mediaconversion.MediaConversionListener;
import xdman.mediaconversion.MediaFormats;
import xdman.util.FormatUtilities;
import xdman.util.IOUtils;
import xdman.util.StringUtils;
import xdman.util.XDMUtils;

@SuppressWarnings("ResultOfMethodCallIgnored")
public class HdsDownloader extends Downloader implements SegmentListener, MediaConversionListener {

	private final HdsMetadata metadata;
	private final ArrayList<String> urlList;
	private Segment manifestSegment;
	private long totalAssembled;
	private String newFileName;
	private boolean assembleFinished;
	private FFmpeg ffmpeg;
	private int lastProgress;
	private float totalDuration;

	private final byte[] flv_sig = { (byte) 'F', (byte) 'L', (byte) 'V', 0x01, 0x05, 0x00, 0x00, 0x00, 0x09, 0x00, 0x00,
			0x00, 0x00 };

	public HdsDownloader(String id, String folder, HdsMetadata metadata) {
		this.id = id;
		this.folder = new File(folder, id).getAbsolutePath();
		this.length = -1;
		this.metadata = metadata;
		this.MAX_COUNT = Config.getInstance().getMaxSegments();
		urlList = new ArrayList<>();
		chunks = new ArrayList<>();
		this.eta = "---";
	}

	public void start() {
		Logger.info("creating folder " + folder);
		new File(folder).mkdirs();

		this.lastDownloaded = downloaded;
		this.prevTime = System.currentTimeMillis();
		try {
			manifestSegment = new SegmentImpl(this, folder);
			manifestSegment.setTag("MF");
			manifestSegment.setLength(-1);
			manifestSegment.setStartOffset(0);
			manifestSegment.setDownloaded(0);
			manifestSegment.setTag("HLS");
			manifestSegment.download(this);
		} catch (IOException e) {
			this.errorCode = XDMConstants.RESUME_FAILED;
			this.listener.downloadFailed(id);
		}
	}

	@Override
	public void chunkInitiated(String id) {
		if (!id.equals(manifestSegment.getId())) {
			processSegments();
		} else {
			isJavaClientRequired = ((HttpChannel) manifestSegment.getChannel()).isJavaClientRequired();
			super.getLastModifiedDate(manifestSegment);
		}
	}

	@Override
	public boolean chunkComplete(String id) {
		if (finished) {
			return true;
		}

		if (stopFlag) {
			return true;
		}
		if (id.equals(manifestSegment.getId())) {
			if (initOrUpdateSegments()) {
				listener.downloadConfirmed(this.id);
			} else {
				if (!stopFlag) {
					this.errorCode = XDMConstants.ERR_INVALID_RESP;
					listener.downloadFailed(this.id);
					return true;
				}
			}
		} else {
			Segment s = getById(id);
			if (s.getLength() < 0) {
				s.setLength(s.getDownloaded());
			}

			if (allFinished()) {

				finished = true;
				long len = 0L;
				for (Segment ss : chunks) {
					len += ss.getLength();
				}
				if (len > 0) {
					this.length = len;
				}

				saveState();

				updateStatus();

				try {
					assemble();
					if (!assembleFinished) {
						throw new IOException("Assemble not finished successfully");
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
		}

		Segment s = getById(id);
		clearChannel(s);
		processSegments();
		return true;
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
				processSegments();
			}
		}
	}

	@Override
	public AbstractChannel createChannel(Segment segment) {
		for (int i = 0; i < chunks.size(); i++) {
			if (segment == chunks.get(i)) {
				HdsMetadata md = new HdsMetadata();
				md.setUrl(urlList.get(i));
				md.setHeaders(metadata.getHeaders());
				return new HttpChannel(segment, md.getUrl(), md.getHeaders(), -1, isJavaClientRequired);
			}
		}
		Logger.info("Create manifest channel");
		return new HttpChannel(segment, metadata.getUrl(), metadata.getHeaders(), -1, isJavaClientRequired);
	}

	@Override
	public boolean shouldCleanup() {
		return assembleFinished;
	}

	private boolean initOrUpdateSegments() {
		try {
			F4MManifest mf = new F4MManifest(metadata.getUrl(),
					new File(folder, manifestSegment.getId()).getAbsolutePath());
			mf.setSelectedBitRate(metadata.getBitRate());
			this.totalDuration = mf.getDuration();
			Logger.info("Total duration " + totalDuration);
			ArrayList<String> urls = mf.getMediaUrls();
			if (urls.size() < 1) {
				Logger.info("Manifest contains no media");
				return false;
			}
			if (urlList.size() > 0 && urlList.size() != urls.size()) {
				Logger.warn("Manifest media count mismatch- expected: " + urlList.size() + " got: " + urls.size());
				return false;
			}
			if (urlList.size() > 0) {
				urlList.clear();
			}
			urlList.addAll(urls);

			String newExtension = null;

			if (chunks.size() < 1) {
				for (String s : urlList) {
					if (newExtension == null && outputFormat == 0) {
						newExtension = findExtension(s);
						Logger.info("HDS: found new extension: " + newExtension);
						if (newExtension != null) {
							this.newFileName = getOutputFileName(false).replace(".flv", newExtension);

						} else {
							newExtension = ".flv";// just to skip the whole file
						}
					}

					Logger.info("HDS: New file name: " + this.newFileName);

					Segment s2 = new SegmentImpl(this, folder);
					s2.setTag("HLS");
					s2.setLength(-1);
					Logger.info("Adding chunk: " + s2);
					chunks.add(s2);
				}
			}
			return true;
		} catch (Exception e) {
			Logger.error(e);
			return false;
		}

	}

	private synchronized void processSegments() {
		int activeCount = getActiveChunkCount();
		Logger.info("active: " + activeCount);
		if (activeCount < MAX_COUNT) {
			int rem = MAX_COUNT - activeCount;
			try {
				retryFailedChunks(rem);
			} catch (IOException e) {
				Logger.error(e);
			}
		}
	}

	private void updateStatus() {
		try {
			long now = System.currentTimeMillis();
			if (this.eta == null) {
				this.eta = "---";
			}
			if (converting) {
				progress = this.convertPrg;
			} else if (assembling) {
				long len = length > 0 ? length : downloaded;
				progress = (int) ((totalAssembled * 100) / len);
			} else {
				long downloaded2 = 0;
				int processedSegments = 0;
				int partPrg = 0;
				downloadSpeed = 0;
				for (Segment s : chunks) {
					downloaded2 += s.getDownloaded();
					downloadSpeed += s.getTransferRate();
					if (s.isFinished()) {
						processedSegments++;
					} else if (s.getDownloaded() > 0 && s.getLength() > 0) {
						int prg2 = (int) ((s.getDownloaded() * 100) / s.getLength());
						partPrg += prg2;
					}
				}
				this.downloaded = downloaded2;
				if (chunks.size() > 0) {
					progress = (processedSegments * 100) / chunks.size();
					progress += (partPrg / chunks.size());
					if (segDet == null) {
						segDet = new SegmentDetails();
						if (segDet.getCapacity() < chunks.size()) {
							segDet.extend(chunks.size() - segDet.getCapacity());
						}
						segDet.setChunkCount(chunks.size());
					}
					SegmentInfo info = segDet.getChunkUpdates().get(0);
					info.setDownloaded(progress);
					info.setLength(100);
					info.setStart(0);
					long timeSpend = now - prevTime;
					if (timeSpend > 0) {

						int prgDiff = progress - lastProgress;
						if (prgDiff > 0) {
							long eta = (timeSpend * (100 - progress) / 1000 * prgDiff);// prgDiff
							lastProgress = progress;
							this.eta = FormatUtilities.hms((int) eta);
						}

						prevTime = now;
						lastDownloaded = downloaded;
					}
				}
			}

			listener.downloadUpdated(id);
		} catch (Exception e) {
			Logger.error(e);
		}
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
			this.lastProgress = this.progress;
			this.prevTime = System.currentTimeMillis();
			if (allFinished()) {
				assembleAsync();
			} else {
				Logger.info("Starting");
				start();
			}
		} catch (Exception e) {
			Logger.error(e);
			this.errorCode = XDMConstants.RESUME_FAILED;
			listener.downloadFailed(this.id);
		}
	}

	@Override
	public int getType() {
		return XDMConstants.HLS;
	}

	@Override
	public boolean isFileNameChanged() {
		return newFileName != null;
	}

	@Override
	public String getNewFile() {
		return newFileName;
	}

	@Override
	public HttpMetadata getMetadata() {
		return this.metadata;
	}

	private void saveState() {
		if (chunks.size() < 1)
			return;
		StringBuilder sb = new StringBuilder();
		sb.append(this.length).append("\n");
		sb.append(downloaded).append("\n");
		sb.append((long) this.totalDuration).append("\n");
		sb.append(urlList.size()).append("\n");
		for (String url : urlList) {
			sb.append(url).append("\n");
		}
		sb.append(chunks.size()).append("\n");
		for (Segment seg : chunks) {
			sb.append(seg.getId()).append("\n");
			if (seg.isFinished()) {
				sb.append(seg.getLength()).append("\n");
				sb.append(seg.getStartOffset()).append("\n");
				sb.append(seg.getDownloaded()).append("\n");
			} else {
				sb.append("-1\n");
				sb.append(seg.getStartOffset()).append("\n");
				sb.append(seg.getDownloaded()).append("\n");
			}
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
			this.totalDuration = Long.parseLong(br.readLine());
			int urlCount = Integer.parseInt(br.readLine());
			for (int i = 0; i < urlCount; i++) {
				String url = XDMUtils.readLineSafe(br);// br.readLine();
				urlList.add(url);
			}
			int chunkCount = Integer.parseInt(br.readLine());
			for (int i = 0; i < chunkCount; i++) {
				String cid = XDMUtils.readLineSafe(br);// br.readLine();
				long len = Long.parseLong(br.readLine());
				long off = Long.parseLong(br.readLine());
				long dwn = Long.parseLong(br.readLine());
				Segment seg = new SegmentImpl(folder, cid, off, len, dwn);
				seg.setTag("HLS");
				Logger.info("id: " + seg.getId() + "\nlength: " + seg.getLength() + "\noffset: " + seg.getStartOffset()
						+ "\ndownload: " + seg.getDownloaded());
				chunks.add(seg);
			}
			this.lastModified = XDMUtils.readLineSafe(br);// br.readLine();
			return true;
		} catch (Exception e) {
			Logger.info(e, "Failed to load saved state");
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

	private String findExtension(String urlStr) {
		String newExtension = null;
		String fileName = XDMUtils.getFileName(urlStr);
		if (!StringUtils.isNullOrEmptyOrBlank(fileName)) {
			String ext = XDMUtils.getExtension(fileName);
			if ((!StringUtils.isNullOrEmptyOrBlank(ext)) && ext.length() > 1) {
				if (!ext.toLowerCase().contains("ts")) {
					newExtension = ext.toLowerCase();
					if (newExtension.contains("m4s")) {
						Logger.info("HLS extension: MP4");
						newExtension = ".mp4";
					}
				}
			}
		}
		return newExtension;
	}

	private void assemble() {
		InputStream in = null;
		OutputStream out = null;
		totalAssembled = 0L;
		assembling = true;
		assembleFinished = false;
		File ffOutFile = null;
		File outFile;

		XDMUtils.mkdirs(getOutputFolder());

		String outFileName = (outputFormat == 0 ? UUID.randomUUID() + "_" + getOutputFileName(true)
				: UUID.randomUUID().toString());
		String outputFolder = (outputFormat == 0 ? getOutputFolder() : folder);
		outFile = new File(outputFolder, outFileName);

		try {
			if (stopFlag)
				return;
			Logger.info("assembling... ");
			out = new FileOutputStream(outFile);
			out.write(flv_sig);
			for (Segment s : chunks) {
				File inFile = new File(folder, s.getId());
				in = new FileInputStream(inFile);
				long streamPos = 0, streamLen = inFile.length();
				while (streamPos < streamLen) {
					if (stopFlag) {
						return;
					}

					long boxSize = readInt32(in);
					streamPos += 4;
					String box_type = readStringBytes(in, 4);
					streamPos += 4;
					if (boxSize == 1) {
						boxSize = readInt64(in) - 16;
						streamPos += 8;
					} else {
						boxSize -= 8;
					}
					if (box_type.equals("mdat")) {
						long boxSize2 = boxSize;
						while (boxSize2 > 0) {
							if (stopFlag)
								return;
							int c = (int) (boxSize2 > b.length ? b.length : boxSize2);
							int x = in.read(b, 0, c);
							if (x == -1)
								throw new IOException("Unexpected EOF");
							out.write(b, 0, x);
							boxSize2 -= x;
							totalAssembled += x;
							long now = System.currentTimeMillis();
							if (now - lastUpdated > 1000) {
								updateStatus();
								lastUpdated = now;
							}
						}
					} else {
						in.skip(boxSize);
					}

					streamPos += boxSize;
				}
				IOUtils.closeFlow(in);
			}
			IOUtils.closeFlow(out);

			Logger.info("Output format: " + outputFormat);

			if (outputFormat != 0) {

				this.converting = true;
				ffOutFile = new File(getOutputFolder(), UUID.randomUUID() + "_" + getOutputFileName(true));

				this.ffmpeg = new FFmpeg(List.of(outFile.getAbsolutePath()),
						ffOutFile.getAbsolutePath(), this, MediaFormats.getSupportedFormats()[outputFormat],
						outputFormat == 0);
				int ret = ffmpeg.convert();
				Logger.info("FFmpeg exit code: " + ret);

				if (ret != 0) {
					throw new IOException("FFmpeg failed");
				} else {
					long length = ffOutFile.length();
					if (length > 0) {
						this.length = length;
					}
					setLastModifiedDate(ffOutFile);
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

			assembleFinished = true;
		} catch (Exception e) {
			Logger.error(e);
		} finally {
			IOUtils.closeFlow(out);
			IOUtils.closeFlow(in);
			if (!assembleFinished) {
				outFile.delete();
				if (ffOutFile != null) {
					ffOutFile.delete();
				}
			}
		}
	}

	byte[] b = new byte[8192];

	@Override
	public void progress(int progress) {
		this.convertPrg = progress;
		long now = System.currentTimeMillis();
		if (now - lastUpdated > 1000) {
			updateStatus();
			lastUpdated = now;
		}
	}

	private long readInt32(InputStream s) throws IOException {
		byte[] bytesData = new byte[4];
		if (s.read(bytesData, 0, bytesData.length) != bytesData.length) {
			throw new IOException("Invalid F4F box");
		}
		long iValLo = (bytesData[3] & 0xff) + ((long) (bytesData[2] & 0xff) * 256);
		long iValHi = (bytesData[1] & 0xff) + ((long) (bytesData[0] & 0xff) * 256);
		return iValLo + (iValHi * 65536);
	}

	private long readInt64(InputStream s) throws IOException {
		long iValHi = readInt32(s);
		long iValLo = readInt32(s);

		return iValLo + (iValHi * 4294967296L);
	}

	private String readStringBytes(InputStream s, long len) throws IOException {
		StringBuilder resultValue = new StringBuilder(4);
		for (int i = 0; i < len; i++) {
			resultValue.append((char) s.read());
		}
		return resultValue.toString();
	}

}
