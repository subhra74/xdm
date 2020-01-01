package xdman.mediaconversion;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import xdman.ui.res.StringResource;
import xdman.util.Logger;

public class FormatLoader {
	public static List<FormatGroup> load() {
		List<FormatGroup> list = new ArrayList<>();
		InputStream inStream = null;
		InputStreamReader r = null;
		BufferedReader br = null;
		try {
			inStream = StringResource.class.getResourceAsStream("/formats/format_db.txt");
			if (inStream == null) {
				inStream = new FileInputStream("formats/format_db.txt");
			}
			r = new InputStreamReader(inStream, StandardCharsets.UTF_8);

			br = new BufferedReader(r);

			while (true) {
				String ln = br.readLine();
				if (ln == null||ln.length() < 1) {
					break;
				}
				FormatGroup fg = new FormatGroup();
				String[] arr = ln.split("\\|");
				fg.name = arr[0].trim();
				fg.desc = arr[1].trim();
				System.out.println("group: " + fg.name);
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
						System.out.println(fg.desc + " " + format.desc);
						fg.formats.add(format);
					}
				}
			}
		} catch (Exception e) {
			Logger.log(e);
		}
		finally {
			try {
				if (inStream != null) inStream.close();
				if (r != null) r.close();
				if (br != null) br.close();
			} catch (IOException e){
				Logger.log(e);
			}
		}
		return list;
	}

	static void print(Format format) {
		System.out.println("\t" + format.getDesc() + " '" + format.group + "'");
		List<String> list = format.getVideoCodecs();
		if (list.size() > 0) {
			System.out.print("\t\tVideo Codec:");
			for (int i = 0; i < list.size(); i++) {
				if (list.get(i).length() > 1) {
					if (list.get(i).equals(format.getDefautVideoCodec())) {
						System.out.print("*");
					}

					System.out.print(list.get(i) + " ");
				}
			}
			System.out.println("\n");
		}

		list = format.getResolutions();
		if (list.size() > 0) {
			System.out.print("\t\tResolution:");
			for (int i = 0; i < list.size(); i++) {
				if (list.get(i).length() > 1) {
					if (list.get(i).equals(format.getDefaultResolution())) {
						System.out.print("*");
					}

					System.out.print(list.get(i) + " ");
				}
			}
			System.out.println("\n");
		}

		list = format.getAudioChannel();
		if (list.size() > 0) {
			System.out.print("\t\tChannel:");
			for (int i = 0; i < list.size(); i++) {
				if (list.get(i).equals(format.getDefaultAudioChannel())) {
					System.out.print("*");
				}

				System.out.print(list.get(i) + " ");
			}
			System.out.println("\n");
		}

	}
}
