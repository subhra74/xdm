/*
 * Copyright (c)  Subhra Das Gupta
 *
 * This file is part of Xtreme Download Manager.
 *
 * Xtreme Download Manager is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * Xtreme Download Manager is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with Xtream Download Manager; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA
 * 
 */

package xdman;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.PasswordAuthentication;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.tinylog.Logger;

import xdman.util.Base64;
import xdman.util.IOUtils;
import xdman.util.StringUtils;

@SuppressWarnings("unused")
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
