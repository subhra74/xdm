package xdman;

import xdman.downloaders.metadata.HttpMetadata;

public interface LinkRefreshCallback {
	public String getId();

	public boolean isValidLink(HttpMetadata metadata);
}
