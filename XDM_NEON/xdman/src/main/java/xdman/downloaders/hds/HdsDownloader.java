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
import java.util.Arrays;
import java.util.UUID;

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
import xdman.util.Logger;
import xdman.util.StringUtils;
import xdman.util.XDMUtils;

public class HdsDownloader extends Downloader implements SegmentListener, MediaConversionListener {
	private HdsMetadata metadata;
	private ArrayList<String> urlList;
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
		urlList = new ArrayList<String>();
		chunks = new ArrayList<Segment>();
		this.eta = "---";
	}

	public void start() {
		Logger.log("creating folder " + folder);
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
					// assembleFinished = false;
					assemble();
					if (!assembleFinished) {
						throw new IOException("Assemble not finished successfully");
					}
					Logger.log("********Download finished*********");
					updateStatus();
					// assembleFinished = true;
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
		Logger.log("Create manifest channel");
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
			Logger.log("Total duration " + totalDuration);
			ArrayList<String> urls = mf.getMediaUrls();
			if (urls.size() < 1) {
				Logger.log("Manifest contains no media");
				return false;
			}
			if (urlList.size() > 0 && urlList.size() != urls.size()) {
				Logger.log("Manifest media count mismatch- expected: " + urlList.size() + " got: " + urls.size());
				return false;
			}
			if (urlList.size() > 0) {
				urlList.clear();
			}
			urlList.addAll(urls);

			String newExtension = null;

			if (chunks.size() < 1) {
				for (int i = 0; i < urlList.size(); i++) {
					if (newExtension == null && outputFormat == 0) {
						newExtension = findExtension(urlList.get(i));
						Logger.log("HDS: found new extension: " + newExtension);
						if (newExtension != null) {
							this.newFileName = getOutputFileName(false).replace(".flv", newExtension);

						} else {
							newExtension = ".flv";// just to skip the whole file
													// ext extraction
						}
					}

					Logger.log("HDS: Newfile name: " + this.newFileName);

					Segment s2 = new SegmentImpl(this, folder);
					s2.setTag("HLS");
					s2.setLength(-1);
					Logger.log("Adding chunk: " + s2);
					chunks.add(s2);
				}
			}
			return true;
		} catch (Exception e) {
			Logger.log(e);
			return false;
		}

	}

	private synchronized void processSegments() {
		int activeCount = getActiveChunkCount();
		Logger.log("active: " + activeCount);
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
					progress = (int) ((processedSegments * 100) / chunks.size());
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
					//long diff = downloaded - lastDownloaded;
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
		sb.append(this.length + "\n");
		sb.append(downloaded + "\n");
		sb.append(((long) this.totalDuration) + "\n");
		sb.append(urlList.size() + "\n");
		for (int i = 0; i < urlList.size(); i++) {
			String url = urlList.get(i);
			sb.append(url + "\n");
		}
		sb.append(chunks.size() + "\n");
		for (int i = 0; i < chunks.size(); i++) {
			Segment seg = chunks.get(i);
			sb.append(seg.getId() + "\n");
			if (seg.isFinished()) {
				sb.append(seg.getLength() + "\n");
				sb.append(seg.getStartOffset() + "\n");
				sb.append(seg.getDownloaded() + "\n");
			} else {
				sb.append("-1\n");
				sb.append(seg.getStartOffset() + "\n");
				sb.append(seg.getDownloaded() + "\n");
			}
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
				Logger.log("id: " + seg.getId() + "\nlength: " + seg.getLength() + "\noffset: " + seg.getStartOffset()
						+ "\ndownload: " + seg.getDownloaded());
				chunks.add(seg);
			}
			this.lastModified = XDMUtils.readLineSafe(br);// br.readLine();
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
				}
			}
		}
		return newExtension;
	}

	private void assemble() throws IOException {
		InputStream in = null;
		OutputStream out = null;
		totalAssembled = 0L;
		assembling = true;
		assembleFinished = false;
		File ffOutFile = null;
		File outFile = null;

		XDMUtils.mkdirs(getOutputFolder());

		// File outFile = new File(outputFormat == 0 ? getOutputFolder() : folder,
		// getOutputFileName(true));

		String outFileName = (outputFormat == 0 ? UUID.randomUUID() + "_" + getOutputFileName(true)
				: UUID.randomUUID().toString());
		String outputFolder = (outputFormat == 0 ? getOutputFolder() : folder);
		outFile = new File(outputFolder, outFileName);

		try {
			if (stopFlag)
				return;
			Logger.log("assembling... ");
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

					long boxsize = readInt32(in);
					streamPos += 4;
					String box_type = readStringBytes(in, 4);
					streamPos += 4;
					if (boxsize == 1) {
						boxsize = readInt64(in) - 16;
						streamPos += 8;
					} else {
						boxsize -= 8;
					}
					if (box_type.equals("mdat")) {
						long boxsz = boxsize;
						while (boxsz > 0) {
							if (stopFlag)
								return;
							int c = (int) (boxsz > b.length ? b.length : boxsz);
							int x = in.read(b, 0, c);
							if (x == -1)
								throw new IOException("Unexpected EOF");
							out.write(b, 0, x);
							boxsz -= x;
							totalAssembled += x;
							long now = System.currentTimeMillis();
							if (now - lastUpdated > 1000) {
								updateStatus();
								lastUpdated = now;
							}
						}
					} else {
						in.skip(boxsize);
					}

					streamPos += boxsize;
				}
				in.close();
			}
			out.close();

			Logger.log("Output format: " + outputFormat);

			if (outputFormat != 0) {

				this.converting = true;
				ffOutFile = new File(getOutputFolder(), UUID.randomUUID() + "_" + getOutputFileName(true));

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
					setLastModifiedDate(ffOutFile);
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

			assembleFinished = true;
		} catch (Exception e) {
			Logger.log(e);
		} finally {
			try {
				out.close();
			} catch (Exception e2) {
			}
			try {
				in.close();
			} catch (Exception e2) {
			}

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
		long iValLo = (long) ((bytesData[3] & 0xff) + ((long) (bytesData[2] & 0xff) * 256));
		long iValHi = (long) ((bytesData[1] & 0xff) + ((long) (bytesData[0] & 0xff) * 256));
		long iVal = iValLo + (iValHi * 65536);
		return iVal;
	}

	private long readInt64(InputStream s) throws IOException {
		long iValHi = readInt32(s);
		long iValLo = readInt32(s);

		long iVal = iValLo + (iValHi * 4294967296L);
		return iVal;
	}

	private String readStringBytes(InputStream s, long len) throws IOException {
		StringBuilder resultValue = new StringBuilder(4);
		for (int i = 0; i < len; i++) {
			resultValue.append((char) s.read());
		}
		return resultValue.toString();
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
