using Newtonsoft.Json;
using System;
using XDM.Core.Downloader;

namespace XDM.Core
{
    public abstract class DownloadItemBase : IComparable
    {
        public string Id { get; set; }

        public string Name { get; set; }

        public long Size { get; set; }

        public string? TargetDir { get; set; }

        public DateTime DateAdded { get; set; }

        public string DownloadType { get; set; }

        public FileNameFetchMode FileNameFetchMode { get; set; }

        public string PrimaryUrl { get; set; }

        public string RefererUrl { get; set; }

        public AuthenticationInfo? Authentication { get; set; }

        public ProxyInfo? Proxy { get; set; }

        public int MaxSpeedLimitInKiB { get; set; }

        public int CompareTo(object? obj)
        {
            if (obj == null) return 1;
            if (obj is DownloadItemBase other)
                return this.Name.CompareTo(other.Name);
            else
                throw new ArgumentException("Object is not a DownloadItemBase");
        }

        public override string ToString()
        {
            return Name ?? "";
        }
    }

    public class InProgressDownloadItem
        : DownloadItemBase
    {
        public int Progress { get; set; }

        public DownloadStatus Status { get; set; }

        public string? DownloadSpeed { get; set; }

        public string? ETA { get; set; }
    }

    public class FinishedDownloadItem : DownloadItemBase
    {
    }

    public enum DownloadStatus
    {
        Downloading, Stopped, Finished, Waiting
    }
}