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

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;

import org.tinylog.Logger;

import xdman.util.DateTimeUtils;
import xdman.util.UpdateChecker;
import xdman.util.XDMUtils;

public class QueueScheduler implements Runnable {
	private static QueueScheduler _this;
	private long lastKeepAwakePing = 0L;

	private QueueScheduler() {
	}

	public static QueueScheduler getInstance() {
		if (_this == null) {
			_this = new QueueScheduler();
		}
		return _this;
	}

	public void start() {
		lastKeepAwakePing = System.currentTimeMillis();
		new Thread(this).start();
	}

	@Override
	public void run() {
		long lastUpdateChecked = 0;
		try {
			Calendar cal = Calendar.getInstance();
			while (true) {
				try {
					long currentTime = System.currentTimeMillis();
					if (currentTime - lastKeepAwakePing > 3000) {
						if (!XDMApp.getInstance().isAllFinished()) {
							XDMUtils.keepAwakePing();
							lastKeepAwakePing = currentTime;
						}
					}

					ArrayList<DownloadQueue> queues = QueueManager.getInstance().getQueueList();
					for (DownloadQueue queue : queues) {
						if (queue.isRunning() || queue.getStartTime() == -1) {
							continue;
						}
						Date now = new Date();
						cal.setTime(now);
						Date onlyDate = DateTimeUtils.getDatePart(cal);
						long seconds = DateTimeUtils.getTimePart(now);

						if (seconds > queue.getStartTime()) {
							if (queue.getEndTime() > 0) {
								if (queue.getEndTime() < seconds) {
									continue;
								}
							}
						} else {
							continue;
						}

						if (queue.isPeriodic()) {
							int day = cal.get(Calendar.DAY_OF_WEEK);
							int mask = 0x01 << (day - 1);

							if ((queue.getDayMask() & mask) != mask) {
								continue;
							}
						} else {
							Date execDate = queue.getExecDate();
							if (execDate == null) {
								continue;
							}
							cal.setTime(execDate);
							Date onlyDate2 = DateTimeUtils.getDatePart(cal);
							if (onlyDate.compareTo(onlyDate2) < 0) {
								continue;
							}
						}
						queue.start();
					}

					for (DownloadQueue queue : queues) {
						if (!queue.isRunning()) {
							continue;
						}
						if (queue.getEndTime() < 1) {
							continue;
						}
						Date now = new Date();
						long seconds = DateTimeUtils.getTimePart(now);
						if (queue.getEndTime() < seconds) {
							queue.stop();
						}
					}
					Thread.sleep(1000);
				} catch (Exception e2) {
					Logger.error("error in scheduler: " + e2);
					Logger.error(e2);
				}

				long now = System.currentTimeMillis();
				if (now - lastUpdateChecked > 3600 * 1000) {
					int stat = UpdateChecker.getUpdateStat();
					switch (stat) {
					case UpdateChecker.NO_UPDATE_AVAILABLE:
						break;
					case UpdateChecker.APP_UPDATE_AVAILABLE:
						XDMApp.getInstance().notifyAppUpdate();
						break;
					case UpdateChecker.COMP_NOT_INSTALLED:
						XDMApp.getInstance().notifyComponentInstall();
						break;
					case UpdateChecker.COMP_UPDATE_AVAILABLE:
						XDMApp.getInstance().notifyComponentUpdate();
						break;
					}
				}
				lastUpdateChecked = now;
			}
		} catch (Exception e) {
			Logger.error("error in scheduler: " + e);
			Logger.error(e);
		}
	}
}
