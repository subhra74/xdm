package xdman.downloaders;

import java.io.IOException;
import java.io.RandomAccessFile;

public interface Segment {
	long getLength();

	long getStartOffset();

	long getDownloaded();

	RandomAccessFile getOutStream();

	boolean transferComplete() throws IOException;

	void transferInitiated() throws IOException;

	void transferring();

	void transferFailed(String reason);

	boolean isFinished();

	boolean isActive();

	String getId();

	void setId(String id);

	void download(SegmentListener listenre) throws IOException;

	void setLength(long length);

	void setDownloaded(long downloaded);

	void setStartOffset(long offset);

	void stop();

	SegmentListener getChunkListener();

	void dispose();

	AbstractChannel getChannel();

	float getTransferRate();

	int getErrorCode();

	Object getTag();

	void setTag(Object obj);

	void resetStream() throws IOException;

	void reopenStream() throws IOException;

	boolean promptCredential(String msg, boolean proxy);

	void clearChannel();
}
