package xdman.downloaders.http;

import xdman.XDMConstants;
import xdman.downloaders.AbstractChannel;
import xdman.downloaders.Segment;
import xdman.downloaders.SegmentDownloader;
import xdman.downloaders.metadata.HttpMetadata;
import xdman.util.Logger;
import xdman.util.MimeUtil;
import xdman.util.NetUtils;
import xdman.util.StringUtils;
import xdman.util.XDMUtils;

public class HttpDownloader extends SegmentDownloader {
	private HttpMetadata metadata;
	private String newFileName;
	private boolean isJavaClientRequired;

	public HttpDownloader(String id, String folder, HttpMetadata metadata) {
		super(id, folder);
		this.metadata = metadata;
	}

	@Override
	public AbstractChannel createChannel(Segment segment) {
		StringBuffer buf = new StringBuffer();
		metadata.getHeaders().appendToBuffer(buf);
		System.out.println("Headers all: " + buf);
		HttpChannel hc = new HttpChannel(segment, metadata.getUrl(), metadata.getHeaders(), length,
				isJavaClientRequired);
		return hc;
	}

	@Override
	public int getType() {
		return XDMConstants.HTTP;
	}

	@Override
	public boolean isFileNameChanged() {
		Logger.log("Checking for filename change " + (newFileName != null));
		return newFileName != null;
	}

	@Override
	public String getNewFile() {
		return newFileName;
	}

	@Override
	protected void chunkConfirmed(Segment c) {
		// logic
		// if the response has html content type and no attachment
		// no matter what is the target file extension, if any, will be changed to html.
		// If the download
		// has video conversion option, then conversion format will be removed.
		// in case of having an attachment, attachment extension will be used
		String oldFileName = getOutputFileName(false);
		HttpChannel hc = (HttpChannel) c.getChannel();
		this.isJavaClientRequired = hc.isJavaClientRequired();
		super.getLastModifiedDate(c);
		if (hc.isRedirected()) {
			metadata.setUrl(hc.getRedirectUrl());
			metadata.save();

			// newFileName = XDMUtils.getFileName(metadata.getUrl());
			//
			// if (outputFormat == 0) {
			// newFileName = XDMUtils.getFileName(metadata.getUrl());
			// Logger.log("set new filename: " + newFileName);
			// Logger.log("new file name: " + newFileName);
			// }
		}

		if ((hc.getHeader("content-type") + "").contains("text/html")) {
			if (hc.getHeader("content-disposition") == null) {
				newFileName = XDMUtils.getFileNameWithoutExtension(oldFileName) + ".html";
				outputFormat = 0;
			}
		}

		boolean nameSet = false;
		String contentDispositionHeader = hc.getHeader("content-disposition");
		if (contentDispositionHeader != null) {
			if (outputFormat == 0) {
				System.out.println("checking content disposition");
				String name = NetUtils.getNameFromContentDisposition(contentDispositionHeader);
				if (name != null) {
					this.newFileName = name;
					nameSet = true;
					Logger.log("set new filename: " + newFileName);
				}
			}
		}
		// if ((hc.getHeader("content-type") + "").contains("/html")) {
		// if (this.newFileName != null) {
		// String upperStr = this.newFileName.toUpperCase();
		// if (!(upperStr.endsWith(".HTML") || upperStr.endsWith(".HTM"))) {
		// outputFormat = 0;
		// this.newFileName += ".html";
		// Logger.log("set new filename: " + newFileName);
		// }
		// }
		// }
		if (!nameSet) {
			String ext = XDMUtils.getExtension(oldFileName);
			if (StringUtils.isNullOrEmptyOrBlank(ext)) {
				String newExt = MimeUtil.getFileExt(hc.getHeader("content-type"));
				if (newExt != null) {
					newFileName = oldFileName + "." + newExt;
				}
			}
			Logger.log("new filename: " + newFileName);
		}
	}

	@Override
	public HttpMetadata getMetadata() {
		return this.metadata;
	}

}
