package xdman.network.http;

import java.net.Authenticator;
import java.net.PasswordAuthentication;
import java.net.Socket;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;

import xdman.CredentialManager;
import xdman.network.ICredentialManager;
import xdman.util.Logger;

public class HttpContext {
	private boolean init = false;
	private SSLContext sslContext;
	// private ICredentialManager credentialMgr;
	private static HttpContext _this;

	public static HttpContext getInstance() {
		if (_this == null) {
			_this = new HttpContext();
		}
		return _this;
	}

	public void registerCredentialManager(ICredentialManager mgr) {
		// credentialMgr = mgr;
	}

	public SSLContext getSSLContext() {
		return sslContext;
	}

	private HttpContext() {
		init();
	}

	public void init() {
		if (!init) {
			Logger.log("Context initialized");
			System.setProperty("http.auth.preference", "ntlm");
			try {
				try {
					// sslContext = SSLContext.getInstance("SSLv3");
					sslContext = SSLContext.getInstance("TLS");
				} catch (Exception e) {
					e.printStackTrace();
					sslContext = SSLContext.getInstance("SSL");
				}

				TrustManager[] trustAllCerts = new TrustManager[] { new X509ExtendedTrustManager() {

					@Override
					public void checkClientTrusted(X509Certificate[] chain, String authType)
							throws CertificateException {
						// TODO Auto-generated method stub

					}

					@Override
					public void checkServerTrusted(X509Certificate[] chain, String authType)
							throws CertificateException {
						// TODO Auto-generated method stub

					}

					@Override
					public X509Certificate[] getAcceptedIssuers() {
						// TODO Auto-generated method stub
						return null;
					}

					@Override
					public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket)
							throws CertificateException {
						// TODO Auto-generated method stub

					}

					@Override
					public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
							throws CertificateException {
						// TODO Auto-generated method stub

					}

					@Override
					public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket)
							throws CertificateException {
						// TODO Auto-generated method stub

					}

					@Override
					public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
							throws CertificateException {
						// TODO Auto-generated method stub

					}
				} };

				// TrustManager[] trustAllCerts = new TrustManager[] { new
				// X509TrustManager() {
				// public java.security.cert.X509Certificate[]
				// getAcceptedIssuers() {
				// return new java.security.cert.X509Certificate[] {};
				// }
				//
				// public void checkClientTrusted(X509Certificate[] chain,
				// String authType)
				// throws CertificateException {
				// }
				//
				// public void checkServerTrusted(X509Certificate[] chain,
				// String authType)
				// throws CertificateException {
				// }
				// } };
				sslContext.init(null, trustAllCerts, new SecureRandom());
				HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.getSocketFactory());
			} catch (Exception e) {
				Logger.log(e);
			}

			Authenticator.setDefault(new Authenticator() {
				protected PasswordAuthentication getPasswordAuthentication() {
					System.out.println("Called on " + getRequestorType() + " scheme: " + getRequestingScheme()
							+ " host: " + getRequestingHost() + " url: " + getRequestingURL() + " prompt: "
							+ getRequestingPrompt());
					if (getRequestorType() == RequestorType.SERVER) {

						PasswordAuthentication pauth = CredentialManager.getInstance()
								.getCredentialForHost(getRequestingHost());
						return pauth;
					} else {
						return CredentialManager.getInstance().getCredentialForProxy();
					} // return new
						// PasswordAuthentication(credentialMgr.getUser(),
						// credentialMgr.getPass().toCharArray());
				}
			});
			init = true;
		}
	}
}
