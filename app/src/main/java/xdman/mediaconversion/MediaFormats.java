package xdman.mediaconversion;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.ArrayList;

import xdman.util.StringUtils;

public class MediaFormats {
	private static MediaFormat[] supportedFormats;
	static {
		ArrayList<MediaFormat> list = new ArrayList<>();
		list.add(new MediaFormat());
		BufferedReader br = null;
		try {
			InputStream inStream = MediaFormats.class.getResourceAsStream("/formats/list.txt");
			if (inStream == null) {
				inStream = new FileInputStream("formats/list.txt");
			}
			InputStreamReader r = new InputStreamReader(inStream, Charset.forName("utf-8"));
			br = new BufferedReader(r, 1024);
			while (true) {
				String ln = br.readLine();
				if (ln == null)
					break;
				if (ln.startsWith("#")) {
					continue;
				}
				String[] arr = ln.split("\\|");
				if (arr.length != 12) {
					continue;
				}
				MediaFormat format = new MediaFormat();
				String fmt = getString(arr[0]);
				String resolution = getString(arr[1]);
				String vcodec = getString(arr[2]);
				String vbr = getString(arr[3]);
				String fr = getString(arr[4]);
				String vextra = getString(arr[5]);
				String acodec = getString(arr[6]);
				String abr = getString(arr[7]);
				String asr = getString(arr[8]);
				String aextra = getString(arr[9]);
				String desc = getString(arr[10]);
				String audioOnly = getString(arr[11]);

				format.setFormat(fmt);
				format.setResolution(resolution);
				format.setVideo_codec(vcodec);
				format.setVideo_bitrate(vbr);
				format.setFramerate(fr);
				format.setVideo_param_extra(vextra);
				format.setAudio_codec(acodec);
				format.setAudio_bitrate(abr);
				format.setSamplerate(asr);
				format.setAudio_extra_param(aextra);
				format.setDescription(desc);
				format.setAudioOnly("1".equals(audioOnly));

				list.add(format);

				supportedFormats = new MediaFormat[list.size()];
				supportedFormats = list.toArray(supportedFormats);

			}
		} catch (RuntimeException | IOException e) {
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

}
