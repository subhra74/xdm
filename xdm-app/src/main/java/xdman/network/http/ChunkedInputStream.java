/*
 * ====================================================================
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * ====================================================================
 *
 * This software consists of voluntary contributions made by many
 * individuals on behalf of the Apache Software Foundation.  For more
 * information on the Apache Software Foundation, please see
 * <http://www.apache.org/>.
 *
 */

package xdman.network.http;

import java.io.IOException;
import java.io.InputStream;

/**
 * Implements chunked transfer coding. The content is received in small chunks.
 * Entities transferred using this input stream can be of unlimited length.
 * After the stream is read to the end, it provides access to the trailers, if
 * any.
 * <p>
 * Note that this class NEVER closes the underlying stream, even when close gets
 * called. Instead, it will read until the "end" of its chunking on close, which
 * allows for the seamless execution of subsequent HTTP 1.1 requests, while not
 * requiring the client to remember to read the entire contents of the response.
 * 
 * 
 * @since 4.0
 * 
 */
public class ChunkedInputStream extends InputStream {

	private static final int CHUNK_LEN = 1;
	private static final int CHUNK_DATA = 2;
	private static final int CHUNK_CRLF = 3;

	private static final int BUFFER_SIZE = 2048;

	/** The session input buffer */
	private final InputStream in;

	private StringBuffer buffer;

	private int state;

	/** The chunk size */
	private int chunkSize;

	/** The current position within the current chunk */
	private int pos;

	/** True if we've reached the end of stream */
	private boolean eof = false;

	/** True if this stream is closed */
	private boolean closed = false;

	/**
	 * Wraps session input stream and reads chunk coded input.
	 * 
	 * @param in
	 *            The session input buffer
	 */
	public ChunkedInputStream(final InputStream in) {
		super();
		if (in == null) {
			throw new IllegalArgumentException(
					"Session input buffer may not be null");
		}
		this.in = in;
		this.pos = 0;
		this.buffer = new StringBuffer(16);
		this.state = CHUNK_LEN;
	}

	/**
	 * <p>
	 * Returns all the data in a chunked stream in coalesced form. A chunk is
	 * followed by a CRLF. The method returns -1 as soon as a chunksize of 0 is
	 * detected.
	 * </p>
	 * 
	 * <p>
	 * Trailer headers are read automatically at the end of the stream and can
	 * be obtained with the getResponseFooters() method.
	 * </p>
	 * 
	 * @return -1 of the end of the stream has been reached or the next data
	 *         byte
	 * @throws IOException
	 *             in case of an I/O error
	 */
	public int read() throws IOException {
		if (this.closed) {
			throw new IOException("Attempted read from closed stream.");
		}
		if (this.eof) {
			
			return -1;
		}
		if (state != CHUNK_DATA) {
			nextChunk();
			if (this.eof) {
				return -1;
			}
		}
		int b = in.read();
		if (b != -1) {
			pos++;
			if (pos >= chunkSize) {
				state = CHUNK_CRLF;
			}
		}
		return b;
	}

	/**
	 * Read some bytes from the stream.
	 * 
	 * @param b
	 *            The byte array that will hold the contents from the stream.
	 * @param off
	 *            The offset into the byte array at which bytes will start to be
	 *            placed.
	 * @param len
	 *            the maximum number of bytes that can be returned.
	 * @return The number of bytes returned or -1 if the end of stream has been
	 *         reached.
	 * @throws IOException
	 *             in case of an I/O error
	 */
	public int read(byte[] b, int off, int len) throws IOException {

		if (closed) {
			throw new IOException("Attempted read from closed stream.");
		}

		if (eof) {
			return -1;
		}
		if (state != CHUNK_DATA) {
			nextChunk();
			if (eof) {
				return -1;
			}
		}
		len = Math.min(len, chunkSize - pos);
		int bytesRead = in.read(b, off, len);
		if (bytesRead != -1) {
			pos += bytesRead;
			if (pos >= chunkSize) {
				state = CHUNK_CRLF;
			}
			return bytesRead;
		} else {
			eof = true;
			throw new IllegalArgumentException("Truncated chunk "
					+ "( expected size: " + chunkSize + "; actual size: " + pos
					+ ")");
		}
	}

	/**
	 * Read some bytes from the stream.
	 * 
	 * @param b
	 *            The byte array that will hold the contents from the stream.
	 * @return The number of bytes returned or -1 if the end of stream has been
	 *         reached.
	 * @throws IOException
	 *             in case of an I/O error
	 */
	public int read(byte[] b) throws IOException {
		return read(b, 0, b.length);
	}

	/**
	 * Read the next chunk.
	 * 
	 * @throws IOException
	 *             in case of an I/O error
	 */
	private void nextChunk() throws IOException {
		chunkSize = getChunkSize();
		if (chunkSize < 0) {
			throw new IllegalArgumentException("Negative chunk size");
		}
		state = CHUNK_DATA;
		pos = 0;
		if (chunkSize == 0) {
			eof = true;
			parseTrailerHeaders();
		}
	}

	/**
	 * Expects the stream to start with a chunksize in hex with optional
	 * comments after a semicolon. The line must end with a CRLF: "a3; some
	 * comment\r\n" Positions the stream at the start of the next line.
	 * 
	 * @param in
	 *            The new input stream.
	 * @param required
	 *            <tt>true<tt/> if a valid chunk must be present,
	 *                 <tt>false<tt/> otherwise.
	 * 
	 * @return the chunk size as integer
	 * 
	 * @throws IOException when the chunk size could not be parsed
	 */
	private int getChunkSize() throws IOException {
		int st = this.state;
		switch (st) {
		case CHUNK_CRLF:
			this.buffer = new StringBuffer();
			int i = readLine(this.in, this.buffer);
			if (i == -1) {
				return 0;
			}
			if (this.buffer.length() != 0) {
				throw new IllegalArgumentException(
						"Unexpected content at the end of chunk");
			}
			state = CHUNK_LEN;
			// $FALL-THROUGH$
		case CHUNK_LEN:
			this.buffer = new StringBuffer();
			i = readLine(this.in, this.buffer);
			if (i == -1) {
				return 0;
			}
			int separator = this.buffer.toString().indexOf(';');
			if (separator < 0) {
				separator = this.buffer.length();
			}
			try {
				return Integer.parseInt(this.buffer.substring(0, separator)
						.trim(), 16);
			} catch (NumberFormatException e) {
				throw new IllegalArgumentException("Bad chunk header");
			}
		default:
			throw new IllegalStateException("Inconsistent codec state");
		}
	}

	/**
	 * Reads and stores the Trailer headers.
	 * 
	 * @throws IOException
	 *             in case of an I/O error
	 */
	private void parseTrailerHeaders() throws IOException {
		while (true) {
			StringBuffer buf = new StringBuffer();
			int i = readLine(in, buf);
			if (i == -1)
				break;
			if (buf.length() < 1) {
				break;
			}
		}
	}

	/**
	 * Upon close, this reads the remainder of the chunked message, leaving the
	 * underlying socket at a position to start reading the next response
	 * without scanning.
	 * 
	 * @throws IOException
	 *             in case of an I/O error
	 */
	public void close() throws IOException {
		if (!closed) {
			try {
				if (!eof) {
					// read and discard the remainder of the message
					byte buffer[] = new byte[BUFFER_SIZE];
					while (read(buffer) >= 0) {
					}
				}
			} finally {
				eof = true;
				closed = true;
			}
		}
	}

	public static final int readLine(InputStream in, StringBuffer buf)
			throws IOException {
		boolean gotCR = false;
		while (true) {
			int x = in.read();
			if (x == -1)
				return (buf.length() > 0 ? buf.length() : -1);
			if (x == '\n') {
				if (gotCR) {
					return buf.length();
				}
			}
			if (x == '\r') {
				gotCR = true;
			} else {
				gotCR = false;
			}
			if (x != '\r')
				buf.append((char) x);
		}
	}

}