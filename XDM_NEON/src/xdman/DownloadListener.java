package xdman;

public interface DownloadListener {
	public void downloadFinished(String id);

	public void downloadFailed(String id);

	public void downloadStopped(String id);

	public void downloadConfirmed(String id);

	public void downloadUpdated(String id);

	public String getOutputFolder(String id);

	public String getOutputFile(String id, boolean update);
}
