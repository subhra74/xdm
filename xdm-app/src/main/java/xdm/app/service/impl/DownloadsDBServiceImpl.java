//package xdm.app.service.impl;
//
//import xdm.app.constants.DownloadEntryState;
//import xdm.app.models.DownloadEntry;
//import xdm.app.service.DownloadsDBService;
//import xdman.Config;
//import xdman.util.Logger;
//
//import java.io.*;
//import java.util.*;
//import java.util.concurrent.ConcurrentHashMap;
//
//public class DownloadsDBServiceImpl implements DownloadsDBService {
//  private final Logger logger = Logger.getLogger(DownloadsDBServiceImpl.class);
//  private List<DownloadEntry> records;
//  private Map<Long, Integer> indexMap;
//  private static final String ACT_FILE_NAME = "downloads.dat";
//  private static final String BKP_FILE_NAME = "downloads.bkp";
//  private static final String TMP_FILE_NAME = "downloads.tmp";
//  private File activeFile;
//  private File backupFile;
//  private File tempFile;
////  private Sorter sorter = new Sorter();
//
//  @Override
//  public synchronized void add(DownloadEntry entry) {
//    var index = records.size();
//    this.records.add(entry);
//    indexMap.put(entry.getId(), index);
//  }
//
//  @Override
//  public synchronized Integer indexById(long id) {
//    return indexMap.get(id);
//  }
//
//  @Override
//  public synchronized int size() {
//    return records.size();
//  }
//
//  @Override
//  public synchronized DownloadEntry getByIndex(int index) {
//    if (index < 0 || index >= records.size()) {
//      throw new ArrayIndexOutOfBoundsException(index);
//    }
//    return records.get(index);
//  }
//
//  @Override
//  public synchronized DownloadEntry getById(long id) {
//    var index = indexMap.get(id);
//    if (index == null) {
//      return null;
//    }
//    return records.get(index);
//  }
//
//  @Override
//  public synchronized void load() {
//    activeFile = new File(Config.getInstance().getDataFolder(), ACT_FILE_NAME);
//    backupFile = new File(Config.getInstance().getDataFolder(), BKP_FILE_NAME);
//    tempFile = new File(Config.getInstance().getDataFolder(), TMP_FILE_NAME);
//    if (loadData(activeFile) || loadData(backupFile)) {
//      return;
//    }
//    records = Collections.synchronizedList(new ArrayList<>());
//    indexMap = new ConcurrentHashMap<>();
//  }
//
//  @Override
//  public synchronized void save() {
//    try {
//      if (backupFile.exists()) {
//        backupFile.renameTo(tempFile);
//      }
//      writeToFile(tempFile);
//      if (activeFile.exists()) {
//        activeFile.renameTo(backupFile);
//      }
//      tempFile.renameTo(activeFile);
//    } catch (IOException ex) {
//      logger.error("Error saving download list", ex);
//    }
//  }
//
//  private synchronized void writeToFile(File file) throws IOException {
//    try (var fs = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(file)))) {
//      fs.writeInt(records.size());
//      for (var ent : records) {
//        writeTo(fs, ent);
//      }
//    }
//  }
//
//  private boolean loadData(File file) {
//    if (file.exists()) {
//      try {
//        this.records = loadDataFromFile(file);
//        this.buildIndex();
//        return true;
//      } catch (Exception ex) {
//        logger.error("Unable to download list from active file", ex);
//      }
//    }
//    return false;
//  }
//
//  private List<DownloadEntry> loadDataFromFile(File file) throws IOException {
//    try (var fs = new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
//      var count = fs.readInt();
//      var list = new ArrayList<DownloadEntry>(count + 10);
//      for (var i = 0; i < count; i++) {
//        var ent = new DownloadEntry();
//        readFrom(fs, ent);
//        if (ent.getState() != DownloadEntryState.FINISHED) {
//          ent.setState(DownloadEntryState.PAUSED);
//        }
//        list.add(ent);
//      }
//      return list;
//    }
//  }
//
//  private void buildIndex() {
//    this.indexMap = new ConcurrentHashMap<>(this.records.size() + 10);
//    var index = 0;
//    for (var ent : this.records) {
//      this.indexMap.put(ent.getId(), index++);
//    }
//  }
//
////  public synchronized void sort() {
////    this.records.sort(this.sorter);
////    this.buildIndex();
////    this.save();
////  }
//
//  private void writeTo(DataOutputStream fs, DownloadEntry entry) throws IOException {
//    fs.writeLong(entry.getId());
//    fs.writeInt(entry.getState().ordinal());
//    fs.writeLong(entry.getSize());
//    fs.writeLong(entry.getDownloaded());
//    fs.writeInt(entry.getProgress());
//    fs.writeLong(entry.getDateEpoch());
//    fs.writeUTF(entry.getFileName());
//  }
//
//  private void readFrom(DataInputStream fs, DownloadEntry entry) throws IOException {
//    entry.setId(fs.readLong());
//    entry.setState(DownloadEntryState.values()[fs.readInt()]);
//    entry.setSize(fs.readLong());
//    entry.setDownloaded(fs.readLong());
//    entry.setProgress(fs.readInt());
//    entry.setDateEpoch(fs.readLong());
//    entry.setFileName(fs.readUTF());
//  }
//
////  private static class Sorter implements Comparator<DownloadEntry> {
////
////    @Override
////    public int compare(DownloadEntry o1, DownloadEntry o2) {
////      var sortKey = AppContext.INSTANCE.getConfig().getSortKey();
////      var ascending = AppContext.INSTANCE.getConfig().isSortAscending();
////      int res = 0;
////      switch (sortKey) {
////        case NAME: // sort by name
////          res = o1.getFileName().compareTo(o2.getFileName());
////          break;
////        case SIZE: // sort by size
////          res = Long.compare(o1.getSize(), o2.getSize());
////          break;
////        case DATE: // sort by date
////          res = Long.compare(o1.getDateEpoch(), o2.getDateEpoch());
////          break;
////        case TYPE: // sort by type
////          res = o1.getState().compareTo(o2.getState());
////          break;
////        default:
////          break;
////      }
////      return ascending ? res : -res;
////    }
////  }
//}
