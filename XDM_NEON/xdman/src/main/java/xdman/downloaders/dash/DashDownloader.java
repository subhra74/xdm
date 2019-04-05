package xdman.downloaders.dash;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import xdman.Config;
import xdman.XDMConstants;
import xdman.downloaders.AbstractChannel;
import xdman.downloaders.Downloader;
import xdman.downloaders.Segment;
import xdman.downloaders.SegmentComparator;
import xdman.downloaders.SegmentDetails;
import xdman.downloaders.SegmentImpl;
import xdman.downloaders.SegmentInfo;
import xdman.downloaders.SegmentListener;
import xdman.downloaders.http.HttpChannel;
import xdman.downloaders.metadata.DashMetadata;
import xdman.downloaders.metadata.HttpMetadata;
import xdman.mediaconversion.FFmpeg;
import xdman.mediaconversion.MediaConversionListener;
import xdman.mediaconversion.MediaFormats;
import xdman.util.FormatUtilities;
import xdman.util.Logger;
import xdman.util.StringUtils;
import xdman.util.XDMUtils;

public class DashDownloader extends Downloader implements SegmentListener, MediaConversionListener {
	private DashMetadata metadata;
	private long MIN_CHUNK_SIZE = 256 * 1024;
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
		Logger.log("creating folder " + folder);
		new File(folder).mkdirs();
		this.lastDownloaded = downloaded;
		this.prevTime = System.currentTimeMillis();
		chunks = new ArrayList<Segment>();
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
			// c2.download(this);
		} catch (IOException e) {
			this.errorCode = XDMConstants.RESUME_FAILED;
			this.listener.downloadFailed(id);
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
			Logger.log(id + " is no longer valid chunk");
			return;
		}
		// int code = dc.getCode();
		// Logger.log(id + " code: " + code + " len: " + c.getLength());
		if (isFirstChunk(c)) {
			// HttpChannel dc = (HttpChannel) c.getChannel();
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
			System.out.println("length set - this.len1: " + this.len1 + " this.len2: " + this.len2);
			listener.downloadConfirmed(this.id);
		} else {
			System.out.println("this.len1: " + this.len1 + " this.len2: " + this.len2);
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
			// finish
			finished = true;
			updateStatus();
			try {
				// assembleFinished = false;
				assemble();
				if (!assembleFinished) {
					throw new IOException("Assemble failed");
				}
				// assembleFinished = true;
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
						Logger.log(e);
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
			Logger.log("FFmpeg exit code: " + ret);

			if (ret != 0) {
				throw new IOException("FFmpeg failed");
			} else {
				long length = outFile.length();
				if (length > 0) {
					this.length = length;
				}
				setLastModifiedDate(outFile);
			}

			// delete the original file if exists and rename the temp file to original
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
				this.downloaded = downloaded2;
				if (length > 0) {
					progress = (int) ((downloaded * 100) / length);
					long diff = downloaded - lastDownloaded;
					long timeSpend = now - prevTime;
					if (timeSpend > 0) {
						float rate = ((float) diff / timeSpend) * 1000;
						// downloadSpeed = rate;
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

	long totalAssembled;
	boolean assembling;

	private void assemblePart(File file, ArrayList<Segment> list) throws IOException {
		InputStream in = null;
		OutputStream out = null;
		totalAssembled = 0L;
		assembling = true;
		Logger.log("Combining " + file + " " + list.size());
		try {
			if (stopFlag)
				return;
			byte buf[] = new byte[8192 * 8];
			Logger.log("assembling... " + stopFlag);
			Collections.sort(list, new SegmentComparator());
			// list.sort(new SegmentComparator());
			out = new FileOutputStream(file);
			for (int i = 0; i < list.size(); i++) {
				Logger.log("chunk " + i + " " + stopFlag);
				Segment c = list.get(i);
				in = new FileInputStream(new File(folder, c.getId()));
				long rem = c.getLength();
				while (true) {
					int x = (int) (rem > 0 ? (rem > buf.length ? buf.length : rem) : buf.length);
					int r = in.read(buf, 0, x);
					if (stopFlag) {
						// closeStream(in, out);
						return;
					}

					if (r == -1) {
						if (length > 0) {
							in.close();
							out.close();
							throw new IllegalArgumentException("Assemble EOF");
						} else {
							break;
						}
					}

					out.write(buf, 0, r);
					if (stopFlag) {
						// closeStream(in, out);
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
			// assembleFinished = true;
			// listener.downloadFinished(id);
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
			this.prevTime = System.currentTimeMillis();

			if (allFinished()) {
				assembleAsync();
				return;
			}

			Segment c1 = null;
			for (int i = 0; i < chunks.size(); i++) {
				Segment c = chunks.get(i);
				if (c.isFinished() || c.isActive())
					continue;
				if (c.getTag().equals("T1")) {
					c1 = c;
					break;
				}
			}

			Segment c2 = null;
			for (int i = 0; i < chunks.size(); i++) {
				Segment c = chunks.get(i);
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
					Logger.log(e);
				}
			}

			if (c2 != null) {
				try {
					if (c1 == null) {
						c2.download(this);
					}
				} catch (IOException e) {
					Logger.log(e);
				}
			}

			if (c1 == null && c2 == null) {
				Logger.log("Internal error: no inactive/incomplete chunk found while resuming!");
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
		StringBuffer sb = new StringBuffer();
		sb.append(this.length + "\n");
		sb.append(downloaded + "\n");
		sb.append(this.len1 + "\n");
		sb.append(this.len2 + "\n");
		sb.append(chunks.size() + "\n");
		for (int i = 0; i < chunks.size(); i++) {
			Segment seg = chunks.get(i);
			sb.append(seg.getId() + "\n");
			sb.append(seg.getLength() + "\n");
			sb.append(seg.getStartOffset() + "\n");
			sb.append(seg.getDownloaded() + "\n");
			sb.append(seg.getTag() + "\n");
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
			this.len1 = Long.parseLong(br.readLine());
			this.len2 = Long.parseLong(br.readLine());
			int chunkCount = Integer.parseInt(br.readLine());
			for (int i = 0; i < chunkCount; i++) {
				String cid = XDMUtils.readLineSafe(br);
				long len = Long.parseLong(br.readLine());
				long off = Long.parseLong(br.readLine());
				long dwn = Long.parseLong(br.readLine());
				String tag = XDMUtils.readLineSafe(br);//br.readLine();
				Segment seg = new SegmentImpl(folder, cid, off, len, dwn);
				seg.setTag(tag);
				Logger.log("id: " + seg.getId() + "\nlength: " + seg.getLength() + "\noffset: " + seg.getStartOffset()
						+ "\ndownload: " + seg.getDownloaded());
				chunks.add(seg);
			}
			this.lastModified = XDMUtils.readLineSafe(br);
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

	private synchronized void createChunk() throws IOException {
		if (stopFlag)
			return;
		int activeCount = getActiveChunkCount();
		Logger.log("active count:" + activeCount);
		if (activeCount == MAX_COUNT) {
			Logger.log("Maximum chunk created");
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
		c2.setTag(c.getTag());
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

	// public static void main(String[] args) {
	// DashDownloader d2 = new DashDownloader(UUID.randomUUID().toString(),
	// "C:\\Users\\subhro\\Desktop\\temp");
	// d2.metadata = new DashMetadata();
	// d2.metadata.setUrl(
	// "https://r1---sn-np2a-2o9e.googlevideo.com/videoplayback?mt=1506358716&mv=m&ei=_jXJWev8JJr8oQO5tJnoBg&itag=244&ms=au&sparams=aitags%2Cclen%2Cdur%2Cei%2Cgir%2Cid%2Cinitcwndbps%2Cip%2Cipbits%2Citag%2Ckeepalive%2Clmt%2Cmime%2Cmm%2Cmn%2Cms%2Cmv%2Cpcm2cms%2Cpl%2Crequiressl%2Csource%2Cexpire&keepalive=yes&mm=31&mn=sn-np2a-2o9e&initcwndbps=1576250&expire=1506380382&clen=33592703&mime=video%2Fwebm&pl=24&source=youtube&dur=964.597&lmt=1466914855692296&key=yt6&ipbits=0&aitags=133%2C134%2C135%2C136%2C137%2C160%2C242%2C243%2C244%2C247%2C248%2C278&id=o-AGId65IVcAk9ngAq8kRwiEkXcsZCKAxpi9z1xPXKEwLM&ip=137.59.65.111&requiressl=yes&gir=yes&signature=D851E33F0ADB1514421437792F19FF598591E4F8.25078AEA597B68501AE55991A17F12994060AFB6&pcm2cms=yes&alr=yes&ratebypass=yes&cpn=CLvL20LuzQ5vz5XZ&c=web&cver=html5&range=0-&rn=2&rbuf=0");
	// d2.metadata.setUrl2(
	// "https://r1---sn-np2a-2o9e.googlevideo.com/videoplayback?mt=1506358716&mv=m&ei=_jXJWev8JJr8oQO5tJnoBg&itag=251&ms=au&sparams=clen%2Cdur%2Cei%2Cgir%2Cid%2Cinitcwndbps%2Cip%2Cipbits%2Citag%2Ckeepalive%2Clmt%2Cmime%2Cmm%2Cmn%2Cms%2Cmv%2Cpcm2cms%2Cpl%2Crequiressl%2Csource%2Cexpire&keepalive=yes&mm=31&mn=sn-np2a-2o9e&initcwndbps=1576250&expire=1506380382&clen=11812252&mime=audio%2Fwebm&pl=24&source=youtube&dur=964.641&lmt=1466913413788649&key=yt6&ipbits=0&id=o-AGId65IVcAk9ngAq8kRwiEkXcsZCKAxpi9z1xPXKEwLM&ip=137.59.65.111&requiressl=yes&gir=yes&signature=255A0C5DDABDB26A4DD2AD89D50E3FC7FD1988E5.126AF8828D8BC9E13052C77A26D56F263A255C4A&pcm2cms=yes&alr=yes&ratebypass=yes&cpn=CLvL20LuzQ5vz5XZ&c=web&cver=html5&range=0-&rn=5&rbuf=0");
	// d2.start();
	// }

}
