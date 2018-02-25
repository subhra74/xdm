package xdman.downloaders;

import java.io.IOException;
import java.io.RandomAccessFile;

public interface Segment {
	public long getLength();

	public long getStartOffset();

	public long getDownloaded();

	public RandomAccessFile getOutStream();

	public boolean transferComplete() throws IOException;

	public void transferInitiated() throws IOException;

	public void transferring();

	public void transferFailed(String reason);

	public boolean isFinished();

	public boolean isActive();

	public String getId();

	public void setId(String id);

	public void download(SegmentListener listenre) throws IOException;

	public void setLength(long length);

	public void setDownloaded(long downloaded);

	public void setStartOffset(long offset);

	public void stop();

	public SegmentListener getChunkListener();

	public void dispose();

	public AbstractChannel getChannel();

	public float getTransferRate();

	public int getErrorCode();

	public Object getTag();

	public void setTag(Object obj);

	public void resetStream() throws IOException;

	public void reopenStream() throws IOException;

	public boolean promptCredential(String msg, boolean proxy);

	public void clearChannel();
}
