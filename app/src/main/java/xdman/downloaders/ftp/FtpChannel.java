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

package xdman.downloaders.ftp;

import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.PasswordAuthentication;

import org.tinylog.Logger;

import xdman.XDMConstants;
import xdman.downloaders.AbstractChannel;
import xdman.downloaders.Segment;
import xdman.network.ftp.FtpClient;
import xdman.network.http.JavaClientRequiredException;
import xdman.util.XDMUtils;

@SuppressWarnings("unused")
public class FtpChannel extends AbstractChannel {

	private final String url;
	private FtpClient hc;
	private InputStream in;
	private boolean redirected;
	private long length;

	public FtpChannel(Segment chunk, String url) {
		super(chunk);
		this.url = url;
	}

	@Override
	protected boolean connectImpl() {
		int sleepInterval;
		boolean isRedirect;
		if (stop) {
			closeImpl();
			return false;
		}

		if (chunk.getLength() < 0 && chunk.getDownloaded() > 0) {
			errorCode = XDMConstants.ERR_NO_RESUME;
			closeImpl();
			Logger.warn("server does not support resuming");
			return false;
		}
		try {
			chunk.reopenStream();
		} catch (IOException e) {
			Logger.error(e);
			closeImpl();
			errorCode = XDMConstants.ERR_NO_RESUME;
			return false;
		}

		PasswordAuthentication passwd = new PasswordAuthentication("anonymous", "anonymous".toCharArray());

		while (!stop) {
			isRedirect = false;
			try {
				Logger.info("ftp Connecting to: " + url + " " + chunk.getTag() + " offset "
						+ (chunk.getStartOffset() + chunk.getDownloaded()));
				hc = new FtpClient(url);

				long startOff = chunk.getStartOffset() + chunk.getDownloaded();

				if (startOff > 0) {
					hc.setOffset(startOff);
				}

				hc.setUser(passwd.getUserName());
				hc.setPassword(new String(passwd.getPassword()));

				hc.connect();

				if (stop) {
					closeImpl();
					return false;
				}

				int code = hc.getStatusCode();

				Logger.info(chunk + ": " + code);

				if (code != 200 && code != 206 && code != 416 && code != 413 && code != 401 && code != 408
						&& code != 407 && code != 503) {
					errorCode = XDMConstants.ERR_INVALID_RESP;
					closeImpl();
					return false;
				}

				if (code == 407 || code == 401) {
					Logger.info("asking for password");
					boolean proxy = code == 407;
					passwd = Authenticator.requestPasswordAuthentication(null, hc.getPort(), "ftp", "", "ftp");

					if (passwd == null) {
						if (!chunk.promptCredential(hc.getHost(), proxy)) {
							errorCode = XDMConstants.ERR_INVALID_RESP;
							closeImpl();
							return false;
						} else {
							passwd = Authenticator.requestPasswordAuthentication(null, hc.getPort(), "ftp", "", "ftp");
							Logger.info("Passwd: " + passwd);
							throw new JavaClientRequiredException();
						}
					}
				}

				if (stop) {
					closeImpl();
					return false;
				}

				if (((chunk.getDownloaded() + chunk.getStartOffset()) > 0) && code != 206) {
					closeImpl();
					errorCode = XDMConstants.ERR_NO_RESUME;
					return false;
				}

				length = hc.getContentLength();

				if (hc.getContentLength() > 0 && XDMUtils.getFreeSpace(null) < hc.getContentLength()) {
					Logger.warn("Disk is full");
					errorCode = XDMConstants.DISK_FAIURE;
					closeImpl();
					return false;
				}

				in = hc.getInputStream();
				Logger.info("Connection success");
				return true;

			} catch (JavaClientRequiredException e) {
				sleepInterval = 0;
				Logger.error(e);
			} catch (Exception e) {
				Logger.error(e, chunk.toString());
				if (isRedirect) {
					closeImpl();
					continue;
				}
				sleepInterval = 5000;
			}

			closeImpl();

			try {
				Thread.sleep(sleepInterval);
			} catch (Exception e) {
				Logger.error(e);
			}
		}

		Logger.warn("return as " + errorCode);

		return false;
	}

	@Override
	protected InputStream getInputStreamImpl() {
		return in;
	}

	@Override
	protected long getLengthImpl() {
		return length;
	}

	@Override
	protected void closeImpl() {
		if (hc != null) {
			hc.dispose();
		}
	}

	public boolean isFinished() {
		return false;
	}

	public boolean isRedirected() {
		return redirected;
	}

	public String getRedirectUrl() {
		return null;
	}

}
