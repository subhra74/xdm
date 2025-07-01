package xdm.core;

public interface InteractiveCredentialProvider {
  boolean promptCredential(long id, String msg, boolean proxy);
}
