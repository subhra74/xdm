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

package xdman.downloaders.hls;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.security.Key;
import java.security.spec.AlgorithmParameterSpec;
import java.util.Iterator;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.tinylog.Logger;

import xdman.XDMConstants;
import xdman.downloaders.Segment;
import xdman.downloaders.http.HttpChannel;
import xdman.network.ProxyResolver;
import xdman.network.http.HeaderCollection;
import xdman.network.http.HttpHeader;
import xdman.network.http.JavaClientRequiredException;
import xdman.network.http.JavaHttpClient;
import xdman.network.http.WebProxy;
import xdman.network.http.XDMHttpClient;
import xdman.util.XDMUtils;

public class EncryptedHlsChannel extends HttpChannel {

	private final String keyUrl;
	private final String mediaUrl;
	private final HlsEncryptedSource source;

	public EncryptedHlsChannel(Segment chunk, String url, HeaderCollection headers, long totalLength,
							   boolean javaClientRequired, HlsEncryptedSource source, String keyurl) {
		super(chunk, url, headers, totalLength, javaClientRequired);
		this.source = source;
		this.url = this.mediaUrl = url;
		this.keyUrl = keyurl;
	}

	@Override
	protected boolean connectImpl() {
		int sleepInterval;
		boolean isRedirect;
		if (stop) {
			closeImpl();
			return false;
		}

		try {
			chunk.reopenStream();
			chunk.resetStream();
			chunk.setDownloaded(0);
		} catch (IOException e) {
			Logger.error(e, "Stream rest failed");
		}

		boolean isKey = !source.hasKey(keyUrl);
		if (isKey) {
			Logger.info("Retrieving key");
			url = keyUrl;
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
					while (headerIt.hasNext()) {
						HttpHeader header = headerIt.next();
						hc.addHeader(header.getName(), header.getValue());
					}
				}

				Logger.info("Initiating connection");
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
						if (!chunk.promptCredential(hc.getHost(), proxy)) {
							errorCode = XDMConstants.ERR_INVALID_RESP;
							closeImpl();
							return false;
						}
					}
					throw new JavaClientRequiredException();
				}

				if (code == 200 || code == 206) {
					if (isKey) {
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
							bout.write(x);
						}
						byte[] buf = bout.toByteArray();
						isKey = false;
						this.url = mediaUrl;
						source.setKey(keyUrl, buf);
						isRedirect = true;
						throw new Exception("Youtube text redirect to: " + url);
					}
				}

				firstLength = -1;

				if (hc.getContentLength() > 0 && XDMUtils.getFreeSpace(null) < hc.getContentLength()) {
					Logger.warn("Disk is full");
					errorCode = XDMConstants.DISK_FAILURE;
					closeImpl();
					return false;
				}

				if (!(code == 200 || code == 206)) {
					errorCode = XDMConstants.ERR_INVALID_RESP;
					closeImpl();
					return false;
				}

				in = hc.getInputStream();
				String ivStr = source.getIV(mediaUrl);
				byte[] key = source.getKey(keyUrl);
				try {
					in = getCypherStream(in, key, getIV(ivStr));
				} catch (Exception e) {
					Logger.error(e);
					errorCode = XDMConstants.ERR_INVALID_RESP;
					closeImpl();
					return false;
				}
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

		Logger.info("return as " + errorCode);

		return false;
	}

	private byte[] getIV(String str) {
		if (str.toLowerCase().startsWith("0x")) {
			str = str.substring(2);
		}
		byte[] ivData = new BigInteger(str, 16).toByteArray();
		byte[] ivDataWithPadding = new byte[16];
		int offset = ivData.length > 16 ? ivData.length - 16 : 0;
		System.arraycopy(ivData, offset, ivDataWithPadding, ivDataWithPadding.length - ivData.length + offset,
				ivData.length - offset);
		return ivDataWithPadding;
	}

	private InputStream getCypherStream(InputStream in, byte[] key, byte[] iv) throws Exception {
		Cipher cipher;
		cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
		Key cipherKey = new SecretKeySpec(key, "AES");
		AlgorithmParameterSpec cipherIV = new IvParameterSpec(iv);
		cipher.init(Cipher.DECRYPT_MODE, cipherKey, cipherIV);
		return new CipherInputStream(in, cipher);
	}

}
