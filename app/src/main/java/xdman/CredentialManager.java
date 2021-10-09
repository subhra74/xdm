package xdman;

import org.tinylog.Logger;
import xdman.util.Base64;
import xdman.util.IOUtils;
import xdman.util.StringUtils;

import java.io.*;
import java.net.PasswordAuthentication;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

public class CredentialManager {
    private static CredentialManager _this;
    private final Map<String, PasswordAuthentication> savedCredentials;
    private final Map<String, PasswordAuthentication> cachedCredentials;

    private CredentialManager() {
		this.savedCredentials = new HashMap<>();
		this.cachedCredentials = new HashMap<>();
		this.load();
    }

    public static CredentialManager getInstance() {
        if (_this == null) {
            _this = new CredentialManager();
        }
        return _this;
    }

    public Set<Entry<String, PasswordAuthentication>> getCredentials() {
        return this.savedCredentials.entrySet();
    }

    public PasswordAuthentication getCredentialForHost(String host) {
        Logger.info("Getting cred for " + host);
        PasswordAuthentication authentication = this.savedCredentials.get(host);
		return authentication == null ? this.cachedCredentials.get(host) : authentication;
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

    public void addCredentialForHost(String host, PasswordAuthentication authentication, boolean save) {
        if (save) {
			this.savedCredentials.put(host, authentication);
        } else {
			this.cachedCredentials.put(host, authentication);
        }
    }

    public void addCredentialForHost(String host, String user, String pass, boolean save) {
		this.addCredentialForHost(host, new PasswordAuthentication(user, pass.toCharArray()), save);
    }

    public void addCredentialForHost(String host, String user, String pass) {
		this.addCredentialForHost(host, new PasswordAuthentication(user, pass.toCharArray()), false);
    }

    public void addCredentialForHost(String host, PasswordAuthentication authentication) {
		this.addCredentialForHost(host, authentication, false);
    }

    private void load() {
        BufferedReader br = null;
        try {
            File f = new File(Config.getInstance().getDataFolder(), ".credentials");
            if (!f.exists()) {
                Logger.warn("No saved credentials");
                return;
            }
            br = new BufferedReader(new InputStreamReader(new FileInputStream(f)));
            if (!this.savedCredentials.isEmpty())
				this.savedCredentials.clear();
            while (true) {
                String ln = br.readLine();
                if (ln == null)
                    break;
                String str = new String(Base64.decode(ln));
                String[] arr = str.split("\n");
                if (arr.length < 2)
                    continue;
				this.savedCredentials.put(arr[0],
                        new PasswordAuthentication(arr[1], arr.length == 3 ? arr[2].toCharArray() : new char[0]));
            }
        } catch (Exception e) {
            Logger.error(e);
        } finally {
			IOUtils.closeFlow(br);
        }
    }

    public void save() {
        StringBuilder buf = new StringBuilder();
		for (String key : this.savedCredentials.keySet()) {
			PasswordAuthentication authentication = this.savedCredentials.get(key);
			String str = key + "\n" + authentication.getUserName() + "\n" + new String(authentication.getPassword());
			String str64 = Base64.encode(str.getBytes());
			buf.append(str64).append("\n");
		}
        OutputStream out = null;
        try {
            File f = new File(Config.getInstance().getDataFolder(), ".credentials");
            out = new FileOutputStream(f);
            out.write(buf.toString().getBytes());
        } catch (Exception e) {
            Logger.error(e);
        } finally {
			IOUtils.closeFlow(out);
        }
    }

    public void removeSavedCredential(String host) {
		this.savedCredentials.remove(host);
		this.save();
    }
}
