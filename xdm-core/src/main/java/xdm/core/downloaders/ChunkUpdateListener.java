//package xdm.core.downloaders;
//
//import java.io.IOException;
//
//public interface ChunkUpdateListener {
//  public void chunkInitiated(long id) throws IOException;
//
//  public void chunkFailed(long id, String reason);
//
//  public boolean chunkComplete(long id) throws IOException;
//
//  public void chunkUpdated(long id, int bytes);
//
//  public void synchronize();
//
//  public AbstractChunkRetriever createChannel(Chunk chunk);
//
//  public void cleanup();
//
//  public long getSize();
//
//  public boolean shouldCleanup();
//
//  public int getActiveChunkCount();
//
//  public boolean promptCredential(String msg, boolean proxy);
//}
