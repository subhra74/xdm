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

package xdman.network.ftp;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;

import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPReply;
import org.tinylog.Logger;

@SuppressWarnings({"unused", "FieldCanBeLocal"})
public class FtpClient {

	private final String url;
	private int statusCode;
	private String statusMessage;
	private long offset;
	private FTPClient fc;
	private String dir, file;
	private int port;
	private String host, path;
	private String user, password;
	private long length;

	public void setOffset(long offset) {
		this.offset = offset;
	}

	public int getStatusCode() {
		return this.statusCode;
	}

	public String getStatusMessage() {
		return this.statusMessage;
	}

	public FtpClient(String url) {
		this.url = url;
	}

	public void connect() throws IOException {
		Logger.info("Initiate ftp: " + url);
		URI ftpuri;
		try {
			ftpuri = new URI(url);
		} catch (URISyntaxException e) {
			Logger.error(e);
			throw new IOException(e);
		}
		host = ftpuri.getHost();
		port = ftpuri.getPort();
		path = ftpuri.getPath();
		Logger.info("Path: " + path);
		getPath();
		fc = new FTPClient();
		Logger.info("Connecting ftp: " + host + ":" + port);
		if (port > 0)
			fc.connect(host, port);
		else
			fc.connect(host);
		Logger.info("Loggin in");
		fc.login(user, password);
		int reply = fc.getReplyCode();
		if (!FTPReply.isPositiveCompletion(reply)) {
			this.statusCode = 401;
			this.statusMessage = fc.getReplyString();
			fc.disconnect();
			return;
		}
		Logger.info("Going binary");
		fc.setFileType(FTPClient.BINARY_FILE_TYPE);
		reply = fc.getReplyCode();
		if (!FTPReply.isPositiveCompletion(reply)) {
			statusCode = 403;
			statusMessage = fc.getReplyString();
			fc.disconnect();
			return;
		}
		Logger.info("Going passive");
		fc.enterLocalPassiveMode();
		Logger.info("cd " + dir);
		fc.changeWorkingDirectory(dir);
		reply = fc.getReplyCode();
		if (!FTPReply.isPositiveCompletion(reply)) {
			statusCode = 403;
			statusMessage = fc.getReplyString();
			fc.disconnect();
			return;
		}

		Logger.info("Listing files");
		FTPFile[] files = fc.listFiles(dir);
		reply = fc.getReplyCode();
		if (!FTPReply.isPositiveCompletion(reply)) {
			statusCode = 403;
			statusMessage = fc.getReplyString();
			fc.disconnect();
			return;
		}
		for (FTPFile f : files) {
			if (f.getName().equals(file)) {
				this.length = f.getSize();
				Logger.info("Length retrived: " + length);
				break;
			}
		}
		this.statusCode = 200;

		if (offset > 0 && length > 0) {
			Logger.info("Setting offset");
			fc.setRestartOffset(offset);
			if (!FTPReply.isPositiveCompletion(reply)) {
				throw new IOException(fc.getReplyString());
			}
			this.length -= offset;
			Logger.info("Length after seek: " + length);
			this.statusCode = 206;
		}

	}

	public void close() throws IOException {
		fc.disconnect();
	}

	public InputStream getInputStream() throws IOException {
		return fc.retrieveFileStream(file);
	}

	private void getPath() {
		int pos = path.lastIndexOf("/");
		if (pos < 0)
			return;
		dir = path.substring(0, pos);
		if (dir.length() < 1)
			dir = "/";
		if (pos == path.length() - 1)
			return;
		if (pos < path.length() - 1) {
			file = path.substring(pos + 1);
		}
	}

	public String getUser() {
		return user;
	}

	public void setUser(String user) {
		this.user = user;
	}

	public String getPassword() {
		return password;
	}

	public void setPassword(String password) {
		this.password = password;
	}

	public long getContentLength() {
		return length;
	}

	public String getHost() {
		return host;
	}

	public int getPort() {
		return fc.getPassivePort();
	}

	public void dispose() {
		try {
			fc.disconnect();
		} catch (Exception e) {
			Logger.error(e);
		}
	}

}
