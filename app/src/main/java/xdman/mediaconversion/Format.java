package xdman.mediaconversion;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

public class Format {
	public static String getBitRate(String s) {
		try {
			Double.parseDouble(s);
		} catch (Exception e) {
			return null;
		}
		return s + "k";
	}

	public static String getSize(String name) {
		if (name != null) {
			name = name.toLowerCase();
			if (name.contains("x")) {
				return name;
			}
		}
		return null;
	}

	public static String getAspec(String name) {
		if (name != null) {
			name = name.toLowerCase();
			if (name.contains("/")) {
				return name.replace("/", ":");
			}
		}
		return null;
	}

	public static String getCodecName(String name) {
		if (name == null)
			return null;
		name = name.toLowerCase();
		switch (name) {
		case "vp8":
			return "vp8";
		case "vp9":
			return "vp9";
		case "wmv1":
		case "wmv":
			return "wmv1";
		case "wmv v8":
		case "wmv2":
			return "wmv2";
		case "wmv v9":
		case "wmv3":
			return "wmv2";
		case "ffv1":
			return "ffv1";
		case "flv":
			return "flv";
		case "gif":
			return "gif";
		case "xvid":
			return "libxvid";
		case "h263":
			return "h263";
		case "h264":
		case "x264":
			return "libx264";
		case "x265":
			return "libx265";
		case "h263p":
			return "h263p";
		case "huffyuv":
			return "huffyuv";
		case "libtheora":
		case "theora":
			return "libtheora";
		case "mjpeg":
			return "mjpeg";
		case "mpeg1":
		case "mpeg1video":
			return "mpeg1video";
		case "mpeg2":
		case "mpeg2video":
			return "mpeg2video";
		case "mpeg4":
			return "mpeg4";
		case "msmpeg4":
			return "msmpeg4v1";
		case "msmpeg4v2":
			return "msmpeg4v2";
		case "wma 9.2":
			return "wmapro";
		case "aac":
		case "aac_low":
		case "aac_ltp":
		case "faac":
		case "aac_main":
			return "aac";
		case "alac":
			return "alac";
		case "ac3":
			return "ac3";
		case "ape":
			return "ape";
		case "dca":
			return "dts";
		case "flac":
			return "flac";
		case "mp2":
			return "mp2";
		case "mp3":
			return "libmp3lame";
		case "ogg":
		case "vorbis":
			return "libvorbis";
		case "opencore_amrnb":
			return "libopencore_amrnb";
		case "pcm":
			return "pcm_u8";
		case "pcm_s16be":
			return "pcm_s16be";
		case "wmav1":
			return "wmav1";
		case "wmav2":
			return "wmav2";
		default:
			return null;
		}
	}

	public static Format read(BufferedReader br) throws IOException {

		Format format = new Format();

		for (;;) {
			String ln = br.readLine();
			if (ln == null) {
				return null;
			}
			int index = ln.indexOf(":");
			if (index < 0) {
				break;
			}
			String key = ln.substring(0, index).trim();
			String val = ln.substring(index + 1).trim();
			if (key.equals("name")) {
				format.desc = val;
			}
			if (key.equals("ext")) {
				format.ext = val;
			}
			if (key.equals("group")) {
				format.group = val;
			}
			if (key.equals("resolutions")) {
				format.resolutions = toList(val);
			}
			if (key.equals("video_extra")) {
				format.vidExtra = val.trim();
			}
			if (key.equals("video_codecs")) {
				format.videoCodecs = toList(val);
			}
			if (key.equals("framerates")) {
				format.frameRate = toList(val);
			}
			if (key.equals("video_bitrates")) {
				format.videoBitrate = toList(val);
			}
			if (key.equals("audio_codecs")) {
				format.audioCodecs = toList(val);
			}
			if (key.equals("aspect_ratios")) {
				format.aspectRatio = toList(val);
			}
			if (key.equals("audio_bitrates")) {
				format.audioBitrate = toList(val);
			}
			if (key.equals("audio_samplerates")) {
				format.audioSampleRate = toList(val);
			}
			if (key.equals("audio_channels")) {
				format.audioChannel = toList(val);
			}

			if (key.equals("default_resolution")) {
				format.defaultResolution = val;
			}
			if (key.equals("default_video_codec")) {
				format.defautVideoCodec = val;
			}
			if (key.equals("default_framerate")) {
				format.defaultFrameRate = val;
			}
			if (key.equals("default_video_bitrate")) {
				format.defaultVideoBitrate = val;
			}
			if (key.equals("default_aspect_ratio")) {
				format.defaultAspectRatio = val;
			}
			if (key.equals("default_audio_codec")) {
				format.defautAudioCodec = val;
			}
			if (key.equals("default_audio_bitrate")) {
				format.defaultAudioBitrate = val;
			}
			if (key.equals("default_samplerate")) {
				format.defaultAudioSampleRate = val;
			}
			if (key.equals("default_channel")) {
				format.defaultAudioChannel = val;
			}

		}
		return format;
	}

	public void write(PrintStream out) throws Exception {
		out.println("name: " + this.desc);
		out.println("group: " + this.group);
		out.println("resolutions: " + getList(this.resolutions));
		out.println("video_codecs:" + getList(this.videoCodecs));
		out.println("framerates:" + getList(this.frameRate));
		out.println("video_bitrates:" + getList(this.videoBitrate));
		out.println("aspect_ratios:" + getList(this.aspectRatio));
		out.println("audio_codecs:" + getList(this.audioCodecs));
		out.println("audio_bitrates:" + getList(this.audioBitrate));
		out.println("audio_samplerates:" + getList(this.audioSampleRate));
		out.println("audio_channels:" + getList(this.audioChannel));

		out.println("default_resolution:" + this.defaultResolution);
		out.println("default_video_codec:" + this.defautVideoCodec);
		out.println("default_framerate:" + this.defaultFrameRate);
		out.println("default_video_bitrate:" + this.defaultVideoBitrate);
		out.println("default_aspect_ratio:" + this.defaultAspectRatio);
		out.println("default_audio_codec:" + this.defautAudioCodec);
		out.println("default_audio_bitrate:" + this.defaultAudioBitrate);
		out.println("default_samplerate:" + this.defaultAudioSampleRate);
		out.println("default_channel:" + this.defaultAudioChannel);
		out.println();
	}

	public static List<String> toList(String str) {
		ArrayList<String> list = new ArrayList<>();
		if (str == null || str.trim().length() < 1) {
			return list;
		}
		String[] arr = str.split(" ");
		for (String s : arr) {
			if (s.trim().length() > 0) {
				list.add(s);
			}
		}
		return list;
	}

	public String getList(List<String> list) {
		StringBuffer sb = new StringBuffer();
		boolean first = true;
		if (list != null) {
			for (String s : list) {
				if (!first) {
					sb.append(" ");
				}
				sb.append(s);
				if (first) {
					first = false;
				}
			}
		}
		return sb.toString();
	}

	String group;
	public static final String MPEG4_XVID_MAX_RESOLUTION = "640x480";
	public static final String MPEG4_XVID_MAX_VIDEO_BR = "2500";
	public static final String MPEG4_XVID_MAX_FRAME_RATE = "30";
	List<String> videoCodecs;
	String defautVideoCodec;
	List<String> resolutions;
	String defaultResolution;
	List<String> aspectRatio;
	String defaultAspectRatio;
	List<String> videoBitrate;
	String defaultVideoBitrate;
	List<String> frameRate;
	String defaultFrameRate;
	List<String> audioCodecs;
	String defautAudioCodec;
	List<String> audioSampleRate;
	String defaultAudioSampleRate;
	List<String> audioBitrate;
	String defaultAudioBitrate;
	List<String> audioChannel;
	String defaultAudioChannel;
	String desc;
	String ext;
	String vidExtra;

	public List<String> getVideoCodecs() {
		return videoCodecs;
	}

	public void setVideoCodecs(List<String> videoCodecs) {
		this.videoCodecs = videoCodecs;
	}

	public String getDefautVideoCodec() {
		return defautVideoCodec;
	}

	public String getDefautValue(List<String> list, String defaultValue) {
		for (String str : list) {
			if (str.equals(defaultValue)) {
				return str;
			}
		}
		if (list.size() > 0) {
			return list.get(0);
		}
		return null;
	}

	public void setDefautVideoCodecIndex(String defautVideoCodec) {
		this.defautVideoCodec = defautVideoCodec;
	}

	public List<String> getResolutions() {
		return resolutions;
	}

	public void setResolutions(List<String> resolutions) {
		this.resolutions = resolutions;
	}

	public String getDefaultResolution() {
		return defaultResolution;
	}

	public void setDefaultResolution(String defaultResolution) {
		this.defaultResolution = defaultResolution;
	}

	public List<String> getAspectRatio() {
		return aspectRatio;
	}

	public void setAspectRatio(List<String> aspectRatio) {
		this.aspectRatio = aspectRatio;
	}

	public String getDefaultAspectRatio() {
		return defaultAspectRatio;
	}

	public void setDefaultAspectRatio(String defaultAspectRatio) {
		this.defaultAspectRatio = defaultAspectRatio;
	}

	public List<String> getVideoBitrate() {
		return videoBitrate;
	}

	public void setVideoBitrate(List<String> videoBitrate) {
		this.videoBitrate = videoBitrate;
	}

	public String getDefaultVideoBitrate() {
		return defaultVideoBitrate;
	}

	public void setDefaultVideoBitrate(String defaultVideoBitrate) {
		this.defaultVideoBitrate = defaultVideoBitrate;
	}

	public List<String> getFrameRate() {
		return frameRate;
	}

	public void setFrameRate(List<String> frameRate) {
		this.frameRate = frameRate;
	}

	public String getDefaultFrameRate() {
		return defaultFrameRate;
	}

	public void setDefaultFrameRate(String defaultFrameRate) {
		this.defaultFrameRate = defaultFrameRate;
	}

	public List<String> getAudioCodecs() {
		return audioCodecs;
	}

	public void setAudioCodecs(List<String> audioCodecs) {
		this.audioCodecs = audioCodecs;
	}

	public String getDefautAudioCodec() {
		return defautAudioCodec;
	}

	public void setDefautAudioCodecIndex(String defautAudioCodecIndex) {
		this.defautAudioCodec = defautAudioCodecIndex;
	}

	public List<String> getAudioSampleRate() {
		return audioSampleRate;
	}

	public void setAudioSampleRate(List<String> audioSampleRate) {
		this.audioSampleRate = audioSampleRate;
	}

	public String getDefaultAudioSampleRate() {
		return defaultAudioSampleRate;
	}

	public void setDefaultAudioSampleRate(String defaultAudioSampleRate) {
		this.defaultAudioSampleRate = defaultAudioSampleRate;
	}

	public List<String> getAudioBitrate() {
		return audioBitrate;
	}

	public void setAudioBitrate(List<String> audioBitrate) {
		this.audioBitrate = audioBitrate;
	}

	public String getDefaultAudioBitrate() {
		return defaultAudioBitrate;
	}

	public void setDefaultAudioBitrate(String defaultAudioBitrate) {
		this.defaultAudioBitrate = defaultAudioBitrate;
	}

	public List<String> getAudioChannel() {
		return audioChannel;
	}

	public void setAudioChannel(List<String> audioChannel) {
		this.audioChannel = audioChannel;
	}

	public String getDefaultAudioChannel() {
		return defaultAudioChannel;
	}

	public void setDefaultAudioChannel(String defaultAudioChannel) {
		this.defaultAudioChannel = defaultAudioChannel;
	}

	public String getGroup() {
		return group;
	}

	public void setGroup(String group) {
		this.group = group;
	}

	@Override
	public String toString() {
		return desc;
	}

	public String getDesc() {
		return desc;
	}

	public void setDesc(String desc) {
		this.desc = desc;
	}

	public String getExt() {
		return ext;
	}

	public void setExt(String ext) {
		this.ext = ext;
	}

	public String getVidExtra() {
		return vidExtra;
	}

	public void setVidExtra(String vidExtra) {
		this.vidExtra = vidExtra;
	}
}