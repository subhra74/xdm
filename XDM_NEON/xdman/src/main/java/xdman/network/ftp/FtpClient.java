package xdman.network.ftp;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;

import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPReply;

import xdman.util.Logger;

public class FtpClient {
	private String url;
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
		Logger.log("Initiate ftp: " + url);
		URI ftpuri;
		try {
			ftpuri = new URI(url);
		} catch (URISyntaxException e) {
			Logger.log(e);
			throw new IOException(e);
		}
		host = ftpuri.getHost();
		port = ftpuri.getPort();
		path = ftpuri.getPath();
		Logger.log("Path: " + path);
		getPath();
		fc = new FTPClient();
		Logger.log("Connecting ftp: " + host + ":" + port);
		if (port > 0)
			fc.connect(host, port);
		else
			fc.connect(host);
		Logger.log("Loggin in");
		fc.login(user, password);
		int reply = fc.getReplyCode();
		if (!FTPReply.isPositiveCompletion(reply)) {
			this.statusCode = 401;
			this.statusMessage = fc.getReplyString();
			fc.disconnect();
			return;
		}
		Logger.log("Going binary");
		fc.setFileType(FTPClient.BINARY_FILE_TYPE);
		reply = fc.getReplyCode();
		if (!FTPReply.isPositiveCompletion(reply)) {
			statusCode = 403;
			statusMessage = fc.getReplyString();
			fc.disconnect();
			return;
		}
		Logger.log("Going passive");
		fc.enterLocalPassiveMode();
		Logger.log("cd " + dir);
		fc.changeWorkingDirectory(dir);
		reply = fc.getReplyCode();
		if (!FTPReply.isPositiveCompletion(reply)) {
			statusCode = 403;
			statusMessage = fc.getReplyString();
			fc.disconnect();
			return;
		}

		Logger.log("Listing files");
		FTPFile files[] = fc.listFiles(dir);
		reply = fc.getReplyCode();
		if (!FTPReply.isPositiveCompletion(reply)) {
			statusCode = 403;
			statusMessage = fc.getReplyString();
			fc.disconnect();
			return;
		}
		for (int i = 0; i < files.length; i++) {
			FTPFile f = files[i];
			if (f.getName().equals(file)) {
				this.length = f.getSize();
				Logger.log("Length retrived: " + length);
				break;
			}
		}
		this.statusCode = 200;

		if (offset > 0 && length > 0) {
			Logger.log("Setting offset");
			fc.setRestartOffset(offset);
			if (!FTPReply.isPositiveCompletion(reply)) {
				throw new IOException(fc.getReplyString());
			}
			this.length -= offset;
			Logger.log("Length after seek: " + length);
			this.statusCode = 206;
		}

		// if (!FTPReply.isPositiveCompletion(reply)) {
		// PasswordAuthentication passwd =
		// Authenticator.requestPasswordAuthentication(fc.getRemoteAddress(),
		// fc.getPassivePort(), "ftp", "", "ftp");
		// if (passwd == null) {
		// if (!XDMApp.getInstance().promptCredential(null, "", false)) {
		// throw new IOException(fc.getReplyString());
		// }
		// passwd = Authenticator.requestPasswordAuthentication(fc.getRemoteAddress(),
		// fc.getPassivePort(), "ftp", "", "ftp");
		// if (passwd == null) {
		// throw new IOException(fc.getReplyString());
		// }
		// }
		// }

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
			Logger.log(e);
		}
	}
}
