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

package xdman.downloaders.metadata.manifests;

import java.io.FileReader;
import java.net.URL;
import java.util.ArrayList;
import java.util.Iterator;

import javax.xml.namespace.NamespaceContext;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

import org.tinylog.Logger;
import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import xdman.util.Base64;
import xdman.util.IOUtils;
import xdman.util.StringUtils;

@SuppressWarnings({"FieldCanBeLocal", "unused"})
public class F4MManifest {

	private static XPath xpath;
	private long selectedBitRate;
	private ArrayList<Fragment> fragTable;
	private ArrayList<Segment> segTable;
	private int duration;
	private long fromTimestamp;
	private int start;
	private boolean live;
	private int fragCount;
	private int segStart, fragStart;
	private int fragsPerSeg;
	private F4MMedia selectedMedia;
	private int segNum, fragNum;
	private String fragUrl, baseUrl;
	private int discontinuity;
	private String query;
	private String pv;

	private final String url;
	private final String file;

	public F4MManifest(String url, String file) {
		this.url = url;
		this.file = file;
	}

	public ArrayList<String> getMediaUrls() throws Exception {
		ArrayList<String> urlList = new ArrayList<>();
		fragTable = new ArrayList<>();
		segTable = new ArrayList<>();

		query = getQuery(url);
		parseDoc(loadDoc(file), url);
		segNum = segStart;
		fragNum = fragStart;
		if (start > 0) {
			segNum = getSegmentFromFragment(start);
			fragNum = start - 1;
			segStart = segNum;
			fragStart = fragNum;
		}
		Logger.info(fragNum + " " + fragCount);
		if (fragNum >= fragCount)
			throw new Exception("No fragment available for downloading");
		Logger.info("[F4M Parser: selectedMedia.url: " + selectedMedia.url);

		if (selectedMedia.getUrl().startsWith("http")) {
			Logger.info("============ " + selectedMedia.getUrl());
			fragUrl = selectedMedia.getUrl();
		} else {
			if (baseUrl.endsWith("/")) {
				fragUrl = baseUrl + selectedMedia.getUrl();
			} else {
				fragUrl = baseUrl + "/" + selectedMedia.getUrl();
			}
		}
		Logger.info("fragUrl: " + fragUrl + "\nfragCount: " + fragCount + " baseUrl: " + baseUrl);

		while (fragNum < fragCount) {
			Logger.info("Remaining: " + (fragCount - fragNum));
			fragNum++;
			segNum = getSegmentFromFragment(fragNum);

			int fragIndex = findFragmentInTable(fragNum);
			if (fragIndex >= 0)
				discontinuity = fragTable.get(fragIndex).discontinuityIndicator;
			else {
				for (Fragment fragment : fragTable) {
					if (fragment.firstFragment < fragNum)
						continue;
					discontinuity = fragment.discontinuityIndicator;
					break;
				}
			}
			if (discontinuity != 0) {
				Logger.info("Skipping fragment " + fragNum + " due to discontinuity, Type: " + discontinuity);
				continue;
			}
			String ___url = getFragmentUrl(segNum, fragNum);

			if (!StringUtils.isNullOrEmpty(query)) {
				if (___url.contains("?")) {
					___url += "&" + query;
				} else {
					___url += "?" + query;
				}
			}

			if (!StringUtils.isNullOrEmpty(pv)) {
				if (___url.contains("?")) {
					___url += "&" + pv;
				} else {
					___url += "?" + pv;
				}
			}

			Logger.info(___url);
			urlList.add(___url);
		}

		return urlList;
	}

	private void parseDoc(Document doc, String surl) throws XPathExpressionException {
		if (xpath == null) {
			initXPath();
		}

		baseUrl = xpath.evaluate("/ns:manifest/ns:baseURL", doc);
		if (StringUtils.isNullOrEmptyOrBlank(baseUrl)) {
			try {
				URL url = new URL(surl);
				StringBuilder sb = new StringBuilder();
				sb.append(url.getProtocol());
				sb.append("://");
				sb.append(url.getHost());
				int port = url.getPort();
				if (port < 1) {
					port = url.getDefaultPort();
				}
				sb.append(port == 80 ? "" : port);
				String path = url.getPath();
				String[] arr = path.split("/");
				for (int i = 0; i < arr.length - 1; i++) {
					if (arr[i].length() > 0) {
						sb.append("/").append(arr[i]);
					}
				}
				baseUrl = sb.toString();
				Logger.info("*** URL: " + baseUrl);
			} catch (Exception e) {
				Logger.error(e);
			}
		}

		pv = xpath.evaluate("/ns:manifest/ns:pv-2.0", doc);

		NodeList mediaNodeList = (NodeList) xpath.evaluate("/ns:manifest/ns:media", doc, XPathConstants.NODESET);

		F4MMedia media = null;

		for (int i = 0; i < mediaNodeList.getLength(); i++) {
			Node mediaNode = mediaNodeList.item(i);
			NamedNodeMap attrMap = mediaNode.getAttributes();
			Node bitRateAttr = attrMap.getNamedItem("bitrate");
			long bitRate = 0;
			if (bitRateAttr != null) {
				bitRate = Long.parseLong(bitRateAttr.getNodeValue());
			}
			boolean mediaFound = false;
			if (this.selectedBitRate > 0) {
				if (this.selectedBitRate == bitRate) {
					mediaFound = true;
				}
			} else {
				mediaFound = true;
			}

			if (mediaFound) {
				media = new F4MMedia();
				media.setBaseUrl(baseUrl);
				media.setBitRate(bitRate);
				media.setUrl(attrMap.getNamedItem("url").getNodeValue());
				Node bootstrapInfoIdNode = attrMap.getNamedItem("bootstrapInfoId");

				String bootstrapInfoStr;

				if (bootstrapInfoIdNode != null) {
					String bootstrapInfoId = bootstrapInfoIdNode.getNodeValue();
					bootstrapInfoStr = xpath.evaluate("/ns:manifest/ns:bootstrapInfo[@id='" + bootstrapInfoId + "']",
							doc);
				} else {
					bootstrapInfoStr = xpath.evaluate("/ns:manifest/ns:bootstrapInfo", doc);
				}

				media.setBootstrap(Base64.decode(bootstrapInfoStr));
				break;
			}
		}

		if (media == null) {
			Logger.warn("Could not find media");
			return;
		}

		int pos = 0;

		BufferPointer ptr = new BufferPointer();
		ptr.setBuf(media.getBootstrap());
		ptr.setPos(pos);

		BoxInfo boxInfo = readBoxHeader(ptr);

		pos = ptr.getPos();
		String boxType = boxInfo.getBoxType();

		if (boxType.equals("abst"))
			parseBootstrapBox(media.bootstrap, pos);
		if (fragsPerSeg == 0)
			fragsPerSeg = fragCount;
		if (live) {
			fromTimestamp = -1;
			Logger.info("F4M Parser: [Live stream]");
		} else {
			Logger.warn("F4M Parser: [Not Live stream]");
		}
		Logger.info("F4M Parser: Start- " + start);
		selectedMedia = media;
	}

	public long[] getBitRates() {
		try {
			if (xpath == null) {
				initXPath();
			}
			Document doc = loadDoc(file);
			NodeList mediaNodeList = (NodeList) xpath.evaluate("/ns:manifest/ns:media", doc, XPathConstants.NODESET);
			if (mediaNodeList == null) {
				return null;
			}

			ArrayList<Long> bitRates = new ArrayList<>();

			for (int i = 0; i < mediaNodeList.getLength(); i++) {
				Node mediaNode = mediaNodeList.item(i);
				Node bitRateAttr = mediaNode.getAttributes().getNamedItem("bitrate");
				if (bitRateAttr != null) {
					bitRates.add(Long.parseLong(bitRateAttr.getNodeValue()));
				}
			}

			long[] bitRateArr = new long[bitRates.size()];

			for (int i = 0; i < bitRateArr.length; i++) {
				bitRateArr[i] = bitRates.get(i);
			}

			return bitRateArr;
		} catch (Exception e) {
			Logger.error(e);
		}
		return null;
	}

	private Document loadDoc(String fileName) {
		FileReader r = null;
		try {
			r = new FileReader(fileName);
			DocumentBuilderFactory domFactory = DocumentBuilderFactory.newInstance();
			domFactory.setNamespaceAware(true);
			DocumentBuilder builder = domFactory.newDocumentBuilder();
			return builder.parse(new InputSource(r));
		} catch (Exception e) {
			Logger.error(e);
		} finally {
			IOUtils.closeFlow(r);
		}
		return null;
	}

	private static void initXPath() {
		xpath = XPathFactory.newInstance().newXPath();
		xpath.setNamespaceContext(new NamespaceContext() {

			@Override
			public Iterator<String> getPrefixes(String s) {
				return null;
			}

			@Override
			public String getPrefix(String s) {
				return null;
			}

			@Override
			public String getNamespaceURI(String s) {
				if ("ns".equals(s)) {
					return "http://ns.adobe.com/f4m/1.0";
				}
				return null;
			}
		});
	}

	private int getSegmentFromFragment(int fragN) {
		if ((segTable.size() == 0) || (fragTable.size() == 0))
			return 1;
		Segment firstSegment = segTable.get(0);
		Segment lastSegment = segTable.get(segTable.size() - 1);
		Fragment firstFragment = fragTable.get(0);
		fragTable.get(fragTable.size() - 1);

		if (segTable.size() == 1)
			return firstSegment.firstSegment;
		else {
			Segment seg, prev = firstSegment;
			int end, start = firstFragment.firstFragment;
			for (int i = firstSegment.firstSegment; i <= lastSegment.firstSegment; i++) {
				if (segTable.size() >= (i - 1))
					seg = segTable.get(i);
				else
					seg = prev;
				end = start + seg.fragmentsPerSegment;
				if ((fragN >= start) && (fragN < end))
					return i;
				prev = seg;
				start = end;
			}
		}
		return lastSegment.firstSegment;
	}

	private void parseBootstrapBox(byte[] bootstrapInfo, int pos) {
		Logger.info("parsing abst");
		live = false;
		readByte(bootstrapInfo, pos);
		readInt24(bootstrapInfo, pos + 1);
		readInt32(bootstrapInfo, pos + 4);
		int b = readByte(bootstrapInfo, pos + 8);
		int update = (b & 0x10) >> 4;
		if (((b & 0x20) >> 5) > 0) {
			live = true;
		}
		if (update == 0) {
			segTable.clear();
			fragTable.clear();
		}
		readInt32(bootstrapInfo, pos + 9);
		readInt64(bootstrapInfo, 13);
		readInt64(bootstrapInfo, 21);
		pos += 29;

		BufferPointer bPtr = new BufferPointer();
		bPtr.setBuf(bootstrapInfo);
		bPtr.setPos(pos);

		String movieIdentifier = readString(bPtr);
		Logger.info("[F4M Parser- movieIdentifier: " + movieIdentifier);
		pos = bPtr.getPos();

		int serverEntryCount = readByte(bootstrapInfo, pos++);
		bPtr.setPos(pos);

		for (int i = 0; i < serverEntryCount; i++)
			readString(bPtr);

		int qualityEntryCount = readByte(bootstrapInfo, pos++);
		bPtr.setPos(pos);

		for (int i = 0; i < qualityEntryCount; i++)
			readString(bPtr);
		readString(bPtr);
		readString(bPtr);
		pos = bPtr.getPos();
		int segRunTableCount = readByte(bootstrapInfo, pos++);

		long boxSize;

		BufferPointer ptr = new BufferPointer();
		ptr.setBuf(bootstrapInfo);

		for (int i = 0; i < segRunTableCount; i++) {
			ptr.setPos(pos);
			String boxType;

			BoxInfo boxInfo = readBoxHeader(ptr);
			boxSize = boxInfo.getBoxSize();
			boxType = boxInfo.getBoxType();
			pos = ptr.getPos();

			if (boxType.equals("asrt"))
				parseAsrtBox(bootstrapInfo, pos);
			pos += boxSize;
		}

		int fragRunTableCount = readByte(bootstrapInfo, pos++);

		for (int i = 0; i < fragRunTableCount; i++) {
			ptr.setPos(pos);
			BoxInfo boxInfo = readBoxHeader(ptr);
			pos = ptr.getPos();
			boxSize = boxInfo.getBoxSize();
			String boxType = boxInfo.getBoxType();
			Logger.info("555 " + boxType + " " + boxSize);
			if (boxType.equals("afrt"))
				parseAfrtBox(bootstrapInfo, pos);
			pos += (int) boxSize;
		}

		parseSegAndFragTable();
	}

	private void parseSegAndFragTable() {
		Logger.info("parseSegAndFragTable called");
		if ((segTable.size() == 0) || (fragTable.size() == 0)) {
			Logger.info("return as zero " + segTable.size() + " " + fragTable.size());
			return;
		}
		Segment firstSegment = segTable.get(0);
		Segment lastSegment = segTable.get(segTable.size() - 1);
		Fragment firstFragment = fragTable.get(0);
		Fragment lastFragment = fragTable.get(fragTable.size() - 1);

		if ((lastFragment.fragmentDuration == 0) && (lastFragment.discontinuityIndicator == 0)) {
			live = false;
			fragTable.remove(fragTable.size() - 1);
			if (fragTable.size() > 0)
				lastFragment = fragTable.get(fragTable.size() - 1);
		}

		boolean invalidFragCount = false;
		Segment prev = segTable.get(0);
		fragCount = prev.fragmentsPerSegment;
		for (Segment current : segTable) {
			fragCount += (current.firstSegment - prev.firstSegment - 1) * prev.fragmentsPerSegment;
			fragCount += current.fragmentsPerSegment;
			prev = current;
		}
		if ((fragCount & 0x80000000) == 0)
			fragCount += firstFragment.firstFragment - 1;
		if ((fragCount & 0x80000000) != 0) {
			fragCount = 0;
			invalidFragCount = true;
		}
		if (fragCount < lastFragment.firstFragment)
			fragCount = lastFragment.firstFragment;

		if (segStart < 0) {
			if (live)
				segStart = lastSegment.firstSegment;
			else
				segStart = firstSegment.firstSegment;
			if (segStart < 1)
				segStart = 1;
		}
		if (fragStart < 0) {
			if (live && !invalidFragCount)
				fragStart = fragCount - 2;
			else
				fragStart = firstFragment.firstFragment - 1;
			if (fragStart < 0)
				fragStart = 0;
		}
	}

	private void parseAsrtBox(byte[] asrt, int pos) {
		Logger.info("parsing asrt");
		readByte(asrt, pos);
		readInt24(asrt, pos + 1);
		int qualityEntryCount = readByte(asrt, pos + 4);
		segTable.clear();
		pos += 5;
		BufferPointer bPtr = new BufferPointer();
		for (int i = 0; i < qualityEntryCount; i++) {
			bPtr.setBuf(asrt);
			bPtr.setPos(pos);
			readString(bPtr);
			pos = bPtr.getPos();
		}
		int segCount = (int) readInt32(asrt, pos);
		pos += 4;
		Logger.info("segcount: " + segCount);
		for (int i = 0; i < segCount; i++) {
			int firstSegment = (int) readInt32(asrt, pos);
			Segment segEntry = new Segment();
			segEntry.firstSegment = firstSegment;
			segEntry.fragmentsPerSegment = (int) readInt32(asrt, pos + 4);
			if ((segEntry.fragmentsPerSegment & 0x80000000L) > 0)
				segEntry.fragmentsPerSegment = 0;
			pos += 8;
			segTable.add(segEntry);
		}
	}

	private void parseAfrtBox(byte[] afrt, int pos) {
		Logger.info("Parse afrt");
		fragTable.clear();
		readByte(afrt, pos);
		readInt24(afrt, pos + 1);
		readInt32(afrt, pos + 4);
		int qualityEntryCount = readByte(afrt, pos + 8);
		pos += 9;
		BufferPointer args = new BufferPointer();
		for (int i = 0; i < qualityEntryCount; i++) {
			args.setBuf(afrt);
			args.setPos(pos);
			readString(args);
			pos = args.getPos();
		}
		int fragEntries = (int) readInt32(afrt, pos);
		pos += 4;
		for (int i = 0; i < fragEntries; i++) {
			int firstFragment = (int) readInt32(afrt, pos);
			Fragment fragEntry = new Fragment();
			fragEntry.firstFragment = firstFragment;
			fragEntry.firstFragmentTimestamp = readInt64(afrt, pos + 4);
			fragEntry.fragmentDuration = (int) readInt32(afrt, pos + 12);
			duration += fragEntry.fragmentDuration;
			fragEntry.discontinuityIndicator = 0;
			pos += 16;
			if (fragEntry.fragmentDuration == 0)
				fragEntry.discontinuityIndicator = readByte(afrt, pos++);
			fragTable.add(fragEntry);
			if ((fromTimestamp > 0) && (fragEntry.firstFragmentTimestamp > 0)
					&& (fragEntry.firstFragmentTimestamp < fromTimestamp))
				start = fragEntry.firstFragment + 1;
		}
	}

	private BoxInfo readBoxHeader(BufferPointer ptr) {
		int pos = ptr.getPos();
		byte[] bytesData = ptr.getBuf();
		StringBuilder boxType = new StringBuilder();
		long boxSize;
		boxSize = readInt32(bytesData, pos);
		boxType.append(readStringBytes(bytesData, pos + 4, 4));
		if (boxSize == 1) {
			boxSize = readInt64(bytesData, pos + 8) - 16;
			pos += 16;
		} else {
			boxSize -= 8;
			pos += 8;
		}
		ptr.setPos(pos);
		BoxInfo boxInfo = new BoxInfo();
		boxInfo.setBoxSize(boxSize);
		boxInfo.setBoxType(boxType.toString());
		return boxInfo;
	}

	private String readStringBytes(byte[] bytesData, int pos, long len) {
		StringBuilder resultValue = new StringBuilder();
		for (int i = 0; i < len; i++) {
			resultValue.append((char) bytesData[pos + i]);
		}
		return resultValue.toString();
	}

	private String readString(BufferPointer bufPtr) {
		byte[] bytesData = bufPtr.getBuf();
		int pos = bufPtr.getPos();
		StringBuilder resultValue = new StringBuilder();
		int bytesCount = bytesData.length;
		while ((pos < bytesCount) && (bytesData[pos] != 0)) {
			resultValue.append((char) bytesData[pos]);
			pos++;
		}
		pos++;
		bufPtr.setPos(pos);
		return resultValue.toString();
	}

	private int readByte(byte[] data, int pos) {
		return data[pos] & 0xFF;
	}

	private long readInt24(byte[] data, int pos) {
		long iValLo = (data[pos + 2] & 0xFF + ((data[pos + 1] & 0xFF) * 256));
		long iValHi = data[pos] & 0xFF;
		return iValLo + (iValHi * 65536);
	}

	private static long readInt32(byte[] data, int pos) {
		long iValLo = ((long) (data[pos + 3] & 0xFF) + (long) (data[pos + 2] & 0xFF) * 256);
		long iValHi = ((long) (data[pos + 1] & 0xFF) + ((long) (data[pos] & 0xFF) * 256));
		return iValLo + (iValHi * 65536);
	}

	private static long readInt64(byte[] data, int pos) {
		long iValLo = readInt32(data, pos + 4);
		long iValHi = readInt32(data, pos);
		return iValLo + (iValHi * 4294967296L);
	}

	private int findFragmentInTable(int needle) {
		for (int i = 0; i < fragTable.size(); i++) {
			if (fragTable.get(i).firstFragment == needle) {
				return i;
			}
		}
		return -1;
	}

	private String getQuery(String url) {
		int index = url.indexOf('?');
		if (index < 0) {
			return "";
		}
		return url.substring(index + 1);
	}

	private String getFragmentUrl(int segNum, int fragNum) {
		return fragUrl + "Seg" + segNum + "-Frag" + fragNum;
	}

	@SuppressWarnings("unused")
	static class Segment {
		private int firstSegment;
		private int fragmentsPerSegment;

		public int getFirstSegment() {
			return firstSegment;
		}

		public void setFirstSegment(int firstSegment) {
			this.firstSegment = firstSegment;
		}

		public int getFragmentsPerSegment() {
			return fragmentsPerSegment;
		}

		public void setFragmentsPerSegment(int fragmentsPerSegment) {
			this.fragmentsPerSegment = fragmentsPerSegment;
		}
	}

	@SuppressWarnings({"unused", "InnerClassMayBeStatic"})
	class Fragment {
		private int firstFragment;
		private long firstFragmentTimestamp;
		private int fragmentDuration;
		private int discontinuityIndicator;

		public int getFirstFragment() {
			return firstFragment;
		}

		public void setFirstFragment(int firstFragment) {
			this.firstFragment = firstFragment;
		}

		public long getFirstFragmentTimestamp() {
			return firstFragmentTimestamp;
		}

		public void setFirstFragmentTimestamp(long firstFragmentTimestamp) {
			this.firstFragmentTimestamp = firstFragmentTimestamp;
		}

		public int getFragmentDuration() {
			return fragmentDuration;
		}

		public void setFragmentDuration(int fragmentDuration) {
			this.fragmentDuration = fragmentDuration;
		}

		public int getDiscontinuityIndicator() {
			return discontinuityIndicator;
		}

		public void setDiscontinuityIndicator(int discontinuityIndicator) {
			this.discontinuityIndicator = discontinuityIndicator;
		}
	}

	static class BoxInfo {
		private String boxType;
		private long boxSize;

		public String getBoxType() {
			return boxType;
		}

		public void setBoxType(String boxType) {
			this.boxType = boxType;
		}

		public long getBoxSize() {
			return boxSize;
		}

		public void setBoxSize(long boxSize) {
			this.boxSize = boxSize;
		}
	}

	static class BufferPointer {
		private byte[] buf;
		private int pos;

		public byte[] getBuf() {
			return buf;
		}

		public void setBuf(byte[] buf) {
			this.buf = buf;
		}

		public int getPos() {
			return pos;
		}

		public void setPos(int pos) {
			this.pos = pos;
		}
	}

	@SuppressWarnings("unused")
	static class F4MMedia {
		private String baseUrl;
		private String url;
		private String bootstrapUrl;
		private byte[] bootstrap;
		private byte[] metadata;
		private long bitRate;

		public String getBaseUrl() {
			return baseUrl;
		}

		public void setBaseUrl(String baseUrl) {
			this.baseUrl = baseUrl;
		}

		public String getUrl() {
			return url;
		}

		public void setUrl(String url) {
			this.url = url;
		}

		public String getBootstrapUrl() {
			return bootstrapUrl;
		}

		public void setBootstrapUrl(String bootstrapUrl) {
			this.bootstrapUrl = bootstrapUrl;
		}

		public byte[] getBootstrap() {
			return bootstrap;
		}

		public void setBootstrap(byte[] bootstrap) {
			this.bootstrap = bootstrap;
		}

		public byte[] getMetadata() {
			return metadata;
		}

		public void setMetadata(byte[] metadata) {
			this.metadata = metadata;
		}

		public long getBitRate() {
			return bitRate;
		}

		public void setBitRate(long bitRate) {
			this.bitRate = bitRate;
		}
	}

	public int getDuration() {
		return duration;
	}

	public void setDuration(int duration) {
		this.duration = duration;
	}

	public long getSelectedBitRate() {
		return selectedBitRate;
	}

	public void setSelectedBitRate(long selectedBitRate) {
		this.selectedBitRate = selectedBitRate;
	}

}
