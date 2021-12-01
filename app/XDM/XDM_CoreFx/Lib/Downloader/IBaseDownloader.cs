using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;

using XDM.Core.Lib.Common;

namespace XDM.Core.Lib.Common
{
    public interface IBaseDownloader
    {
        public bool IsCancelled { get; }
        public string? Id { get; }
        public long FileSize { get; }
        public string? TargetFile { get; }
        public string? TargetFileName { get; }
        public string Type { get; }
        public FileNameFetchMode FileNameFetchMode { get; }
        public Uri? PrimaryUrl { get; }

        event EventHandler Probed;
        event EventHandler Finished;
        event EventHandler Started;
        event EventHandler<ProgressResultEventArgs> ProgressChanged;
        event EventHandler<ProgressResultEventArgs> AssembingProgressChanged;
        event EventHandler Cancelled;
        event EventHandler<DownloadFailedEventArgs> Failed;

        public void Stop();
        public void Resume();
        public void Start();
        public void SaveForLater();
        public void SetFileName(string name, FileNameFetchMode fileNameFetchMode);
        public void SetTargetDirectory(string folder);
        public long GetDownloaded();
        public void UpdateSpeedLimit(bool enable, int limit);
        public int SpeedLimit { get; }
        public bool EnableSpeedLimit { get; }
    }
}
