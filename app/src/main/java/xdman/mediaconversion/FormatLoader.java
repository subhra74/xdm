package xdman.mediaconversion;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

import org.tinylog.Logger;
import xdman.ui.res.StringResource;

public class FormatLoader {
	public static List<FormatGroup> load() {
		List<FormatGroup> list = new ArrayList<>();
		try {
			InputStream inStream = StringResource.class.getResourceAsStream("/formats/format_db.txt");
			if (inStream == null) {
				inStream = new FileInputStream("formats/format_db.txt");
			}
			InputStreamReader r = new InputStreamReader(inStream, Charset.forName("utf-8"));

			BufferedReader br = new BufferedReader(r);

			while (true) {
				String ln = br.readLine();
				if (ln == null||ln.length() < 1) {
					break;
				}
				FormatGroup fg = new FormatGroup();
				String[] arr = ln.split("\\|");
				fg.name = arr[0].trim();
				fg.desc = arr[1].trim();
				Logger.info("group: " + fg.name);
				list.add(fg);
			}
			while (true) {
				Format format = Format.read(br);
				if (format == null) {
					break;
				}
				print(format);
				for (FormatGroup fg : list) {
					if (fg.name.equals(format.group)) {
						Logger.info(fg.desc + " " + format.desc);
						fg.formats.add(format);
					}
				}
			}
		} catch (Exception e) {
			Logger.error(e);
		}
		return list;
	}

	static void print(Format format) {
		Logger.info("\t" + format.getDesc() + " '" + format.group + "'");
		List<String> list = format.getVideoCodecs();
		if (list.size() > 0) {
			Logger.info("\t\tVideo Codec:");
			for (int i = 0; i < list.size(); i++) {
				if (list.get(i).length() > 1) {
					if (list.get(i).equals(format.getDefautVideoCodec())) {
						Logger.info("*");
					}

					Logger.info(list.get(i) + " ");
				}
			}
			Logger.info("\n");
		}

		list = format.getResolutions();
		if (list.size() > 0) {
			Logger.info("\t\tResolution:");
			for (int i = 0; i < list.size(); i++) {
				if (list.get(i).length() > 1) {
					if (list.get(i).equals(format.getDefaultResolution())) {
						Logger.info("*");
					}

					Logger.info(list.get(i) + " ");
				}
			}
			Logger.info("\n");
		}

		list = format.getAudioChannel();
		if (list.size() > 0) {
			Logger.info("\t\tChannel:");
			for (int i = 0; i < list.size(); i++) {
				if (list.get(i).equals(format.getDefaultAudioChannel())) {
					Logger.info("*");
				}

				Logger.info(list.get(i) + " ");
			}
			Logger.info("\n");
		}

	}
}
