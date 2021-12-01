using System;
using System.Threading;
using TraceLog;
using XDM.Core.Lib.Util;

namespace XDM.Core.Lib.Common
{
    public class SpeedLimiter
    {
        private long lastTick, lastBytes;
        private ManualResetEvent sleepHandle = new ManualResetEvent(false);

        public void WakeIfSleeping()
        {
            this.sleepHandle.Set();
        }

        public void ThrottleIfNeeded(IBaseDownloader downloader, int speedLimit)
        {
            //Log.Debug("==========speed limit: " + speedLimit);
            if (speedLimit < 1) return;
            if (lastBytes == 0 || lastTick == 0)
            {
                lastBytes = downloader.GetDownloaded();
                lastTick = Helpers.TickCount();
                return;
            }
            var maxBytesPerMS = (double)speedLimit * 1024 / 1000;
            var now = Helpers.TickCount();
            var actualTimeSpent = now - lastTick;
            if (actualTimeSpent < 1) return;
            var bytes = downloader.GetDownloaded();
            var diff = bytes - lastBytes;
            lastBytes = bytes;
            lastTick = now;
            var expectedTimeSpent = diff / maxBytesPerMS;
            //Log.Debug("==========expectedTimeSpent: " + expectedTimeSpent + " actualTimeSpent: " + actualTimeSpent);

            if (actualTimeSpent < expectedTimeSpent)
            {
                try
                {
                    sleep((int)Math.Ceiling(expectedTimeSpent - actualTimeSpent));
                }
                catch (Exception ex) { Log.Debug(ex, "Exception while throttling"); }
            }
        }

        private void sleep(int interval)
        {
            sleepHandle.WaitOne(interval);
        }
    }
}
