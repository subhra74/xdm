package xdman;

import xdman.downloaders.metadata.HttpMetadata;

public interface LinkRefreshCallback {
	String getId();

	boolean isValidLink(HttpMetadata metadata);
}
