package xdman.network;

public interface ICredentialManager {
	boolean requestProxyCredential();

	String getProxyUser();

	String getProxyPass();

	boolean requestCredential();

	String getUser();

	String getPass();
}
