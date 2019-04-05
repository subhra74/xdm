package xdman.ui.components;

import xdman.downloaders.metadata.HttpMetadata;
import xdman.util.StringUtils;

public class VideoPopupItem {
	private HttpMetadata metadata;
	private String file;
	private String info;
	private long timestamp;

	public final HttpMetadata getMetadata() {
		return metadata;
	}

	public final void setMetadata(HttpMetadata metadata) {
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
