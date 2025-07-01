package xdm.ui.components;

import xdm.core.downloaders.metadata.HttpDownloadMetadata;
import xdm.core.util.StringUtils;

public class VideoPopupItem {
	private HttpDownloadMetadata metadata;
	private String file;
	private String info;
	private long timestamp;

	public final HttpDownloadMetadata getMetadata() {
		return metadata;
	}

	public final void setMetadata(HttpDownloadMetadata metadata) {
		this.metadata = metadata;
	}

	public final String getFile() {
		return file;
	}

	public final void setFile(String file) {
		this.file = file;
	}

	public final String getInfo() {
		return info;
	}

	public final void setInfo(String info) {
		this.info = info;
	}

	public final long getTimestamp() {
		return timestamp;
	}

	public final void setTimestamp(long timestamp) {
		this.timestamp = timestamp;
	}

	@Override
	public String toString() {
		if (StringUtils.isNullOrEmptyOrBlank(file)) {
			return "";
		}

		return (StringUtils.isNullOrEmptyOrBlank(info) ? "" : "[ " + info
				+ " ]  ")
				+ (file.length() > 30 ? file.substring(0, 25) + "..." : file);
	}
}
