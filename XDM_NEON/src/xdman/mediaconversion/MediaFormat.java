package xdman.mediaconversion;

import xdman.ui.res.StringResource;

public class MediaFormat {
	public MediaFormat() {
	}

	// public MediaFormat(int width, int height, String format, String description,
	// boolean audioOnly) {
	// this.width = width;
	// this.height = height;
	// this.format = format;
	// this.description = description;
	// this.audioOnly = audioOnly;
	// }

	// public MediaFormat(int width, int height, String format, String description)
	// {
	// this(width, height, format, description, false);
	// }

	private String resolution, video_codec, video_bitrate, framerate, video_param_extra, audio_codec, audio_bitrate,
			samplerate, audio_extra_param, audio_channel, aspectRatio;

	// private int width, height;
	private String format, description;
	private boolean audioOnly;

	// public final int getWidth() {
	// return width;
	// }
	//
	// public final void setWidth(int width) {
	// this.width = width;
	// }
	//
	// public final int getHeight() {
	// return height;
	// }
	//
	// public final void setHeight(int height) {
	// this.height = height;
	// }

	public final String getFormat() {
		return format;
	}

	public final void setFormat(String format) {
		this.format = format;
	}

	public final String getDescription() {
		return description;
	}

	public final void setDescription(String description) {
		this.description = description;
	}

	@Override
	public String toString() {
		if (format == null) {
			return StringResource.get("VID_FMT_ORIG");
		}

		return format + " " + description;
	}

	public final boolean isAudioOnly() {
		return audioOnly;
	}

	public final void setAudioOnly(boolean audioOnly) {
		this.audioOnly = audioOnly;
	}

	public String getResolution() {
		return resolution;
	}

	public void setResolution(String resolution) {
		this.resolution = resolution;
	}

	public String getVideo_codec() {
		return video_codec;
	}

	public void setVideo_codec(String video_codec) {
		this.video_codec = video_codec;
	}

	public String getVideo_bitrate() {
		return video_bitrate;
	}

	public void setVideo_bitrate(String video_bitrate) {
		this.video_bitrate = video_bitrate;
	}

	public String getFramerate() {
		return framerate;
	}

	public void setFramerate(String framerate) {
		this.framerate = framerate;
	}

	public String getVideo_param_extra() {
		return video_param_extra;
	}

	public void setVideo_param_extra(String video_param_extra) {
		this.video_param_extra = video_param_extra;
	}

	public String getAudio_codec() {
		return audio_codec;
	}

	public void setAudio_codec(String audio_codec) {
		this.audio_codec = audio_codec;
	}

	public String getAudio_bitrate() {
		return audio_bitrate;
	}

	public void setAudio_bitrate(String audio_bitrate) {
		this.audio_bitrate = audio_bitrate;
	}

	public String getSamplerate() {
		return samplerate;
	}

	public void setSamplerate(String samplerate) {
		this.samplerate = samplerate;
	}

	public String getAudio_extra_param() {
		return audio_extra_param;
	}

	public void setAudio_extra_param(String audio_extra_param) {
		this.audio_extra_param = audio_extra_param;
	}

	public String getAudio_channel() {
		return audio_channel;
	}

	public void setAudio_channel(String audio_channel) {
		this.audio_channel = audio_channel;
	}

	public String getAspectRatio() {
		return aspectRatio;
	}

	public void setAspectRatio(String aspectRatio) {
		this.aspectRatio = aspectRatio;
	}
}
