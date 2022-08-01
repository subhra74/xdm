using System;
using System.Threading;
using TraceLog;
using XDM.Core.Util;

namespace XDM.Core.Downloader
{
    public class SpeedLimiter
    {
        private long lastTick, lastBytes;
        private ManualResetEvent sleepHandle = new ManualResetEvent(false);
        private long lastChecked = Helpers.TickCount();
        private int cachedSpeedLimit = -2;

        public int SpeedLimit => cachedSpeedLimit;

        public void WakeIfSleeping()
        {
            this.sleepHandle.Set();
        }

        private int GetCachedSpeedLimit()
        {
            var now = Helpers.TickCount();
            if (now - lastChecked > 3000 || cachedSpeedLimit == -2)
            {
                lastChecked = now;
                cachedSpeedLimit = GetGlobalSpeedLimit();
            }
            return cachedSpeedLimit;
        }

        private int GetGlobalSpeedLimit()
        {
            int speedLimit = 0;
            lock (Config.Instance)
            {
                if (Config.Instance.EnableSpeedLimit && Config.Instance.DefaltDownloadSpeed > 0)
                {
                    speedLimit = Config.Instance.DefaltDownloadSpeed;
                }
            }
            return speedLimit;
        }

        public void ThrottleIfNeeded(IBaseDownloader downloader)
        {
            int speedLimit = GetCachedSpeedLimit();
            if (speedLimit < 1) return;
            if (lastBytes == 0 || lastTick == 0)
            {
                lastBytes = downloader.GetDownloaded();
                lastTick = Helpers.TickCount();
                return;
            }
            try
            {
                downloader.Lock.EnterWriteLock();
                var maxBytesPerMS = (double)speedLimit * 1024 / 1000;
                var now = Helpers.TickCount();
                var actualTimeSpent = now - lastTick;
                if (actualTimeSpent < 1) return;
                var bytes = downloader.GetDownloaded();
                var diff = bytes - lastBytes;
                lastBytes = bytes;
                lastTick = now;
                var expectedTimeSpent = diff / maxBytesPerMS;

                if (actualTimeSpent < expectedTimeSpent)
                {
                    try
                    {
                        sleep((int)Math.Ceiling(expectedTimeSpent - actualTimeSpent));
                    }
                    catch (Exception ex) { Log.Debug(ex, "Exception while throttling"); }
                }
            }
            finally
            {
                downloader.Lock.ExitWriteLock();
            }
        }

        private void sleep(int interval)
        {
            sleepHandle.WaitOne(interval);
        }
    }
}
