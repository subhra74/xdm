package xdman.downloaders;

import java.io.IOException;

public interface SegmentListener {
	void chunkInitiated(String id) throws IOException;

	void chunkFailed(String id, String reason);

	boolean chunkComplete(String id) throws IOException;

	void chunkUpdated(String id);

	void synchronize();

	AbstractChannel createChannel(Segment segment);

	void cleanup();

	long getSize();

	boolean shouldCleanup();

	int getActiveChunkCount();

	boolean promptCredential(String msg, boolean proxy);
}
