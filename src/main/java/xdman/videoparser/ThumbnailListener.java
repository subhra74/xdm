package xdman.videoparser;

import java.util.Map;

public interface ThumbnailListener {
	void thumbnailsLoaded(long key, String url, String file);
}
