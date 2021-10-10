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

package xdman.mediaconversion;

import xdman.ui.res.StringResource;

public class MediaFormat {

	public MediaFormat() {
	}

	private String resolution, video_codec, video_bitrate, framerate, video_param_extra, audio_codec, audio_bitrate,
			sampleRate, audio_extra_param, audio_channel, aspectRatio;

	private String format, description;
	private boolean audioOnly;

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

	public String getSampleRate() {
		return sampleRate;
	}

	public void setSampleRate(String sampleRate) {
		this.sampleRate = sampleRate;
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
