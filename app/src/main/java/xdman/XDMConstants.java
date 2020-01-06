package xdman;

public interface XDMConstants {
	public static final int ALL = 0, DOCUMENTS = 10, PROGRAMS = 20, VIDEO = 30, MUSIC = 40, COMPRESSED = 50, OTHER = 60;
	public static final int FINISHED = 100, DOWNLOADING = 110, PAUSED = 130, FAILED = 140, UNFINISHED = 150,
			ASSEMBLING = 910;

	public static final int HTTP = 1000, HLS = 1001, HDS = 1002, DASH = 1003, FTP = 1004, DASH_AUDIO = 912,
			DASH_VIDEO = 516;
	public static final int ERR_INVALID_RESP = 100, ERR_CONN_FAILED = 101, ERR_SESSION_FAILED = 102,
			ERR_NO_RESUME = 103, ERR_ASM_FAILED = 132, DISK_FAIURE = 133, RESUME_FAILED = 135;
	public byte SUNDAY = 1, MONDAY = 2, TUESDAY = 3, WEDNESSDAY = 4, THURSDAY = 5, FRDAY = 6, SATURDAY = 7;
	public int TYPE1 = 10, TYPE2 = 20;
	public static final int DUP_ACT_AUTO_RENAME = 0, DUP_ACT_OVERWRITE = 1;
	public static final int HDPI = 10, XHDPI = 20, NORMAL = 0;
}