package xdm.core.downloaders.ftp;//package xdm.downloaders.ftp;
//
//import xdm.XDMConstants;
//import xdm.downloaders.AbstractChunkRetriever;
//import xdm.downloaders.Chunk;
//import xdm.downloaders.AbstractSegmentedDownloader;
//import xdm.downloaders.metadata.HttpDownloadMetadata;
//
//public class FtpDownloader extends AbstractSegmentedDownloader {
//	private HttpDownloadMetadata metadata;
//	//private String newFileName;
//
//	public FtpDownloader(String id, String folder, HttpDownloadMetadata metadata) {
//		super(id, folder);
//		this.metadata = metadata;
//	}
//
//	@Override
//	public AbstractChunkRetriever createChannel(Chunk chunk) {
//		FtpChannel hc = new FtpChannel(chunk, metadata.getUrl());
//		return hc;
//	}
//
//	@Override
//	public int getType() {
//		return XDMConstants.FTP;
//	}
//
//	@Override
//	public boolean isFileNameChanged() {
//		return false;
//		/*
//		 * Logger.log("Checking for filename change " + (newFileName != null)); return
//		 * newFileName != null;
//		 */
//	}
//
//	@Override
//	public String getNewFile() {
//		return null;//newFileName;
//	}
//
//	@Override
//	protected void chunkConfirmed(Chunk c) {
//
//	}
//
//	@Override
//	public HttpDownloadMetadata getMetadata() {
//		return this.metadata;
//	}
//
//}
