package xdman.downloaders.metadata;

import xdman.XDMConstants;
import xdman.util.Logger;

public class HlsMetadata extends HttpMetadata {
	public HlsMetadata() {
		super();
	}

	@Override
	public int getType() {
		return XDMConstants.HLS;
	}

	protected HlsMetadata(String id) {
		super(id);
	}

	@Override
	public HttpMetadata derive() {
		Logger.log("derive hls metadata");
		HlsMetadata md = new HlsMetadata();
		md.setHeaders(this.getHeaders());
		md.setUrl(this.getUrl());
		return md;
	}

	// @Override
	// public void save() {
	// FileWriter fw = null;
	// try {
	// File file = new File(Config.getInstance().getMetadataFolder(), id);
	// fw = new FileWriter(file);
	// fw.write(getType() + "\n");
	// fw.write(url + "\n");
	// Iterator<HttpHeader> headerIterator = headers.getAll();
	// while (headerIterator.hasNext()) {
	// HttpHeader header = headerIterator.next();
	// fw.write(header.getName() + ":" + header.getValue() + "\n");
	// }
	// fw.close();
	// } catch (Exception e) {
	// Logger.log(e);
	// if (fw != null) {
	// try {
	// fw.close();
	// } catch (Exception ex) {
	// }
	// }
	// }
	// }
}
