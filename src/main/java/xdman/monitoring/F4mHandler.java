package xdman.monitoring;

import xdman.XDMApp;
import xdman.downloaders.metadata.HdsMetadata;
import xdman.downloaders.metadata.manifests.F4MManifest;
import xdman.util.Logger;
import xdman.util.StringUtils;
import xdman.util.XDMUtils;

import java.io.BufferedReader;
import java.io.File;

public class F4mHandler {
	public static boolean handle(File f4mfile, ParsedHookData data) {
		if (!f4mfile.exists()) {
			Logger.log("No saved HDS manifest F4m",
					f4mfile.getAbsolutePath());
			return false;
		}
		BufferedReader bufferedReader = null;
		try {
			StringBuffer buf = new StringBuffer();
			Logger.log("Loading HDS manifest F4m...",
					f4mfile.getAbsolutePath());
			bufferedReader = XDMUtils.getBufferedReader(f4mfile);
			String ln;
			while ((ln = bufferedReader.readLine()) != null) {
				buf.append(ln + "\n");
			}
			Logger.log("HDS manifest validating...");
			if (buf.indexOf("http://ns.adobe.com/f4m/1.0") < 0) {
				Logger.log("No namespace");
				return false;
			}
			if (buf.indexOf("manifest") < 0) {
				Logger.log("No manifest keyword");
				return false;
			}
			if (buf.indexOf("drmAdditional") > 0) {
				Logger.log("DRM");
				return false;
			}
			if (buf.indexOf("media") == 0 || buf.indexOf("href") > 0 || buf.indexOf(".f4m") > 0) {
				Logger.log("Not a valid manifest");
				return false;
			}

			Logger.log("URL:", data.getUrl());
			F4MManifest manifest = new F4MManifest(data.getUrl(), f4mfile.getAbsolutePath());
			long[] bitRates = manifest.getBitRates();
			Logger.log("Bitrates:", bitRates.length);
			for (int i = 0; i < bitRates.length; i++) {
				HdsMetadata metadata = new HdsMetadata();
				metadata.setUrl(data.getUrl());
				metadata.setBitRate((int) bitRates[i]);
				metadata.setHeaders(data.getRequestHeaders());
				String file = data.getFile();
				if (StringUtils.isNullOrEmptyOrBlank(file)) {
					file = XDMUtils.getFileName(data.getUrl());
				}
				String info = String.format("FLV %d bps", bitRates[i]);
				String flvFileName = String.format("%s.flv", file);
				XDMApp.getInstance().addMedia(metadata, flvFileName, info);
			}
			Logger.log("Manifest valid");
			return true;
		} catch (Exception e) {
			Logger.log(e);
			return false;
		} finally {
			if (bufferedReader != null) {
				try {
					bufferedReader.close();
				} catch (Exception e2) {
					Logger.log(e2);
				}
			}
		}
	}
}
