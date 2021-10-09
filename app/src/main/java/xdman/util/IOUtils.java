package xdman.util;

import org.tinylog.Logger;

import java.io.Closeable;
import java.io.IOException;

/**
 * This class has functionalities to operate with outgoing and incoming flows.
 *
 * @version 1.0
 */
public class IOUtils {

    /**
     * This method closes a data flow after verification.
     *
     * @param stream source where the data flow comes from
     */
    public static void closeFlow(Closeable stream) {
        if (stream != null) {
            try {
                stream.close();
            } catch (IOException ex) {
                Logger.error(ex);
            }
        }
    }

}
