package xdman.downloaders.hls;

import java.util.List;

public class HlsPlaylist {
	private boolean isMaster, isEncrypted;
	private List<HlsPlaylistItem> items;
	private float duration;

	public boolean isMaster() {
		return isMaster;
	}

	public void setMaster(boolean isMaster) {
		this.isMaster = isMaster;
	}

	public boolean isEncrypted() {
		return isEncrypted;
	}

	public void setEncrypted(boolean isEncrypted) {
		this.isEncrypted = isEncrypted;
	}

	public List<HlsPlaylistItem> getItems() {
		return items;
	}

	public void setItems(List<HlsPlaylistItem> items) {
		this.items = items;
	}

	@Override
	public String toString() {
		String s = "";
		if (items != null) {
			for (HlsPlaylistItem item : items) {
				s += item;
				s += "\n";
			}
		}
		return s;
	}

	public float getDuration() {
		return duration;
	}

	public void setDuration(float duration) {
		this.duration = duration;
	}
}