package xdman;

import org.tinylog.Logger;
import xdman.downloaders.metadata.HttpMetadata;
import xdman.util.StringUtils;
import xdman.util.XDMUtils;

import java.net.URL;

public class ClipboardMonitor implements Runnable {

    private static ClipboardMonitor _this;
    private String lastContent;
    private Thread t;

    private ClipboardMonitor() {
    }

    public static ClipboardMonitor getInstance() {
        if (_this == null) {
            _this = new ClipboardMonitor();
        }
        return _this;
    }

    public void startMonitoring() {
        try {
            if (this.t == null) {
                this.t = new Thread(this);
                this.t.start();
            }
        } catch (Exception e) {
            Logger.error(e);
        }

    }

    public void stopMonitoring() {
        try {
            if (this.t != null && this.t.isAlive()) {
                this.t.interrupt();
                this.t = null;
            }
        } catch (Exception e) {
            Logger.error(e);
        }

    }

    @Override
    public void run() {
        try {
            while (true) {
                String txt = XDMUtils.getClipBoardText();
                if (StringUtils.isNullOrEmptyOrBlank(txt)) {
                    return;
                }
                if (!txt.equals(this.lastContent)) {
                    Logger.info("New content: " + txt);
                    this.lastContent = txt;
                    try {
                        new URL(txt);
                        HttpMetadata md = new HttpMetadata();
                        md.setUrl(txt);
                        String file = XDMUtils.getFileName(txt);
                        String ext = XDMUtils.getExtension(file);
                        if (!StringUtils.isNullOrEmptyOrBlank(ext)) {
                            ext = ext.toUpperCase().replace(".", "");
                        }

                        String[] arr = Config.getInstance().getFileExts();
                        boolean found = false;
                        for (String s : arr) {
                            if (s.contains(ext)) {
                                found = true;
                                break;
                            }
                        }
                        if (found) {
                            XDMApp.getInstance().addDownload(md, file);
                        }
                    } catch (Exception e) {
                        Logger.error(e);
                    }
                }
                Thread.sleep(1000);
            }
        } catch (Exception e) {
            Logger.error(e);
        }
    }

}
