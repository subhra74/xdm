package xdm;

import xdm.core.downloaders.metadata.HttpDownloadMetadata;

public interface LinkRefreshCallback {
	public String getId();

	public boolean isValidLink(HttpDownloadMetadata metadata);
}
