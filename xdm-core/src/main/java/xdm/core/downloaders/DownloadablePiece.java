//package xdm.core.downloaders;
//
//import java.io.DataInputStream;
//import java.io.DataOutputStream;
//import java.io.IOException;
//import java.util.AbstractMap;
//import lombok.AllArgsConstructor;
//import lombok.Builder;
//import lombok.Data;
//
//@Data
//@Builder
//@AllArgsConstructor
//public class DownloadablePiece {
//  private long sequence;
//  private String id;
//  private long downloaded;
//  private long length;
//  private PieceStatus status;
//  private String tag;
//  private String contentType;
//  private String url;
//  private AbstractMap.SimpleEntry<Long, Long> byteRange;
//  private String keyUrl;
//  private String iv;
//
//  private DownloadablePiece() {}
//
//  public static DownloadablePiece read(DataInputStream ds) throws IOException {
//    DownloadablePiece pc = new DownloadablePiece();
//    pc.setId(ds.readUTF());
//    pc.setUrl(ds.readUTF());
//    pc.setStatus(PieceStatus.valueOf(ds.readUTF()));
//    pc.setSequence(ds.readLong());
//    pc.setDownloaded(ds.readLong());
//    pc.setLength(ds.readLong());
//    pc.setTag(readConditional(ds));
//    pc.setContentType(readConditional(ds));
//    pc.setKeyUrl(readConditional(ds));
//    pc.setIv(readConditional(ds));
//    if (ds.readBoolean()) {
//      pc.byteRange = new AbstractMap.SimpleEntry<>(ds.readLong(), ds.readLong());
//    }
//    return pc;
//  }
//
//  public static void write(DownloadablePiece pc, DataOutputStream ds) throws IOException {
//    ds.writeUTF(pc.id);
//    ds.writeUTF(pc.url);
//    ds.writeUTF(pc.status.toString());
//    ds.writeLong(pc.sequence);
//    ds.writeLong(pc.downloaded);
//    ds.writeLong(pc.length);
//    writeConditional(pc.tag, ds);
//    writeConditional(pc.contentType, ds);
//    writeConditional(pc.keyUrl, ds);
//    writeConditional(pc.iv, ds);
//    ds.writeBoolean(pc.byteRange != null);
//    if (pc.byteRange != null) {
//      ds.writeLong(pc.byteRange.getKey());
//      ds.writeLong(pc.byteRange.getValue());
//    }
//  }
//
//  private static void writeConditional(String value, DataOutputStream ds) throws IOException {
//    ds.writeBoolean(value != null);
//    if (value != null) {
//      ds.writeUTF(value);
//    }
//  }
//
//  private static String readConditional(DataInputStream ds) throws IOException {
//    if (ds.readBoolean()) {
//      return ds.readUTF();
//    }
//    return null;
//  }
//}
