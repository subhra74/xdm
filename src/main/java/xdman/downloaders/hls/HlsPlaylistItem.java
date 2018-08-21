package xdman.downloaders.hls;

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
