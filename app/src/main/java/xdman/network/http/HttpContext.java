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

package xdman.network.http;

import java.net.Authenticator;
import java.net.PasswordAuthentication;
import java.net.Socket;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;

import org.tinylog.Logger;

import xdman.CredentialManager;
import xdman.network.ICredentialManager;

@SuppressWarnings("unused")
public class HttpContext {

	private boolean init = false;
	private SSLContext sslContext;
	private static HttpContext _this;

	public static HttpContext getInstance() {
		if (_this == null) {
			_this = new HttpContext();
		}
		return _this;
	}

	public void registerCredentialManager(ICredentialManager mgr) {
	}

	public SSLContext getSSLContext() {
		return sslContext;
	}

	private HttpContext() {
		init();
	}

	public void init() {
		if (!init) {
			Logger.info("Context initialized");
			System.setProperty("http.auth.preference", "ntlm");
			try {
				try {
					sslContext = SSLContext.getInstance("TLS");
				} catch (Exception e) {
					Logger.error(e);
					sslContext = SSLContext.getInstance("SSL");
				}

				TrustManager[] trustAllCerts = new TrustManager[] { new X509ExtendedTrustManager() {

					@Override
					public void checkClientTrusted(X509Certificate[] chain, String authType) {

					}

					@Override
					public void checkServerTrusted(X509Certificate[] chain, String authType) {

					}

					@Override
					public X509Certificate[] getAcceptedIssuers() {
						return null;
					}

					@Override
					public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) {

					}

					@Override
					public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {

					}

					@Override
					public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) {

					}

					@Override
					public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {

					}
				} };

				sslContext.init(null, trustAllCerts, new SecureRandom());
				HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.getSocketFactory());
			} catch (Exception e) {
				Logger.error(e);
			}

			Authenticator.setDefault(new Authenticator() {
				protected PasswordAuthentication getPasswordAuthentication() {
					Logger.info("Called on " + getRequestorType() + " scheme: " + getRequestingScheme() + " host: "
							+ getRequestingHost() + " url: " + getRequestingURL() + " prompt: "
							+ getRequestingPrompt());
					if (getRequestorType() == RequestorType.SERVER) {

						PasswordAuthentication pauth = CredentialManager.getInstance()
								.getCredentialForHost(getRequestingHost());
						return pauth;
					} else {
						return CredentialManager.getInstance().getCredentialForProxy();
					} // return new
				}
			});
			init = true;
		}
	}

}
