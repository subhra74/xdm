package xdman.videoparser;

import org.tinylog.Logger;
import xdman.Config;
import xdman.network.http.JavaHttpClient;
import xdman.util.IOUtils;
import xdman.util.XDMUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class ThumbnailDownloader implements Runnable {
    private final String[] thumbnails;
    private final long instanceKey;
    private boolean stop;
    private ThumbnailListener listener;

    public ThumbnailDownloader(ArrayList<String> list, ThumbnailListener listener, long instanceKey) {
        this.thumbnails = new String[list.size()];
        int i = 0;
        for (String str : list) {
            this.thumbnails[i++] = str;
        }
        this.listener = listener;
        this.instanceKey = instanceKey;
    }

    public void download() {
        Thread t = new Thread(this);
        t.start();
    }

    public void stop() {
        this.stop = true;
        this.listener = null;
    }

    public void removeThumbnailListener() {
        this.listener = null;
    }

    @Override
    public void run() {
        List<String> list = new ArrayList<>();
        try {
            if (this.thumbnails == null)
                return;
            for (String thumbnail : this.thumbnails) {
                if (this.stop)
                    return;
                String file = downloadReal(thumbnail);
                if (this.stop)
                    return;
                if (file != null) {
                    if (this.listener != null) {
                        this.listener.thumbnailsLoaded(this.instanceKey, thumbnail, file);
                    }
                    list.add(file);
                }
            }
        } catch (Exception e) {
            Logger.error(e);
        } finally {
            if (this.stop) {
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
            if (this.stop) {
                return null;
            }
            Logger.info("manifest download response: " + resp);
            if (resp == 200 || resp == 206) {
                InputStream in = client.getInputStream();
                long len = client.getContentLength();
                out = new FileOutputStream(tmpFile);
                XDMUtils.copyStream(in, out, len);
                Logger.info("thumbnail download successful");
                return tmpFile.getAbsolutePath();
            }
        } catch (Exception e) {
            Logger.error(e);
        } finally {
            if (client != null) {
                try {
                    client.dispose();
                } catch (Exception e) {
                    Logger.error(e);
                }
            }
            IOUtils.closeFlow(out);
            if (this.stop) {
                tmpFile.delete();
            }
        }
        return null;
    }
}
