package xdman.downloaders.metadata;

import xdman.Config;
import xdman.XDMConstants;
import xdman.network.http.HeaderCollection;
import xdman.network.http.HttpHeader;
import xdman.util.Logger;
import xdman.util.StringUtils;
import xdman.util.XDMUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Iterator;
import java.util.UUID;

public class HttpMetadata {
	private String id;
	private String url;
	private HeaderCollection headers;
	private long size;
	private String ydlUrl;

	public HttpMetadata derive() {
		Logger.log("derive normal metadata");
		HttpMetadata md = new HttpMetadata();
		md.setHeaders(this.getHeaders());
		md.setUrl(this.getUrl());
		md.setSize(getSize());
		return md;
	}

	public HttpMetadata() {
		this.setId(UUID.randomUUID().toString());
		setHeaders(new HeaderCollection());
	}

	protected HttpMetadata(String id) {
		this.setId(id);
		setHeaders(new HeaderCollection());
	}

	public int getType() {
		if (getUrl().startsWith("ftp")) {
			return XDMConstants.FTP;
		} else {
			return XDMConstants.HTTP;
		}
	}

	// public static HttpMetadata load(String id) {
	// BufferedReader br = null;
	// HttpMetadata metadata = null;
	//
	// try {
	// br = new BufferedReader(new FileReader(new
	// File(Config.getInstance().getMetadataFolder(), id)));
	// int type = Integer.parseInt(br.readLine());
	// switch (type) {
	// case XDMConstants.HTTP:
	// metadata = new HttpMetadata(id);
	// metadata.load(br);
	// break;
	// case XDMConstants.HLS:
	// metadata = new HlsMetadata(id);
	// metadata.load(br);
	// break;
	// case XDMConstants.HDS:
	// metadata = new HdsMetadata(id);
	// metadata.load(br);
	// break;
	// case XDMConstants.DASH:
	// metadata = new DashMetadata(id);
	// metadata.load(br);
	// break;
	// }
	//
	// br.close();
	// } catch (Exception e) {
	// Logger.log(e);
	// if (br != null) {
	// try {
	// br.close();
	// } catch (Exception ex) {
	// }
	// }
	// }
	// return metadata;
	// }

	public final String getUrl() {
		return url;
	}

	public final void setUrl(String url) {
		this.url = url;
	}

	public final HeaderCollection getHeaders() {
		return headers;
	}

	public final void setHeaders(HeaderCollection headers) {
		this.headers = headers;
	}

	public String getId() {
		return id;
	}

	// public void load(BufferedReader br) throws IOException {
	// url = br.readLine();
	// headers = new HeaderCollection();
	// while (true) {
	// String ln = br.readLine();
	// if (ln == null)
	// break;
	// HttpHeader header = HttpHeader.parse(ln);
	// if (header != null) {
	// headers.addHeader(header);
	// }
	// }
	// }

	public static HttpMetadata load(String id) {
		Logger.log("Loading Metadata:", id);
		File metadataFile = new File(Config.getInstance().getMetadataFolder(), id);
		if (!metadataFile.exists()) {
			Logger.log("No saved Metadata",
					metadataFile.getAbsolutePath());
			return null;
		}
		BufferedReader bufferedReader = null;
		HttpMetadata metadata = null;
		int type;
		try {
			Logger.log("Loading Metadata...",
					metadataFile.getAbsolutePath());
			bufferedReader = XDMUtils.getBufferedReader(metadataFile);
			String ln = bufferedReader.readLine();
			if (ln == null) {
				Logger.log("invalid metadata, file is empty");
				return null;
			}
			int index = ln.indexOf(":");
			if (index < 0) {
				Logger.log("invalid metadata file starting with:", ln);
				return null;
			}
			String key = ln.substring(0, index).trim().toLowerCase();
			String val = ln.substring(index + 1).trim();
			if (key.equals("type")) {
				type = Integer.parseInt(val);
				if (type == XDMConstants.HTTP || type == XDMConstants.FTP) {
					metadata = new HttpMetadata(id);
				} else if (type == XDMConstants.HLS) {
					metadata = new HlsMetadata(id);
				} else if (type == XDMConstants.HDS) {
					metadata = new HdsMetadata(id);
				} else if (type == XDMConstants.DASH) {
					metadata = new DashMetadata(id);
				}
			} else {
				Logger.log("invalid metadata file starting with:", ln);
				return null;
			}
			if (metadata != null) {
				while ((ln = bufferedReader.readLine()) != null) {
					index = ln.indexOf(":");
					if (index < 0)
						continue;
					key = ln.substring(0, index).trim().toLowerCase();
					val = ln.substring(index + 1).trim();
					if (key.equals("url")) {
						metadata.setUrl(val);
					}
					if (key.equals("size")) {
						metadata.setSize(Long.parseLong(val));
					}
					if (key.equals("header")) {
						int index2 = val.indexOf(":");
						if (index2 < 0) {
							continue;
						}
						String key1 = val.substring(0, index2).trim();
						String val1 = val.substring(index2 + 1).trim();
						metadata.getHeaders().addHeader(key1, val1);
					}
					if (key.equals("ydlurl")) {
						Logger.log("ydurl:", val);
						metadata.setYdlUrl(val);
					}
					DashMetadata dashMetadata = metadata instanceof DashMetadata
							? (DashMetadata) metadata
							: null;
					if (dashMetadata != null) {
						if (key.equals("header2")) {
							int index2 = val.indexOf(":");
							if (index2 < 0) {
								continue;
							}
							String key1 = val.substring(0, index2).trim();
							String val1 = val.substring(index2 + 1).trim();
							dashMetadata.getHeaders2().addHeader(key1, val1);
						}
						if (key.equals("url2")) {
							dashMetadata.setUrl2(val);
						}
						if (key.equals("len1")) {
							dashMetadata.setLen1(Long.parseLong(val));
						}
						if (key.equals("len2")) {
							dashMetadata.setLen2(Long.parseLong(val));
						}
					}
					HdsMetadata hdsMetadata = metadata instanceof HdsMetadata
							? (HdsMetadata) metadata
							: null;
					if (hdsMetadata != null) {
						if (key.equals("bitrate")) {
							hdsMetadata.setBitRate(Integer.parseInt(val));
						}
					}
				}
			}
			bufferedReader.close();
		} catch (Exception e) {
			Logger.log(e);
		} finally {
			if (bufferedReader != null) {
				try {
					bufferedReader.close();
				} catch (Exception ex) {
					Logger.log(ex);
				}
			}
		}
		return metadata;

	}

	public void save() {
		FileOutputStream fw = null;
		try {
			StringBuilder sb = new StringBuilder();
			if (getUrl() == null)
				throw new NullPointerException("url is null");
			sb.append("type: ").append(getType()).append("\n");
			sb.append("url: ").append(getUrl()).append("\n");
			sb.append("size: ").append(getSize()).append("\n");
			if (getHeaders() != null) {
				Iterator<HttpHeader> headerIterator = getHeaders().getAll();
				while (headerIterator.hasNext()) {
					HttpHeader header = headerIterator.next();
					sb.append("header: ").append(header.getName()).append(":").append(header.getValue()).append("\n");
				}
			}
			if (!StringUtils.isNullOrEmptyOrBlank(getYdlUrl())) {
				sb.append("ydlUrl: ").append(getYdlUrl());
			}

			if (getType() == XDMConstants.DASH) {
				sb.append("url2: ").append(((DashMetadata) this).getUrl2()).append("\n");
				sb.append("len1: ").append(((DashMetadata) this).getLen1()).append("\n");
				sb.append("len2: ").append(((DashMetadata) this).getLen2()).append("\n");
				if (((DashMetadata) this).getHeaders2() != null) {
					Iterator<HttpHeader> headerIterator = ((DashMetadata) this).getHeaders2().getAll();
					while (headerIterator.hasNext()) {
						HttpHeader header = headerIterator.next();
						sb.append("header2: ").append(header.getName()).append(":").append(header.getValue()).append("\n");
					}
				}

			} else if (getType() == XDMConstants.HDS) {
				sb.append("bitrate: " + ((HdsMetadata) this).getBitRate() + "\n");
			}

			File metadataFolder = new File(Config.getInstance().getMetadataFolder());
			if (!metadataFolder.exists()) {
				metadataFolder.mkdirs();
			}
			File file = new File(metadataFolder, getId());
			fw = new FileOutputStream(file);
			fw.write(sb.toString().getBytes());
			fw.close();
		} catch (Exception e) {
			Logger.log(e);
			if (fw != null) {
				try {
					fw.close();
				} catch (Exception ex) {
					Logger.log(e);
				}
			}
		}
	}

	public long getSize() {
		return size;
	}

	public void setSize(long size) {
		this.size = size;
	}

	public String getYdlUrl() {
		return ydlUrl;
	}

	public void setYdlUrl(String ydlUrl) {
		this.ydlUrl = ydlUrl;
	}

	public void setId(String id) {
		this.id = id;
	}
}
