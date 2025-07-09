package xdm.core.util;

import xdm.core.downloaders.Metadata;
import xdm.core.downloaders.http.HttpMetadata;

import java.io.*;

public final class MetadataStore {
  private static final int HTTP_META = 1;

  private MetadataStore() {}

  public static synchronized Metadata get(long id) {
    try (DataInputStream inputStream =
        new DataInputStream(
            new FileInputStream(new File(PlatformUtils.getMetaDir(), id + ".meta")))) {
      int type = inputStream.readInt();
      Metadata metadata;
      if (type == HTTP_META) {
        metadata = new HttpMetadata();
      } else {
        return null;
      }
      metadata.read(inputStream);
      return metadata;
    } catch (Exception ex) {
      Logger.log(ex);
    }
    return null;
  }

  public static synchronized void save(Metadata metadata) {
    File folder = new File(PlatformUtils.getMetaDir());
    if (!folder.exists()) {
      folder.mkdirs();
    }
    try (DataOutputStream outputStream =
        new DataOutputStream(new FileOutputStream(new File(folder, metadata.getId() + ".meta")))) {
      if (metadata instanceof HttpMetadata) {
        outputStream.writeInt(HTTP_META);
      }
      metadata.save(outputStream);
    } catch (Exception ex) {
      Logger.log(ex);
    }
  }
}
