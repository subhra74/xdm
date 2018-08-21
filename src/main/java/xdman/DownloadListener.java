package xdman;

public interface DownloadListener {
	void downloadFinished(String id);

	void downloadFailed(String id);

	void downloadStopped(String id);

	void downloadConfirmed(String id);

	void downloadUpdated(String id);

	String getOutputFolder(String id);

	String getOutputFile(String id, boolean update);
}
