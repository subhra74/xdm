package xdman;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.UUID;

import xdman.ui.res.StringResource;
import xdman.util.Logger;
import xdman.util.XDMUtils;

public class QueueManager {
	private static QueueManager _this;
	private ArrayList<DownloadQueue> queueList;

	private QueueManager() {
		queueList = new ArrayList<DownloadQueue>();
		loadQueues();
	}

	public static QueueManager getInstance() {
		if (_this == null) {
			_this = new QueueManager();
		}
		return _this;
	}

	public DownloadQueue getQueueById(String queueId) {
		if (queueId == null)
			return null;
		if (queueId.length() < 1) {
			return queueList.get(0);
		}
		for (int i = 0; i < queueList.size(); i++) {
			DownloadQueue q = queueList.get(i);
			if (q.getQueueId().equals(queueId)) {
				return q;
			}
		}
		return null;
	}

	public ArrayList<DownloadQueue> getQueueList() {
		return queueList;
	}

	public DownloadQueue getDefaultQueue() {
		return queueList.get(0);
	}

	private void loadQueues() {
		File file = new File(Config.getInstance().getDataFolder(), "queues.txt");

		DownloadQueue defaultQ = new DownloadQueue("", StringResource.get("DEF_QUEUE"));
		queueList.add(defaultQ);
		if (!file.exists()) {
			return;
		}

		SimpleDateFormat dateFormatter = new SimpleDateFormat("yyyy-MM-dd");

		try (BufferedReader reader = new BufferedReader(
				new InputStreamReader(new FileInputStream(file), Charset.forName("UTF-8")))) {

			String str = reader.readLine();
			int count = Integer.parseInt((str == null ? "0" : str).trim());
			for (int i = 0; i < count; i++) {
				String strLn = reader.readLine();
				if (strLn == null) {
					throw new IOException("Unexpected EOF");
				}
				String id = strLn.trim();
				strLn = reader.readLine();
				if (strLn == null) {
					throw new IOException("Unexpected EOF");
				}
				String name = strLn.trim();
				DownloadQueue queue = null;
				if ("".equals(id)) {
					queue = defaultQ;
				} else {
					queue = new DownloadQueue(id, name);
				}
				int c = Integer.parseInt(XDMUtils.readLineSafe(reader).trim());
				for (int j = 0; j < c; j++) {
					queue.getQueuedItems().add(XDMUtils.readLineSafe(reader).trim());
				}
				boolean hasStartTime = Integer.parseInt(reader.readLine()) == 1;
				if (hasStartTime) {
					queue.setStartTime(Long.parseLong(reader.readLine()));
					boolean hasEndTime = Integer.parseInt(reader.readLine()) == 1;
					if (hasEndTime) {
						queue.setEndTime(Long.parseLong(reader.readLine()));
					}
					boolean isPeriodic = Integer.parseInt(reader.readLine()) == 1;
					queue.setPeriodic(isPeriodic);
					if (isPeriodic) {
						queue.setDayMask(Integer.parseInt(reader.readLine()));
					} else {
						if (Integer.parseInt(reader.readLine()) == 1) {
							String ln = reader.readLine();
							if (ln != null)
								queue.setExecDate(dateFormatter.parse(ln));
						}
					}
				}
				if (queue.getQueueId().length() > 0) {
					queueList.add(queue);
				}
			}
		} catch (Exception e) {
			Logger.log(e);
		}
	}

	public void saveQueues() {
		int count = queueList.size();
		File file = new File(Config.getInstance().getDataFolder(), "queues.txt");
		BufferedWriter writer = null;
		String newLine = System.getProperty("line.separator");
		SimpleDateFormat dateFormatter = new SimpleDateFormat("yyyy-MM-dd");
		try {
			writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file), Charset.forName("UTF-8")));
			writer.write(count + newLine);
			for (int i = 0; i < count; i++) {
				DownloadQueue queue = queueList.get(i);
				writer.write(queue.getQueueId() + newLine);
				writer.write(queue.getName() + newLine);
				ArrayList<String> queuedItems = queue.getQueuedItems();
				writer.write(queuedItems.size() + newLine);
				for (int j = 0; j < queuedItems.size(); j++) {
					writer.write(queuedItems.get(j) + newLine);
				}
				if (queue.getStartTime() != -1) {
					writer.write("1" + newLine);
					writer.write(queue.getStartTime() + newLine);
					if (queue.getEndTime() != -1) {
						writer.write("1" + newLine);
						writer.write(queue.getEndTime() + newLine);
					} else {
						writer.write("0" + newLine);
					}
					writer.write((queue.isPeriodic() ? 1 : 0) + newLine);
					if (queue.isPeriodic()) {
						writer.write(queue.getDayMask() + newLine);
					} else {
						if (queue.getExecDate() != null) {
							writer.write("1" + newLine);
							writer.write(dateFormatter.format(queue.getExecDate()) + newLine);
						} else {
							writer.write("0" + newLine);
						}
					}
				} else {
					writer.write("0" + newLine);
				}
			}
		} catch (Exception e) {
			Logger.log(e);
		}
		if (writer != null) {
			try {
				writer.close();
			} catch (IOException e) {
				Logger.log(e);
			}
		}
	}

	public void removeQueue(String queueId) {
		DownloadQueue q = getQueueById(queueId);
		if (q == null)
			return;
		if (q.isRunning()) {
			q.stop();
		}
		for (int i = 0; i < q.getQueuedItems().size(); i++) {
			String id = q.getQueuedItems().get(i);
			DownloadEntry ent = XDMApp.getInstance().getEntry(id);
			if (ent != null) {
				ent.setQueueId("");
			}
		}
		queueList.remove(q);
	}

	public DownloadQueue createNewQueue() {
		int counter = 1;
		String name = "";
		String qw = StringResource.get("Q_WORD");
		while (true) {
			boolean found = false;
			counter++;
			for (DownloadQueue qi : queueList) {
				if ("".equals(qi.getQueueId()))
					continue;
				if ((qw + " " + counter).equals(qi.getName())) {
					found = true;
					break;
				}
			}
			if (!found) {
				name = qw + " " + counter;
				break;
			}
		}
		DownloadQueue q = new DownloadQueue(UUID.randomUUID().toString(), name);
		queueList.add(q);
		saveQueues();
		return q;
	}

	// check and remove invalid entries from queued item list (invalid entries
	// might appear from corrupt download list
	public void fixCorruptEntries(Iterator<String> ids, XDMApp app) {
		DownloadQueue dfq = getDefaultQueue();
		while (ids.hasNext()) {
			String id = ids.next();
			DownloadEntry ent = app.getEntry(id);
			String qId = ent.getQueueId();
			if (qId == null || getQueueById(qId) == null) {
				dfq.getQueuedItems().add(id);
				ent.setQueueId("");
			}
		}
		for (int i = 0; i < queueList.size(); i++) {
			DownloadQueue q = queueList.get(i);
			ArrayList<String> corruptIds = new ArrayList<String>();
			for (int k = 0; k < q.getQueuedItems().size(); k++) {
				String id = q.getQueuedItems().get(k);
				if (app.getEntry(id) == null) {
					corruptIds.add(id);
				}
			}
			q.getQueuedItems().removeAll(corruptIds);
		}
	}
}
