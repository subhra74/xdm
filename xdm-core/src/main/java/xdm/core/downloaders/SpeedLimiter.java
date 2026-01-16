//package xdm.core.downloaders;
//
//import java.util.concurrent.CountDownLatch;
//import java.util.concurrent.TimeUnit;
//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;
//import xdm.core.Config;
//
//public class SpeedLimiter {
//  private long lastTick, lastBytes;
//  private long lastChecked = System.currentTimeMillis();
//  private int cachedSpeedLimit = -2;
//  private CountDownLatch sleepHandle = new CountDownLatch(1);
//  private AbstractDownloader downloader;
//  private final Logger logger = LoggerFactory.getLogger(SpeedLimiter.class);
//
//  public SpeedLimiter(AbstractDownloader downloader) {
//    this.downloader = downloader;
//  }
//
//  private int getCachedSpeedLimit() {
//    long now = System.currentTimeMillis();
//    if (now - lastChecked > 3000 || cachedSpeedLimit == -2) {
//      lastChecked = now;
//      cachedSpeedLimit = getGlobalSpeedLimit();
//    }
//    return cachedSpeedLimit;
//  }
//
//  private int getGlobalSpeedLimit() {
//    int speedLimit = 0;
//    Config config = Config.getInstance();
//    synchronized (config) {
//      if (config.getSpeedLimit() > 0) {
//        speedLimit = config.getSpeedLimit();
//      }
//    }
//    return speedLimit;
//  }
//
//  public void throttleIfNeeded() {
//    int speedLimit = getCachedSpeedLimit();
//    if (speedLimit < 1) return;
//    if (this.downloader == null) {
//      return;
//    }
//    if (lastBytes == 0 || lastTick == 0) {
//      lastBytes = downloader.getDownloaded();
//      lastTick = System.currentTimeMillis();
//      return;
//    }
//    synchronized (this.downloader) {
//      double maxBytesPerMS = (double) speedLimit * 1024 / 1000;
//      long now = System.currentTimeMillis();
//      long actualTimeSpent = now - lastTick;
//      if (actualTimeSpent < 1) return;
//      long bytes = downloader.getDownloaded();
//      long diff = bytes - lastBytes;
//      lastBytes = bytes;
//      lastTick = now;
//      double expectedTimeSpent = maxBytesPerMS > 0 ? diff / maxBytesPerMS : 0;
//      if (actualTimeSpent < expectedTimeSpent) {
//        try {
//          sleep((int) Math.ceil(expectedTimeSpent - actualTimeSpent));
//        } catch (Exception ex) {
//          logger.info("Exception while throttling");
//        }
//      }
//    }
//  }
//
//  private void sleep(int interval) {
//    try {
//      sleepHandle.await(interval, TimeUnit.MILLISECONDS);
//    } catch (Exception ex) {
//      // Swallow
//      ex.printStackTrace();
//    }
//  }
//
//  public void wakeIfSleeping() {
//    sleepHandle.countDown();
//  }
//}
