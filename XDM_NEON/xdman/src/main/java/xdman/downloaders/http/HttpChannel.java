package xdman.downloaders.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.Iterator;

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
import xdman.util.Logger;
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
			// it may be known from first connection
			// if java client is required
			boolean javaClientRequired) {
		super(chunk);
		this.url = url;
		this.headers = headers;
		this.totalLength = totalLength;
		this.javaClientRequired = javaClientRequired;
	}

	@Override
	protected boolean connectImpl() {
		int sleepInterval = 0;
		boolean isRedirect = false;
		if (stop) {
			closeImpl();
			return false;
		}

		if (!"HLS".equals(chunk.getTag())) {
			if (chunk.getLength() < 0 && chunk.getDownloaded() > 0) {
				errorCode = XDMConstants.ERR_NO_RESUME;
				closeImpl();
				Logger.log("server does not support resuming");
				return false;
			}
			try {
				chunk.reopenStream();
			} catch (IOException e) {
				Logger.log(e);
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
				Logger.log("Stream rest failed");
				Logger.log(e);
			}
		}
		while (!stop) {
			isRedirect = false;
			try {
				Logger.log("Connecting to: " + url + " " + chunk.getTag());
				WebProxy wp = ProxyResolver.resolve(url);
				if (wp != null) {
					javaClientRequired = true;
				}

				if (javaClientRequired) {
					hc = new JavaHttpClient(url);
				} else {
					// this.socketDataRemaining = -1;
					hc = new XDMHttpClient(url);
				}

				
				if (headers != null) {
					Iterator<HttpHeader> headerIt = headers.getAll();
					while (headerIt.hasNext()) {
						HttpHeader header = headerIt.next();
						hc.addHeader(header.getName(), header.getValue());
					}
				}

				long length = chunk.getLength();

				// hc.setHeader("user-agent", "Mozilla/5.0 (Windows NT 10.0;
				// rv:51.0) Gecko/20100101 Firefox/51.0");

				long startOff = chunk.getStartOffset() + chunk.getDownloaded();

				long endOff = startOff + length - chunk.getDownloaded();

				long expectedLength = endOff - startOff;

				if (length > 0 && expectedLength > 0) {
					Logger.log(chunk + " requesting:- " + "Range:" + "bytes=" + startOff + "-" + (endOff - 1));
					hc.setHeader("Range", "bytes=" + startOff + "-" + (endOff - 1));
				} else {
					hc.setHeader("Range", "bytes=0-");
				}

				Logger.log("Initating connection");
				hc.connect();

				if (stop) {
					closeImpl();
					return false;
				}

				int code = hc.getStatusCode();

				Logger.log(chunk + ": " + code);

				if (code >= 300 && code < 400) {
					closeImpl();
					if (totalLength > 0) {
						errorCode = XDMConstants.ERR_INVALID_RESP;
						Logger.log(chunk + " Redirecting twice");
						return false;
					} else {
						url = hc.getResponseHeader("location");
						Logger.log(chunk + " location: " + url);
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
						Logger.log("asking for password");
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
						System.out.println(inStr);
						long len = hc.getContentLength();
						int read = 0;
						System.out.println("reading url of length: " + len);
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
							System.out.print((char) x);
							bout.write(x);
						}
						byte[] buf = bout.toByteArray();
						url = new String(buf, Charset.forName("ASCII"));
						isRedirect = true;
						throw new Exception("Youtube text redirect to: " + url);
					}
				}

				if (((chunk.getDownloaded() + chunk.getStartOffset()) > 0) && code != 206) {
					closeImpl();
					errorCode = XDMConstants.ERR_NO_RESUME;
					return false;
				}

				// first length will be used if this is the first thread
				// otherwise its value will be lost
				if ("HLS".equals(chunk.getTag())) {
					firstLength = -1;
				} else {
					firstLength = hc.getContentLength();
				}
				// this.socketDataRemaining = firstLength;
				// we should check content range header instead of this
				if (length > 0) {
					if (firstLength != expectedLength)
					// if (chunk.getStartOffset() + chunk.getDownloaded()
					// + firstLength != totalLength)
					{
						Logger.log(chunk + " length mismatch: expected: " + expectedLength + " got: " + firstLength);
						errorCode = XDMConstants.ERR_NO_RESUME;
						closeImpl();
						return false;
					}
				}
				if (hc.getContentLength() > 0 && XDMUtils.getFreeSpace(null) < hc.getContentLength()) {
					Logger.log("Disk is full");
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
				Logger.log("Connection success");
				return true;

			} catch (JavaClientRequiredException e) {
				Logger.log("java client required");
				javaClientRequired = true;
				sleepInterval = 0;
			} catch (Exception e) {
				Logger.log(chunk);
				Logger.log(e);
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
			}
		}

		Logger.log("return as " + errorCode);

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
