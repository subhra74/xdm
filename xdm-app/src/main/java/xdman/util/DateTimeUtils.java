package xdman.util;

import java.util.Calendar;
import java.util.Date;

public class DateTimeUtils {
	public static Date getDefaultStart() {
		Calendar cal = Calendar.getInstance();
		cal.set(Calendar.HOUR_OF_DAY, 0);
		cal.set(Calendar.MINUTE, 0);
		cal.set(Calendar.SECOND, 0);
		cal.set(Calendar.MILLISECOND, 0);
		return cal.getTime();
	}

	public static Date getDefaultEnd() {
		Calendar cal = Calendar.getInstance();
		cal.set(Calendar.HOUR_OF_DAY, 23);
		cal.set(Calendar.MINUTE, 59);
		cal.set(Calendar.SECOND, 59);
		cal.set(Calendar.MILLISECOND, 0);
		return cal.getTime();
	}

	public static Date getBeginDate() {
		return getDefaultStart();
	}

	public static Date getEndDate() {
		Calendar cal = Calendar.getInstance();
		cal.add(Calendar.YEAR, 100);
		return cal.getTime();
	}

	public static long getTimePart(Date date) {
		Calendar cal = Calendar.getInstance();
		cal.setTime(date);
		return cal.get(Calendar.HOUR_OF_DAY) * 3600 + cal.get(Calendar.MINUTE)
				* 60 + cal.get(Calendar.SECOND);
	}

	public static Date addTimePart(long sec) {
		if (sec < 0) {
			return null;
		}
		Calendar cal = Calendar.getInstance();
		cal.set(Calendar.HOUR_OF_DAY, 0);
		cal.set(Calendar.MINUTE, 0);
		cal.set(Calendar.SECOND, 0);
		cal.set(Calendar.MILLISECOND, 0);
		cal.add(Calendar.SECOND, (int) sec);
		return cal.getTime();
	}

	public static Date getDatePart(Calendar cal) {
		cal.set(Calendar.HOUR_OF_DAY, 0);
		cal.set(Calendar.MINUTE, 0);
		cal.set(Calendar.SECOND, 0);
		cal.set(Calendar.MILLISECOND, 0);
		return cal.getTime();
	}
}
