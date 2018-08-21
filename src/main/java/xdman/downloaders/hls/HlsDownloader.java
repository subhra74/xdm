package xdman.downloaders.hls;

import xdman.Config;
import xdman.XDMConstants;
import xdman.downloaders.*;
import xdman.downloaders.http.HttpChannel;
import xdman.downloaders.metadata.HlsMetadata;
import xdman.downloaders.metadata.HttpMetadata;
import xdman.mediaconversion.FFmpeg;
import xdman.mediaconversion.MediaConversionListener;
import xdman.mediaconversion.MediaFormats;
import xdman.util.FormatUtilities;
import xdman.util.Logger;
import xdman.util.StringUtils;
import xdman.util.XDMUtils;

import java.io.*;
import java.util.*;

public class HlsDownloader extends Downloader implements SegmentListener, MediaConversionListener, HlsEncryptedSouce {
	private HlsMetadata metadata;
	private ArrayList<HlsPlaylistItem> items;
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
		Logger.log("HlsDownloader creating folder", folder);
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
			Logger.log("Non manifest segment:", id, "manifest seg:", manifestSegment.getId());
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
			Logger.log("Manifest segment complete:", id);
			if (initOrUpdateSegments()) {
				listener.downloadConfirmed(this.id);
				Logger.log("confirmed");
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
					Logger.log("Creating encrypted channel");
					return new EncryptedHlsChannel(segment, item.getUrl(), metadata.getHeaders(), -1,
							isJavaClientRequired, this, item.getKeyUrl());
				} else {
					return new HttpChannel(segment, item.getUrl(), metadata.getHeaders(), -1, isJavaClientRequired);
				}
			}
		}
		Logger.log("Create manifest channel");
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
				Logger.log("Manifest either invalid or have unsupported DRM");
				return false;
			}
			// M3U8Manifest mf = new M3U8Manifest(new File(folder,
			// manifestSegment.getId()).getAbsolutePath(),
			// metadata.getUrl());
			this.totalDuration = playlist.getDuration();
			Logger.log("Total duration");
			List<HlsPlaylistItem> pitems = playlist.getItems();
			if (pitems == null) {
				Logger.log("Manifest either invalid or have unsupported DRM");
				return false;
			}
			if (pitems.size() < 1) {
				Logger.log("Manifest contains no media");
				return false;
			}
			if (items.size() > 0 && items.size() != playlist.getItems().size()) {
				Logger.log("Manifest media count mismatch- expected:", items.size(),
						"got:", playlist.getItems().size());
				return false;
			}
			if (items.size() > 0) {
				items.clear();
			}

			if (playlist.isEncrypted()) {
				keyMap = new HashMap<>();
			}

			Logger.log("Play list items:", playlist.getItems().size());

			for (HlsPlaylistItem item : playlist.getItems()) {
				Logger.log(item);
				HlsPlaylistItem item2 = new HlsPlaylistItem(item.getUrl(), item.getKeyUrl(), item.getIV(), null, null,
						item.getDuration());
				this.items.add(item2);
			}

			String newExtension = null;
			Logger.log("Chunk size:", chunks.size());
			if (chunks.size() < 1) {
				Logger.log("Creating chunk");
				for (HlsPlaylistItem hlsPlaylistItem : items) {
					String url = hlsPlaylistItem.getUrl();
					if (newExtension == null && outputFormat == 0) {
						newExtension = findExtension(url);
						if (newExtension != null) {
							Logger.log("HLS: found new extension:", newExtension);

							this.newFileName = getOutputFileName(true).replace(".ts", newExtension);

						} else {
							newExtension = ".ts";// just to skip the whole file
							// ext extraction
						}
					}

					Logger.log("HLS: New file name:",
							this.newFileName,
							"segment creating for url:",
							url);
					Segment s2 = new SegmentImpl(this, folder);
					s2.setTag("HLS");
					s2.setLength(-1);
					Logger.log("Adding chunk:", s2);
					chunks.add(s2);
				}
				Logger.log("Segments created");
			}
			return true;
		} catch (Exception e) {
			Logger.log(e);
			return false;
		}

	}

	private synchronized void processSegments() {
		Logger.log("HLS: process segment");
		int activeCount = getActiveChunkCount();
		Logger.log("active:", activeCount);
		if (activeCount < MAX_COUNT) {
			int rem = MAX_COUNT - activeCount;
			try {
				retryFailedChunks(rem);
			} catch (IOException e) {
				Logger.log(e);
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
				for (int i = 0; i < chunks.size(); i++) {
					Segment s = chunks.get(i);
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
					long diff = downloaded - lastDownloaded;
					long timeSpend = now - prevTime;
					if (timeSpend > 0) {
						// float rate = ((float) diff / timeSpend) * 1000;
						// downloadSpeed = rate;

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
			Logger.log(e);
		}
	}

	@Override
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
			Logger.log("Restore success");
			this.lastDownloaded = downloaded;
			this.lastProgress = this.progress;
			this.prevTime = System.currentTimeMillis();
			if (allFinished()) {
				assembleAsync();
			} else {
				Logger.log("Starting");
				start();
			}
		} catch (Exception e) {
			Logger.log(e);
			this.errorCode = XDMConstants.RESUME_FAILED;
			listener.downloadFailed(this.id);
			return;
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
		StringBuffer sb = new StringBuffer();
		sb.append(this.length).append("\n");
		sb.append(downloaded).append("\n");
		sb.append(((long) this.totalDuration)).append("\n");
		sb.append(items.size()).append("\n");
		Logger.log("url saved of size:", items.size());
		for (int i = 0; i < items.size(); i++) {
			String url = items.get(i).getUrl();
			Logger.log("Saving url: ", url);
			sb.append(url).append("\n");
		}
		sb.append(chunks.size()).append("\n");
		for (int i = 0; i < chunks.size(); i++) {
			Segment seg = chunks.get(i);
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
			String tmpFileName = String.format("%d.tmp", System.currentTimeMillis());
			File tmp = new File(folder, tmpFileName);
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
		BufferedReader bufferedReader = null;
		chunks = new ArrayList<>();
		File stateFile = new File(folder, "state.txt");
		if (!stateFile.exists()) {
			stateFile = getBackupFile(folder);
			if (stateFile == null) {
				Logger.log("No HlsDownloader saved State",
						stateFile.getAbsolutePath());
				return false;
			}
		}
		try {
			Logger.log("Restoring HlsDownloader State...",
					stateFile.getAbsolutePath());
			bufferedReader = XDMUtils.getBufferedReader(stateFile);
			this.length = Long.parseLong(bufferedReader.readLine());
			this.downloaded = Long.parseLong(bufferedReader.readLine());
			this.totalDuration = Long.parseLong(bufferedReader.readLine());
			int urlCount = Integer.parseInt(bufferedReader.readLine());
			Logger.log("Loading urls:", urlCount);
			for (int i = 0; i < urlCount; i++) {
				String url = bufferedReader.readLine();
				HlsPlaylistItem item = new HlsPlaylistItem();
				item.setUrl(url);
				items.add(item);
				Logger.log("loading url:", url);
			}
			int chunkCount = Integer.parseInt(bufferedReader.readLine());
			for (int i = 0; i < chunkCount; i++) {
				String cid = bufferedReader.readLine();
				long len = Long.parseLong(bufferedReader.readLine());
				long off = Long.parseLong(bufferedReader.readLine());
				long dwn = Long.parseLong(bufferedReader.readLine());
				Segment seg = new SegmentImpl(folder, cid, off, len, dwn);
				seg.setTag("HLS");
				Logger.log("id: ", seg.getId(),
						"\nlength: ", seg.getLength(),
						"\noffset: ", seg.getStartOffset(),
						"\ndownload: ", seg.getDownloaded());
				chunks.add(seg);
			}
			this.lastModified = bufferedReader.readLine();

			String strHasMoreInfo = bufferedReader.readLine();
			if (strHasMoreInfo != null) {
				// read encryption details
				if ("true".equals(strHasMoreInfo)) {
					for (int i = 0; i < urlCount; i++) {
						HlsPlaylistItem item = items.get(i);
						if ("true".equals(bufferedReader.readLine())) {
							item.setKeyUrl(bufferedReader.readLine());
						}
						if ("true".equals(bufferedReader.readLine())) {
							item.setIV(bufferedReader.readLine());
						}
					}

					int keys = Integer.parseInt(bufferedReader.readLine());
					for (int i = 0; i < keys; i++) {
						String keyUrl = bufferedReader.readLine();
						Logger.log("Keydata:", keyUrl);
						String keyData = bufferedReader.readLine();
						byte[] data = Base64.getDecoder().decode(keyData);
						keyMap.put(keyUrl, data);
					}
				}
			}

			// get key iv and other details
			return true;
		} catch (Exception e) {
			Logger.log("Failed to load saved state", e);
		} finally {
			if (bufferedReader != null) {
				try {
					bufferedReader.close();
				} catch (IOException e) {
					Logger.log(e);
				}
			}
		}
		return false;
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

	private String findExtension(String urlStr) {
		String newExtension = null;
		String fileName = XDMUtils.getFileName(urlStr);
		if (!StringUtils.isNullOrEmptyOrBlank(fileName)) {
			String ext = XDMUtils.getExtension(fileName);
			if ((!StringUtils.isNullOrEmptyOrBlank(ext)) && ext.length() > 1) {
				if (!ext.toLowerCase().contains("ts")) {
					newExtension = ext.toLowerCase();
					if (newExtension.contains("m4s")) {
						Logger.log("HLS extension: MP4");
						newExtension = ".mp4";
					}
					if (!newExtension.contains("mp4")) {
						newExtension = ".mkv"; // if extension is not mp4 or ts save it in MKV container, as it might be
						// the case where m3u8 playlist is having wrong file extension
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
			StringBuffer sb = new StringBuffer();
			for (Segment s : chunks) {
				File file = new File(folder, s.getId());
				sb.append("file '")
						.append(file)
						.append("'\r\n");
			}
			OutputStream hlsTextStream = null;
			File hlsFile = new File(folder, id + "-hls.txt");

			try {
				hlsTextStream = new FileOutputStream(hlsFile);
				hlsTextStream.write(sb.toString().getBytes());
				hlsTextStream.close();
			} catch (Exception e) {
				try {
					hlsTextStream.close();
				} catch (Exception e2) {
				}
			}
			this.converting = true;
			List<String> inputFiles = new ArrayList<>();
			inputFiles.add(hlsFile.getAbsolutePath());
			String randomFile = FormatUtilities.getRandomFileName(getOutputFileName(true));
			ffOutFile = new File(getOutputFolder(), randomFile);
			this.ffmpeg = new FFmpeg(inputFiles,
					ffOutFile.getAbsolutePath(),
					this,
					MediaFormats.getSupportedFormats()[outputFormat],
					outputFormat == 0);
			ffmpeg.setHls(true);
			ffmpeg.setHLSDuration(totalDuration);
			int ret = ffmpeg.convert();
			Logger.log("FFmpeg exit code:", ret);

			if (ret != 0) {
				throw new IOException("FFmpeg failed");
			} else {
				long length = ffOutFile.length();
				if (length > 0) {
					this.length = length;
				}
			}

			// delete the original file if exists and rename the temp file to original
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

	// public static void main(String[] args) {
	// try {
	// Thread.sleep(5000);
	// } catch (InterruptedException e) {
	// // TODO Auto-generated catch block
	// e.printStackTrace();
	// }
	//
	// HlsDownloader d2 = new HlsDownloader(UUID.randomUUID().toString(),
	// "C:\\Users\\sd00109548\\Desktop\\temp");
	// d2.metadata = new HlsMetadata();
	// d2.metadata.setUrl("http://localhost:8080/test.m3u8");
	// d2.start();
	// }

}