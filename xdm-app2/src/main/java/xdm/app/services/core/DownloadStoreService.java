package xdm.app.services.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xdm.app.models.DownloadEntry;
import xdm.app.models.InprogressDownloadEntry;

import java.io.*;
import java.util.*;

public class DownloadStoreService {
  private static final Logger logger = LoggerFactory.getLogger(DownloadStoreService.class);
  private Map<String, Integer> inProgressIdMap = new HashMap<>();
  private Map<String, Integer> finishedIdMap = new HashMap<>();
  private List<InprogressDownloadEntry> inProgressItems = new ArrayList<>();
  private List<DownloadEntry> finishedItems = new ArrayList<>();
  private static final String IN_PROGRESS_NAME = "InProgress.dat";
  private static final String FINISHED_NAME = "Finished.dat";
  private static boolean init = false;
  private static final DownloadStoreService me = new DownloadStoreService();

  private DownloadStoreService() {}

  public static synchronized DownloadStoreService getInstance() {
    if (!init) {
      me.loadData();
      init = true;
    }
    return me;
  }

  public void addNewDownload(InprogressDownloadEntry downloadEntry) {
    var index = inProgressItems.size();
    inProgressItems.add(downloadEntry);
    inProgressIdMap.put(downloadEntry.getId(), index);
    saveData(IN_PROGRESS_NAME, inProgressItems);
  }

  public void markAsFinished(InprogressDownloadEntry downloadEntry) {
    saveData(FINISHED_NAME, finishedItems);
  }

  public int getFinishedDownloadsCount() {
    return this.finishedItems.size();
  }

  public int getInProgressDownloadsCount() {
    return this.inProgressItems.size();
  }

  public DownloadEntry getFinishedDownloadById(String id) {
    var index = this.finishedIdMap.get(id);
    if (index != null) {
      return this.finishedItems.get(index);
    }
    return null;
  }

  public DownloadEntry getFinishedDownloadByIndex(int index) {
    return this.finishedItems.get(index);
  }

  public InprogressDownloadEntry getInProgressDownloadById(String id) {
    var index = this.inProgressIdMap.get(id);
    if (index != null) {
      return this.inProgressItems.get(index);
    }
    return null;
  }

  public InprogressDownloadEntry getInProgressDownloadByIndex(int index) {
    return this.inProgressItems.get(index);
  }

  public void updateDownload(InprogressDownloadEntry entry) {}

  public void deleteById(String id) {}

  public void deleteByIdList(List<String> idList) {}

  private synchronized void saveData(String fileName, List<? extends DownloadEntry> map) {
    var config = AppConfigService.INSTANCE.getConfig();
    var tempFile = new File(config.getConfigDir(), fileName + ".tmp");
    try (var ds = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(tempFile)))) {
      ds.writeInt(map.size());
      for (var entry : map) {
        entry.writeTo(ds);
      }
      var oldFile = new File(config.getConfigDir(), fileName);
      oldFile.delete();
      tempFile.renameTo(oldFile);
    } catch (IOException ex) {
      logger.error(ex.getMessage(), ex);
    }
  }

  private void loadData() {
    var config = AppConfigService.INSTANCE.getConfig();
    var file = new File(config.getConfigDir(), IN_PROGRESS_NAME);
    if (!file.exists()) file = new File(config.getConfigDir(), IN_PROGRESS_NAME + ".tmp");
    loadInProgressItems(file);
    file = new File(config.getConfigDir(), FINISHED_NAME);
    if (!file.exists()) file = new File(config.getConfigDir(), FINISHED_NAME + ".tmp");
    loadFinishedItems(file);
  }

  private void loadInProgressItems(File file) {
    if (!file.exists()) {
      return;
    }
    try (var ds = new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
      var count = ds.readInt();
      this.inProgressItems = new ArrayList<>(count);
      this.inProgressIdMap = new HashMap<>(count);
      for (var i = 0; i < count; i++) {
        var entry = new InprogressDownloadEntry();
        entry.readFrom(ds);
        this.inProgressItems.add(entry);
        this.inProgressIdMap.put(entry.getId(), i);
      }
    } catch (Exception ex) {
      logger.error(ex.getMessage(), ex);
    }
  }

  private void loadFinishedItems(File file) {
    if (!file.exists()) {
      return;
    }
    try (var ds = new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
      var count = ds.readInt();
      this.finishedItems = new ArrayList<>(count);
      this.finishedIdMap = new HashMap<>(count);
      for (var i = 0; i < count; i++) {
        var entry = new DownloadEntry();
        entry.readFrom(ds);
        this.finishedItems.add(entry);
        this.finishedIdMap.put(entry.getId(), i);
      }
    } catch (Exception ex) {
      logger.error(ex.getMessage(), ex);
    }
  }
}
