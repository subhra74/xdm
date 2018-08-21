package xdman.mediaconversion;

import xdman.util.StringUtils;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

public class MediaFormats {
	private static final String FORMATS_FILE_NAME = "formats/list.txt";
	private static MediaFormat[] supportedFormats;

	static {
		ArrayList<MediaFormat> list = new ArrayList<>();
		list.add(new MediaFormat());
		BufferedReader br = null;
		try {
			InputStream inStream = MediaFormats.class.getResourceAsStream(String.format("/%s", FORMATS_FILE_NAME));
			if (inStream == null) {
				inStream = new FileInputStream(FORMATS_FILE_NAME);
			}
			InputStreamReader r = new InputStreamReader(inStream, StandardCharsets.UTF_8);
			br = new BufferedReader(r, 1024);
			String ln;
			while ((ln = br.readLine()) != null) {
				if (ln.startsWith("#")) {
					continue;
				}
				MediaFormat mediaFormat = parseMediaFormat(ln);

				list.add(mediaFormat);

				supportedFormats = new MediaFormat[list.size()];
				supportedFormats = list.toArray(supportedFormats);

			}
		} catch (Exception e) {
			// TODO: handle exception
		}

		// supportedFormats = new MediaFormat[11];
		// supportedFormats[0] = new MediaFormat(-1, -1, null, null);
		// supportedFormats[1] = new MediaFormat(1366, 768, "MP4", "Video");
		// supportedFormats[2] = new MediaFormat(1920, 1080, "MP4", "Video");
		// supportedFormats[3] = new MediaFormat(1280, 800, "MP4", "Video");
		// supportedFormats[4] = new MediaFormat(320, 568, "MP4", "Video");
		// supportedFormats[5] = new MediaFormat(320, 480, "MP4", "Video");
		// supportedFormats[6] = new MediaFormat(1920, 1200, "MP4", "Video");
		// supportedFormats[7] = new MediaFormat(720, 1280, "MP4", "Video");
		// supportedFormats[8] = new MediaFormat(96, -1, "MP3", "Audio", true);
		// supportedFormats[9] = new MediaFormat(128, -1, "MP3", "Audio", true);
		// supportedFormats[10] = new MediaFormat(320, -1, "MP3", "Audio", true);
	}

	static String getString(String str) {
		if (!StringUtils.isNullOrEmptyOrBlank(str)) {
			return str;
		}
		return null;
	}

	public MediaFormats() {

	}

	public static final MediaFormat[] getSupportedFormats() {
		return supportedFormats;
	}

	public static final void setSupportedFormats(MediaFormat[] supportedFmts) {
		supportedFormats = supportedFmts;
	}

	public static MediaFormat parseMediaFormat(String line) {

		String[] arr = line.split("\\|");
		if (arr.length != 12) {
			return null;
		}
		MediaFormat mediaFormat = new MediaFormat();

		String fmt = getString(arr[0]);
		mediaFormat.setFormat(fmt);

		String resolution = getString(arr[1]);
		mediaFormat.setResolution(resolution);

		String vcodec = getString(arr[2]);
		mediaFormat.setVideo_codec(vcodec);

		String vbr = getString(arr[3]);
		mediaFormat.setVideo_bitrate(vbr);

		String fr = getString(arr[4]);
		mediaFormat.setFramerate(fr);

		String vextra = getString(arr[5]);
		mediaFormat.setVideo_param_extra(vextra);

		String acodec = getString(arr[6]);
		mediaFormat.setAudio_codec(acodec);

		String abr = getString(arr[7]);
		mediaFormat.setAudio_bitrate(abr);

		String asr = getString(arr[8]);
		mediaFormat.setSamplerate(asr);

		String aextra = getString(arr[9]);
		mediaFormat.setAudio_extra_param(aextra);

		String desc = getString(arr[10]);
		mediaFormat.setDescription(desc);

		String audioOnly = getString(arr[11]);
		mediaFormat.setAudioOnly("1".equals(audioOnly));
		return mediaFormat;
	}
}
