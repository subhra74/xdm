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

package xdman.downloaders.hls;

@SuppressWarnings("unused")
public class HlsPlaylistItem {

	public HlsPlaylistItem() {

	}

	public HlsPlaylistItem(String url, String keyUrl, String iV, String resolution, String bandwidth, String duration) {
		super();
		this.url = url;
		this.keyUrl = keyUrl;
		IV = iV;
		this.resolution = resolution;
		this.bandwidth = bandwidth;
		this.duration = duration;
	}

	private String url, keyUrl, IV, resolution, bandwidth, duration;

	@Override
	public String toString() {
		return "url: " + url + "\nduration:" + duration + "\nbandwidth: " + bandwidth + "\nresolution: " + resolution
				+ "\nkeyUrl: " + keyUrl + "\nIV: " + IV;
	}

	public String getUrl() {
		return url;
	}

	public void setUrl(String url) {
		this.url = url;
	}

	public String getKeyUrl() {
		return keyUrl;
	}

	public void setKeyUrl(String keyUrl) {
		this.keyUrl = keyUrl;
	}

	public String getIV() {
		return IV;
	}

	public void setIV(String iV) {
		IV = iV;
	}

	public String getResolution() {
		return resolution;
	}

	public void setResolution(String resolution) {
		this.resolution = resolution;
	}

	public String getBandwidth() {
		return bandwidth;
	}

	public void setBandwidth(String bandwidth) {
		this.bandwidth = bandwidth;
	}

	public String getDuration() {
		return duration;
	}

	public void setDuration(String duration) {
		this.duration = duration;
	}

}
