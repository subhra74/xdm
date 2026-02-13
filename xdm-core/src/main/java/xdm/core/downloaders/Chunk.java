//package xdm.core.downloaders;
//
//import xdm.core.constants.ErrorCode;
//
//import java.io.IOException;
//import java.io.RandomAccessFile;
//
//public interface Chunk {
//  long getLastTakeOverTime();
//
//  void setLastTakeOverTime(long time);
//
//  long getLength();
//
//  long getStartOffset();
//
//  long getDownloaded();
//
//  RandomAccessFile getOutStream();
//
//  boolean transferComplete() throws IOException;
//
//  void transferInitiated() throws IOException;
//
//  void transferring(int bytes);
//
//  void transferFailed(String reason);
//
//  boolean isFinished();
//
//  boolean isActive();
//
//  long getId();
//
//  void setId(long id);
//
//  void download(ChunkUpdateListener listenre) throws IOException;
//
//  void setLength(long length);
//
//  void setDownloaded(long downloaded);
//
//  void setStartOffset(long offset);
//
//  void stop();
//
//  ChunkUpdateListener getChunkListener();
//
//  void dispose();
//
//  AbstractChunkRetriever getChunkRetriever();
//
//  ErrorCode getErrorCode();
//
//  Object getTag();
//
//  void setTag(Object obj);
//
//  void resetStream() throws IOException;
//
//  void reopenStream() throws IOException;
//
//  boolean promptCredential(String msg, boolean proxy);
//
//  void clearChannel();
//}
