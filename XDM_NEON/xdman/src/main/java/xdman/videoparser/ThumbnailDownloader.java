package xdman.videoparser;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import xdman.Config;
import xdman.network.http.JavaHttpClient;
import xdman.util.Logger;
import xdman.util.XDMUtils;

public class ThumbnailDownloader implements Runnable {
	private String[] thumbnails;
	private Thread t;
	private boolean stop;
	private ThumbnailListener listener;
	private long instanceKey;

	public void download() {
		t = new Thread(this);
		t.start();
	}

	public ThumbnailDownloader(ArrayList<String> list, ThumbnailListener listener, long instanceKey) {
		thumbnails = new String[list.size()];
		int i = 0;
		for (String str : list) {
			thumbnails[i++] = str;
		}
		this.listener = listener;
		this.instanceKey = instanceKey;
	}

	public void stop() {
		stop = true;
		this.listener = null;
	}

	public void removeThumbnailListener() {
		this.listener = null;
	}

	@Override
	public void run() {
		List<String> list = new ArrayList<>();
		try {
			if (thumbnails == null)
				return;
			for (int i = 0; i < thumbnails.length; i++) {
				if (stop)
					return;
				String url = thumbnails[i];
				String file = downloadReal(url);
				if (stop)
					return;
				if (file != null) {
					if (listener != null) {
						listener.thumbnailsLoaded(instanceKey, url, file);
					}
					list.add(file);
				}
			}
		} catch (Exception e) {
			Logger.log(e);
		} finally {
			if (stop) {
				for (String file : list) {
					new File(file).delete();
				}
			}
		}
	}

	private String downloadReal(String url) {
		JavaHttpClient client = null;
		File tmpFile = new File(Config.getInstance().getTemporaryFolder(), UUID.randomUUID().toString());
		FileOutputStream out = null;
		try {
			client = new JavaHttpClient(url);
			client.setFollowRedirect(true);
			client.connect();
			int resp = client.getStatusCode();
			if (stop) {
				return null;
			}
			Logger.log("manifest download response: " + resp);
			if (resp == 200 || resp == 206) {
				InputStream in = client.getInputStream();
				long len = client.getContentLength();
				out = new FileOutputStream(tmpFile);
				XDMUtils.copyStream(in, out, len);
				Logger.log("thumbnail download successfull");
				return tmpFile.getAbsolutePath();
			}
		} catch (Exception e) {
			Logger.log(e);
		} finally {
			try {
				client.dispose();
			} catch (Exception e) {
			}
			try {
				if (out != null) {
					out.close();
				}
			} catch (Exception e) {
			}
			if (stop) {
				tmpFile.delete();
			}
		}
		return null;
	}
}
