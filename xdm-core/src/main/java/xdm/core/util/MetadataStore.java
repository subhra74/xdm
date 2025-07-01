package xdm.core.util;

import xdm.core.downloaders.Metadata;

public final class MetadataStore {
  private MetadataStore() {}

  public static synchronized Metadata get(long id) {
    return null;
  }

  public static synchronized void save(Metadata metadata) {}
}
