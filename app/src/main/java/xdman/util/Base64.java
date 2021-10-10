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

package xdman.util;

public class Base64 {

	private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

	public static String encode(byte[] bytes) {
		int length = bytes.length;
		if (length == 0)
			return "";
		StringBuilder builder = new StringBuilder((int) Math.ceil((double) length / 3d) * 4);
		int remainder = length % 3;
		length -= remainder;
		int block;
		int i = 0;
		while (i < length) {
			block = ((bytes[i++] & 0xff) << 16) | ((bytes[i++] & 0xff) << 8) | (bytes[i++] & 0xff);
			builder.append(ALPHABET.charAt(block >>> 18));
			builder.append(ALPHABET.charAt((block >>> 12) & 0x3f));
			builder.append(ALPHABET.charAt((block >>> 6) & 0x3f));
			builder.append(ALPHABET.charAt(block & 0x3f));
		}
		if (remainder == 0)
			return builder.toString();
		if (remainder == 1) {
			block = (bytes[i] & 0xff) << 4;
			builder.append(ALPHABET.charAt(block >>> 6));
			builder.append(ALPHABET.charAt(block & 0x3f));
			builder.append("==");
			return builder.toString();
		}
		block = (((bytes[i++] & 0xff) << 8) | ((bytes[i]) & 0xff)) << 2;
		builder.append(ALPHABET.charAt(block >>> 12));
		builder.append(ALPHABET.charAt((block >>> 6) & 0x3f));
		builder.append(ALPHABET.charAt(block & 0x3f));
		builder.append("=");
		return builder.toString();
	}

	public static byte[] decode(String string) {
		int length = string.length();
		if (length == 0)
			return new byte[0];
		int pad = (string.charAt(length - 2) == '=') ? 2 : (string.charAt(length - 1) == '=') ? 1 : 0;
		int size = length * 3 / 4 - pad;
		byte[] buffer = new byte[size];
		int block;
		int i = 0;
		int index = 0;
		while (i < length) {
			block = (ALPHABET.indexOf(string.charAt(i++)) & 0xff) << 18
					| (ALPHABET.indexOf(string.charAt(i++)) & 0xff) << 12
					| (ALPHABET.indexOf(string.charAt(i++)) & 0xff) << 6
					| (ALPHABET.indexOf(string.charAt(i++)) & 0xff);
			buffer[index++] = (byte) (block >>> 16);
			if (index < size)
				buffer[index++] = (byte) ((block >>> 8) & 0xff);
			if (index < size)
				buffer[index++] = (byte) (block & 0xff);
		}
		return buffer;
	}

}
