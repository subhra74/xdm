//package xdm.core.util;
//
//import xdm.core.downloaders.Metadata;
//import xdm.core.downloaders.http.HttpSource;
//
//import java.io.*;
//
//public final class MetadataStore {
//  private static final int HTTP_META = 1;
//
//  private MetadataStore() {}
//
//  public static synchronized Metadata get(long id) {
//    String folder = PlatformUtils.getMetaDir();
//    String file = id + ".meta";
//    try (DataInputStream inputStream =
//        new DataInputStream(new FileInputStream(new File(folder, file)))) {
//      int type = inputStream.readInt();
//      Metadata metadata;
//      if (type == HTTP_META) {
//        metadata = new HttpSource();
//      } else {
//        return null;
//      }
//      metadata.read(inputStream);
//      return metadata;
//    } catch (Exception ex) {
//      Logger.info(ex);
//    }
//    return null;
//  }
//
//  public static synchronized void save(Metadata metadata) {
//    File folder = new File(PlatformUtils.getMetaDir());
//    String file = metadata.getId() + ".meta";
//    if (!folder.exists()) {
//      folder.mkdirs();
//    }
//    try (DataOutputStream outputStream =
//        new DataOutputStream(new FileOutputStream(new File(folder, file)))) {
//      if (metadata instanceof HttpSource) {
//        outputStream.writeInt(HTTP_META);
//      }
//      metadata.save(outputStream);
//    } catch (Exception ex) {
//      Logger.info(ex);
//    }
//  }
//}
