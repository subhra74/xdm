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

package xdman;

import xdman.util.FormatUtilities;
import xdman.util.StringUtils;

@SuppressWarnings("unused")
public class DownloadEntry {
	private String id, file, folder;
	private int state, category;
	private long size, downloaded;
	private long date;
	private int progress;
	private String dateStr;
	private String queueId;
	private boolean startedByUser;
	private int outputFormatIndex;
	private String tempFolder;

	public DownloadEntry() {
	}

	public String getId() {
		return id;
	}

	public String getDateStr() {
		return this.dateStr;
	}

	public void setId(String id) {
		this.id = id;
	}

	public String getFile() {
		return this.file;
	}

	public void setFile(String file) {
		this.file = file;
	}

	public int getState() {
		return this.state;
	}

	public void setState(int state) {
		this.state = state;
	}

	public int getCategory() {
		return this.category;
	}

	public void setCategory(int category) {
		this.category = category;
	}

	public long getSize() {
		return this.size;
	}

	public void setSize(long size) {
		this.size = size;
	}

	public long getDownloaded() {
		return this.downloaded;
	}

	public void setDownloaded(long downloaded) {
		this.downloaded = downloaded;
	}

	public long getDate() {
		return this.date;
	}

	public void setDate(long date) {
		this.date = date;
		this.dateStr = FormatUtilities.formatDate(date);
	}

	public int getProgress() {
		return this.progress;
	}

	public void setProgress(int progress) {
		this.progress = progress;
	}

	String getFolder() {
		return this.folder;
	}

	public void setFolder(String folder) {
		this.folder = folder;
	}

	public final String getQueueId() {
		return this.queueId;
	}

	public final void setQueueId(String queueId) {
		this.queueId = queueId;
	}

	public final void setDateStr(String dateStr) {
		this.dateStr = dateStr;
	}

	public final boolean isStartedByUser() {
		return this.startedByUser;
	}

	public final void setStartedByUser(boolean startedByUser) {
		this.startedByUser = startedByUser;
	}

	public final int getOutputFormatIndex() {
		return this.outputFormatIndex;
	}

	public final void setOutputFormatIndex(int outputFormatIndex) {
		this.outputFormatIndex = outputFormatIndex;
	}

	public String getTempFolder() {
		if (StringUtils.isNullOrEmptyOrBlank(this.tempFolder)) {
			this.tempFolder = Config.getInstance().getTemporaryFolder();
		}
		return this.tempFolder;
	}

	public void setTempFolder(String tempFolder) {
		this.tempFolder = tempFolder;
	}
}
