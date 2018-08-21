package xdman;

import xdman.ui.res.StringResource;
import xdman.util.Logger;
import xdman.util.StringUtils;
import xdman.util.XDMUtils;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;

public class QueueManager {
	private File queuesFile = new File(Config.getInstance().getDataFolder(), "queues.txt");
	private static QueueManager _this;
	private LinkedHashMap<String, DownloadQueue> downloadQueueMap;

	private QueueManager() {
		downloadQueueMap = new LinkedHashMap<>();
		loadQueues(queuesFile);
	}

	public static QueueManager getInstance() {
		if (_this == null) {
			_this = new QueueManager();
		}
		return _this;
	}

	public DownloadQueue getQueueById(String queueId) {
		if (queueId == null) {
			return null;
		}
		if (StringUtils.isNullOrEmptyOrBlank(queueId)) {
			return downloadQueueMap.get(DEFAULT_DOWNLOAD_QUEUE_ID);
		}
		DownloadQueue downloadQueue = downloadQueueMap.get(queueId);
		return downloadQueue;
	}

	public Collection<DownloadQueue> getDownloadQueues() {
		return downloadQueueMap.values();
	}

	public DownloadQueue getDefaultQueue() {
		return downloadQueueMap.get(DEFAULT_DOWNLOAD_QUEUE_ID);
	}

	public static final String DEFAULT_DOWNLOAD_QUEUE_ID = "";

	private void loadQueues(File queuesFile) {
		String defaultDownloadQueueName = StringResource.get("DEF_QUEUE");
		DownloadQueue defaultDownloadQueue = new DownloadQueue(DEFAULT_DOWNLOAD_QUEUE_ID,
				defaultDownloadQueueName);
		downloadQueueMap.put(DEFAULT_DOWNLOAD_QUEUE_ID, defaultDownloadQueue);
		if (!queuesFile.exists()) {
			Logger.log("No saved Queues",
					queuesFile.getAbsolutePath());
			return;
		}
		SimpleDateFormat dateFormatter = new SimpleDateFormat("yyyy-MM-dd");
		BufferedReader bufferedReader = null;
		try {
			Logger.log("Loading Queues...",
					queuesFile.getAbsolutePath());
			bufferedReader = XDMUtils.getBufferedReader(queuesFile);
			String str = bufferedReader.readLine();
			int count = Integer.parseInt((str == null ? "0" : str).trim());
			for (int i = 0; i < count; i++) {
				String downloadQueueIdLine = bufferedReader.readLine();
				String downloadQueueId = downloadQueueIdLine == null
						? DEFAULT_DOWNLOAD_QUEUE_ID
						: downloadQueueIdLine.trim();
				String downloadQueueNameLine = bufferedReader.readLine();
				String downloadQueueName = downloadQueueNameLine == null
						? defaultDownloadQueueName
						: downloadQueueNameLine.trim();
				DownloadQueue downloadQueue;
				if (DEFAULT_DOWNLOAD_QUEUE_ID.equals(downloadQueueId)) {
					downloadQueue = defaultDownloadQueue;
				} else {
					downloadQueue = new DownloadQueue(downloadQueueId,
							downloadQueueName);
				}
				String downloadQueueItemsCountLine = bufferedReader.readLine();
				int downloadQueueItemsCount = downloadQueueItemsCountLine == null
						? 0
						: Integer.parseInt(downloadQueueItemsCountLine.trim());
				ArrayDeque<String> queuedItems = downloadQueue.getQueuedItems();
				for (int downloadQueueItemIndex = 0; downloadQueueItemIndex < downloadQueueItemsCount; downloadQueueItemIndex++) {
					String downloadQueueItemLine = bufferedReader.readLine();
					if (downloadQueueItemLine != null) {
						String downloadQueueItem = downloadQueueItemLine.trim();
						queuedItems.add(downloadQueueItem);
					}
				}
				String hasStartTimeLine = bufferedReader.readLine();
				boolean hasStartTime = hasStartTimeLine != null
						&& Integer.parseInt(hasStartTimeLine.trim()) == 1;
				if (hasStartTime) {
					downloadQueue.setStartTime(Long.parseLong(bufferedReader.readLine()));
					boolean hasEndTime = Integer.parseInt(bufferedReader.readLine()) == 1;
					if (hasEndTime) {
						downloadQueue.setEndTime(Long.parseLong(bufferedReader.readLine()));
					}
					boolean isPeriodic = Integer.parseInt(bufferedReader.readLine()) == 1;
					downloadQueue.setPeriodic(isPeriodic);
					if (isPeriodic) {
						downloadQueue.setDayMask(Integer.parseInt(bufferedReader.readLine()));
					} else {
						if (Integer.parseInt(bufferedReader.readLine()) == 1) {
							String ln = bufferedReader.readLine();
							if (ln != null)
								downloadQueue.setExecDate(dateFormatter.parse(ln));
						}
					}
				}
				if (downloadQueue.getQueueId().length() > 0) {
					downloadQueueMap.put(downloadQueue.getQueueId(), downloadQueue);
				}
			}
		} catch (Exception e) {
			Logger.log(e);
		}
		try {
			if (bufferedReader != null) {
				bufferedReader.close();
			}
		} catch (Exception e1) {
			Logger.log(e1);
		}
	}


	public void saveQueues() {
		BufferedWriter bufferedWriter = null;
		String newLine = System.getProperty("line.separator");
		SimpleDateFormat dateFormatter = new SimpleDateFormat("yyyy-MM-dd");
		try {
			Logger.log("Saving Queues to", queuesFile.getAbsolutePath());
			bufferedWriter = XDMUtils.getBufferedWriter(queuesFile,
					false);
			int count = downloadQueueMap.size();
			bufferedWriter.write(count + newLine);
			for (DownloadQueue downloadQueue : downloadQueueMap.values()) {
				if (downloadQueue == null) {
					continue;
				}
				bufferedWriter.write(downloadQueue.getQueueId() + newLine);
				bufferedWriter.write(downloadQueue.getName() + newLine);
				ArrayDeque<String> queuedItems = downloadQueue.getQueuedItems();
				bufferedWriter.write(queuedItems.size() + newLine);
				for (String queuedItem : queuedItems) {
					bufferedWriter.write(queuedItem + newLine);
				}
				if (downloadQueue.getStartTime() != -1) {
					bufferedWriter.write("1" + newLine);
					bufferedWriter.write(downloadQueue.getStartTime() + newLine);
					if (downloadQueue.getEndTime() != -1) {
						bufferedWriter.write("1" + newLine);
						bufferedWriter.write(downloadQueue.getEndTime() + newLine);
					} else {
						bufferedWriter.write("0" + newLine);
					}
					bufferedWriter.write((downloadQueue.isPeriodic() ? 1 : 0) + newLine);
					if (downloadQueue.isPeriodic()) {
						bufferedWriter.write(downloadQueue.getDayMask() + newLine);
					} else {
						if (downloadQueue.getExecDate() != null) {
							bufferedWriter.write("1" + newLine);
							bufferedWriter.write(dateFormatter.format(downloadQueue.getExecDate()) + newLine);
						} else {
							bufferedWriter.write("0" + newLine);
						}
					}
				} else {
					bufferedWriter.write("0" + newLine);
				}
			}
		} catch (Exception e) {
			Logger.log(e);
		}
		if (bufferedWriter != null) {
			try {
				bufferedWriter.close();
			} catch (IOException e) {
				Logger.log(e);
			}
		}
	}

	public void removeQueue(String queueId) {
		DownloadQueue queueById = getQueueById(queueId);
		if (queueById == null)
			return;
		if (queueById.isRunning()) {
			queueById.stop();
		}
		ArrayDeque<String> queuedItems = queueById.getQueuedItems();
		for (String queuedItem : queuedItems) {
			DownloadEntry ent = XDMApp.getInstance().getEntry(queuedItem);
			if (ent != null) {
				ent.setQueueId("");
			}
		}
		downloadQueueMap.remove(queueById);
	}

	private static int queueIndex = 0;

	public DownloadQueue createNewQueue() {
		String queueWord = StringResource.get("Q_WORD");
		queueIndex++;
		String queueName = String.format("%s %d",
				queueWord,
				queueIndex);
		DownloadQueue downloadQueue = new DownloadQueue(UUID.randomUUID().toString(), queueName);
		downloadQueueMap.put(downloadQueue.getQueueId(),
				downloadQueue);
		saveQueues();
		return downloadQueue;
	}

	// check and remove invalid entries from queued item list (invalid entries
	// might appear from corrupt Downloads
	public void fixCorruptEntries(Iterator<String> ids,
	                              XDMApp app) {
		DownloadQueue defaultQueue = getDefaultQueue();
		while (ids.hasNext()) {
			String id = ids.next();
			DownloadEntry downloadEntry = app.getEntry(id);
			String queueId = downloadEntry.getQueueId();
			if (queueId == null
					|| getQueueById(queueId) == null) {
				defaultQueue.getQueuedItems().add(id);
				downloadEntry.setQueueId(DEFAULT_DOWNLOAD_QUEUE_ID);
			}
		}
		for (DownloadQueue downloadQueue : downloadQueueMap.values()) {
			ArrayList<String> corruptIds = new ArrayList<>();
			ArrayDeque<String> queuedItems = downloadQueue.getQueuedItems();
			for (String queuedItem : queuedItems) {
				if (app.getEntry(queuedItem) == null) {
					corruptIds.add(queuedItem);
				}
			}
			queuedItems.removeAll(corruptIds);
		}
	}
}
