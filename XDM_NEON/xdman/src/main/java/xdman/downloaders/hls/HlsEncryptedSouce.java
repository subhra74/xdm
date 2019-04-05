package xdman.downloaders.hls;

public interface HlsEncryptedSouce {
	public boolean hasKey(String keyUrl);

	public void setKey(String keyUrl, byte[] data);

	public String getIV(String url);

	public byte[] getKey(String keyUrl);
}
