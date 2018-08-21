package xdman.downloaders.hls;

public interface HlsEncryptedSouce {
	boolean hasKey(String keyUrl);

	void setKey(String keyUrl, byte[] data);

	String getIV(String url);

	byte[] getKey(String keyUrl);
}
