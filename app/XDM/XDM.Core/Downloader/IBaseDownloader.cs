using System;

using XDM.Core;

namespace XDM.Core.Downloader
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
        public void SetTargetDirectory(string? folder);
        public long GetTotalDownloaded();
        public long GetDownloaded();
    }
}
