//package xdm.core.util;
//
//import java.io.*;
//import java.util.function.Consumer;
//
//public class TransactedIO {
//
//  public interface TransactedWriter {
//    void write(DataOutputStream writer) throws IOException;
//  }
//
//  public interface TransactedReader {
//    void read(DataInputStream writer) throws IOException;
//  }
//
//  public static boolean write(
//      String folder, String file, TransactedWriter exec, Consumer<IOException> error) {
//    File tmp = new File(folder, file + ".bak");
//    File out = new File(folder, file);
//    try (DataOutputStream writer = new DataOutputStream(new FileOutputStream(tmp))) {
//      exec.write(writer);
//      out.delete();
//      tmp.renameTo(out);
//      return true;
//    } catch (IOException ex) {
//      if (error != null) {
//        error.accept(ex);
//      }
//      return false;
//    }
//  }
//
//  public static boolean read(
//      String folder, String file, TransactedReader exec, Consumer<IOException> error) {
//    File input = new File(folder, file);
//    if (!input.exists()) {
//      input = new File(folder, file + ".bak");
//      if (!input.exists()) {
//        return false;
//      }
//    }
//    try (DataInputStream reader = new DataInputStream(new FileInputStream(input))) {
//      exec.read(reader);
//      return true;
//    } catch (IOException ex) {
//      if (error != null) {
//        error.accept(ex);
//      }
//      return false;
//    }
//  }
//}
