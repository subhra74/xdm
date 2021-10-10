/*
 * Copyright (c)  Subhra Das Gupta
 *
 * This file is part of Xtreme Download Manager.
 *
 * Xtreme Download Manager is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * Xtreme Download Manager is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with Xtream Download Manager; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA
 * 
 */

package xdman;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.UUID;

import org.tinylog.Logger;

import xdman.ui.res.StringResource;
import xdman.util.IOUtils;
import xdman.util.XDMUtils;

public class QueueManager {
	private static QueueManager _this;
	private final ArrayList<DownloadQueue> queueList;

	private QueueManager() {
		this.queueList = new ArrayList<>();
		this.loadQueues();
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
			return this.queueList.get(0);
		}
		for (DownloadQueue q : this.queueList) {
			if (q.getQueueId().equals(queueId)) {
				return q;
			}
		}
		return null;
	}

	public ArrayList<DownloadQueue> getQueueList() {
		return this.queueList;
	}

	public DownloadQueue getDefaultQueue() {
		return this.queueList.get(0);
	}

	private void loadQueues() {
		File file = new File(Config.getInstance().getDataFolder(), "queues.txt");
		DownloadQueue defaultQ = new DownloadQueue("", StringResource.get("DEF_QUEUE"));
		this.queueList.add(defaultQ);
		if (!file.exists()) {
			return;
		}

		SimpleDateFormat dateFormatter = new SimpleDateFormat("yyyy-MM-dd");

		try (BufferedReader reader = new BufferedReader(
				new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {

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
				DownloadQueue queue;
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
					this.queueList.add(queue);
				}
			}
		} catch (Exception e) {
			Logger.error(e);
		}
	}

	public void saveQueues() {
		int count = this.queueList.size();
		File file = new File(Config.getInstance().getDataFolder(), "queues.txt");
		BufferedWriter writer = null;
		String newLine = System.getProperty("line.separator");
		SimpleDateFormat dateFormatter = new SimpleDateFormat("yyyy-MM-dd");
		try {
			writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8));
			writer.write(count + newLine);
			for (DownloadQueue queue : this.queueList) {
				writer.write(queue.getQueueId() + newLine);
				writer.write(queue.getName() + newLine);
				ArrayList<String> queuedItems = queue.getQueuedItems();
				writer.write(queuedItems.size() + newLine);
				for (String queuedItem : queuedItems) {
					writer.write(queuedItem + newLine);
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
			Logger.error(e);
		}
		IOUtils.closeFlow(writer);
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
		this.queueList.remove(q);
	}

	public DownloadQueue createNewQueue() {
		int counter = 1;
		String name;
		String qw = StringResource.get("Q_WORD");
		while (true) {
			boolean found = false;
			counter++;
			for (DownloadQueue qi : this.queueList) {
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
		this.queueList.add(q);
		saveQueues();
		return q;
	}

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
		for (DownloadQueue q : this.queueList) {
			ArrayList<String> corruptIds = new ArrayList<>();
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
