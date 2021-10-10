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

package xdman.downloaders.hls;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import xdman.downloaders.metadata.HlsMetadata;
import xdman.downloaders.metadata.HttpMetadata;
import xdman.mediaconversion.FFmpeg;
import xdman.mediaconversion.MediaConversionListener;
import xdman.mediaconversion.MediaFormats;
import xdman.util.FormatUtilities;
import xdman.util.IOUtils;
import xdman.util.StringUtils;
import xdman.util.XDMUtils;

@SuppressWarnings({"unused", "ResultOfMethodCallIgnored", "FieldCanBeLocal"})
public class HlsDownloader extends Downloader implements SegmentListener, MediaConversionListener, HlsEncryptedSource {

	private final HlsMetadata metadata;
	private final ArrayList<HlsPlaylistItem> items;
	private Segment manifestSegment;
	private long totalAssembled;
	private String newFileName;
	private boolean assembleFinished;
	private FFmpeg ffmpeg;
	private int lastProgress;
	private float totalDuration;
	private HlsPlaylist playlist;

	private Map<String, byte[]> keyMap;

	public HlsDownloader(String id, String folder, HlsMetadata metadata) {
		this.id = id;
		this.folder = new File(folder, id).getAbsolutePath();
		this.length = -1;
		this.metadata = metadata;
		this.MAX_COUNT = Config.getInstance().getMaxSegments();
		items = new ArrayList<>();
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
			Logger.error(e);
		}
	}

	@Override
	public void chunkInitiated(String id) {
		if (!id.equals(manifestSegment.getId())) {
			Logger.info("Non manifest segment: " + id + " manifest seg: " + manifestSegment.getId());
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
			Logger.info("Manifest segment complete: " + id);
			if (initOrUpdateSegments()) {
				listener.downloadConfirmed(this.id);
				Logger.info("confirmed");
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
				saveState();
				finished = true;
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
		if (manifestSegment != null && id.equals(manifestSegment.getId())) {
			return;
		}
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
				HlsPlaylistItem item = items.get(i);
				if (keyMap != null && item.getKeyUrl() != null) {
					Logger.info("Creating encrypted channel");
					return new EncryptedHlsChannel(segment, item.getUrl(), metadata.getHeaders(), -1,
							isJavaClientRequired, this, item.getKeyUrl());
				} else {
					return new HttpChannel(segment, item.getUrl(), metadata.getHeaders(), -1, isJavaClientRequired);
				}
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
			this.playlist = PlaylistParser.parse(new File(folder, manifestSegment.getId()).getAbsolutePath(),
					metadata.getUrl());

			if (this.playlist == null) {
				Logger.warn("Manifest either invalid or have unsupported DRM");
				return false;
			}
			this.totalDuration = playlist.getDuration();
			Logger.info("Total duration");
			List<HlsPlaylistItem> pitems = playlist.getItems();
			if (pitems == null) {
				Logger.warn("Manifest either invalid or have unsupported DRM");
				return false;
			}
			if (pitems.size() < 1) {
				Logger.warn("Manifest contains no media");
				return false;
			}
			if (items.size() > 0 && items.size() != playlist.getItems().size()) {
				Logger.warn("Manifest media count mismatch- expected: " + items.size() + " got: "
						+ playlist.getItems().size());
				return false;
			}
			if (items.size() > 0) {
				items.clear();
			}

			if (playlist.isEncrypted()) {
				keyMap = new HashMap<>();
			}

			Logger.info("Playlist items: " + playlist.getItems().size());

			for (HlsPlaylistItem item : playlist.getItems()) {
				Logger.info(item);
				HlsPlaylistItem item2 = new HlsPlaylistItem(item.getUrl(), item.getKeyUrl(), item.getIV(), null, null,
						item.getDuration());
				this.items.add(item2);
			}

			String newExtension = null;
			Logger.info("Chunk size: " + chunks.size());
			if (chunks.size() < 1) {
				Logger.info("Creating chunk");
				for (HlsPlaylistItem item : items) {
					if (newExtension == null && outputFormat == 0) {
						newExtension = findExtension(item.getUrl());
						if (newExtension != null) {
							Logger.info("HLS: found new extension: " + newExtension);

							this.newFileName = getOutputFileName(true).replace(".ts", newExtension);

						} else {
							newExtension = ".ts";
						}
					}
					Segment s2 = new SegmentImpl(this, folder);
					s2.setTag("HLS");
					s2.setLength(-1);
					Logger.info("Adding chunk: " + s2);
					Logger.info("Adding");
					chunks.add(s2);
				}
				Logger.info("Segments created");
			}
			return true;
		} catch (Exception e) {
			Logger.error(e);
			return false;
		}

	}

	private synchronized void processSegments() {
		Logger.info("HLS: process segment");
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
		sb.append(items.size()).append("\n");
		Logger.info("url saved of size: " + items.size());
		for (HlsPlaylistItem hlsPlaylistItem : items) {
			String url = hlsPlaylistItem.getUrl();
			Logger.info("Saving url: " + url);
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

		sb.append(keyMap != null);// if this is true that means it can have info about encrypted segments

		if (keyMap != null) {
			for (HlsPlaylistItem item : items) {
				boolean hasKey = !StringUtils.isNullOrEmptyOrBlank(item.getKeyUrl());
				sb.append(hasKey);
				if (hasKey) {
					sb.append(item.getKeyUrl()).append("\n");
				}
				boolean hasIV = !StringUtils.isNullOrEmptyOrBlank(item.getIV());
				sb.append(hasIV);
				if (hasIV) {
					sb.append(item.getIV()).append("\n");
				}
			}

			sb.append(keyMap.size()).append("\n");
			for (Map.Entry<String, byte[]> ent : keyMap.entrySet()) {
				sb.append(ent.getKey()).append("\n");
				sb.append(Base64.getEncoder().encodeToString(ent.getValue()));
			}
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
			Logger.info("Loading urls: " + urlCount);
			for (int i = 0; i < urlCount; i++) {
				String url = XDMUtils.readLineSafe(br);// br.readLine();
				HlsPlaylistItem item = new HlsPlaylistItem();
				item.setUrl(url);
				items.add(item);
				Logger.info("loading url: " + url);
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

			String strHasMoreInfo = br.readLine();
			if (strHasMoreInfo != null) {
				if ("true".equals(strHasMoreInfo)) {
					for (int i = 0; i < urlCount; i++) {
						HlsPlaylistItem item = items.get(i);
						if ("true".equals(br.readLine())) {
							item.setKeyUrl(XDMUtils.readLineSafe(br));
						}
						if ("true".equals(br.readLine())) {
							item.setIV(XDMUtils.readLineSafe(br));
						}
					}

					int keys = Integer.parseInt(br.readLine());
					for (int i = 0; i < keys; i++) {
						String keyUrl = XDMUtils.readLineSafe(br);
						Logger.info("Keydata: " + keyUrl);
						String keyData = XDMUtils.readLineSafe(br);
						byte[] data = Base64.getDecoder().decode(keyData);
						keyMap.put(keyUrl, data);
					}
				}
			}

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
					if (!newExtension.contains("mp4")) {
						newExtension = ".mkv"; // if extension is not mp4 or ts save it in MKV container, as it might be
					}
				}
			}
		}
		return newExtension;
	}

	private void assemble() throws IOException {
		File ffOutFile = null;
		XDMUtils.mkdirs(getOutputFolder());
		try {
			assembleFinished = false;
			StringBuilder sb = new StringBuilder();
			for (Segment s : chunks) {
				sb.append("file '").append(new File(folder, s.getId())).append("'\r\n");
			}

			File hlsFile = new File(folder, id + "-hls.txt");

			try (OutputStream hlsTextStream = new FileOutputStream(hlsFile)) {
				hlsTextStream.write(sb.toString().getBytes());
			} catch (Exception e) {
				Logger.error(e);
			}
			this.converting = true;
			List<String> inputFiles = new ArrayList<>();
			inputFiles.add(hlsFile.getAbsolutePath());
			ffOutFile = new File(getOutputFolder(), UUID.randomUUID() + "_" + getOutputFileName(true));
			this.ffmpeg = new FFmpeg(inputFiles, ffOutFile.getAbsolutePath(), this,
					MediaFormats.getSupportedFormats()[outputFormat], outputFormat == 0);
			ffmpeg.setHls(true);
			ffmpeg.setHLSDuration(totalDuration);
			int ret = ffmpeg.convert();
			Logger.info("FFmpeg exit code: " + ret);

			if (ret != 0) {
				throw new IOException("FFmpeg failed");
			} else {
				long length = ffOutFile.length();
				if (length > 0) {
					this.length = length;
				}
			}

			File realFile = new File(getOutputFolder(), getOutputFileName(true));
			if (realFile.exists()) {
				realFile.delete();
			}
			ffOutFile.renameTo(realFile);

			assembleFinished = true;
		} finally {
			if (!assembleFinished) {
				if (ffOutFile != null) {
					ffOutFile.delete();
				}
			}
		}
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

	@Override
	public boolean hasKey(String keyUrl) {
		if (keyMap == null || keyUrl == null) {
			return false;
		}

		return keyMap.get(keyUrl) != null;
	}

	@Override
	public void setKey(String keyUrl, byte[] data) {
		if (keyUrl != null && data != null && keyMap != null) {
			if (!keyMap.containsKey(keyUrl))
				keyMap.put(keyUrl, data);
		}
	}

	@Override
	public byte[] getKey(String keyUrl) {
		return keyMap.get(keyUrl);
	}

	@Override
	public String getIV(String url) {
		if (StringUtils.isNullOrEmptyOrBlank(url)) {
			return null;
		}
		for (HlsPlaylistItem item : items) {
			if (url.equals(item.getUrl())) {
				return item.getIV();
			}
		}
		return null;
	}

}