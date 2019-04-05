package xdman.network;

public interface ICredentialManager {
	public boolean requestProxyCredential();

	public String getProxyUser();

	public String getProxyPass();

	public boolean requestCredential();

	public String getUser();

	public String getPass();
}
