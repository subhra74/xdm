package xdm.core.util;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public final class SerializationUtils {
  private SerializationUtils() {}

  public static long readLong(DataInputStream inputStream, long defaultValue) throws IOException {
    if (inputStream.readBoolean()) {
      return inputStream.readLong();
    }
    return defaultValue;
  }

  public static long readInt(DataInputStream inputStream, int defaultValue) throws IOException {
    if (inputStream.readBoolean()) {
      return inputStream.readLong();
    }
    return defaultValue;
  }

  public static Boolean readBoolean(DataInputStream inputStream, boolean defaultValue)
      throws IOException {
    if (inputStream.readBoolean()) {
      return inputStream.readBoolean();
    }
    return defaultValue;
  }

  public static String readStr(DataInputStream inputStream, String defaultValue)
      throws IOException {
    if (inputStream.readBoolean()) {
      return inputStream.readUTF();
    }
    return defaultValue;
  }

  public static String readStr(DataInputStream inputStream) throws IOException {
    return readStr(inputStream, null);
  }

  public static void writeNullable(Object value, DataOutputStream outputStream) throws IOException {
    if (value == null) {
      outputStream.writeBoolean(false);
      return;
    }
    if (value instanceof String) {
      String val = (String) value;
      boolean hasValue = !StringUtils.isNullOrEmptyOrBlank(val);
      outputStream.writeBoolean(hasValue);
      if (hasValue) {
        outputStream.writeUTF(val);
      }
    }

    if (value instanceof Long) {
      outputStream.writeBoolean(true);
      outputStream.writeLong((Long) value);
    }

    if (value instanceof Integer) {
      outputStream.writeBoolean(true);
      outputStream.writeLong((Integer) value);
    }

    if (value instanceof Boolean) {
      outputStream.writeBoolean(true);
      outputStream.writeBoolean((Boolean) value);
    }
  }
}
