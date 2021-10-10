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

package xdman.downloaders;

import java.io.IOException;
import java.io.RandomAccessFile;

public interface Segment {
	long getLength();

	long getStartOffset();

	long getDownloaded();

	RandomAccessFile getOutStream();

	boolean transferComplete() throws IOException;

	void transferInitiated() throws IOException;

	void transferring();

	void transferFailed(String reason);

	boolean isFinished();

	boolean isActive();

	String getId();

	void setId(String id);

	void download(SegmentListener listenre) throws IOException;

	void setLength(long length);

	void setDownloaded(long downloaded);

	void setStartOffset(long offset);

	void stop();

	SegmentListener getChunkListener();

	void dispose();

	AbstractChannel getChannel();

	float getTransferRate();

	int getErrorCode();

	Object getTag();

	void setTag(Object obj);

	void resetStream() throws IOException;

	void reopenStream() throws IOException;

	boolean promptCredential(String msg, boolean proxy);

	void clearChannel();

}
