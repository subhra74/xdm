package xdman;

import xdman.util.DateTimeUtils;
import xdman.util.Logger;
import xdman.util.UpdateChecker;
import xdman.util.XDMUtils;

import java.util.Calendar;
import java.util.Collection;
import java.util.Date;

public class QueueScheduler implements Runnable {
	private boolean stop;
	private long lastKeepAwakePing = 0L;

	private static QueueScheduler _this;

	private QueueScheduler() {
		stop = false;
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

					Collection<DownloadQueue> downloadQueues = QueueManager.getInstance().getDownloadQueues();
					for (DownloadQueue downloadQueue : downloadQueues) {
						if (downloadQueue.isRunning() || downloadQueue.getStartTime() == -1) {
							continue;
						}
						Date now = new Date();
						cal.setTime(now);
						Date onlyDate = DateTimeUtils.getDatePart(cal);
						long seconds = DateTimeUtils.getTimePart(now);

						if (seconds > downloadQueue.getStartTime()) {
							if (downloadQueue.getEndTime() > 0) {
								if (downloadQueue.getEndTime() < seconds) {
									continue;
								}
							}
						} else {
							continue;
						}

						if (downloadQueue.isPeriodic()) {
							int day = cal.get(Calendar.DAY_OF_WEEK);
							int mask = 0x01 << (day - 1);

							if ((downloadQueue.getDayMask() & mask) != mask) {
								continue;
							}
						} else {
							Date execDate = downloadQueue.getExecDate();
							if (execDate == null) {
								continue;
							}
							cal.setTime(execDate);
							Date onlyDate2 = DateTimeUtils.getDatePart(cal);
							if (onlyDate.compareTo(onlyDate2) < 0) {
								continue;
							}
						}
						downloadQueue.start();
					}

					for (DownloadQueue downloadQueue : downloadQueues) {
						if (!downloadQueue.isRunning()) {
							continue;
						}
						if (downloadQueue.getEndTime() < 1) {
							continue;
						}
						Date now = new Date();
						long seconds = DateTimeUtils.getTimePart(now);
						if (downloadQueue.getEndTime() < seconds) {
							downloadQueue.stop();
						}
					}
					Thread.sleep(1000);
				} catch (Exception e2) {
					Logger.log("Error in scheduler:", e2);
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
			Logger.log("error in scheduler:", e);
		}
	}
}
