package xdman.downloaders.metadata;

import xdman.XDMConstants;
import xdman.network.http.HeaderCollection;
import xdman.util.Logger;

public class DashMetadata extends HttpMetadata {
	private String url2;
	private long len1, len2;
	private HeaderCollection headers2;

	@Override
	public HttpMetadata derive() {
		Logger.log("derive dash metadata");
		DashMetadata md = new DashMetadata();
		md.setHeaders(this.getHeaders());
		md.setHeaders2(this.getHeaders2());
		md.setUrl(this.getUrl());
		md.setUrl2(this.getUrl2());
		md.setLen1(this.getLen1());
		md.setLen2(this.getLen2());
		return md;
	}

	public DashMetadata() {
		super();
		this.headers2 = new HeaderCollection();
	}

	protected DashMetadata(String id) {
		super(id);
		this.headers2 = new HeaderCollection();
	}

	@Override
	public int getType() {
		return XDMConstants.DASH;
	}

	public String getUrl2() {
		return url2;
	}

	public void setUrl2(String url2) {
		this.url2 = url2;
	}

	public void setHeaders2(HeaderCollection headers2) {
		this.headers2 = headers2;
	}

	public HeaderCollection getHeaders2() {
		return this.headers2;
	}

	// @Override
	// public void load(BufferedReader br) throws IOException {
	// url = br.readLine();
	// url2 = br.readLine();
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

	// @Override
	// public void save() {
	// FileWriter fw = null;
	// try {
	// File file = new File(Config.getInstance().getMetadataFolder(), id);
	// fw = new FileWriter(file);
	// fw.write(getType() + "\n");
	// fw.write(url + "\n");
	// fw.write(url2 + "\n");
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

	public long getLen1() {
		return len1;
	}

	public void setLen1(long len1) {
		this.len1 = len1;
	}

	public long getLen2() {
		return len2;
	}

	public void setLen2(long len2) {
		this.len2 = len2;
	}
}
