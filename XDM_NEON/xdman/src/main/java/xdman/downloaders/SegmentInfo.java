package xdman.downloaders;

public class SegmentInfo {
	private long start, length, downloaded;

	public final long getStart() {
		return start;
	}

	public final void setStart(long start) {
		this.start = start;
	}

	public final long getLength() {
		return length;
	}

	public final void setLength(long length) {
		this.length = length;
	}

	public final long getDownloaded() {
		return downloaded;
	}

	public final void setDownloaded(long downloaded) {
		this.downloaded = downloaded;
	}
}
