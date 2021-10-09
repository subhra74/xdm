package xdman;

import xdman.util.FormatUtilities;
import xdman.util.StringUtils;

public class DownloadEntry {
    private String id, file, folder;
    private int state, category;
    private long size, downloaded;
    private long date;
    private int progress;
    private String dateStr;
    private String queueId;
    private boolean startedByUser;
    private int outputFormatIndex;// 0 orginal
    private String tempFolder;

    public DownloadEntry() {
    }

    public String getId() {
        return id;
    }

    public String getDateStr() {
        return this.dateStr;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getFile() {
        return this.file;
    }

    public void setFile(String file) {
        this.file = file;
    }

    public int getState() {
        return this.state;
    }

    public void setState(int state) {
        this.state = state;
    }

    public int getCategory() {
        return this.category;
    }

    public void setCategory(int category) {
        this.category = category;
    }

    public long getSize() {
        return this.size;
    }

    public void setSize(long size) {
        this.size = size;
    }

    public long getDownloaded() {
        return this.downloaded;
    }

    public void setDownloaded(long downloaded) {
        this.downloaded = downloaded;
    }

    public long getDate() {
        return this.date;
    }

    public void setDate(long date) {
        this.date = date;
        this.dateStr = FormatUtilities.formatDate(date);
    }

    public int getProgress() {
        return this.progress;
    }

    public void setProgress(int progress) {
        this.progress = progress;
    }

    String getFolder() {
        return this.folder;
    }

    public void setFolder(String folder) {
        this.folder = folder;
    }

    public final String getQueueId() {
        return this.queueId;
    }

    public final void setQueueId(String queueId) {
        this.queueId = queueId;
    }

    public final void setDateStr(String dateStr) {
        this.dateStr = dateStr;
    }

    public final boolean isStartedByUser() {
        return this.startedByUser;
    }

    public final void setStartedByUser(boolean startedByUser) {
        this.startedByUser = startedByUser;
    }

    public final int getOutputFormatIndex() {
        return this.outputFormatIndex;
    }

    public final void setOutputFormatIndex(int outputFormatIndex) {
        this.outputFormatIndex = outputFormatIndex;
    }

    public String getTempFolder() {
        if (StringUtils.isNullOrEmptyOrBlank(this.tempFolder)) {
			this.tempFolder = Config.getInstance().getTemporaryFolder();
        }
        return this.tempFolder;
    }

    public void setTempFolder(String tempFolder) {
        this.tempFolder = tempFolder;
    }
}
