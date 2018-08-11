package xdman;

import xdman.util.Base64;
import xdman.util.Logger;
import xdman.util.StringUtils;

import java.io.*;
import java.net.PasswordAuthentication;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

public class CredentialManager {
	private File credentialsFile = new File(Config.getInstance().getDataFolder(), ".credentials");
	private Map<String, PasswordAuthentication> savedCredentials;
	private Map<String, PasswordAuthentication> cachedCredentials;

	private static CredentialManager _this;

	public static CredentialManager getInstance() {
		if (_this == null) {
			_this = new CredentialManager();
		}
		return _this;
	}

	public Set<Entry<String, PasswordAuthentication>> getCredentials() {
		return savedCredentials.entrySet();
	}

	public PasswordAuthentication getCredentialForHost(String host) {
		System.out.println("Getting cred for " + host);
		PasswordAuthentication pauth = savedCredentials.get(host);
		if (pauth == null) {
			return cachedCredentials.get(host);
		}
		return pauth;
	}

	public PasswordAuthentication getCredentialForProxy() {
		if (!StringUtils.isNullOrEmptyOrBlank(Config.getInstance().getProxyUser())) {
			return new PasswordAuthentication(Config.getInstance().getProxyUser(),
					Config.getInstance().getProxyPass() == null ? new char[0]
							: Config.getInstance().getProxyPass().toCharArray());
		} else {
			return null;
		}
	}

	private CredentialManager() {
		savedCredentials = new HashMap<>();
		cachedCredentials = new HashMap<>();
		load();
	}

	public void addCredentialForHost(String host, PasswordAuthentication pauth, boolean save) {
		if (save) {
			savedCredentials.put(host, pauth);
		} else {
			cachedCredentials.put(host, pauth);
		}
	}

	public void addCredentialForHost(String host, String user, String pass, boolean save) {
		addCredentialForHost(host, new PasswordAuthentication(user, pass.toCharArray()), save);
	}

	public void addCredentialForHost(String host, String user, String pass) {
		addCredentialForHost(host, new PasswordAuthentication(user, pass.toCharArray()), false);
	}

	public void addCredentialForHost(String host, PasswordAuthentication pauth) {
		addCredentialForHost(host, pauth, false);
	}

	private void load() {
		BufferedReader br = null;
		try {
			if (!credentialsFile.exists()) {
				Logger.log("No saved credentials");
				return;
			}
			br = new BufferedReader(new InputStreamReader(new FileInputStream(credentialsFile)));
			if (!savedCredentials.isEmpty())
				savedCredentials.clear();
			while (true) {
				String ln = br.readLine();
				if (ln == null)
					break;
				String str = new String(Base64.decode(ln));
				String[] arr = str.split("\n");
				if (arr.length < 2)
					continue;
				savedCredentials.put(arr[0],
						new PasswordAuthentication(arr[1], arr.length == 3 ? arr[2].toCharArray() : new char[0]));
			}
		} catch (Exception e) {
			Logger.log(e);
		} finally {
			try {
				if (br != null) {
					br.close();
				}
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}

	public void save() {
		StringBuilder buf = new StringBuilder();
		Iterator<String> savedKeyIterator = savedCredentials.keySet().iterator();
		while (savedKeyIterator.hasNext()) {
			String key = savedKeyIterator.next();
			PasswordAuthentication pauth = savedCredentials.get(key);
			String str = key + "\n" + pauth.getUserName() + "\n" + new String(pauth.getPassword());
			String str64 = Base64.encode(str.getBytes());
			buf.append(str64 + "\n");
		}
		OutputStream out = null;
		try {
			out = new FileOutputStream(credentialsFile);
			out.write(buf.toString().getBytes());
		} catch (Exception e) {
			Logger.log(e);
		} finally {
			try {
				if (out != null) {
					out.close();
				}
			} catch (Exception e) {

			}
		}
	}

	public void removeSavedCredential(String host) {
		savedCredentials.remove(host);
		save();
	}
}
