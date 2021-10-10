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

package xdman.downloaders.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

import org.tinylog.Logger;

import xdman.XDMConstants;
import xdman.downloaders.AbstractChannel;
import xdman.downloaders.Segment;
import xdman.network.ProxyResolver;
import xdman.network.http.HeaderCollection;
import xdman.network.http.HttpClient;
import xdman.network.http.HttpHeader;
import xdman.network.http.JavaClientRequiredException;
import xdman.network.http.JavaHttpClient;
import xdman.network.http.WebProxy;
import xdman.network.http.XDMHttpClient;
import xdman.util.XDMUtils;

public class HttpChannel extends AbstractChannel {

	protected String url;
	protected HeaderCollection headers;
	protected HttpClient hc;
	protected InputStream in;
	protected boolean javaClientRequired;
	protected long firstLength;
	protected long totalLength;
	protected boolean redirected;
	protected String redirectUrl;

	public HttpChannel(Segment chunk, String url, HeaderCollection headers, long totalLength,
			boolean javaClientRequired) {
		super(chunk);
		this.url = url;
		this.headers = headers;
		this.totalLength = totalLength;
		this.javaClientRequired = javaClientRequired;
	}

	@Override
	protected boolean connectImpl() {
		int sleepInterval;
		boolean isRedirect;
		if (stop) {
			closeImpl();
			return false;
		}

		if (!"HLS".equals(chunk.getTag())) {
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
		} else {
			try {
				chunk.reopenStream();
				chunk.resetStream();
				chunk.setDownloaded(0);
			} catch (IOException e) {
				Logger.error(e, "Stream rest failed");
			}
		}
		while (!stop) {
			isRedirect = false;
			try {
				Logger.info("Connecting to: " + url + " " + chunk.getTag());
				WebProxy wp = ProxyResolver.resolve(url);
				if (wp != null) {
					javaClientRequired = true;
				}

				if (javaClientRequired) {
					hc = new JavaHttpClient(url);
				} else {
					hc = new XDMHttpClient(url);
				}

				if (headers != null) {
					Iterator<HttpHeader> headerIt = headers.getAll();
					List<String> cookies = new ArrayList<>();
					while (headerIt.hasNext()) {
						HttpHeader header = headerIt.next();
						if (header.getName().toLowerCase(Locale.ENGLISH).equals("cookie")) {
							cookies.add(header.getValue());
							continue;
						}
						hc.addHeader(header.getName(), header.getValue());
					}
					hc.addHeader("Cookie", String.join(";", cookies));
				}

				long length = chunk.getLength();

				long startOff = chunk.getStartOffset() + chunk.getDownloaded();

				long endOff = startOff + length - chunk.getDownloaded();

				long expectedLength = endOff - startOff;

				if (length > 0 && expectedLength > 0) {
					Logger.info(chunk + " requesting:- " + "Range:" + "bytes=" + startOff + "-" + (endOff - 1));
					hc.setHeader("Range", "bytes=" + startOff + "-" + (endOff - 1));
				} else {
					hc.setHeader("Range", "bytes=0-");
				}

				Logger.info("Initating connection");
				hc.connect();

				if (stop) {
					closeImpl();
					return false;
				}

				int code = hc.getStatusCode();

				Logger.info(chunk + ": " + code);

				if (code >= 300 && code < 400) {
					closeImpl();
					if (totalLength > 0) {
						errorCode = XDMConstants.ERR_INVALID_RESP;
						Logger.info(chunk + " Redirecting twice");
						return false;
					} else {
						url = hc.getResponseHeader("location");
						Logger.info(chunk + " location: " + url);
						if (!url.startsWith("http")) {
							if (!url.startsWith("/")) {
								url = "/" + url;
							}
							url = "http://" + hc.getHost() + url;
						}
						url = url.replace(" ", "%20");
						isRedirect = true;
						redirected = true;
						redirectUrl = url;
						throw new Exception("Redirecting to: " + url);
					}
				}

				if (code != 200 && code != 206 && code != 416 && code != 413 && code != 401 && code != 408
						&& code != 407 && code != 503) {
					errorCode = XDMConstants.ERR_INVALID_RESP;
					closeImpl();
					return false;
				}

				if (code == 407 || code == 401) {
					if (javaClientRequired) {
						Logger.info("asking for password");
						boolean proxy = code == 407;
						if (!chunk.promptCredential(getHostName(hc.getHost()), proxy)) {
							errorCode = XDMConstants.ERR_INVALID_RESP;
							closeImpl();
							return false;
						}
					}
					throw new JavaClientRequiredException();
				}

				if ("T1".equals(chunk.getTag()) || "T2".equals(chunk.getTag())) {
					if ("text/plain".equals(hc.getResponseHeader("content-type"))) {
						ByteArrayOutputStream bout = new ByteArrayOutputStream();
						InputStream inStr = hc.getInputStream();
						Logger.info(inStr);
						long len = hc.getContentLength();
						int read = 0;
						Logger.info("reading url of length: " + len);
						while (true) {
							if (len > 0 && read == len)
								break;
							int x = inStr.read();
							if (x == -1) {
								if (len > 0) {
									throw new IOException("Unable to read url: unexpected EOF");
								} else {
									break;
								}
							}
							read++;
							Logger.info((char) x + "\n");
							bout.write(x);
						}
						url = bout.toString(StandardCharsets.US_ASCII);
						isRedirect = true;
						throw new Exception("Youtube text redirect to: " + url);
					}
				}

				if (((chunk.getDownloaded() + chunk.getStartOffset()) > 0) && code != 206) {
					closeImpl();
					errorCode = XDMConstants.ERR_NO_RESUME;
					return false;
				}

				if ("HLS".equals(chunk.getTag())) {
					firstLength = -1;
				} else {
					firstLength = hc.getContentLength();
				}
				if (length > 0) {
					if (firstLength != expectedLength) {
						Logger.info(chunk + " length mismatch: expected: " + expectedLength + " got: " + firstLength);
						errorCode = XDMConstants.ERR_NO_RESUME;
						closeImpl();
						return false;
					}
				}
				if (hc.getContentLength() > 0 && XDMUtils.getFreeSpace(null) < hc.getContentLength()) {
					Logger.warn("Disk is full");
					errorCode = XDMConstants.DISK_FAIURE;
					closeImpl();
					return false;
				}

				if (!(code == 200 || code == 206)) {
					errorCode = XDMConstants.ERR_INVALID_RESP;
					closeImpl();
					return false;
				}

				in = hc.getInputStream();
				Logger.info("Connection success");
				return true;

			} catch (JavaClientRequiredException e) {
				Logger.error(e, "java client required");
				javaClientRequired = true;
				sleepInterval = 0;
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
		return firstLength;
	}

	@Override
	protected void closeImpl() {
		if (hc != null) {
			hc.dispose();
		}
	}

	public boolean isFinished() {
		if (hc instanceof XDMHttpClient) {
			return ((XDMHttpClient) hc).isFinished();
		} else {
			return false;
		}
	}

	public boolean isJavaClientRequired() {
		return this.javaClientRequired;
	}

	public boolean isRedirected() {
		return redirected;
	}

	public String getRedirectUrl() {
		return redirectUrl;
	}

	public String getHeader(String name) {
		return hc.getResponseHeader(name);
	}

	private String getHostName(String hostPort) {
		return hostPort.split(":")[0];
	}

}
